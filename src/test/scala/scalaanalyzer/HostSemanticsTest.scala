package scalaanalyzer

import com.intellij.psi.{PsiErrorElement, PsiMethod}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.ScSyntheticFunction

/**
 * The plugin's parser, resolver and type checker answering through the host. Expectations are what the Scala
 * compiler (and the plugin inside IntelliJ) give for the scenario, not what the host currently returns.
 */
class HostSemanticsTest extends munit.FunSuite {
  private lazy val file = TestHost.scenarioFile()

  // Building the scenario writes to the host: before the tests, which run inside reads.
  override def beforeAll(): Unit = file

  // Every test reads PSI: each runs as a read of the host.
  override def munitTestTransforms: List[TestTransform] = super.munitTestTransforms :+
    new TestTransform("host read", test => test.withBody(() => TestHost.host.read(test.body())))

  test("parses into Scala PSI without syntax errors") {
    assertEquals(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).size(), 0)
  }

  // Each reference in source order, and the text of the declaration it must resolve to in the same file.
  private val localResolves = Seq(
    "a" -> "a",
    "x" -> "x: Int",
    "inc" -> "def inc(x: Int): Int = x + 1",
    "b" -> "b",
    "v" -> "val v: A",
    "c" -> "c",
    "x" -> "x",
  )

  test("resolves references to their local declarations") {
    val resolved = TestHost.references(file).filterNot(r => r.getText == "+" || r.getText == "f").map { ref =>
      val target = ref.resolve()
      assert(target != null, s"'${ref.getText}' is unresolved")
      assertEquals(target.getContainingFile, file, s"'${ref.getText}' resolved outside the file")
      ref.getText -> target.getText
    }
    assertEquals(resolved, localResolves)
  }

  // Arithmetic on primitives resolves to the plugin's synthetic members (registered at startup), not library code.
  test("resolves Int's + to the synthetic Int member") {
    val plus = TestHost.references(file).find(_.getText == "+").get
    plus.resolve() match {
      case m: ScSyntheticFunction => assertEquals(m.name, "+")
      case other => fail(s"'+' resolved to $other")
    }
  }

  // A call on a function value is sugar for apply: the reference resolves to Function1.apply, and the plugin keeps
  // the parameter `f` as the inner resolve result (see ScalaResolveResult.innerResolveResult).
  test("resolves a function value call to Function1.apply") {
    val f = TestHost.references(file).find(_.getText == "f").get
    f.resolve() match {
      case m: PsiMethod => assertEquals((m.getName, m.getContainingClass.getQualifiedName), ("apply", "scala.Function1"))
      case other => fail(s"'f' resolved to $other")
    }
  }

  private def typeOf(definition: String): String = {
    val pattern = TestHost.patternDefinitions(file).find(_.getText.startsWith(definition)).get
    pattern.expr.get.`type`().fold(failure => s"<failure: $failure>", _.presentableText(using pattern))
  }

  test("types literals and local references") {
    assertEquals(typeOf("val a"), "Int")
    assertEquals(typeOf("val b"), "Int")
  }

  test("types a method application") {
    assertEquals(typeOf("val c"), "Int")
  }

  test("types a for-comprehension through map") {
    assertEquals(typeOf("val d"), "Box[Int]")
  }
}
