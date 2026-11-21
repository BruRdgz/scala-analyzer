package scalaanalyzer

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiJavaFile

import java.nio.file.Path

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
    val module = host.addModule(ModuleSpec("java-diagnostics", Some("2.13.18"), Seq.empty, Seq.empty))
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

  test("Java sources that compile have no problems: on-demand imports, records and uses of Scala objects") {
    val host = TestHost.host
    TestHost.libraryReport
    val scalaLibrary = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary"))
    val module = host.addModule(ModuleSpec("java-clean", Some("2.13.18"), Seq.empty, Seq(scalaLibrary)))
    val greeter = host.openFile(Path.of("/java-clean/Greeter.scala"),
      "package clean\nobject Greeter { def hi(): String = \"hi\" }\n", module)
    val cases = Seq(
      "an on-demand import of a JDK package" ->
        "import java.util.*;\nclass JdkImport { List<String> names = new ArrayList<>(); }",
      "an on-demand import of a library package" ->
        "import scala.runtime.*;\nclass LibraryImport { Object unit = BoxedUnit.UNIT; }",
      "a record's implicit canonical constructor and accessor" ->
        "record Point(int x, int y) {}\nclass RecordUse { int x = new Point(1, 2).x(); }",
      "a Scala object through MODULE$ and its static forwarder" ->
        "package clean;\nclass ObjectUse { String a = Greeter$.MODULE$.hi(); String b = Greeter.hi(); }",
    )
    /** The problems of one case's file, as message and highlighted text. */
    def problems(text: String, index: Int): Seq[(String, String)] =
      host.openFile(Path.of(s"/java-clean/Clean$index.java"), text, module) match {
        case file: PsiJavaFile =>
          try host.diagnostics(file).map(d => d.message -> d.range.substring(text))
          finally host.closeSource(file)
        case other => fail(s"not Java: $other")
      }

    try {
      val found = cases.zipWithIndex.map { case ((label, text), index) => label -> problems(text, index) }
      assertEquals(found.filter(_._2.nonEmpty), Seq.empty)
    } finally {
      host.closeSource(greeter)
      host.removeModule(module.spec.stableId)
    }
  }

  test("a Java source is checked at its module's language level") {
    val host = TestHost.host
    TestHost.libraryReport
    val record = "record Point(int x) {}"
    val cases = Seq("11", "17").map { release =>
      host.addModule(ModuleSpec(s"java-level-$release", None, Seq.empty, Seq.empty, javacOptions = Seq("--release", release)))
    }

    /** The problems of the record in a module's file, as messages. */
    def messages(module: HostModule): Seq[String] =
      host.openFile(Path.of(s"/${module.spec.name}/Point.java"), record, module) match {
        case file: PsiJavaFile =>
          try host.diagnostics(file).map(_.message)
          finally host.closeSource(file)
        case other => fail(s"not Java: $other")
      }

    try {
      val found = cases.map(messages)
      assert(found.head.exists(_.contains("level")), found.head.toString)
      assertEquals(found(1), Seq.empty)
    } finally cases.foreach(module => host.removeModule(module.spec.stableId))
  }
}
