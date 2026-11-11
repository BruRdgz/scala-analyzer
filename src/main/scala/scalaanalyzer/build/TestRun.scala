package scalaanalyzer.build

import ch.epfl.scala.bsp4j.JvmEnvironmentItem
import org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner
import org.jetbrains.plugins.scala.testingSupport.specs2.Specs2Runner
import org.jetbrains.plugins.scala.testingSupport.uTest.UTestRunner

import scalaanalyzer.munitrunner.MUnitTestRunner

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

/**
 * Executes one test selection or main class at a time, away from document requests and host locks.
 *
 * A single daemon thread prepares and runs it. `active` is guarded by this instance's lock; a run's process is
 * guarded by the run's own lock, and its cancellation flag is volatile.
 */
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

  /**
   * Starts a test selection on the run thread: it prepares the environment, then launches the runner.
   *
   * Throws `IllegalStateException` if a run is already in progress and not cancelled; after a cancelled one, the new run
   * waits for it to end. Cancelling the returned future terminates the runner and its child processes. A run that fails
   * to prepare or launch reports the failure through `log` and `finished`; `finished` is not called after cancellation.
   *
   * @param selection suite, and optionally tests, to run
   * @param prepare supplies the runner's environment; called on the run thread, before the process starts
   * @param log receives the runner's output lines and test outcomes, from the run thread
   * @param finished receives the final summary once the runner exits, or a failed result if the run could not complete
   * @return a future that completes with `null` after the runner process has exited and the run is no longer active;
   *         cancelling it completes it at once, while the run ends on the run thread
   */
  def start(selection: Selection, prepare: () => Environment, log: String => Unit,
            finished: Result => Unit): CompletableFuture[AnyRef] = submit(testJob(selection), prepare, log, finished)

  /**
   * Starts a main class on the run thread, with the lifecycle of [[start]]: the same single active run, preparation,
   * cancellation and failure reporting. The program gets no arguments and no standard input.
   *
   * @param main main class to run
   * @param prepare supplies the program's environment; called on the run thread, before the process starts
   * @param log receives the program's output lines, standard error included, from the run thread
   * @param finished receives a summary of the exit status once the program exits, or a failed result if the run could
   *                 not complete
   * @return a future that completes with `null` after the program has exited and the run is no longer active
   */
  def startMain(main: MainSelection, prepare: () => Environment, log: String => Unit,
                finished: Result => Unit): CompletableFuture[AnyRef] = submit(mainJob(main), prepare, log, finished)

  /**
   * Makes `job` the active run and queues it on the run thread, behind a cancelled run that is still ending; fails if
   * another run is active and not cancelled.
   */
  private def submit(job: Job, prepare: () => Environment, log: String => Unit,
                     finished: Result => Unit): CompletableFuture[AnyRef] = synchronized {
    if (active.exists(!_.cancelled)) throw new IllegalStateException("a run is already in progress")

    val run = new Run

    active = Some(run)
    run.answer.whenComplete((_, _) => if (run.answer.isCancelled) run.cancel())
    executor.execute(() => execute(run, job, prepare, log, finished))
    run.answer
  }

  /**
   * Prepares and launches the run on the executor thread, then always cancels it, clears `active` unless a newer run
   * took its place, and completes it.
   */
  private def execute(run: Run, job: Job, prepare: () => Environment,
                      log: String => Unit, finished: Result => Unit): Unit = {
    try {
      val environment = prepare()
      if (!run.cancelled) {
        val interpreter = job.interpreter(log)
        val process = run.launch(job.command(environment), environment.item)
        process.foreach { child =>
          Using.resource(child.inputReader(UTF_8)) { reader =>
            reader.lines().iterator().asScala.foreach(interpreter.line)
          }
          val exit = child.waitFor()
          if (!run.cancelled) finished(interpreter.result(exit))
        }
      }
    } catch {
      case NonFatal(error) =>
        if (!run.cancelled) reportFailure(job, error, log, finished)
    } finally {
      run.cancel()
      run.awaitExit()
      synchronized { if (active.exists(_ eq run)) active = None }
      run.answer.complete(null)
    }
  }

  /** Logs and reports a failed preparation or launch as a failed result. */
  private def reportFailure(job: Job, error: Throwable, log: String => Unit, finished: Result => Unit): Unit = {
    val message = s"${job.subject} failed: ${Option(error.getMessage).getOrElse(error.toString)}"
    log(message)
    finished(Result(message, failed = true))
  }

  /**
   * Cancels the current run and shuts down the run thread.
   *
   * Cancelling terminates the runner and its children; an outstanding BSP preparation finishes under the session's
   * serialization guard.
   */
  override def close(): Unit = synchronized {
    active.foreach(_.cancel())
    executor.shutdown()
  }

  /** One run: the future returned to the caller, its cancellation flag and its runner process. */
  private final class Run {
    val answer = new CompletableFuture[AnyRef]
    @volatile var cancelled = false
    private var process: Option[Process] = None

    /** Starts the runner in the environment's working directory unless the run was already cancelled. */
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

    /** Blocks until the launched runner, if any, has exited. */
    def awaitExit(): Unit = process.foreach(_.waitFor())

    /** Marks the run cancelled and forcibly ends the runner and its descendants. */
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
  /**
   * A lens's selection. Names retain the plugin's escaped encoding until its runner parses them.
   *
   * @param target BSP target URI of the module that holds the suite
   * @param framework test framework, one of [[Frameworks]]
   * @param suite suite name passed to the runner; backticks are removed first
   * @param testName names of the tests to run, one per line, or `None` for the whole suite
   */
  final case class Selection(target: String, framework: String, suite: String, testName: Option[String])

  /**
   * A main class to run.
   *
   * @param target BSP target URI of the module that holds the class
   * @param mainClass JVM name of the class that has the `main` method, as `java` takes it
   */
  final case class MainSelection(target: String, mainClass: String)

  /**
   * BSP test or run environment plus the target JDK, or deliberately the host JVM when BSP omits it.
   *
   * @param java path of the `java` executable that launches the runner
   * @param item the environment BSP reported: working directory, environment variables, JVM options and classpath
   */
  final case class Environment(java: Path, item: JvmEnvironmentItem)

  /**
   * Final user-facing summary; ignored tests warrant a warning rather than an all-passed notification.
   *
   * @param summary one-line outcome message
   * @param failed `true` if the run failed or any test failed
   * @param ignored `true` if the run completed and at least one test was ignored
   */
  final case class Result(summary: String, failed: Boolean = false, ignored: Boolean = false)

  /** Reads a server path that is either a file URI or a native path, as the plugin's BSP adapter does. */
  private def path(value: String): Path =
    if (value.startsWith("file:")) Path.of(URI.create(value)) else Path.of(value)

  /** Runner main class per framework (the plugin's, and ours for MUnit); all take the same arguments. */
  private val Runners: Map[String, Class[?]] = Map(
    "scalatest" -> classOf[ScalaTestRunner],
    "specs2" -> classOf[Specs2Runner],
    "utest" -> classOf[UTestRunner],
    "munit" -> classOf[MUnitTestRunner],
  )

  /** The frameworks whose selections can run, sorted by name. */
  val Frameworks: Seq[String] = Runners.keys.toSeq.sorted

  /** What a run launches and how it reads the output: `subject` names the run in a failure message. */
  private final case class Job(subject: String, command: Environment => Seq[String],
                               interpreter: (String => Unit) => Interpreter)

  /** Reads one run's output line by line and turns its exit code into the final summary. */
  private final case class Interpreter(line: String => Unit, result: Int => Result)

  /** The job of a test selection: the runner's command, and its TeamCity output read as test outcomes. */
  private def testJob(selection: Selection): Job = Job(
    s"${selection.suite}: test run",
    command(selection, _),
    log => {
      val report = new Report(log)

      Interpreter(report.line, report.result(selection.suite, _))
    })

  /** The job of a main class: its output goes to the log unread, and the summary says how it exited. */
  private def mainJob(main: MainSelection): Job = {
    val name = main.mainClass.split('.').last

    Job(
      s"${main.mainClass}: run",
      jvmCommand(_, Seq.empty, main.mainClass, Seq.empty),
      log => Interpreter(log, exit =>
        if (exit == 0) Result(s"$name: finished")
        else Result(s"$name: exited with code $exit", failed = true)))
  }

  /** Builds the runner's command line: the JVM, its options and classpath, then the runner's arguments. */
  private def command(selection: Selection, environment: Environment): Seq[String] = {
    val runner = Runners(selection.framework)
    // The runner's jar joins the project's test classpath; loading its class does not link the framework it calls.
    val runnerJar = Path.of(runner.getProtectionDomain.getCodeSource.getLocation.toURI).toString
    // A ScalaTest scope selects each of its tests by name, one per line.
    val names = selection.testName.toSeq.flatMap(_.split("\n")).flatMap(name => Seq("-testName", name))
    val arguments = Seq("-s", selection.suite.replace("`", "")) ++ names ++ Seq("-showProgressMessages", "true")

    jvmCommand(environment, Seq(runnerJar), runner.getName, arguments)
  }

  /** Builds a JVM command line: `java`, the environment's options, its classpath plus `extraClasspath`, the class. */
  private def jvmCommand(environment: Environment, extraClasspath: Seq[String], mainClass: String,
                         arguments: Seq[String]): Seq[String] = {
    val classpath = environment.item.getClasspath.asScala.map(entry => path(entry).toString) ++ extraClasspath

    Seq(environment.java.toString) ++ environment.item.getJvmOptions.asScala ++
      Seq("-cp", classpath.mkString(File.pathSeparator), mainClass) ++ arguments
  }

  // Possessive runs of unescaped characters: Java's regex recurses on each repetition, and failure details are long.
  /** Matches the body of a quoted TeamCity attribute value. */
  private val Value = """[^|']*+(?:\|.[^|']*+)*+"""

  /** Matches a whole TeamCity service message, capturing its kind and its attribute list. */
  private val Message = ("""##teamcity\[(\w+)((?:\s*\w+\s*=\s*'""" + Value + """')*+)\s*]""").r

  /** Matches one `name='value'` attribute, capturing the name and the still-escaped value. */
  private val Attribute = ("""(\w+)\s*=\s*'(""" + Value + """)'""").r

  /** Matches one `|`-escape, capturing the escaped character. */
  private val Escape = """\|(.)""".r

  /** A decoded TeamCity service message: its kind (such as `testFailed`) and its unescaped attributes. */
  private[build] final case class TeamCityMessage(kind: String, attributes: Map[String, String])

  /**
   * Decodes a TeamCity service message as the plugin's runners escape it (`TestRunnerUtil.escapeString`).
   *
   * @param text a whole `##teamcity[...]` line; anything else is rejected with `IllegalArgumentException`
   * @return the message kind and its attributes, unescaped
   */
  private[build] def message(text: String): TeamCityMessage = text match {
    case Message(kind, attributes) =>
      TeamCityMessage(kind, Attribute.findAllMatchIn(attributes).map(m => m.group(1) -> unescape(m.group(2))).toMap)
    case _ => throw new IllegalArgumentException(s"malformed TeamCity message: $text")
  }

  /** Replaces TeamCity `|` escapes with the characters they stand for; `|n` and `|r` become line breaks. */
  private def unescape(value: String): String = Escape.replaceAllIn(value, m => Matcher.quoteReplacement(m.group(1) match {
    case "n" => "\n"
    case "r" => "\r"
    case escaped => escaped
  }))

  /**
   * Tracks terminal outcomes by runner node identity; failure/ignored events may or may not precede testFinished.
   *
   * Not thread-safe: one run thread feeds it lines in order.
   *
   * @param log receives output lines, test outcomes and the first stack lines of failures
   */
  private[build] final class Report(log: String => Unit) {
    /** Progress of one test: `Running` until a terminal outcome is seen. */
    private enum Outcome {
      case Running, Passed, Failed, Ignored
    }
    import Outcome.*

    private val outcomes = mutable.Map.empty[String, Outcome]
    private var runnerError = false
    /** Stack lines logged per failure: enough to identify the assertion and caller, short enough to stay readable. */
    private val StackLines = 8

    /**
     * Consumes one line of runner output.
     *
     * Plain lines go to `log` unless empty. TeamCity messages update the recorded outcomes and log what they report;
     * a message that starts like TeamCity output but is malformed throws `IllegalArgumentException`.
     *
     * @param text one line of runner output, without its terminator
     */
    def line(text: String): Unit = {
      if (!text.startsWith("##teamcity[")) { if (text.nonEmpty) log(text) }
      else {
        val TeamCityMessage(kind, fields) = message(text)
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

    /** Records a failed or ignored test and logs it with the start of its details. */
    private def finish(id: String, name: String, outcome: Outcome, fields: Map[String, String]): Unit = {
      outcomes(id) = outcome
      log(s"$name: ${outcome.toString.toLowerCase}${fields.get("message").fold("")(": " + _)}")
      fields.get("details").foreach(_.linesIterator.take(StackLines).foreach(log))
    }

    /**
     * Summarizes the outcomes recorded so far once the runner has exited.
     *
     * The run counts as failed when the runner exited abnormally, reported an error message, left a test running or
     * produced no test outcome at all.
     *
     * @param suite suite name; the summary uses the part after its last dot
     * @param exit runner process exit code
     * @return the summary; `failed` also holds when any test failed, `ignored` when any test was ignored
     */
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
