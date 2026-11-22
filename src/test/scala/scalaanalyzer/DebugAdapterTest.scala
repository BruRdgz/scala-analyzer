package scalaanalyzer

import org.eclipse.lsp4j.debug.*

import scalaanalyzer.build.TestRun
import scalaanalyzer.workspace.ProjectSession

import com.intellij.psi.PsiManager

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Debugging a main class and a test suite of the BSP fixture (`fixtures/bsp-project`) through the embedded debug
 * adapter, with a DAP client in the test. Runs in a JVM of its own (see `Test / testGrouping`): it opens the fixture's modules in the host.
 */
class DebugAdapterTest extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private val fixture = Path.of("fixtures/bsp-project").toAbsolutePath
  private val main = fixture.resolve("app/src/main/scala/app/Main.scala")
  /** The line of `println(text)` in `app.Main`, where `text` is assigned. */
  private val printLine = 8
  private val munitSuite = fixture.resolve("core/src/test/scala/core/ExampleMUnit.scala")
  /** The line of `assertEquals(1 + 1, 2)` in the body of the test `adds numbers` of `core.ExampleMUnit`. */
  private val testBodyLine = 4

  test("debugs a main class: breakpoint, stack, variables, evaluation and exit") {
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main", _ => ())
      assertEquals(uri.getScheme, "tcp")
      Using.resource(new DapClient(uri)) { client =>
        import client.{await, server}

        val launched = client.start()
        client.breakAt(main, printLine)
        client.run(launched)

        val stop = client.nextStop()
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

        client.resume(stop)
        val end = client.awaitEnd()
        assert(Set("terminated", "exited 0")(end), end)
        assert(client.output.contains("HELLO, WORLD!"), client.output)
      }
    } finally {
      session.close()
      releaseModules()
    }
  }

  test("debugs a test: the runner of a test run stops at a breakpoint in the test body, then finishes") {
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val selection = TestRun.Selection(fixture.toFile.toURI.toString + "#core/Test", "munit", "core.ExampleMUnit",
        Some("adds numbers"))
      val uri = session.startTestDebugSession(selection, _ => ())
      Using.resource(new DapClient(uri)) { client =>
        val launched = client.start()
        client.breakAt(munitSuite, testBodyLine)
        client.run(launched)

        // The line both registers the test, in the suite's constructor, and is its body, in a lambda: the program stops
        // in each (the IDE would offer one breakpoint per lambda of the line). Each stop is read and resumed until the end.
        val stopped = Iterator.unfold(()) { _ =>
          client.nextStopOrEnd().left.toOption.map { stop =>
            assertEquals(stop.getReason, "breakpoint")
            val traceArguments = new StackTraceArguments
            traceArguments.setThreadId(stop.getThreadId)
            val top = client.await("stackTrace", client.server.stackTrace(traceArguments)).getStackFrames.head
            client.resume(stop)
            ((Path.of(top.getSource.getPath), top.getLine.intValue, top.getName), ())
          }
        }.toSeq
        assert(stopped.forall((path, line, _) => path == munitSuite && line == testBodyLine), stopped.toString)
        // The body's frame is the lambda's (`$anonfun$`), not the constructor's.
        assert(stopped.exists((_, _, frame) => frame.contains("anonfun")), stopped.toString)
        val end = client.awaitEnd()
        assert(Set("terminated", "exited 0")(end), end)
        assert(client.output.contains("adds numbers"), client.output)
      }
    } finally {
      session.close()
      releaseModules()
    }
  }

  test("debugs a library frame: a step into println shows a file with the Scala library's source") {
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main", _ => ())
      Using.resource(new DapClient(uri)) { client =>
        import client.{await, server}

        val launched = client.start()
        client.breakAt(main, printLine)
        client.run(launched)

        val stop = client.stepIn(client.nextStop())
        val traceArguments = new StackTraceArguments
        traceArguments.setThreadId(stop.getThreadId)
        val top = await("stackTrace", server.stackTrace(traceArguments)).getStackFrames.head
        val source = top.getSource
        // The adapter answers no `sourceReference` for a library: the frame's source is a file the host unpacked from the
        // sources jar, which an editor opens as any other.
        assertEquals(source.getSourceReference.intValue, 0)
        assertEquals(source.getName, "Predef.scala")
        val content = Files.readString(Path.of(source.getPath))
        assert(content.contains("def println(x: Any)"), content.take(500))

        client.resume(stop)
        client.awaitEnd()
      }
    } finally {
      session.close()
      releaseModules()
    }
  }

  test("attaches to a running JVM: a breakpoint stops it, and ending the session lets it exit") {
    val session = ProjectSession.open(TestHost.host, fixture)
    val target = fixture.toFile.toURI.toString + "#app/Compile"
    var debuggee: Process = null
    try {
      val environment = session.prepareMainRun(target, _ => ())
      val command = TestRun.mainCommand(TestRun.MainSelection(target, "app.Main"), environment)
      val builder = new ProcessBuilder((command.head +: Agent +: command.tail).asJava)
        .directory(Path.of(java.net.URI.create(environment.item.getWorkingDirectory)).toFile)
        .redirectErrorStream(true)
      builder.environment().putAll(environment.item.getEnvironmentVariables)
      debuggee = builder.start()
      val lines = new java.io.BufferedReader(new java.io.InputStreamReader(debuggee.getInputStream))
      // The agent announces its port before anything else; a JVM that failed to start prints its error instead.
      val Listening = """Listening for transport dt_socket at address: (\d+)""".r
      val port = Iterator.continually(lines.readLine()).takeWhile(_ != null).collectFirst { case Listening(port) => port.toInt }
        .getOrElse(fail("the JVM did not announce its JDWP port"))

      val uri = session.startAttachDebugSession(target, "127.0.0.1", port, _ => ())
      Using.resource(new DapClient(uri)) { client =>
        val attached = client.startAttached("127.0.0.1", port)
        client.breakAt(main, printLine)
        client.run(attached)

        val stop = client.nextStop()
        assertEquals(stop.getReason, "breakpoint")
        val traceArguments = new StackTraceArguments
        traceArguments.setThreadId(stop.getThreadId)
        val top = client.await("stackTrace", client.server.stackTrace(traceArguments)).getStackFrames.head
        assertEquals(Path.of(top.getSource.getPath), main)
        assertEquals(top.getLine.intValue, printLine)

        client.resume(stop)
        val end = client.awaitEnd()
        assert(Set("terminated", "exited 0")(end), end)
      }
      assert(debuggee.waitFor(1, java.util.concurrent.TimeUnit.MINUTES), "the program did not exit")
      assertEquals(debuggee.exitValue, 0)
    } finally {
      if (debuggee != null) debuggee.destroyForcibly()
      session.close()
      releaseModules()
    }
  }

  /** The JDWP agent of a program the test starts itself: it waits for the debugger and picks a free port. */
  private val Agent = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0"

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
