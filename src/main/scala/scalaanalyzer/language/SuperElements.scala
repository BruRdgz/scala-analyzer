package scalaanalyzer.language

import com.intellij.psi.impl.FindSuperElementsHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{
  LambdaUtil, PsiClass, PsiElement, PsiFile, PsiFunctionalExpression, PsiJavaFile, PsiMember, PsiMethod,
  PsiNameIdentifierOwner,
}
import org.jetbrains.plugins.scala.annotator.gutter.ScalaGoToSuperActionHandler
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import java.lang.reflect.InvocationTargetException

/**
 * Finds what the IDE's Go to Super action (Ctrl+U) navigates to: the superclasses of a type, or the members that a
 * member overrides or implements; in Java also the method that a lambda or method reference implements.
 *
 * The actions only offer their result in a popup, and the functions that compute it are private: the Scala plugin's
 * is in a private object, which is called by reflection; the Java plugin's is a private method of
 * `JavaGotoSuperHandler`, a class that the host cannot load (its other methods name navigation UI types), so
 * [[inJava]] repeats its few lines.
 */
object SuperElements {
  /** The private companion's module class, which Scala compiles to a public class. */
  private val Companion = Class.forName(classOf[ScalaGoToSuperActionHandler].getName + "$")

  /** The companion's `findSuperElements(file, offset)`. */
  private val Find = Companion.getMethod("findSuperElements", classOf[PsiFile], classOf[Int])

  /** The singleton `Find` runs on. */
  private val Instance = Companion.getField("MODULE$").get(null)

  /**
   * Collects the supers of the declaration around an offset, as the action does.
   *
   * The caller holds the host read lock.
   *
   * @param file Scala or Java source being queried
   * @param offset UTF-16 offset of the caret
   * @return for Scala, the superclasses of the enclosing type, then the overridden or implemented members; for Java,
   *         the supers of the innermost method, class or functional expression around the caret; empty when the caret
   *         is in no declaration that has them, and for a file of another language
   */
  def at(file: PsiFile, offset: Int): Seq[PsiElement] =
    try file match {
      case scala: ScalaFile =>
        // The method returns a `(Seq[PsiElement], Seq[PsiElement])`, which reflection erases to `Object`.
        val (classes, members) =
          Find.invoke(Instance, scala, Int.box(offset)).asInstanceOf[(Seq[PsiElement], Seq[PsiElement])]

        classes ++ members
      case java: PsiJavaFile => inJava(java, offset)
      case _ => Seq.empty
    } catch { case error: InvocationTargetException => throw error.getCause }

  /**
   * `JavaGotoSuperHandler.findSuperElements`: in a lambda or method reference, the method it implements and that
   * method's supers; else the supers of the innermost method or class.
   */
  private def inJava(file: PsiJavaFile, offset: Int): Seq[PsiElement] = Option(file.findElementAt(offset)).toSeq.flatMap {
    element =>
      val implemented = PsiTreeUtil.getParentOfType(element, classOf[PsiFunctionalExpression], classOf[PsiMember]) match {
        case functional: PsiFunctionalExpression => Option(LambdaUtil.getFunctionalInterfaceMethod(functional))
        case _ => None
      }

      implemented match {
        case Some(method) => method +: method.findSuperMethods(false).toSeq
        case None =>
          Option(PsiTreeUtil.getNonStrictParentOfType[PsiNameIdentifierOwner](element, classOf[PsiMethod], classOf[PsiClass]))
            .toSeq.flatMap(owner => FindSuperElementsHelper.findSuperElements(owner).toSeq)
      }
  }
}
