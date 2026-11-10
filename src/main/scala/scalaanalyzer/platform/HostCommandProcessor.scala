package scalaanalyzer.platform

import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.impl.CoreCommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

/**
 * Runs the plugin's commands where they are asked, instead of on the event dispatch thread.
 *
 * The platform wraps a change in a command for undo; the host has no undo, and its changes run on request threads
 * under the host's own write coordination (see [[HostLock]]), which makes the platform's dispatch-thread assertion
 * wrong for it. A command therefore only runs its task. Undo-transparent actions need no such answer: they do not
 * assert the thread.
 */
private[scalaanalyzer] final class HostCommandProcessor extends CoreCommandProcessor {
  /** Runs `command` at once; the host records no undo step, so the name, group and document are not kept. */
  override def executeCommand(project: Project, command: Runnable, name: String, groupId: Object,
                              undoConfirmationPolicy: UndoConfirmationPolicy,
                              shouldRecordCommandForActiveDocument: Boolean, document: Document): Unit =
    command.run()
}
