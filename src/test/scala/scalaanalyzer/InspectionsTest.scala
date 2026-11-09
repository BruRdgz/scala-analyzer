package scalaanalyzer

import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import scalaanalyzer.language.CodeActions
import scalaanalyzer.platform.HostLog

import java.nio.file.Path

/** Inspection diagnostics, native suppression and previewed fixes against editable source buffers. */
class InspectionsTest extends munit.FunSuite {
  private def host = TestHost.host
  private lazy val module = {
    TestHost.libraryReport
    host.addModule(ModuleSpec("inspections", "2.13.18", Seq.empty,
      Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
  }

  private def open(name: String, text: String): ScalaFile =
    host.openFile(Path.of(s"/inspections/$name.scala"), text, module).asInstanceOf[ScalaFile]

  test("native simplification warnings supply applicable fixes without mutating source") {
    val cases = Seq(
      ("SimplifyBoolean", "object Inspect { def f(b: Boolean): Boolean = b == true }", "b == true", "b == true"),
      ("ScalaRedundantCast", "object Inspect { val n = 1.asInstanceOf[Int] }", "asInstanceOf[Int]", "asInstanceOf"),
      ("ScalaUnusedExpression", "object Inspect { def f: Int = { 1 + 2; 3 } }", "1 + 2", "1 + 2"),
      ("ScalaUnusedSymbol", "object Inspect { private val dead = 2 }", "dead", "dead"),
      ("RemoveRedundantReturn", "object Inspect { def f: Int = { return 1 } }", "return", "return"),
      ("ScalaUnnecessarySemicolon", "object Inspect { val n = 1;\n}", ";", ";"),
    )
    for ((id, text, highlighted, removed) <- cases) {
      val file = open("Inspect", text)
      try {
        val found = host.diagnostics(file)
        val problem = found.find(_.inspectionId.contains(id)).getOrElse(fail(s"$id: $found"))
        assert(problem.range.substring(text).contains(highlighted), problem.toString)
        assert(problem.fixes.nonEmpty, problem.toString)
        val actions = new CodeActions(Seq.empty, Seq.empty)
        val warnings = HostLog.count(HostLog.Level.Warn)
        val choices = host.read(actions.collect(file, problem.range, Seq(problem)))
        assertEquals(HostLog.count(HostLog.Level.Warn), warnings, choices.toString)
        if (id == "ScalaUnusedExpression") {
          val unsafeReturn = choices.find(_.title == "Add return keyword").getOrElse(fail(choices.toString))
          assert(unsafeReturn.disabled.nonEmpty && unsafeReturn.edits.isEmpty, unsafeReturn.toString)
        }
        val chosen = choices.find(c => c.disabled.isEmpty && c.edits.nonEmpty).getOrElse(fail(choices.toString))
        val result = TestHost.applyEdits(text, chosen.edits)
        assert(!result.contains(removed), result)
        assertEquals(host.read(file.getText), text)
        host.edit(file, 0, text.length, result)
        assert(!host.diagnostics(file).exists(_.inspectionId.contains(id)), result)
      } finally host.closeSource(file)
    }
  }

  test("Scala 3 inspections supplement compiler highlighting without build warning flags") {
    val scala3 = host.addModule(ModuleSpec("inspections3", "3.3.4", Seq.empty,
      Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
    val text = "object Inspect3:\n  private val dead = 1\n  def f(b: Boolean): Boolean = b == true\n"
    val file = host.openFile(Path.of("/inspections3/Inspect3.scala"), text, scala3).asInstanceOf[ScalaFile]
    try {
      assert(host.isCompilerHighlighted(file))
      val ids = host.diagnostics(file).flatMap(_.inspectionId).toSet
      assert(ids.contains("ScalaUnusedSymbol") && ids.contains("SimplifyBoolean"), ids.toString)
    } finally {
      host.closeSource(file)
      host.removeModule(scala3.spec.stableId)
    }
  }

  test("unused analysis preserves public API, local references, overrides and annotation exemptions") {
    val text = """object Inspect {
      |  def publicApi: Int = 1
      |  private val dead = 2
      |  private class DeadClass
      |  private val live = 3
      |  private def helper: Int = live
      |  private def recursive: Int = recursive
      |  @scala.annotation.unused private val intentional = 4
      |  def f: Int = { val localDead = 5; helper }
      |  override def toString: String = "Inspect"
      |}
      |""".stripMargin
    val file = open("Inspect", text)
    try {
      val unused = host.diagnostics(file).filter(_.inspectionId.contains("ScalaUnusedSymbol"))
      assertEquals(unused.map(_.range.substring(text)).toSet, Set("dead", "localDead", "DeadClass", "recursive"))
      assert(unused.forall(_.unnecessary), unused.toString)
    } finally host.closeSource(file)
  }

  test("unused fixes preserve initializer effects and keep Safe Delete unavailable") {
    val text = "object Inspect { private val dead = println(\"effect\"); def f(unused: Int): Int = 1 }"
    val file = open("Inspect", text)
    try {
      val unused = host.diagnostics(file).filter(_.inspectionId.contains("ScalaUnusedSymbol"))
      val binding = unused.find(_.range.substring(text) == "dead").getOrElse(fail(unused.toString))
      val collector = new CodeActions(Seq.empty, Seq.empty)
      val actions = host.read(collector.collect(file, binding.range, Seq(binding)))
      val preserveEffects = actions.find(_.title == "Remove only dead binding").getOrElse(fail(actions.toString))
      assertEquals(preserveEffects.disabled, None)
      val result = TestHost.applyEdits(text, preserveEffects.edits)
      assert(!result.contains("val dead") && result.contains("println(\"effect\")"), result)
      val parameter = unused.find(_.range.substring(text) == "unused").getOrElse(fail(unused.toString))
      val parameterActions = host.read(collector.collect(file, parameter.range, Seq(parameter)))
      val removal = parameterActions.find(_.title == "Remove unused element").getOrElse(fail(parameterActions.toString))
      assert(removal.disabled.nonEmpty && removal.edits.isEmpty, removal.toString)
      assertEquals(host.read(file.getText), text)
    } finally host.closeSource(file)
  }

  test("explicit unused imports offer the existing optimizer as a quick fix") {
    val text = "import scala.util.Try\nimport scala.util.Random\nobject Inspect { val n = Random.nextInt() }"
    val file = open("Inspect", text)
    try {
      val unused = host.diagnostics(file).filter(_.inspectionId.contains("ScalaUnusedImport"))
      assertEquals(unused.size, 1, unused.toString)
      assert(unused.head.range.substring(text).contains("Try"), unused.toString)
      val actions = host.read(new CodeActions(Seq.empty, Seq.empty).collect(file, unused.head.range, unused))
      val fix = actions.find(a => a.disabled.isEmpty && a.edits.nonEmpty).getOrElse(fail(actions.toString))
      val result = TestHost.applyEdits(text, fix.edits)
      assert(!result.contains("Try") && result.contains("Random"), result)
      assertEquals(host.read(file.getText), text)
    } finally host.closeSource(file)
  }

  test("deprecation follows resolution and changes to declarations in another file") {
    val declaration = "object OldApi { @deprecated(\"Use fresh\", \"1\") def old: Int = 1 }"
    val api = open("OldApi", declaration)
    val client = open("Client", "object Client { val value = OldApi.old }")
    try {
      val found = host.diagnostics(client).filter(_.deprecated)
      assertEquals(found.map(_.range.substring(client.getText)), Seq("old"))
      assert(found.head.message.contains("Use fresh"), found.toString)
      host.edit(api, 0, declaration.length, "object OldApi { def old: Int = 1 }")
      assertEquals(host.diagnostics(client).filter(_.deprecated), Seq.empty)
    } finally {
      host.closeSource(client)
      host.closeSource(api)
    }
  }

  test("an inspection problem offers its fix and the plugin's suppressions, each naming the problem") {
    val cases = Seq(
      ("RemoveRedundantReturn", "object Inspect { def f: Int = { return 1 } }", "Suppress for statement"),
      ("ScalaUnusedImport", "import scala.util.Try\nobject Inspect", "Suppress for file"),
    )
    for ((id, text, title) <- cases) {
      val file = open("Inspect", text)
      try {
        val problem = host.inspect(file).find(_.inspectionId.contains(id)).getOrElse(fail(id))
        // The file's diagnostics are not cached, as when a client shows a problem that it asks the fixes of at once.
        val actions = host.codeActions(file, problem.range, withInspections = true)
          .filter(_.problems.exists(about => about.inspectionId.contains(id) && about.range == problem.range))
        val (suppressions, fixes) = actions.partition(_.title.startsWith("Suppress"))
        assert(fixes.exists(fix => fix.disabled.isEmpty && fix.edits.nonEmpty), actions.toString)
        val suppression = suppressions.find(_.title == title).getOrElse(fail(actions.toString))
        assertEquals(suppression.disabled, None)
        val suppressed = TestHost.applyEdits(text, suppression.edits)
        assert(suppressed.contains(s"//noinspection $id"), suppressed)
        assertEquals(host.read(file.getText), text)
        host.edit(file, 0, text.length, suppressed)
        assert(!host.diagnostics(file).exists(_.inspectionId.contains(id)), suppressed)
      } finally host.closeSource(file)
    }
  }

  test("Scala suppression comments suppress the selected inspection") {
    val cases = Seq(
      "object Inspect {\n //noinspection SimplifyBoolean\n def f(b: Boolean): Boolean = b == true\n}",
      "//noinspection ALL\nobject Inspect { def f(b: Boolean): Boolean = b == true }",
    )
    for (text <- cases) {
      val file = open("Inspect", text)
      try assertEquals(host.diagnostics(file).filter(_.inspectionId.contains("SimplifyBoolean")), Seq.empty)
      finally host.closeSource(file)
    }
  }
}
