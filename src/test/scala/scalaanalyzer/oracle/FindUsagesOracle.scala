package scalaanalyzer.oracle

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.usageView.UsageInfo
import com.intellij.util.CommonProcessors
import org.jetbrains.plugins.scala.findUsages.factory.{ScalaFindUsagesConfiguration, ScalaFindUsagesHandlerFactory}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost}
import scalaanalyzer.platform.{CallLog, HostLog, RequestEditor}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.regex.Matcher

import scala.collection.mutable
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.matching.Regex.Match

/**
 * Replays the plugin's find-usages tests (`FindUsagesTest_Scala2` and `FindUsagesTest_Scala3`, whose Scala 2 methods
 * also run under Scala 3) without the IDE test harness: the literal text of each `doTest` call, the target at its
 * caret, and the usages the plugin's find-usages handler reports over the project scope, as `myFixture.findUsages` does.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.FindUsagesOracle <intellij-scala checkout> <report.md> [--only=<name>,...]`.
 */
object FindUsagesOracle {
  private final case class TestCase(suite: String, name: String, body: String, scala3: Boolean)
  private final case class Result(outcome: String, reason: String)
  /** The text with markers removed, the caret and the ranges the test expects to be found. */
  private final case class Fixture(text: String, caret: Int, expected: Seq[TextRange])
  /** One usage as the test compares it: where the handler navigates to, and the text there. */
  private final case class Usage(range: TextRange, text: String) {
    override def toString: String = s"${range.getStartOffset}-${range.getEndOffset} '$text'"
  }

  private val Scala2Suite = "FindUsagesTest_Scala2"
  private val Scala3Suite = "FindUsagesTest_Scala3"
  private val Suites = Seq(Scala2Suite, Scala3Suite)
  private val TestMethod = "(?m)^  def test(\\w+)\\(\\): Unit =".r
  private val Call = "(?s)^doTest\\(\\s*".r
  private val Literal = "(?s)^(s?)\"\"\"(.*?)\"\"\"(\\.stripMargin)?".r
  private val SetOption = "ScalaFindUsagesConfiguration\\.getInstance\\(getProject\\)\\.getTypeDefinitionOptions\\.(\\w+) = (true|false)".r
  private val Interpolation = """\$(\$|\{\w+\}|\w+)""".r
  // The tests' `$Right` is the scala.util.Right companion, which prints as its name.
  private val Values = Map("CARET" -> "<caret>", "start" -> "/*start*/", "end" -> "/*end*/", "$" -> "$", "Right" -> "Right")
  // What `getElementAtCaret` accepts, less the lookup item: the host shows no lookup.
  private val TargetFlags = TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED | TargetElementUtil.ELEMENT_NAME_ACCEPTED
  private val Caret = "<caret>"
  private val Start = "/*start*/"
  private val End = "/*end*/"

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
    val module2 = host.addModule(ModuleSpec("find-usages-oracle-2", "2.13.18", Seq.empty, Seq(library)))
    val module3 = host.addModule(ModuleSpec("find-usages-oracle-3", "3.9.0", Seq.empty, Seq(library, library3)))
    val started = System.nanoTime()
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = runCase(host, if (c.scala3) module3 else module2, c)
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    writeReport(Path.of(reportPath), results, (System.nanoTime() - started) / 1_000_000)
    for (suite <- Suites) println(summary(suite, results))
    println(s"find usages oracle: report at $reportPath")
    sys.exit(0)
  }

  /** Each method of the Scala 2 class, and of the Scala 3 class, which inherits the Scala 2 methods and runs them as Scala 3. */
  private def collect(repo: Path): Vector[TestCase] = {
    val directory = repo.resolve("scala/scala-impl/test/org/jetbrains/plugins/scala/lang/findUsages")
    def methods(suite: String) = methodsOf(Files.readString(directory.resolve(suite + ".scala")))
    val scala2 = methods(Scala2Suite)
    val scala3 = scala2 ++ methods(Scala3Suite)
    (scala2.map((name, body) => TestCase(Scala2Suite, name, body, scala3 = false)) ++
      scala3.map((name, body) => TestCase(Scala3Suite, name, body, scala3 = true))).toVector
  }

  private def methodsOf(source: String): Seq[(String, String)] = {
    val starts = TestMethod.findAllMatchIn(source).toVector
    starts.zip(starts.map(_.start).drop(1) :+ source.length).map((method, end) => method.group(1) -> source.substring(method.end, end))
  }

  /** The literal text of the `doTest` call, as the test builds it: interpolated, with its margins stripped. */
  private def literalOf(body: String): Either[String, String] = for {
    start <- Option(body.indexOf("doTest(")).filter(_ >= 0).toRight(
      if (body.contains("addFileToProject")) "needs several project files, Java among them, and the find-usages action"
      else "no doTest call: the test checks the presentation of a target, not a usage search")
    _ <- Either.cond(SetOption.replaceAllIn(body.substring(0, start), "").forall(c => c.isWhitespace || c == '{'), (),
      "setup before doTest that the oracle does not replay")
    call <- Call.findPrefixMatchOf(body.substring(start)).toRight("unexpected doTest call")
    literal <- Literal.findPrefixMatchOf(body.substring(start + call.end)).toRight("non-literal test text")
    text = literal.group(2)
    interpolated <- if (literal.group(1) == "s") interpolate(text) else Right(text)
  } yield if (literal.group(3) == null) interpolated else interpolated.stripMargin

  private def interpolate(text: String): Either[String, String] = {
    def name(m: Match): String = m.group(1).stripPrefix("{").stripSuffix("}")
    if (Interpolation.findAllMatchIn(text).exists(m => !Values.contains(name(m)))) Left("unsupported interpolation")
    else Right(Interpolation.replaceAllIn(text, m => Matcher.quoteReplacement(Values(name(m)))))
  }

  /** Removes the caret and the numbered-less `/*start*/ ... /*end*/` markers, as `extractNumberedMarkers` does. */
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

  /** The `ScalaTypeDefinitionFindUsagesOptions` flags the test sets before searching. */
  private def optionsOf(body: String): Seq[(String, Boolean)] =
    SetOption.findAllMatchIn(body).map(m => m.group(1) -> (m.group(2) == "true")).toSeq

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase): Result =
    try literalOf(c.body) match {
      case Left(reason) => Result("skip", reason)
      case Right(marked) =>
        val fixture = fixtureOf(marked)
        val file = host.openFile(Path.of(s"/find-usages-oracle/${c.suite}/${c.name}.scala"), fixture.text, module) match {
          case f: ScalaFile => f
          case other => throw new IllegalStateException(s"not a Scala fixture: $other")
        }
        try {
          val expected = fixture.expected.map(range => Usage(range, range.substring(fixture.text)))
          compare(expected, host.read(usages(host, file, fixture.caret, optionsOf(c.body))))
        } finally host.closeSource(file)
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"find usages oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  /** What `myFixture.findUsages(myFixture.getElementAtCaret)` collects, distinct and in source order. */
  private def usages(host: ScalaPsiHost, file: ScalaFile, caret: Int, options: Seq[(String, Boolean)]): Seq[Usage] = {
    val project = host.project
    val configuration = ScalaFindUsagesConfiguration.getInstance(project)
    try {
      for ((option, value) <- options) option match {
        case "isMembersUsages" => configuration.getTypeDefinitionOptions.isMembersUsages = value
        case other => throw new IllegalArgumentException(s"unsupported find usages option: $other")
      }
      val editor = new RequestEditor(PsiDocumentManager.getInstance(project).getDocument(file), project, caret)
      val target = try TargetElementUtil.getInstance.findTargetElement(editor.editor, TargetFlags, caret)
        finally editor.close()
      if (target == null) throw new IllegalStateException(s"no target element at caret $caret")
      val handler = new ScalaFindUsagesHandlerFactory(project).createFindUsagesHandler(target, false)
      val collected = new CommonProcessors.CollectProcessor[UsageInfo]
      val findOptions = handler.getFindUsagesOptions
      for (element <- handler.getPrimaryElements ++ handler.getSecondaryElements) handler.processElementUsages(element, collected, findOptions)
      collected.getResults.asScala.toSeq.sortBy(_.getNavigationRange.getStartOffset).map { usage =>
        val range = new TextRange(usage.getNavigationRange.getStartOffset, usage.getNavigationRange.getEndOffset)
        Usage(range, range.substring(usage.getFile.getText))
      }.distinct
    } finally configuration.reset()
  }

  private def compare(expected: Seq[Usage], actual: Seq[Usage]): Result =
    if (expected == actual) Result("agree", "")
    else Result("disagree", s"missing: ${expected.diff(actual).mkString(", ")}; extra: ${actual.diff(expected).mkString(", ")}")

  private def summary(suite: String, results: Vector[(TestCase, Result)]): String = {
    val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
    s"$suite: ${outcomes.count(_ == "agree")} agree, ${outcomes.count(_ == "disagree")} disagree, ${outcomes.count(_ == "skip")} skip"
  }

  private def writeReport(path: Path, results: Vector[(TestCase, Result)], millis: Long): Unit = {
    val lines = Vector.newBuilder[String]
    lines += "# Find usages oracle"
    lines += ""
    lines += s"Scala 2.13.18 and Scala 3.9.0, unit-test mode, JDK ${System.getProperty("java.version")}; $millis ms."
    lines += ""
    lines += "Each case is a test method of `FindUsagesTest_Scala2` or `FindUsagesTest_Scala3` (which also runs the Scala 2 methods, as Scala 3) " +
      "with a literal `doTest` text. The host opens the text without its markers and finds the target element at the caret as the editor does " +
      "(`TargetElementUtil`), then collects what the plugin's find-usages handler reports for the handler's primary and secondary elements with the " +
      "handler's default options over the project scope. Usages are compared as (range, text) pairs, in source order, once each. The assertion " +
      "on the type of the find-usages dialog some tests make afterwards is not replayed (the host has no UI)."
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
