package scalaanalyzer.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.editor.{Editor, SelectionModel}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.actions.ShowTypeInfoAction
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.platform.RequestEditor

import java.lang.reflect.{InvocationTargetException, Method}

import scala.util.Using

/**
 * Computes what the plugin's Type Info action (Ctrl+Shift+P) shows: the type under a caret or of a selected expression.
 *
 * The action only shows its result as an editor hint, so its two computations are called directly.
 */
object TypeInfo {
  /** The companion's private `getTypeInfoHint(file, offset)`, which Scala compiles to a public, name-mangled method. */
  private val ForCaret = ShowTypeInfoAction.getClass.getMethod(
    "org$jetbrains$plugins$scala$actions$ShowTypeInfoAction$$$getTypeInfoHint", classOf[PsiFile], classOf[Int])

  /** The action's private `getTypeInfoHintForSelection(editor, file, project, selectionModel)`, made accessible. */
  private val ForSelection = {
    val method = classOf[ShowTypeInfoAction].getDeclaredMethod(
      "getTypeInfoHintForSelection", classOf[Editor], classOf[PsiFile], classOf[Project], classOf[SelectionModel])
    method.setAccessible(true)
    method
  }

  /** The instance `ForSelection` runs on; the method reads none of its state. */
  private lazy val Action = new ShowTypeInfoAction

  /**
   * Describes the type under a caret or of a selected expression, as the Type Info action does.
   *
   * The caller holds the host read lock. An empty range is a caret, which the action first moves onto the identifier
   * it touches; any other range is the editor's selection.
   *
   * @param file Scala source being queried
   * @param start UTF-16 offset of the selection's start, or of the caret
   * @param end UTF-16 offset of the selection's end; equal to `start` for a caret
   * @return the type as plain text, on several lines when the action adds the simplified, original or expected
   *         type; `None` when there is no type to show
   */
  def at(file: ScalaFile, start: Int, end: Int): Option[String] =
    if (start == end) atCaret(file, start) else atSelection(file, start, end)

  /** The type of the symbol or pattern at the caret, after the action's own offset adjustment. */
  private def atCaret(file: ScalaFile, offset: Int): Option[String] = {
    val adjusted = TargetElementUtil.adjustOffset(file, file.getViewProvider.getDocument, offset)

    hint(ForCaret, ShowTypeInfoAction, file, Int.box(adjusted))
  }

  /** The type of the pattern, expression or parameter that the range selects. */
  private def atSelection(file: ScalaFile, start: Int, end: Int): Option[String] =
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, start)) { request =>
      val editor = request.editor
      val selection = editor.getSelectionModel

      selection.setSelection(start, end)

      // The action escapes the text for its HTML hint.
      hint(ForSelection, Action, editor, file, file.getProject, selection).map(StringUtil.unescapeXmlEntities)
    }

  /** Calls one of the action's private methods; what it throws, a cancellation included, is rethrown as itself. */
  private def hint(method: Method, target: AnyRef, arguments: AnyRef*): Option[String] =
    // Both methods return the plugin's `Option[String]`, which reflection erases to `Object`.
    try method.invoke(target, arguments*).asInstanceOf[Option[String]]
    catch { case error: InvocationTargetException => throw error.getCause }
}
