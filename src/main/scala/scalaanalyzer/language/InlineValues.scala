package scalaanalyzer.language

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiClass, PsiElement, PsiFile, PsiLocalVariable, PsiMethod, PsiParameter, PsiReferenceExpression, SyntaxTraverser}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody

import scala.jdk.CollectionConverters.*

/**
 * Where a debugger stopped in a source shows the values of variables at the end of lines, as the IDE's "Show values
 * inline" does: each local variable and parameter of the method the program stopped in, on each line from the
 * method's start to the stopped line that declares or reads it. The values come from the debugger, which looks each
 * name up in the stopped frame; a name it does not find there (a lambda's parameter, from the enclosing method's
 * frame) shows nothing.
 *
 * The IDE asks its debugger for each variable's positions; without one, the variables are found in the PSI of the
 * method instead, which knows them the same way: references resolved to declarations inside the method.
 */
object InlineValues {
  /**
   * A place where a variable's value is shown.
   *
   * @param range the variable's name in the source
   * @param name the name the debugger knows the variable by
   */
  final case class Lookup(range: TextRange, name: String)

  /**
   * Finds the variables whose values a debugger stopped at `stopped` shows inside `visible`.
   *
   * The caller holds the host read lock.
   *
   * @param file Scala or Java source the program stopped in
   * @param document the file's document
   * @param visible the part of the file the editor shows
   * @param stopped UTF-16 offset of the stopped location
   * @return one place per variable and line, in source order: the first on the line that names it
   */
  def at(file: PsiFile, document: Document, visible: TextRange, stopped: Int): Seq[Lookup] =
    Option(file.findElementAt(stopped)).map(scope).fold(Seq.empty) { scope =>
      val executed = new TextRange(scope.getTextRange.getStartOffset, document.getLineEndOffset(document.getLineNumber(stopped)))

      SyntaxTraverser.psiTraverser(scope).traverse().asScala
        .flatMap(lookup(_, scope))
        .filter(found => visible.contains(found.range) && executed.contains(found.range))
        .toSeq
        .distinctBy(found => (document.getLineNumber(found.range.getStartOffset), found.name))
    }

  /**
   * The code whose locals a stopped frame holds: the method (or local function) around the stopped location; for code
   * of a class body, which runs in a constructor or a static initializer, the statement or member of the body.
   */
  private def scope(leaf: PsiElement): PsiElement =
    Option(PsiTreeUtil.getParentOfType(leaf, classOf[PsiMethod], false)).getOrElse {
      Iterator.iterate(leaf)(_.getParent).takeWhile(_ != null).find(element => element.getParent match {
        case _: ScTemplateBody | _: PsiClass | _: PsiFile | null => true
        case _ => false
      }).getOrElse(leaf)
    }

  /** The place an element shows a variable at: an unqualified reference to a local, or a local's declaration. */
  private def lookup(element: PsiElement, scope: PsiElement): Option[Lookup] = element match {
    case reference: ScReferenceExpression if reference.qualifier.isEmpty && isLocal(reference.resolve(), scope) =>
      Some(Lookup(reference.nameId.getTextRange, reference.refName))
    case reference: PsiReferenceExpression if reference.getQualifierExpression == null && isLocal(reference.resolve(), scope) =>
      Option(reference.getReferenceNameElement).map(name => Lookup(name.getTextRange, reference.getReferenceName))
    case declared: (ScReferencePattern | ScParameter) if isLocal(declared, scope) =>
      Some(Lookup(declared.nameId.getTextRange, declared.name))
    case declared: (PsiLocalVariable | PsiParameter) if isLocal(declared, scope) =>
      Option(declared.getNameIdentifier).map(name => Lookup(name.getTextRange, declared.getName))
    case _ => None
  }

  /** Whether a declaration is a local variable or a parameter of `scope`: a class's parameters are its fields. */
  private def isLocal(target: PsiElement, scope: PsiElement): Boolean = target match {
    case _: ScClassParameter => false
    case _: (ScReferencePattern | ScParameter | PsiLocalVariable | PsiParameter) => PsiTreeUtil.isAncestor(scope, target, false)
    case _ => false
  }
}
