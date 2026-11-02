package scalaanalyzer.oracle

import com.intellij.openapi.util.TextRange
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost}
import scalaanalyzer.platform.{CallLog, HostLog}
import scalaanalyzer.language.{ProjectSearch}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.regex.Matcher

import scala.collection.mutable
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.matching.Regex.Match

/**
 * Replays the plugin's go-to-implementation tests (`GoToImplementationTest` in Scala 2, `GoToImplementationTest_Scala3`
 * in Scala 3) without the IDE test harness: the literal text of each `doTest` call, the implementations the host finds
 * for the symbol at its caret, and the text ranges of those elements against the ones the test marks.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.ImplementationsOracle <intellij-scala checkout> <report.md> [--only=<name>,...]`.
 */
object ImplementationsOracle {
  private final case class TestCase(suite: String, name: String, body: String, scala3: Boolean)
  private final case class Result(outcome: String, reason: String)
  /** The text with markers removed, the caret and the ranges the test expects to be found. */
  private final case class Fixture(text: String, caret: Int, expected: Seq[TextRange])

  private val Scala2Suite = "GoToImplementationTest"
  private val Scala3Suite = "GoToImplementationTest_Scala3"
  private val Suites = Seq(Scala2Suite, Scala3Suite)
  private val TestMethod = "(?m)^  def test(\\w+)\\(\\): Unit =".r
  private val Call = "(?s)^doTest\\(\\s*".r
  private val Literal = "(?s)^(s?)\"\"\"(.*?)\"\"\"(\\.stripMargin)?".r
  private val Interpolation = """\$(\$|\{\w+\}|\w+)""".r
  private val Caret = "<caret>"
  private val Start = "<selection>"
  private val End = "</selection>"
  private val Values = Map("CARET" -> Caret, "START" -> Start, "END" -> End, "$" -> "$")

  def main(args: Array[String]): Unit = {
    val (Seq(repo, reportPath), flags) = args.toSeq.partition(!_.startsWith("--"))
    val only = flags.collectFirst { case f if f.startsWith("--only=") => f.stripPrefix("--only=").split(',').toSet }
    val cases = collect(Path.of(repo)).filter(c => only.forall(names => names(c.name) || names(c.suite + "/" + c.name)))
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      unitTestMode = true,
      compilerErrorsScala3 = false,
    ))
    host.addJdk(Path.of(System.getProperty("java.home")))
    val library = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary"))
    val library3 = Path.of(classOf[Mirror].getProtectionDomain.getCodeSource.getLocation.toURI)
    host.addLibrary(library)
    host.addLibrary(library3)
    val module2 = host.addModule(ModuleSpec("implementations-oracle-2", "2.13.18", Seq.empty, Seq(library)))
    val module3 = host.addModule(ModuleSpec("implementations-oracle-3", "3.9.0", Seq.empty, Seq(library, library3)))
    val started = System.nanoTime()
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = runCase(host, if (c.scala3) module3 else module2, c)
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    writeReport(Path.of(reportPath), results, (System.nanoTime() - started) / 1_000_000)
    for (suite <- Suites) println(summary(suite, results))
    println(s"implementations oracle: report at $reportPath")
    sys.exit(0)
  }

  private def collect(repo: Path): Vector[TestCase] = {
    val directory = repo.resolve("scala/scala-impl/test/org/jetbrains/plugins/scala/lang/navigation")
    def cases(suite: String, scala3: Boolean) =
      methodsOf(Files.readString(directory.resolve(suite + ".scala"))).map((name, body) => TestCase(suite, name, body, scala3))
    (cases(Scala2Suite, scala3 = false) ++ cases(Scala3Suite, scala3 = true)).toVector
  }

  private def methodsOf(source: String): Seq[(String, String)] = {
    val starts = TestMethod.findAllMatchIn(source).toVector
    starts.zip(starts.map(_.start).drop(1) :+ source.length).map((method, end) => method.group(1) -> source.substring(method.end, end))
  }

  /** The literal text of the `doTest` call, as the test builds it: interpolated, with its margins stripped, and trimmed. */
  private def literalOf(body: String): Either[String, String] = for {
    start <- Option(body.indexOf("doTest(")).filter(_ >= 0).toRight("no doTest call: the test checks the targets' presentation, which the host has no UI for")
    call <- Call.findPrefixMatchOf(body.substring(start)).toRight("unexpected doTest call")
    literal <- Literal.findPrefixMatchOf(body.substring(start + call.end)).toRight("non-literal test text")
    text = literal.group(2)
    interpolated <- if (literal.group(1) == "s") interpolate(text) else Right(text)
  } yield (if (literal.group(3) == null) interpolated else interpolated.stripMargin).trim

  private def interpolate(text: String): Either[String, String] = {
    def name(m: Match): String = m.group(1).stripPrefix("{").stripSuffix("}")
    if (Interpolation.findAllMatchIn(text).exists(m => !Values.contains(name(m)))) Left("unsupported interpolation")
    else Right(Interpolation.replaceAllIn(text, m => Matcher.quoteReplacement(Values(name(m)))))
  }

  /** Removes the caret and the selection markers, as `extractMarker` does. */
  private def fixtureOf(marked: String): Fixture = {
    val clean = new StringBuilder
    val starts = mutable.Stack.empty[Int]
    val ranges = Vector.newBuilder[TextRange]
    var caret = -1
    var i = 0
    while (i < marked.length) {
      if (marked.startsWith(Caret, i)) { caret = clean.length; i += Caret.length }
      else if (marked.startsWith(Start, i)) { starts.push(clean.length); i += Start.length }
      else if (marked.startsWith(End, i)) { ranges += new TextRange(starts.pop(), clean.length); i += End.length }
      else { clean += marked(i); i += 1 }
    }
    Fixture(clean.result(), caret, ranges.result().sortBy(_.getStartOffset))
  }

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase): Result =
    try literalOf(c.body) match {
      case Left(reason) => Result("skip", reason)
      case Right(marked) =>
        val fixture = fixtureOf(marked)
        val file = host.openFile(Path.of(s"/implementations-oracle/${c.suite}/${c.name}.scala"), fixture.text, module) match {
          case f: ScalaFile => f
          case other => throw new IllegalStateException(s"not a Scala fixture: $other")
        }
        try {
          val found = host.read(ProjectSearch.implementations(file, fixture.caret).map(_.getTextRange).sortBy(_.getStartOffset))
          compare(fixture, found)
        } finally host.closeSource(file)
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"implementations oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  /** The test's three assertions: some target, every expected range among them, and no other. */
  private def compare(fixture: Fixture, actual: Seq[TextRange]): Result = {
    def show(ranges: Seq[TextRange]) = ranges.map(r => s"$r '${r.substring(fixture.text)}'").mkString(", ")
    if (actual.isEmpty) Result("disagree", "no targets")
    else if (fixture.expected == actual) Result("agree", "")
    else Result("disagree", s"missing: ${show(fixture.expected.diff(actual))}; extra: ${show(actual.diff(fixture.expected))}")
  }

  private def summary(suite: String, results: Vector[(TestCase, Result)]): String = {
    val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
    s"$suite: ${outcomes.count(_ == "agree")} agree, ${outcomes.count(_ == "disagree")} disagree, ${outcomes.count(_ == "skip")} skip"
  }

  private def writeReport(path: Path, results: Vector[(TestCase, Result)], millis: Long): Unit = {
    val lines = Vector.newBuilder[String]
    lines += "# Implementations oracle"
    lines += ""
    lines += s"Scala 2.13.18 and Scala 3.9.0, unit-test mode, JDK ${System.getProperty("java.version")}; $millis ms."
    lines += ""
    lines += "Each case is a test method of `GoToImplementationTest` (Scala 2.13) or `GoToImplementationTest_Scala3` (Scala 3) with a literal " +
      "`doTest` text. The host opens the text without its markers and asks for the implementations of the symbol at the caret as the " +
      "language server does (`ProjectSearch.implementations`: the editor's target element, `DefinitionsScopedSearch` over the project " +
      "scope, the IDE handler's rule for including the symbol itself). The text ranges of the elements found are compared with the " +
      "marked ones, as the test does. The presentation of the targets some tests check afterwards is not replayed (the host has no UI)."
    lines += ""
    lines += "| Suite | Agree | Disagree | Skip |"
    lines += "|---|---:|---:|---:|"
    for (suite <- Suites) {
      val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
      lines += s"| $suite | ${outcomes.count(_ == "agree")} | ${outcomes.count(_ == "disagree")} | ${outcomes.count(_ == "skip")} |"
    }
    lines += ""
    lines += "## Disagreements and skips by cause"
    lines += ""
    for ((reason, count) <- results.filter(_._2.outcome != "agree").groupMapReduce { (_, r) =>
      r.outcome + ": " + r.reason.takeWhile(_ != ':')
    }(_ => 1)(_ + _).toSeq.sortBy((reason, count) => (-count, reason))) lines += s"- $reason: $count"
    lines += ""
    lines += "## Cases"
    lines += ""
    lines += "| Suite / case | Outcome | Reason |"
    lines += "|---|---|---|"
    for ((c, r) <- results) lines += s"| ${c.suite}/${c.name} | ${r.outcome} | ${r.reason.replace("|", "\\|").replace("\n", " ")} |"
    lines += ""
    lines += "## Adapter observations"
    lines += ""
    for ((entry, count) <- CallLog.snapshot if entry.startsWith("UNSUPPORTED ") || entry.startsWith("LOGGED ERROR "))
      lines += s"- $entry: $count"
    Files.createDirectories(path.toAbsolutePath.getParent)
    Files.write(path, lines.result().asJava)
  }
}
