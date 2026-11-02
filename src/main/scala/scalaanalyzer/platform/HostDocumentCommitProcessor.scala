package scalaanalyzer.platform

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.impl.{BlockSupportImpl, BooleanRunnable, ChangedPsiRangeUtil, DocumentCommitKind, DocumentCommitProcessor, PsiDocumentManagerBase, PsiDocumentManagerEx}

import scala.jdk.CollectionConverters.*

/**
 * Commits document changes synchronously into existing PSI trees.
 *
 * Editor documents commit under
 * the write lock, preview copies under a read. For each PSI file, the range that changed since the last commit
 * is reparsed, and the resulting difference is applied to the existing tree, which sends the tree change events.
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

  override def commitAsynchronously(project: Project, documents: PsiDocumentManagerEx, document: Document, reason: Any,
                                    modality: ModalityState): Unit =
    CallLog.unsupported("DocumentCommitProcessor.commitAsynchronously (the host commits synchronously)")

  override def commitSynchronouslyLightweight(document: Document, project: Project): Unit =
    CallLog.unsupported("DocumentCommitProcessor.commitSynchronouslyLightweight")
}

/** Mirrors the platform's postponed-formatting unblock without installing its editor event multicaster. */
private[scalaanalyzer] final class HostPsiDocumentManager(project: Project) extends PsiDocumentManagerBase(project) {
  // File-level changes such as ScalaImportOptimizer ask the manager for their preview copy's document.
  override def getDocument(file: PsiFile): Document =
    if (com.intellij.codeInsight.intention.preview.IntentionPreviewUtils.isPreviewElement(file)) file.getViewProvider.getDocument
    else super.getDocument(file)

  /** The priority collector records event ranges before the manager decides whether a commit is needed. */
  def listenTo(document: Document): AutoCloseable = {
    val collector = new PriorityEventCollector
    document.addDocumentListener(collector)
    document.addDocumentListener(this)
    () => {
      document.removeDocumentListener(this)
      document.removeDocumentListener(collector)
    }
  }

  // The platform commits from its event thread; the host commits where the change was made, under the write lock.
  override def commitAllDocuments(): Unit = getUncommittedDocuments.foreach(commitDocument)

  override def isDocumentBlockedByPsi(document: Document): Boolean = {
    val aspect = com.intellij.psi.impl.source.PostprocessReformattingAspect.getInstance(project)
    getCachedViewProviders(document).asScala.exists(aspect.isViewProviderLocked)
  }

  override def doPostponedOperationsAndUnblockDocument(document: Document): Unit = {
    val aspect = com.intellij.psi.impl.source.PostprocessReformattingAspect.getInstance(project)
    val providers = document match {
      case window: com.intellij.injected.editor.DocumentWindow =>
        val top = getCachedViewProviders(window.getDelegate)
        if (top.asScala.exists(com.intellij.util.InjectionUtils.shouldFormatOnlyInjectedCode)) getCachedViewProviders(document)
        else top
      case _ => getCachedViewProviders(document)
    }
    providers.asScala.foreach(aspect.doPostponedFormatting)
  }

  override protected def beforeDocumentChangeOnUnlockedDocument(provider: com.intellij.psi.FileViewProvider): Unit = {
    com.intellij.psi.impl.source.PostprocessReformattingAspect.getInstance(project).assertDocumentChangeIsAllowed(provider)
    super.beforeDocumentChangeOnUnlockedDocument(provider)
  }
}
