package scalapsihost

import com.intellij.lang.annotation.HighlightSeverity

/**
 * The plugin's highlighting through the host. Expected problems are the ones scalac reports for the same code, with
 * the plugin's messages (ScalaBundle).
 */
class DiagnosticsTest extends munit.FunSuite {
  private def problems(file: org.jetbrains.plugins.scala.lang.psi.api.ScalaFile): Seq[(String, String)] =
    TestHost.host.diagnostics(file)
      .filter(_.severity.compareTo(HighlightSeverity.WARNING) >= 0)
      .map(d => d.message -> d.range.substring(file.getText))

  test("a correct file has no problems") {
    assertEquals(problems(TestHost.scenarioFile()), Seq.empty)
  }

  test("reports type mismatches, unresolved names and missing arguments") {
    TestHost.libraryReport
    val file = TestHost.host.openSource(
      """object Errors {
        |  def f(a: Int): Int = a
        |  val x: Int = "s"
        |  val y = undefinedName
        |  val z = f()
        |}
        |""".stripMargin)
    try {
      val found = problems(file)
      assert(found.contains("Type mismatch. Required: Int, found: String" -> "\"s\""), found)
      assert(found.contains("Cannot resolve symbol undefinedName" -> "undefinedName"), found)
      assert(found.exists(_._1 == "Unspecified value parameters: a: Int"), found)
      assertEquals(found.size, 3, found)
    } finally TestHost.host.closeSource(file)
  }

  test("reports syntax errors") {
    val file = TestHost.host.parse("object Broken { val = 1 }")
    assert(TestHost.host.diagnostics(file).exists(_.severity == HighlightSeverity.ERROR))
  }

  test("Java diagnostics resolve names, types and access against the current PSI") {
    val host = TestHost.host
    TestHost.libraryReport
    val module = host.addModule(ModuleSpec("java-diagnostics", "2.13.18", Seq.empty, Seq.empty))
    val text = "class JavaErrors { private int secret; void use() { int n = \"wrong\"; missing(); } } class Other { int n = new JavaErrors().secret; }"
    val file = host.openFile(java.nio.file.Path.of("/java-diagnostics/JavaErrors.java"), text, module) match {
      case f: com.intellij.psi.PsiJavaFile => f
      case other => fail(s"not Java: $other")
    }
    try {
      val found = host.diagnostics(file).map(d => d.message -> d.range.substring(text))
      assert(found.exists((message, range) => range.startsWith("\"wrong\"") && message.contains("int")), found.toString)
      assert(found.exists((message, range) => range == "missing" && message.contains("resolve")), found.toString)
      assert(found.exists((message, range) => range == "secret" && message.contains("private")), found.toString)
      host.edit(file, 0, text.length, "class JavaErrors { int n = 1; String value() { return \"ok\"; } }")
      assertEquals(host.diagnostics(file), Seq.empty)
    } finally {
      host.closeSource(file)
      host.removeModule(module.spec.stableId)
    }
  }

}
