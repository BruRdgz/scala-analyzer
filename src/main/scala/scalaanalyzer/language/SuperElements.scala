package scalaanalyzer.language

import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.plugins.scala.annotator.gutter.ScalaGoToSuperActionHandler
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import java.lang.reflect.InvocationTargetException

/**
 * Finds what the plugin's Go to Super action (Ctrl+U) navigates to: the superclasses of a type, or the members that a
 * member overrides or implements.
 *
 * The action only offers its result in a popup, and the function that computes it is in a private object.
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
   * @param file Scala source being queried
   * @param offset UTF-16 offset of the caret
   * @return the superclasses of the enclosing type, then the overridden or implemented members; empty when the caret
   *         is in no declaration that has them
   */
  def at(file: ScalaFile, offset: Int): Seq[PsiElement] =
    try {
      // The method returns a `(Seq[PsiElement], Seq[PsiElement])`, which reflection erases to `Object`.
      val (classes, members) =
        Find.invoke(Instance, file, Int.box(offset)).asInstanceOf[(Seq[PsiElement], Seq[PsiElement])]

      classes ++ members
    } catch { case error: InvocationTargetException => throw error.getCause }
}
