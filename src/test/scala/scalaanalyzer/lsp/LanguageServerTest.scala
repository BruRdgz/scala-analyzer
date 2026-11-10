package scalaanalyzer.lsp

import com.google.gson.{Gson, JsonObject, JsonPrimitive}
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

import scalaanalyzer.TestHost
import scalaanalyzer.language.{ChangeEngine}
import scalaanalyzer.platform.{HostLock, HostLog}
import scalaanalyzer.workspace.{ProjectSession}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, OutputStream}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.{List as JavaList}
import java.util.concurrent.{CompletableFuture, CountDownLatch, ExecutionException, Executors, LinkedBlockingQueue, TimeUnit}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * The language server end to end: a client and the server connected through JSON-RPC streams, on the BSP fixture
 * (`fixtures/bsp-project`, a Scala 2.13 build: errors come from the plugin). Runs in a JVM of its own (see
 * `Test / testGrouping`): it opens the fixture's modules in the host.
 */
class LanguageServerTest extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private val fixture = Path.of("fixtures/bsp-project").toAbsolutePath
  private val main = fixture.resolve("app/src/main/scala/app/Main.scala")
  private val greeting = fixture.resolve("core/src/main/scala/core/Greeting.scala")
  private val counter = fixture.resolve("core/src/main/scala/core/Counter.scala")
  private val greet3 = fixture.resolve("core3/src/main/scala/core3/Greet3.scala")
  private val hello3 = fixture.resolve("core3/src/main/scala/core3/Hello3.scala")
  private val shape = fixture.resolve("core/src/main/scala/core/Shape.scala")

  /** Records what the server sends. */
  private final class RecordingClient extends ClientBase {
    val registrations = new LinkedBlockingQueue[RegistrationParams]()
    override def registerCapability(params: RegistrationParams): CompletableFuture[Void] = {
      registrations.add(params)
      CompletableFuture.completedFuture(null)
    }
    val published = new LinkedBlockingQueue[PublishDiagnosticsParams]()
    val states = new LinkedBlockingQueue[HostProtocol.ServerState]()
    val logged = new LinkedBlockingQueue[MessageParams]()
    val testProcesses = new LinkedBlockingQueue[java.lang.Long]()
    val shown = new LinkedBlockingQueue[MessageParams]()
    override def publishDiagnostics(params: PublishDiagnosticsParams): Unit = published.add(params)
    override def state(state: HostProtocol.ServerState): Unit = states.add(state)
    override def telemetryEvent(o: AnyRef): Unit = ()
    override def showMessage(params: MessageParams): Unit = shown.add(params)
    override def showMessageRequest(params: ShowMessageRequestParams): CompletableFuture[MessageActionItem] =
      CompletableFuture.completedFuture(null)
    override def logMessage(params: MessageParams): Unit = {
      logged.add(params)
      val prefix = "test runner ready: "
      if (params.getMessage.startsWith(prefix)) testProcesses.add(params.getMessage.stripPrefix(prefix).toLong)
    }
    val applied = new LinkedBlockingQueue[ApplyWorkspaceEditParams]()
    /** What the client answers for the `scalaAnalyzer.inlayHints` section. */
    @volatile var inlayHintSettings = new JsonObject
    val hintRefreshes = new LinkedBlockingQueue[java.lang.Boolean]()
    override def configuration(params: ConfigurationParams): CompletableFuture[JavaList[Object]] =
      CompletableFuture.completedFuture(JavaList.of[Object](inlayHintSettings))
    override def refreshInlayHints(): CompletableFuture[Void] = {
      hintRefreshes.add(java.lang.Boolean.TRUE)
      CompletableFuture.completedFuture(null)
    }
    val showed = new LinkedBlockingQueue[ShowDocumentParams]()
    override def applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture[ApplyWorkspaceEditResponse] = {
      applied.add(params)
      CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true))
    }
    override def showDocument(params: ShowDocumentParams): CompletableFuture[ShowDocumentResult] = {
      showed.add(params)
      CompletableFuture.completedFuture(new ShowDocumentResult(true))
    }
  }

  private def connect(client: RecordingClient, implementation: ScalaLanguageServer = new ScalaLanguageServer(TestHost.host))
      : HostProtocol.LanguageServerWithStatus = LocalConnection.connect(client, implementation)

  private val Client = new RecordingClient
  private lazy val implementation = new ScalaLanguageServer(TestHost.host)
  private lazy val server = connect(Client, implementation)

  private lazy val closedServer: Unit = {
    server.shutdown().get(30, TimeUnit.SECONDS)
    server.exit()
  }

  override def afterAll(): Unit = closedServer

  /** Initializes `server` as an editor that, when `appliesEdits`, applies the server's edits and shows its documents. */
  private def initialize(server: HostProtocol.LanguageServerWithStatus, root: Path, appliesEdits: Boolean = true, scoped: Boolean = false): InitializeResult = {
    val params = new InitializeParams
    params.setWorkspaceFolders(java.util.List.of(new WorkspaceFolder(root.toUri.toString, root.getFileName.toString)))
    val workspace = new WorkspaceClientCapabilities
    workspace.setApplyEdit(appliesEdits)
    workspace.setConfiguration(true)
    workspace.setDidChangeConfiguration(new DidChangeConfigurationCapabilities(true))
    workspace.setInlayHint(new InlayHintWorkspaceCapabilities(true))
    val window = new WindowClientCapabilities
    window.setShowDocument(new ShowDocumentCapabilities(appliesEdits))
    val capabilities = new ClientCapabilities
    capabilities.setWorkspace(workspace)
    capabilities.setWindow(window)
    if (scoped) {
      val text = new TextDocumentClientCapabilities
      val completion = new CompletionCapabilities
      val signatures = new SignatureHelpCapabilities
      val formatting = new FormattingCapabilities
      val rangeFormatting = new RangeFormattingCapabilities
      val references = new ReferencesCapabilities
      val actions = new CodeActionCapabilities
      val lenses = new CodeLensCapabilities
      Seq[DynamicRegistrationCapabilities](completion, signatures, formatting, rangeFormatting, references, actions, lenses)
        .foreach(_.setDynamicRegistration(true))
      text.setCompletion(completion)
      text.setSignatureHelp(signatures)
      text.setFormatting(formatting)
      text.setRangeFormatting(rangeFormatting)
      text.setReferences(references)
      text.setCodeAction(actions)
      text.setCodeLens(lenses)
      capabilities.setTextDocument(text)
    }
    params.setCapabilities(capabilities)
    val result = server.initialize(params).get(1, TimeUnit.MINUTES)
    server.initialized(new InitializedParams)
    result
  }

  /** The states the server reports until it reaches `last`. */
  private def statesUntil(client: RecordingClient, last: String): Seq[String] = {
    val seen = Seq.newBuilder[String]
    var state = ""
    while (state != last) {
      val next = client.states.poll(5, TimeUnit.MINUTES)
      if (next == null) fail(s"the server never reported '$last' (reported ${seen.result()})")
      state = next.state
      seen += state
    }
    seen.result()
  }

  private def diagnosticsOf(path: Path, version: Int): Seq[Diagnostic] = {
    val deadline = System.nanoTime() + 5.minutes.toNanos
    var found: Option[PublishDiagnosticsParams] = None
    while (found.isEmpty) {
      val next = Client.published.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
      if (next == null) fail(s"no diagnostics for $path version $version")
      if (next.getUri == path.toUri.toString && next.getVersion == version) found = Some(next)
    }
    found.get.getDiagnostics.asScala.toSeq
  }

  private def errors(diagnostics: Seq[Diagnostic]) =
    diagnostics.filter(_.getSeverity == DiagnosticSeverity.Error).map(_.getMessage)

  private def document(path: Path) = new TextDocumentIdentifier(path.toUri.toString)

  test("ends once when the client's input ends, with the shutdown status") {
    for (shutdown <- Seq(false, true)) {
      val exited = new LinkedBlockingQueue[Int]()
      val implementation = new ScalaLanguageServer(TestHost.host, code => { exited.add(code); () })
      if (shutdown) implementation.shutdown().get(10, TimeUnit.SECONDS)
      Main.listen(implementation, new ByteArrayInputStream(Array.emptyByteArray), new ByteArrayOutputStream)
      assertEquals(Option(exited.poll(10, TimeUnit.SECONDS)), Some(if (shutdown) 0 else 1))
      implementation.exit()
      assert(exited.isEmpty, "exit was called more than once")
    }
  }

  test("ends if transport linkage fails while forwarding startup logs during connection") {
    val exited = new LinkedBlockingQueue[Int]()
    // lsp4j absorbs notification Exceptions; linkage failures propagate and still require server cleanup.
    val failureToLink = new NoClassDefFoundError("missing transport dependency")
    val output = new OutputStream {
      override def write(value: Int): Unit = throw failureToLink
    }
    val implementation = new ScalaLanguageServer(TestHost.host, code => { exited.add(code); () },
      startupLog = Seq(HostLog.Entry(Instant.now(), HostLog.Level.Info, "startup")))

    try {
      // MUnit's intercept handles only NonFatal; catch the injected linkage failure explicitly.
      val failure = try {
        Main.listen(implementation, new ByteArrayInputStream(Array.emptyByteArray), output)
        None
      } catch {
        case error: NoClassDefFoundError => Some(error)
      }
      assertEquals(failure, Some(failureToLink))
      assertEquals(Option(exited.poll(10, TimeUnit.SECONDS)), Some(1))
      implementation.exit()
      assert(exited.isEmpty, "exit was called more than once")
    } finally implementation.exit()
  }

  test("ends when the watched client process exits without an exit notification") {
    val exited = new LinkedBlockingQueue[Int]()
    val implementation = new ScalaLanguageServer(TestHost.host, code => { exited.add(code); () })
    implementation.connect(new RecordingClient)
    val root = Files.createTempDirectory("scala-analyzer-client-exit")
    val process = new ProcessBuilder("sleep", "1").start()
    try {
      val params = new InitializeParams
      params.setRootUri(root.toUri.toString)
      params.setProcessId(Math.toIntExact(process.pid()))
      implementation.initialize(params).get(10, TimeUnit.SECONDS)
      assert(process.waitFor(10, TimeUnit.SECONDS), "the client process did not exit")
      assertEquals(Option(exited.poll(10, TimeUnit.SECONDS)), Some(1))
      implementation.exit()
      assert(exited.isEmpty, "exit was called more than once")
    } finally {
      process.destroyForcibly()
      implementation.exit()
      Files.delete(root)
    }
  }

  // Linux process ids stay below 2^22: this one belongs to no process the server can see.
  test("does not take a client process it cannot see for one that exited") {
    val exited = new LinkedBlockingQueue[Int]()
    val implementation = new ScalaLanguageServer(TestHost.host, code => { exited.add(code); () })
    implementation.connect(new RecordingClient)
    val root = Files.createTempDirectory("scala-analyzer-invisible-client")
    try {
      val params = new InitializeParams
      params.setRootUri(root.toUri.toString)
      params.setProcessId(Int.MaxValue)
      implementation.initialize(params).get(10, TimeUnit.SECONDS)
      assertEquals(Option(exited.poll(1, TimeUnit.SECONDS)), None)
    } finally {
      implementation.exit()
      Files.delete(root)
    }
  }

  test("preserves static Scala capabilities for clients without dynamic registrations") {
    val implementation = new ScalaLanguageServer(TestHost.host)
    val params = new InitializeParams
    params.setRootUri(fixture.toUri.toString)
    val capabilities = implementation.initialize(params).get().getCapabilities
    assertEquals(capabilities.getTextDocumentSync.getLeft, TextDocumentSyncKind.Incremental)
    assert(capabilities.getHoverProvider.getLeft)
    assertEquals(capabilities.getCodeLensProvider.getResolveProvider, java.lang.Boolean.FALSE)
    assert(capabilities.getInlayHintProvider.getRight.getResolveProvider)
    assert(capabilities.getSelectionRangeProvider.getLeft)
    assertEquals(capabilities.getCompletionProvider.getTriggerCharacters.asScala.toSeq, Seq("."))
    assertEquals(capabilities.getCompletionProvider.getResolveProvider, java.lang.Boolean.FALSE)
    assert(capabilities.getDocumentFormattingProvider.getLeft)
    assert(capabilities.getDocumentRangeFormattingProvider.getLeft)
    assert(capabilities.getDefinitionProvider.getLeft)
    assert(capabilities.getTypeDefinitionProvider.getLeft)
    assert(capabilities.getReferencesProvider.getLeft)
    assert(capabilities.getImplementationProvider.getLeft)
    assert(capabilities.getCallHierarchyProvider.getLeft)
    assert(capabilities.getTypeHierarchyProvider.getLeft)
    assert(capabilities.getRenameProvider.getRight.getPrepareProvider)
    assert(capabilities.getWorkspaceSymbolProvider.getLeft)
    assert(capabilities.getDocumentHighlightProvider.getLeft)
    assert(capabilities.getDocumentSymbolProvider.getLeft)
    assert(capabilities.getFoldingRangeProvider.getLeft)
    assert(capabilities.getSemanticTokensProvider.getFull.getLeft)
    implementation.shutdown().get(30, TimeUnit.SECONDS)
    implementation.exit()
  }

  test("scopes Scala providers while sharing supported features with Java") {
    val capabilities = initialize(server, fixture, scoped = true).getCapabilities
    val gson = new com.google.gson.Gson
    def scalaSelector(options: AnyRef): Unit = {
      val selector = gson.toJsonTree(options).getAsJsonObject.getAsJsonArray("documentSelector")
      assertEquals(selector.size(), 1)
      assertEquals(selector.get(0).getAsJsonObject.get("language").getAsString, "scala")
      assertEquals(selector.get(0).getAsJsonObject.get("scheme").getAsString, "file")
    }
    scalaSelector(capabilities.getInlayHintProvider.getRight)
    assert(capabilities.getInlayHintProvider.getRight.getResolveProvider)
    scalaSelector(capabilities.getSelectionRangeProvider.getRight)
    scalaSelector(capabilities.getImplementationProvider.getRight)
    scalaSelector(capabilities.getCallHierarchyProvider.getRight)
    scalaSelector(capabilities.getTypeHierarchyProvider.getRight)
    scalaSelector(capabilities.getRenameProvider.getRight)
    scalaSelector(capabilities.getSemanticTokensProvider)
    assertEquals(capabilities.getCompletionProvider.getTriggerCharacters.asScala.toSeq, Seq("."))
    assertEquals(capabilities.getCompletionProvider.getResolveProvider, java.lang.Boolean.FALSE)
    assertEquals(capabilities.getCodeLensProvider, null)
    assertEquals(capabilities.getSignatureHelpProvider, null)
    assertEquals(capabilities.getDocumentFormattingProvider, null)
    assertEquals(capabilities.getDocumentRangeFormattingProvider, null)
    assertEquals(capabilities.getReferencesProvider, null)
    assertEquals(capabilities.getCodeActionProvider, null)
    assert(capabilities.getHoverProvider.getLeft)
    assert(capabilities.getDefinitionProvider.getLeft)
    assert(capabilities.getTypeDefinitionProvider.getLeft)
    assert(capabilities.getDocumentHighlightProvider.getLeft)
    assert(capabilities.getDocumentSymbolProvider.getLeft)
    assert(capabilities.getFoldingRangeProvider.getLeft)
    val registered = Client.registrations.poll(30, TimeUnit.SECONDS)
    assert(registered != null)
    val methods = registered.getRegistrations.asScala.map { registration =>
      scalaSelector(registration.getRegisterOptions)
      registration.getMethod -> gson.toJsonTree(registration.getRegisterOptions).getAsJsonObject
    }.toMap
    assertEquals(methods.keySet, Set("textDocument/codeLens", "textDocument/signatureHelp",
      "textDocument/formatting", "textDocument/rangeFormatting", "textDocument/references", "textDocument/codeAction"))
    assertEquals(methods("textDocument/codeLens").get("resolveProvider").getAsBoolean, false)
    assertEquals(methods("textDocument/signatureHelp").getAsJsonArray("triggerCharacters").asScala.map(_.getAsString).toSeq,
      Seq("(", "[", ","))
    assertEquals(methods("textDocument/signatureHelp").getAsJsonArray("retriggerCharacters").asScala.map(_.getAsString).toSeq,
      Seq(","))
    assertEquals(methods("textDocument/codeAction").getAsJsonArray("codeActionKinds").asScala.map(_.getAsString).toSeq,
      Seq(CodeActionKind.QuickFix, CodeActionKind.RefactorRewrite, CodeActionKind.RefactorInline,
        CodeActionKind.SourceOrganizeImports))
  }

  test("discovers suite and test code lenses with plugin runner selections and BSP target identities") {
    case class Expected(line: Int, title: String, name: Option[String])
    val cases = Seq(
      ("ExampleFunSuite", "scalatest", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("adds numbers")),
        Expected(4, "Run test", Some("escaped \"name\"")))),
      ("ExampleWordSpec", "scalatest", Seq(
        Expected(2, "Run suite", None),
        Expected(3, "Run test", Some("a calculator should add numbers\na calculator should subtract numbers")),
        Expected(4, "Run test", Some("a calculator should add numbers")),
        Expected(5, "Run test", Some("a calculator should subtract numbers")))),
      ("ExampleFlatSpec", "scalatest", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("a calculator should add numbers")))),
      ("ExampleFunSpec", "scalatest", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("a calculator adds numbers")),
        Expected(4, "Run test", Some("a calculator adds numbers")))),
      ("ExampleFreeSpec", "scalatest", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("a calculator adds numbers")),
        Expected(4, "Run test", Some("a calculator adds numbers")))),
      ("ExamplePropSpec", "scalatest", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("adds numbers")))),
      ("ExampleFeatureSpec", "scalatest", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("Feature: a calculator Scenario: adds numbers")),
        Expected(4, "Run test", Some("Feature: a calculator Scenario: adds numbers")))),
      ("ExampleRefSpec", "scalatest", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("adds numbers")))),
      ("ExampleMUnit", "munit", Seq(
        Expected(2, "Run suite", None), Expected(3, "Run test", Some("adds numbers")))),
      // Exact uTest paths require its IDE-dependent producer; object suite discovery remains available.
      ("ExampleUTest", "utest", Seq(
        Expected(4, "Run suite", None))),
      ("ExampleSpecs2", "specs2", Seq(
        Expected(2, "Run suite", None), Expected(4, "Run test", Some("add numbers")),
        Expected(5, "Run test", Some("subtract numbers")))),
    )
    val target = fixture.toFile.toURI.toString + "#core/Test"
    val actualByFile = cases.map { (name, framework, _) =>
      val path = fixture.resolve(s"core/src/test/scala/core/$name.scala")
      val lenses = server.getTextDocumentService.codeLens(new CodeLensParams(document(path))).get(2, TimeUnit.MINUTES).asScala.toSeq
      val actual = lenses.map { lens =>
        assertEquals(lens.getRange.getEnd.getLine, lens.getRange.getStart.getLine, name)
        val command = lens.getCommand
        assertEquals(command.getCommand, "scalaAnalyzer.runTest", name)
        assertEquals(command.getArguments.size(), 1, name)
        val argument = new Gson().toJsonTree(command.getArguments.get(0)).getAsJsonObject
        assertEquals(argument.get("target").getAsString, target, name)
        assertEquals(argument.get("framework").getAsString, framework, name)
        assertEquals(argument.get("suite").getAsString, s"core.$name", name)
        Expected(lens.getRange.getStart.getLine, command.getTitle, Option(argument.get("testName")).map(_.getAsString))
      }
      name -> actual
    }
    assertEquals(actualByFile, cases.map((name, _, expected) => name -> expected))
    val ordinary = server.getTextDocumentService.codeLens(new CodeLensParams(document(counter))).get(30, TimeUnit.SECONDS)
    assert(ordinary.isEmpty)
  }

  test("discovers run-main code lenses with the class the JVM launches and BSP target identities") {
    case class Expected(line: Int, mainClass: String, target: String)
    val root = fixture.toFile.toURI.toString
    // Main has a main method of its own; Entry3 inherits it from App; a @main method is launched as the class named
    // after it, in the package. An object with a main method gets one lens, not one for the object and the method.
    val cases = Seq(
      main -> Seq(Expected(4, "app.Main", root + "#app/Compile")),
      hello3 -> Seq(
        Expected(2, "core3.hello3", root + "#core3/Compile"),
        Expected(4, "core3.fail3", root + "#core3/Compile"),
        Expected(6, "core3.Entry3", root + "#core3/Compile")),
    )
    val actual = cases.map { (path, _) =>
      val lenses = server.getTextDocumentService.codeLens(new CodeLensParams(document(path))).get(2, TimeUnit.MINUTES).asScala.toSeq
      path -> lenses.map { lens =>
        val command = lens.getCommand
        assertEquals(command.getCommand, "scalaAnalyzer.runMain", path.toString)
        assertEquals(command.getTitle, "Run main", path.toString)
        assertEquals(command.getArguments.size(), 1, path.toString)
        val argument = new Gson().toJsonTree(command.getArguments.get(0)).getAsJsonObject
        Expected(lens.getRange.getStart.getLine, argument.get("mainClass").getAsString, argument.get("target").getAsString)
      }
    }
    assertEquals(actual, cases)
  }

  test("runs test selections through BSP and reports readable outcomes") {
    val target = fixture.toFile.toURI.toString + "#core/Test"
    val cases = Seq(
      ("scalatest", "ExampleFunSuite", Some("escaped \"name\""), "1 passed, 0 failed, 0 ignored", MessageType.Info),
      ("scalatest", "FailingSuite", None, "1 passed, 1 failed, 1 ignored", MessageType.Error),
      ("utest", "ExampleUTest", None, "1 passed, 0 failed, 0 ignored", MessageType.Info),
      ("specs2", "ExampleSpecs2", None, "2 passed, 0 failed, 0 ignored", MessageType.Info),
      ("munit", "ExampleMUnit", Some("adds numbers"), "1 passed, 0 failed, 0 ignored", MessageType.Info),
      ("munit", "FailingMUnit", None, "1 passed, 1 failed, 1 ignored", MessageType.Error),
    )
    cases.foreach { (framework, suite, name, expected, kind) =>
      Client.shown.clear()
      val loggedBefore = Client.logged.size()
      val argument = new JsonObject
      argument.addProperty("target", target)
      argument.addProperty("framework", framework)
      argument.addProperty("suite", s"core.$suite")
      name.foreach(argument.addProperty("testName", _))
      server.getWorkspaceService.executeCommand(new ExecuteCommandParams("scalaAnalyzer.runTest", JavaList.of(argument)))
        .get(3, TimeUnit.MINUTES)
      val summary = Client.shown.poll(10, TimeUnit.SECONDS)
      assert(summary != null, suite)
      assertEquals(summary.getMessage, s"$suite: $expected")
      assertEquals(summary.getType, kind)
      val logs = Client.logged.asScala.drop(loggedBefore).map(_.getMessage).toSeq
      assert(logs.exists(_.contains("passed")), logs.mkString("\n"))
      assert(!logs.exists(_.contains("##teamcity[")), logs.mkString("\n"))
      if (suite.startsWith("Failing")) assert(logs.exists(_.contains("deliberate failure")), logs.mkString("\n"))
    }
  }

  test("runs main classes through BSP and reports how they exited") {
    val root = fixture.toFile.toURI.toString
    val cases = Seq(
      ("#app/Compile", "app.Main", Some("HELLO, WORLD!"), "Main: finished", MessageType.Info),
      ("#core3/Compile", "core3.hello3", Some("Hello from hello3"), "hello3: finished", MessageType.Info),
      ("#core3/Compile", "core3.fail3", None, "fail3: exited with code 3", MessageType.Error),
    )
    cases.foreach { (target, mainClass, output, expected, kind) =>
      Client.shown.clear()
      val loggedBefore = Client.logged.size()
      val argument = new JsonObject
      argument.addProperty("target", root + target)
      argument.addProperty("mainClass", mainClass)
      server.getWorkspaceService.executeCommand(new ExecuteCommandParams("scalaAnalyzer.runMain", JavaList.of(argument)))
        .get(3, TimeUnit.MINUTES)
      val summary = Client.shown.poll(10, TimeUnit.SECONDS)
      assert(summary != null, mainClass)
      assertEquals(summary.getMessage, expected)
      assertEquals(summary.getType, kind)
      val logs = Client.logged.asScala.drop(loggedBefore).map(_.getMessage).toSeq
      output.foreach(line => assert(logs.contains(line), logs.mkString("\n")))
    }
  }

  test("cancels a running test JVM and rejects overlapping runs without blocking document requests") {
    val argument = new JsonObject
    argument.addProperty("target", fixture.toFile.toURI.toString + "#core/Test")
    argument.addProperty("framework", "scalatest")
    argument.addProperty("suite", "core.RunControlSuite")
    val params = new ExecuteCommandParams("scalaAnalyzer.runTest", JavaList.of(argument))
    val running = server.getWorkspaceService.executeCommand(params)
    try {
      val pid = Client.testProcesses.poll(1, TimeUnit.MINUTES)
      assert(pid != null, "test runner did not start")
      val child = ProcessHandle.of(pid).orElseThrow()
      val mainArgument = new JsonObject
      mainArgument.addProperty("target", fixture.toFile.toURI.toString + "#app/Compile")
      mainArgument.addProperty("mainClass", "app.Main")
      // A test run and a main run share the one active run, whichever started first.
      Seq(params, new ExecuteCommandParams("scalaAnalyzer.runMain", JavaList.of(mainArgument))).foreach { overlapping =>
        val error = intercept[ExecutionException] {
          server.getWorkspaceService.executeCommand(overlapping).get(10, TimeUnit.SECONDS)
        }
        assert(error.getCause.getMessage.contains("a run is already in progress"), overlapping.getCommand)
      }
      val lenses = server.getTextDocumentService.codeLens(new CodeLensParams(document(counter))).get(10, TimeUnit.SECONDS)
      assert(lenses.isEmpty)
      running.cancel(true)
      child.onExit().get(10, TimeUnit.SECONDS)
      assert(!child.isAlive)
    } finally running.cancel(true)
  }

  test("rejects invalid runTest and runMain arguments") {
    def command(name: String, members: (String, String)*): ExecuteCommandParams = {
      val argument = new JsonObject
      members.foreach((member, value) => argument.addProperty(member, value))
      new ExecuteCommandParams(name, JavaList.of(argument))
    }

    val invalid = Seq(
      "a test without a target" -> command("scalaAnalyzer.runTest", "suite" -> "core.ExampleFunSuite"),
      "a main without a class" -> command("scalaAnalyzer.runMain", "target" -> "file:///project#app/Compile"),
      "a main with a blank class" -> command("scalaAnalyzer.runMain", "target" -> "file:///project#app/Compile", "mainClass" -> " "),
    )
    invalid.foreach { (name, params) =>
      val error = intercept[ExecutionException] {
        server.getWorkspaceService.executeCommand(params).get(30, TimeUnit.SECONDS)
      }
      error.getCause match {
        case response: ResponseErrorException => assertEquals(response.getResponseError.getCode, ResponseErrorCode.InvalidParams.getValue, name)
        case other => fail(s"$name: unexpected command failure: $other")
      }
    }
  }

  test("rejects malformed completionInsertion arguments") {
    def primitives(values: Any*): JavaList[Object] =
      JavaList.of(values.map {
        case number: Int => new JsonPrimitive(Integer.valueOf(number))
        case text: String => new JsonPrimitive(text)
      }*)

    val malformed = Seq(
      "no arguments" -> null,
      "a line that is not a number" -> primitives("file:///none.scala", "abc", 0, "pre", "lookup", "identity"),
      "a column that is not a number" -> primitives("file:///none.scala", 0, "abc", "pre", "lookup", "identity"),
      "a missing argument" -> primitives("file:///none.scala", 0, 0, "pre", "lookup"),
    )

    for ((description, arguments) <- malformed) {
      val error = intercept[ExecutionException] {
        server.getWorkspaceService.executeCommand(new ExecuteCommandParams("scalaAnalyzer.completeInsertion", arguments))
          .get(30, TimeUnit.SECONDS)
      }
      error.getCause match {
        case response: ResponseErrorException =>
          assertEquals(response.getResponseError.getCode, ResponseErrorCode.InvalidParams.getValue, description)
        case other => fail(s"$description: unexpected command failure: $other")
      }
    }
  }

  test("publishes a document's problems, and new ones after a change") {
    server.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
      new TextDocumentItem(main.toUri.toString, "scala", 1, Files.readString(main))))
    assertEquals(errors(diagnosticsOf(main, 1)), Seq.empty)

    // `val text: String = ...` becomes `val text: Int = ...` (line 6, columns 14-20).
    val change = new TextDocumentContentChangeEvent(new Range(new Position(6, 14), new Position(6, 20)), "Int")
    server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, 2), java.util.List.of(change)))
    assertEquals(errors(diagnosticsOf(main, 2)), Seq("Type mismatch. Required: Int, found: String"))
  }

  test("reports its state, logs what it does and answers its status") {
    assertEquals(statesUntil(Client, "ready"), Seq("importing", "ready"))
    val logged = Client.logged.asScala.toSeq.map(_.getMessage)
    assert(logged.exists(_.contains("ready in")), logged.mkString("\n"))
    assert(logged.exists(_.contains("build imported in")), logged.mkString("\n"))
    val status = server.status().get(1, TimeUnit.MINUTES).asScala
    assertEquals(status("state"), "ready")
    // The fixture's four projects (core, app, core3 and the root), main and test targets each; Gson reads numbers
    // as doubles.
    assertEquals(status("project").asInstanceOf[java.util.Map[String, AnyRef]].get("modules"), java.lang.Double.valueOf(8))
    val requests = status("requests").asInstanceOf[java.util.Map[String, AnyRef]]
    assert(requests.containsKey("didChange") && requests.containsKey("problems"), requests)
  }

  test("lists the build's targets sorted by name, with their dependencies, libraries and sources") {
    val targets = server.buildTargets().get(1, TimeUnit.MINUTES).asScala.toSeq
    val keys = targets.map(target => (target.name, target.id))
    assertEquals(targets.size, 8)
    assertEquals(keys, keys.sorted)
    val core = targets.find(_.name == "core").getOrElse(fail(s"no core target in ${targets.map(_.name)}"))
    val app = targets.find(_.name == "app").getOrElse(fail(s"no app target in ${targets.map(_.name)}"))
    assertEquals(app.dependsOn.asScala.toSeq, Seq(core.id))
    assertEquals(app.scalaVersion, "2.13.18")
    assert(app.libraries.asScala.exists(_.endsWith("scala-library-2.13.18.jar")), app.libraries)
    assert(core.sourceFileCount > 0, core.sourceFileCount)
  }

  test("tells the user, and its status, when the project cannot be opened") {
    val noBuild = Files.createTempDirectory("scala-analyzer-no-build")
    val client = new RecordingClient
    val other = connect(client)
    initialize(other, noBuild)
    assertEquals(statesUntil(client, "failed"), Seq("importing", "failed"))
    val shown = client.shown.poll(1, TimeUnit.MINUTES)
    assertEquals(shown.getType, MessageType.Error)
    assert(shown.getMessage.contains("could not open the project"), shown.getMessage)
    assertEquals(other.status().get(1, TimeUnit.MINUTES).get("state"), "failed")
  }

  // After the change above (`String` became `Int`), `.text` is at line 6, column 50: Greeting.text, declared on line 3
  // of Greeting.scala.
  test("shows the signature of the symbol under the cursor") {
    val hover = server.getTextDocumentService.hover(new HoverParams(document(main), new Position(6, 50))).get(1, TimeUnit.MINUTES)
    assert(hover.getContents.getRight.getValue.contains("def text: String"), hover.getContents.getRight.getValue)
  }

  // The cursor on a declaration's own name (`Greeting` on line 2 of Greeting.scala).
  test("shows the signature of the declaration under the cursor") {
    val hover = server.getTextDocumentService.hover(new HoverParams(document(greeting), new Position(2, 20))).get(1, TimeUnit.MINUTES)
    assert(hover != null, "no hover on a declaration's name")
    assert(hover.getContents.getRight.getValue.contains("case class Greeting"), hover.getContents.getRight.getValue)
  }

  // `println` (line 7 of Main.scala): Predef's ScalaDoc, in scala-library's source jar.
  test("shows the documentation of a library symbol") {
    val hover = server.getTextDocumentService.hover(new HoverParams(document(main), new Position(7, 4))).get(1, TimeUnit.MINUTES)
    val markdown = hover.getContents.getRight.getValue
    assert(markdown.contains("def println(x: Any): Unit"), markdown)
    assert(markdown.contains("Prints out an object to the default output, followed by a newline character."), markdown)
  }

  // `hello` (line 11 of Greet3.scala), in the Scala 3 module: its ScalaDoc is Markdown, which the plugin parses as such.
  test("shows a Scala 3 symbol's Markdown documentation") {
    val hover = server.getTextDocumentService.hover(new HoverParams(document(greet3), new Position(11, 6))).get(1, TimeUnit.MINUTES)
    val markdown = hover.getContents.getRight.getValue
    assert(markdown.contains("Greets `name` **warmly**."), markdown)
    assert(markdown.contains("```scala\nGreet3.hello(\"you\")\n```"), markdown)
    assert(markdown.contains("### Parameters\n\n- `name` – who to greet"), markdown)
  }

  // Inside the literal "Hello, $name": text, not a symbol.
  test("shows nothing on text that is not a symbol") {
    val hover = server.getTextDocumentService.hover(new HoverParams(document(greeting), new Position(3, 24))).get(1, TimeUnit.MINUTES)
    assertEquals(hover, null)
  }

  test("goes to the declaration of the symbol under the cursor") {
    val locations = server.getTextDocumentService.definition(new DefinitionParams(document(main), new Position(6, 50)))
      .get(1, TimeUnit.MINUTES).getLeft.asScala.toSeq
    assertEquals(locations.map(l => (l.getUri, l.getRange.getStart)), Seq((greeting.toUri.toString, new Position(3, 6))))
  }

  // `println` (line 7 of Main.scala): Predef's, in scala-library's source jar, which the build provides.
  test("goes to a library declaration in the library's sources") {
    val locations = server.getTextDocumentService.definition(new DefinitionParams(document(main), new Position(7, 4)))
      .get(1, TimeUnit.MINUTES).getLeft.asScala.toSeq
    assertEquals(locations.size, 1, locations)
    val target = Path.of(java.net.URI.create(locations.head.getUri))
    assert(target.toString.endsWith("scala/Predef.scala"), target)
    val line = Files.readAllLines(target).get(locations.head.getRange.getStart.getLine)
    assert(line.substring(locations.head.getRange.getStart.getCharacter).startsWith("println"), line)
    // The editor may open it; it is not a project source, so the server answers nothing about it.
    val hover = server.getTextDocumentService.hover(new HoverParams(new TextDocumentIdentifier(locations.head.getUri), new Position(0, 0)))
      .get(1, TimeUnit.MINUTES)
    assertEquals(hover, null)
  }

  // `zero` (line 20) returns a Counter, declared on line 8.
  test("goes to the declaration of a symbol's type") {
    val locations = server.getTextDocumentService.typeDefinition(new TypeDefinitionParams(document(counter), new Position(20, 6)))
      .get(1, TimeUnit.MINUTES).getLeft.asScala.toSeq
    assertEquals(locations.map(l => (l.getUri, l.getRange.getStart)), Seq((counter.toUri.toString, new Position(8, 6))))
  }

  // `Greeting("world")` (line 6 of Main.scala, columns 31-39 once the earlier test has changed the line, 34-42 before)
  // is a case class's synthetic `apply`, which leads to the case class on line 2 of Greeting.scala; `object Counter`
  // (line 19) and `class Counter` (line 8) lead to each other, to the keyword that starts the companion.
  test("goes to the plugin's targets of synthetic members and companions") {
    def definition(path: Path, position: Position) = server.getTextDocumentService
      .definition(new DefinitionParams(document(path), position)).get(1, TimeUnit.MINUTES).getLeft.asScala.toSeq
      .map(l => (l.getUri, l.getRange.getStart))
    assertEquals(definition(main, new Position(6, 36)), Seq((greeting.toUri.toString, new Position(2, 17))))
    assertEquals(definition(counter, new Position(19, 2)), Seq((counter.toUri.toString, new Position(8, 0))))
    assertEquals(definition(counter, new Position(8, 2)), Seq((counter.toUri.toString, new Position(19, 0))))
  }

  // `area` of Square (line 7, column 7) implements Shape's (line 3); Square (line 6, column 14) extends Shape (line
  // 2); Shape's own `area` and the trait itself have no super.
  test("finds the supers of a member and of a class") {
    def supers(position: Position) = server.superElements(new TextDocumentPositionParams(document(shape), position))
      .get(1, TimeUnit.MINUTES).asScala.toSeq.map(l => (l.getUri, l.getRange.getStart))
    assertEquals(supers(new Position(7, 7)), Seq((shape.toUri.toString, new Position(3, 6))))
    assertEquals(supers(new Position(6, 14)), Seq((shape.toUri.toString, new Position(2, 6))))
    assertEquals(supers(new Position(3, 7)), Seq.empty)
    assertEquals(supers(new Position(2, 8)), Seq.empty)
  }

  // A one-line range of a file, as (URI, range); found and expected ranges compare in file and line order.
  private def span(path: Path, line: Int, startColumn: Int, endColumn: Int) =
    (path.toUri.toString, new Range(new Position(line, startColumn), new Position(line, endColumn)))

  private def sorted(spans: Seq[(String, Range)]) = spans.sortBy((uri, range) => (uri, range.getStart.getLine))

  private def spans(locations: Seq[Location]) = sorted(locations.map(l => (l.getUri, l.getRange)))

  // `.text` in the app module (line 6, columns 49-53, after the change above) uses Greeting.text of the core module,
  // declared on line 3 of Greeting.scala.
  test("finds the references to a symbol across modules, with or without its declaration") {
    def references(includeDeclaration: Boolean) = spans(server.getTextDocumentService
      .references(new ReferenceParams(document(main), new Position(6, 50), new ReferenceContext(includeDeclaration)))
      .get(1, TimeUnit.MINUTES).asScala.toSeq)
    assertEquals(references(includeDeclaration = false), Seq(span(main, 6, 49, 53)))
    assertEquals(references(includeDeclaration = true), sorted(Seq(span(greeting, 3, 6, 10), span(main, 6, 49, 53))))
  }

  // `.text` again (line 6, columns 49-53), renamed from its use in the app module: the declaration in core changes too.
  test("renames a symbol across modules, leaving the files as they are until the editor reports the edits") {
    val service = server.getTextDocumentService
    assertEquals(service.prepareRename(new PrepareRenameParams(document(main), new Position(6, 50))).get(1, TimeUnit.MINUTES).getFirst,
      new Range(new Position(6, 49), new Position(6, 53)))
    val edit = service.rename(new RenameParams(document(main), new Position(6, 50), "message")).get(1, TimeUnit.MINUTES)
    val changes = edit.getDocumentChanges.asScala.map(_.getLeft).map(c => c.getTextDocument.getUri -> c.getEdits.asScala.toSeq).toMap
    def only(path: Path, line: Int, startColumn: Int, endColumn: Int) =
      Seq(
        new TextEdit(new Range(new Position(line, startColumn), new Position(line, startColumn + 1)), "m"),
        new TextEdit(new Range(new Position(line, startColumn + 2), new Position(line, endColumn)), "ssage"))
    assertEquals(changes, Map(greeting.toUri.toString -> only(greeting, 3, 6, 10), main.toUri.toString -> only(main, 6, 49, 53)))
    // The edits are the editor's to apply: until it reports them, the server's files are the old ones.
    assert(service.hover(new HoverParams(document(main), new Position(6, 50))).get(1, TimeUnit.MINUTES)
      .getContents.getRight.getValue.contains("def text: String"))
    assertEquals(spans(service.references(new ReferenceParams(document(main), new Position(6, 50), new ReferenceContext(false)))
      .get(1, TimeUnit.MINUTES).asScala.toSeq), Seq(span(main, 6, 49, 53)))
  }

  // `println` (line 7 of Main.scala) is the library's; `1text` is no identifier.
  test("refuses to rename a library symbol, and to rename to an invalid name") {
    val service = server.getTextDocumentService
    def failure(request: => CompletableFuture[?]) = intercept[java.util.concurrent.ExecutionException](request.get(1, TimeUnit.MINUTES))
      .getCause.asInstanceOf[org.eclipse.lsp4j.jsonrpc.ResponseErrorException].getResponseError
    val library = failure(service.prepareRename(new PrepareRenameParams(document(main), new Position(7, 4))))
    assertEquals(library.getCode, ResponseErrorCode.RequestFailed.getValue)
    assertEquals(failure(service.rename(new RenameParams(document(main), new Position(7, 4), "print"))).getCode, ResponseErrorCode.RequestFailed.getValue)
    val invalid = failure(service.rename(new RenameParams(document(main), new Position(6, 50), "1text")))
    assert(invalid.getMessage.contains("'1text' is not a valid name"), invalid.getMessage)
  }

  test("finds the implementations of a trait and of its abstract member") {
    def implementations(position: Position) = spans(server.getTextDocumentService
      .implementation(new ImplementationParams(document(shape), position)).get(1, TimeUnit.MINUTES).getLeft.asScala.toSeq)
    // `Shape` (line 2) and its `area` (line 3): Square on lines 6-7 and Circle on lines 10-11.
    assertEquals(implementations(new Position(2, 8)), sorted(Seq(span(shape, 6, 12, 18), span(shape, 10, 12, 18))))
    assertEquals(implementations(new Position(3, 7)), sorted(Seq(span(shape, 7, 6, 10), span(shape, 11, 6, 10))))
  }

  test("finds callers and callees with call-site ranges and rejects non-method call targets") {
    val service = server.getTextDocumentService
    def prepare(path: Path, line: Int, column: Int) = service.prepareCallHierarchy(
      new CallHierarchyPrepareParams(document(path), new Position(line, column))).get(1, TimeUnit.MINUTES).asScala.toSeq
    val text = prepare(greeting, 3, 7).head
    assertEquals(text.getName, "text")
    assertEquals(text.getSelectionRange, new Range(new Position(3, 6), new Position(3, 10)))
    assert(text.getRange.getStart.getCharacter < text.getSelectionRange.getStart.getCharacter)
    val incoming = service.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(text))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
    assertEquals(incoming.map(_.getFrom.getName), Seq("main"))
    assertEquals(incoming.head.getFromRanges.asScala.toSeq, Seq(new Range(new Position(6, 49), new Position(6, 53))))
    val outgoing = service.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(incoming.head.getFrom))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
    val toText = outgoing.filter(_.getTo.getName == "text")
    assertEquals(toText.size, 1)
    assertEquals(toText.head.getFromRanges, incoming.head.getFromRanges)
    val libraryCalls = outgoing.filter(_.getTo.getName == "println")
    assertEquals(libraryCalls.size, 1)
    libraryCalls.foreach { call =>
      assert(Files.exists(Path.of(java.net.URI.create(call.getTo.getUri))))
      assertEquals(call.getFromRanges.asScala.toSeq, Seq(new Range(new Position(7, 4), new Position(7, 11))))
      val callers = service.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(call.getTo))
        .get(1, TimeUnit.MINUTES).asScala.toSeq
      assert(callers.exists(c => c.getFrom.getName == "main" && c.getFrom.getUri == main.toUri.toString), callers)
    }
    assertEquals(prepare(shape, 2, 8), Seq.empty)
    // As in the IDE, a local's reference (`text`, line 8 of Main.scala) selects its method; an object selects nothing.
    assertEquals(prepare(main, 7, 13).map(_.getName), Seq("main"))
    assertEquals(prepare(main, 4, 8), Seq.empty)
    // `main` is declared in `app`, which depends on `core`: the search widens its use scope with module dependencies.
    val entry = prepare(main, 5, 6).head
    assertEquals(service.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(entry)).get(1, TimeUnit.MINUTES).asScala.toSeq, Seq.empty)
    val abstractArea = prepare(shape, 3, 7).head
    val overrides = service.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(abstractArea))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
    assertEquals(overrides.map(_.getTo.getSelectionRange.getStart.getLine).toSet, Set(7, 11))
    assert(overrides.forall(_.getFromRanges.isEmpty))
  }

  test("finds direct type children and follows library hierarchy items") {
    val service = server.getTextDocumentService
    def prepare(line: Int, column: Int) = service.prepareTypeHierarchy(
      new TypeHierarchyPrepareParams(document(shape), new Position(line, column))).get(1, TimeUnit.MINUTES).get(0)
    val parent = prepare(2, 8)
    assertEquals(parent.getName, "Shape")
    assertEquals(parent.getKind, SymbolKind.Interface)
    val children = service.typeHierarchySubtypes(new TypeHierarchySubtypesParams(parent))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
    assertEquals(children.map(_.getName).toSet, Set("Square", "Circle"))
    val supers = service.typeHierarchySupertypes(new TypeHierarchySupertypesParams(prepare(6, 13)))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
    assert(supers.exists(_.getName == "Shape"), supers)
    val library = supers.filter(_.getUri != shape.toUri.toString)
    assert(library.nonEmpty, supers)
    library.foreach { item =>
      assert(Files.exists(Path.of(java.net.URI.create(item.getUri))))
      service.typeHierarchySupertypes(new TypeHierarchySupertypesParams(item)).get(1, TimeUnit.MINUTES)
      val subtypes = service.typeHierarchySubtypes(new TypeHierarchySubtypesParams(item)).get(1, TimeUnit.MINUTES)
      assert(subtypes.asScala.forall(_.getUri.startsWith(fixture.toUri.toString)))
    }
  }

  test("groups Java calls and retains constructor call sites") {
    val service = server.getTextDocumentService
    val javaFile = fixture.resolve("core/src/main/java/core/Shout.java")
    val text = "package core; class Shout { public Shout() {} int value() { return 1; } int caller() { return new Shout().value() + value(); } }"
    def change(value: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(javaFile.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(value))))
    def at(start: Int, length: Int) = new Range(new Position(0, start), new Position(0, start + length))
    try {
      change(text, 170)
      val caller = service.prepareCallHierarchy(new CallHierarchyPrepareParams(document(javaFile), new Position(0, text.indexOf("caller"))))
        .get(1, TimeUnit.MINUTES).get(0)
      val calls = service.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(caller))
        .get(1, TimeUnit.MINUTES).asScala.toSeq
      assertEquals(calls.size, 2)
      assertEquals(calls.find(_.getTo.getName == "value").get.getFromRanges.asScala.toSeq,
        Seq(at(text.indexOf(".value") + 1, 5), at(text.lastIndexOf("value"), 5)))
      val constructor = calls.find(_.getTo.getKind == SymbolKind.Constructor).get
      assertEquals(constructor.getFromRanges.asScala.toSeq, Seq(at(text.indexOf("new Shout"), "new Shout()".length)))
    } finally {
      change(Files.readString(javaFile), 171)
      service.documentSymbol(new DocumentSymbolParams(document(javaFile))).get(1, TimeUnit.MINUTES)
    }
  }

  test("selects type hierarchy targets for classes, constructors, traits and aliases and rejects stale items") {
    val service = server.getTextDocumentService
    val cases = Seq(
      "class Base; class Impl extends <caret>Base" -> "Base",
      "trait Trait; class Base(i: Int); class Impl extends <caret>Base(1) with Trait" -> "Base",
      "trait Trait; class Base(i: Int) { def this(s: String) = this(1) }; class Impl extends <caret>Base(\"\") with Trait" -> "Base",
      "trait Trait; class Impl extends <caret>Trait" -> "Trait",
      "class Base; object Aliases { type Alias = Base }; class Impl extends Aliases.<caret>Alias" -> "Base",
    )
    def change(text: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(shape.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(text))))
    try {
      for (((marked, expected), index) <- cases.zipWithIndex) {
        val cursor = marked.indexOf("<caret>")
        change(marked.replace("<caret>", ""), 180 + index)
        val items = service.prepareTypeHierarchy(new TypeHierarchyPrepareParams(document(shape), new Position(0, cursor)))
          .get(1, TimeUnit.MINUTES)
        assertEquals(items.asScala.map(_.getName).toSeq, Seq(expected), marked)
      }
      val derived = "class Derived extends java.util.ArrayList[String]"
      change(derived, 188)
      val child = service.prepareTypeHierarchy(new TypeHierarchyPrepareParams(document(shape), new Position(0, 7)))
        .get(1, TimeUnit.MINUTES).get(0)
      val base = service.typeHierarchySupertypes(new TypeHierarchySupertypesParams(child))
        .get(1, TimeUnit.MINUTES).asScala.find(_.getName == "ArrayList").get
      val ancestors = service.typeHierarchySupertypes(new TypeHierarchySupertypesParams(base)).get(1, TimeUnit.MINUTES)
      assert(ancestors.asScala.exists(_.getName == "AbstractList"), ancestors)
      change("trait Before; class Child extends Before", 190)
      val stale = service.prepareTypeHierarchy(new TypeHierarchyPrepareParams(document(shape), new Position(0, 7)))
        .get(1, TimeUnit.MINUTES).get(0)
      change("trait After; class Child extends After", 191)
      assert(service.typeHierarchySubtypes(new TypeHierarchySubtypesParams(stale)).get(1, TimeUnit.MINUTES).isEmpty)
    } finally {
      change(Files.readString(shape), 192)
      service.documentSymbol(new DocumentSymbolParams(document(shape))).get(1, TimeUnit.MINUTES)
    }
  }

  test("finds the project's symbols by name, and no library symbol") {
    def symbols(query: String) = server.getWorkspaceService.symbol(new WorkspaceSymbolParams(query))
      .get(1, TimeUnit.MINUTES).getRight.asScala.toSeq
    val counters = symbols("Counter")
    assertEquals(counters.map(c => (c.getName, c.getKind, c.getContainerName)).toSet,
      Set(("Counter", SymbolKind.Class, "core"), ("Counter", SymbolKind.Module, "core")))
    assert(counters.forall(_.getLocation.getLeft.getUri == counter.toUri.toString), counters)
    // Anywhere in the name; `scala.collection.mutable.ListBuffer` and `scala.Option` are the library's.
    assertEquals(symbols("quare").map(_.getName), Seq("Square"))
    assertEquals(symbols("ListBuffer"), Seq.empty)
    assertEquals(symbols("Option"), Seq.empty)
  }

  // `count`, declared on line 9: assigned on lines 12 and 13, read on lines 13 and 16.
  test("highlights a symbol's occurrences in the file, reads and writes") {
    val highlights = server.getTextDocumentService.documentHighlight(new DocumentHighlightParams(document(counter), new Position(9, 14)))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
    val found = highlights.map(h => (h.getRange.getStart.getLine, h.getRange.getStart.getCharacter, h.getKind)).sortBy(h => (h._1, h._2))
    assertEquals(found, Seq(
      (9, 14, DocumentHighlightKind.Write),
      (12, 4, DocumentHighlightKind.Write),
      (13, 4, DocumentHighlightKind.Write),
      (13, 12, DocumentHighlightKind.Read),
      (16, 21, DocumentHighlightKind.Read),
    ))
  }

  // The plugin's usage handlers answer on keywords: `def` marks the method's exit points (line 16: `current`, whose
  // result is `count`) and `class` marks the `object` keyword of its companion (line 19).
  test("highlights a method's exit points from def and a companion's keyword from class") {
    def highlightsAt(line: Int, column: Int) = server.getTextDocumentService
      .documentHighlight(new DocumentHighlightParams(document(counter), new Position(line, column)))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
      .map(h => (h.getRange.getStart.getLine, h.getRange.getStart.getCharacter, h.getKind)).sortBy(h => (h._1, h._2))

    assertEquals(highlightsAt(16, 3), Seq((16, 2, DocumentHighlightKind.Read), (16, 21, DocumentHighlightKind.Read)))
    assertEquals(highlightsAt(8, 2), Seq((8, 0, DocumentHighlightKind.Read), (19, 0, DocumentHighlightKind.Read)))
    // `new Counter(0)` (line 20): the constructor call and the class it constructs.
    val construction = highlightsAt(20, 27).map(h => (h._1, h._2))
    assert(construction.contains((8, 6)) && construction.contains((20, 26)), construction.toString)
  }

  test("hover renders Java declarations and Java targets referenced from Scala") {
    val javaFile = fixture.resolve("core/src/main/java/core/Shout.java")
    val original = Files.readString(javaFile)
    val text = original.replace("    public static String loud",
      "    /** Returns uppercase text. <pre>String example;</pre>\n     * @param text text to convert\n     * @return uppercase text\n     */ public static String loud")
    val service = implementation.getTextDocumentService
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(javaFile.toUri.toString, "java", 100, text)))
    try {
      val declaration = text.split("\n").zipWithIndex.find(_._1.contains("String loud")).get
      val javaHover = service.hover(new HoverParams(document(javaFile),
        new Position(declaration._2, declaration._1.indexOf("loud")))).get(30, TimeUnit.SECONDS)
      assert(javaHover != null)
      val rendered = javaHover.getContents.getRight.getValue
      assert(rendered.startsWith("```java\n"), rendered)
      assert(rendered.contains("loud") && rendered.contains("String"), rendered)
      assert(rendered.contains("Returns uppercase text."), rendered + "\n" + Client.logged.asScala.filter(_.getMessage.contains("no documentation")).mkString("\n"))
      assert(rendered.contains("```java\nString example;\n```"), rendered)
      assert(rendered.contains("### Parameters\n\n- `text`"), rendered)
      assert(rendered.contains("### Returns\n\nuppercase text"), rendered)
      val scalaLine = Files.readString(main).replace("val text: String", "val text: Int").split("\n")(6)
      val scalaHover = service.hover(new HoverParams(document(main), new Position(6, scalaLine.indexOf("loud") + 1)))
        .get(30, TimeUnit.SECONDS)
      assert(scalaHover != null)
      assert(scalaHover.getContents.getRight.getValue.startsWith("```java\n"), scalaHover.toString)
    } finally {
      service.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(javaFile.toUri.toString, 101),
        JavaList.of(new TextDocumentContentChangeEvent(original))))
      service.documentSymbol(new DocumentSymbolParams(document(javaFile))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java getter documentation uses field gists and refreshes after an unsaved edit") {
    val javaFile = fixture.resolve("core/src/main/java/core/Shout.java")
    val original = Files.readString(javaFile)
    val service = server.getTextDocumentService
    val text = "package core; class Shout { /** Current field documentation. */ private int length; /** Updated field documentation. */ private int changedLength; public int getNewLength() { return length; } }"
    def change(value: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(javaFile.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(value))))
    def hover(value: String): String = {
      val result = service.hover(new HoverParams(document(javaFile), new Position(0, value.indexOf("getNewLength"))))
        .get(30, TimeUnit.SECONDS)
      assert(result != null)
      result.getContents.getRight.getValue
    }
    try {
      service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(javaFile.toUri.toString, "java", 160, text)))
      assert(hover(text).contains("Current field documentation."))
      val changed = text.replace("return length;", "return changedLength;")
      change(changed, 161)
      val updated = hover(changed)
      assert(updated.contains("Updated field documentation."), updated)
      assert(!updated.contains("Current field documentation."), updated)
      val abstractGetter = "package core; abstract class Shout { public abstract int getNewLength(); }"
      change(abstractGetter, 162)
      assert(hover(abstractGetter).contains("getNewLength"))
      val failures = Client.logged.asScala.filter(p => p.getMessage.contains("no documentation for") &&
        p.getMessage.contains("getNewLength")).toSeq
      assertEquals(failures, Seq.empty)
    } finally {
      change(original, 163)
      service.documentSymbol(new DocumentSymbolParams(document(javaFile))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java type definitions resolve array components and highlights distinguish writes") {
    val javaFile = fixture.resolve("core/src/main/java/core/Shout.java")
    val text = "package core; class Shout {} class Use { Shout[] values; void set(Shout value) { values = new Shout[]{value}; Shout[] copy = values; } }"
    val service = implementation.getTextDocumentService
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(javaFile.toUri.toString, "java", 130, text)))
    try {
      val assignment = text.indexOf("values =")
      val types = service.typeDefinition(new TypeDefinitionParams(document(javaFile), new Position(0, assignment)))
        .get(30, TimeUnit.SECONDS).getLeft.asScala
      assertEquals(types.map(_.getUri).toSeq, Seq(javaFile.toUri.toString))
      assertEquals(types.head.getRange.getStart, new Position(0, text.indexOf("Shout")))
      val highlights = service.documentHighlight(new DocumentHighlightParams(document(javaFile), new Position(0, assignment)))
        .get(30, TimeUnit.SECONDS).asScala
      val kinds = highlights.map(h => h.getRange.getStart.getCharacter.intValue -> h.getKind).toMap
      assertEquals(kinds(assignment), DocumentHighlightKind.Write)
      assertEquals(kinds(text.lastIndexOf("values")), DocumentHighlightKind.Read)
    } finally {
      service.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(javaFile.toUri.toString, 131),
        JavaList.of(new TextDocumentContentChangeEvent(Files.readString(javaFile)))))
      service.documentSymbol(new DocumentSymbolParams(document(javaFile))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java folding follows PSI blocks, comments and imports") {
    val javaFile = fixture.resolve("core/src/main/java/core/Shout.java")
    val text = """package core;
                 |import java.util.List;
                 |import java.util.Map;
                 |/** A comment
                 | * with a closing brace } inside.
                 | */
                 |class Shout {
                 |  void method() {
                 |    String value = "}";
                 |    if (true) {
                 |      value.length();
                 |    }
                 |  }
                 |}
                 |""".stripMargin
    val service = implementation.getTextDocumentService
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(javaFile.toUri.toString, "java", 120, text)))
    try {
      val ranges = service.foldingRange(new FoldingRangeRequestParams(document(javaFile))).get(30, TimeUnit.SECONDS).asScala
      assertEquals(ranges.map(r => (r.getStartLine.intValue, r.getEndLine.intValue, Option(r.getKind))).toSet,
        Set[(Int, Int, Option[String])]((1, 2, Some(FoldingRangeKind.Imports)), (3, 5, Some(FoldingRangeKind.Comment)),
          (6, 12, None), (7, 11, None), (9, 10, None)))
    } finally {
      service.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(javaFile.toUri.toString, 121),
        JavaList.of(new TextDocumentContentChangeEvent(Files.readString(javaFile)))))
      service.documentSymbol(new DocumentSymbolParams(document(javaFile))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java outline keeps record components and enum constants without synthetic members") {
    val javaFile = fixture.resolve("core/src/main/java/core/Shout.java")
    val service = implementation.getTextDocumentService
    val text = "package core; record JavaRecord(int count) { enum State { READY } int value() { return count; } }"
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(javaFile.toUri.toString, "java", 110, text)))
    try {
      val symbols = service.documentSymbol(new DocumentSymbolParams(document(javaFile))).get(30, TimeUnit.SECONDS)
        .asScala.map(_.getRight)
      assertEquals(symbols.map(_.getName).toSeq, Seq("JavaRecord"))
      val members = symbols.head.getChildren.asScala
      assertEquals(members.map(s => s.getName -> s.getKind).toSet,
        Set("count" -> SymbolKind.Property, "State" -> SymbolKind.Enum, "value" -> SymbolKind.Method))
      assertEquals(members.find(_.getName == "State").get.getChildren.asScala.map(s => s.getName -> s.getKind).toSeq,
        Seq("READY" -> SymbolKind.EnumMember))
    } finally {
      service.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(javaFile.toUri.toString, 111),
        JavaList.of(new TextDocumentContentChangeEvent(Files.readString(javaFile)))))
      service.documentSymbol(new DocumentSymbolParams(document(javaFile))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java semantic diagnostics publish current-buffer type, resolution and unhandled exception errors") {
    val java = fixture.resolve("core/src/main/java/core/Shout.java")
    val service = server.getTextDocumentService
    val broken = "package core; class Shout { String emoji = \"😀\"; int value = \"wrong\"; void use() { missing(); } " +
      "void pause() { Thread.sleep(1); } }"
    def change(text: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(java.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(text))))
    try {
      service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(java.toUri.toString, "java", 140, broken)))
      val problems = diagnosticsOf(java, 140)
      assertEquals(problems.size, 3, problems.toString)
      assert(problems.forall(_.getSource == "java-plugin"))
      assert(problems.exists(_.getMessage.contains("Unhandled exception")), problems.toString)
      val mismatch = problems.find(_.getMessage.contains("Incompatible types")).getOrElse(fail(problems.toString))
      assertEquals(mismatch.getRange.getStart, new Position(0, broken.indexOf("\"wrong\"")))
      assert(problems.exists(_.getMessage.contains("missing")), problems.toString)
      change("package core; class Shout { int value = 1; void use() {} }", 141)
      assertEquals(diagnosticsOf(java, 141), Seq.empty)
    } finally {
      change(Files.readString(java), 142)
      service.documentSymbol(new DocumentSymbolParams(document(java))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java completion returns prefix edits and replays its method insert handler over JSON-RPC") {
    val java = fixture.resolve("core/src/main/java/core/Shout.java")
    val service = server.getTextDocumentService
    val text = "package core; class Shout { static String message(int n) { return \"ok\"; } static void use() { String emoji = \"😀\"; Shout.mes; } }"
    def change(value: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(java.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(value))))
    try {
      service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(java.toUri.toString, "java", 150, text)))
      val offset = text.indexOf("Shout.mes") + "Shout.mes".length
      val list = service.completion(new CompletionParams(document(java), new Position(0, offset)))
        .get(1, TimeUnit.MINUTES).getRight
      val item = list.getItems.asScala.find(_.getFilterText == "message").getOrElse(fail(list.toString))
      assertEquals(item.getKind, CompletionItemKind.Method)
      assertEquals(item.getTextEdit.getLeft.getRange, new Range(new Position(0, offset - 3), new Position(0, offset)))
      assert(item.getDetail.contains("String"), item.toString)
      change(text.replace("Shout.mes;", "Shout.message;"), 151)
      val command = Option(item.getCommand).getOrElse(fail(item.toString))
      server.getWorkspaceService.executeCommand(new ExecuteCommandParams(command.getCommand, command.getArguments))
        .get(1, TimeUnit.MINUTES)
      val applied = Option(Client.applied.poll(30, TimeUnit.SECONDS)).getOrElse(fail("no Java insertion edits"))
      val edit = applied.getEdit.getDocumentChanges.get(0).getLeft
      assertEquals(edit.getTextDocument.getUri, java.toUri.toString)
      assertEquals(edit.getTextDocument.getVersion, Integer.valueOf(151))
      assert(edit.getEdits.asScala.exists(_.getNewText.contains("(")), edit.toString)
      val shown = Option(Client.showed.poll(30, TimeUnit.SECONDS)).getOrElse(fail("no Java insertion caret"))
      assertEquals(shown.getUri, java.toUri.toString)
      val completed = text.replace("Shout.mes;", "Shout.message();")
      assertEquals(shown.getSelection.getStart, new Position(0, completed.indexOf("Shout.message(") + "Shout.message(".length))
    } finally {
      change(Files.readString(java), 152)
      service.documentSymbol(new DocumentSymbolParams(document(java))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java syntax diagnostics follow unsaved buffers and clear after correction") {
    val java = fixture.resolve("core/src/main/java/core/Shout.java")
    val service = implementation.getTextDocumentService
    val broken = """package core; class Shout { String value = "😀" + ; }"""
    def change(text: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(java.toUri.toString, version),
      JavaList.of(new TextDocumentContentChangeEvent(text))))
    try {
      service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(java.toUri.toString, "java", 1, broken)))
      val problems = diagnosticsOf(java, 1)
      assert(problems.nonEmpty)
      assert(problems.forall(_.getSource == "java-plugin"))
      assert(problems.head.getRange.getStart.getCharacter > broken.indexOf("😀"))
      change("""package core; class Shout { String value = "😀"; }""", 2)
      assertEquals(diagnosticsOf(java, 2), Seq.empty)
    } finally {
      change(Files.readString(java), 3)
      service.documentSymbol(new DocumentSymbolParams(document(java))).get(30, TimeUnit.SECONDS)
    }
  }

  test("Java outline and qualified workspace symbols use Java declarations") {
    val java = fixture.resolve("core/src/main/java/core/Shout.java")
    val service = implementation.getTextDocumentService
    val symbols = service.documentSymbol(new DocumentSymbolParams(document(java))).get(30, TimeUnit.SECONDS).asScala.map(_.getRight)
    assertEquals(symbols.map(_.getName).toSeq, Seq("Shout"))
    assertEquals(symbols.head.getKind, SymbolKind.Class)
    assertEquals(symbols.head.getChildren.asScala.map(s => s.getName -> s.getKind).toSet,
      Set("Shout" -> SymbolKind.Constructor, "loud" -> SymbolKind.Method))
    val found = server.getWorkspaceService.symbol(new WorkspaceSymbolParams("core.Shout")).get(30, TimeUnit.SECONDS)
      .getRight.asScala
    assertEquals(found.map(s => (s.getName, s.getContainerName, s.getKind)).toSeq,
      Seq(("Shout", "core", SymbolKind.Class)))
    assertEquals(found.head.getLocation.getLeft.getUri, java.toUri.toString)
  }

  test("outlines a file as the structure view shows it") {
    val symbols = server.getTextDocumentService.documentSymbol(new DocumentSymbolParams(document(counter)))
      .get(1, TimeUnit.MINUTES).asScala.toSeq.map(_.getRight)
    assertEquals(symbols.map(s => (s.getName, s.getKind)), Seq(("Counter", SymbolKind.Class), ("Counter", SymbolKind.Module)))
    val members = symbols.head.getChildren.asScala.map(s => s.getName -> s.getDetail).toMap
    // As the IDE shows it: parameter types only.
    assertEquals(members.get("increment"), Some("(Int): Unit"))
    assert(members.contains("count") && members.contains("current"), members)
    // The class's range is its whole definition, doc comment included (line 5), as LSP asks; its name's range, the name.
    assertEquals(symbols.head.getRange.getStart, new Position(5, 0))
    assertEquals(symbols.head.getSelectionRange, new Range(new Position(8, 6), new Position(8, 13)))
  }

  // Line 13, `    count = count + 1`: the assigned and the read variable; line 20, `  def zero: Counter = new Counter(0)`.
  test("colors identifiers by what they declare or refer to") {
    val data = server.getTextDocumentService.semanticTokensFull(new SemanticTokensParams(document(counter)))
      .get(1, TimeUnit.MINUTES).getData.asScala.map(_.toInt).toSeq
    val legend = SemanticColoring.legend
    // Decoded: (line, column) -> (type, modifiers).
    val tokens = data.grouped(5).foldLeft((Map.empty[(Int, Int), (String, Set[String])], 0, 0)) {
      case ((found, line, column), Seq(deltaLine, deltaColumn, _, tokenType, bits)) =>
        val l = line + deltaLine
        val c = if (deltaLine == 0) column + deltaColumn else deltaColumn
        val modifiers = legend.getTokenModifiers.asScala.zipWithIndex.collect { case (m, i) if (bits & (1 << i)) != 0 => m }.toSet
        (found + ((l, c) -> (legend.getTokenTypes.get(tokenType), modifiers)), l, c)
      case (state, _) => state
    }._1
    assertEquals(tokens.get((13, 4)).map(_._1), Some(SemanticTokenTypes.Variable))
    assertEquals(tokens.get((13, 12)).map(_._1), Some(SemanticTokenTypes.Variable))
    assertEquals(tokens.get((20, 6)), Some(SemanticTokenTypes.Method -> Set(SemanticTokenModifiers.Declaration)))
    assertEquals(tokens.get((20, 12)).map(_._1), Some(SemanticTokenTypes.Class))
  }

  test("inlay hints show inferred method types within the requested range") {
    val text = """package app
                 |object Hints {
                 |  def names = Option(1).toList
                 |  class A { def next: B = ??? }
                 |  class B { def next: C = ??? }
                 |  class C { def next: D = ??? }
                 |  class D { def next: E = ??? }
                 |  class E
                 |  def start: A = ???
                 |  start
                 |    .next
                 |    .next
                 |    .next
                 |    .next
                 |  val range = 1 to 3
                 |}
                 |""".stripMargin
    def change(value: String, version: Int): Unit = server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(value))))
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    try {
      change(text, 38)
      val hints = server.getTextDocumentService.inlayHint(new InlayHintParams(document(main),
        new Range(new Position(0, 0), new Position(16, 0)))).get(1, TimeUnit.MINUTES).asScala.toSeq
      assert(hints.exists(h => h.getPosition == new Position(2, 11) && labelText(h) == ": List[Int]"), hints)
      assert(hints.exists(h => h.getPosition == new Position(10, 9) && labelText(h) == ": B"), hints)
      val selected = server.getTextDocumentService.inlayHint(new InlayHintParams(document(main),
        new Range(new Position(2, 0), new Position(2, 11)))).get(1, TimeUnit.MINUTES)
      assert(selected.isEmpty, selected)
    } finally change(original, 39)
  }

  /** A hint's label as the editor shows it, whether the label is a string or has parts. */
  private def labelText(hint: InlayHint): String =
    if (hint.getLabel.isLeft) hint.getLabel.getLeft else hint.getLabel.getRight.asScala.map(_.getValue).mkString

  test("inlay hint parts locate their declarations once resolved, and a definition's type hint carries its annotation") {
    val text = """package app
                 |object Hints {
                 |  def first = List(core.Greeting("x")).head
                 |}
                 |""".stripMargin
    def change(value: String, version: Int): Unit = server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(value))))
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    try {
      change(text, 380)
      val hints = server.getTextDocumentService.inlayHint(new InlayHintParams(document(main),
        new Range(new Position(0, 0), new Position(4, 0)))).get(1, TimeUnit.MINUTES).asScala.toSeq
      val hint = hints.find(_.getPosition == new Position(2, 11)).getOrElse(fail(s"no hint after `first`: $hints"))
      val parts = hint.getLabel.getRight.asScala.toSeq
      assertEquals(parts.map(_.getValue), Seq(": ", "Greeting"))
      assertEquals(parts.map(_.getLocation), Seq(null, null), "locations wait for the resolve request")
      assertEquals(hint.getTextEdits.asScala.map(e => (e.getRange, e.getNewText)).toSeq,
        Seq((new Range(new Position(2, 11), new Position(2, 11)), ": Greeting")))

      val resolved = server.getTextDocumentService.resolveInlayHint(hint).get(1, TimeUnit.MINUTES)
      val resolvedParts = resolved.getLabel.getRight.asScala.toSeq
      assertEquals(resolvedParts.head.getLocation, null)
      val location = resolvedParts(1).getLocation
      assertEquals(location.getUri, greeting.toUri.toString)
      assertEquals(location.getRange.getStart, new Position(2, 17))
      assert(resolvedParts(1).getTooltip.getLeft.contains("Greeting"), resolvedParts(1).getTooltip)
    } finally change(original, 381)
  }

  test("inlay hints follow the settings the client answers") {
    val text = """package app
                 |object Hints {
                 |  def names = Option(1).toList
                 |  def setup(timeout: Int): Int = timeout
                 |  setup(30)
                 |}
                 |""".stripMargin
    def change(value: String, version: Int): Unit = server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(value))))
    def labels(): Seq[String] = server.getTextDocumentService.inlayHint(new InlayHintParams(document(main),
      new Range(new Position(0, 0), new Position(6, 0)))).get(1, TimeUnit.MINUTES).asScala.map(labelText).toSeq
    /** The client's settings change: the server reads them and asks for the hints again. */
    def settings(answer: JsonObject): Unit = {
      Client.hintRefreshes.clear()
      Client.inlayHintSettings = answer
      server.getWorkspaceService.didChangeConfiguration(new DidChangeConfigurationParams(answer))
      assert(Client.hintRefreshes.poll(1, TimeUnit.MINUTES) != null, "the server did not ask for the hints again")
    }
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    try {
      change(text, 382)
      assert(labels().contains(": List[Int]"), labels())
      assert(!labels().contains("timeout = "), labels())

      val changed = new JsonObject
      changed.addProperty("methodResultTypes", false)
      changed.addProperty("parameterNames", true)
      settings(changed)
      assert(!labels().contains(": List[Int]"), labels())
      assert(labels().contains("timeout = "), labels())

      settings(new JsonObject)
      assert(labels().contains(": List[Int]") && !labels().contains("timeout = "), s"an empty answer restores the defaults: ${labels()}")
    } finally {
      settings(new JsonObject)
      change(original, 383)
    }
  }

  test("signature help shows a call's signatures with the current parameter, as the IDE's parameter info") {
    val text = "package app\nobject Signatures { def greet(name: String, times: Int): String = name; greet(\"x\", ) }"
    def change(value: String, version: Int): Unit = server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version), java.util.List.of(new TextDocumentContentChangeEvent(value))))
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    try {
      change(text, 40)
      val help = server.getTextDocumentService.signatureHelp(new SignatureHelpParams(document(main),
        new Position(1, text.split("\n")(1).indexOf("\", )") + 3))).get(1, TimeUnit.MINUTES)
      val signature = help.getSignatures.get(help.getActiveSignature)
      assertEquals(signature.getLabel, "name: String, times: Int")
      val current = signature.getParameters.get(signature.getActiveParameter).getLabel.getRight
      assertEquals(signature.getLabel.substring(current.getFirst, current.getSecond), "times: Int")
    } finally change(original, 41)
  }

  test("selection ranges follow successive Extend Selection steps") {
    val selected = server.getTextDocumentService.selectionRange(new SelectionRangeParams(document(counter),
      JavaList.of(new Position(16, 23)))).get(1, TimeUnit.MINUTES).asScala.toSeq
    assertEquals(selected.size, 1)
    val word = selected.head
    assertEquals(word.getRange, new Range(new Position(16, 21), new Position(16, 26)))
    assertEquals(word.getParent.getRange, new Range(new Position(16, 2), new Position(16, 26)))
    assertEquals(word.getParent.getParent.getRange, new Range(new Position(16, 0), new Position(17, 0)))
  }

  test("type info shows the type of the identifier under the caret or of the selected expression, and nothing else") {
    val text = """package app
                 |object TypeInfo {
                 |  val answer = 40 + 2
                 |  val shout = "hi".toUpperCase
                 |}
                 |""".stripMargin
    def change(value: String, version: Int): Unit = server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version), JavaList.of(new TextDocumentContentChangeEvent(value))))
    def typeInfo(range: Range): String =
      server.typeInfo(new HostProtocol.TypeInfoParams(document(main), range)).get(1, TimeUnit.MINUTES)
    def caret(line: Int, column: Int) = new Range(new Position(line, column), new Position(line, column))
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    try {
      change(text, 42)
      // `answer`, whose type is inferred.
      assertEquals(typeInfo(caret(2, 7)), "Int")
      // The selected `"hi".toUpperCase`, up to the end of line 3.
      val shout = text.split("\n")(3)
      assertEquals(typeInfo(new Range(new Position(3, shout.indexOf("\"hi\"")), new Position(3, shout.length))), "String")
      // Inside the `val` keyword, and on the space after it.
      assertEquals(typeInfo(caret(2, 3)), null)
      assertEquals(typeInfo(caret(2, 5)), null)
    } finally change(original, 43)
  }

  test("folds imports, doc comments and bodies, leaving closing braces visible") {
    val folds = server.getTextDocumentService.foldingRange(new FoldingRangeRequestParams(document(counter)))
      .get(1, TimeUnit.MINUTES).asScala.toSeq
    val found = folds.map(f => (f.getStartLine.toInt, f.getEndLine.toInt, Option(f.getKind))).sortBy(_._1)
    assertEquals(found, Seq(
      (2, 3, Some(FoldingRangeKind.Imports)),
      (5, 7, Some(FoldingRangeKind.Comment)),
      (8, 16, None),
      (11, 13, None),
      (19, 20, None),
    ))
  }

  test("completes members, locals and keywords through the Scala contributors") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    def change(text: String, version: Int): Unit =
      server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
        new VersionedTextDocumentIdentifier(main.toUri.toString, version),
        java.util.List.of(new TextDocumentContentChangeEvent(text))))
    def complete(text: String, marked: String, version: Int): Seq[CompletionItem] = {
      change(text, version)
      val offset = text.indexOf(marked) + marked.length
      val before = text.substring(0, offset).split("\n", -1)
      val result = server.getTextDocumentService.completion(new CompletionParams(document(main),
        new Position(before.length - 1, before.last.length))).get(1, TimeUnit.MINUTES).getRight
      assert(!result.isIncomplete)
      result.getItems.asScala.toSeq
    }
    try {
      val members = complete(original.replace("Greeting(\"world\").text", "Greeting(\"world\").te"), "Greeting(\"world\").te", 20)
      val text = members.find(_.getFilterText == "text").getOrElse(fail(members.toString))
      assert(text.getDetail.contains("String"), text.toString)
      assertEquals(text.getKind, CompletionItemKind.Method)
      assertEquals(text.getTextEdit.getLeft.getNewText, "text")
      val locals = complete(original.replace("println(text)", "println(tex)"), "println(tex", 21)
      assert(locals.exists(_.getFilterText == "text"), locals.toString)
      val keywords = complete(original.replace("println(text)", "val next = tr"), "val next = tr", 22)
      assert(keywords.exists(i => i.getFilterText == "true" && i.getKind == CompletionItemKind.Keyword), keywords.toString)
    } finally change(original, 23)
  }

  test("a chosen suggestion's command runs its insert handler: the editor gets the import, parentheses and caret") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val text = "package app\nobject ConstructorClient { val c = new Coun }"
    def change(value: String, version: Int): Unit = server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version), java.util.List.of(new TextDocumentContentChangeEvent(value))))
    try {
      change(text, 24)
      val list = server.getTextDocumentService.completion(new CompletionParams(document(main),
        new Position(1, text.split("\n")(1).indexOf("Coun }") + 4))).get(1, TimeUnit.MINUTES).getRight
      val item = list.getItems.asScala.find(_.getFilterText == "Counter").getOrElse(fail(list.toString))
      assertEquals(item.getTextEdit.getLeft.getNewText, "Counter")
      // As the editor does on Enter: the item's edit, then its command.
      change(text.replace("new Coun }", "new Counter }"), 25)
      val command = Option(item.getCommand).getOrElse(fail(s"no command: $item"))
      server.getWorkspaceService.executeCommand(new ExecuteCommandParams(command.getCommand, command.getArguments)).get(1, TimeUnit.MINUTES)
      val applied = Option(Client.applied.poll(1, TimeUnit.MINUTES)).getOrElse(fail("no edit applied"))
      val edit = applied.getEdit.getDocumentChanges.get(0).getLeft
      assertEquals(edit.getTextDocument.getVersion, Integer.valueOf(25))
      val inserted = edit.getEdits.asScala.map(_.getNewText)
      assert(inserted.exists(_.contains("import core.Counter")) && inserted.exists(_.contains("(")), inserted)
      val caret = Option(Client.showed.poll(1, TimeUnit.MINUTES)).getOrElse(fail("caret not moved")).getSelection.getStart
      assertEquals(text.replace("new Coun }", "new Counter() }").split("\n").last.charAt(caret.getCharacter - 1), '(')
    } finally change(original, 26)
  }

  test("code actions return versioned edits and honor the requested kind") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val text = "package app\nobject Action { val string: String = ???; val value: Int = string }"
    val service = server.getTextDocumentService
    def change(value: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version),
      java.util.List.of(new TextDocumentContentChangeEvent(value))))
    try {
      change(text, 27)
      val column = text.split("\n").last.lastIndexOf("string")
      val at = new Position(1, column)
      val context = new CodeActionContext(java.util.List.of())
      context.setOnly(java.util.List.of(CodeActionKind.QuickFix))
      val params = new CodeActionParams(document(main), new Range(at, at), context)
      val actions = service.codeAction(params).get(1, TimeUnit.MINUTES).asScala.map(_.getRight)
      assert(actions.forall(_.getKind == CodeActionKind.QuickFix))
      val action = actions.find(_.getTitle == "Change type 'Int' to 'String'").getOrElse(fail(actions.toString))
      assertEquals(action.getDisabled, null)
      val edit = action.getEdit.getDocumentChanges.get(0).getLeft
      assertEquals(edit.getTextDocument.getVersion, Integer.valueOf(27))
      assertEquals(edit.getTextDocument.getUri, main.toUri.toString)
      val lineStart = text.indexOf('\n') + 1
      val changes = edit.getEdits.asScala.toSeq.map { change =>
        val range = change.getRange
        scalaanalyzer.language.ChangeEngine.Edit(new com.intellij.openapi.util.TextRange(
          lineStart + range.getStart.getCharacter, lineStart + range.getEnd.getCharacter), change.getNewText)
      }
      assertEquals(TestHost.applyEdits(text, changes), text.replace("value: Int", "value: String"))
      assertEquals(service.codeAction(params).get(1, TimeUnit.MINUTES).asScala.map(_.getRight.getTitle).toSeq,
        actions.map(_.getTitle).toSeq)
      val withImport = text.replace("object Action", "import scala.util.Try\nobject Action")
      change(withImport, 28)
      context.setOnly(java.util.List.of(CodeActionKind.Source))
      params.setRange(new Range(new Position(0, 0), new Position(0, 0)))
      val sourceActions = service.codeAction(params).get(1, TimeUnit.MINUTES).asScala.map(_.getRight)
      assertEquals(sourceActions.map(_.getKind).toSeq, Seq(CodeActionKind.SourceOrganizeImports))
      val sourceEdit = sourceActions.head.getEdit.getDocumentChanges.get(0).getLeft
      assertEquals(sourceEdit.getTextDocument.getVersion, Integer.valueOf(28))
      assert(!sourceEdit.getEdits.isEmpty)
      context.setOnly(java.util.List.of(CodeActionKind.SourceOrganizeImports, CodeActionKind.QuickFix))
      val shifted = new Position(2, column)
      params.setRange(new Range(shifted, shifted))
      val combined = service.codeAction(params).get(1, TimeUnit.MINUTES).asScala.map(_.getRight.getKind).toSet
      assertEquals(combined, Set(CodeActionKind.SourceOrganizeImports, CodeActionKind.QuickFix))
    } finally change(original, 29)
  }

  test("inline is offered for a declaration only its file sees, and not for a member used from another module") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val local = "package app\nobject Inlining {\n  def f: Int = {\n    val r = 33\n    r + r\n  }\n}\n"
    val crossModule = "package app\nimport core.*\nobject UsesGreeting { val t: String = Greeting(\"x\").text }\n"
    val service = server.getTextDocumentService
    def change(value: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version),
      java.util.List.of(new TextDocumentContentChangeEvent(value))))
    def inlinings(path: Path, at: Position): Seq[CodeAction] = {
      val context = new CodeActionContext(java.util.List.of())
      context.setOnly(java.util.List.of(CodeActionKind.RefactorInline))
      service.codeAction(new CodeActionParams(document(path), new Range(at, at), context))
        .get(1, TimeUnit.MINUTES).asScala.map(_.getRight).toSeq
    }
    try {
      change(local, 300)
      val actions = inlinings(main, new Position(3, 8))
      assertEquals(actions.map(_.getTitle), Seq("Inline all and remove the declaration"))
      assertEquals(actions.head.getKind, CodeActionKind.RefactorInline)
      assertEquals(actions.head.getDisabled, null)
      val edit = actions.head.getEdit.getDocumentChanges.get(0).getLeft
      assertEquals(edit.getTextDocument.getVersion, Integer.valueOf(300))
      val snapshot = new com.intellij.openapi.editor.impl.DocumentImpl(local, true)
      def offset(p: Position) = snapshot.getLineStartOffset(p.getLine) + p.getCharacter
      val inlined = edit.getEdits.asScala.toSeq.reverse.foldLeft(local)((current, textEdit) =>
        current.substring(0, offset(textEdit.getRange.getStart)) + textEdit.getNewText +
          current.substring(offset(textEdit.getRange.getEnd)))
      assertEquals(inlined, local.replace("    val r = 33\n    r + r", "    33 + 33"))

      // `text` is public and used from `app`: the declaration (line 3 of Greeting.scala) and the call offer nothing.
      assertEquals(inlinings(greeting, new Position(3, 6)), Seq.empty)
      change(crossModule, 301)
      assertEquals(inlinings(main, new Position(2, crossModule.split("\n")(2).indexOf(".text") + 1)), Seq.empty)
    } finally change(original, 302)
  }

  test("inspection diagnostics preserve IDs and tags and fixes return versioned edits") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val text = "package app\nobject InspectionActions { @deprecated(\"Use replacement\", \"1\") def old: Int = 1; val value = old; private val dead = 2; def f(b: Boolean): Boolean = b == true }"
    val service = server.getTextDocumentService
    def change(value: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version),
      java.util.List.of(new TextDocumentContentChangeEvent(value))))
    try {
      change(text, 170)
      val problems = diagnosticsOf(main, 170)
      def named(id: String): Diagnostic = problems.find(p => p.getCode != null && p.getCode.getLeft == id)
        .getOrElse(fail(problems.toString))
      assert(named("ScalaDeprecation").getTags.contains(DiagnosticTag.Deprecated))
      assert(named("ScalaUnusedSymbol").getTags.contains(DiagnosticTag.Unnecessary))
      val simplification = named("SimplifyBoolean")
      assertEquals(simplification.getSeverity, DiagnosticSeverity.Warning)
      val context = new CodeActionContext(java.util.List.of(simplification))
      context.setOnly(java.util.List.of(CodeActionKind.QuickFix))
      val actions = service.codeAction(new CodeActionParams(document(main), simplification.getRange, context))
        .get(1, TimeUnit.MINUTES).asScala.map(_.getRight)
      val action = actions.find(a => a.getDisabled == null && a.getTitle.startsWith("Simplify"))
        .getOrElse(fail(actions.toString))
      // The fix and the suppressions name the problem they are about by the diagnostic the client showed.
      val suppressions = actions.filter(_.getTitle.startsWith("Suppress"))
      assert(suppressions.nonEmpty, actions.toString)
      for (about <- action +: suppressions) assertEquals(about.getDiagnostics.asScala.toSeq, Seq(simplification), about.getTitle)
      val edit = action.getEdit.getDocumentChanges.get(0).getLeft
      assertEquals(edit.getTextDocument.getVersion, Integer.valueOf(170))
      val lineStart = text.indexOf('\n') + 1
      val changes = edit.getEdits.asScala.toSeq.map { change =>
        val range = change.getRange
        ChangeEngine.Edit(new com.intellij.openapi.util.TextRange(
          lineStart + range.getStart.getCharacter, lineStart + range.getEnd.getCharacter), change.getNewText)
      }
      val corrected = TestHost.applyEdits(text, changes)
      assert(!corrected.contains("b == true"), corrected)
      change(corrected, 171)
      assert(!diagnosticsOf(main, 171).exists(p => p.getCode != null && p.getCode.getLeft == "SimplifyBoolean"))
    } finally {
      change(original, 172)
      service.documentSymbol(new DocumentSymbolParams(document(main))).get(1, TimeUnit.MINUTES)
    }
  }

  test("completion answers for its position while the user types on, as editors expect") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    // The next keystroke arrives before the completion has run.
    val pending = scalaanalyzer.platform.HostLock.write {
      val request = implementation.getTextDocumentService.completion(new CompletionParams(document(main), new Position(6, 50)))
      implementation.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
        new VersionedTextDocumentIdentifier(main.toUri.toString, 24),
        java.util.List.of(new TextDocumentContentChangeEvent(original))))
      request
    }
    val answered = pending.get(1, TimeUnit.MINUTES)
    assert(answered.getRight.getItems.asScala.exists(_.getFilterText == "text"), answered.toString)
  }

  test("reads answered together agree with reads answered one by one, and see the changes sent before them") {
    val text = server.getTextDocumentService
    val at = new Position(6, 50)
    val reads: Seq[() => CompletableFuture[?]] = Seq(
      () => text.hover(new HoverParams(document(main), at)),
      () => text.definition(new DefinitionParams(document(main), at)),
      () => text.documentHighlight(new DocumentHighlightParams(document(main), at)),
      () => text.documentSymbol(new DocumentSymbolParams(document(main))),
      () => text.semanticTokensFull(new SemanticTokensParams(document(main))),
      () => text.foldingRange(new FoldingRangeRequestParams(document(main))),
      () => text.inlayHint(new InlayHintParams(document(main), new Range(new Position(0, 0), new Position(20, 0)))),
    )
    def answer(request: CompletableFuture[?]) = String.valueOf(request.get(1, TimeUnit.MINUTES))
    val oneByOne = reads.map(read => answer(read()))
    // Rounds enough for every read to overlap the others on the read threads.
    val together = Seq.fill(8)(reads.map(_())).map(_.map(answer))
    together.foreach(round => assertEquals(round, oneByOne))

    val current = Files.readString(main).replace("val text: String", "val text: Int")
    text.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(main.toUri.toString, 25),
      java.util.List.of(new TextDocumentContentChangeEvent(current + "\nobject Probe\n"))))
    val symbols = text.documentSymbol(new DocumentSymbolParams(document(main))).get(1, TimeUnit.MINUTES)
    text.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(main.toUri.toString, 26),
      java.util.List.of(new TextDocumentContentChangeEvent(current))))
    assert(symbols.asScala.exists(_.getRight.getName == "Probe"), symbols.toString)
  }

  test("formats the fixture buffer and a selected range using the request's tab options") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val text = "package app\nobject FormatFixture {\nval emoji=\"😀\"\nval n=1+2\n}\n"
    def change(value: String, version: Int): Unit =
      server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
        new VersionedTextDocumentIdentifier(main.toUri.toString, version),
        java.util.List.of(new TextDocumentContentChangeEvent(value))))
    def applyEdits(edits: Seq[TextEdit]): String = {
      val snapshot = new com.intellij.openapi.editor.impl.DocumentImpl(text, true)
      def offset(p: Position) = snapshot.getLineStartOffset(p.getLine) + p.getCharacter
      edits.reverse.foldLeft(text)((current, edit) =>
        current.substring(0, offset(edit.getRange.getStart)) + edit.getNewText + current.substring(offset(edit.getRange.getEnd)))
    }
    change(text, 30)
    try {
      val options = new FormattingOptions(4, false)
      val params = new DocumentFormattingParams(document(main), options)
      val edits = server.getTextDocumentService.formatting(params).get(1, TimeUnit.MINUTES).asScala.toSeq
      assertEquals(applyEdits(edits), "package app\n\nobject FormatFixture {\n\tval emoji = \"😀\"\n\tval n = 1 + 2\n}\n")
      assertEquals(server.getTextDocumentService.formatting(params).get(1, TimeUnit.MINUTES).asScala.toSeq, edits)
      val selected = server.getTextDocumentService.rangeFormatting(new DocumentRangeFormattingParams(
        document(main), new FormattingOptions(2, true), new Range(new Position(3, 0), new Position(3, 9))))
        .get(1, TimeUnit.MINUTES).asScala.toSeq
      assertEquals(applyEdits(selected), "package app\nobject FormatFixture {\nval emoji=\"😀\"\n  val n = 1 + 2\n}\n")
    } finally change(original, 31)
  }

  test("a newer edit cancels a queued formatting request") {
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val pending = scalaanalyzer.platform.HostLock.write {
      val request = implementation.getTextDocumentService.formatting(new DocumentFormattingParams(document(main), new FormattingOptions(2, true)))
      implementation.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
        new VersionedTextDocumentIdentifier(main.toUri.toString, 32), java.util.List.of(new TextDocumentContentChangeEvent(original))))
      request
    }
    val error = intercept[java.util.concurrent.ExecutionException](pending.get(1, TimeUnit.MINUTES))
    error.getCause match {
      case cancelled: org.eclipse.lsp4j.jsonrpc.ResponseErrorException =>
        assertEquals(cancelled.getResponseError.getCode, org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestCancelled.getValue)
      case other => fail(s"formatting was not cancelled: $other")
    }
  }

  test("formats with the module scalafmt config, preserves range defaults and reports invalid config") {
    val config = fixture.resolve("app/.scalafmt.conf")
    assert(!Files.exists(config), "the test must not overwrite an existing module config")
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val text = "object ScalafmtBuffer {\nval x=1+2\n}\n"
    val service = server.getTextDocumentService
    def change(value: String, version: Int): Unit = service.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(main.toUri.toString, version),
      java.util.List.of(new TextDocumentContentChangeEvent(value))))
    def applied(edits: Seq[TextEdit]): String = {
      val snapshot = new com.intellij.openapi.editor.impl.DocumentImpl(text, true)
      def offset(p: Position) = snapshot.getLineStartOffset(p.getLine) + p.getCharacter
      edits.reverse.foldLeft(text) { (current, edit) =>
        current.substring(0, offset(edit.getRange.getStart)) + edit.getNewText +
          current.substring(offset(edit.getRange.getEnd))
      }
    }
    Files.copy(Path.of("fixtures/scalafmt/.scalafmt.conf"), config)
    try {
      change(text, 33)
      val params = new DocumentFormattingParams(document(main), new FormattingOptions(2, true))
      val edits = service.formatting(params).get(10, TimeUnit.MINUTES).asScala.toSeq
      assertEquals(applied(edits), "object ScalafmtBuffer {\n    val x = 1 + 2\n}\n")
      assertEquals(service.formatting(params).get(1, TimeUnit.MINUTES).asScala.toSeq, edits)
      val selected = service.rangeFormatting(new DocumentRangeFormattingParams(document(main),
        new FormattingOptions(2, true), new Range(new Position(1, 0), new Position(1, 9))))
        .get(1, TimeUnit.MINUTES).asScala.toSeq
      assertEquals(applied(selected), "object ScalafmtBuffer {\n  val x = 1 + 2\n}\n")
      Files.writeString(config, "version = [\n")
      val error = intercept[java.util.concurrent.ExecutionException](service.formatting(params).get(1, TimeUnit.MINUTES))
      error.getCause match {
        case failed: org.eclipse.lsp4j.jsonrpc.ResponseErrorException =>
          assertEquals(failed.getResponseError.getCode, ResponseErrorCode.RequestFailed.getValue)
        case other => fail(s"scalafmt did not report an LSP error: $other")
      }
      val shown = Client.shown.poll(10, TimeUnit.SECONDS)
      assert(shown != null && shown.getMessage.contains("scalafmt"), String.valueOf(shown))
    } finally {
      Files.delete(config)
      change(original, 34)
    }
  }

  private def filesChanged(changes: (Path, FileChangeType)*): Unit = {
    server.getWorkspaceService.didChangeWatchedFiles(new DidChangeWatchedFilesParams(
      changes.map((path, kind) => new FileEvent(path.toUri.toString, kind)).asJava))
  }

  test("saving the build leaves document requests responsive and groups pending saves") {
    val service = implementation.getTextDocumentService
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    val compiling = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val first = new java.util.concurrent.atomic.AtomicBoolean(true)
    val finished = new LinkedBlockingQueue[String]()
    val threshold = HostLog.threshold
    HostLog.threshold = HostLog.Level.Debug
    val sink = HostLog.addSink { entry =>
      if (entry.message.startsWith("compile on save:") || entry.message.startsWith("slow: compile on save took")) {
        if (first.compareAndSet(true, false)) {
          compiling.countDown()
          val released = release.await(30, TimeUnit.SECONDS)
          assert(released, "the build worker was not released")
        }
        finished.add(Thread.currentThread().getName)
      }
    }
    try {
      service.didSave(new DidSaveTextDocumentParams(document(main)))
      assert(compiling.await(1, TimeUnit.MINUTES), "the build did not compile")
      for (_ <- 1 to 3) service.didSave(new DidSaveTextDocumentParams(document(main)))
      service.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(main.toUri.toString, 40),
        java.util.List.of(new TextDocumentContentChangeEvent("package app\nobject WhileCompiling\n"))))
      val symbols = service.documentSymbol(new DocumentSymbolParams(document(main))).get(5, TimeUnit.SECONDS)
      assertEquals(symbols.asScala.map(_.getRight.getName).toSeq, Seq("WhileCompiling"))
      release.countDown()
      assertEquals(Option(finished.poll(1, TimeUnit.MINUTES)), Some("scala-analyzer-builds"))
      assertEquals(Option(finished.poll(1, TimeUnit.MINUTES)), Some("scala-analyzer-builds"))
      val stats = implementation.status().join().get("requests").asInstanceOf[java.util.Map[String, Object]]
      val compiles = stats.get("compile on save").asInstanceOf[java.util.Map[String, Object]]
      assertEquals(compiles.get("count"), java.lang.Long.valueOf(2))
    } finally {
      release.countDown()
      sink.close()
      HostLog.threshold = threshold
      service.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(main.toUri.toString, 41),
        java.util.List.of(new TextDocumentContentChangeEvent(original))))
      service.documentSymbol(new DocumentSymbolParams(document(main))).get(30, TimeUnit.SECONDS)
    }
  }

  test("compiles the build on request and reports its outcome") {
    val report = server.compile(null).get(5, TimeUnit.MINUTES)
    assert(report.succeeded)
    assertEquals(report.errors, 0)
  }

  test("compiles one build target on request") {
    val targets = server.buildTargets().get(1, TimeUnit.MINUTES).asScala
    val core = targets.find(_.name == "core").getOrElse(fail(s"no core target in ${targets.map(_.name)}"))
    val report = server.compile(new HostProtocol.CompileParams(core.id)).get(5, TimeUnit.MINUTES)
    assert(report.succeeded)
    assertEquals(report.errors, 0)
  }

  test("a compile request for an unknown target fails with InvalidParams") {
    val error = intercept[ExecutionException] {
      server.compile(new HostProtocol.CompileParams(fixture.toFile.toURI.toString + "#missing/Compile")).get(1, TimeUnit.MINUTES)
    }
    error.getCause match {
      case response: ResponseErrorException => assertEquals(response.getResponseError.getCode, ResponseErrorCode.InvalidParams.getValue)
      case other => fail(s"unexpected compile failure: $other")
    }
  }

  test("pending diagnostics for the same version are replaced without starving another source") {
    val service = implementation.getTextDocumentService
    val original = Files.readString(main).replace("val text: String", "val text: Int")
    def open(path: Path, version: Int, text: String): Unit = service.didOpen(new DidOpenTextDocumentParams(
      new TextDocumentItem(path.toUri.toString, "scala", version, text)))
    // This last scheduled source drains earlier work before the diagnostic worker is paused.
    open(greeting, 49, Files.readString(greeting))
    diagnosticsOf(greeting, 49)
    val computing = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val first = new java.util.concurrent.atomic.AtomicBoolean(true)
    val threshold = HostLog.threshold
    HostLog.threshold = HostLog.Level.Debug
    val sink = HostLog.addSink { entry =>
      if (Thread.currentThread().getName == "scala-analyzer-diagnostics" &&
          (entry.message.startsWith("problems:") || entry.message.startsWith("slow: problems took")) &&
          first.compareAndSet(true, false)) {
        computing.countDown()
        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("diagnostics were not released")
      }
    }
    try {
      open(main, 50, original)
      assert(computing.await(30, TimeUnit.SECONDS), "diagnostics did not start")
      for (_ <- 1 to 10) open(main, 50, original)
      open(greeting, 50, Files.readString(greeting))
      service.documentSymbol(new DocumentSymbolParams(document(main))).get(30, TimeUnit.SECONDS)
      release.countDown()
      var mainPublications = 0
      var greetingPublished = false
      while (!greetingPublished) {
        val next = Option(Client.published.poll(30, TimeUnit.SECONDS)).getOrElse(fail("diagnostics did not finish"))
        if (next.getVersion == 50) {
          if (next.getUri == main.toUri.toString) mainPublications += 1
          if (next.getUri == greeting.toUri.toString) greetingPublished = true
        }
      }
      assertEquals(mainPublications, 2, "only the running and latest pending diagnostics should publish")
    } finally {
      release.countDown()
      sink.close()
      HostLog.threshold = threshold
    }
  }

  test("opens a source file created after the import, and lets it go when it is deleted") {
    val added = fixture.resolve("core/src/main/scala/core/Added.scala")
    Files.writeString(added, "package core\n\nobject Added {\n  val n: Int = Counter.zero.current\n}\n")
    try {
      filesChanged(added -> FileChangeType.Created)
      server.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(added.toUri.toString, "scala", 1, Files.readString(added))))
      assertEquals(errors(diagnosticsOf(added, 1)), Seq.empty)
      // `zero` (line 3) is Counter's, on line 20 of Counter.scala.
      val locations = server.getTextDocumentService.definition(new DefinitionParams(document(added), new Position(3, 23)))
        .get(1, TimeUnit.MINUTES).getLeft.asScala.toSeq
      assertEquals(locations.map(l => (l.getUri, l.getRange.getStart)), Seq((counter.toUri.toString, new Position(20, 6))))
    } finally Files.delete(added)
    filesChanged(added -> FileChangeType.Deleted)
    // No longer a source: its problems are cleared, and nothing is answered about it.
    assertEquals(diagnosticsOf(added, 1), Seq.empty)
    assertEquals(server.getTextDocumentService.hover(new HoverParams(document(added), new Position(3, 23))).get(1, TimeUnit.MINUTES), null)
  }

  test("follows a source changed on disk while it is closed, and returns a closed source to its file") {
    val service = server.getTextDocumentService
    val original = Files.readString(hello3)
    def symbols(): Seq[String] = service.documentSymbol(new DocumentSymbolParams(document(hello3))).get(1, TimeUnit.MINUTES)
      .asScala.map(_.getRight.getName).toSeq
    try {
      Files.writeString(hello3, original + "\nobject OnDisk\n")
      filesChanged(hello3 -> FileChangeType.Changed)
      assert(symbols().contains("OnDisk"), symbols())

      service.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(hello3.toUri.toString, "scala", 1, original + "\nobject Unsaved\n")))
      // While the file is open, the editor's buffer is its text, whatever is on disk.
      filesChanged(hello3 -> FileChangeType.Changed)
      assert(symbols().contains("Unsaved"), symbols())

      service.didClose(new DidCloseTextDocumentParams(document(hello3)))
      assertEquals(symbols().filter(Set("OnDisk", "Unsaved")), Seq("OnDisk"))
    } finally {
      Files.writeString(hello3, original)
      filesChanged(hello3 -> FileChangeType.Changed)
    }
    assert(!symbols().contains("OnDisk"), symbols())
  }

  // Main.scala still has the unsaved change of the diagnostics test (`String` became `Int`). Without `-Xsource:3`,
  // its `import core.*` is no wildcard import: the module's new options must reach the plugin.
  test("imports the build again after a change to its definition, keeping unsaved changes") {
    val buildFile = fixture.resolve("build.sbt")
    val original = Files.readString(buildFile)
    val typeMismatch = Seq("Type mismatch. Required: Int, found: String")
    // A change that leaves the text as it is, under a new version: the server handles it after the build change
    // (documents are handled in order), so its problems are those of the imported build.
    def problemsAfterImport(version: Int) = {
      val unchanged = new TextDocumentContentChangeEvent(new Range(new Position(6, 14), new Position(6, 17)), "Int")
      server.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
        new VersionedTextDocumentIdentifier(main.toUri.toString, version), java.util.List.of(unchanged)))
      errors(diagnosticsOf(main, version))
    }
    try {
      Files.writeString(buildFile, original.replace("""scalacOptions += "-Xsource:3"""", """scalacOptions += "-deprecation""""))
      filesChanged(buildFile -> FileChangeType.Changed)
      val withoutSource3 = problemsAfterImport(3)
      assert(withoutSource3.nonEmpty && withoutSource3 != typeMismatch, withoutSource3)
    } finally {
      Files.writeString(buildFile, original)
      filesChanged(buildFile -> FileChangeType.Changed)
    }
    // Back to the original options; the editor's text was kept through both imports.
    assertEquals(problemsAfterImport(4), typeMismatch)
  }

  test("imports the build again on request, reporting its state") {
    Client.states.clear()
    server.reimport().get(5, TimeUnit.MINUTES)
    assertEquals(statesUntil(Client, "ready"), Seq("importing", "ready"))
  }

  /** The start lines of one code's inspection problems in one source of a report, in line order. */
  private def inspected(report: HostProtocol.InspectReport, path: Path, code: String): Seq[Int] = report.files.asScala
    .filter(_.getUri == path.toUri.toString)
    .flatMap(_.getDiagnostics.asScala)
    .filter(_.getCode.getLeft == code)
    .map(_.getRange.getStart.getLine)
    .sorted
    .toSeq

  test("inspects every Scala source of the build, listing the problems of those that have some") {
    val report = server.inspect().get(5, TimeUnit.MINUTES)
    // Counter.scala imports `mutable` (line 2) and `Try` (line 3) without using them.
    assertEquals(inspected(report, counter, "ScalaUnusedImport"), Seq(2, 3))
    assert(!report.files.asScala.exists(_.getUri == greeting.toUri.toString), "Greeting.scala has no problems")
    assert(report.files.asScala.forall(file => !file.getDiagnostics.isEmpty && file.getVersion == null), report.files)
    // Java sources are not inspected.
    val scalaSources = Seq("app", "core", "core3").flatMap { module =>
      Using.resource(Files.walk(fixture.resolve(module).resolve("src")))(_.iterator().asScala.toVector)
    }.filter(_.toString.endsWith(".scala"))
    assertEquals(report.filesInspected, scalaSources.size)
  }

  test("an inspection report describes each inspection that reported, with the plugin's name and group") {
    val report = server.inspect().get(5, TimeUnit.MINUTES)
    val described = report.inspections.asScala.map(description => description.id -> description).toMap
    val codes = report.files.asScala.flatMap(_.getDiagnostics.asScala).map(_.getCode.getLeft).toSet

    assertEquals(described.keySet, codes)
    assertEquals(report.inspections.size, described.size)
    // Unused imports come out of the unused-declaration inspection, which the plugin registers as "Unused declaration".
    val unusedImport = described("ScalaUnusedImport")
    assertEquals(unusedImport.displayName, "Unused declaration")
    assertEquals(unusedImport.groupPath.asScala.toSeq, Seq("Scala", "General"))
  }

  test("an inspection publishes nothing: the client decides how to show its problems") {
    // Diagnostics are published in the order they were scheduled: once Greeting's arrive, the earlier ones have.
    def publishedUntilGreeting(version: Int): Set[String] = {
      server.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(greeting.toUri.toString, "scala", version, Files.readString(greeting))))
      val uris = Set.newBuilder[String]
      var arrived = false
      while (!arrived) {
        val next = Option(Client.published.poll(30, TimeUnit.SECONDS)).getOrElse(fail(s"no diagnostics for Greeting.scala $version"))
        uris += next.getUri
        arrived = next.getUri == greeting.toUri.toString && next.getVersion == version
      }
      uris.result()
    }
    publishedUntilGreeting(60)
    val report = server.inspect().get(5, TimeUnit.MINUTES)
    val withProblems = report.files.asScala.map(_.getUri).toSet
    assert(withProblems.contains(counter.toUri.toString), withProblems)
    assertEquals(publishedUntilGreeting(61).intersect(withProblems), Set.empty[String])
  }

  test("inspects the editor's unsaved text, and the file again once it is closed") {
    val service = server.getTextDocumentService
    val unused = Files.readString(shape).replace("package core\n", "package core\n\nimport scala.util.Try\n")
    // A request on a document waits for the notifications sent before it; an inspection does not.
    def settled(): Unit = service.documentSymbol(new DocumentSymbolParams(document(shape))).get(1, TimeUnit.MINUTES)
    def unusedImports() = inspected(server.inspect().get(5, TimeUnit.MINUTES), shape, "ScalaUnusedImport")

    assertEquals(unusedImports(), Seq.empty[Int])
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(shape.toUri.toString, "scala", 1, unused)))
    settled()
    assertEquals(unusedImports(), Seq(2))
    service.didClose(new DidCloseTextDocumentParams(document(shape)))
    settled()
    assertEquals(unusedImports(), Seq.empty[Int])
  }

  test("a cancelled inspection answers cancellation and leaves the builds thread free") {
    val holding = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val edit = Executors.newSingleThreadExecutor()
    val loggedErrors = HostLog.count(HostLog.Level.Error)
    try {
      // An edit in progress holds the inspection at its first source, as a long run would be held.
      edit.execute { () =>
        HostLock.write {
          holding.countDown()
          release.await(1, TimeUnit.MINUTES)
        }
      }
      assert(holding.await(30, TimeUnit.SECONDS), "the edit did not start")
      server.inspect().cancel(true)
    } finally {
      release.countDown()
      edit.shutdown()
    }
    assert(server.compile(null).get(5, TimeUnit.MINUTES).succeeded)
    assertEquals(HostLog.count(HostLog.Level.Error), loggedErrors)
  }

  test("a client that does not apply the server's edits gets suggestions without commands") {
    closedServer
    releaseModules()
    val plain = connect(new RecordingClient)
    try {
      initialize(plain, fixture, appliesEdits = false)
      val text = "package app\nobject PlainClient { val c = new Coun }"
      plain.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(main.toUri.toString, "scala", 1, text)))
      val list = plain.getTextDocumentService.completion(new CompletionParams(document(main),
        new Position(1, text.split("\n")(1).indexOf("Coun }") + 4))).get(1, TimeUnit.MINUTES).getRight
      val item = list.getItems.asScala.find(_.getFilterText == "Counter").getOrElse(fail(list.toString))
      assertEquals(item.getTextEdit.getLeft.getNewText, "Counter")
      assertEquals(item.getCommand, null)
    } finally {
      // Even when shutdown fails, the fixture's modules are released, or every later test fails to import them.
      try plain.shutdown().get(30, TimeUnit.SECONDS)
      finally {
        plain.exit()
        releaseModules()
      }
    }
  }

  test("a test run's compile reports the sources whose build diagnostics changed, also when it fails") {
    val session = ProjectSession.open(TestHost.host, fixture)
    val target = fixture.toFile.toURI.toString + "#core/Test"
    val original = Files.readString(greeting)
    val reported = scala.collection.mutable.Buffer.empty[Set[Path]]
    try {
      Files.writeString(greeting, original + "\nobject Broken { val x: Int = \"s\" }\n")
      intercept[IllegalStateException](session.prepareTestRun(target, reported += _))
      // `contains`: the compile may report other files too.
      assert(reported.last.contains(greeting), reported.toString)

      Files.writeString(greeting, original)
      session.prepareTestRun(target, reported += _)
      assert(reported.last.contains(greeting), reported.toString)
    } finally {
      Files.writeString(greeting, original)
      session.close()
      releaseModules()
    }
  }

  test("a target's compile counts the errors of the target and its dependencies, not those kept for other targets") {
    val session = ProjectSession.open(TestHost.host, fixture)
    def target(name: String) = Some(fixture.toFile.toURI.toString + s"#$name/Compile")
    val original = Files.readString(greeting)
    try {
      Files.writeString(greeting, original + "\nobject Broken { val x: Int = \"s\" }\n")
      assert(session.compileBuild(target("core")).errors > 0)
      // core's errors stay stored, but core3 does not depend on core; app does.
      assertEquals(session.compileBuild(target("core3")).errors, 0)
      assert(session.compileBuild(target("app")).errors > 0)
    } finally {
      Files.writeString(greeting, original)
      try session.compileBuild(target("core"))
      finally {
        session.close()
        releaseModules()
      }
    }
  }

  test("source checks do not wait for an ongoing diagnostic read") {
    val session = ProjectSession.open(TestHost.host, fixture)
    val executor = Executors.newSingleThreadExecutor(r => new Thread(r, "session-diagnostic-test"))
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val listeners = com.intellij.openapi.util.Disposer.newDisposable()
    HostLock.addReadListener(new com.intellij.openapi.application.ReadActionListener {
      override def readActionStarted(action: Class[?]): Unit =
        if (Thread.currentThread().getName == "session-diagnostic-test") {
          started.countDown()
          val released = release.await(30, TimeUnit.SECONDS)
          assert(released, "the diagnostic read was not released")
        }
    }, listeners)
    val problems = CompletableFuture.supplyAsync(() => session.problems(main), executor)
    try {
      assert(started.await(30, TimeUnit.SECONDS), "diagnostics did not acquire the read lock")
      assert(CompletableFuture.supplyAsync(() => session.isSource(main)).get(5, TimeUnit.SECONDS))
      release.countDown()
      problems.get(30, TimeUnit.SECONDS)
    } finally {
      release.countDown()
      com.intellij.openapi.util.Disposer.dispose(listeners)
      executor.shutdown()
      assert(executor.awaitTermination(30, TimeUnit.SECONDS))
      session.close()
      releaseModules()
    }
  }

  test("reimport failures report recovery and stop requests after a partial host update") {
    val buildFile = fixture.resolve("build.sbt")
    val originalBuild = Files.readString(buildFile)
    val host = TestHost.host
    val client = new RecordingClient
    val implementation = new ScalaLanguageServer(host)
    val protocol = connect(client, implementation)
    var project: Option[ProjectSession] = None

    def nextState(): HostProtocol.ServerState =
      Option(client.states.poll(5, TimeUnit.MINUTES)).getOrElse(fail("the server did not report an import state"))

    try {
      val session = ProjectSession.open(host, fixture)
      project = Some(session)
      val unsaved = Files.readString(main) + "\n// unsaved buffer\n"
      session.change(main, None, unsaved)

      Files.writeString(buildFile, originalBuild + "\nval malformed: Int = \"not an integer\"\n")
      intercept[Exception](implementation.importAgain(session, reload = true))
      assertEquals(nextState().state, "importing")
      val recoverable = nextState()
      assertEquals(recoverable.state, "ready")
      assert(recoverable.message.contains("last imported snapshot remains active"), recoverable.message)
      assert(session.isUsable)
      assertEquals(session.text(main), unsaved)

      Files.writeString(buildFile, originalBuild)
      client.states.clear()
      implementation.importAgain(session, reload = true)
      assertEquals(nextState().state, "importing")
      assertEquals(nextState().state, "ready")
      assertEquals(session.text(main), unsaved)

      val changedBuild = originalBuild.replace(
        "scalacOptions += \"-Xsource:3\"", "scalacOptions += \"-deprecation\"")
      assert(changedBuild != originalBuild, "the build option replacement did not change the fixture")
      Files.writeString(buildFile, changedBuild)
      intercept[RuntimeException] {
        session.importBuild(reload = true, message =>
          if (message.startsWith("module ")) throw new RuntimeException("injected failure after host removal"))
      }
      assert(!session.isUsable)
      assert(session.unavailableMessage.exists(_.contains("restart the language server")))
      intercept[ProjectSession.Unavailable](session.isSource(main))
      intercept[ProjectSession.Unavailable](session.problems(main))
      intercept[ProjectSession.Unavailable](session.text(main))

      client.states.clear()
      intercept[ProjectSession.Unavailable](implementation.importAgain(session, reload = false))
      assertEquals(nextState().state, "importing")
      val failed = nextState()
      assertEquals(failed.state, "failed")
      assert(failed.message.contains("restart the language server"), failed.message)
    } finally {
      Files.writeString(buildFile, originalBuild)
      project.foreach(_.close())
      protocol.shutdown().get(30, TimeUnit.SECONDS)
      protocol.exit()
      releaseModules()
    }
  }

  /** A fresh client session imports the same module names into this JVM's one host. */
  private def releaseModules(): Unit = {
    val host = TestHost.host
    for (module <- host.modules.all.reverse) {
      val files = host.read(module.sources.asScala.toSeq.map(PsiManager.getInstance(host.project).findFile))
      files.foreach(host.closeSource)
      host.removeModule(module.spec.stableId)
    }
  }
}
