package scalaanalyzer.language

import com.intellij.execution.PsiLocation
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testIntegration.TestFramework
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScInfixExpr
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTrait, ScTypeDefinition}
import org.jetbrains.plugins.scala.testingSupport.MUnitLocations
import org.jetbrains.plugins.scala.testingSupport.test.{AbstractTestFramework, TestConfigurationUtil}
import org.jetbrains.plugins.scala.testingSupport.test.munit.MUnitTestFramework
import org.jetbrains.plugins.scala.testingSupport.test.scalatest.{ScalaTestAstTransformer, ScalaTestSingleTestLocationFinderOld, ScalaTestTestFramework, ScalaTestTestLocationsFinder}
import org.jetbrains.plugins.scala.testingSupport.test.specs2.{Specs2TestFramework, Specs2TestLocationsFinder}
import org.jetbrains.plugins.scala.testingSupport.test.structureView.TestNodeProvider
import org.jetbrains.plugins.scala.testingSupport.test.utest.UTestTestFramework

import scalaanalyzer.platform.HostLock

import scala.jdk.CollectionConverters.*

/**
 * Finds runnable suites and tests where the IDE puts its run markers, with the names the plugin's runners select.
 *
 * The plugin's run configuration producers need the IDE's execution framework, so this calls what they call.
 * uTest has suite lenses only: its test paths are private to its producer.
 */
object TestLenses {
  /**
   * A suite, or one of its tests, anchored to a source range.
   *
   * @param range source range of the suite's or test's name when it has one, else of the whole element
   * @param framework id of the test framework: `scalatest`, `specs2`, `utest` or `munit`
   * @param suite qualified name of the suite
   * @param testName the test's name in the runner's encoding, or `None` for a lens that runs the whole suite
   */
  final case class Lens(range: TextRange, framework: String, suite: String, testName: Option[String])

  /** The frameworks and their suite detectors; `apply` would look them up as IDE extensions, which the host lacks. */
  private enum Framework(val id: String, val detector: AbstractTestFramework) {
    case ScalaTest extends Framework("scalatest", new ScalaTestTestFramework)
    case Specs2 extends Framework("specs2", new Specs2TestFramework)
    case UTest extends Framework("utest", new UTestTestFramework)
    case MUnit extends Framework("munit", new MUnitTestFramework)
  }

  /**
   * The detectors to install as the platform's `TestFramework` extensions, which `TestFrameworks` asks whether a
   * class is a test (Go to Test, the icons of test classes).
   *
   * uTest is left out: for a class suite its detector reads the library version from IDE services the host lacks,
   * and it would run on every class that is asked about.
   *
   * @return the same detector instances that `in` uses, one per framework
   */
  def platformFrameworks: Seq[TestFramework] = Framework.values.toSeq.filter(_ != Framework.UTest).map(_.detector)

  /**
   * Finds the runnable suites of a file and their tests.
   *
   * Runs under a cancellable host read. Every type definition, nested ones included, is checked against the
   * supported frameworks; a suite with no recognized framework, or one the IDE would not offer to run, has no lens.
   *
   * @param file Scala file to scan
   * @param module module that owns the file
   * @return a suite lens for each runnable suite, plus a lens for each of its tests whose name is static, ordered by
   *         start offset
   */
  def in(file: ScalaFile, module: Module): Seq[Lens] = HostLock.query {
    PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeDefinition]).asScala.toSeq
      .flatMap(suite => frameworkOf(suite).filter(runnable(suite, _)).toSeq.flatMap(lenses(suite, _, module)))
      .sortBy(_.range.getStartOffset)
  }

  /** The first framework whose detector recognizes the suite. */
  private def frameworkOf(suite: ScTypeDefinition): Option[Framework] = Framework.values.find {
    // uTest detection of a class suite reads the library version from IDE services the host lacks.
    case Framework.UTest => suite.isInstanceOf[ScObject] && Framework.UTest.detector.isTestClass(suite: PsiElement)
    case framework => framework.detector.isTestClass(suite: PsiElement)
  }

  /** As the IDE's run markers, plus abstract classes: the ScalaTest runner instantiates the suite class. */
  private def runnable(suite: ScTypeDefinition, framework: Framework): Boolean = framework match {
    case Framework.ScalaTest =>
      !(suite.isInstanceOf[ScTrait] || suite.isInstanceOf[ScObject] || suite.hasModifierPropertyScala("abstract"))
    case _ => true
  }

  /** The suite's own lens followed by one lens per test whose name the runner can select. */
  private def lenses(suite: ScTypeDefinition, framework: Framework, module: Module): Seq[Lens] = {
    // A test whose name is not static has no lens: running it by an empty name would run the whole suite.
    val tests = testLocations(suite, framework).flatMap { element =>
      testName(element, suite, framework, module).map(name => Lens(anchor(element), framework.id, suite.qualifiedName, Some(name)))
    }
    Lens(anchor(suite), framework.id, suite.qualifiedName, None) +: tests
  }

  /** The elements the framework's finder anchors the suite's tests to; none for uTest. */
  private def testLocations(suite: ScTypeDefinition, framework: Framework): Seq[PsiElement] = framework match {
    case Framework.ScalaTest => ScalaTestTestLocationsFinder.calculateTestLocations(suite)
    case Framework.Specs2 => Specs2TestLocationsFinder.calculateTestLocations(suite)
    case Framework.MUnit => MUnitLocations.in(suite)
    case Framework.UTest => Seq.empty
  }

  /** The runner's name for the test at `element`, or none when it has no static name. */
  private def testName(element: PsiElement, suite: ScTypeDefinition, framework: Framework, module: Module): Option[String] =
    framework match {
      case Framework.ScalaTest => scalaTestName(element, suite, module)
      case Framework.Specs2 => specs2TestName(element)
      case Framework.MUnit => legacyFinder(element, suite).flatMap(_.findTestNameForMUnit())
      case Framework.UTest => None
    }

  /** As `ScalaTestConfigurationProducer`: the finder's selection, retried on the parent, then the legacy finder. */
  private def scalaTestName(element: PsiElement, suite: ScTypeDefinition, module: Module): Option[String] =
    ScalaTestAstTransformer.testSelection(new PsiLocation(element.getProject, module, element)) match {
      case Some(selection) if selection.testNames.nonEmpty => Some(selection.testNames.toSeq.map(_.trim).mkString("\n"))
      case Some(_) => Option(element.getParent).flatMap(scalaTestName(_, suite, module))
      case None => legacyFinder(element, suite).flatMap(_.findTestName)
    }

  /** As `Specs2ConfigurationProducer`: the static text left of the enclosing test expression. */
  private def specs2TestName(element: PsiElement): Option[String] =
    ScalaPsiUtil.getParentWithProperty(element, strict = false, TestNodeProvider.isSpecs2TestExpr) match {
      case Some(test: ScInfixExpr) => TestConfigurationUtil.getStaticTestName(test.left)
      case _ => None
    }

  /** The plugin's single-test finder over the suite's template body, or none when the suite has no body. */
  private def legacyFinder(element: PsiElement, suite: ScTypeDefinition): Option[ScalaTestSingleTestLocationFinderOld] =
    suite.extendsBlock.templateBody.map(new ScalaTestSingleTestLocationFinderOld(element, suite, _))

  /** RefSpec tests are methods and objects: their lens sits on the name, not the whole body. */
  private def anchor(element: PsiElement): TextRange = element match {
    case named: PsiNameIdentifierOwner => named.getNameIdentifier.getTextRange
    case _ => element.getTextRange
  }
}
