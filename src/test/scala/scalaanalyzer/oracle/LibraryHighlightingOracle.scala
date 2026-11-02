package scalaanalyzer.oracle

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.LatestScalaVersions
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.{ScIntegerLiteral, ScStringLiteral}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScInfixExpr, ScTuple}
import org.jetbrains.plugins.scala.project.ScalaFeatures

import scalaanalyzer.{Diagnostic, HostConfig, ScalaPsiHost}

import java.io.File
import java.nio.file.{FileSystems, Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Differential oracle for diagnostics: highlights every source file of scala-library 2.13 through the host and
 * compares the errors with `ScalaLibraryHighlightingTest_2_13.filesWithProblems`, the errors the plugin's CI expects
 * inside IntelliJ for the same sources (known false positives; every other file must have none).
 *
 * As in the IDE, the sources are library sources: not indexed, so references from them resolve to the library's
 * compiled classes, which are indexed as usual. Like the plugin's test, the host runs in unit-test mode.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.LibraryHighlightingOracle <intellij-scala checkout> <report.md>`.
 */
object LibraryHighlightingOracle {
  def main(args: Array[String]): Unit = {
    val Array(repo, reportPath) = args
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      unitTestMode = true,
    ))
    val t0 = System.nanoTime()
    host.addJdk(Path.of(System.getProperty("java.home")))
    host.addLibrary(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))
    val indexMs = (System.nanoTime() - t0) / 1_000_000
    val heapAfterIndexMb = usedHeapMb()

    val expected = host.read(expectedProblems(host, Path.of(repo,
      "sbt/sbt-project-highlighting-tests/test/org/jetbrains/plugins/scala/projectHighlighting/scalaLibrary/ScalaLibraryHighlightingTest_2_13.scala")))
    val features = ScalaFeatures.onlyByVersion(LatestScalaVersions.Scala_2_13)
    val sourcesJar = FileSystems.newFileSystem(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrarySources")))
    val root = sourcesJar.getPath("/")
    val sources = Files.walk(root).iterator().asScala.filter(_.toString.endsWith(".scala")).toSeq.sortBy(_.toString)

    val t1 = System.nanoTime()
    val results = sources.map { source =>
      val relative = root.relativize(source).toString
      val start = System.nanoTime()
      val outcome =
        try Right(host.diagnostics(host.parse(Files.readString(source), features)).filter(_.severity == HighlightSeverity.ERROR))
        catch {
          case NonFatal(e) =>
            println(s"crash in $relative: $e")
            Left(s"$e at ${e.getStackTrace.headOption.getOrElse("?")}")
        }
      FileResult(relative, outcome, expected.getOrElse(relative, Set.empty), (System.nanoTime() - start) / 1_000_000)
    }
    val highlightMs = (System.nanoTime() - t1) / 1_000_000
    Report.write(Path.of(reportPath), results, indexMs, highlightMs, heapAfterIndexMb, usedHeapMb())
    println(s"library highlighting oracle: ${results.count(_.agrees)} / ${results.size} files agree; report at $reportPath")
  }

  private def usedHeapMb(): Long = {
    System.gc()
    val rt = Runtime.getRuntime
    (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
  }

  /** `"scala/Any.scala" -> Set((3533, 3541), ...)` entries of `filesWithProblems`, read with the host's parser. */
  private def expectedProblems(host: ScalaPsiHost, testSource: Path): Map[String, Set[TextRange]] = {
    val file = host.parse(Files.readString(testSource), ScalaFeatures.onlyByVersion(LatestScalaVersions.Scala_3_LTS))
    val entries = for {
      infix <- PsiTreeUtil.findChildrenOfType(file, classOf[ScInfixExpr]).asScala.toSeq
      if infix.operation.refName == "->"
      path <- Option(infix.left).collect { case s: ScStringLiteral => s.getValue }
    } yield path -> PsiTreeUtil.findChildrenOfType(infix.right, classOf[ScTuple]).asScala.toSet.map { tuple =>
      tuple.exprs match {
        case Seq(start: ScIntegerLiteral, end: ScIntegerLiteral) => new TextRange(start.getValue, end.getValue)
        case other => throw new IllegalStateException(s"unexpected range in $path: ${tuple.getText}")
      }
    }
    if (entries.isEmpty) throw new IllegalStateException(s"no filesWithProblems entries found in $testSource")
    entries.toMap
  }

  final case class FileResult(path: String, outcome: Either[String, Seq[Diagnostic]], expected: Set[TextRange], ms: Long) {
    def actual: Set[TextRange] = outcome.fold(_ => Set.empty, _.map(_.range).toSet)
    def agrees: Boolean = outcome.isRight && actual == expected
  }

  object Report {
    def write(path: Path, results: Seq[FileResult], indexMs: Long, highlightMs: Long, heapAfterIndexMb: Long, heapAfterMb: Long): Unit = {
      val lines = Vector.newBuilder[String]
      def cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
      lines += "# Library highlighting oracle"
      lines += ""
      lines += s"scala-library 2.13 sources, JDK ${System.getProperty("java.version")}, unit-test mode."
      lines += ""
      lines += s"- files agreeing: ${results.count(_.agrees)} / ${results.size}"
      lines += s"- crashed files: ${results.count(_.outcome.isLeft)}"
      lines += s"- errors only in the host (not expected in IntelliJ): ${results.map(r => (r.actual -- r.expected).size).sum}"
      lines += s"- expected errors the host does not report: ${results.map(r => (r.expected -- r.actual).size).sum}"
      lines += s"- indexing JDK + scala-library: $indexMs ms; heap after: $heapAfterIndexMb MB"
      lines += s"- highlighting all files (one thread): $highlightMs ms; heap after: $heapAfterMb MB"
      val slowest = results.sortBy(-_.ms).take(5).map(r => s"${r.path} ${r.ms} ms").mkString(", ")
      lines += s"- slowest files: $slowest"
      lines += ""
      lines += "## Disagreements"
      lines += ""
      lines += "| File | Only in host | Only in IntelliJ |"
      lines += "|---|---|---|"
      for (r <- results if !r.agrees) {
        val onlyHost = r.outcome match {
          case Left(crash) => s"crash: $crash"
          case Right(ds) => ds.filterNot(d => r.expected(d.range)).map(d => s"${d.range} ${d.message}").mkString("<br>")
        }
        val onlyIde = (r.expected -- r.actual).toSeq.sortBy(_.getStartOffset).mkString(", ")
        lines += s"| ${r.path} | ${cell(onlyHost)} | $onlyIde |"
      }
      Files.createDirectories(path.toAbsolutePath.getParent)
      Files.write(path, lines.result().asJava)
    }
  }
}
