package scalaanalyzer

import org.eclipse.lsp4j.debug.*

import scalaanalyzer.build.TestRun
import scalaanalyzer.workspace.ProjectSession

import com.intellij.psi.PsiManager

import java.nio.file.Path

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
