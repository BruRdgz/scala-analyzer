package scalaanalyzer.language

import com.intellij.psi.{PsiElement, PsiReference}
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import org.jetbrains.plugins.scala.extensions.PsiElementExt
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScBindingPattern, ScStableReferencePattern}
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScMethodCall
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunctionDefinition, ScFunctionDefinitionExt, ScPatternDefinition, ScTypeAliasDefinition
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScNamedElement, ScTypedDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScMember
import org.jetbrains.plugins.scala.lang.psi.types.api.FunctionType
import org.jetbrains.plugins.scala.lang.refactoring.inline.ScalaInlineProcessor
import org.jetbrains.plugins.scala.lang.refactoring.inline.method.ScalaInlineMethodProcessor
import org.jetbrains.plugins.scala.lang.refactoring.inline.typeAlias.{
  ScalaInlineTypeAliasHandler, ScalaInlineTypeAliasProcessor
}
import org.jetbrains.plugins.scala.lang.refactoring.inline.variable.ScalaInlineVariableProcessor

import java.lang.reflect.InvocationTargetException

import scala.jdk.CollectionConverters.*

/**
 * Offers the plugin's Inline refactoring for `val`s, `def`s and type aliases that only their own file can see.
 *
 * The plugin's handlers ask in a dialog whether to inline every usage and remove the declaration or one usage only,
 * and show an error hint where they refuse. The host has neither: it offers both inlinings as separate actions and
 * offers nothing where a handler would refuse. A declaration other files can see (a public member, a Scala 3
 * top-level private one, a `private[pkg]` one) is refused too: the host edits the current file only. Inlining runs the
 * plugin's processors on a preview copy of the file, with the usages found in the original.
 *
 * Callers hold PSI read access.
 */
object Inline {
  /**
   * One way of inlining at a caret.
   *
   * @param title user-facing action name
   * @param perform the inlining, applied to a preview copy of the file the action was found in
   */
  final case class Action(title: String, perform: ScalaFile => Unit)

  /** The handler's private `isSimpleTypeAlias`, compiled to a public, name-mangled method of its companion. */
  private val IsSimpleTypeAlias = ScalaInlineTypeAliasHandler.getClass.getMethod(
    "org$jetbrains$plugins$scala$lang$refactoring$inline$typeAlias$ScalaInlineTypeAliasHandler$$$isSimpleTypeAlias",
    classOf[ScTypeAliasDefinition])

  /**
   * Finds the inlinings of the declaration at a caret, which is on the declaration or on one of its usages.
   *
   * Inlining all usages and removing the declaration is offered for each inlinable declaration. Inlining one usage
   * is added when the caret is on a usage and the declaration has others, as the IDE's dialog does.
   *
   * @param file Scala source being queried
   * @param offset caret offset in UTF-16 code units
   * @return the available inlinings; empty when the caret is not on an inlinable `val`, `def` or type alias that
   *         only `file` can see
   */
  def actions(file: ScalaFile, offset: Int): Seq[Action] =
    ProjectSearch.usageTarget(file, offset) match {
      case Some(declaration: ScNamedElement) if isVisibleOnlyIn(file, declaration) =>
        val usages = ReferencesSearch.search(declaration, declaration.getUseScope).findAll().asScala.toSeq

        if (!inlinable(declaration, usages)) Seq.empty
        else {
          val removeAll = inlineInCopy(_, declaration, usages, removeDeclaration = true)
          val all = Action("Inline all and remove the declaration", removeAll)
          val thisOne = Option(file.findReferenceAt(offset)).filter(_ => usages.size > 1).map { usage =>
            Action("Inline this occurrence", inlineInCopy(_, declaration, Seq(usage), removeDeclaration = false))
          }

          all +: thisOne.toSeq
        }
      case _ => Seq.empty
    }

  /** Whether the declaration's use scope lies within `file`, so that the file holds every usage. */
  private def isVisibleOnlyIn(file: ScalaFile, declaration: ScNamedElement): Boolean =
    declaration.getUseScope match {
      case scope: LocalSearchScope => scope.getScope.forall(_.getContainingFile == file)
      case _ => false
    }

  /**
   * Whether the plugin's handler would go on to inline the declaration instead of showing an error hint.
   *
   * Follows `ScalaInlineActionHandler` and its three subclasses. Only `val`s are inlined: the plugin's tests do not
   * cover the inlining of a simple `var`.
   */
  private def inlinable(declaration: ScNamedElement, usages: Seq[PsiReference]): Boolean =
    usages.nonEmpty && !ScalaPsiUtil.isImplicit(declaration) && usedInContainingClassOnly(declaration, usages) &&
      (declaration match {
        case pattern: ScBindingPattern =>
          !isFunctionTyped(pattern) && !usedAsStablePath(usages) && isSimpleVal(pattern)
        case method: ScFunctionDefinition => !usedAsStablePath(usages) && isInlinableMethod(method, usages)
        case alias: ScTypeAliasDefinition => alias.typeParameters.isEmpty && isSimpleTypeAlias(alias)
        case _ => false
      })

  /**
   * Whether every usage of a class member lies inside its class, as the handler's `usedInSameClassOnly` requires.
   *
   * A private member's use scope takes in its companion, and a member of a local class the enclosing block, and the
   * inlined body would not resolve there. A declaration that is not a class member passes.
   */
  private def usedInContainingClassOnly(declaration: ScNamedElement, usages: Seq[PsiReference]): Boolean =
    declaration.nameContext match {
      case member: ScMember =>
        Option(member.containingClass).forall { owner =>
          usages.forall(usage => PsiTreeUtil.isAncestor(owner, usage.getElement, true))
        }
      case _ => true
    }

  /** Whether the value or parameter is a function that takes arguments, whose uses the inliner cannot replace. */
  private def isFunctionTyped(definition: ScTypedDefinition): Boolean =
    FunctionType.unapply(definition.`type`().getOrAny).exists(_._2.nonEmpty)

  /** Whether a usage is a stable path or a stable-reference pattern, which a replacement expression cannot take. */
  private def usedAsStablePath(usages: Seq[PsiReference]): Boolean = {
    val stablePaths: Seq[Class[? <: PsiElement]] =
      Seq(classOf[ScStableCodeReference], classOf[ScStableReferencePattern])

    usages.exists(_.getElement.nonStrictParentOfType(stablePaths).isDefined)
  }

  /** Whether the pattern is the one name of a `val name = ...` definition, not part of a destructuring or a case. */
  private def isSimpleVal(pattern: ScBindingPattern): Boolean =
    pattern.nameContext match {
      case definition: ScPatternDefinition => definition.isSimple
      case _ => false
    }

  /**
   * Whether the method has a body, at most one plain parameter clause and no recursion, and is only called when it has
   * parameters.
   */
  private def isInlinableMethod(method: ScFunctionDefinition, usages: Seq[PsiReference]): Boolean = {
    val clauses = method.paramClauses.clauses
    val isCall = (usage: PsiReference) => usage.getElement.getParent.isInstanceOf[ScMethodCall]

    method.body.isDefined &&
      method.recursiveReferences.isEmpty &&
      clauses.size <= 1 &&
      !clauses.exists(clause => clause.hasImplicitKeyword || clause.hasUsingKeyword) &&
      !method.parameters.exists(_.isVarArgs) &&
      !method.isSpecial &&
      method.typeParameters.isEmpty &&
      !method.parameters.exists(isFunctionTyped) &&
      (method.parameters.isEmpty || usages.forall(isCall))
  }

  /** Whether the alias is one the handler accepts: its right-hand side names no type parameter or unstable path. */
  private def isSimpleTypeAlias(alias: ScTypeAliasDefinition): Boolean =
    // The method returns a primitive boolean, which reflection boxes.
    try IsSimpleTypeAlias.invoke(ScalaInlineTypeAliasHandler, alias).asInstanceOf[Boolean]
    catch { case error: InvocationTargetException => throw error.getCause }

  /**
   * Inlines usages of a declaration of the original file in a copy of it, as the dialog's processors do.
   *
   * The usages come from the original, as a reference search on a copy is not reliable. Both have the same text, so
   * the processor works on the copy's elements at the same offsets. The declaration goes with the usages when
   * `removeDeclaration` is set.
   */
  private def inlineInCopy(
      copy: ScalaFile, declaration: ScNamedElement, usages: Seq[PsiReference], removeDeclaration: Boolean): Unit = {
    val processor = processorIn(copy, declaration, inlineThisOnly = !removeDeclaration)
    val usagesInCopy = usages.map(usage => new UsageInfo(referenceInCopy(copy, usage))).toArray

    processor.performRefactoring(usagesInCopy)
    processor.performPsiSpoilingRefactoring()
  }

  /**
   * The plugin's processor for the declaration's kind, over its counterpart in the copy.
   *
   * The processors take the dialog's choices as arguments. They search for usages only in `findUsages`, which the host
   * does not call, so their `reference` stays empty. They remove the declaration unless they inline this usage only.
   */
  private def processorIn(
      copy: ScalaFile, declaration: ScNamedElement, inlineThisOnly: Boolean): ScalaInlineProcessor = {
    val offset = declaration.nameId.getTextRange.getStartOffset
    val project = copy.getProject

    declaration match {
      case _: ScBindingPattern =>
        new ScalaInlineVariableProcessor(
          elementInCopy(copy, offset, classOf[ScBindingPattern]),
          elementInCopy(copy, offset, classOf[ScPatternDefinition]),
          None, inlineThisOnly, removeDefinitionAfterInlining = true)(using project)
      case _: ScFunctionDefinition =>
        new ScalaInlineMethodProcessor(
          elementInCopy(copy, offset, classOf[ScFunctionDefinition]),
          None, inlineThisOnly, removeDefinitionAfterInlining = true)(using project)
      case _: ScTypeAliasDefinition =>
        new ScalaInlineTypeAliasProcessor(
          elementInCopy(copy, offset, classOf[ScTypeAliasDefinition]),
          None, inlineThisOnly, removeDefinitionAfterInlining = true)(using project)
      case other => throw new IllegalStateException(s"cannot inline a ${other.getClass.getName}")
    }
  }

  /** The `kind` element enclosing `offset` in the copy, which has the original's text and so the same PSI shape. */
  private def elementInCopy[A <: PsiElement](copy: ScalaFile, offset: Int, kind: Class[A]): A =
    Option(PsiTreeUtil.findElementOfClassAtOffset(copy, offset, kind, false))
      .getOrElse(throw new IllegalStateException(s"no ${kind.getSimpleName} at offset $offset in the preview copy"))

  /** The reference at the same position in the copy as `reference` in the original. */
  private def referenceInCopy(copy: ScalaFile, reference: PsiReference): PsiReference = {
    val offset = reference.getElement.getTextRange.getStartOffset + reference.getRangeInElement.getStartOffset

    Option(copy.findReferenceAt(offset))
      .getOrElse(throw new IllegalStateException(s"no reference at offset $offset in the preview copy"))
  }
}
