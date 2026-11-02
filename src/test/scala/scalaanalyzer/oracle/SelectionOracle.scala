package scalaanalyzer.oracle

import com.intellij.openapi.util.TextRange
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost}
import scalaanalyzer.platform.{CallLog, HostLog}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.regex.Matcher

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Replays the selectioner tests' literal Seq states. Every later state is one IDE Extend Selection step, rather
 * than an unordered set of acceptable ranges. The multiline/single-line wrapper is replayed twice, as in the tests.
 * Usage: `Test/runMain scalaanalyzer.oracle.SelectionOracle <intellij-scala checkout> <report.md>`.
 */
object SelectionOracle {
  private final case class TestCase(suite: String, name: String, states: Either[String, Vector[String]])
  private final case class State(text: String, caret: Int, selection: Option[TextRange])
  private final case class Result(outcome: String, reason: String)
  private val TestMethod = "(?m)^  def (test\\w+)\\(\\): Unit =".r
  private val Call = """(?s)^\s*(doTest|doTestForMultilineAndSingleLine)\(Seq\(""".r
  // These fixtures use only interpolated triple-quoted literals; a closing quote run ends at its last quote.
  private val Literal = "(?s)^s\"\"\"(.*?)\"\"\"(?!\")(\\.stripMargin)?".r
  private val Separator = """(?s)^(?:\s|//[^\n]*(?:\n|$))*""".r
  private val Interpolation = """\$(\$|\{\w+\}|\w+)""".r
  private val Values = Map("Caret" -> "<caret>", "Start" -> "<selection>", "End" -> "</selection>",
    "q" -> "\"", "qq" -> "\"\"", "qqq" -> "\"\"\"", "$" -> "$")

  def main(args: Array[String]): Unit = {
    val Seq(repo, reportPath) = args.toSeq
    val cases = collect(Path.of(repo, "scala/scala-impl/test/org/jetbrains/plugins/scala/editor/selectioner"))
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      unitTestMode = true,
    ))
    val module = host.addModule(ModuleSpec("selection-oracle", "2.13.18", Seq.empty, Seq.empty))
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = runCase(host, module, c)
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    writeReport(Path.of(reportPath), results)
    for (suite <- results.map(_._1.suite).distinct) println(summary(suite, results))
    println(s"selection oracle: report at $reportPath")
    sys.exit(0)
  }

  private def collect(directory: Path): Vector[TestCase] = Using.resource(Files.list(directory)) { paths =>
    paths.iterator.asScala.filter(_.getFileName.toString.endsWith("Test.scala")).toVector.sortBy(_.toString).flatMap { path =>
      val source = Files.readString(path)
      val methods = TestMethod.findAllMatchIn(source).toVector
      methods.zip(methods.map(_.start).drop(1) :+ source.length).flatMap { (method, end) =>
        val body = source.substring(method.end, end).stripSuffix("\n").stripSuffix("}")
        val suite = path.getFileName.toString.stripSuffix(".scala")
        val call = Call.findPrefixMatchOf(body)
        val states = call.toRight("not a direct doTest(Seq(...)) call").flatMap(m => literals(body.substring(m.end), Vector.empty))
        if (call.exists(_.group(1) == "doTestForMultilineAndSingleLine")) Vector(
          TestCase(suite, method.group(1) + "/multiline", states),
          TestCase(suite, method.group(1) + "/single-line", states.map(_.map(_.replace("\"\"\"", "\"")))))
        else Vector(TestCase(suite, method.group(1), states))
      }
    }
  }

  /** Only literal lists, comments and the fixture base's named interpolations are evaluated; other code is skipped. */
  @tailrec
  private def literals(remaining: String, states: Vector[String]): Either[String, Vector[String]] = {
    val text = remaining.drop(Separator.findPrefixOf(remaining).get.length)
    if (text.startsWith("))") && Separator.findPrefixOf(text.drop(2)).contains(text.drop(2)) && states.size >= 2)
      return Right(states)
    Literal.findPrefixMatchOf(text) match {
      case None => Left("non-literal state or additional test setup")
      case Some(literal) =>
        val raw = StringContext.processEscapes(literal.group(1))
        val names = Interpolation.findAllMatchIn(raw).map(_.group(1).stripPrefix("{").stripSuffix("}")).toVector
        if (names.exists(name => !Values.contains(name))) return Left("unsupported fixture interpolation")
        val expanded = Interpolation.replaceAllIn(raw, m => Matcher.quoteReplacement(Values(m.group(1).stripPrefix("{").stripSuffix("}"))))
        val state = if (literal.group(2) == null) expanded else expanded.stripMargin
        val rest = text.substring(literal.end)
        val afterComment = rest.drop(Separator.findPrefixOf(rest).get.length)
        if (afterComment.startsWith(",")) literals(afterComment.drop(1), states :+ state)
        else literals(afterComment, states :+ state)
    }
  }

  private def state(marked: String): State = {
    val caret = marked.indexOf("<caret>")
    require(caret >= 0 && marked.indexOf("<caret>", caret + 1) < 0, "expected one caret marker")
    def clean(text: String): String = Values.values.filter(_.startsWith("<")).foldLeft(text)((s, tag) => s.replace(tag, ""))
    val start = marked.indexOf("<selection>")
    val end = marked.indexOf("</selection>")
    require((start < 0 && end < 0) || start >= 0 && end > start, "invalid selection markers")
    val selection = Option.when(start >= 0)(new TextRange(clean(marked.take(start)).length, clean(marked.take(end)).length))
    State(clean(marked), clean(marked.take(caret)).length, selection)
  }

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase): Result = try c.states match {
    case Left(reason) => Result("skip", reason)
    case Right(marked) =>
      val states = marked.map(state)
      val initial = states.head
      require(initial.selection.isEmpty && states.forall(s => s.text == initial.text && s.caret == initial.caret),
        "fixture changes its text/caret or starts with a selection")
      val file = host.openFile(Path.of(s"/selection-oracle/${c.suite}/${c.name}.scala"), initial.text, module) match {
        case f: ScalaFile => f
        case other => throw new IllegalStateException(s"not a Scala fixture: $other")
      }
      try {
        val actual = host.selectionRanges(file, initial.caret)
        val differences = states.tail.zipWithIndex.flatMap { (expected, index) =>
          Option.when(expected.selection != actual.lift(index))(
            s"step ${index + 1}: expected ${expected.selection}, actual ${actual.lift(index)}")
        }
        if (differences.isEmpty) Result("agree", "")
        else Result("disagree", differences.mkString("; ") + s"; source: ${initial.text}")
      } finally host.closeSource(file)
  } catch {
    case NonFatal(e) =>
      HostLog.error(s"selection oracle ${c.suite}/${c.name} failed", e)
      Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
  }

  private def summary(suite: String, results: Vector[(TestCase, Result)]): String = {
    val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
    s"$suite | ${outcomes.count(_ == "agree")} | ${outcomes.count(_ == "disagree")} | ${outcomes.count(_ == "skip")}"
  }

  private def writeReport(path: Path, results: Vector[(TestCase, Result)]): Unit = {
    val lines = Vector.newBuilder[String]
    lines += "# Selection oracle"
    lines += ""
    lines += "Scala 2.13.18, unit-test mode, default editor settings. Cases are literal Seq states from the plugin's selectioner test methods."
    lines += "The multiline/single-line helper runs twice. Every later state is compared at its exact step; fixtures may stop before the chain ends."
    lines += ""
    lines += "| Suite | Agree | Disagree | Skip |"
    lines += "|---|---:|---:|---:|"
    for (suite <- results.map(_._1.suite).distinct) lines += s"| ${summary(suite, results)} |"
    lines += ""
    lines += "## Disagreements and skips"
    lines += ""
    for ((c, result) <- results if result.outcome != "agree") {
      lines += s"### ${c.suite}/${c.name}: ${result.outcome}"
      lines += ""
      lines += "```text"
      lines += result.reason
      lines += "```"
    }
    lines += ""
    lines += "## Adapter observations"
    for ((entry, count) <- CallLog.snapshot if entry.startsWith("UNSUPPORTED ") || entry.startsWith("LOGGED ERROR "))
      lines += s"- $entry: $count"
    Files.createDirectories(path.toAbsolutePath.getParent)
    Files.write(path, lines.result().asJava)
  }
}
