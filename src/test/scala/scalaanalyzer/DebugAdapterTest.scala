package scalaanalyzer

import org.eclipse.lsp4j.debug.*

import scalaanalyzer.build.{DebugAdapter, TestRun}
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

  test("debugs a main class: breakpoint, stack, variables, evaluation, launch options and exit") {
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val options = DebugAdapter.LaunchOptions(Seq("-Dscala.analyzer.check=property"), Map("SCALA_ANALYZER_CHECK" -> "variable"))
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main",
        Seq("first", "second"), options, _ => ())
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
        // The launch configuration's arguments, JVM options and variables reach the program.
        assert(evaluate("args.mkString(\"+\")").contains("first+second"))
        assert(evaluate("System.getProperty(\"scala.analyzer.check\")").contains("property"))
        assert(evaluate("System.getenv(\"SCALA_ANALYZER_CHECK\")").contains("variable"))

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

  test("a line without code is not verified: package clause, blank line, import") {
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main", Seq.empty,
        DebugAdapter.LaunchOptions.Empty, _ => ())
      Using.resource(new DapClient(uri)) { client =>
        val launched = client.start()
        // The adapter knows the lines that class files have code for (line 1 is `package`, 2 is blank, 3 is `import`),
        // which is what the IDE's `canPutAt` refuses; it neither moves such a breakpoint nor reports it as set.
        val verdicts = Seq(1, 2, 3).map(line => line -> client.setBreakpoint(main, line).isVerified)
        assertEquals(verdicts, Seq(1 -> false, 2 -> false, 3 -> false))
        client.breakAt(main, printLine)
        client.run(launched)

        client.resume(client.nextStop())
        client.awaitEnd()
      }
    } finally {
      session.close()
      releaseModules()
    }
  }

  test("hot code replace: a class recompiled during a session is redefined, and the stopped program sees it") {
    val greeting = fixture.resolve("core/src/main/scala/core/Greeting.scala")
    val original = Files.readString(greeting)
    val target = fixture.toFile.toURI.toString + "#app/Compile"
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val uri = session.startMainDebugSession(target, "app.Main", Seq.empty, DebugAdapter.LaunchOptions.Empty, _ => ())
      Using.resource(new DapClient(uri)) { client =>
        import client.{await, server}

        val launched = client.start()
        client.breakAt(main, printLine)
        client.run(launched)
        val stop = client.nextStop()

        def greetingText(): String = {
          val traceArguments = new StackTraceArguments
          traceArguments.setThreadId(stop.getThreadId)
          val top = await("stackTrace", server.stackTrace(traceArguments)).getStackFrames.head
          val arguments = new EvaluateArguments
          arguments.setExpression("core.Greeting(\"you\").text")
          arguments.setFrameId(top.getId)
          arguments.setContext("repl")
          await("evaluate", server.evaluate(arguments)).getResult
        }
        // Needs the expression compiler, as the first test's lambda does.
        assert(greetingText().contains("Hello, you"), greetingText())

        try {
          assert(original.contains("s\"Hello, $name\""), original)
          Files.writeString(greeting, original.replace("s\"Hello, $name\"", "s\"Hello again, $name\""))
          assert(session.compileBuild(Some(target)).succeeded)

          // The host reported the recompiled classes; the client asks for them to be redefined.
          // The case class and its companion object, both compiled from the changed file.
          assertEquals(client.redefineClasses().toSet, Set("core.Greeting", "core.Greeting$"))
          assert(greetingText().contains("Hello again, you"), greetingText())
        } finally {
          Files.writeString(greeting, original)
          session.compileBuild(Some(target))
        }

        client.resume(stop)
        client.awaitEnd()
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
      val uri = session.startTestDebugSession(selection, DebugAdapter.LaunchOptions.Empty, _ => ())
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
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main", Seq.empty,
        DebugAdapter.LaunchOptions.Empty, _ => ())
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

  /** `app.Debugged`, the program of the tests of breakpoint options, exceptions and values. */
  private val debugged = fixture.resolve("app/src/main/scala/app/Debugged.scala")
  /** The line of `total += triple(i)` in the loop of `app.Debugged`. */
  private val loopLine = 8
  /** The lines of `val result = n * 3` and `result` in `app.Debugged.triple`. */
  private val tripleLine = 19
  private val resultLine = 20
  /** The line of the `println` of `app.Debugged`, after the collections are built. */
  private val printTotalLine = 14

  /**
   * Debugs `app.Debugged` with `arguments`: `prepare` sets the breakpoints before the program starts, and `use` follows
   * the program from there.
   */
  private def debuggingDebugged(arguments: String*)(prepare: DapClient => Unit)(use: DapClient => Unit): Unit = {
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Debugged", arguments,
        DebugAdapter.LaunchOptions.Empty, _ => ())
      Using.resource(new DapClient(uri)) { client =>
        val launched = client.start()
        prepare(client)
        client.run(launched)
        use(client)
      }
    } finally {
      session.close()
      releaseModules()
    }
  }

  test("breakpoint options: a condition, a hit count and a log message, as the IDE's breakpoint properties") {
    debuggingDebugged()(_.setVerifiedBreakpoints(debugged, Seq(
      sourceBreakpoint(loopLine, _.setCondition("i == 3")),
      // The adapter's log messages are Scala interpolations: `$n` or `${expr}`.
      sourceBreakpoint(tripleLine, _.setLogMessage("triple of $n")),
      sourceBreakpoint(resultLine, _.setHitCondition("4"))))) { client =>
      val conditional = client.nextStop()
      val loop = client.topFrame(conditional)
      assertEquals(loop.getLine.intValue, loopLine)
      assertEquals(client.evaluate(loop, "i"), "3")
      client.resume(conditional)

      // The fourth time the line runs: the call for 4, the calls for 1 to 3 having passed it.
      val counted = client.nextStop()
      val triple = client.topFrame(counted)
      assertEquals(triple.getLine.intValue, resultLine)
      assertEquals(client.evaluate(triple, "n"), "4")
      client.resume(counted)

      assertEquals(client.nextStopOrEnd().map(Set("terminated", "exited 0")), Right(true))
      // A log message stops nothing and prints its text with the expressions in braces evaluated, each time.
      for (n <- 1 to 5) assert(client.output.contains(s"triple of $n"), client.output)
      assert(client.output.contains("total 45, 6, 2"), client.output)
    }
  }

  test("run without debugging: breakpoints are not set, and the program runs to its end") {
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Debugged", Seq.empty,
        DebugAdapter.LaunchOptions.Empty, _ => ())
      Using.resource(new DapClient(uri)) { client =>
        val launched = client.start(noDebug = true)
        assertEquals(client.setBreakpoints(debugged, Seq(sourceBreakpoint(loopLine))).map(_.isVerified), Seq(false))
        client.breakOnExceptions("caught")
        client.run(launched)
        assertEquals(client.nextStopOrEnd().map(_ => "ended"), Right("ended"))
        assert(client.output.contains("total 45, 6, 2"), client.output)
      }
    } finally {
      session.close()
      releaseModules()
    }
  }

  test("exception breakpoints: a caught exception, then an uncaught one, with what was thrown") {
    debuggingDebugged("throw")(_.breakOnExceptions("caught", "uncaught")) { client =>
      def exception(stop: StoppedEventArguments): (String, String) = {
        assertEquals(stop.getReason, "exception")
        val arguments = new ExceptionInfoArguments
        arguments.setThreadId(stop.getThreadId)
        val info = client.await("exceptionInfo", client.server.exceptionInfo(arguments))
        client.resume(stop)
        info.getExceptionId -> info.getDescription
      }

      // Each stops where it is thrown, in `fail`; the program has no other exception (the Scala library throws none here).
      val thrown = Seq(exception(client.nextStop()), exception(client.nextStop()))
      assertEquals(thrown.map(_._1), Seq.fill(2)("java.lang.IllegalStateException"))
      assert(thrown.head._2.contains("caught") && thrown(1)._2.contains("uncaught"), thrown.toString)
      // The uncaught exception ends the program with an error. The adapter's `exited` event always says 0 (JDI does not
      // know the code); the session prints the code, as the IDE's console does.
      client.awaitEnd()
      assert(client.output.contains("Process finished with exit code 1"), client.output)
    }
  }

  test("values: a collection, set value and drop frame (restart frame)") {
    debuggingDebugged()(_.setVerifiedBreakpoints(debugged, Seq(
      sourceBreakpoint(resultLine, _.setCondition("n == 2")),
      sourceBreakpoint(printTotalLine)))) { client =>
      val first = client.nextStop()
      val frame = client.topFrame(first)
      assertEquals(client.evaluate(frame, "result"), "6")

      // Dropping the frame pops `triple(2)` and steps into its call again: the program stops at the method's first
      // line, then at the same breakpoint again.
      val restart = new RestartFrameArguments
      restart.setFrameId(frame.getId)
      client.await("restartFrame", client.server.restartFrame(restart))
      val stepped = client.nextStop()
      assertEquals(client.topFrame(stepped).getLine.intValue, tripleLine)
      client.resume(stepped)
      val again = client.nextStop()
      val restarted = client.topFrame(again)
      assertEquals(restarted.getLine.intValue, resultLine)
      assertEquals(client.evaluate(restarted, "n"), "2")

      // Set Value: the call answers 40 instead of 6, which the total shows.
      val locals = client.locals(restarted)
      val setValue = new SetVariableArguments
      setValue.setVariablesReference(locals.getVariablesReference)
      setValue.setName("result")
      setValue.setValue("40")
      assertEquals(client.await("setVariable", client.server.setVariable(setValue)).getValue, "40")
      client.resume(again)

      val atPrint = client.nextStop()
      val printFrame = client.topFrame(atPrint)
      assertEquals(printFrame.getLine.intValue, printTotalLine)
      val values = client.variables(client.locals(printFrame).getVariablesReference)
      // A `var` that a lambda captures is a `scala.runtime.IntRef`, which the proxy shows as the value it holds.
      val total = values.getOrElse("total", fail(s"no total in ${values.keySet}"))
      assert(total.getValue.contains("79") && !total.getValue.contains("IntRef"), total.getValue)
      // A collection lists its elements, named by their indexes, as the IDE does; the adapter alone shows its fields.
      val numbers = values.getOrElse("numbers", fail(s"no numbers in ${values.keySet}"))
      val elements = client.variables(numbers.getVariablesReference)
      assertEquals(elements.keySet, Set("0", "1", "2"))
      for ((index, expected) <- Seq("0" -> "1", "1" -> "2", "2" -> "3"))
        assert(elements(index).getValue.contains(expected), elements(index).getValue)
      val names = values.getOrElse("names", fail(s"no names in ${values.keySet}"))
      assertEquals(client.variables(names.getVariablesReference).keySet, Set("0", "1"))
      client.resume(atPrint)
      client.awaitEnd()
      assert(client.output.contains("total 79, 6, 2"), client.output)
    }
  }

  test("smart step into: the line's calls are listed, and the chosen one is stepped into") {
    debuggingDebugged()(_.breakAt(debugged, loopLine)) { client =>
      val stop = client.nextStop()
      val loop = client.topFrame(stop)
      val target = client.stepInTargets(loop).find(_.getLabel.startsWith("triple"))
        .getOrElse(fail(s"no triple among ${client.stepInTargets(loop).map(_.getLabel)}"))

      val inside = client.topFrame(client.stepInTo(stop, target))
      assertEquals(inside.getLine.intValue, tripleLine)
      assert(inside.getName.contains("triple"), inside.getName)
    }
  }

  test("debugs a Java frame: a breakpoint in a Java source of an sbt build, and its stack") {
    val shout = fixture.resolve("core/src/main/java/core/Shout.java")
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      val uri = session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main", Seq.empty,
        DebugAdapter.LaunchOptions.Empty, _ => ())
      Using.resource(new DapClient(uri)) { client =>
        val launched = client.start()
        // The line of `return text.toUpperCase() + "!";`.
        client.breakAt(shout, 7)
        client.run(launched)

        val stop = client.nextStop()
        val top = client.topFrame(stop)
        assertEquals(Path.of(top.getSource.getPath), shout)
        assertEquals(top.getLine.intValue, 7)
        // No local variables: sbt compiles Java without `-g`, so the class has no table of them (Maven and Gradle
        // compile with it, see BloopImportTest).
        client.resume(stop)
        client.awaitEnd()
      }
    } finally {
      session.close()
      releaseModules()
    }
  }

  test("a debug session of a target that does not compile fails with the first error and where it is") {
    val greeting = fixture.resolve("core/src/main/scala/core/Greeting.scala")
    val original = Files.readString(greeting)
    val session = ProjectSession.open(TestHost.host, fixture)
    try {
      Files.writeString(greeting, original.replace("def text: String = s\"Hello, $name\"", "def text: String = 42"))
      val failure = intercept[IllegalStateException] {
        session.startMainDebugSession(fixture.toFile.toURI.toString + "#app/Compile", "app.Main", Seq.empty,
          DebugAdapter.LaunchOptions.Empty, _ => ())
      }
      // The error is in `core`, which `app` depends on: the message says where, as the editor shows no other dialog.
      assert(failure.getMessage.contains("core/src/main/scala/core/Greeting.scala:4: "), failure.getMessage)
    } finally {
      Files.writeString(greeting, original)
      session.compileBuild(None)
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
