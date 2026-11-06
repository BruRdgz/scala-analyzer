package scalaanalyzer.language

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.diff.comparison.{ByCharRt, ByWordRt, CancellationChecker, ComparisonPolicy}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Computable, TextRange}
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.codeStyle.{CodeStyleManager, CodeStyleSettingsManager}
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.project.ScalaFeatures

import scalaanalyzer.platform.{HostLock, HostPsiDocumentManager, RequestEditor}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Previews PSI changes on nonphysical copies and returns their text edits.
 *
 * The original file, its document and its indexes remain unchanged. A preview runs in a cancellable read: the
 * plugin's write actions on the copy run in place (see the host's `runWriteAction`), and an editor edit cancels it.
 * Changes take turns among themselves: the platform's PSI-to-document synchronizer keeps the document it synchronizes
 * in one slot for the whole project (`PsiToDocumentSynchronizer.mySyncDocument`), copies included.
 */
object ChangeEngine {
  /** Monitor that makes previews take turns, as described above. */
  private val changesInTurn = new Object

  /** Holds the resources of the preview running on this thread, so copies made by ModCommands are released with it. */
  private val copyResources = new ThreadLocal[Using.Manager]

  /**
   * A replacement within an original text snapshot.
   *
   * @param range UTF-16 start and exclusive end offsets in the original text
   * @param text replacement text, empty for a deletion
   */
  final case class Edit(range: TextRange, text: String)

  /**
   * The result of a previewed text transformation.
   *
   * @param text complete transformed text
   * @param edits replacements against the original, sorted, disjoint and non-touching
   */
  final case class Result(text: String, edits: Seq[Edit])

  /**
   * Applies a transformation to a preview copy and computes its edits.
   *
   * `modify` runs in a preview session whose editor shows the copy. Copy listeners and temporary editors are
   * released before returning; postponed formatting is drained even if the transformation fails or is cancelled.
   *
   * @tparam A transformation result type
   * @param file original Scala file to copy
   * @param modify transformation allowed to mutate only the preview copy
   * @return the changed text and edits, paired with the transformation's result
   */
  def run[A](file: ScalaFile)(modify: ScalaFile => A): (Result, A) =
    run(file: PsiFile) {
      case scala: ScalaFile => modify(scala)
      case other => throw new IllegalStateException(s"change copy is not a Scala file: ${other.getClass.getName}")
    }

  /**
   * Previews a source transformation using its own language's PSI.
   *
   * Uses the same preview session, commit listeners and postponed-operation cleanup as the Scala overload.
   *
   * @tparam A transformation result type
   * @param file original source file to copy
   * @param modify transformation allowed to mutate only the preview copy
   * @return changed text and edits, paired with the transformation's result
   */
  def run[A](file: PsiFile)(modify: PsiFile => A): (Result, A) = HostLock.query {
    changesInTurn.synchronized {
      ProgressManager.checkCanceled()
      withPreviewCopy(file) { copy =>
        val document = copy.getViewProvider.getDocument
        Using.Manager { resources =>
          val previous = copyResources.get()
          copyResources.set(resources)
          try {
            resources(listenTo(copy))
            // Insert handlers position the caret explicitly; inserting a closing delimiter must leave it before
            // that delimiter.
            val editor = resources(new RequestEditor(document, copy.getProject, 0, caretRightBiased = false))
            inPreviewSession(editor.editor)(change(file.getText, copy, modify))
          } finally copyResources.set(previous)
        }.get
      }
    }
  }

  /**
   * Copies a file for the platform's ModCommand pipeline during a preview.
   *
   * ModCommand creates further copies; their documents need the same commit listeners as the preview's file. The
   * copy keeps a Scala file's attached Scala features and is released with the running preview.
   *
   * @param original file the ModCommand wants to copy
   * @return the listened-to copy, or `null` when no preview is running on the calling thread
   */
  private[scalaanalyzer] def copyForModCommand(original: PsiFile): PsiFile = original match {
    case file if copyResources.get() != null =>
      val resources = copyResources.get()
      val copy = file.copy() match {
        case f: PsiFile => f
        case other => throw new IllegalStateException(s"mod-command copy is not a PSI file: $other")
      }
      resources((() => release(copy)): AutoCloseable)
      file match {
        case scala: ScalaFile => ScalaFeatures.setAttachedScalaFeatures(copy, ScalaFeatures.forPsiOrDefault(scala))
        case _ => ()
      }
      resources(listenTo(copy))
      copy
    case _ => null
  }

  /** `modify` on `copy`, its postponed formatting done and its document committed. */
  private def change[A](original: String, copy: PsiFile, modify: PsiFile => A): (Result, A) = {
    val document = copy.getViewProvider.getDocument
    val documents = PsiDocumentManager.getInstance(copy.getProject)
    try {
      val modified = PostprocessReformattingAspect.getInstance(copy.getProject).postponeFormattingInside((() => {
        val modified = modify(copy)
        documents.doPostponedOperationsAndUnblockDocument(document)
        modified
      }): Computable[A])
      documents.commitDocument(document)
      ProgressManager.checkCanceled()
      val changed = copy.getText
      require(document.getText == changed, "copy PSI and document diverged")
      (Result(changed, diff(original, changed)), modified)
    } finally {
      // Postponed tasks own range markers: drain them even when the change failed or was cancelled.
      ProgressManager.getInstance.executeNonCancelableSection((() =>
        documents.doPostponedOperationsAndUnblockDocument(document)): Runnable)
    }
  }

  /** Attaches the host's commit listener so the copied PSI and document stay synchronized. */
  private def listenTo(copy: PsiFile): AutoCloseable = PsiDocumentManager.getInstance(copy.getProject) match {
    case manager: HostPsiDocumentManager => manager.listenTo(copy.getViewProvider.getDocument)
    case other => throw new IllegalStateException(s"unexpected document manager: ${other.getClass.getName}")
  }

  /** The platform's session runs a `Runnable`: the change's result comes back through a local. */
  private def inPreviewSession[A](editor: Editor)(body: => A): A = {
    var result = Option.empty[A]
    IntentionPreviewUtils.previewSession(editor, () => result = Some(body))
    result.getOrElse(throw new IllegalStateException("the preview session did not run the change"))
  }

  /** Runs against a feature-aware preview copy and releases its provider on every exit path. */
  private def withPreviewCopy[A](file: PsiFile)(body: PsiFile => A): A = {
    val copy = file.copy() match {
      case f: PsiFile => f
      case other => throw new IllegalStateException(s"change copy is not a PSI file: ${other.getClass.getName}")
    }
    try {
      // Copies keep copyable user data, but Scala features live in ordinary user data.
      file match {
        case scala: ScalaFile => ScalaFeatures.setAttachedScalaFeatures(copy, ScalaFeatures.forPsiOrDefault(scala))
        case _ => ()
      }
      IntentionPreviewUtils.markAsPreviewCopy(copy, file)
      body(copy)
    } finally release(copy)
  }

  /**
   * Previews formatting with the platform's IntelliJ formatter.
   *
   * Settings are cloned for this request, leaving project settings unchanged. Configured whole-file scalafmt
   * requests are handled by the workspace before reaching this entry point.
   *
   * @param file Scala file to copy and format
   * @param range range to format, or none for the whole file
   * @param indentSize positive indentation width in spaces
   * @param useTabs whether indentation may use tab characters
   * @return formatted text and edits against the original
   */
  def format(file: ScalaFile, range: Option[TextRange], indentSize: Int, useTabs: Boolean): Result = HostLock.query {
    require(indentSize > 0, "the indent size must be positive")

    val manager = CodeStyleSettingsManager.getInstance(file.getProject)
    val settings = manager.getCurrentSettings.clone()

    // Configured whole-file scalafmt requests are handled before this IntelliJ-formatter entry point.
    settings.getCustomSettings(classOf[ScalaCodeStyleSettings]).FORMATTER = ScalaCodeStyleSettings.INTELLIJ_FORMATTER
    val indent = settings.getCommonSettings(ScalaLanguage.INSTANCE).getIndentOptions
    indent.TAB_SIZE = indentSize
    indent.INDENT_SIZE = indentSize
    indent.USE_TAB_CHARACTER = useTabs

    manager.computeWithLocalSettings(settings, _ => run(file) { copy =>
      val selected = range.getOrElse(copy.getTextRange)
      CodeStyleManager.getInstance(copy.getProject).reformatText(copy, selected.getStartOffset, selected.getEndOffset)
    }._1)
  }

  /**
   * Computes the edits that turn one text into another.
   *
   * Refines word hunks into character edits and joins touching changes while retaining unchanged whitespace.
   * Cancellation propagates from the platform's comparison.
   *
   * @param original text the edits apply to
   * @param changed text the edits produce
   * @return replacements against `original`, sorted, disjoint and non-touching; empty when the texts are equal
   */
  private[scalaanalyzer] def diff(original: String, changed: String): Seq[Edit] = {
    val cancel: CancellationChecker = () => ProgressManager.checkCanceled()
    val edits = for {
      hunk <- ByWordRt.compare(original, changed, ComparisonPolicy.DEFAULT, cancel).asScala.toVector
      before = original.substring(hunk.getStartOffset1, hunk.getEndOffset1)
      after = changed.substring(hunk.getStartOffset2, hunk.getEndOffset2)
      change <- ByCharRt.compare(before, after, cancel).iterateChanges().asScala
    } yield Edit(
      new TextRange(hunk.getStartOffset1 + change.start1, hunk.getStartOffset1 + change.end1),
      after.substring(change.start2, change.end2))
    joinTouching(edits)
  }

  /** Joins adjacent character-level changes so returned edits stay disjoint and non-touching. */
  private def joinTouching(edits: Seq[Edit]): Seq[Edit] = edits.foldLeft(Vector.empty[Edit]) {
    case (joined :+ previous, edit) if previous.range.getEndOffset == edit.range.getStartOffset =>
      joined :+ Edit(previous.range.union(edit.range), previous.text + edit.text)
    case (joined, edit) => joined :+ edit
  }

  /** Drops the copy's view-provider cache entry after preview use. */
  private def release(copy: PsiFile): Unit =
    PsiManagerEx.getInstanceEx(copy.getProject).getFileManager.setViewProvider(copy.getViewProvider.getVirtualFile, null)
}
