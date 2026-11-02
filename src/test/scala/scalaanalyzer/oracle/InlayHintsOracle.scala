package scalaanalyzer.oracle

import org.jetbrains.plugins.scala.codeInsight.ScalaCodeInsightSettings
import org.jetbrains.plugins.scala.codeInsight.hints.ScalaHintsSettings
import org.jetbrains.plugins.scala.codeInsight.implicits.ImplicitHints
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.settings.ScalaApplicationSettings

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost}
import scalaanalyzer.platform.{CallLog, HostLog}
import scalaanalyzer.language.{InlayHints}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.regex.Matcher

import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.matching.Regex.Match

/**
 * Replays the hints tests' literal doTest cases, including their file wrapper and settings, before editor rendering.
 * Usage: `Test/runMain scalaanalyzer.oracle.InlayHintsOracle <intellij-scala checkout> <report.md> [--only=<name>,...]`.
 */
object InlayHintsOracle {
  private final case class TestCase(suite: String, name: String, body: String, scala3: Boolean, skip: Option[String] = None)
  private final case class Result(outcome: String, reason: String)
  private val Suites = Vector("hints/InlayTypeHintsTest", "hints/InlayParameterHintsTest", "hints/TypeArgumentsHintTest",
    "implicits/ImplicitArgumentHintsTest", "implicits/ImplicitConversionHintsTest")
  private val ClassHeader = "(?m)^class (\\w+) extends".r
  private val TestMethod = "(?m)^  def test(\\w+)\\(\\): Unit =".r
  private val Call = "(?s)^\\s*(?:\\{\\s*)?(doTest(?:WithImplicitHints)?)\\(\\s*".r
  private val Literal = "(?s)^(s?)(\"\"\".*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\")(\\.stripMargin)?".r
  private val Setting = "settings\\.(\\w+Setter)(?:\\(\\))?".r
  private val Interpolation = """\$(\$|\{\w+\}|\w+)""".r
  // The public expanded setter also changes shortcuts through the absent KeymapManager; only the oracle uses it.
  private val Expanded = ImplicitHints.getClass.getDeclaredField("_expanded")
  Expanded.setAccessible(true)
  private val RangeProbe = TestCase("RangeProbe", "RangeProbe", "doTest(\"  val range = 1 to 3\")", false,
    Some("range hints are not shown: RangeInlayHintsPass requires PresentationFactory over EditorImpl"))
  private val HintMarker = "<hint text=\"([^\"]*)\"/>".r

  def main(args: Array[String]): Unit = {
    val (Seq(repo, reportPath), flags) = args.toSeq.partition(!_.startsWith("--"))
    val only = flags.collectFirst { case f if f.startsWith("--only=") => f.stripPrefix("--only=").split(',').toSet }
    val cases = (collect(Path.of(repo)) :+ RangeProbe).filter(c => only.forall(names => names(c.name) || names(c.suite + "/" + c.name)))
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
    val module2 = host.addModule(ModuleSpec("inlay-oracle-2", "2.13.18", Seq.empty, Seq(library)))
    val module3 = host.addModule(ModuleSpec("inlay-oracle-3", "3.9.0", Seq.empty, Seq(library, library3)))
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = runCase(host, if (c.scala3) module3 else module2, c)
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    writeReport(Path.of(reportPath), results)
    for (suite <- results.map(_._1.suite).distinct) println(summary(suite, results))
    println(s"inlay hints oracle: report at $reportPath")
    sys.exit(0)
  }

  private def collect(repo: Path): Vector[TestCase] = Suites.flatMap { suite =>
    val source = Files.readString(repo.resolve(s"scala/codeInsight/test/org/jetbrains/plugins/scala/codeInsight/$suite.scala"))
    val classes = ClassHeader.findAllMatchIn(source).toVector
    classes.zip(classes.map(_.start).drop(1) :+ source.length).flatMap { (header, end) =>
      val text = source.substring(header.end, end)
      val methods = TestMethod.findAllMatchIn(text).toVector
      methods.zip(methods.map(_.start).drop(1) :+ text.length).map { (method, next) =>
        TestCase(header.group(1), method.group(1), text.substring(method.end, next),
          header.group(1).endsWith("Scala3") || suite.endsWith("TypeArgumentsHintTest"))
      }
    }
  }

  /** This extractor accepts a first literal argument with S/E markers and the fixture's bounded option syntax. */
  private def expected(c: TestCase): Either[String, String] = for {
    _ <- c.skip.toLeft(())
    call <- Call.findPrefixMatchOf(c.body).toRight("not a direct doTest literal (additional setup or another assertion)")
    literal <- Literal.findPrefixMatchOf(c.body.substring(call.end)).toRight("non-literal test text")
    raw = literal.group(2)
    content = if (raw.startsWith("\"\"\"")) raw.drop(3).dropRight(3) else raw.drop(1).dropRight(1)
    decoded = if (!raw.startsWith("\"\"\"") || literal.group(1) == "s") StringContext.processEscapes(content) else content
    _ <- Either.cond(!c.body.contains("withTooltips = true"), (), "tooltip rendering is outside string labels")
    expanded <- if (literal.group(1) == "s") interpolate(decoded, c.scala3) else Right(decoded)
  } yield if (literal.group(3) == null) expanded else expanded.stripMargin

  private def interpolate(text: String, scala3: Boolean): Either[String, String] = {
    val values = Map("S" -> "<hint text=\"", "E" -> "\"/>", "$" -> "$",
      "compoundTypeText" -> (if (scala3) "Bar & Serializable" else "Bar with Serializable"))
    def name(m: Match): String = m.group(1).stripPrefix("{").stripSuffix("}")
    if (Interpolation.findAllMatchIn(text).exists(m => !values.contains(name(m)))) Left("unsupported interpolation")
    else Right(Interpolation.replaceAllIn(text, m => Matcher.quoteReplacement(values(name(m)))))
  }

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase): Result =
    try expected(c) match {
      case Left(reason) => Result("skip", reason)
      case Right(text) => withSettings(c) {
        val wrapped = s"class Foo {\n$text\n}\n\nnew Foo"
        val source = HintMarker.replaceAllIn(wrapped, "")
        val file = host.openFile(Path.of(s"/inlay-oracle/${c.suite}/${c.name}.scala"), source, module) match {
          case f: ScalaFile => f
          case other => throw new IllegalStateException(s"not a Scala fixture: $other")
        }
        try {
          val actual = InlayHints.in(file).reverse.foldLeft(source) { (current, hint) =>
            current.patch(hint.offset, s"<hint text=\"${hint.text}\"/>", 0)
          }
          if (actual == wrapped) Result("agree", "")
          else Result("disagree", s"expected: $wrapped; actual: $actual")
        } finally host.closeSource(file)
      }
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"inlay hints oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  private def withSettings[A](c: TestCase)(body: => A): A = {
    val settings = ScalaCodeInsightSettings.getInstance()
    val saved = new ScalaCodeInsightSettings
    saved.loadState(settings)
    val application = ScalaApplicationSettings.getInstance()
    val oldTypeArguments = application.XRAY_SHOW_TYPE_ARGUMENT_HINTS
    val oldXRay = ScalaHintsSettings.xRayMode
    val oldEnabled = ImplicitHints.enabled
    val oldExpanded = ImplicitHints.expanded
    try {
      for (option <- Setting.findAllMatchIn(c.body).map(_.group(1)).toSet) option match {
        case "showObviousTypeSetter" => settings.showObviousTypeSetter().set(true)
        case "showMemberVariableSetter" => settings.showMemberVariableSetter().set(true)
        case "showLocalVariableTypeSetter" => settings.showLocalVariableTypeSetter().set(true)
        case "preserveIndentsSetter" => settings.preserveIndentsSetter().set(true)
        case other => throw new IllegalArgumentException(s"unsupported setting: $other")
      }
      if (c.suite == "InlayParameterHintsTest") settings.showParameterNames = true
      if (c.suite == "TypeArgumentsHintTest") {
        ScalaHintsSettings.xRayMode = true
        application.XRAY_SHOW_TYPE_ARGUMENT_HINTS = true
      }
      InlayHints.setImplicitHintsEnabled(c.suite.startsWith("Implicit") || c.body.contains("doTestWithImplicitHints"))
      Expanded.setBoolean(ImplicitHints, c.body.contains("expand = true"))
      body
    } finally {
      settings.loadState(saved)
      application.XRAY_SHOW_TYPE_ARGUMENT_HINTS = oldTypeArguments
      ScalaHintsSettings.xRayMode = oldXRay
      InlayHints.setImplicitHintsEnabled(oldEnabled)
      Expanded.setBoolean(ImplicitHints, oldExpanded)
    }
  }

  private def summary(suite: String, results: Vector[(TestCase, Result)]): String = {
    val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
    s"$suite | ${outcomes.count(_ == "agree")} | ${outcomes.count(_ == "disagree")} | ${outcomes.count(_ == "skip")}"
  }

  private def writeReport(path: Path, results: Vector[(TestCase, Result)]): Unit = {
    val lines = Vector.newBuilder[String]
    lines += "# Inlay hints oracle"
    lines += ""
    lines += "Scala 2.13.18 and Scala 3.9.0, unit-test mode, compiler highlighting disabled. Each declared test method is reported; inherited Scala 2 cases are reported once."
    lines += "The file patcher wraps the literal in `class Foo { ... }\\n\\nnew Foo`. S/E interpolations become `<hint text=\"...\"/>` markers. Settings are restored after every case."
    lines += ""
    lines += "| Suite | Agree | Disagree | Skip |"
    lines += "|---|---:|---:|---:|"
    for (suite <- results.map(_._1.suite).distinct) lines += s"| ${summary(suite, results)} |"
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
