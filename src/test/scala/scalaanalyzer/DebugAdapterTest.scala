package scalaanalyzer

import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.{IDebugProtocolClient, IDebugProtocolServer}

import scalaanalyzer.workspace.ProjectSession

import com.intellij.psi.PsiManager

import java.net.{InetAddress, Socket, URI}
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Debugging a main class of the BSP fixture (`fixtures/bsp-project`) through the embedded debug adapter, with a DAP
 * client in the test. Runs in a JVM of its own (see `Test / testGrouping`): it opens the fixture's modules in the host.
 */
class DebugAdapterTest extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private val fixture = Path.of("fixtures/bsp-project").toAbsolutePath
  private val main = fixture.resolve("app/src/main/scala/app/Main.scala")
  /** The line of `println(text)` in `app.Main`, where `text` is assigned. */
  private val printLine = 8
  /** Every wait of the test is bounded by this. */
  private val Wait = 2

  /**
   * What the adapter sends to the client, kept for the test to wait for.
   *
   * The client is a proxy of the interface rather than a Scala class: Scala writes forwarders of the interface's default
   * methods into a class that extends it, with their JSON-RPC annotations, which lsp4j then finds twice.
   */
  private final class Events {
    val ready = new CompletableFuture[Unit]
    val stops = new LinkedBlockingQueue[StoppedEventArguments]
    val ended = new CompletableFuture[String]
    val printed = new LinkedBlockingQueue[String]

    /** The client the launcher answers with; the events the test does not wait for are ignored. */
    val client: IDebugProtocolClient = java.lang.reflect.Proxy.newProxyInstance(getClass.getClassLoader,
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
      }).asInstanceOf[IDebugProtocolClient]
  }

  test("debugs a main class: breakpoint, stack, variables, evaluation and exit") {
    val session = ProjectSession.open(TestHost.host, fixture)
    val events = new Events
    var socket: Socket = null
    try {
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main", _ => ())
      assertEquals(uri.getScheme, "tcp")
      socket = new Socket(InetAddress.getByName(uri.getHost), uri.getPort)
      val launcher = DSPLauncher.createClientLauncher(events.client, socket.getInputStream, socket.getOutputStream)
      launcher.startListening()
      val server = launcher.getRemoteProxy

      def await[T](what: String, future: CompletableFuture[T]): T =
        try future.get(60, TimeUnit.SECONDS)
        catch {
          case failure: Exception => fail(s"$what failed: $failure\noutput: ${events.printed.asScala.mkString}")
        }

      val initialize = new InitializeRequestArguments
      initialize.setAdapterID("scala-analyzer")
      initialize.setLinesStartAt1(true)
      initialize.setColumnsStartAt1(true)
      await("initialize", server.initialize(initialize))
      // The adapter answers `launch` once it has attached to the program, which waits for the configuration.
      val launched = server.launch(java.util.Map.of("noDebug", false))
      await("initialized event", events.ready)

      val source = new Source
      source.setPath(main.toString)
      val breakpoints = new SetBreakpointsArguments
      breakpoints.setSource(source)
      // A plain instance: Gson writes an anonymous subclass as null.
      val breakpoint = new SourceBreakpoint
      breakpoint.setLine(printLine)
      breakpoints.setBreakpoints(Array(breakpoint))
      val set = await("setBreakpoints", server.setBreakpoints(breakpoints))
      assertEquals(set.getBreakpoints.map(_.isVerified).toSeq, Seq(true))
      await("configurationDone", server.configurationDone(new ConfigurationDoneArguments))
      await("launch", launched)

      val stop = Option(events.stops.poll(Wait, TimeUnit.MINUTES))
        .getOrElse(fail(s"no stop at the breakpoint; output: ${events.printed.asScala.mkString}"))
      assertEquals(stop.getReason, "breakpoint")

      val traceArguments = new StackTraceArguments
      traceArguments.setThreadId(stop.getThreadId)
      val top = await("stackTrace", server.stackTrace(traceArguments)).getStackFrames.head
      assertEquals(Path.of(top.getSource.getPath), main)
      assertEquals(top.getLine.intValue, printLine)

      val scopes = new ScopesArguments
      scopes.setFrameId(top.getId)
      val locals = await("scopes", server.scopes(scopes)).getScopes.head
      val variablesArguments = new VariablesArguments
      variablesArguments.setVariablesReference(locals.getVariablesReference)
      val variables = await("variables", server.variables(variablesArguments)).getVariables.map(v => v.getName -> v.getValue).toMap
      assert(variables.get("text").exists(_.contains("HELLO, WORLD!")), variables.toString)

      def evaluate(expression: String): String = {
        val arguments = new EvaluateArguments
        arguments.setExpression(expression)
        arguments.setFrameId(top.getId)
        arguments.setContext("repl")
        await(s"evaluate $expression", server.evaluate(arguments)).getResult
      }
      // The adapter's own evaluator, which reads values through the debugger and downloads nothing.
      assert(evaluate("text").contains("HELLO, WORLD!"))
      // A lambda needs the Scala 2.13 expression compiler, which the adapter downloads on its first use: this
      // assertion needs network access (or a populated `target/host-state/debug-tools`).
      assert(evaluate("text.map(_.toLower)").contains("hello, world!"))

      val resume = new ContinueArguments
      resume.setThreadId(stop.getThreadId)
      await("continue", server.continue_(resume))
      val end = await("end of the program", events.ended)
      assert(Set("terminated", "exited 0")(end), end)
      assert(events.printed.asScala.exists(_.contains("HELLO, WORLD!")), events.printed.asScala.mkString)
    } finally {
      if (socket != null) socket.close()
      session.close()
      releaseModules()
    }
  }

  /** Releases the fixture's modules from the process-wide host, which other tests of the group open. */
  private def releaseModules(): Unit = {
    val host = TestHost.host
    for (module <- host.modules.all.reverse) {
      val files = host.read(module.sources.asScala.toSeq.map(PsiManager.getInstance(host.project).findFile))
      files.foreach(host.closeSource)
      host.removeModule(module.spec.stableId)
    }
  }
}
