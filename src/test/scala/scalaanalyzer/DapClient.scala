package scalaanalyzer

import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.{IDebugProtocolClient, IDebugProtocolServer}

import java.net.{InetAddress, Socket, URI}
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}

import scala.jdk.CollectionConverters.*

/** A breakpoint at a line, with the options of `configure` (condition, hit count, log message). */
def sourceBreakpoint(line: Int, configure: SourceBreakpoint => Unit = _ => ()): SourceBreakpoint = {
  // A plain instance: Gson writes an anonymous subclass as null.
  val breakpoint = new SourceBreakpoint
  breakpoint.setLine(line)
  configure(breakpoint)
  breakpoint
}

/**
 * A DAP client for the tests of the debug adapter: it connects to the address a debug session answered with and keeps
 * what the adapter sends, for the test to wait for. Every wait is bounded.
 *
 * The client is a proxy of the interface rather than a Scala class: Scala writes forwarders of the interface's default
 * methods into a class that extends it, with their JSON-RPC annotations, which lsp4j then finds twice.
 *
 * @param uri the `tcp://host:port` address of the session's server
 */
final class DapClient(uri: URI) extends munit.Assertions with AutoCloseable {
  /** Completes when the adapter has sent `initialized`, after which it takes breakpoints. */
  private val ready = new CompletableFuture[Unit]
  private val stops = new LinkedBlockingQueue[StoppedEventArguments]
  private val ended = new CompletableFuture[String]
  private val printed = new LinkedBlockingQueue[String]

  /** The client the launcher answers with; the events the tests do not wait for are ignored. */
  private val client: IDebugProtocolClient = java.lang.reflect.Proxy.newProxyInstance(getClass.getClassLoader,
    Array(classOf[IDebugProtocolClient]), (_, method, arguments) => {
      (method.getName, Option(arguments).flatMap(_.headOption).orNull) match {
        case ("initialized", _) => ready.complete(())
        case ("stopped", stop: StoppedEventArguments) => stops.add(stop)
        case ("terminated", _) => ended.complete("terminated")
        case ("exited", exit: ExitedEventArguments) => ended.complete(s"exited ${exit.getExitCode}")
        case ("output", output: OutputEventArguments) => printed.add(output.getOutput)
        case _ => ()
      }
      null
    }).asInstanceOf[IDebugProtocolClient] // sound: the proxy implements exactly this interface

  private val socket = new Socket(InetAddress.getByName(uri.getHost), uri.getPort)

  private val launcher = DSPLauncher.createClientLauncher(client, socket.getInputStream, socket.getOutputStream)
  launcher.startListening()

  /** The adapter, as the requests of the protocol. */
  val server: IDebugProtocolServer = launcher.getRemoteProxy

  /** Everything the program printed so far, which a failure message shows. */
  def output: String = printed.asScala.mkString

  /** Waits for a response of the adapter, failing the test with `what` and the program's output when it fails or is late. */
  def await[T](what: String, future: CompletableFuture[T]): T =
    try future.get(60, TimeUnit.SECONDS)
    catch {
      case failure: Exception => fail(s"$what failed: $failure\noutput: $output")
    }

  /**
   * Initializes the adapter and sends `launch`, then waits until the adapter accepts breakpoints. The adapter answers
   * `launch` once it has attached to the program, which waits for [[run]].
   *
   * @return the response to `launch`, which [[run]] waits for
   */
  def start(): CompletableFuture[Void] = begin(server.launch(java.util.Map.of("noDebug", false)))

  /**
   * As [[start]], for a program that already runs: sends `attach` to its JDWP agent, as an editor does.
   *
   * @return the response to `attach`, which [[run]] waits for
   */
  def startAttached(hostName: String, port: Int): CompletableFuture[Void] =
    begin(server.attach(java.util.Map.of("hostName", hostName, "port", Int.box(port))))

  /** Initializes the adapter, sends the request that starts the session, and waits until breakpoints are accepted. */
  private def begin(request: => CompletableFuture[Void]): CompletableFuture[Void] = {
    val initialize = new InitializeRequestArguments
    initialize.setAdapterID("scala-analyzer")
    initialize.setLinesStartAt1(true)
    initialize.setColumnsStartAt1(true)
    await("initialize", server.initialize(initialize))
    val started = request
    await("initialized event", ready)
    started
  }

  /** Puts a breakpoint at a line of a source and checks that the adapter verified it. */
  def breakAt(source: Path, line: Int): Unit = assertEquals(setBreakpoint(source, line).isVerified, true)

  /** Puts a breakpoint at a line of a source, as the only one of the file, and answers the adapter's verdict. */
  def setBreakpoint(source: Path, line: Int): Breakpoint = setBreakpoints(source, Seq(sourceBreakpoint(line))).head

  /**
   * Replaces the breakpoints of a source, as an editor sends them all at once, and checks that the adapter verified
   * each.
   */
  def setVerifiedBreakpoints(source: Path, breakpoints: Seq[SourceBreakpoint]): Unit =
    assertEquals(setBreakpoints(source, breakpoints).map(_.isVerified), breakpoints.map(_ => true))

  /** Replaces the breakpoints of a source, and answers the adapter's verdicts in the same order. */
  def setBreakpoints(source: Path, breakpoints: Seq[SourceBreakpoint]): Seq[Breakpoint] = {
    val file = new Source
    file.setPath(source.toString)
    val arguments = new SetBreakpointsArguments
    arguments.setSource(file)
    arguments.setBreakpoints(breakpoints.toArray)
    val set = await("setBreakpoints", server.setBreakpoints(arguments))
    assertEquals(set.getBreakpoints.length, breakpoints.size)
    set.getBreakpoints.toSeq
  }

  /** Makes the program stop at the exceptions of the adapter's filters: `caught`, `uncaught`, or both. */
  def breakOnExceptions(filters: String*): Unit = {
    val arguments = new SetExceptionBreakpointsArguments
    arguments.setFilters(filters.toArray)
    await("setExceptionBreakpoints", server.setExceptionBreakpoints(arguments))
  }

  /** The frame the program stopped in. */
  def topFrame(stop: StoppedEventArguments): StackFrame = {
    val arguments = new StackTraceArguments
    arguments.setThreadId(stop.getThreadId)
    await("stackTrace", server.stackTrace(arguments)).getStackFrames.head
  }

  /** The first scope of a frame, its local variables. */
  def locals(frame: StackFrame): Scope = {
    val arguments = new ScopesArguments
    arguments.setFrameId(frame.getId)
    await("scopes", server.scopes(arguments)).getScopes.head
  }

  /** The variables of a scope or a structured value, by name. */
  def variables(reference: Int): Map[String, Variable] = {
    val arguments = new VariablesArguments
    arguments.setVariablesReference(reference)
    await("variables", server.variables(arguments)).getVariables.map(variable => variable.getName -> variable).toMap
  }

  /** Evaluates an expression in a frame, as the debug console does, and answers its value. */
  def evaluate(frame: StackFrame, expression: String): String = {
    val arguments = new EvaluateArguments
    arguments.setExpression(expression)
    arguments.setFrameId(frame.getId)
    arguments.setContext("repl")
    await(s"evaluate $expression", server.evaluate(arguments)).getResult
  }

  /**
   * Sends the adapter's custom `redefineClasses` request, which redefines the classes the host reported as recompiled
   * (hot code replace). The protocol's server interface does not know it, so it goes through the raw endpoint.
   *
   * @return the JVM names of the classes the adapter redefined
   */
  def redefineClasses(): Seq[String] = {
    val response = await("redefineClasses", launcher.getRemoteEndpoint.request("redefineClasses", java.util.Map.of()))
    response match {
      // Gson reads the body as a tree of maps and lists.
      case body: com.google.gson.JsonObject =>
        Option(body.getAsJsonArray("changedClasses")).map(_.asScala.map(_.getAsString).toSeq)
          .getOrElse(fail(s"hot code replace failed: $body"))
      case other => fail(s"unexpected redefineClasses response: $other")
    }
  }

  /** Ends the configuration, which starts the program, and waits until the adapter has attached to it. */
  def run(launched: CompletableFuture[Void]): Unit = {
    await("configurationDone", server.configurationDone(new ConfigurationDoneArguments))
    await("launch", launched)
  }

  /** Waits for the program to stop, for the longest the tests allow: the first stop compiles nothing but loads classes. */
  def nextStop(): StoppedEventArguments =
    Option(stops.poll(2, TimeUnit.MINUTES)).getOrElse(fail(s"no stop at the breakpoint; output: $output"))

  /** Resumes the program after a stop. */
  def resume(stop: StoppedEventArguments): Unit = {
    val arguments = new ContinueArguments
    arguments.setThreadId(stop.getThreadId)
    await("continue", server.continue_(arguments))
  }

  /** Steps into the call of the stopped line and waits for the program to stop again. */
  def stepIn(stop: StoppedEventArguments): StoppedEventArguments = {
    val arguments = new StepInArguments
    arguments.setThreadId(stop.getThreadId)
    await("stepIn", server.stepIn(arguments))
    nextStop()
  }

  /**
   * Waits for the next stop or for the end of the program, whichever comes first, within the time `nextStop` allows.
   *
   * @return the stop, or how the program ended (as [[awaitEnd]] tells it)
   */
  def nextStopOrEnd(): Either[StoppedEventArguments, String] = {
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
    // Both arrive from the client's thread; the stop queue is polled in short steps so that an end is noticed too.
    Iterator.continually(Option(stops.poll(100, TimeUnit.MILLISECONDS)).map(Left(_))
        .orElse(Option.when(ended.isDone)(Right(ended.join()))))
      .takeWhile(found => found.isEmpty && System.nanoTime() < deadline || found.nonEmpty)
      .collectFirst { case Some(found) => found }
      .getOrElse(fail(s"neither a stop nor the end of the program; output: $output"))
  }

  /** Waits for the end of the program: `terminated`, or `exited` with its code. */
  def awaitEnd(): String = await("end of the program", ended)

  /** Closes the connection, which ends the session if it is still running. */
  override def close(): Unit = socket.close()
}
