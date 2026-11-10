package scalaanalyzer.platform

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.lang.impl.modcommand.ModCommandBatchExecutorImpl
import com.intellij.modcommand.{ActionContext, ModCommand, ModNavigate}
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager

import scala.jdk.CollectionConverters.*

/**
 * Executes the commands the platform runs "interactively" on the file copy a change is previewed on.
 *
 * Every host edit is made on a copy, and the host has no UI: the batch executor would refuse a command that
 * moves the caret. Here such a command updates the copy's text, as `executeForFileCopy` does for completion and code
 * actions, and the caret goes where the command navigates to. The command was computed from the copy's PSI, so the
 * document first takes the PSI changes the caller made before asking for it. Outside a preview session the file is
 * one of the user's, which the host never writes, so the command is rejected; so is one for a file that is not the
 * session's copy.
 */
private[scalaanalyzer] final class HostModCommandExecutor extends ModCommandBatchExecutorImpl {
  override def executeInteractively(context: ActionContext, command: ModCommand, editor: Editor): Unit = {
    if (!IntentionPreviewUtils.isPreviewElement(context.file))
      CallLog.unsupported("ModCommandExecutor.executeInteractively")
    val document = context.file.getViewProvider.getDocument
    PsiDocumentManager.getInstance(context.project).doPostponedOperationsAndUnblockDocument(document)
    executeForFileCopy(command, context.file)
    command.unpack.asScala.foreach {
      case navigate: ModNavigate if editor != null && navigate.caret >= 0 =>
        editor.getCaretModel.moveToOffset(navigate.caret)
      case _ => ()
    }
  }
}
