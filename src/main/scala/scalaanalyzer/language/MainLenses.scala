package scalaanalyzer.language

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.util.ScalaMainMethodUtil

import scalaanalyzer.platform.HostLock

import scala.jdk.CollectionConverters.*

/**
 * Finds the main classes where the IDE puts its run markers, with the class name the JVM launches.
 *
 * The plugin's run configuration producer needs the IDE's execution framework, and its helpers are private, so this
 * calls what they call: `ScalaMainMethodUtil`. Its provider-based class check finds nothing in the host, which
 * registers no main method providers, so objects are checked for the main method itself.
 */
object MainLenses {
  /**
   * A main class anchored to a source range.
   *
   * @param range source range of the name of the object or `@main` method
   * @param mainClass JVM name of the class that has the `main` method
   */
  final case class Lens(range: TextRange, mainClass: String)

  /**
   * Finds the main classes of a file.
   *
   * Runs under a cancellable host read. A top-level object whose `main` method is its own or inherited (`App`), and a
   * Scala 3 `@main` method, each have one lens; the IDE's second marker, on the method of an object, has none. A
   * worksheet or script has no lens, as the IDE shows no marker there.
   *
   * @param file Scala file to scan
   * @return a lens per main class, ordered by start offset
   */
  def in(file: ScalaFile): Seq[Lens] = HostLock.query {
    if (file.isWorksheetFile || file.isMultipleDeclarationsAllowed) Seq.empty
    else {
      val objects = PsiTreeUtil.findChildrenOfType(file, classOf[ScObject]).asScala.toSeq
        .filter(ScalaMainMethodUtil.hasScala2MainMethod)
        .map(main => Lens(main.nameId.getTextRange, main.qualifiedName))
      val annotated = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunctionDefinition]).asScala.toSeq
        .filter(ScalaMainMethodUtil.isScala3MainMethod)
        .map(main => Lens(main.nameId.getTextRange, scala3MainClass(main)))

      (objects ++ annotated).sortBy(_.range.getStartOffset)
    }
  }

  /** The class the compiler generates for a `@main` method: named after the method, in the package of its place. */
  private def scala3MainClass(main: ScFunctionDefinition): String =
    (Option(ScalaPsiUtil.getPlacePackageName(main)).filter(_.nonEmpty).toSeq :+ main.name).mkString(".")
}
