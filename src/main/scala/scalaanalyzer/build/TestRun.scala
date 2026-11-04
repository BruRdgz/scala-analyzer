package scalaanalyzer.build

import ch.epfl.scala.bsp4j.JvmEnvironmentItem
import org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner
import org.jetbrains.plugins.scala.testingSupport.specs2.Specs2Runner
import org.jetbrains.plugins.scala.testingSupport.uTest.UTestRunner

import scalaanalyzer.munit.MUnitTestRunner

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, Executors, ThreadFactory}
import java.util.regex.Matcher

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Executes one test selection at a time, away from document requests and host locks. */
final class TestRun extends AutoCloseable {
  import TestRun.*

  private val executor = Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(task: Runnable): Thread = {
      val thread = new Thread(task, "scala-analyzer-tests")
      thread.setDaemon(true)
      thread
    }
  })
  private var active: Option[Run] = None

  /** Runs after preparation, completing only after process exit; cancellation terminates the runner and its children. */
  def start(selection: Selection, prepare: () => Environment, log: String => Unit,
            finished: Result => Unit): CompletableFuture[AnyRef] = synchronized {
    if (active.nonEmpty) throw new IllegalStateException("a test run is already in progress")
    val run = new Run
    active = Some(run)
    run.answer.whenComplete((_, _) => if (run.answer.isCancelled) run.cancel())
    executor.execute(() => execute(run, selection, prepare, log, finished))
    run.answer
  }

  private def execute(run: Run, selection: Selection, prepare: () => Environment,
                      log: String => Unit, finished: Result => Unit): Unit = {
    try {
      val environment = prepare()
      if (!run.cancelled) {
        val report = new Report(log)
        val process = run.launch(command(selection, environment), environment.item)
        process.foreach { child =>
          Using.resource(child.inputReader(UTF_8)) { reader =>
            reader.lines().iterator().asScala.foreach(report.line)
          }
          val exit = child.waitFor()
          if (!run.cancelled) finished(report.result(selection.suite, exit))
        }
      }
    } catch {
      case NonFatal(error) =>
        if (!run.cancelled) reportFailure(selection, error, log, finished)
    } finally {
      run.cancel()
      run.awaitExit()
      synchronized { active = None }
      run.answer.complete(null)
    }
  }

  private def reportFailure(selection: Selection, error: Throwable, log: String => Unit, finished: Result => Unit): Unit = {
    val message = s"${selection.suite}: test run failed: ${Option(error.getMessage).getOrElse(error.toString)}"
    log(message)
    finished(Result(message, failed = true))
  }

  /** Cancels the current process; an outstanding BSP preparation finishes under the session's serialization guard. */
  override def close(): Unit = synchronized {
    active.foreach(_.cancel())
    executor.shutdown()
  }

  private final class Run {
    val answer = new CompletableFuture[AnyRef]
    @volatile var cancelled = false
    private var process: Option[Process] = None

    def launch(command: Seq[String], environment: JvmEnvironmentItem): Option[Process] = synchronized {
      if (cancelled) None
      else {
        val builder = new ProcessBuilder(command.asJava).redirectErrorStream(true)
          .directory(path(environment.getWorkingDirectory).toFile)
        builder.environment().putAll(environment.getEnvironmentVariables)
        process = Some(builder.start())
        process
      }
    }

    def awaitExit(): Unit = process.foreach(_.waitFor())

    def cancel(): Unit = synchronized {
      cancelled = true
      process.foreach { child =>
        child.descendants().forEach(_.destroyForcibly())
        if (child.isAlive) child.destroyForcibly()
      }
    }
  }
}

/** Runner arguments and the small subset of TeamCity output needed for readable test results. */
object TestRun {
  /** A lens's selection. Names retain the plugin's escaped encoding until its runner parses them. */
  final case class Selection(target: String, framework: String, suite: String, testName: Option[String])

  /** BSP test environment plus the target JDK, or deliberately the host JVM when BSP omits it. */
  final case class Environment(java: Path, item: JvmEnvironmentItem)

  /** Final user-facing summary; ignored tests warrant a warning rather than an all-passed notification. */
  final case class Result(summary: String, failed: Boolean = false, ignored: Boolean = false)

  // As the plugin's BSP adapter: servers use both file URIs and native paths for these environment fields.
  private def path(value: String): Path =
    if (value.startsWith("file:")) Path.of(URI.create(value)) else Path.of(value)

  /** The runner main class of each framework a lens can run: the plugin's, and ours for MUnit; all take the same arguments. */
  private val Runners: Map[String, Class[?]] = Map(
    "scalatest" -> classOf[ScalaTestRunner],
    "specs2" -> classOf[Specs2Runner],
    "utest" -> classOf[UTestRunner],
    "munit" -> classOf[MUnitTestRunner],
  )

  /** The frameworks whose selections can run. */
  val Frameworks: Seq[String] = Runners.keys.toSeq.sorted

  private def command(selection: Selection, environment: Environment): Seq[String] = {
    val runner = Runners(selection.framework)
    // The runner's jar joins the project's test classpath; loading its class does not link the framework it calls.
    val runnerJar = Path.of(runner.getProtectionDomain.getCodeSource.getLocation.toURI).toString
    val classpath = environment.item.getClasspath.asScala.map(entry => path(entry).toString) :+ runnerJar
    // A ScalaTest scope selects each of its tests by name, one per line.
    val names = selection.testName.toSeq.flatMap(_.split("\n")).flatMap(name => Seq("-testName", name))
    Seq(environment.java.toString) ++ environment.item.getJvmOptions.asScala ++
      Seq("-cp", classpath.mkString(File.pathSeparator), runner.getName, "-s", selection.suite.replace("`", "")) ++
      names ++ Seq("-showProgressMessages", "true")
  }

  // Possessive runs of unescaped characters: Java's regex recurses on each repetition, and failure details are long.
  private val Value = """[^|']*+(?:\|.[^|']*+)*+"""
  private val Message = ("""##teamcity\[(\w+)((?:\s*\w+\s*=\s*'""" + Value + """')*+)\s*]""").r
  private val Attribute = ("""(\w+)\s*=\s*'(""" + Value + """)'""").r
  private val Escape = """\|(.)""".r

  /** Decodes a TeamCity service message as the plugin's runners escape it (`TestRunnerUtil.escapeString`). */
  private[build] def message(text: String): (String, Map[String, String]) = text match {
    case Message(kind, attributes) =>
      kind -> Attribute.findAllMatchIn(attributes).map(m => m.group(1) -> unescape(m.group(2))).toMap
    case _ => throw new IllegalArgumentException(s"malformed TeamCity message: $text")
  }

  private def unescape(value: String): String = Escape.replaceAllIn(value, m => Matcher.quoteReplacement(m.group(1) match {
    case "n" => "\n"
    case "r" => "\r"
    case escaped => escaped
  }))

  /** Tracks terminal outcomes by runner node identity; failure/ignored events may or may not precede testFinished. */
  private[build] final class Report(log: String => Unit) {
    private enum Outcome {
      case Running, Passed, Failed, Ignored
    }
    import Outcome.*

    private val outcomes = mutable.Map.empty[String, Outcome]
    private var runnerError = false
    // Keep failures readable in the output channel; the first eight stack lines identify the assertion and caller.
    private val StackLines = 8

    def line(text: String): Unit = {
      if (!text.startsWith("##teamcity[")) { if (text.nonEmpty) log(text) }
      else {
        val (kind, fields) = message(text)
        val name = fields.getOrElse("name", "test")
        val id = fields.getOrElse("nodeId", fields.getOrElse("flowId", "") + ":" + name)
        kind match {
          case "testStarted" => outcomes(id) = Running
          case "testFailed" => finish(id, name, Failed, fields)
          case "testIgnored" => finish(id, name, Ignored, fields)
          case "testFinished" if !outcomes.get(id).exists(Set(Failed, Ignored)) =>
            outcomes(id) = Passed
            log(s"$name: passed")
          case "testStdOut" | "testStdErr" => fields.get("out").foreach(log)
          case "message" =>
            if (fields.get("status").contains("ERROR")) runnerError = true
            fields.get("text").foreach(log)
            fields.get("errorDetails").foreach(_.linesIterator.take(StackLines).foreach(log))
          case _ => ()
        }
      }
    }

    private def finish(id: String, name: String, outcome: Outcome, fields: Map[String, String]): Unit = {
      outcomes(id) = outcome
      log(s"$name: ${outcome.toString.toLowerCase}${fields.get("message").fold("")(": " + _)}")
      fields.get("details").foreach(_.linesIterator.take(StackLines).foreach(log))
    }

    def result(suite: String, exit: Int): Result = {
      val name = suite.split('.').last
      if (exit != 0 || runnerError || outcomes.values.exists(_ == Running) || outcomes.isEmpty)
        Result(s"$name: test run failed (runner exit $exit; no complete test results)", failed = true)
      else {
        def count(outcome: Outcome): Int = outcomes.values.count(_ == outcome)
        Result(s"$name: ${count(Passed)} passed, ${count(Failed)} failed, ${count(Ignored)} ignored",
          failed = count(Failed) > 0, ignored = count(Ignored) > 0)
      }
    }
  }
}
