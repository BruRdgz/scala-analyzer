package scalaanalyzer.oracle

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

import scalaanalyzer.{Diagnostic, HostConfig, LineColumn, ScalaPsiHost}
import scalaanalyzer.build.{BspImport}
import scalaanalyzer.platform.{HostLog}

import java.io.File
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Highlights a whole project through the host as an LSP server would: imports the build over BSP, compiles it there,
 * adds its modules, opens every Scala source and reports each file's errors (the plugin's, and the compiler's for files
 * whose errors come from the compiler), with timings and heap use. For a project that compiles, every reported error
 * is a false positive (of the host, or of the plugin itself).
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.ProjectHighlighting <project dir> <report.md> [--connection=<name>]
 * [--plugin-errors]`. `--plugin-errors` takes every file's errors from the plugin's type-aware checks instead.
 */
object ProjectHighlighting {
  def main(args: Array[String]): Unit = {
    val (Seq(projectArg, reportPath), flags) = args.toSeq.partition(!_.startsWith("--"))
    val projectDir = Path.of(projectArg).toAbsolutePath
    val connection = flags.collectFirst { case f if f.startsWith("--connection=") => f.stripPrefix("--connection=") }
    val compilerErrors = !flags.contains("--plugin-errors")
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      compilerErrorsScala3 = compilerErrors,
    ))

    val t0 = System.nanoTime()
    val build = BspImport.connect(projectDir, connection)
    val (imported, compiled) =
      try {
        val imported = build.modules()
        (imported, if (compilerErrors) Some(build.compile()) else None)
      } finally build.close()
    val importMs = millisSince(t0)

    val t1 = System.nanoTime()
    host.addJdk(Path.of(System.getProperty("java.home")))
    // Each jar indexed here, once, to report it; adding the modules then reuses the mounted jars.
    val jarReports = imported.flatMap(_.spec.classpath).distinct.map(jar => jar -> host.addLibrary(jar))
    val modules = imported.map(m => m -> host.addModule(m.spec))
    val modulesMs = millisSince(t1)
    val heapAfterModulesMb = usedHeapMb()

    val t2 = System.nanoTime()
    // Java sources are opened so Scala code resolves their classes; only Scala files are highlighted.
    val opened = for ((m, hostModule) <- modules; source <- m.sources)
      yield projectDir.relativize(source).toString -> host.openFile(source, Files.readString(source), hostModule)
    val files = opened.collect { case (path, file: org.jetbrains.plugins.scala.lang.psi.api.ScalaFile) => path -> file }
    val openMs = millisSince(t2)

    val t3 = System.nanoTime()
    val results = files.map { (path, file) =>
      val start = System.nanoTime()
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val outcome =
        try {
          val fromPlugin = host.diagnostics(file).map(d => ("plugin", d))
          val fromCompiler =
            if (!host.isCompilerHighlighted(file)) Seq.empty
            else compiled.toSeq.flatMap(_.diagnostics.getOrElse(projectDir.resolve(path), Seq.empty)).map { c =>
              val text = host.read(file.getText)
              ("compiler", Diagnostic(c.severity, new TextRange(offset(text, c.start), offset(text, c.end)), c.message))
            }
          Right((fromPlugin ++ fromCompiler).filter(_._2.severity == HighlightSeverity.ERROR))
        }
        catch { case NonFatal(e) => println(s"crash in $path: $e"); Left(e.toString) }
      FileResult(path, outcome, HostLog.count(HostLog.Level.Error) - errorsBefore, millisSince(start))
    }
    val highlightMs = millisSince(t3)
    val heapAfterMb = usedHeapMb()
    // What the heap holds at the end; soft-referenced caches stay until the heap limit forces them out.
    val histogram = java.lang.management.ManagementFactory.getPlatformMBeanServer.invoke(
      new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
      Array[AnyRef](Array.empty[String]), Array(classOf[Array[String]].getName)).toString.linesIterator.take(33).toSeq

    val lines = Vector.newBuilder[String]
    def cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
    lines += s"# Project highlighting: ${projectDir.getFileName}"
    lines += ""
    lines += s"JDK ${System.getProperty("java.version")}, production mode."
    lines += ""
    lines += s"- modules: ${imported.size}; jars: ${imported.flatMap(_.spec.classpath).distinct.size}; Scala files: ${files.size}; Java files: ${opened.size - files.size}"
    lines += s"- files without errors: ${results.count(_.isClean)} / ${results.size}; crashed: ${results.count(_.outcome.isLeft)}"
    lines += s"- files whose highlighting logged errors (incomplete highlights): ${results.count(_.loggedErrors > 0)}"
    lines += s"- errors: ${results.map(_.outcome.fold(_ => 0, _.size)).sum}"
    lines += s"- errors from: ${if (compilerErrors) "the compiler for Scala 3 files (build compile status: " + compiled.map(c => if (c.succeeded) "OK" else "failed").getOrElse("-") + ")" else "the plugin's type-aware checks"}"
    lines += s"- BSP import${if (compilerErrors) " + compile" else ""}: $importMs ms; adding modules (mount + index jars): $modulesMs ms, heap after: $heapAfterModulesMb MB"
    lines += s"- opening sources (parse + index): $openMs ms"
    lines += s"- highlighting (one thread): $highlightMs ms, heap after: $heapAfterMb MB"
    lines += s"- slowest files: ${results.sortBy(-_.ms).take(5).map(r => s"${r.path} ${r.ms} ms").mkString(", ")}"
    lines += s"- max heap: ${Runtime.getRuntime.maxMemory / (1024 * 1024)} MB"
    lines += ""
    lines += "## Heap histogram (top classes at the end)"
    lines += ""
    lines += "```"
    lines ++= histogram
    lines += "```"
    lines += ""
    lines += "## Jars"
    lines += ""
    lines += "| Jar | Class files | Scala indexed | Java | Refused |"
    lines += "|---|---|---|---|---|"
    for ((jar, r) <- jarReports)
      lines += s"| ${jar.getFileName} | ${r.classFiles} | ${r.scalaIndexed} | ${r.javaClasses} | ${r.refused.size} |"
    lines += ""
    lines += "## Errors by message"
    lines += ""
    val messages = results.flatMap(_.outcome.toOption.toSeq.flatten).map((origin, d) => s"[$origin] " + d.message.replaceAll("'[^']*'", "'…'").take(90))
    for ((message, count) <- messages.groupBy(identity).view.mapValues(_.size).toSeq.sortBy(-_._2).take(30))
      lines += s"- $count × ${cell(message)}"
    lines += ""
    lines += "## Files"
    lines += ""
    lines += "| File | Errors |"
    lines += "|---|---|"
    for (r <- results if !r.isClean) {
      val text = r.outcome match {
        case Left(crash) => s"crash: $crash"
        case Right(ds) => ds.map((origin, d) => s"${d.range} [$origin] ${d.message}").mkString("<br>")
      }
      val logged = if (r.loggedErrors > 0) s" (${r.loggedErrors} logged errors)" else ""
      lines += s"| ${r.path} | ${cell(text + logged)} |"
    }
    Files.createDirectories(Path.of(reportPath).toAbsolutePath.getParent)
    Files.write(Path.of(reportPath), lines.result().asJava)
    println(s"project highlighting: ${results.count(_.isClean)} / ${results.size} files without errors; report at $reportPath")
  }

  /**
   * Each error with where it came from: `plugin` or `compiler`. Errors the platform logged while highlighting the
   * file (an annotator that failed, caught by the platform) mean its highlights are incomplete: it is not clean.
   */
  private final case class FileResult(path: String, outcome: Either[String, Seq[(String, Diagnostic)]], loggedErrors: Long, ms: Long) {
    def isClean: Boolean = outcome.exists(_.isEmpty) && loggedErrors == 0
  }

  private def offset(text: String, position: LineColumn): Int = {
    val lineStart = Iterator.iterate(0)(i => text.indexOf('\n', i) + 1).drop(position.line).next()
    math.min(lineStart + position.column, text.length)
  }

  private def millisSince(start: Long): Long = (System.nanoTime() - start) / 1_000_000

  private def usedHeapMb(): Long = {
    System.gc()
    val rt = Runtime.getRuntime
    (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
  }
}
