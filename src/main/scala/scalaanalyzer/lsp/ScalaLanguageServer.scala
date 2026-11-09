package scalaanalyzer.lsp

import com.google.gson.{Gson, JsonObject, JsonPrimitive}
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.ProcessCanceledException
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither, Either3 as LspEither3, ResponseError, ResponseErrorCode, Tuple}
import org.eclipse.lsp4j.services.*

import scalaanalyzer.{LineColumn, LineRange, ScalaPsiHost}
import scalaanalyzer.build.{BspImport, ScalafmtFormatting, TestRun}
import scalaanalyzer.language.{Completion, CompletionChoice, CompletionFollowUp, FileProblem, Fold, HierarchyItem, HierarchyTarget, InlayHints, InlineHint, OutlineSymbol, ProblemSeverity, Rename, SignatureChoices, SourceSpan, TextChange, WorkspaceSymbolKind}
import scalaanalyzer.platform.{CallLog, HostLock, HostLog}
import scalaanalyzer.workspace.ProjectSession

import java.net.URI
import java.nio.file.{Files, Path}
import java.util.{List as JavaList}
import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Serves LSP requests over the workspace's imported build session.
 *
 * Document notifications and requests run in order on one thread, so a request sees every change sent
 * before it; the project is opened there first, so `initialize` answers at once. Requests that only read a document
 * leave that thread once the changes sent before them are applied, and run together on read threads; a change of the
 * document cancels them. Diagnostics are computed on another thread, a short while after a document's last change: a
 * change cancels a computation in progress (the host's write lock cancels running queries), and a result is published
 * only for the document's current version.
 *
 * What the server does is logged ([[scalaanalyzer.platform.HostLog]]) and sent to the client as log messages; opening and importing the
 * project are reported as progress, and its state (see [[HostProtocol]]) as it changes. Requests are timed: slow ones
 * are logged, and every one at debug level.
 *
 * @param host process-owned PSI host used by the workspace session
 * @param exitProcess ends the server's process with the given code when the client exits or disappears (0 after a
 *                    `shutdown` request, 1 otherwise, as LSP defines); nothing for a server embedded in another process
 * @param logFile     where the log is written, for messages that point to it
 * @param startupLog  what was logged before the client connected (the host's startup), sent to it when it connects
 */
final class ScalaLanguageServer(
  host: ScalaPsiHost,
  exitProcess: Int => Unit = _ => (),
  logFile: Option[Path] = None,
  startupLog: Seq[HostLog.Entry] = Seq.empty,
) extends LspServices.Server {
  /** Fast typing produces a change per keystroke; diagnostics wait for this pause before they are computed. */
  private val DiagnosticsDelayMs = 200L

  /** A request that takes longer is logged as slow: an editor waiting this long looks stuck. */
  private val SlowRequestMs = 1000L

  /** Allows a short BSP shutdown handshake, but never waits for a full build import when the client has gone. */
  private val EndingDeadlineSeconds = 5L

  /** Progress cannot start until the client acknowledges its token; bound that UI handshake separately from import. */
  private val ProgressRegistrationDeadlineSeconds = 30L

  /** Status is a compact report; the full bounded warning/error history remains in HostLog. */
  private val ReportedProblems = 20

  /**
   * Requests that only read run concurrently on this many threads: most of what an editor asks after a keystroke at
   * once, leaving cores to the documents and diagnostics threads.
   */
  private val ReadThreads = 4

  /** The connected client; set by `connect`. */
  @volatile private var client: LanguageClient = scala.compiletime.uninitialized

  /** The subscription that forwards host log entries to the client; closed on exit. */
  private var clientLog: Option[AutoCloseable] = None

  /**
   * Whether the client applies the server's edits and moves the caret on its request: a chosen suggestion's insert
   * handler needs both (see CompleteInsertion).
   */
  @volatile private var appliesEdits = false

  /** Whether the client can show a document, with a selection, when the server asks. */
  @volatile private var showsDocuments = false

  /** The project to open once the client says it is ready (`initialized`). */
  @volatile private var workspace = Option.empty[Path]

  /** Whether the client accepts dynamic registration of watched-file notifications. */
  @volatile private var watchesFiles = false

  /** The Scala-only providers registered once the client is ready; empty when the capabilities are static. */
  @volatile private var languageRegistrations = Seq.empty[Registration]

  /** Whether the client shows work-done progress. */
  @volatile private var reportsProgress = false

  /** Whether the client supports requests to refresh its code lenses. */
  @volatile private var refreshesCodeLenses = false

  /** Whether the client supports requests to refresh its inlay hints. */
  @volatile private var refreshesInlayHints = false

  /** Whether the client answers `workspace/configuration` requests. */
  @volatile private var readsConfiguration = false

  /** Whether the client accepts dynamic registration of configuration-change notifications. */
  @volatile private var watchesConfiguration = false

  /** The latest reported server state. */
  @volatile private var state = new HostProtocol.ServerState("starting", "waiting for the client")

  /** When the server was created, in `System.nanoTime` units, for the uptime report. */
  private val startedAt = System.nanoTime()

  /** Numbers the progress tokens sent to the client. */
  private val progressTokens = new AtomicInteger()

  /** Latencies of the requests served. */
  private val requests = new RequestStats

  /** Whether the client sent `shutdown`; selects the process exit code. */
  @volatile private var shutdownRequested = false

  /** Whether opening a project was queued, so that closing has a session to close. */
  @volatile private var projectRequested = false

  /** Makes `exit` run its cleanup once. */
  private val ending = new AtomicBoolean()

  /** Runs document notifications and requests in order, on one thread. */
  private val documents = Executors.newSingleThreadExecutor(daemon("scala-analyzer-documents"))

  /** Runs the requests that only read a document, concurrently. */
  private val reads = Executors.newFixedThreadPool(ReadThreads, daemon("scala-analyzer-reads"))

  /** Runs build compiles away from the documents thread. */
  private val builds = Executors.newSingleThreadExecutor(daemon("scala-analyzer-builds"))

  /** At most one compile waits behind the running one; every compile includes all Scala targets. */
  private val compileQueued = new AtomicBoolean()

  /** Runs the test selections and main classes started from code lenses. */
  private val testRuns = new TestRun

  /** Delays and runs diagnostic computations. */
  private val diagnostics = new ScheduledThreadPoolExecutor(1, daemon("scala-analyzer-diagnostics"))
  diagnostics.setRemoveOnCancelPolicy(true)

  /** Keeps a pending computation's identity together with its cancellable scheduler handle. */
  private final case class ScheduledDiagnostics(task: Runnable, future: ScheduledFuture[?])

  /** The computation scheduled for each source; guarded by its own monitor. */
  private val pendingDiagnostics = scala.collection.mutable.Map.empty[Path, ScheduledDiagnostics]

  /** Completes with the project session once it is open, or exceptionally if opening failed. */
  private val session = new CompletableFuture[ProjectSession]

  /** Each document's latest version, as the client numbers them; diagnostics of older versions are dropped. */
  private val versions = new ConcurrentHashMap[Path, Integer]()

  /** The project sources the client has open; the others follow their file on disk. */
  private val openDocuments = ConcurrentHashMap.newKeySet[Path]()

  /** Edits counted as they arrive, before their turn on the documents thread (see onEditsRequest). */
  private val editsReceived = new AtomicLong()

  /** Creates a daemon-thread factory for server work that must not keep the process alive. */
  private def daemon(name: String): ThreadFactory = runnable => {
    val thread = new Thread(runnable, name)
    thread.setDaemon(true)
    thread
  }

  /**
   * Attaches the client and begins forwarding host log entries.
   *
   * Connect once before initialization. Buffered startup entries are sent before subscribing to subsequent logs;
   * the subscription is removed during server exit.
   *
   * @param client remote LSP client
   */
  override def connect(client: LanguageClient): Unit = {
    this.client = client
    startupLog.foreach(sendLog)
    clientLog = Some(HostLog.addSink(sendLog))
  }

  /** Converts a host log entry to the matching LSP message type. */
  private def sendLog(entry: HostLog.Entry): Unit = {
    val kind = entry.level match {
      case HostLog.Level.Error => MessageType.Error
      case HostLog.Level.Warn => MessageType.Warning
      case HostLog.Level.Info => MessageType.Info
      case HostLog.Level.Debug => MessageType.Log
    }

    client.logMessage(new MessageParams(kind, entry.toString))
  }

  /** Updates the reported server state and sends the extension notification when the client supports it. */
  private def setState(name: String, message: String): Unit = {
    state = new HostProtocol.ServerState(name, message)
    client match {
      case c: HostProtocol.Client => c.state(state)
      case _ => ()
    }
  }

  /** Runs `body` as a work-done progress the client shows (when it can), with `body`'s reports as its messages. */
  private def withProgress[T](title: String)(body: (String => Unit) => T): T =
    if (!reportsProgress) body(_ => ())
    else {
      val token = LspEither.forLeft[String, Integer](s"scala-analyzer-${progressTokens.incrementAndGet()}")

      /** Sends a progress update after the client has acknowledged the token. */
      def send(value: WorkDoneProgressNotification): Unit =
        client.notifyProgress(new ProgressParams(token, LspEither.forLeft[WorkDoneProgressNotification, Object](value)))

      // The client must know the token before progress is reported on it.
      client.createProgress(new WorkDoneProgressCreateParams(token)).get(ProgressRegistrationDeadlineSeconds, TimeUnit.SECONDS)
      val begin = new WorkDoneProgressBegin
      begin.setTitle(title)
      send(begin)
      try body { message =>
        val report = new WorkDoneProgressReport
        report.setMessage(message)
        send(report)
      }
      finally send(new WorkDoneProgressEnd)
    }

  /** Asks the client to request lenses again: the modules, and so the test suites, may have changed. */
  private def refreshCodeLenses(): Unit =
    if (refreshesCodeLenses) client.refreshCodeLenses().exceptionally { error =>
      HostLog.warn("could not refresh code lenses", error)
      null
    }

  /** Asks the client to request hints again: their settings changed. */
  private def refreshInlayHints(): Unit =
    if (refreshesInlayHints) client.refreshInlayHints().exceptionally { error =>
      HostLog.warn("could not refresh inlay hints", error)
      null
    }

  /**
   * Reads the client's `scalaAnalyzer.inlayHints` settings and applies them to the host, then refreshes the hints.
   *
   * Does not wait for the client's answer. The settings are applied on the documents thread, between the requests
   * the client sent before and after its answer.
   */
  private def readInlayHintSettings(): Unit = {
    val item = new ConfigurationItem
    item.setSection(InlayHintSettings)

    client.configuration(new ConfigurationParams(JavaList.of(item)))
      .thenAcceptAsync(answers => {
        host.configureInlayHints(inlayHintToggles(answers.asScala.headOption.orNull))
        refreshInlayHints()
      }, documents)
      .exceptionally { error =>
        HostLog.warn("could not read the inlay hint settings", error)
        null
      }
  }

  /**
   * The hint kinds a `scalaAnalyzer.inlayHints` settings object asks for.
   *
   * @param settings the client's answer for the section; anything but an object stands for no settings
   * @return the section's toggles, each defaulting to the IDE's when the section lacks it or does not hold a boolean
   */
  private def inlayHintToggles(settings: AnyRef): InlayHints.Toggles = {
    val section = new Gson().toJsonTree(settings) match {
      case json: JsonObject => json
      case _ => new JsonObject
    }
    val defaults = InlayHints.Toggles.Default

    /** The boolean the section gives for `name`, or `default` when it gives none. */
    def toggle(name: String, default: Boolean): Boolean = Option(section.get(name)).collect {
      case value: JsonPrimitive if value.isBoolean => value.getAsBoolean
    }.getOrElse(default)

    InlayHints.Toggles(
      methodResultTypes = toggle("methodResultTypes", defaults.methodResultTypes),
      memberVariableTypes = toggle("memberVariableTypes", defaults.memberVariableTypes),
      localVariableTypes = toggle("localVariableTypes", defaults.localVariableTypes),
      obviousTypes = toggle("obviousTypes", defaults.obviousTypes),
      parameterNames = toggle("parameterNames", defaults.parameterNames),
      methodChains = toggle("methodChains", defaults.methodChains),
      implicits = toggle("implicits", defaults.implicits),
    )
  }

  /**
   * Imports the build again (see [[ProjectSession.importBuild]]), as progress and server state.
   *
   * Blocks the calling thread until the import ends. The state is "importing" meanwhile and "ready" afterwards, with
   * code lenses refreshed when the client supports it. A failure is rethrown after the state is set: "failed" when the session is no longer
   * usable, otherwise "ready" with a message saying whether the import had returned (only progress reporting failed)
   * or the last imported snapshot remains active.
   *
   * @param s session whose build is imported
   * @param reload `true` to reload the BSP workspace first, as when the build definition changed; `false` when only
   *               source files were created or deleted
   */
  private[lsp] def importAgain(s: ProjectSession, reload: Boolean): Unit = {
    var importReturned = false

    setState("importing", "importing the build again")
    try {
      withProgress("scala-analyzer: importing the build") { progress =>
        s.importBuild(reload, progress)
        importReturned = true
      }
      val snapshot = s.snapshot
      setState("ready", s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files")
      refreshCodeLenses()
    } catch {
      case error: Throwable =>
        val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        if (!s.isUsable) setState("failed", s.unavailableMessage.getOrElse(detail))
        else if (importReturned) {
          val snapshot = s.snapshot
          setState("ready", s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files; build imported, but progress reporting failed: $detail")
        }
        else
          setState("ready", s"reimport failed before host updates; the last imported snapshot remains active and can be retried: $detail")
        throw error
    }
  }

  /** Imports the build again (see `importAgain`), then schedules diagnostics for every tracked document. */
  private def importAndScheduleDiagnostics(s: ProjectSession, reload: Boolean): Unit = {
    importAgain(s, reload)
    versions.keySet.forEach(scheduleDiagnostics)
  }

  /**
   * Negotiates client features and advertises the server's LSP capabilities.
   *
   * Uses the first workspace folder, falling back to the root URI. Build import begins only after `initialized`,
   * so this response does not wait for the project to open. Clients supporting all required dynamic registrations
   * receive Scala-only providers alongside shared Java features; other clients retain static Scala capabilities
   * and should select only Scala documents.
   *
   * @param params client capabilities, workspace root and optional client process information
   * @return an already completed future containing server capabilities and identity
   * @throws IllegalArgumentException when neither a workspace folder nor a root URI is supplied
   */
  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    val offered = Option(params.getCapabilities)
    val workspaceCapabilities = offered.flatMap(c => Option(c.getWorkspace))
    val windowCapabilities = offered.flatMap(c => Option(c.getWindow))
    val textCapabilities = offered.flatMap(c => Option(c.getTextDocument))
    val scopesScalaFeatures = textCapabilities.exists { c =>
      Seq[DynamicRegistrationCapabilities](c.getSignatureHelp, c.getFormatting,
        c.getRangeFormatting, c.getReferences, c.getCodeAction, c.getCodeLens).forall { feature =>
        Option(feature).flatMap(f => Option(f.getDynamicRegistration)).exists(_.booleanValue)
      }
    }

    /** Selects only Scala documents on the `file` scheme in the registration options, and returns them. */
    def scalaOnly[T <: AbstractTextDocumentRegistrationAndWorkDoneProgressOptions](options: T): T = {
      options.setDocumentSelector(java.util.List.of(new DocumentFilter("scala", "file", null)))
      options
    }

    watchesFiles = workspaceCapabilities.flatMap(w => Option(w.getDidChangeWatchedFiles))
      .flatMap(w => Option(w.getDynamicRegistration)).exists(_.booleanValue)
    reportsProgress = windowCapabilities.flatMap(w => Option(w.getWorkDoneProgress)).exists(_.booleanValue)
    refreshesCodeLenses = workspaceCapabilities.flatMap(w => Option(w.getCodeLens))
      .flatMap(c => Option(c.getRefreshSupport)).exists(_.booleanValue)
    refreshesInlayHints = workspaceCapabilities.flatMap(w => Option(w.getInlayHint))
      .flatMap(c => Option(c.getRefreshSupport)).exists(_.booleanValue)
    readsConfiguration = workspaceCapabilities.flatMap(w => Option(w.getConfiguration)).exists(_.booleanValue)
    watchesConfiguration = workspaceCapabilities.flatMap(w => Option(w.getDidChangeConfiguration))
      .flatMap(c => Option(c.getDynamicRegistration)).exists(_.booleanValue)
    appliesEdits = workspaceCapabilities.flatMap(w => Option(w.getApplyEdit)).exists(_.booleanValue)
    showsDocuments = windowCapabilities.flatMap(w => Option(w.getShowDocument))
      .exists(_.isSupport)

    val folders = Option(params.getWorkspaceFolders).map(_.asScala.toSeq).getOrElse(Seq.empty)
    val rootUri = folders.headOption.map(_.getUri).orElse(Option(params.getRootUri))
      .getOrElse(throw new IllegalArgumentException("the client sent no workspace folder or root URI"))
    val clientName = Option(params.getClientInfo).map(i => s"${i.getName} ${Option(i.getVersion).getOrElse("")}".trim).getOrElse("an unnamed client")
    HostLog.info(s"initialized by $clientName for $rootUri")
    workspace = Some(Path.of(URI.create(rootUri)))

    val capabilities = new ServerCapabilities
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)
    capabilities.setHoverProvider(true)
    capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(java.util.List.of(CompleteInsertion, RunTest, RunMain)))
    capabilities.setDefinitionProvider(true)
    capabilities.setTypeDefinitionProvider(true)
    capabilities.setWorkspaceSymbolProvider(true)
    capabilities.setDocumentHighlightProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    capabilities.setFoldingRangeProvider(true)
    val inlayHints = new InlayHintRegistrationOptions
    // The locations and tooltips of a hint's label parts are filled in by inlayHint/resolve.
    inlayHints.setResolveProvider(true)
    val rename = new RenameOptions(/* prepareProvider = */ true)
    val tokens = new SemanticTokensWithRegistrationOptions(SemanticColoring.legend, /* full = */ true)
    val completionTriggers = java.util.List.of(".")
    // A call's opening parenthesis or bracket shows the signatures; a comma moves to the next parameter.
    val signatureTriggers = java.util.List.of("(", "[", ",")
    val signatureRetriggers = java.util.List.of(",")
    val actionKinds = java.util.List.of(CodeActionKind.QuickFix, CodeActionKind.RefactorRewrite,
      CodeActionKind.SourceOrganizeImports)
    capabilities.setCompletionProvider(new CompletionOptions(/* resolveProvider = */ false, completionTriggers))

    if (scopesScalaFeatures) {
      val lenses = scalaOnly(new CodeLensRegistrationOptions)
      lenses.setResolveProvider(false)
      capabilities.setInlayHintProvider(scalaOnly(inlayHints))
      capabilities.setSelectionRangeProvider(scalaOnly(new SelectionRangeRegistrationOptions))
      capabilities.setImplementationProvider(scalaOnly(new ImplementationRegistrationOptions))
      capabilities.setCallHierarchyProvider(scalaOnly(new CallHierarchyRegistrationOptions))
      capabilities.setTypeHierarchyProvider(scalaOnly(new TypeHierarchyRegistrationOptions))
      scalaOnly(rename)
      scalaOnly(tokens)
      val signatures = scalaOnly(new SignatureHelpRegistrationOptions)
      signatures.setTriggerCharacters(signatureTriggers)
      signatures.setRetriggerCharacters(signatureRetriggers)
      val actions = scalaOnly(new CodeActionRegistrationOptions)
      actions.setCodeActionKinds(actionKinds)
      languageRegistrations = Seq(
        "codeLens" -> lenses, "signatureHelp" -> signatures,
        "formatting" -> scalaOnly(new DocumentFormattingRegistrationOptions),
        "rangeFormatting" -> scalaOnly(new DocumentRangeFormattingRegistrationOptions),
        "references" -> scalaOnly(new ReferenceRegistrationOptions), "codeAction" -> actions,
      ).map { (method, options) => new Registration(s"scala-analyzer-$method", s"textDocument/$method", options) }
    } else {
      capabilities.setCodeLensProvider(new CodeLensOptions(false))
      capabilities.setInlayHintProvider(inlayHints)
      capabilities.setSelectionRangeProvider(true)
      capabilities.setSignatureHelpProvider(new SignatureHelpOptions(signatureTriggers, signatureRetriggers))
      capabilities.setDocumentFormattingProvider(true)
      capabilities.setDocumentRangeFormattingProvider(true)
      capabilities.setReferencesProvider(true)
      capabilities.setImplementationProvider(true)
      capabilities.setCallHierarchyProvider(true)
      capabilities.setTypeHierarchyProvider(true)
      capabilities.setCodeActionProvider(new CodeActionOptions(actionKinds))
      languageRegistrations = Seq.empty
    }
    capabilities.setRenameProvider(rename)
    capabilities.setSemanticTokensProvider(tokens)

    Option(params.getProcessId).foreach { pid =>
      try {
        val process = ProcessHandle.of(pid.longValue())
        if (process.isPresent) process.get().onExit().thenRun(() => exit())
        // A client in another PID namespace (a container, a Flatpak sandbox) is invisible, not dead: the server then
        // ends with its input.
        else HostLog.info(s"client process $pid is not visible; the server ends when its input ends")
      } catch { case NonFatal(e) => HostLog.warn(s"could not watch client process $pid", e) }
    }

    CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo("scala-analyzer", Main.version)))
  }

  /**
   * Queues the initial build import and registers negotiated language providers and file watchers.
   *
   * Build-definition changes and source creation/deletion trigger reimports. Watchers are registered only when
   * the client advertises dynamic registration support.
   *
   * @param params notification that the client is ready for server-initiated requests
   */
  override def initialized(params: InitializedParams): Unit = {
    workspace.foreach(openProject)

    if (languageRegistrations.nonEmpty) {
      client.registerCapability(new RegistrationParams(languageRegistrations.asJava)).exceptionally { error =>
        HostLog.warn("could not register Scala language features", error)
        null
      }
    }

    if (readsConfiguration) readInlayHintSettings()

    if (readsConfiguration && watchesConfiguration) {
      // The notification carries no settings the server needs: it reads them again, as after `initialized`.
      val registration =
        new Registration("scala-analyzer-configuration", "workspace/didChangeConfiguration", new JsonObject)
      client.registerCapability(new RegistrationParams(JavaList.of(registration))).exceptionally { error =>
        HostLog.warn("could not register for configuration changes", error)
        null
      }
    }

    if (watchesFiles) {
      val changes = WatchKind.Create | WatchKind.Change | WatchKind.Delete
      val watchers = Seq(
        BuildDefinition.SbtFiles -> changes, BuildDefinition.ProjectFiles -> changes, BuildDefinition.BuildProperties -> changes,
        "**/*.{scala,java}" -> changes,
      ).map((glob, kind) => new FileSystemWatcher(LspEither.forLeft[String, RelativePattern](glob), kind))

      client.registerCapability(new RegistrationParams(java.util.List.of(new Registration(
        "scala-analyzer-build-files", "workspace/didChangeWatchedFiles", new DidChangeWatchedFilesRegistrationOptions(watchers.asJava)))))
    }
  }

  /** Queues project opening after client initialization, reporting progress, readiness or startup failure. */
  private def openProject(root: Path): Unit = {
    projectRequested = true
    documents.execute { () =>
      setState("importing", s"opening $root")
      try {
        val opened = withProgress("scala-analyzer: opening the project")(progress => ProjectSession.open(host, root, progress = progress))
        val heap = Runtime.getRuntime
        val snapshot = opened.snapshot

        HostLog.info(s"ready in ${(System.nanoTime() - startedAt) / 1_000_000} ms since the server started: " +
          s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files, " +
          s"heap ${(heap.totalMemory - heap.freeMemory) / (1024 * 1024)} of ${heap.maxMemory / (1024 * 1024)} MB")
        setState("ready", s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files")
        session.complete(opened)
        refreshCodeLenses()
      } catch {
        case e: Throwable =>
          HostLog.error(s"opening the project at $root failed", e)
          setState("failed", String.valueOf(e.getMessage))
          val seeLog = logFile.fold("")(f => s" (log: $f)")
          client.showMessage(new MessageParams(MessageType.Error, s"scala-analyzer could not open the project: ${e.getMessage}$seeLog"))
          session.completeExceptionally(e)
      }
    }
  }

  /**
   * Requests session shutdown and stops request executors.
   *
   * Cleanup failures and deadline expiry are logged and converted into a successful null response.
   * The client is still expected to send `exit` afterward.
   *
   * @return a future completing with null after cleanup succeeds, fails or reaches its deadline
   */
  override def shutdown(): CompletableFuture[AnyRef] = {
    shutdownRequested = true
    closing.exceptionally { e =>
      HostLog.warn("could not close the project on shutdown", e)
      null
    }
  }

  /** Stops request executors and closes a session already being opened, with the server's shutdown deadline. */
  private lazy val closing: CompletableFuture[AnyRef] = {
    documents.shutdownNow()
    reads.shutdownNow()
    builds.shutdownNow()
    testRuns.close()
    pendingDiagnostics.synchronized {
      diagnostics.shutdownNow()
      pendingDiagnostics.clear()
    }

    val closed = if (!projectRequested) CompletableFuture.completedFuture[AnyRef](null)
    else session.handleAsync[AnyRef]((opened, error) => {
      if (error == null) opened.close()
      null
    }, runnable => daemon("scala-analyzer-ending").newThread(runnable).start())
    closed.orTimeout(EndingDeadlineSeconds, TimeUnit.SECONDS)
  }

  /**
   * Ends the server lifecycle and invokes the configured process-exit callback once.
   *
   * Session cleanup is bounded by the shutdown deadline. Logs remain subscribed during cleanup, then detach.
   * The callback receives 0 after `shutdown`, or 1 when the client disappears without requesting shutdown.
   */
  override def exit(): Unit = if (ending.compareAndSet(false, true)) {
    HostLog.info(s"exiting (${if (shutdownRequested) "after a shutdown request" else "without a shutdown request"})")
    try closing.get(EndingDeadlineSeconds, TimeUnit.SECONDS)
    catch {
      case e: InterruptedException =>
        HostLog.warn("interrupted while closing the project", e)
        Thread.currentThread().interrupt()
      case NonFatal(e) => HostLog.warn("could not close the project before exiting", e)
    } finally {
      clientLog.foreach(_.close())
      exitProcess(if (shutdownRequested) 0 else 1)
    }
  }

  /**
   * Captures server state, project counts, memory, request latencies and logged problems.
   *
   * Answers immediately while import is in progress. Individual counters may continue to change during collection;
   * the report is an operational snapshot rather than an atomic view of the entire server.
   *
   * @return an already completed future containing the host-specific status fields
   */
  override def status(): CompletableFuture[java.util.Map[String, Object]] = {
    /** Builds an ordered heterogeneous object for lsp4j's JSON serialization. */
    def map(entries: (String, Any)*): java.util.Map[String, Object] = {
      val fields = new java.util.LinkedHashMap[String, Object]()
      // Every Scala value is an Object at runtime (primitives are boxed), so this cast cannot fail.
      entries.foreach((name, value) => fields.put(name, value.asInstanceOf[Object]))
      fields
    }

    val heap = Runtime.getRuntime
    val opened = if (session.isDone && !session.isCompletedExceptionally) Some(session.join()) else None
    val project = opened.map { s =>
      val snapshot = s.snapshot
      map("modules" -> Integer.valueOf(snapshot.moduleCount), "sourceFiles" -> Integer.valueOf(snapshot.sourceFileCount))
    }

    CompletableFuture.completedFuture(map(
      "state" -> state.state,
      "message" -> state.message,
      "uptimeSeconds" -> java.lang.Long.valueOf((System.nanoTime() - startedAt) / 1_000_000_000),
      "logFile" -> logFile.map(_.toString).orNull,
      "project" -> project.orNull,
      "heapMb" -> map("used" -> java.lang.Long.valueOf((heap.totalMemory - heap.freeMemory) / (1024 * 1024)),
        "max" -> java.lang.Long.valueOf(heap.maxMemory / (1024 * 1024))),
      "requests" -> map(requests.summary.map(s => s.kind -> map(
        "count" -> java.lang.Long.valueOf(s.count), "p50Ms" -> java.lang.Long.valueOf(s.p50),
        "p95Ms" -> java.lang.Long.valueOf(s.p95), "maxMs" -> java.lang.Long.valueOf(s.max)))*),
      "logged" -> map("errors" -> java.lang.Long.valueOf(HostLog.count(HostLog.Level.Error)),
        "warnings" -> java.lang.Long.valueOf(HostLog.count(HostLog.Level.Warn))),
      "unsupportedAdapterCalls" -> CallLog.snapshot.map(_._1).filter(_.startsWith("UNSUPPORTED ")).map(_.stripPrefix("UNSUPPORTED ")).asJava,
      "recentProblems" -> HostLog.recentProblems.takeRight(ReportedProblems).map(_.toString.linesIterator.next()).asJava,
    ))
  }

  /**
   * Lists the targets of the imported build.
   *
   * Runs on the documents thread, so it describes the build as the imports sent before it left it.
   *
   * @return a future containing the targets sorted by name, then by identity; it fails when the project is not open
   */
  override def buildTargets(): CompletableFuture[JavaList[HostProtocol.BuildTarget]] =
    onDocuments[JavaList[HostProtocol.BuildTarget]]("buildTargets") { s =>
      s.buildTargets().map { target =>
        new HostProtocol.BuildTarget(
          target.id,
          target.name,
          target.scalaVersion,
          target.dependsOn.asJava,
          target.libraries.map(_.toAbsolutePath.toString).asJava,
          target.sourceFileCount,
        )
      }.asJava
    }

  /**
   * Compiles the build, or one target of it, and schedules diagnostics for the sources whose build problems changed.
   *
   * Runs on the builds thread, as the compile on save does, and is shown as progress. A build that does not compile
   * still answers: its errors are counted in the report.
   *
   * @param params the target to compile; `null`, or parameters without a target, compile the whole build
   * @return a future containing the compilation's outcome and its error and warning counts; it fails when the
   *         project is not open or the compile could not run, and with `InvalidParams` when the target is not one of
   *         the build's
   */
  override def compile(params: HostProtocol.CompileParams): CompletableFuture[HostProtocol.CompileReport] =
    answered("compile", builds.execute) { s =>
      val target = Option(params).flatMap(compileParams => Option(compileParams.target))
      val compilation = withProgress("scala-analyzer: compiling the build")(_ => s.compileBuild(target))

      compilation.changed.foreach(scheduleDiagnostics)

      new HostProtocol.CompileReport(compilation.succeeded, compilation.errors, compilation.warnings)
    }

  /**
   * Runs the selected inspections over the project's Scala sources and answers their problems; nothing is published.
   *
   * Runs on the builds thread, as a compile does, and is shown as progress with the file being inspected. Each source is
   * read on its own, so edits go on between sources. A client's cancellation ends the run before the next source.
   *
   * @return a future containing the number of sources inspected, the problems of those that have some, in path
   *         order, and the description of each inspection that reported a problem; it fails when the project is not
   *         open, and with `RequestCancelled` when the client cancels it
   */
  override def inspect(): CompletableFuture[HostProtocol.InspectReport] =
    answered("inspect", builds.execute) { s =>
      val sources = s.inspectableSources
      val files = withProgress("scala-analyzer: inspecting the project") { progress =>
        sources.zipWithIndex.flatMap { (path, index) =>
          if (HostLock.isRequestCancelled) throw new ProcessCanceledException
          progress(s"${index + 1}/${sources.size} ${path.getFileName}")

          val problems = inspected(s, path)
          Option.when(problems.nonEmpty)(new PublishDiagnosticsParams(path.toUri.toString, problems.map(diagnostic).asJava))
        }
      }

      val reported = files.flatMap(_.getDiagnostics.asScala).flatMap(problem => Option(problem.getCode)).map(_.getLeft).toSet
      val inspections = s.inspectionDescriptions.filter(description => reported(description.id)).map { description =>
        new HostProtocol.InspectionDescription(description.id, description.displayName, description.groupPath.asJava)
      }

      new HostProtocol.InspectReport(sources.size, files.asJava, inspections.asJava)
    }

  /** Inspects one source, once more if an edit cancelled the first attempt; a cancelled request is not retried. */
  private def inspected(s: ProjectSession, path: Path): Seq[FileProblem] =
    try s.inspect(path)
    catch { case _: ProcessCanceledException if !HostLock.isRequestCancelled => s.inspect(path) }

  /**
   * Imports the build again after reloading the build server's workspace, then schedules diagnostics for every
   * tracked document.
   *
   * Runs on the documents thread. The server state is `importing` meanwhile and `ready` afterwards.
   *
   * @return a future that completes with `null` when the import ends, or fails with the import's error
   */
  override def reimport(): CompletableFuture[Void] = onDocuments[Void]("reimport") { s =>
    importAndScheduleDiagnostics(s, reload = true)
    null
  }

  /**
   * Describes the type under a caret or of a selected expression, as the IDE's Type Info action does.
   *
   * Runs on a read thread.
   *
   * @param params the document and the selection; an empty range stands for the caret
   * @return the type as plain text, or `null` when there is no type there or the document is not a project source
   */
  override def typeInfo(params: HostProtocol.TypeInfoParams): CompletableFuture[String] =
    onRead("typeInfo", params.textDocument, null) { (s, path) =>
      s.typeInfo(path, lineRange(params.range)).orNull
    }

  /**
   * Locates what the IDE's Go to Super navigates to from a caret: the superclasses of the type around it, or the
   * members that the member around it overrides or implements.
   *
   * Runs on a read thread.
   *
   * @param params the document and the caret position
   * @return the locations of the supers, each a zero-width range at the reported position; empty when there is none
   *         or the document is not a project source
   */
  override def superElements(params: TextDocumentPositionParams): CompletableFuture[java.util.List[Location]] =
    onRead("superElements", params.getTextDocument, java.util.List.of[Location]()) { (s, path) =>
      s.superElements(path, lineColumn(params.getPosition)).map { (target, at) =>
        new Location(target.toUri.toString, new Range(position(at), position(at)))
      }.asJava
    }

  /**
   * Exposes text-document requests and notifications.
   *
   * @return the service backed by the ordered documents executor
   */
  override def getTextDocumentService: TextDocumentService = TextDocuments

  /**
   * Exposes workspace commands, symbol queries and watched-file notifications.
   *
   * @return the workspace service backed by the ordered documents executor
   */
  override def getWorkspaceService: WorkspaceService = Workspace

  // ---- Documents -------------------------------------------------------------------------------------------------

  /** Converts a client file URI to its local filesystem path. */
  private def pathOf(uri: String): Path = Path.of(URI.create(uri))

  /** A notification: handled in order on the documents thread; a failure is logged (a notification has no answer). */
  private def onDocuments(kind: String)(action: ProjectSession => Unit): Unit = {
    val submitted = System.nanoTime()
    documents.execute { () =>
      try timed(kind, submitted)(action(session.join()))
      catch { case NonFatal(e) => HostLog.error(s"$kind failed", e) }
    }
  }

  /** A request: answered in order on the documents thread. */
  private def onDocuments[T](kind: String)(request: ProjectSession => T): CompletableFuture[T] =
    answered(kind, documents.execute)(request)

  /**
   * Answers `request` where `schedule` runs it; a failure is logged and answered as an error. The client cancels the
   * returned future (`$/cancelRequest`): a request not started yet is then skipped, a running one cancelled.
   */
  private def answered[T](kind: String, schedule: Runnable => Unit)(request: ProjectSession => T): CompletableFuture[T] = {
    val submitted = System.nanoTime()
    val answer = new CompletableFuture[T]
    val cancellation = new HostLock.RequestCancellation

    answer.whenComplete((_, _) => if (answer.isCancelled) cancellation.cancel())
    schedule { () =>
      if (answer.isCancelled) HostLog.debug(s"$kind: cancelled before it ran")
      else
        try answer.complete(timed(kind, submitted)(HostLock.cancelledBy(cancellation)(answerOf(kind, request(session.join())))))
        catch { case error: Throwable => answer.completeExceptionally(error) }
    }

    answer
  }

  /** Maps a request's failure to the error answered to the client, logging what is not an expected refusal. */
  private def answerOf[T](kind: String, request: => T): T =
    try request
    catch {
      case _: ProcessCanceledException => throw requestError(ResponseErrorCode.RequestCancelled, "cancelled by the client or a newer edit")
      case e: DiffTooBigException =>
        HostLog.warn(s"$kind: the change is too large to compare", e)
        throw requestError(ResponseErrorCode.RequestFailed, "the change is too large to compare")
      case e: Rename.Refused => throw requestError(ResponseErrorCode.RequestFailed, e.getMessage)
      case e: BspImport.UnknownTarget => throw requestError(ResponseErrorCode.InvalidParams, e.getMessage)
      case e: ScalafmtFormatting.Failed =>
        client.showMessage(new MessageParams(MessageType.Error, e.getMessage))
        throw requestError(ResponseErrorCode.RequestFailed, e.getMessage)
      case e: ProjectSession.Unavailable =>
        HostLog.error(s"$kind rejected because the project session is unavailable", e)
        throw requestError(ResponseErrorCode.RequestFailed, e.getMessage)
      // A class the plugin needs and the host does not have is a LinkageError, not a NonFatal one: logged too.
      case e @ (NonFatal(_) | _: LinkageError) =>
        HostLog.error(s"$kind failed", e)
        throw e
    }

  /** Times `body` from submission, recording both queue wait and execution latency. */
  private def timed[T](kind: String, submitted: Long)(body: => T): T = {
    val started = System.nanoTime()
    try body
    finally {
      val totalMs = (System.nanoTime() - submitted) / 1_000_000
      val waitedMs = (started - submitted) / 1_000_000
      requests.record(kind, totalMs)
      if (totalMs >= SlowRequestMs) HostLog.warn(s"slow: $kind took $totalMs ms ($waitedMs ms waiting for other work)")
      else HostLog.debug(s"$kind: $totalMs ms ($waitedMs ms waiting)")
    }
  }

  /** Replaces a source's buffer with its file on disk when they differ, then schedules its diagnostics. */
  private def followDisk(s: ProjectSession, path: Path): Unit =
    if (Files.isRegularFile(path)) {
      val onDisk = Files.readString(path)

      if (s.text(path) != onDisk) {
        s.change(path, None, onDisk)
        scheduleDiagnostics(path)
      }
    }

  /** Resolves only a URI currently tracked as a project source; other open documents are outside the session. */
  private def sourcePath(s: ProjectSession, uri: String): Option[Path] =
    Option.when(uri.startsWith("file:"))(pathOf(uri)).filter(s.isSource)

  /** A request about a document: answered for project sources, `outside` for other documents. */
  private def onSource[T](kind: String, document: TextDocumentIdentifier, outside: => T)(request: (ProjectSession, Path) => T): CompletableFuture[T] =
    onDocuments(kind)(s => sourcePath(s, document.getUri).fold(outside)(request(s, _)))

  /**
   * A request that only reads a document: it waits on the documents thread for the edits sent before it, then runs on
   * a read thread, alongside other reads. Its answer must describe the version it was asked about: a change of the
   * document before it ends cancels it (an edit also cancels the queries it is running).
   */
  private def onRead[T](kind: String, document: TextDocumentIdentifier, outside: => T)(request: (ProjectSession, Path) => T): CompletableFuture[T] = {
    val uri = document.getUri
    // Written on the documents thread before the request is handed to the read threads, which read it after.
    var asked: Integer = null

    /** Whether the document still has the version it had when the request was taken off the documents thread. */
    def isAsked(path: Path): Boolean = versions.get(path) == asked

    answered(kind, work => documents.execute { () =>
      asked = Option.when(uri.startsWith("file:"))(versions.get(pathOf(uri))).orNull
      reads.execute(work)
    }) { s =>
      sourcePath(s, uri).fold(outside) { path =>
        if (!isAsked(path)) throw new ProcessCanceledException
        val result = request(s, path)
        if (!isAsked(path)) throw new ProcessCanceledException
        result
      }
    }
  }

  /** Builds the JSON-RPC error used to reject an LSP request with the supplied protocol code. */
  private def requestError(code: ResponseErrorCode, message: String): ResponseErrorException =
    new ResponseErrorException(new ResponseError(code, message, null))

  /** Rejects edit-producing responses if any newer edit has arrived since submission. */
  private def onEditsRequest[T](kind: String, document: TextDocumentIdentifier, outside: => T)
                               (request: (ProjectSession, Path) => T): CompletableFuture[T] = {
    val received = editsReceived.get()
    // Completion lists use no edit guard: clients filter suggestions as the user continues typing.
    /** Checks incoming edits even before they reach the ordered documents executor. */
    def checkNoNewerEdit(): Unit = if (editsReceived.get() != received) throw new ProcessCanceledException

    onSource(kind, document, outside) { (s, path) =>
      checkNoNewerEdit()
      val result = request(s, path)
      checkNoNewerEdit()
      result
    }
  }

  /** Text-document notifications and requests, executed in document order (reads then run concurrently). */
  private object TextDocuments extends LspServices.TextDocuments {
    /**
     * Tracks versions, syncs unsaved text and schedules diagnostics for project sources.
     *
     * Runs on the documents thread. Documents that are not project sources are not tracked. A Scala or Java file
     * outside the host state directory that is not one yet may have just been created (the editor may open it before
     * reporting its creation): the build is imported again first.
     *
     * @param params the opened document, with its version and the editor's text
     */
    override def didOpen(params: DidOpenTextDocumentParams): Unit = onDocuments("didOpen") { s =>
      val uri = params.getTextDocument.getUri

      if (uri.startsWith("file:") && sourcePath(s, uri).isEmpty) {
        val path = pathOf(uri)
        val name = path.getFileName.toString
        if ((name.endsWith(".scala") || name.endsWith(".java")) && !path.startsWith(host.stateDir)) importAgain(s, reload = false)
      }

      sourcePath(s, uri).foreach { path =>
        openDocuments.add(path)
        versions.put(path, params.getTextDocument.getVersion)
        // The editor's buffer may differ from the file on disk (unsaved changes).
        if (s.text(path) != params.getTextDocument.getText) s.change(path, None, params.getTextDocument.getText)
        scheduleDiagnostics(path)
      }
    }

    /**
     * Applies the client's incremental edits in order and replaces diagnostics for the new version.
     *
     * The edit counter that guards edit-producing requests advances on arrival, before the documents thread takes the
     * change; the change itself applies there, and only to project sources.
     *
     * @param params the changed document's new version and its content changes; a change without a range replaces the
     *               whole text
     */
    override def didChange(params: DidChangeTextDocumentParams): Unit = {
      editsReceived.incrementAndGet()
      onDocuments("didChange") { s =>
        sourcePath(s, params.getTextDocument.getUri).foreach { path =>
          versions.put(path, params.getTextDocument.getVersion)
          for (change <- params.getContentChanges.asScala) {
            s.change(path, Option(change.getRange).map(lineRange), change.getText)
          }
          scheduleDiagnostics(path)
        }
      }
    }

    /**
     * Returns a closed project source to its file on disk, which drops the unsaved changes the editor discarded.
     *
     * Runs on the documents thread. The source stays registered until a build reimport or session shutdown.
     *
     * @param params the closed document
     */
    override def didClose(params: DidCloseTextDocumentParams): Unit = onDocuments("didClose") { s =>
      sourcePath(s, params.getTextDocument.getUri).foreach { path =>
        openDocuments.remove(path)
        followDisk(s, path)
      }
    }

    /**
     * Queues a build compile without holding up edits and requests; pending saves share the next compile.
     *
     * The compile runs on the builds thread, then schedules diagnostics for each path it reports.
     *
     * @param params the saved document, which is ignored: every compile includes all Scala targets
     */
    override def didSave(params: DidSaveTextDocumentParams): Unit = onDocuments("didSave") { s =>
      if (compileQueued.compareAndSet(false, true)) {
        val submitted = System.nanoTime()
        builds.execute { () =>
          compileQueued.set(false)
          try timed("compile on save", submitted)(s.compileBuild().changed.foreach(scheduleDiagnostics))
          catch {
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(e) => HostLog.error("compile on save failed", e)
          }
        }
      }
    }

    /**
     * Returns the documentation at the requested project-source position as Markdown.
     *
     * Runs on a read thread.
     *
     * @param params the document and position to describe
     * @return the hover with Markdown content, or `null` when the session has none for the position or the document
     *         is not a project source
     */
    override def hover(params: HoverParams): CompletableFuture[Hover] = onRead("hover", params.getTextDocument, null) { (s, path) =>
      s.hover(path, lineColumn(params.getPosition)).map { markdown =>
        new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown))
      }.orNull
    }

    /**
     * Returns completion choices and whether more choices may be requested as the user types.
     *
     * Runs on the documents thread and, unlike edit-producing requests, is not cancelled by newer edits: clients
     * filter suggestions as the user continues typing.
     *
     * @param params the document and position to complete at
     * @return a list in the session's rank order whose `isIncomplete` flag is the session's; empty when the
     *         document is not a project source
     */
    override def completion(params: CompletionParams): CompletableFuture[LspEither[java.util.List[CompletionItem], CompletionList]] =
      onSource("completion", params.getTextDocument, LspEither.forRight(new CompletionList())) { (s, path) =>
        val choices = s.completion(path, lineColumn(params.getPosition))
        val items = choices.items.zipWithIndex.map((choice, rank) => completionItem(params.getTextDocument.getUri, choice, rank))
        LspEither.forRight(new CompletionList(choices.incomplete, items.asJava))
      }

    /**
     * Returns the inline hints the session computes within the requested source range.
     *
     * Runs on a read thread.
     *
     * @param params the document and range to hint
     * @return one hint per hint the session returns, each at its position; empty when the document is not a project
     *         source. A hint with a part that navigates has label parts, whose locations and tooltips
     *         `resolveInlayHint` gives; the others have a plain label. A hint that shows a definition's inferred type
     *         has the edit that writes it into the source
     */
    override def inlayHint(params: InlayHintParams): CompletableFuture[JavaList[InlayHint]] =
      onRead("inlayHint", params.getTextDocument, JavaList.of[InlayHint]()) { (s, path) =>
        val range = params.getRange
        s.inlayHints(path, lineColumn(range.getStart), lineColumn(range.getEnd))
          .map(toInlayHint(params.getTextDocument.getUri, _)).asJava
      }

    /**
     * Gives the label parts of a hint their locations and tooltips.
     *
     * Runs on a read thread. Locating a library declaration materializes its source file, which the hints of a whole
     * document must not do after every edit, so the client asks for the one hint the user points at.
     *
     * @param hint a hint this server returned, with its `data`
     * @return the same hint with `location` and `tooltip` on its label parts; unchanged when it has no `data` of
     *         this server's, or when the document no longer has that hint
     */
    override def resolveInlayHint(hint: InlayHint): CompletableFuture[InlayHint] = hint.getData match {
      case data: JsonObject if hint.getLabel.isRight =>
        onRead("inlayHint/resolve", new TextDocumentIdentifier(data.get("uri").getAsString), hint) { (s, path) =>
          val targets = s.inlayHintTargets(path, lineColumn(hint.getPosition), data.get("index").getAsInt)
          val parts = hint.getLabel.getRight.asScala

          // A changed document may have another hint at that index: leave this one as the client has it.
          if (targets.map(_.text) == parts.map(_.getValue).toSeq) parts.zip(targets).foreach { (part, target) =>
            target.location.foreach { (file, at) =>
              part.setLocation(new Location(file.toUri.toString, new Range(position(at), position(at))))
            }
            target.tooltip.foreach(text => part.setTooltip(text))
          }

          hint
        }
      case _ => CompletableFuture.completedFuture(hint)
    }

    /** Converts a session hint to the LSP's: label parts only when one navigates, resolved later by `data`. */
    private def toInlayHint(uri: String, hint: InlineHint): InlayHint = {
      val at = position(hint.at)
      val converted = new InlayHint(at, LspEither.forLeft[String, JavaList[InlayHintLabelPart]](hint.text))

      if (hint.parts.exists(_.navigable)) {
        val data = new JsonObject
        data.addProperty("uri", uri)
        data.addProperty("index", hint.index)

        converted.setLabel(hint.parts.map(part => new InlayHintLabelPart(part.text)).asJava)
        converted.setData(data)
      }
      hint.annotation.foreach(text => converted.setTextEdits(JavaList.of(new TextEdit(new Range(at, at), text))))

      converted
    }

    /**
     * Returns the run lenses of test suites, their tests and main classes, resolved eagerly.
     *
     * A client that can refresh lenses gets none until the project is open, then a refresh: an answer that waited
     * for the import would make VS Code, which spaces lens requests by their past durations, slow for the session.
     *
     * @param params the document whose lenses are requested
     * @return one lens per test suite or test, titled "Run suite" or "Run test", whose command carries the run's
     *         target, framework, suite and optional test name; then one per main class, titled "Run main", whose
     *         command carries the target and the class. Empty when the document is not a project source, or while
     *         the project is still opening for a client that refreshes lenses
     */
    override def codeLens(params: CodeLensParams): CompletableFuture[JavaList[? <: CodeLens]] =
      if (refreshesCodeLenses && !session.isDone) CompletableFuture.completedFuture(JavaList.of[CodeLens]())
      else onRead("codeLens", params.getTextDocument, JavaList.of[CodeLens]()) { (s, path) =>
        val tests = s.testLenses(path).map { lens =>
          val arguments = new JsonObject
          arguments.addProperty("target", lens.target)
          arguments.addProperty("framework", lens.framework)
          arguments.addProperty("suite", lens.suite)
          lens.testName.foreach(arguments.addProperty("testName", _))
          val title = if (lens.testName.isDefined) "Run test" else "Run suite"
          new CodeLens(range(lens.range), new Command(title, RunTest, JavaList.of[Object](arguments)), null)
        }
        val mains = s.mainLenses(path).map { lens =>
          val arguments = new JsonObject
          arguments.addProperty("target", lens.target)
          arguments.addProperty("mainClass", lens.mainClass)
          new CodeLens(range(lens.range), new Command("Run main", RunMain, JavaList.of[Object](arguments)), null)
        }

        (tests ++ mains).asJava
      }

    /**
     * Returns the signatures and active parameter at the requested call position.
     *
     * Runs on a read thread.
     *
     * @param params the document and position inside a call
     * @return the help for the signatures the session finds, or `null` when it finds none or the document is not a
     *         project source
     */
    override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
      onRead("signatureHelp", params.getTextDocument, null) { (s, path) =>
        s.signatures(path, lineColumn(params.getPosition)).filter(_.items.nonEmpty).map(signatureHelpOf).orNull
      }

    /**
     * Returns the nested syntax selections available at each requested position.
     *
     * Runs on a read thread.
     *
     * @param params the document and the positions to expand a selection from
     * @return one selection per requested position, in request order, each linked through `parent` along the
     *         session's range chain for that position; empty when the document is not a project source
     */
    override def selectionRange(params: SelectionRangeParams): CompletableFuture[JavaList[SelectionRange]] =
      onRead("selectionRange", params.getTextDocument, JavaList.of[SelectionRange]()) { (s, path) =>
        s.selectionRanges(path, params.getPositions.asScala.toSeq.map(lineColumn)).map { chain =>
          chain.map { r =>
            val selected = new SelectionRange
            selected.setRange(range(r))
            selected
          }.reduceRight { (child, parent) =>
            child.setParent(parent)
            child
          }
        }.asJava
      }

    /**
     * Formats a whole project source using the editor's indentation options.
     *
     * Runs on the documents thread; the request fails as cancelled if a newer edit arrives before it answers.
     *
     * @param params the document and the editor's tab size and tabs-or-spaces choice
     * @return the edits that format the document; empty when the document is not a project source
     */
    override def formatting(params: DocumentFormattingParams): CompletableFuture[java.util.List[? <: TextEdit]] =
      onEditsRequest("formatting", params.getTextDocument, java.util.List.of[TextEdit]()) { (s, path) =>
        textEdits(s.formatting(path, None, params.getOptions.getTabSize, !params.getOptions.isInsertSpaces))
      }

    /**
     * Formats only the requested range using the editor's indentation options.
     *
     * Runs on the documents thread; the request fails as cancelled if a newer edit arrives before it answers.
     *
     * @param params the document, the range to format and the editor's tab size and tabs-or-spaces choice
     * @return the edits that format the range; empty when the document is not a project source
     */
    override def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[java.util.List[? <: TextEdit]] =
      onEditsRequest("rangeFormatting", params.getTextDocument, java.util.List.of[TextEdit]()) { (s, path) =>
        val options = params.getOptions
        textEdits(s.formatting(path, Some(lineRange(params.getRange)), options.getTabSize, !options.isInsertSpaces))
      }

    /**
     * Returns declaration locations for the symbol at the requested position.
     *
     * Runs on a read thread.
     *
     * @param params the document and position of the symbol
     * @return the locations the session reports, each a zero-width range at the reported position; empty when none
     *         is found or the document is not a project source
     */
    override def definition(params: DefinitionParams)
        : CompletableFuture[LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
      onRead("definition", params.getTextDocument, locations(Seq.empty)) { (s, path) =>
        locations(s.definition(path, lineColumn(params.getPosition)))
      }

    /**
     * Returns type-declaration locations for the symbol at the requested position.
     *
     * Runs on a read thread.
     *
     * @param params the document and position of the symbol
     * @return the locations the session reports, each a zero-width range at the reported position; empty when none
     *         is found or the document is not a project source
     */
    override def typeDefinition(params: TypeDefinitionParams)
        : CompletableFuture[LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
      onRead("typeDefinition", params.getTextDocument, locations(Seq.empty)) { (s, path) =>
        locations(s.typeDefinition(path, lineColumn(params.getPosition)))
      }

    /**
     * Returns the source locations that reference the symbol at the requested position.
     *
     * Runs on a read thread.
     *
     * @param params the document and position of the symbol, and whether the declaration itself is included
     * @return the locations of the references the session reports; empty when the document is not a project source
     */
    override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
      onRead("references", params.getTextDocument, java.util.List.of[Location]()) { (s, path) =>
        s.references(path, lineColumn(params.getPosition), params.getContext.isIncludeDeclaration).map(location).asJava
      }

    /**
     * Returns source spans for the implementations of the symbol at the requested position.
     *
     * Runs on a read thread.
     *
     * @param params the document and position of the symbol
     * @return the spans the session reports, each as a location covering its whole range; empty when the document is
     *         not a project source
     */
    override def implementation(params: ImplementationParams)
        : CompletableFuture[LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
      onRead("implementation", params.getTextDocument, spanLocations(Seq.empty)) { (s, path) =>
        spanLocations(s.implementations(path, lineColumn(params.getPosition)))
      }

    /**
     * Prepares the method the IDE's call hierarchy selects at the position.
     *
     * Runs on a read thread. Each item's `data` identifies its declaration for the incoming and outgoing requests.
     *
     * @param params the document and position to prepare from
     * @return the items the session selects; empty when the document is not a project source
     */
    override def prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture[JavaList[CallHierarchyItem]] =
      onRead("prepareCallHierarchy", params.getTextDocument, java.util.List.of[CallHierarchyItem]()) { (s, path) =>
        s.prepareHierarchy(path, lineColumn(params.getPosition), types = false).map(callHierarchyItem).asJava
      }

    /**
     * Returns plugin caller groups with the references' ranges.
     *
     * Runs on a read thread. An item whose `data` is not a valid host identity is answered at once with an empty list.
     *
     * @param params the item, with the `data` this server gave it
     * @return one entry per caller group, with the ranges of its references; empty when the identity is invalid or its
     *         document is not a project source
     */
    override def callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture[JavaList[CallHierarchyIncomingCall]] =
      hierarchyRead("incomingCalls", params.getItem.getData, java.util.List.of[CallHierarchyIncomingCall]()) { (s, path, target) =>
        s.hierarchyCalls(path, target, incoming = true).map(c =>
          new CallHierarchyIncomingCall(callHierarchyItem(c.item), c.ranges.map(range).asJava)).asJava
      }

    /**
     * Returns callee groups with call sites in the requested caller.
     *
     * Runs on a read thread. An item whose `data` is not a valid host identity is answered at once with an empty list.
     *
     * @param params the item, with the `data` this server gave it
     * @return one entry per callee group, with the ranges of its call sites; empty when the identity is invalid or its
     *         document is not a project source
     */
    override def callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture[JavaList[CallHierarchyOutgoingCall]] =
      hierarchyRead("outgoingCalls", params.getItem.getData, java.util.List.of[CallHierarchyOutgoingCall]()) { (s, path, target) =>
        s.hierarchyCalls(path, target, incoming = false).map(c =>
          new CallHierarchyOutgoingCall(callHierarchyItem(c.item), c.ranges.map(range).asJava)).asJava
      }

    /**
     * Uses the Scala type hierarchy provider's target selection.
     *
     * Runs on a read thread. Each item's `data` identifies its declaration for the supertypes and subtypes requests.
     *
     * @param params the document and position to prepare from
     * @return the items the session selects; empty when the document is not a project source
     */
    override def prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture[JavaList[TypeHierarchyItem]] =
      onRead("prepareTypeHierarchy", params.getTextDocument, java.util.List.of[TypeHierarchyItem]()) { (s, path) =>
        s.prepareHierarchy(path, lineColumn(params.getPosition), types = true).map(typeHierarchyItem).asJava
      }

    /**
     * Returns direct supertypes, including navigable library declarations.
     *
     * Runs on a read thread. An item whose `data` is not a valid host identity is answered at once with an empty list.
     *
     * @param params the item, with the `data` this server gave it
     * @return the direct supertypes; empty when the identity is invalid or its document is not a project source
     */
    override def typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture[JavaList[TypeHierarchyItem]] =
      hierarchyRead("supertypes", params.getItem.getData, java.util.List.of[TypeHierarchyItem]()) { (s, path, target) =>
        s.hierarchyTypes(path, target, subtypes = false).map(typeHierarchyItem).asJava
      }

    /**
     * Returns direct subtypes in project sources.
     *
     * Runs on a read thread. An item whose `data` is not a valid host identity is answered at once with an empty list.
     *
     * @param params the item, with the `data` this server gave it
     * @return the direct subtypes; empty when the identity is invalid or its document is not a project source
     */
    override def typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture[JavaList[TypeHierarchyItem]] =
      hierarchyRead("subtypes", params.getItem.getData, java.util.List.of[TypeHierarchyItem]()) { (s, path, target) =>
        s.hierarchyTypes(path, target, subtypes = true).map(typeHierarchyItem).asJava
      }

    /**
     * Returns applicable code actions, with versioned edits for actions that can be applied immediately.
     *
     * Runs on a read thread. An action the session marks as disabled carries its reason and no edit. An action that
     * fixes or suppresses a problem lists, as its diagnostics, the context's diagnostics that report that problem.
     *
     * @param params the document, the range to act on, the kinds the client asked for and the diagnostics it shows
     *               there; the session gets the range and the kinds as they are
     * @return one action per choice the session offers; empty when the document is not a project source
     */
    override def codeAction(params: CodeActionParams): CompletableFuture[java.util.List[LspEither[Command, CodeAction]]] =
      onRead("codeAction", params.getTextDocument, java.util.List.of[LspEither[Command, CodeAction]]()) { (s, path) =>
        val requested = Option(params.getContext.getOnly).map(_.asScala.toSeq).getOrElse(Seq.empty)
        val shown = params.getContext.getDiagnostics.asScala.toSeq
        val inspectionIds = s.inspectionDescriptions.map(_.id).toSet
        val showsInspections = shown.exists(problem => Option(problem.getCode).exists(code => inspectionIds(code.getLeft)))

        s.codeActions(path, lineColumn(params.getRange.getStart), lineColumn(params.getRange.getEnd), requested, showsInspections)
          .map { (choice, changes) =>
            val action = new CodeAction(choice.title)
            action.setKind(choice.kind)
            val fixed = choice.problems.map(ScalaLanguageServer.this.diagnostic)
            val reporting = shown.filter(problem => fixed.exists(reports(problem, _)))
            if (reporting.nonEmpty) action.setDiagnostics(reporting.asJava)
            choice.disabled match {
              case Some(reason) => action.setDisabled(new CodeActionDisabled(reason))
              case None =>
                val document = new VersionedTextDocumentIdentifier(path.toUri.toString, versions.get(path))
                val edit = new TextDocumentEdit(document, textEdits(changes))
                action.setEdit(new WorkspaceEdit(java.util.List.of(LspEither.forLeft[TextDocumentEdit, ResourceOperation](edit))))
            }
            LspEither.forRight[Command, CodeAction](action)
          }.asJava
      }

    /**
     * Returns the symbol-name range that a following rename request may edit.
     *
     * Runs on a read thread.
     *
     * @param params the document and position of the symbol
     * @return the range of the symbol's name as the first alternative; `null` when the document is not a project source
     */
    override def prepareRename(params: PrepareRenameParams)
        : CompletableFuture[LspEither3[Range, PrepareRenameResult, PrepareRenameDefaultBehavior]] =
      onRead("prepareRename", params.getTextDocument, null) { (s, path) =>
        LspEither3.forFirst[Range, PrepareRenameResult, PrepareRenameDefaultBehavior](range(s.prepareRename(path, lineColumn(params.getPosition))))
      }

    /**
     * Returns workspace edits for a rename, cancelling if the source changes while they are computed.
     *
     * Runs on the documents thread. A `Rename.Refused` thrown by the session fails the request with its message.
     *
     * @param params the document and position of the symbol, and its new name
     * @return one versioned document edit per file the session changes; empty when the document is not a project source
     */
    override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
      onEditsRequest("rename", params.getTextDocument, new WorkspaceEdit()) { (s, path) =>
        val edits = s.rename(path, lineColumn(params.getPosition), params.getNewName).map { file =>
          val document = new VersionedTextDocumentIdentifier(file.path.toUri.toString, versions.get(file.path))
          LspEither.forLeft[TextDocumentEdit, ResourceOperation](new TextDocumentEdit(document, textEdits(file.changes)))
        }

        new WorkspaceEdit(edits.asJava)
      }

    /** Encodes declaration locations as the LSP `Location` response variant. */
    private def locations(found: Seq[(Path, LineColumn)]): LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]] =
      LspEither.forLeft(found.map { case (path, at) => new Location(path.toUri.toString, new Range(position(at), position(at))) }.asJava)

    /** Encodes source spans as the LSP `Location` response variant. */
    private def spanLocations(found: Seq[SourceSpan]): LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]] =
      LspEither.forLeft(found.map(location).asJava)

    /**
     * Returns what the IDE highlights at the position (a symbol's occurrences, a method's exit points, a companion's
     * keywords, ...), marking writes separately from reads.
     *
     * Runs on a read thread.
     *
     * @param params the document and position of the symbol
     * @return one highlight per occurrence the session reports, of kind write or read; empty when the document is not
     *         a project source
     */
    override def documentHighlight(params: DocumentHighlightParams): CompletableFuture[java.util.List[? <: DocumentHighlight]] =
      onRead("documentHighlight", params.getTextDocument, java.util.List.of[DocumentHighlight]()) { (s, path) =>
        s.highlights(path, lineColumn(params.getPosition)).map { o =>
          new DocumentHighlight(range(o.range), if (o.write) DocumentHighlightKind.Write else DocumentHighlightKind.Read)
        }.asJava
      }

    /**
     * Returns the nested declarations displayed in the document outline.
     *
     * Runs on a read thread.
     *
     * @param params the document to outline
     * @return the top-level declarations as document symbols, each carrying its nested children; empty when the
     *         document is not a project source
     */
    override def documentSymbol(params: DocumentSymbolParams)
        : CompletableFuture[java.util.List[LspEither[SymbolInformation, DocumentSymbol]]] =
      onRead("documentSymbol", params.getTextDocument, java.util.List.of[LspEither[SymbolInformation, DocumentSymbol]]()) { (s, path) =>
        /** Converts an outline declaration and its nested children to LSP document symbols. */
        def symbol(o: OutlineSymbol): DocumentSymbol = {
          val converted = new DocumentSymbol(o.name, lspSymbolKind(o.kind), range(o.range), range(o.nameRange), o.detail)

          converted.setChildren(o.children.map(symbol).asJava)
          converted
        }

        s.outline(path).map(o => LspEither.forRight[SymbolInformation, DocumentSymbol](symbol(o))).asJava
      }

    /**
     * Returns the full semantic-token stream for a project source.
     *
     * Runs on a read thread.
     *
     * @param params the document to color
     * @return the session's tokens as encoded by `SemanticColoring`; empty when the document is not a project source
     */
    override def semanticTokensFull(params: SemanticTokensParams): CompletableFuture[SemanticTokens] =
      onRead("semanticTokens", params.getTextDocument, new SemanticTokens(java.util.List.of[Integer]())) { (s, path) =>
        val data = SemanticColoring.encode(s.semanticTokens(path))

        new SemanticTokens(data.map(Integer.valueOf).asJava)
      }

    /**
     * Returns foldable code, comments, imports and regions in a project source.
     *
     * Runs on a read thread.
     *
     * @param params the document to fold
     * @return one range per fold the session reports, with its kind set for comments, imports and regions and left
     *         unset for code; empty when the document is not a project source
     */
    override def foldingRange(params: FoldingRangeRequestParams): CompletableFuture[java.util.List[FoldingRange]] =
      onRead("foldingRange", params.getTextDocument, java.util.List.of[FoldingRange]()) { (s, path) =>
        s.foldingRanges(path).map { fold =>
          val r = new FoldingRange(fold.startLine, fold.endLine)

          fold.kind match {
            case Fold.Comment => r.setKind(FoldingRangeKind.Comment)
            case Fold.Imports => r.setKind(FoldingRangeKind.Imports)
            case Fold.Region => r.setKind(FoldingRangeKind.Region)
            case Fold.Code => ()
          }

          r
        }.asJava
      }
  }

  // ---- Diagnostics -----------------------------------------------------------------------------------------------

  /** Replaces pending diagnostics for a source, leaving any computation already in progress to its version guard. */
  private def scheduleDiagnostics(path: Path): Unit = pendingDiagnostics.synchronized {
    if (!diagnostics.isShutdown) {
      pendingDiagnostics.remove(path).foreach(_.future.cancel(false))
      val version = versions.get(path)
      val task = new Runnable {
        override def run(): Unit = {
          // A dequeued task can lose its place to a newer schedule before it enters this guard.
          val current = pendingDiagnostics.synchronized {
            if (pendingDiagnostics.get(path).exists(_.task eq this)) {
              pendingDiagnostics.remove(path)
              true
            } else false
          }
          if (current) publishDiagnostics(path, version)
        }
      }

      pendingDiagnostics.put(path, ScheduledDiagnostics(task,
        diagnostics.schedule(task, DiagnosticsDelayMs, TimeUnit.MILLISECONDS)))
    }
  }

  /** Whether the client has not sent a newer version of `path` since this computation was scheduled. */
  private def isCurrent(path: Path, version: Integer): Boolean = !diagnostics.isShutdown && versions.get(path) == version

  /** Computes and publishes diagnostics only while `version` remains the current document version. */
  private def publishDiagnostics(path: Path, version: Integer): Unit =
    if (isCurrent(path, version)) {
      try {
        // A file the build no longer has (deleted, or out of every module) has no problems left.
        val problems = timed("problems", System.nanoTime()) {
          val opened = session.join()
          if (opened.isSource(path)) opened.problems(path) else Seq.empty
        }
        if (isCurrent(path, version))
          client.publishDiagnostics(new PublishDiagnosticsParams(path.toUri.toString, problems.map(diagnostic).asJava, version))
      } catch {
        // A later change cancelled this computation; that change scheduled its own.
        case _: ProcessCanceledException => ()
        case NonFatal(e) => HostLog.error(s"problems of $path failed", e)
      }
    }

  /** Encodes the documented host identity without retaining PSI or depending on case-class deserialization. */
  private def hierarchyData(target: HierarchyTarget): JsonObject = {
    val data = new JsonObject

    data.addProperty("uri", target.uri)
    data.addProperty("offset", target.offset)
    data.addProperty("name", target.name)
    target.libraryClass.foreach(data.addProperty("libraryClass", _))
    data
  }

  /** Validates opaque client data before scheduling a hierarchy read against its project document. */
  private def hierarchyRead[T](kind: String, data: Object, outside: => T)
      (request: (ProjectSession, Path, HierarchyTarget) => T): CompletableFuture[T] = {
    val target = data match {
      case values: JsonObject =>
        /** Reads a string member, absent when the member is missing or not a JSON string. */
        def string(key: String): Option[String] = Option(values.get(key))
          .filter(v => v.isJsonPrimitive && v.getAsJsonPrimitive.isString).map(_.getAsString)

        for {
          uri <- string("uri")
          name <- string("name")
          offsetValue <- Option(values.get("offset")).filter(v => v.isJsonPrimitive && v.getAsJsonPrimitive.isNumber)
          offset <- offsetValue.getAsString.toIntOption.filter(_ >= 0)
          if !values.has("libraryClass") || string("libraryClass").exists(_.nonEmpty)
        } yield HierarchyTarget(uri, offset, name, string("libraryClass"))
      case _ => None
    }

    target match {
      case Some(found) => onRead(kind, new TextDocumentIdentifier(found.uri), outside)((s, path) => request(s, path, found))
      case None => CompletableFuture.completedFuture(outside)
    }
  }

  /** Maps the shared declaration model to a call hierarchy item. */
  private def callHierarchyItem(item: HierarchyItem): CallHierarchyItem = {
    val result = new CallHierarchyItem(item.name, lspSymbolKind(item.kind), item.location.path.toUri.toString,
      range(item.location.range), range(item.selectionRange))

    result.setData(hierarchyData(item.target))
    result
  }

  /** Maps the shared declaration model to a type hierarchy item. */
  private def typeHierarchyItem(item: HierarchyItem): TypeHierarchyItem = {
    val result = new TypeHierarchyItem(item.name, lspSymbolKind(item.kind), item.location.path.toUri.toString,
      range(item.location.range), range(item.selectionRange))

    result.setData(hierarchyData(item.target))
    result
  }

  /** Converts a host diagnostic to LSP severity, range and source fields. */
  private def diagnostic(problem: FileProblem): org.eclipse.lsp4j.Diagnostic = {
    val converted = new org.eclipse.lsp4j.Diagnostic(new Range(position(problem.start), position(problem.end)), problem.message)
    val tags = Seq(
      Option.when(problem.deprecated)(org.eclipse.lsp4j.DiagnosticTag.Deprecated),
      Option.when(problem.unnecessary)(org.eclipse.lsp4j.DiagnosticTag.Unnecessary),
    ).flatten

    converted.setSeverity(problem.severity match {
      case ProblemSeverity.Error => DiagnosticSeverity.Error
      case ProblemSeverity.Warning => DiagnosticSeverity.Warning
      case ProblemSeverity.Information => DiagnosticSeverity.Information
    })
    converted.setSource(problem.source)
    problem.inspectionId.foreach(converted.setCode)
    if (tags.nonEmpty) converted.setTags(tags.asJava)

    converted
  }

  /** Whether a client's diagnostic is the one this server published for `problem`: the same range, code and message. */
  private def reports(shown: org.eclipse.lsp4j.Diagnostic, problem: org.eclipse.lsp4j.Diagnostic): Boolean =
    shown.getRange == problem.getRange && shown.getCode == problem.getCode && shown.getMessage == problem.getMessage

  /** Converts the LSP's zero-based UTF-16 position to the session's equivalent coordinate. */
  private def lineColumn(p: Position): LineColumn = LineColumn(p.getLine, p.getCharacter)

  /** Converts a session line and UTF-16 column to an LSP position. */
  private def position(at: LineColumn): Position = new Position(at.line, at.column)

  /** Converts a session range to an LSP range. */
  private def range(span: LineRange): Range = new Range(position(span.start), position(span.end))

  /** Converts an LSP range to the session's equivalent coordinates. */
  private def lineRange(r: Range): LineRange = LineRange(lineColumn(r.getStart), lineColumn(r.getEnd))

  /** Converts a project source span to a URI-backed LSP location. */
  private def location(span: SourceSpan): Location = new Location(span.path.toUri.toString, range(span.range))

  /** Converts a session text change to an LSP text edit. */
  private def textEdit(change: TextChange): TextEdit = new TextEdit(range(LineRange(change.start, change.end)), change.text)

  /** Converts a sequence of session edits to the Java list expected by LSP4J. */
  private def textEdits(changes: Seq[TextChange]): java.util.List[TextEdit] = changes.map(textEdit).asJava

  /** Maps protocol-neutral declaration categories to LSP symbol kinds. */
  private def lspSymbolKind(kind: WorkspaceSymbolKind): SymbolKind = kind match {
    case WorkspaceSymbolKind.EnumMember => SymbolKind.EnumMember
    case WorkspaceSymbolKind.Enum => SymbolKind.Enum
    case WorkspaceSymbolKind.Interface => SymbolKind.Interface
    case WorkspaceSymbolKind.Module => SymbolKind.Module
    case WorkspaceSymbolKind.Field => SymbolKind.Field
    case WorkspaceSymbolKind.Class => SymbolKind.Class
    case WorkspaceSymbolKind.Constructor => SymbolKind.Constructor
    case WorkspaceSymbolKind.Function => SymbolKind.Function
    case WorkspaceSymbolKind.Method => SymbolKind.Method
    case WorkspaceSymbolKind.Property => SymbolKind.Property
    case WorkspaceSymbolKind.Namespace => SymbolKind.Namespace
    case WorkspaceSymbolKind.Variable => SymbolKind.Variable
    case WorkspaceSymbolKind.Constant => SymbolKind.Constant
    case WorkspaceSymbolKind.Object => SymbolKind.Object
  }

  /** Maps host completion categories to LSP completion-item kinds. */
  private def lspCompletionKind(kind: Completion.Kind): CompletionItemKind = kind match {
    case Completion.Kind.EnumMember => CompletionItemKind.EnumMember
    case Completion.Kind.Enum => CompletionItemKind.Enum
    case Completion.Kind.Interface => CompletionItemKind.Interface
    case Completion.Kind.Module => CompletionItemKind.Module
    case Completion.Kind.TypeParameter => CompletionItemKind.TypeParameter
    case Completion.Kind.Class => CompletionItemKind.Class
    case Completion.Kind.Constructor => CompletionItemKind.Constructor
    case Completion.Kind.Method => CompletionItemKind.Method
    case Completion.Kind.Field => CompletionItemKind.Field
    case Completion.Kind.Variable => CompletionItemKind.Variable
    case Completion.Kind.Value => CompletionItemKind.Value
    case Completion.Kind.Keyword => CompletionItemKind.Keyword
  }

  /** Encodes a ranked suggestion and attaches insertion replay when the client can apply follow-up edits. */
  private def completionItem(uri: String, choice: CompletionChoice, rank: Int): CompletionItem = {
    val suggestion = choice.suggestion
    val item = new CompletionItem(suggestion.label)
    val details = new CompletionItemLabelDetails

    details.setDetail(suggestion.tail.orNull)
    details.setDescription(suggestion.typeText.orNull)
    item.setLabelDetails(details)
    item.setDetail(Seq(suggestion.tail, suggestion.typeText).flatten.mkString(" "))
    item.setKind(lspCompletionKind(suggestion.kind))
    if (suggestion.deprecated) item.setTags(java.util.List.of(CompletionItemTag.Deprecated))
    // The suggestions come ordered: by rank, the editor keeps that order instead of sorting them by label.
    item.setSortText(f"$rank%06d")
    item.setFilterText(suggestion.lookup)
    item.setTextEdit(LspEither.forLeft(new TextEdit(range(LineRange(choice.start, choice.end)), suggestion.lookup)))
    if (suggestion.hasHandler && appliesEdits) {
      val arguments: Seq[AnyRef] = Seq(uri, Integer.valueOf(choice.start.line), Integer.valueOf(choice.start.column),
        suggestion.prefixText, suggestion.lookup, suggestion.identity)
      item.setCommand(new Command(suggestion.label, CompleteInsertion, arguments.asJava))
    }

    item
  }

  /** Converts rendered parameter signatures and the selected overload into LSP signature help. */
  private def signatureHelpOf(found: SignatureChoices): SignatureHelp = {
    val signatures = found.items.map { signature =>
      val information = new SignatureInformation(signature.text)
      // The plugin renders the current parameter only, by its range in the text: it is the signature's one parameter.
      signature.current.foreach { current =>
        val parameter = new ParameterInformation()
        parameter.setLabel(LspEither.forRight(new Tuple.Two[Integer, Integer](current.start, current.end)))
        information.setParameters(java.util.List.of(parameter))
        information.setActiveParameter(0)
      }
      information
    }

    new SignatureHelp(signatures.asJava, found.active.map(Integer.valueOf).getOrElse(Integer.valueOf(0)), null)
  }

  /** Applies a versioned insertion follow-up and moves the caret only after the client accepts its edits. */
  private def applyFollowUp(uri: String, version: Integer, followUp: CompletionFollowUp): Unit =
    if (followUp.changes.nonEmpty) {
      val edit = new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri, version), textEdits(followUp.changes))
      val workspaceEdit = new WorkspaceEdit(java.util.List.of(LspEither.forLeft[TextDocumentEdit, ResourceOperation](edit)))

      client.applyEdit(new ApplyWorkspaceEditParams(workspaceEdit, "completion")).thenAccept { response =>
        if (response.isApplied && showsDocuments) {
          val show = new ShowDocumentParams(uri)
          show.setSelection(range(LineRange(followUp.caret, followUp.caret)))
          show.setTakeFocus(true)
          client.showDocument(show)
        }
      }
    }

  /** Starts the run of a lens's selection; the request completes when the run ends. */
  private def executeTest(params: ExecuteCommandParams): CompletableFuture[AnyRef] = testSelection(params) match {
    case None => CompletableFuture.failedFuture(requestError(ResponseErrorCode.InvalidParams,
      s"$RunTest: expected one object with target, framework (${TestRun.Frameworks.mkString(", ")}), suite and an optional testName"))
    case Some(selection) =>
      startRun(testRuns.start(selection, () => session.join().prepareTestRun(selection.target, _.foreach(scheduleDiagnostics)),
        logRunLine, showRunResult))
  }

  /** Starts the run of a lens's main class; the request completes when the program exits. */
  private def executeMain(params: ExecuteCommandParams): CompletableFuture[AnyRef] = mainSelection(params) match {
    case None => CompletableFuture.failedFuture(requestError(ResponseErrorCode.InvalidParams,
      s"$RunMain: expected one object with target and mainClass"))
    case Some(selection) =>
      startRun(testRuns.startMain(selection, () => session.join().prepareMainRun(selection.target, _.foreach(scheduleDiagnostics)),
        logRunLine, showRunResult))
  }

  /** Evaluates the start of a run, answering a start while another run is in progress as `RequestFailed`. */
  private def startRun(start: => CompletableFuture[AnyRef]): CompletableFuture[AnyRef] =
    try start
    catch {
      case e: IllegalStateException => CompletableFuture.failedFuture(requestError(ResponseErrorCode.RequestFailed, e.getMessage))
    }

  /** Reads the test selection argument the code lens gave; lsp4j hands it over as JSON. */
  private def testSelection(params: ExecuteCommandParams): Option[TestRun.Selection] =
    for {
      json <- objectArgument(params)
      target <- stringMember(json, "target")
      framework <- stringMember(json, "framework").filter(TestRun.Frameworks.contains)
      suite <- stringMember(json, "suite")
    } yield TestRun.Selection(target, framework, suite, stringMember(json, "testName"))

  /** Reads the main class argument the code lens gave; lsp4j hands it over as JSON. */
  private def mainSelection(params: ExecuteCommandParams): Option[TestRun.MainSelection] =
    for {
      json <- objectArgument(params)
      target <- stringMember(json, "target")
      mainClass <- stringMember(json, "mainClass")
    } yield TestRun.MainSelection(target, mainClass)

  /** The command's only argument when it is a JSON object, absent otherwise. */
  private def objectArgument(params: ExecuteCommandParams): Option[JsonObject] =
    Option(params.getArguments).map(_.asScala.toSeq) match {
      case Some(Seq(json: JsonObject)) => Some(json)
      case _ => None
    }

  /** Reads a non-blank string member, absent when the member is missing, not a JSON string or blank. */
  private def stringMember(json: JsonObject, name: String): Option[String] = Option(json.get(name)).collect {
    case value: JsonPrimitive if value.isString && !value.getAsString.isBlank => value.getAsString
  }

  /** Sends one line of a run's output to the client's log. */
  private def logRunLine(line: String): Unit = client.logMessage(new MessageParams(MessageType.Info, line))

  /** Shows a finished run's summary to the client: as an error if it failed, a warning if ignored, else information. */
  private def showRunResult(result: TestRun.Result): Unit = {
    val kind = if (result.failed) MessageType.Error else if (result.ignored) MessageType.Warning else MessageType.Info

    client.showMessage(new MessageParams(kind, result.summary))
  }

  // ---- Workspace -------------------------------------------------------------------------------------------------

  /** Workspace-level commands, symbol queries and build/source watch notifications. */
  private object Workspace extends LspServices.Workspace {
    /**
     * Reads the client's inlay hint settings again, for a client that answers `workspace/configuration`.
     *
     * Module settings come from the imported build, not from the client.
     *
     * @param params the changed settings, which are ignored: the client is asked for the section instead
     */
    override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit =
      if (readsConfiguration) readInlayHintSettings()

    /**
     * Executes a test selection, a main class or completion insert handler; unknown commands are invalid parameters.
     *
     * A test selection or main class runs on the run thread and the request completes when the run ends; starting
     * one while another is in progress fails with `RequestFailed`. A completion insertion runs on the documents
     * thread and completes with `null`. A malformed test selection or main class, a completion insertion without six
     * primitive arguments (the second and the third numbers) and an unknown command fail with `InvalidParams`.
     *
     * @param params the command name and its JSON arguments, as `completionItem` or the code lens gave them
     * @return a future completing with `null` once the command has run, or failing with the protocol error
     */
    override def executeCommand(params: ExecuteCommandParams): CompletableFuture[AnyRef] = params.getCommand match {
      case RunTest => executeTest(params)
      case RunMain => executeMain(params)
      case CompleteInsertion =>
        // lsp4j hands the arguments over as JSON: the ones completionItem gave.
        // The protocol lets a client leave `arguments` out; the line and the column are the two numbers.
        val arguments = Option(params.getArguments).map(_.asScala.toSeq).getOrElse(Seq.empty).collect { case json: JsonPrimitive => json }
        if (arguments.size != 6 || !arguments(1).isNumber || !arguments(2).isNumber)
          CompletableFuture.failedFuture(requestError(ResponseErrorCode.InvalidParams, s"$CompleteInsertion: $arguments"))
        else {
          val uri = arguments(0).getAsString
          val start = LineColumn(arguments(1).getAsInt, arguments(2).getAsInt)

          onSource("completion insertion", new TextDocumentIdentifier(uri), null) { (s, path) =>
            val prefixText = arguments(3).getAsString
            val lookup = arguments(4).getAsString
            val identity = arguments(5).getAsString

            s.insertCompletion(path, start, prefixText, lookup, identity)
              .foreach(applyFollowUp(uri, versions.get(path), _))
            null
          }
        }
      case other => CompletableFuture.failedFuture(requestError(ResponseErrorCode.InvalidParams, s"unknown command: $other"))
    }

    /**
     * Searches project-wide symbols matching the client's query.
     *
     * Runs on the documents thread.
     *
     * @param params the query text
     * @return the `WorkspaceSymbol` alternative with one entry per symbol the session finds, in its order, each located
     *         by its source span and carrying its container name when it has one
     */
    override def symbol(params: WorkspaceSymbolParams): CompletableFuture[LspEither[java.util.List[? <: SymbolInformation], java.util.List[? <: WorkspaceSymbol]]] =
      onDocuments("workspaceSymbol") { s =>
        val symbols = s.workspaceSymbols(params.getQuery).map { found =>
          val symbol = new WorkspaceSymbol(found.name, lspSymbolKind(found.kind), LspEither.forLeft[Location, WorkspaceSymbolLocation](location(found.location)))
          found.container.foreach(symbol.setContainerName)
          symbol
        }

        LspEither.forRight[java.util.List[? <: SymbolInformation], java.util.List[? <: WorkspaceSymbol]](symbols.asJava)
      }

    /**
     * Reimports after build changes or created and deleted sources, and brings changed sources that are not open in
     * the editor to their new text on disk.
     *
     * Runs on the documents thread. The import reloads the BSP workspace only when a changed file is part of the build
     * definition, and refreshes diagnostics for tracked files. A source that only changed needs no import; while it is
     * open, the editor's buffer is its text, and a save reports the text it already has.
     *
     * @param params the created, changed or deleted files; entries whose URI is not a `file:` URI are ignored
     */
    override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = onDocuments("watched files") { s =>
      val changes = params.getChanges.asScala.toSeq
        .filter(_.getUri.startsWith("file:"))
        .map(change => pathOf(change.getUri) -> change.getType)
      val root = workspace.getOrElse(throw new IllegalStateException("watched files before initialize"))
      val buildChanged = changes.exists((path, _) => BuildDefinition.isPart(root, path))
      // A file outside the build's sources and definition, such as another checkout under the workspace, changes
      // nothing the server imports.
      val sourcesAddedOrRemoved = changes.exists { (path, kind) =>
        kind != FileChangeType.Changed && !BuildDefinition.isPart(root, path) && s.affectsSources(path)
      }

      if (buildChanged || sourcesAddedOrRemoved) {
        val reason = if (buildChanged) "build definition changed" else "source files created or deleted"
        HostLog.info(s"$reason: ${changes.map(_._1).mkString(", ")}")
        importAndScheduleDiagnostics(s, reload = buildChanged)
      }
      changes
        .collect { case (path, FileChangeType.Changed) if s.isSource(path) && !openDocuments.contains(path) => path }
        .foreach(followDisk(s, _))
    }
  }
}

/** The command that runs the suite or test selection carried by a code lens. */
private val RunTest = "scalaAnalyzer.runTest"

/** The command that runs the main class carried by a code lens. */
private val RunMain = "scalaAnalyzer.runMain"

/** The command that replays insertion after the client has inserted a completion item's lookup text. */
private val CompleteInsertion = "scalaAnalyzer.completeInsertion"

/** The client's settings section that selects the kinds of inlay hints (see `InlayHints.Toggles`). */
private val InlayHintSettings = "scalaAnalyzer.inlayHints"

/** Which files define an sbt build: the client watches them, and a change to one reloads the build. */
private object BuildDefinition {
  /** Watcher glob for sbt build files at any depth. */
  val SbtFiles = "**/*.sbt"

  /** Watcher glob for the Scala sources of a build's `project` directory. */
  val ProjectFiles = "**/project/*.scala"

  /** Watcher glob for the `build.properties` file of a build's `project` directory. */
  val BuildProperties = "**/project/build.properties"

  /**
   * Whether `path` can change the workspace's sbt build definition and therefore requires a BSP workspace reload.
   *
   * Only the build at the workspace root counts: the build files of other builds under it, such as test fixtures or
   * other checkouts, are not part of it.
   *
   * @param root workspace root
   * @param path changed file; must have a file name, so not a filesystem root
   * @return `true` for a `.sbt` file in the root or its `project` directory, and for a `.scala` file or
   *         `build.properties` in that `project` directory; `false` otherwise
   */
  def isPart(root: Path, path: Path): Boolean = {
    val name = path.getFileName.toString
    val parent = path.getParent
    val inProject = parent == root.resolve("project")

    (name.endsWith(".sbt") && (parent == root || inProject)) ||
      (inProject && (name.endsWith(".scala") || name == "build.properties"))
  }
}

/** Latencies by request kind: counts and maxima since the server started, percentiles over the latest requests. */
private final class RequestStats {
  /** Enough recent requests of a kind for stable percentiles, few enough that memory stays flat. */
  private val Window = 512

  /**
   * Aggregate count, recent-window percentiles and lifetime maximum for one request kind.
   *
   * @param kind request kind the figures belong to
   * @param count requests recorded since startup
   * @param p50 median duration over the recent window, in milliseconds
   * @param p95 95th-percentile duration over the recent window, in milliseconds
   * @param max longest duration since startup, in milliseconds
   */
  final case class Summary(kind: String, count: Long, p50: Long, p95: Long, max: Long)

  /** Retains one request kind's lifetime totals and bounded latency window. */
  private final class Kind {
    /** Number of requests recorded since startup. */
    var count = 0L
    /** Longest request duration since startup. */
    var max = 0L
    /** Durations retained for percentile summaries. */
    val recent = new java.util.ArrayDeque[Long]()
  }

  /** The totals and window of each kind in name order; guarded by its own monitor. */
  private val kinds = new java.util.TreeMap[String, Kind]()

  /**
   * Adds one duration to the kind's lifetime totals and bounded percentile window.
   *
   * Safe to call from any thread: updates and `summary` exclude each other.
   *
   * @param kind request kind the duration belongs to; its statistics are created on first use
   * @param ms request duration in milliseconds
   */
  def record(kind: String, ms: Long): Unit = kinds.synchronized {
    val stats = kinds.computeIfAbsent(kind, _ => new Kind)

    stats.count += 1
    stats.max = math.max(stats.max, ms)
    stats.recent.addLast(ms)
    if (stats.recent.size > Window) stats.recent.removeFirst()
  }

  /**
   * Returns per-kind summaries in stable name order.
   *
   * Safe to call from any thread: it excludes concurrent `record` calls, so each summary is internally consistent.
   *
   * @return one summary per kind recorded so far, ordered by kind name; empty before any request is recorded
   */
  def summary: Seq[Summary] = kinds.synchronized {
    kinds.asScala.toSeq.map { (name, stats) =>
      val sorted = stats.recent.asScala.toVector.sorted

      /** Selects the existing window's upper quantile rank, clamped to its final sample. */
      def percentile(p: Double): Long = sorted(math.min(sorted.size - 1, (p * sorted.size).toInt))

      Summary(name, stats.count, percentile(0.5), percentile(0.95), stats.max)
    }
  }
}
