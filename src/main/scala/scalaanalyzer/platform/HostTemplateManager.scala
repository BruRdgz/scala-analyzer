package scalaanalyzer.platform

import com.intellij.codeInsight.template.{Template, TemplateEditingListener, TemplateManager}
import com.intellij.codeInsight.template.impl.{TemplateImpl, TemplateState, TextExpression}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.util.PairProcessor

import scala.jdk.CollectionConverters.*

/**
 * Expands live templates at once, in place of the IDE's interactive template session.
 *
 * The host has no editing session to leave a template in: a template is inserted with the values its variables are
 * built with, reformatted if it asks to be, and the caret ends where its `$END$` is (after the text without one).
 * Only fixed-value variables (`TextExpression`) are supported, which is what the Scala plugin's postfix templates
 * use. Anything that needs the session (tab stops, macros, listeners, a selection) is rejected by `CallLog`.
 */
private[scalaanalyzer] final class HostTemplateManager extends TemplateManager {
  /** A template's text with its variables replaced, and where its `$END$` is within that text. */
  private final case class Expansion(text: String, end: Option[Int])

  /**
   * Inserts the template at the editor's caret and moves the caret to its end position.
   *
   * Runs inside the caller's write action, on the document of a preview copy that the host keeps synchronized with
   * its PSI.
   *
   * @param editor editor over the document to change; its caret is where the template goes
   * @param template the template, created by this manager
   */
  override def startTemplate(editor: Editor, template: Template): Unit = {
    val live = template match {
      case impl: TemplateImpl => impl
      case other => throw new IllegalArgumentException(s"not a template of this manager: ${other.getClass.getName}")
    }
    val document = editor.getDocument
    val project = editor.getProject
    val documents = PsiDocumentManager.getInstance(project)
    val expansion = expanded(live)
    val start = editor.getCaretModel.getOffset
    val endOffset = start + expansion.end.getOrElse(expansion.text.length)

    document.insertString(start, expansion.text)
    // The markers follow the text through the reformatting.
    val inserted = document.createRangeMarker(start, start + expansion.text.length)
    val end = document.createRangeMarker(endOffset, endOffset)
    documents.commitDocument(document)

    if (live.isToReformat) {
      val file: PsiFile = documents.getPsiFile(document)

      CodeStyleManager.getInstance(project).reformatText(file, inserted.getStartOffset, inserted.getEndOffset)
      documents.commitDocument(document)
    }

    val line = document.getLineNumber(end.getStartOffset)
    val lineRange = new TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
    val onBlankLine = document.getText(lineRange).isBlank
    // A caret on an emptied line (`{\n$END$\n}`) goes to that line's indentation.
    val caret = if (onBlankLine) CodeStyleManager.getInstance(project).adjustLineIndent(document, end.getStartOffset)
                else end.getStartOffset

    editor.getCaretModel.moveToOffset(caret)
    inserted.dispose()
    end.dispose()
  }

  /** Substitutes each variable of the template's text with its fixed value and notes where `$END$` falls. */
  private def expanded(template: TemplateImpl): Expansion = {
    val text = template.getTemplateText
    val values = template.getVariables.asScala.map(variable => variable.getName -> variable.getExpression).toMap
    val result = new StringBuilder
    var copied = 0
    var end = Option.empty[Int]

    for (i <- 0 until template.getSegmentsCount) {
      val offset = template.getSegmentOffset(i)
      val name = template.getSegmentName(i)

      result.append(text.substring(copied, offset))
      copied = offset
      if (name == Template.END) end = Some(result.length)
      else values.get(name) match {
        case Some(expression: TextExpression) =>
          result.append(expression.calculateResult(null).toString)
        case _ => CallLog.unsupported(s"TemplateManager.variable $name")
      }
    }
    result.append(text.substring(copied))

    Expansion(result.toString, end)
  }

  /** Creates an empty template for the caller to fill with text and variables. */
  override def createTemplate(key: String, group: String): Template = new TemplateImpl(key, group)

  /** Creates a template whose `$name$` markers in `text` become variables the caller adds. */
  override def createTemplate(key: String, group: String, text: String): Template = new TemplateImpl(key, text, group)

  /** The host has no template sessions: no template is ever active. */
  override def getActiveTemplate(editor: Editor): Template = null

  /** The host has no template sessions: none can be finished. */
  override def finishTemplate(editor: Editor): Boolean = false

  /** Rejects sessions: templates are expanded by `startTemplate(editor, template)`. */
  override def runTemplate(editor: Editor, template: Template): TemplateState =
    CallLog.unsupported("TemplateManager.runTemplate")

  /** Rejects templates that wrap a selection. */
  override def startTemplate(editor: Editor, selectionString: String, template: Template): Unit =
    CallLog.unsupported("TemplateManager.startTemplate(selection)")

  /** Rejects editing listeners, which a session would call. */
  override def startTemplate(editor: Editor, template: Template, listener: TemplateEditingListener): Unit =
    CallLog.unsupported("TemplateManager.startTemplate(listener)")

  /** Rejects predefined variable values and editing listeners. */
  override def startTemplate(editor: Editor, template: Template, inSeparateCommand: Boolean,
                             predefinedVarValues: java.util.Map[String, String],
                             listener: TemplateEditingListener): Unit =
    CallLog.unsupported("TemplateManager.startTemplate(predefinedVarValues)")

  /** Rejects editing listeners and variable callbacks. */
  override def startTemplate(editor: Editor, template: Template, listener: TemplateEditingListener,
                             callback: PairProcessor[? >: String, ? >: String]): Unit =
    CallLog.unsupported("TemplateManager.startTemplate(callback)")

  /** Rejects expansion by an abbreviation typed in the editor. */
  override def startTemplate(editor: Editor, shortcutChar: Char): Boolean =
    CallLog.unsupported("TemplateManager.startTemplate(shortcut)")
}
