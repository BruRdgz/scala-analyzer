package scalapsihost.platform

import com.intellij.openapi.editor.{Caret, CaretModel, Document, Editor, EditorSettings, ScrollingModel, SelectionModel}
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Key, TextRange, UserDataHolderBase}
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory

/** Provides a request-scoped editor; callers close it to detach document-position tracking. */
private[scalapsihost] final class RequestEditor(document: Document, project: Project, offset: Int, caretRightBiased: Boolean = true)
    extends AutoCloseable {
  // State stays on the request thread, under its read/preview or write action. Offsets are UTF-16 code units;
  // selections include their start and exclude their end.
  private val data = new UserDataHolderBase
  private var caretOffset = offset
  private var selectionStart = offset
  private var selectionEnd = offset

  private lazy val settings = tableProxy(classOf[EditorSettings], "SelectionSettings") {
    // AbstractWordSelectioner and ScalaStringLiteralSelectioner read the platform's default camel-word mode.
    case ("isCamelWords", _) => java.lang.Boolean.FALSE
    case ("isInsertParenthesesAutomatically", _) => java.lang.Boolean.valueOf(
      com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance.isInsertParenthesesAutomatically)
  }
  /** Builds the layered lexer lazily from the document's PSI and current text snapshot. */
  private lazy val highlighter = {
    val file = PsiDocumentManager.getInstance(project).getPsiFile(document)
    val syntax = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getLanguage, project, file.getVirtualFile)
    val lexerHighlighter = new LexerEditorHighlighter(syntax, EditorColorsManager.getInstance.getGlobalScheme)

    lexerHighlighter.setText(document.getImmutableCharSequence)
    lexerHighlighter
  }

  private val listener = new DocumentListener {
    /** Moves tracked positions through a replacement, keeping selection-start and selection-end affinity. */
    override def documentChanged(event: DocumentEvent): Unit = {
      val changeStart = event.getOffset
      val oldEnd = changeStart + event.getOldLength
      val newEnd = changeStart + event.getNewLength
      val lengthDelta = newEnd - oldEnd
      val hadSelection = selectionStart != selectionEnd

      /** Keeps left-biased positions at the change start and moves covered positions after the new text. */
      def shifted(at: Int, rightBiased: Boolean): Int =
        if (at < changeStart || (at == changeStart && !rightBiased)) at
        else if (at > oldEnd) at + lengthDelta
        else newEnd

      caretOffset = shifted(caretOffset, rightBiased = caretRightBiased)
      selectionStart = shifted(selectionStart, rightBiased = false)
      selectionEnd = shifted(selectionEnd, rightBiased = true)
      if (!hadSelection || selectionStart == selectionEnd) removeSelection()
    }
  }

  /** Moves the caret to a valid document offset and collapses its selection. */
  private def moveCaret(at: Int): Unit = {
    require(at >= 0 && at <= document.getTextLength, s"caret outside document: $at")

    caretOffset = at
    removeSelection()
  }

  /** Collapses both selection bounds to the current caret. */
  private def removeSelection(): Unit = {
    selectionStart = caretOffset
    selectionEnd = caretOffset
  }

  /** Updates a valid half-open selection without moving the caret. */
  private def setSelection(start: Int, end: Int): Unit = {
    require(start >= 0 && start <= end && end <= document.getTextLength, "selection outside document")

    selectionStart = start
    selectionEnd = end
  }

  /** Stores user data through the reflection boundary where Key's type parameter is erased. */
  private def putData(key: Key[?], value: AnyRef): Unit = data.putUserData(key.asInstanceOf[Key[AnyRef]], value)

  // Scrolling is performed by the LSP client after it receives the final caret, not by this preview editor.
  private lazy val scrolling = tableProxy(classOf[ScrollingModel], "CompletionScrolling") {
    case ("scrollToCaret", _) => CallLog.hit("Completion.scrollSkipped"); null
  }

  /** Exposes the lazily connected editor, caret and selection adapters for this request. */
  lazy val editor: Editor = tableProxy(classOf[Editor], "RequestEditor") {
    case ("getDocument", _) => document
    case ("getProject", _) => project
    case ("getCaretModel", _) => caretModel
    case ("getSelectionModel", _) => selectionModel
    case ("getScrollingModel", _) => scrolling
    // Word selection reads isCamelWords; it never changes editor settings.
    case ("getSettings", _) => settings
    // EditorActionUtil's lexeme boundaries and InjectedFileReferenceSelectioner's escapes use the layered lexer.
    case ("getHighlighter", _) => highlighter
    // A source editor, rather than a one-line input field.
    case ("isOneLineMode", _) => java.lang.Boolean.FALSE
    // The host has no editor-specific color scheme.
    case ("getColorsScheme", _) => EditorColorsManager.getInstance.getGlobalScheme
    case ("getUserData", Array(key: Key[?])) => data.getUserData(key).asInstanceOf[AnyRef]
    case ("putUserData", Array(key: Key[?], value)) =>
      putData(key, value)
      null
  }

  private lazy val caret: Caret = tableProxy(classOf[Caret], "CompletionCaret") {
    case ("getEditor", _) => editor
    case ("getOffset", _) => Integer.valueOf(caretOffset)
    case ("getSelectionStart", _) => Integer.valueOf(selectionStart)
    case ("getSelectionEnd", _) => Integer.valueOf(selectionEnd)
    case ("hasSelection", _) => java.lang.Boolean.valueOf(selectionStart != selectionEnd)
    // SelectWordUtil checks whether the current selection still fits inside whitespace before expanding it.
    case ("getSelectionRange", _) => new TextRange(selectionStart, selectionEnd)
    case ("moveToOffset", Array(at: Integer)) =>
      moveCaret(at.intValue)
      null
    case ("setSelection", Array(start: Integer, end: Integer)) =>
      setSelection(start.intValue, end.intValue)
      null
    case ("removeSelection", _) =>
      removeSelection()
      null
  }

  private lazy val caretModel: CaretModel = tableProxy(classOf[CaretModel], "CompletionCaretModel") {
    case ("getCurrentCaret" | "getPrimaryCaret", _) => caret
    case ("getOffset", _) => Integer.valueOf(caretOffset)
    case ("getAllCarets", _) => java.util.List.of(caret)
    case ("moveToOffset", Array(at: Integer)) =>
      moveCaret(at.intValue)
      null
  }

  private lazy val selectionModel: SelectionModel = tableProxy(classOf[SelectionModel], "CompletionSelection") {
    case ("getSelectionStart", _) => Integer.valueOf(selectionStart)
    case ("getSelectionEnd", _) => Integer.valueOf(selectionEnd)
    case ("hasSelection", _) => java.lang.Boolean.valueOf(selectionStart != selectionEnd)
    case ("setSelection", Array(start: Integer, end: Integer)) =>
      setSelection(start.intValue, end.intValue)
      null
    case ("removeSelection", _) =>
      removeSelection()
      null
  }

  document.addDocumentListener(listener)

  /** Detaches position tracking without changing the document or disposing its PSI. */
  override def close(): Unit = document.removeDocumentListener(listener)
}
