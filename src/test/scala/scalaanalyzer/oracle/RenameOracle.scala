package scalaanalyzer.oracle

import com.intellij.psi.PsiFile

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost, TestHost}
import scalaanalyzer.platform.{CallLog, HostLog}
import scalaanalyzer.language.{Rename}
import scalaanalyzer.language.{ChangeEngine}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.regex.Matcher

import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.jdk.StreamConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Replays the plugin's rename tests without the IDE test harness, over the host's rename (`ScalaPsiHost.rename`): the
 * texts of `ScalaRenameTest`, `ScalaRenameAliasedTest`, `ScalaRenameGivenTest` and `ScalaRenameNewSyntaxGivenTest`
 * (`doRenameTest` calls with literal texts), the files of `testdata/refactoring/rename` (the generated class and
 * parameter tests: the expected text is the file's last comment) and the projects of `testdata/refactoring/rename3`
 * (`before` and `after` directories, `ScalaRenameTest` and `Scala3RenameTest` there). The target is the symbol at each
 * caret, and the result is compared with the expected text of the test, file by file.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.RenameOracle <intellij-scala checkout> <report.md> [--only=<name>,...]`.
 */
object RenameOracle {
  /** A file of a case: its name (relative to the case) and its text. */
  private final case class Source(name: String, text: String)
  /** A rename to run: the files (carets removed), where the carets are, and the expected files. */
  private final case class Scenario(newName: String, sources: Seq[Source], carets: Seq[(String, Int)], expected: Seq[Source],
                                    mayRefuseName: Boolean = false)
  private final case class TestCase(suite: String, name: String, scala3: Boolean, scenario: Either[String, Scenario])
  private final case class Result(outcome: String, reason: String)

  private val InlineSuites = Seq("ScalaRenameTest" -> false, "ScalaRenameAliasedTest" -> false, "ScalaRenameGivenTest" -> true,
    "ScalaRenameNewSyntaxGivenTest" -> true)
  private val FileSuites = Seq("class", "parameter")
  private val ProjectSuites = Seq("ScalaRenameTest" -> false, "Scala3RenameTest" -> true, "ScalaAutomaticRenamerTest" -> true)
  private val TestMethod = "(?m)^  (?:@Test\\s+)?def test(\\w+)\\(\\): Unit =".r
  private val InlineCall = "(?s)^doRenameTest(?:WithUsagesCheck)?\\(\\s*\"([^\"]*)\",\\s*".r
  private val Literal = "(?s)^(s?)\"\"\"(.*?)\"\"\"(\\.stripMargin)?".r
  private val Interpolation = """\$(\$|\{\w+\}|\w+)""".r
  private val ProjectCall = "(?s)doTest\\((.*?)\\)".r
  private val NewName = "\"([^\"]*)\"".r
  private val DefaultName = "NameAfterRename"
  private val Caret = "<caret>"
  private val FileCaret = "/*caret*/"

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
    val module2 = host.addModule(ModuleSpec("rename-oracle-2", "2.13.18", Seq.empty, Seq(library)))
    val module3 = host.addModule(ModuleSpec("rename-oracle-3", "3.9.0", Seq.empty, Seq(library, library3)))
    val started = System.nanoTime()
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = c.scenario.fold(Result("skip", _), runCase(host, if (c.scala3) module3 else module2, c, _))
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    writeReport(Path.of(reportPath), results, (System.nanoTime() - started) / 1_000_000)
    for (suite <- results.map(_._1.suite).distinct) println(summary(suite, results))
    println(s"rename oracle: report at $reportPath")
    sys.exit(0)
  }

  // ---- The plugin's tests -------------------------------------------------------------------------------------------

  private def collect(repo: Path): Vector[TestCase] = {
    val tests = repo.resolve("scala/scala-impl/test/org/jetbrains/plugins/scala/refactoring")
    val data = repo.resolve("scala/scala-impl/testdata/refactoring")
    val texts = for {
      (suite, scala3) <- InlineSuites
      (name, body) <- methodsOf(Files.readString(tests.resolve(s"rename2/$suite.scala")))
    } yield TestCase(suite, name, scala3, inlineScenario(body))
    val files = for {
      suite <- FileSuites
      generated = tests.resolve(s"rename/generated/ScalaRename${suite.capitalize}Test.scala")
      (name, body) <- methodsOf(Files.readString(generated))
    } yield TestCase(s"rename/$suite", name, scala3 = false, fileScenario(data.resolve(s"rename/$suite/$name.scala")))
    val projects = for {
      (suite, scala3) <- ProjectSuites
      (name, body) <- methodsOf(Files.readString(tests.resolve(s"rename3/${if (suite.startsWith("ScalaAuto")) suite else "ScalaRenameTest"}.scala")), suite)
    } yield TestCase(s"rename3/$suite", name, scala3, projectScenario(data.resolve(s"rename3/${name.head.toLower}${name.tail}"), body))
    (texts ++ files ++ projects).toVector
  }

  /** The test methods of a source; of the class `within` only, when given (a file may hold several test classes). */
  private def methodsOf(source: String, within: String = ""): Seq[(String, String)] = {
    val begin = if (within.isEmpty) 0 else source.indexOf(s"class $within ")
    val end = if (within.isEmpty) -1 else source.indexOf("\nclass ", begin + 1)
    val scope = source.substring(begin, if (end < 0) source.length else end)
    val starts = TestMethod.findAllMatchIn(scope).toVector
    starts.zip(starts.map(_.start).drop(1) :+ scope.length).map((method, end) => method.group(1) -> scope.substring(method.end, end))
  }

  /** `doRenameTest(newName, text, result)` with literal texts: the caret is `<caret>` (or `$CARET`) in the text. */
  private def inlineScenario(body: String): Either[String, Scenario] = for {
    start <- Option(body.indexOf("doRenameTest")).filter(_ >= 0).toRight("no doRenameTest call")
    _ <- Either.cond(body.indexOf("doRenameTest", start + 1) < 0, (), "several renames in one test")
    call <- InlineCall.findPrefixMatchOf(body.substring(start)).toRight("unexpected doRenameTest call")
    rest = body.substring(start + call.end)
    text <- Literal.findPrefixMatchOf(rest).toRight("non-literal test text")
    result <- Literal.findPrefixMatchOf(rest.substring(text.end).dropWhile(c => c == ',' || c.isWhitespace)).toRight("non-literal result text")
    before <- literalText(text)
    after <- literalText(result)
    caret = before.trim.indexOf(Caret)
    _ <- Either.cond(caret >= 0, (), "no caret")
  } yield Scenario(call.group(1), Seq(Source("dummy.scala", before.trim.replace(Caret, ""))), Seq("dummy.scala" -> caret),
    Seq(Source("dummy.scala", after.replace(Caret, "").trim)))

  private def literalText(literal: scala.util.matching.Regex.Match): Either[String, String] = {
    def name(m: scala.util.matching.Regex.Match) = m.group(1).stripPrefix("{").stripSuffix("}")
    val text = literal.group(2)
    val interpolated =
      if (literal.group(1) != "s") Right(text)
      else if (Interpolation.findAllMatchIn(text).exists(m => name(m) != "CARET" && name(m) != "$")) Left("unsupported interpolation")
      else Right(Interpolation.replaceAllIn(text, m => Matcher.quoteReplacement(if (name(m) == "CARET") Caret else "$")))
    interpolated.map(t => if (literal.group(3) == null) t else t.stripMargin)
  }

  /** A file of `testdata/rename`: the text before its last comment is renamed to the comment's, at the caret. */
  private def fileScenario(path: Path): Either[String, Scenario] = {
    val text = Files.readString(path).replace("\r\n", "\n")
    val commentAt = text.lastIndexOf("\n/*")
    val caret = text.indexOf(FileCaret)
    if (path.getFileName.toString.contains("Comments")) Left("the test renames in comments, which an LSP rename does not")
    else if (commentAt < 0 || caret < 0) Left("no expected comment or caret")
    else {
      val expected = text.substring(commentAt).trim.stripPrefix("/*").stripSuffix("*/").trim
      Right(Scenario(DefaultName, Seq(Source("dummy.scala", text.substring(0, commentAt).trim)),
        // The test puts the caret one character after the marker.
        Seq("dummy.scala" -> (caret + FileCaret.length + 1)), Seq(Source("dummy.scala", expected))))
    }
  }

  /** A directory of `testdata/rename3`: the project before, a caret in some of its files, the project after. */
  private def projectScenario(directory: Path, body: String): Either[String, Scenario] = {
    def files(side: String): Seq[Source] = {
      val root = directory.resolve(side)
      Using.resource(Files.walk(root)) { paths =>
        paths.toScala(Seq).filter(Files.isRegularFile(_)).sortBy(_.toString)
          .map(p => Source(root.relativize(p).toString, Files.readString(p).replace("\r\n", "\n")))
      }
    }
    val arguments = ProjectCall.findFirstMatchIn(body).map(_.group(1)).getOrElse("")
    val newName = NewName.findFirstMatchIn(arguments).map(_.group(1)).getOrElse(DefaultName)
    val before = files("before")
    val carets = before.flatMap(s => s.text.split(java.util.regex.Pattern.quote(FileCaret), -1).dropRight(1).scanLeft(0)(_ + _.length).tail
      .map(offset => s.name -> offset))
    // Markers are removed in order, so each offset counts the characters of the text before it, markers excluded.
    val clean = before.map(s => s.copy(text = s.text.replace(FileCaret, "")))
    if (body.contains("withAutoRenames = true")) Left("the test applies the optional automatic renames, which the host leaves out")
    else if (body.contains("assertFails")) Left("the plugin's test expects the rename to fail (known issue)")
    else if (carets.forall(_._1.endsWith(".java"))) Left("every caret is in a Java file")
    else Right(Scenario(newName, clean, carets.filterNot(_._1.endsWith(".java")),
      files("after"), mayRefuseName = body.contains("is not an identifier")))
  }

  // ---- Replay ---------------------------------------------------------------------------------------------------------

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase, scenario: Scenario): Result =
    try {
      val outcomes = scenario.carets.map((file, caret) => runCaret(host, module, c, scenario, file, caret))
      outcomes.find(_.outcome != "agree").getOrElse(outcomes.head)
    } catch {
      case e @ (NonFatal(_) | _: LinkageError) =>
        HostLog.error(s"rename oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  private def runCaret(host: ScalaPsiHost, module: HostModule, c: TestCase, scenario: Scenario, caretFile: String, caret: Int): Result = {
    val root = s"/rename-oracle/${c.suite}/${c.name}"
    val opened = scenario.sources.map(s => s -> host.openFile(Path.of(s"$root/${s.name}"), s.text, module))
    try {
      val caretPsi = opened.collectFirst { case (s, f) if s.name == caretFile => f }.get
      val outcome = try Right(host.rename(caretPsi, caret, scenario.newName))
        catch { case refused: Rename.Refused => Left(refused) }
      outcome match {
        case Left(refused) if refused.getMessage.contains("is not a valid name") && scenario.mayRefuseName =>
          Result("agree", s"refused, as the test allows: ${refused.getMessage}")
        case Left(refused) if refused.getMessage.contains("is not located inside the project") =>
          Result("skip", "renaming a package moves its directories, which the host's files do not have")
        case Left(refused) => Result("disagree", s"refused: ${refused.getMessage.replace("\n", "; ")}")
        case Right(changes) => compare(scenario.expected, applied(opened, changes))
      }
    } finally opened.foreach((_, f) => host.closeSource(f))
  }

  private def applied(opened: Seq[(Source, PsiFile)], changes: Seq[(PsiFile, Seq[scalaanalyzer.language.ChangeEngine.Edit])]): Seq[Source] =
    opened.map { (source, file) =>
      source.copy(text = changes.find(_._1 eq file).fold(source.text)((_, edits) => TestHost.applyEdits(source.text, edits)))
    }

  /** Texts file by file; when the IDE renames a file too (a class's file), the texts alone, with the names that differ. */
  private def compare(expected: Seq[Source], actual: Seq[Source]): Result = {
    def trimmed(sources: Seq[Source]) = sources.map(s => s.copy(text = s.text.trim))
    val (want, got) = (trimmed(expected).sortBy(_.name), trimmed(actual).sortBy(_.name))
    if (want == got) Result("agree", "")
    else if (want.map(_.text).sorted == got.map(_.text).sorted) {
      val renamed = want.map(_.name).diff(got.map(_.name))
      Result("agree", s"the IDE also renames the files ${renamed.mkString(", ")}")
    } else {
      val different = got.filterNot(g => want.exists(_.text == g.text)).map(_.name)
      Result("disagree", s"different text in: ${different.mkString(", ")}\n--- expected\n${want.find(w => !got.exists(_.text == w.text)).map(_.text).getOrElse("")}\n--- actual\n${got.find(g => !want.exists(_.text == g.text)).map(_.text).getOrElse("")}")
    }
  }

  // ---- Report -------------------------------------------------------------------------------------------------------

  private def summary(suite: String, results: Vector[(TestCase, Result)]): String = {
    val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
    s"$suite: ${outcomes.count(_ == "agree")} agree, ${outcomes.count(_ == "disagree")} disagree, ${outcomes.count(_ == "skip")} skip"
  }

  private def writeReport(path: Path, results: Vector[(TestCase, Result)], millis: Long): Unit = {
    val lines = Vector.newBuilder[String]
    lines += "# Rename oracle"
    lines += ""
    lines += s"Scala 2.13.18 and Scala 3.9.0, unit-test mode, JDK ${System.getProperty("java.version")}; $millis ms."
    lines += ""
    lines += "Each case is a rename test of the plugin: `rename2` (literal `doRenameTest` texts), `rename` (a file whose last comment is the " +
      "result) or `rename3` (a project directory before and after). The host opens the files without the caret markers, renames the symbol " +
      "at each caret (the plugin's processors and references, applied to the files and returned as edits: `ScalaPsiHost.rename`), and the " +
      "edited texts are compared with the expected ones. Carets in Java files are not replayed. The files the IDE renames along with a class " +
      "are compared by text, as the host leaves file names alone."
    lines += ""
    lines += "| Suite | Agree | Disagree | Skip |"
    lines += "|---|---:|---:|---:|"
    for (suite <- results.map(_._1.suite).distinct) {
      val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
      lines += s"| $suite | ${outcomes.count(_ == "agree")} | ${outcomes.count(_ == "disagree")} | ${outcomes.count(_ == "skip")} |"
    }
    lines += ""
    lines += "## Disagreements and skips by cause"
    lines += ""
    for ((reason, count) <- results.filter(_._2.outcome != "agree").groupMapReduce { (_, r) =>
      r.outcome + ": " + r.reason.takeWhile(_ != ':').takeWhile(_ != '\n')
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
