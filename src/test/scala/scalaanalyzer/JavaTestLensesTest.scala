package scalaanalyzer

import com.intellij.psi.PsiJavaFile

import scalaanalyzer.language.TestLenses

import java.io.File
import java.nio.file.Path

import scala.concurrent.duration.*

/**
 * The JUnit lenses of Java files, against the JUnit plugin's own rules (`JUnitUtil`, its frameworks), with the JUnit 4
 * and 5 APIs on the module's classpath as the IDE resolves them from a project's libraries.
 */
class JavaTestLensesTest extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private def host = TestHost.host
  private lazy val module = {
    TestHost.libraryReport // the JDK
    val junit = sys.props("scalaanalyzer.fixture.junit").split(File.pathSeparator).toSeq.map(Path.of(_))

    host.addModule(ModuleSpec("java-test-lenses", None, Seq.empty, junit))
  }

  /** A lens as the editor shows it: the runner, the class, the method and the source text it sits on. */
  private case class Found(framework: String, suite: String, testName: Option[String], anchor: String)

  private def lensesOf(name: String, text: String): Seq[Found] =
    host.openFile(Path.of(s"/java-test-lenses/$name.java"), text, module) match {
      case java: PsiJavaFile =>
        TestLenses.in(java).map(lens =>
          Found(lens.framework, lens.suite, lens.testName, text.substring(lens.range.getStartOffset, lens.range.getEndOffset)))
      case other => fail(s"not a Java file: $other")
    }

  private val jupiter =
    """package demo;
      |import org.junit.jupiter.api.Nested;
      |import org.junit.jupiter.api.Test;
      |import org.junit.jupiter.params.ParameterizedTest;
      |import org.junit.jupiter.params.provider.ValueSource;
      |class Jupiter {
      |  @Test void first() {}
      |  @ParameterizedTest @ValueSource(strings = {"a"}) void params(String value) {}
      |  void helper() {}
      |  @Test private void hidden() {}
      |  @Nested class Inner { @Test void nested() {} }
      |  static class NotATest { void x() {} }
      |}
      |abstract class JupiterBase { @Test void inherited() {} }
      |""".stripMargin

  private val junit4 =
    """package demo;
      |import org.junit.Test;
      |public class Classic {
      |  @Test public void works() {}
      |  public void helper() {}
      |  @Test static void notAnInstanceMethod() {}
      |  public static class Inner { @Test public void inside() {} }
      |}
      |abstract class ClassicBase { @Test public void inherited() {} }
      |class Plain { public void test() {} }
      |""".stripMargin

  // Nested JUnit 5 classes are selected by their binary name, as the IDE's producer passes it; the parameterized method
  // by the IDE's `name(parameter types)`; an abstract class has no lens, as the runner cannot instantiate it.
  private val cases = Seq(
    ("Jupiter", jupiter, Seq(
      Found("junit5", "demo.Jupiter", None, "Jupiter"),
      Found("junit5", "demo.Jupiter", Some("first"), "first"),
      Found("junit5", "demo.Jupiter", Some("params(java.lang.String)"), "params"),
      Found("junit5", "demo.Jupiter$Inner", None, "Inner"),
      Found("junit5", "demo.Jupiter$Inner", Some("nested"), "nested"))),
    ("Classic", junit4, Seq(
      Found("junit4", "demo.Classic", None, "Classic"),
      Found("junit4", "demo.Classic", Some("works"), "works"),
      Found("junit4", "demo.Classic$Inner", None, "Inner"),
      Found("junit4", "demo.Classic$Inner", Some("inside"), "inside"))),
  )

  cases.foreach { (name, text, expected) =>
    test(s"finds the JUnit lenses of $name") {
      assertEquals(lensesOf(name, text), expected)
    }
  }
}
