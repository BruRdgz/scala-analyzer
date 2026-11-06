package scalaanalyzer.oracle

import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.{HostConfig, ScalaPsiHost, TestHost}
import scalaanalyzer.platform.{CallLog, HostLog}
import scalaanalyzer.language.{ChangeEngine}

import java.io.File
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Replays the plugin's file-set formatter fixtures with their suite defaults, without the IDE test harness.
 * Inline Scala test methods and scalafmt configuration files have no file-based expected output in this corpus.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.FormatterOracle <intellij-scala checkout> <report.md>`.
 */
object FormatterOracle {
  private final case class Result(outcome: String, reason: String)

  def main(args: Array[String]): Unit = {
    val Array(repo, reportPath) = args
    val data = Path.of(repo, "scala/scala-impl/testdata/formatter")
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      unitTestMode = true,
    ))
    val cases = Using.resource(Files.walk(data))(_.iterator().asScala.filter(p => p.toString.endsWith(".test")).toVector.sorted)
    val started = System.nanoTime()
    val results = cases.map { path =>
      val name = data.relativize(path).toString
      name -> runCase(host, path, name)
    }
    val lines = Vector.newBuilder[String]
    lines += "# Formatter oracle"
    lines += ""
    lines += s"Platform 263.7307.200, plugin d2b6e2aed3, unit-test mode; ${(System.nanoTime() - started) / 1_000_000} ms."
    lines += ""
    lines += "The .test corpus uses FileSetTestBase's separator and compares trimmed text. FormatterTestBase sets " +
      "USE_SCALADOC2_FORMATTING=true. Both multiline-string suites additionally enable opening quotes on a new line, " +
      "margin insertion, ALIGN_MULTILINE_BINARY_OPERATION and margin indent 3; only the AlignQuotes suite aligns closing quotes. " +
      "These cheap suite-level settings are reproduced from their Scala setUp methods. There are no per-case settings sections in this corpus."
    lines += ""
    lines += "Scalafmt .conf files are configuration inputs, not before/after cases. Inline tests under lang/formatter/intellij/tests " +
      "are outside the file-set replay: their expectations, selections and settings live in Scala code. " +
      "The replay uses nonphysical Scala 2 files as FormatterTestBase does, and checks that edits reconstruct each copy."
    lines += ""
    lines += "| Agree | Disagree | Skip |"
    lines += "|---:|---:|---:|"
    def count(outcome: String) = results.count(_._2.outcome == outcome)
    lines += s"| ${count("agree")} | ${count("disagree")} | ${count("skip")} |"
    lines += ""
    lines += "## Runtime deviations from the design study"
    lines += ""
    lines += "- The real platform LangPomModel is constructed through the host's existing reflective service wiring; its read, " +
      "write-intent and write listeners run against HostLock. The platform formatter and Scala model/processors run without an application container."
    lines += "- ScalaLanguageCodeStyleSettingsProvider links unavailable UI classes (OptionGroup). A fixed LanguageCodeStyleProvider table " +
      "reproduces its reviewed customizeDefaults; the real ScalaCodeStyleSettings bean supplies import and formatter defaults."
    lines += "- Nonphysical copies obtain their document from the view provider: PsiDocumentManager.getDocument deliberately returns null for them. " +
      "Copies register both the document manager and its PriorityEventCollector. Omitting the latter lost same-length document changes and " +
      "caused two PSI consistency errors during initial replay; the completed run treats any logged platform error as disagreement."
    lines += "- Scalafmt requests are routed separately by ProjectSession; this replay exercises the IntelliJ formatter. " +
      "The registered processors are ScalaDocNewlinedPreFormatProcessor, ScalaBracePostFormatProcessor and ScalaTrailingCommaProcessor."
    lines += ""
    lines += "Cancellation inside util-diff's array algorithm is incomplete upstream. Large-file latency, diff limits and retained heap " +
      "were not benchmarked by this replay. DiffTooBig remains a separate LSP failure, without a whole-file fallback."
    lines += ""
    lines += "## Disagreements by cause"
    lines += ""
    results.filter(_._2.outcome == "disagree").groupMapReduce(_._2.reason.takeWhile(_ != ':'))(_ => 1)(_ + _)
      .toSeq.sortBy((cause, count) => (-count, cause)).foreach((cause, count) => lines += s"- $cause: $count")
    lines += ""
    lines += "## Cases"
    lines += ""
    lines += "| Case | Outcome | Reason |"
    lines += "|---|---|---|"
    def cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
    results.foreach((name, result) => lines += s"| ${cell(name)} | ${result.outcome} | ${cell(result.reason)} |")
    lines += ""
    lines += "## Adapter observations"
    lines += ""
    for ((entry, count) <- CallLog.snapshot if entry.startsWith("UNSUPPORTED ") || entry.startsWith("LOGGED ERROR "))
      lines += s"- $entry: $count"
    val report = Path.of(reportPath)
    Files.createDirectories(report.toAbsolutePath.getParent)
    Files.write(report, lines.result().asJava)
    println(s"formatter: ${count("agree")} agree, ${count("disagree")} disagree, ${count("skip")} skip; report at $report")
  }

  private def runCase(host: ScalaPsiHost, path: Path, name: String): Result = {
    if (path.getFileName.toString.startsWith("_")) return Result("skip", "disabled by FileSetTestBase's filename convention")
    val suite = name.takeWhile(_ != '/')
    if (!Set("data", "scalaDocData", "multiLineStringData", "multiLineStringDataAlignQuotes")(suite))
      return Result("skip", "no reviewed suite setup for this data directory")
    try {
      val parts = Files.readString(path).replace("\r\n", "\n").split("\\n?(?m)^-{4,}\\n?", -1)
      if (parts.length != 2) return Result("skip", "expected two file-set sections; settings sections need review")
      val file = host.read {
        com.intellij.psi.PsiFileFactory.getInstance(host.project)
          .createFileFromText("dummy.scala", ScalaLanguage.INSTANCE, parts.head, false, false) match {
          case f: ScalaFile => f
          case other => throw new IllegalStateException(s"not a Scala fixture: $other")
        }
      }
      val manager = CodeStyleSettingsManager.getInstance(host.project)
      val settings = host.read(manager.getCurrentSettings.clone())
      val scala = settings.getCustomSettings(classOf[ScalaCodeStyleSettings])
      scala.USE_SCALADOC2_FORMATTING = true
      if (suite.startsWith("multiLineStringData")) {
        scala.MULTILINE_STRING_INSERT_MARGIN_ON_ENTER = true
        scala.MULTILINE_STRING_ALIGN_DANGLING_CLOSING_QUOTES = suite == "multiLineStringDataAlignQuotes"
        scala.MULTILINE_STRING_OPENING_QUOTES_ON_NEW_LINE = true
        scala.MULTILINE_STRING_MARGIN_INDENT = 3
        settings.getCommonSettings(ScalaLanguage.INSTANCE).ALIGN_MULTILINE_BINARY_OPERATION = true
      }
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val changed = manager.computeWithLocalSettings(settings, _ =>
        ChangeEngine.format(file, None, 2, useTabs = false))
      val reconstructed = TestHost.applyEdits(parts.head, changed.edits)
      if (reconstructed != changed.text) throw new IllegalStateException("edits do not reconstruct the copy")
      val expected = parts.last.trim
      val actual = changed.text.trim
      if (HostLog.count(HostLog.Level.Error) != errorsBefore) Result("disagree", "platform logged an error during formatting")
      else if (actual == expected) Result("agree", "")
      else {
        val expectedLines = expected.split("\n", -1)
        val actualLines = actual.split("\n", -1)
        val line = expectedLines.zipAll(actualLines, "<missing>", "<missing>").indexWhere((e, a) => e != a)
        Result("disagree", s"text mismatch ($suite): line ${line + 1}, expected `${expectedLines.lift(line).getOrElse("<missing>")}`, " +
          s"actual `${actualLines.lift(line).getOrElse("<missing>")}`")
      }
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"formatter oracle $name failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  }
}
