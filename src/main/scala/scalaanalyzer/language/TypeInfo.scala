package scalaanalyzer.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.hint.JavaTypeProvider
import com.intellij.openapi.editor.{Editor, SelectionModel}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiFile, PsiJavaFile}
import org.jetbrains.plugins.scala.actions.ShowTypeInfoAction
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.platform.RequestEditor

import java.lang.reflect.{InvocationTargetException, Method}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Computes what the IDE's Type Info action (Ctrl+Shift+P) shows: the type under a caret or of a selected expression.
 *
 * The action only shows its result as an editor hint, so its computations are called directly: the Scala plugin's
 * two, and for Java the platform's handler's choice of expressions (`ShowExpressionTypeHandler.getExpressions`,
 * repeated in [[inJava]]: the handler's class names the hint and chooser UI the host does not have) with the Java
 * plugin's type provider.
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
   * @param file Scala or Java source being queried
   * @param start UTF-16 offset of the selection's start, or of the caret
   * @param end UTF-16 offset of the selection's end; equal to `start` for a caret
   * @return the type as plain text, on several lines when the Scala action adds the simplified, original or expected
   *         type; `None` when there is no type to show
   */
  def at(file: ScalaFile | PsiJavaFile, start: Int, end: Int): Option[String] = file match {
    case scala: ScalaFile => if (start == end) atCaret(scala, start) else atSelection(scala, start, end)
    case java: PsiJavaFile => inJava(java, new TextRange(start, end))
  }

  /**
   * The type of the Java expression that the range selects, or of the innermost one at the caret.
   *
   * As the handler: a selection is trimmed of surrounding white space and an expression whose range is exactly it is
   * preferred to those around it; a caret is adjusted onto the identifier it touches, and up to two characters before
   * it are tried when nothing is found at it. Where the IDE lets the user choose among several expressions, this takes
   * the chooser's first, the innermost.
   */
  private def inJava(file: PsiJavaFile, selection: TextRange): Option[String] = {
    val provider = new JavaTypeProvider
    val document = file.getViewProvider.getDocument
    val range = trimmed(selection, document.getImmutableCharSequence)
    val offset =
      if (range.isEmpty) TargetElementUtil.adjustOffset(file, document, range.getStartOffset) else range.getStartOffset
    val found = (0 until 3).iterator.filter(offset >= _).map { back =>
      Option(file.findElementAt(offset - back)).toSeq
        .flatMap(provider.getExpressionsAt(_).asScala)
        .filter(_.getTextRange.contains(range))
    }.find(_.nonEmpty).getOrElse(Seq.empty)
    val exact = found.filter(_.getTextRange == range)

    // The provider's hint is the type's presentation escaped for HTML.
    (if (exact.nonEmpty) exact else found).headOption
      .map(expression => StringUtil.unescapeXmlEntities(provider.getInformationHint(expression)))
  }

  /** The range without the white space at its ends; an empty or all-blank range as it is. */
  private def trimmed(range: TextRange, text: CharSequence): TextRange = {
    val start = Iterator.iterate(range.getStartOffset)(_ + 1)
      .find(at => at >= range.getEndOffset || !Character.isWhitespace(text.charAt(at))).get
    val end = Iterator.iterate(range.getEndOffset)(_ - 1)
      .find(at => at <= start || !Character.isWhitespace(text.charAt(at - 1))).get

    if (start < end) new TextRange(start, end) else range
  }

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
