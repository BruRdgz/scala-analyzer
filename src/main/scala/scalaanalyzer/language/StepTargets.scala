package scalaanalyzer.language

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiMethod, PsiMethodCallExpression, PsiNewExpression}
import org.jetbrains.plugins.scala.lang.psi.api.base.ScConstructorInvocation
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScGenericCall, ScMethodCall, ScReferenceExpression, ScSugarCallExpr}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction

import scalaanalyzer.build.DebugAdapter

import scala.collection.mutable.ListBuffer
import scala.reflect.NameTransformer

/**
 * The calls of a source line that a debugger can step into, as the IDE's Smart Step Into lists them: the methods and
 * constructors the line calls, in the order they run (the receiver and the arguments of a call before the call).
 *
 * The debugger recognizes the target by the name of the method whose frame it enters, not by the call, so two calls of
 * the same method on a line are two targets and the first of them is the one stepped into.
 */
object StepTargets {
  /** A call found in the PSI: where its name is, and what the debugger shows and looks for. */
  private final case class Call(offset: Int, label: String, methodName: String)

  /**
   * Finds the calls of a line of a Scala or Java file.
   *
   * The caller holds the host read lock.
   *
   * @param file Scala or Java source
   * @param document the file's document
   * @param line the line, counted from 0
   * @return the calls whose name is on the line, in the order they run, numbered from 1; empty for a line the document does not have
   */
  def at(file: PsiFile, document: Document, line: Int): Seq[DebugAdapter.StepTarget] =
    if (line < 0 || line >= document.getLineCount) Seq.empty
    else {
      val lineRange = new TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
      val calls = ListBuffer.empty[Call]

      // Children before their parent: that is the order in which the calls run.
      def visit(element: PsiElement): Unit =
        if (element.getTextRange.intersects(lineRange)) {
          element.getChildren.foreach(visit)
          call(element).filter(found => lineRange.containsOffset(found.offset)).foreach(calls += _)
        }

      visit(file)
      calls.toSeq.zipWithIndex.map((found, index) => DebugAdapter.StepTarget(index + 1, found.label, found.methodName))
    }

  /** The call an element is, when it is one the debugger can step into. */
  private def call(element: PsiElement): Option[Call] = element match {
    case invocation: ScMethodCall => callee(invocation.getInvokedExpr).flatMap(scalaCall)
    case sugar: ScSugarCallExpr => scalaCall(sugar.operation)
    case reference: ScReferenceExpression if !isCallee(reference) => scalaCall(reference).filter(_ => isParameterless(reference))
    case constructor: ScConstructorInvocation =>
      Some(Call(constructor.getTextRange.getStartOffset, s"new ${constructor.typeElement.getText}", "<init>"))
    case invocation: PsiMethodCallExpression =>
      Option(invocation.resolveMethod()).map { method =>
        val name = Option(invocation.getMethodExpression.getReferenceNameElement).getOrElse(invocation)
        Call(name.getTextRange.getStartOffset, label(method), methodName(method))
      }
    case creation: PsiNewExpression =>
      Option(creation.getClassOrAnonymousClassReference).flatMap(reference => Option(reference.getReferenceName))
        .map(name => Call(creation.getTextRange.getStartOffset, s"new $name", "<init>"))
    case _ => None
  }

  /** The reference an invoked expression names, looking through type arguments. */
  private def callee(invoked: ScExpression): Option[ScReferenceExpression] = invoked match {
    case reference: ScReferenceExpression => Some(reference)
    case generic: ScGenericCall => callee(generic.referencedExpr)
    case _ => None
  }

  /** Whether the reference is the method of a call that the enclosing expression counts: it must not be counted twice. */
  private def isCallee(reference: ScReferenceExpression): Boolean = reference.getParent match {
    case invocation: ScMethodCall => invocation.getInvokedExpr eq reference
    case sugar: ScSugarCallExpr => sugar.operation eq reference
    case _: ScGenericCall => true
    case _ => false
  }

  /** The call of a method a reference resolves to; none for what is not a method (a value, a synthetic operator). */
  private def scalaCall(reference: ScReferenceExpression): Option[Call] = reference.resolve() match {
    case method: PsiMethod => Some(Call(reference.nameId.getTextRange.getStartOffset, label(method), methodName(method)))
    case _ => None
  }

  /**
   * Whether a reference to a method calls it without arguments (`xs.size`): a method that takes parameters, named
   * alone, is a function value, not a call.
   */
  private def isParameterless(reference: ScReferenceExpression): Boolean = reference.resolve() match {
    case function: ScFunction => function.parameters.isEmpty
    case method: PsiMethod => method.getParameterList.isEmpty
    case _ => false
  }

  /** What the editor lists for a method: its name and parameters, as written for Scala and by type for Java. */
  private def label(method: PsiMethod): String = method match {
    case function: ScFunction => function.name + function.paramClauses.getText.replaceAll("\\s+", " ")
    case _ =>
      method.getName + method.getParameterList.getParameters.map(_.getType.getPresentableText).mkString("(", ", ", ")")
  }

  /** The name the JVM gives a method: a constructor is `<init>`, and a Scala operator's symbols are encoded (`+` is `$plus`). */
  private def methodName(method: PsiMethod): String =
    if (method.isConstructor) "<init>"
    else method match {
      case function: ScFunction => NameTransformer.encode(function.name)
      case _ => method.getName
    }
}
