package scalaanalyzer.language

import com.intellij.codeInsight.editorActions.SelectWordUtil
import com.intellij.openapi.editor.{Caret, Editor}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiElement, PsiFile, PsiWhiteSpace}
import com.intellij.psi.templateLanguages.OuterLanguageElement
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.platform.{HostLock, RequestEditor}

import scala.annotation.tailrec
import scala.util.Using

/**
 * Computes successive Extend Selection steps using the platform's registered handlers.
 *
 * As in SelectWordHandler, each
 * step searches from the caret's leaf and stops at the first ancestor offering a strict expansion of the selection.
 */
object SelectionRanges {
  /**
   * Expands a caret position into successively larger selection ranges.
   *
   * Runs under a cancellable host read with a temporary editor, released before returning.
   *
   * @param file Scala source being queried
   * @param offset caret offset in UTF-16 code units
   * @return strict expansions from innermost to outermost, excluding the initial empty range
   */
  def at(file: ScalaFile, offset: Int): Seq[TextRange] = HostLock.query {
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, offset)) { request =>
      val editor = request.editor
      val caret = editor.getCaretModel.getCurrentCaret
      Iterator.unfold(new TextRange(offset, offset)) { selection =>
        next(file, editor, caret, selection).map { range =>
          caret.setSelection(range.getStartOffset, range.getEndOffset)
          range -> range
        }
      }.toVector
    }
  }

  /** The next Extend Selection step from `selection`: the smallest handler range strictly containing it. */
  private def next(file: ScalaFile, editor: Editor, caret: Caret, selection: TextRange): Option[TextRange] = {
    ProgressManager.checkCanceled()
    val offset = adjustedOffset(editor.getDocument.getCharsSequence, caret)
    startElement(file, editor, caret, offset).flatMap { (element, cursor) =>
      val smallest = smallestExpansion(otherTree(file, element, cursor), cursor, editor, selection)
      Option.when(smallest.contains(selection) && smallest != selection)(smallest)
    }
  }

  /** Starts at the caret's leaf or preceding token, skipping whitespace that cannot expand. */
  private def startElement(file: ScalaFile, editor: Editor, caret: Caret, offset: Int): Option[(PsiElement, Int)] = {
    val leaf = elementAt(file, offset)
    val element = leaf match {
      case _: PsiWhiteSpace if offset > 0 && !elementAt(file, offset - 1).isInstanceOf[PsiWhiteSpace] => elementAt(file, offset - 1)
      case _ => leaf
    }
    element match {
      case space: PsiWhiteSpace if SelectWordUtil.canWhiteSpaceBeExpanded(space, offset, caret, editor) => Some(space -> offset)
      case _ => followingContent(element).map(found => found -> (if (found eq element) offset else found.getTextRange.getStartOffset))
    }
  }

  /** SelectWordUtil offers ranges through a callback; the smallest one that strictly contains `selection` is kept. */
  private def smallestExpansion(element: PsiElement, cursor: Int, editor: Editor, selection: TextRange): TextRange = {
    val text = editor.getDocument.getCharsSequence
    var smallest = new TextRange(0, text.length)
    SelectWordUtil.processRanges(element, text, cursor, editor, range => {
      val expands = range.contains(selection) && range != selection && smallest.contains(range)
      if (expands) smallest = range
      expands
    })
    smallest
  }

  /** Moves the handler lookup one code unit left at token ends, whitespace boundaries and selection ends. */
  private def adjustedOffset(text: CharSequence, caret: Caret): Int = {
    val offset = caret.getOffset
    /** Keeps character access deferred until the caret is known to have a preceding code unit. */
    def favoursPrevious: Boolean = {
      val previous = text.charAt(offset - 1)
      val identifierEnd = offset < text.length && !Character.isJavaIdentifierPart(text.charAt(offset)) && Character.isJavaIdentifierPart(previous)
      val whitespace = (offset == text.length || Character.isWhitespace(text.charAt(offset))) && !Character.isWhitespace(previous)
      val selectionEnd = caret.getSelectionEnd == offset && caret.getSelectionStart < offset
      identifierEnd || whitespace || selectionEnd
    }
    if (offset > 0 && favoursPrevious) offset - 1 else offset
  }

  /** Gets the leaf at `offset`, using the final character when the caret is at end of file. */
  private def elementAt(file: ScalaFile, offset: Int): PsiElement =
    file.findElementAt(if (offset > 0 && offset == file.getTextLength) offset - 1 else offset)

  /** Skips whitespace and file boundaries to the next PSI content, if one exists. */
  @tailrec
  private def followingContent(element: PsiElement): Option[PsiElement] = element match {
    case null | _: PsiFile => None
    case space if space.isInstanceOf[PsiWhiteSpace] || StringUtil.isEmptyOrSpaces(space.getText) =>
      followingContent(followingSibling(space))
    case content => Some(content)
  }

  /** Finds the next sibling, climbing ancestors when the current node is last in its parent. */
  @tailrec
  private def followingSibling(element: PsiElement): PsiElement =
    if (element == null || element.isInstanceOf[PsiFile]) null
    else if (element.getNextSibling != null) element.getNextSibling
    else {
      val parent = element.getParent
      val children = parent.getChildren
      if (children.nonEmpty && children.last == element) followingSibling(parent)
      else if (parent.isInstanceOf[PsiFile]) null
      else parent.getNextSibling
    }

  /** Template leaves can belong to another PSI tree; the action starts at that tree's first sibling at the caret. */
  private def otherTree(file: ScalaFile, element: PsiElement, offset: Int): PsiElement = element match {
    case _: OuterLanguageElement =>
      val other = file.getViewProvider.findElementAt(element.getTextOffset, element.getLanguage)
      if (other == null || other.getContainingFile == element.getContainingFile) element
      else {
        val sibling = firstSibling(other)
        if (sibling != null && sibling.getTextOffset == offset) sibling else element
      }
    case _ => element
  }

  /** Finds the first sibling of a PSI node by walking to the top of its tree. */
  @tailrec
  private def firstSibling(element: PsiElement): PsiElement =
    if (element == null || element.getPrevSibling != null) element else firstSibling(element.getParent)
}
