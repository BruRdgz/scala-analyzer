package scalaanalyzer.platform

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.injected.editor.DocumentWindow
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.{FileViewProvider, PsiDocumentManager, PsiFile}
import com.intellij.psi.impl.{BlockSupportImpl, BooleanRunnable, ChangedPsiRangeUtil, DocumentCommitKind, DocumentCommitProcessor, PsiDocumentManagerBase, PsiDocumentManagerEx}
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.util.InjectionUtils

import scala.jdk.CollectionConverters.*

/**
 * Commits document changes synchronously into existing PSI trees.
 *
 * Editor documents commit under the write lock, preview copies under a read. For each PSI file, the range that
 * changed since the last commit is reparsed, and the resulting difference is applied to the existing tree, which
 * sends the tree change events.
 */
final class HostDocumentCommitProcessor extends DocumentCommitProcessor {
  /**
   * Reparses changed ranges and applies their tree changes before completing the commit.
   *
   * The caller holds the write lock for physical files or the preview read scope for copies.
   *
   * @param document edited document
   * @param project project owning the document manager
   * @param psiFile file supplied by the platform; all cached view-provider files are committed
   */
  override def commitSynchronously(document: Document, project: Project, psiFile: PsiFile): Unit = {
    // The project's document manager is the host's `HostPsiDocumentManager`, which extends `PsiDocumentManagerBase`.
    val documents = PsiDocumentManager.getInstance(project).asInstanceOf[PsiDocumentManagerBase]
    val lastCommitted = documents.getLastCommittedText(document)
    val text = document.getImmutableCharSequence
    val applyChanges = for {
      viewProvider <- documents.getCachedViewProviders(document).asScala.toSeq
      file <- viewProvider.getAllFiles.asScala.toSeq
      changed <- Option(ChangedPsiRangeUtil.getChangedPsiRange(file, document, lastCommitted, text))
    } yield {
      val reparsed = BlockSupportImpl.reparse(file, file.getNode, changed, text, new EmptyProgressIndicator, lastCommitted)

      (() => {
        reparsed.log.doActualPsiChange(file)
        true
      }): BooleanRunnable
    }

    documents.finishCommit(document, applyChanges.asJava, java.util.List.of(), DocumentCommitKind.Synchronous.INSTANCE, "host edit")
  }

  /** Rejects asynchronous commits, because the host commits synchronously. */
  override def commitAsynchronously(project: Project, documents: PsiDocumentManagerEx, document: Document, reason: Any,
                                    modality: ModalityState): Unit =
    CallLog.unsupported("DocumentCommitProcessor.commitAsynchronously (the host commits synchronously)")

  /** Rejects lightweight commits, which the host does not support. */
  override def commitSynchronouslyLightweight(document: Document, project: Project): Unit =
    CallLog.unsupported("DocumentCommitProcessor.commitSynchronouslyLightweight")
}

/** Mirrors the platform's postponed-formatting unblock without installing its editor event multicaster. */
private[scalaanalyzer] final class HostPsiDocumentManager(project: Project) extends PsiDocumentManagerBase(project) {
  /**
   * Resolves a file's document, answering preview copies from their own view provider.
   *
   * File-level changes such as ScalaImportOptimizer ask the manager for their preview copy's document.
   *
   * @param file file whose document is requested
   * @return the document of the preview copy's view provider for a preview file; otherwise the platform's answer
   */
  override def getDocument(file: PsiFile): Document =
    if (IntentionPreviewUtils.isPreviewElement(file)) file.getViewProvider.getDocument
    else super.getDocument(file)

  /**
   * Makes the manager follow a document's changes, in place of the editor's global event multicaster.
   *
   * The priority collector records event ranges before the manager decides whether a commit is needed, so it is
   * registered first and removed last.
   *
   * @param document document whose changes the manager should observe
   * @return handle that removes both listeners when closed
   */
  def listenTo(document: Document): AutoCloseable = {
    val collector = new PriorityEventCollector

    document.addDocumentListener(collector)
    document.addDocumentListener(this)

    () => {
      document.removeDocumentListener(this)
      document.removeDocumentListener(collector)
    }
  }

  /**
   * Commits every uncommitted document on the calling thread.
   *
   * The platform commits from its event thread; the host commits where the change was made, under the write lock.
   */
  override def commitAllDocuments(): Unit = getUncommittedDocuments.foreach(commitDocument)

  /**
   * Tells whether postponed formatting still blocks a document.
   *
   * @param document document to check
   * @return `true` if any cached view provider of the document is locked by the postprocess-reformatting aspect
   */
  override def isDocumentBlockedByPsi(document: Document): Boolean = {
    val aspect = PostprocessReformattingAspect.getInstance(project)

    getCachedViewProviders(document).asScala.exists(aspect.isViewProviderLocked)
  }

  /**
   * Runs the formatting postponed on a document's view providers, so the document is no longer blocked.
   *
   * For an injected-language document window the providers of its delegate are used, unless they
   * format only injected code, in which case the window's own providers are used.
   *
   * @param document document whose postponed formatting is performed
   */
  override def doPostponedOperationsAndUnblockDocument(document: Document): Unit = {
    val aspect = PostprocessReformattingAspect.getInstance(project)
    val providers = document match {
      case window: DocumentWindow =>
        val top = getCachedViewProviders(window.getDelegate)
        if (top.asScala.exists(InjectionUtils.shouldFormatOnlyInjectedCode)) getCachedViewProviders(document)
        else top
      case _ => getCachedViewProviders(document)
    }

    providers.asScala.foreach(aspect.doPostponedFormatting)
  }

  /**
   * Checks that the postprocess-reformatting aspect allows changing the document, then defers to the platform.
   *
   * @param provider view provider whose unlocked document is about to change
   */
  override protected def beforeDocumentChangeOnUnlockedDocument(provider: FileViewProvider): Unit = {
    PostprocessReformattingAspect.getInstance(project).assertDocumentChangeIsAllowed(provider)
    super.beforeDocumentChangeOnUnlockedDocument(provider)
  }
}
