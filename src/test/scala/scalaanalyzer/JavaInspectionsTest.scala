package scalaanalyzer

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiJavaFile

import scalaanalyzer.language.Inspections
import scalaanalyzer.platform.{Descriptors, ExtensionBean, HostLog}

import java.io.File
import java.nio.file.Path

import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * The Java plugin's default local inspections, loaded from its descriptors: the audit that the exclusion set of
 * `Inspections.JavaExclusions` comes from, and the problems, suppression and fixes of the ones that run.
 */
class JavaInspectionsTest extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private def host = TestHost.host
  private lazy val module = {
    TestHost.libraryReport // the JDK
    host.addModule(ModuleSpec("java-inspections", Some("2.13.18"), Seq.empty,
      Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
  }

  private def open(name: String, text: String): PsiJavaFile =
    host.openFile(Path.of(s"/java-inspections/$name.java"), text, module) match {
      case java: PsiJavaFile => java
      case other => fail(s"not a Java file: $other")
    }

  /** Common Java constructs (see the resource), which the audit gives every inspection to run over. */
  private lazy val corpus: String = Using.resource(Source.fromResource("java-inspections-corpus.txt"))(_.mkString)

  /**
   * One audited inspection.
   *
   * @param id the inspection's id
   * @param nanos time spent constructing and running it
   * @param failure what went wrong, when something did: an exception, or an error the platform logged
   */
  private final case class Audited(id: String, nanos: Long, failure: Option[String])

  /** Constructs and runs one inspection over `file` in a read of its own, as `Inspections.runJava` isolates it. */
  private def audit(inspections: Inspections, file: PsiJavaFile, bean: ExtensionBean): Audited = {
    val run = host.read(inspections.runJava(file, Seq(bean))).head
    // The error was logged on this thread, just now: the newest one is the run's.
    val logged = HostLog.recentProblems.filter(_.level == HostLog.Level.Error).lastOption
      .flatMap(_.message.linesIterator.nextOption())
    val thrown = run.failure.map(failure =>
      s"${failure.phase}: ${failure.error.getClass.getName}: ${failure.error.getMessage}")
    val failure = thrown.orElse(Option.when(run.loggedErrors > 0)(
      s"logged ${run.loggedErrors} error(s), the last: ${logged.getOrElse("(not recorded)")}"))

    Audited(run.id, run.nanos, failure)
  }

  test("every loaded Java inspection constructs and runs over the corpus, but those excluded for failing") {
    val loaded = Descriptors.javaLocalInspections
    // The descriptors are read as data: a lookup that found none would pass this audit with nothing audited.
    assert(loaded.size > 400, s"loaded ${loaded.size} Java inspections")
    val scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_))
    // Not the host's own, which leaves the excluded inspections out: the audit runs every one loaded.
    val inspections = new Inspections(Descriptors.scalaExtensionBeans(scalaPluginJars, "com.intellij.localInspection"), loaded)

    val file = open("Corpus", corpus)
    val audited =
      try loaded.map(audit(inspections, file, _))
      finally host.closeSource(file)

    val failing = audited.flatMap(run => run.failure.map(run.id -> _)).toMap
    val excluded = Inspections.JavaExclusions
    val unexpected = failing.filterNot((id, _) => excluded.contains(id)).toSeq.sortBy(_._1)
    val stale = excluded.keys.filterNot(failing.contains).toSeq.sorted

    val total = audited.map(_.nanos).sum / 1_000_000
    val slowest = audited.sortBy(-_.nanos).take(10).map(run => f"  ${run.id}%-48s ${run.nanos / 1_000_000}%6d ms")
    println(s"Java inspections audit: ${audited.size} run over the corpus in $total ms, ${failing.size} failing, " +
      s"${excluded.size} excluded. The first runs include class loading. The 10 slowest:\n${slowest.mkString("\n")}")

    val report = Seq(
      Option.when(unexpected.nonEmpty)(
        s"failing, to fix or to exclude with the reason:\n${unexpected.map((id, why) => s"  $id: $why").mkString("\n")}"),
      Option.when(stale.nonEmpty)(
        s"excluded, but no longer failing (or not loaded), to remove from the exclusions:\n${stale.map("  " + _).mkString("\n")}"),
    ).flatten
    assert(report.isEmpty, report.mkString("\n"))
  }

  // The highlighted texts and message fragments come from the platform's code and bundles (RedundantCast highlights the
  // cast's type element).
  private val reported = Seq(
    // (shortName, source, highlighted text, fragment of the message, severity)
    ("StringEquality", "class StringEq { boolean f(String s) { return s == \"x\"; } }", "==", "not 'equals()'",
      HighlightSeverity.WARNING),
    ("RedundantCast", "class Cast { String f(String s) { return (String) s; } }", "String", "redundant",
      HighlightSeverity.WARNING),
    // Data flow, over the control flow that the Java plugin's provider builds.
    ("DataFlowIssue", "class Npe { int f() { String s = null; return s.length(); } }", "length",
      "NullPointerException", HighlightSeverity.WARNING),
    ("ConstantValue", "class Cv { void f() { int x = 1; if (x == 1) { System.out.println(x); } } }", "x == 1",
      "always 'true'", HighlightSeverity.WARNING),
    ("PointlessBooleanExpression", "class Bool { boolean f(boolean b) { return b && true; } }", "b && true",
      "can be simplified", HighlightSeverity.WARNING),
  )

  test("the default Java inspections report their problems, in the inspection diagnostics and in Inspect Code") {
    for ((id, text, highlighted, fragment, severity) <- reported) {
      val file = open("Reported", text)
      try {
        val found = host.inspect(file).filter(_.inspectionId.contains(id))
        val problem = found match {
          case Seq(only) => only
          case other => fail(s"$id: $other in ${host.inspect(file).map(_.inspectionId)}")
        }
        assertEquals(problem.range.substring(text), highlighted, id)
        assert(problem.message.contains(fragment), problem.toString)
        assert(!problem.message.contains("<"), s"the message is plain text: ${problem.message}")
        assertEquals(problem.severity, severity, id)
        assertEquals(host.inspectionDiagnostics(file).filter(_.inspectionId.contains(id)).map(_.range), Seq(problem.range))
        assert(host.inspectionDescriptions.exists(_.id == id), id)
      } finally host.closeSource(file)
    }
  }

  test("a //noinspection comment and @SuppressWarnings suppress the inspection they name") {
    val cases = Seq(
      "class S {\n  boolean f(String s) {\n    //noinspection StringEquality\n    return s == \"x\";\n  }\n}",
      "class S {\n  @SuppressWarnings(\"StringEquality\")\n  boolean f(String s) { return s == \"x\"; }\n}",
      // The alternative id of an inspection that has one is a suppression too: `static-access` of
      // AccessStaticViaInstance, which the descriptor lists as its alternative id.
      "class S {\n  static int n;\n  @SuppressWarnings(\"static-access\")\n  int f(S s) { return s.n; }\n}",
    )
    val reportedIds = Seq("StringEquality", "StringEquality", "AccessStaticViaInstance")
    for ((text, id) <- cases.zip(reportedIds)) {
      val file = open("Suppressed", text)
      try {
        assertEquals(host.inspect(file).filter(_.inspectionId.contains(id)), Seq.empty, text)
        // The comment names the inspection: without it the same code is reported.
        val unsuppressed = text.replace("//noinspection StringEquality\n", "").replaceAll("  @SuppressWarnings\\(\"[^\"]+\"\\)\n", "")
        host.edit(file, 0, text.length, unsuppressed)
        assertEquals(host.inspect(file).count(_.inspectionId.contains(id)), 1, unsuppressed)
      } finally host.closeSource(file)
    }
  }

  test("a Java inspection problem offers its fix and the suppressions that enclose it, previewed on a copy") {
    val text = "class Fixes {\n  boolean f(String s) {\n    return s == \"x\";\n  }\n}"
    val file = open("Fixes", text)
    try {
      val problem = host.inspect(file).find(_.inspectionId.contains("StringEquality")).getOrElse(fail("StringEquality"))
      // The file's diagnostics are not cached, as when a client shows a problem that it asks the fixes of at once.
      val actions = host.codeActions(file, problem.range, withInspections = true).choices
        .filter(_.problems.exists(about => about.inspectionId.contains("StringEquality") && about.range == problem.range))
      assert(actions.nonEmpty, "no action for the problem")
      assert(actions.forall(_.kind == "quickfix"), actions.toString)

      // The fix replaces `==` with a call of `equals`, whatever its title.
      val equalsFix = actions.find(action => action.edits.nonEmpty &&
        TestHost.applyEdits(text, action.edits).contains(".equals(")).getOrElse(fail(actions.toString))
      assertEquals(equalsFix.disabled, None)
      assert(!TestHost.applyEdits(text, equalsFix.edits).contains("=="), TestHost.applyEdits(text, equalsFix.edits))

      // The Java plugin offers a `//noinspection` comment for the statement among its suppressions.
      val comment = actions.find(action => action.edits.nonEmpty &&
        TestHost.applyEdits(text, action.edits).contains("//noinspection StringEquality")).getOrElse(fail(actions.toString))
      assertEquals(comment.disabled, None)
      val suppressed = TestHost.applyEdits(text, comment.edits)
      assertEquals(host.read(file.getText), text)
      host.edit(file, 0, text.length, suppressed)
      assert(!host.inspect(file).exists(_.inspectionId.contains("StringEquality")), suppressed)
    } finally host.closeSource(file)
  }
}
