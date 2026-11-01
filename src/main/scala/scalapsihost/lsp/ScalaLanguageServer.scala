package scalapsihost.lsp

import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.ProcessCanceledException
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{Either as LspEither, Either3 as LspEither3, ResponseError, ResponseErrorCode, Tuple}
import org.eclipse.lsp4j.services.*

import scalapsihost.{LineColumn, ScalaPsiHost}
import scalapsihost.build.ScalafmtFormatting
import scalapsihost.language.{Completion, CompletionChoice, CompletionFollowUp, FileProblem, Fold, OutlineSymbol, ProblemSeverity, Rename, SignatureChoices, SourceSpan, TextChange, WorkspaceSymbolKind}
import scalapsihost.platform.{CallLog, HostLog}
import scalapsihost.workspace.ProjectSession

import java.net.URI
import java.nio.file.Path
import java.util.{List as JavaList}
import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Serves LSP requests over the workspace's imported build session.
 *
 * Document notifications and requests run in order on one thread, so a request sees every change sent
 * before it; the project is opened there first, so `initialize` answers at once. Diagnostics are computed on
 * another thread, a short while after a document's last change: a change cancels a computation in progress (the
 * host's write lock cancels running queries), and a result is published only for the document's current version.
 *
 * What the server does is logged ([[scalapsihost.platform.HostLog]]) and sent to the client as log messages; opening and importing the
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
  // Fast typing produces a change per keystroke; diagnostics wait for this pause before they are computed.
  private val DiagnosticsDelayMs = 200L
  // A request that takes longer is logged as slow: an editor waiting this long looks stuck.
  private val SlowRequestMs = 1000L
  // Allows a short BSP shutdown handshake, but never waits for a full build import when the client has gone.
  private val EndingDeadlineSeconds = 5L
  // Progress cannot start until the client acknowledges its token; bound that UI handshake separately from import.
  private val ProgressRegistrationDeadlineSeconds = 30L
  // Status is a compact report; the full bounded warning/error history remains in HostLog.
  private val ReportedProblems = 20

  @volatile private var client: LanguageClient = scala.compiletime.uninitialized
  private var clientLog: Option[AutoCloseable] = None
  // Whether the client applies the server's edits and moves the caret on its request: a chosen suggestion's insert
  // handler needs both (see CompleteInsertion).
  @volatile private var appliesEdits = false
  @volatile private var showsDocuments = false
  // The project to open once the client says it is ready (`initialized`).
  @volatile private var workspace = Option.empty[Path]
  @volatile private var watchesFiles = false
  @volatile private var languageRegistrations = Seq.empty[Registration]
  @volatile private var reportsProgress = false
  @volatile private var state = new HostProtocol.ServerState("starting", "waiting for the client")
  private val startedAt = System.nanoTime()
  private val progressTokens = new AtomicInteger()
  private val requests = new RequestStats
  @volatile private var shutdownRequested = false
  @volatile private var projectRequested = false
  private val ending = new AtomicBoolean()
  private val documents = Executors.newSingleThreadExecutor(daemon("scala-psi-host-documents"))
  private val builds = Executors.newSingleThreadExecutor(daemon("scala-psi-host-builds"))
  // At most one compile waits behind the running one; every compile includes all Scala targets.
  private val compileQueued = new AtomicBoolean()
  private val diagnostics = new ScheduledThreadPoolExecutor(1, daemon("scala-psi-host-diagnostics"))
  diagnostics.setRemoveOnCancelPolicy(true)
  /** Keeps a pending computation's identity together with its cancellable scheduler handle. */
  private final case class ScheduledDiagnostics(task: Runnable, future: ScheduledFuture[?])
  private val pendingDiagnostics = scala.collection.mutable.Map.empty[Path, ScheduledDiagnostics]
  private val session = new CompletableFuture[ProjectSession]
  // Each document's latest version, as the client numbers them; diagnostics of older versions are dropped.
  private val versions = new ConcurrentHashMap[Path, Integer]()
  // Edits counted as they arrive, before their turn on the documents thread (see onEditsRequest).
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
      val token = LspEither.forLeft[String, Integer](s"scala-psi-host-${progressTokens.incrementAndGet()}")
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

  /** Imports the build again (see [[ProjectSession.importBuild]]), as progress and server state. */
  private[lsp] def importAgain(s: ProjectSession, reload: Boolean): Unit = {
    var importReturned = false

    setState("importing", "importing the build again")
    try {
      withProgress("scala-psi-host: importing the build") { progress =>
        s.importBuild(reload, progress)
        importReturned = true
      }
      val snapshot = s.snapshot
      setState("ready", s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files")
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
        c.getRangeFormatting, c.getReferences, c.getCodeAction).forall { feature =>
        Option(feature).flatMap(f => Option(f.getDynamicRegistration)).exists(_.booleanValue)
      }
    }
    def scalaOnly[T <: AbstractTextDocumentRegistrationAndWorkDoneProgressOptions](options: T): T = {
      options.setDocumentSelector(java.util.List.of(new DocumentFilter("scala", "file", null)))
      options
    }

    watchesFiles = workspaceCapabilities.flatMap(w => Option(w.getDidChangeWatchedFiles))
      .flatMap(w => Option(w.getDynamicRegistration)).exists(_.booleanValue)
    reportsProgress = windowCapabilities.flatMap(w => Option(w.getWorkDoneProgress)).exists(_.booleanValue)
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
    capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(java.util.List.of(CompleteInsertion)))
    capabilities.setDefinitionProvider(true)
    capabilities.setTypeDefinitionProvider(true)
    capabilities.setWorkspaceSymbolProvider(true)
    capabilities.setDocumentHighlightProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    capabilities.setFoldingRangeProvider(true)
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
      capabilities.setInlayHintProvider(scalaOnly(new InlayHintRegistrationOptions))
      capabilities.setSelectionRangeProvider(scalaOnly(new SelectionRangeRegistrationOptions))
      capabilities.setImplementationProvider(scalaOnly(new ImplementationRegistrationOptions))
      scalaOnly(rename)
      scalaOnly(tokens)
      val signatures = scalaOnly(new SignatureHelpRegistrationOptions)
      signatures.setTriggerCharacters(signatureTriggers)
      signatures.setRetriggerCharacters(signatureRetriggers)
      val actions = scalaOnly(new CodeActionRegistrationOptions)
      actions.setCodeActionKinds(actionKinds)
      languageRegistrations = Seq(
        "signatureHelp" -> signatures,
        "formatting" -> scalaOnly(new DocumentFormattingRegistrationOptions),
        "rangeFormatting" -> scalaOnly(new DocumentRangeFormattingRegistrationOptions),
        "references" -> scalaOnly(new ReferenceRegistrationOptions), "codeAction" -> actions,
      ).map { (method, options) => new Registration(s"scala-psi-host-$method", s"textDocument/$method", options) }
    } else {
      capabilities.setInlayHintProvider(true)
      capabilities.setSelectionRangeProvider(true)
      capabilities.setSignatureHelpProvider(new SignatureHelpOptions(signatureTriggers, signatureRetriggers))
      capabilities.setDocumentFormattingProvider(true)
      capabilities.setDocumentRangeFormattingProvider(true)
      capabilities.setReferencesProvider(true)
      capabilities.setImplementationProvider(true)
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
    CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo("scala-psi-host", "0.1")))
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
    if (watchesFiles) {
      val changes = WatchKind.Create | WatchKind.Change | WatchKind.Delete
      val watchers = Seq(
        BuildDefinition.SbtFiles -> changes, BuildDefinition.ProjectFiles -> changes, BuildDefinition.BuildProperties -> changes,
        "**/*.{scala,java}" -> (WatchKind.Create | WatchKind.Delete),
      ).map((glob, kind) => new FileSystemWatcher(LspEither.forLeft[String, RelativePattern](glob), kind))
      client.registerCapability(new RegistrationParams(java.util.List.of(new Registration(
        "scala-psi-host-build-files", "workspace/didChangeWatchedFiles", new DidChangeWatchedFilesRegistrationOptions(watchers.asJava)))))
    }
  }

  /** Queues project opening after client initialization, reporting progress, readiness or startup failure. */
  private def openProject(root: Path): Unit = {
    projectRequested = true
    documents.execute { () =>
      setState("importing", s"opening $root")
      try {
        val opened = withProgress("scala-psi-host: opening the project")(progress => ProjectSession.open(host, root, progress = progress))
        val heap = Runtime.getRuntime
        val snapshot = opened.snapshot
        HostLog.info(s"ready in ${(System.nanoTime() - startedAt) / 1_000_000} ms since the server started: " +
          s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files, " +
          s"heap ${(heap.totalMemory - heap.freeMemory) / (1024 * 1024)} of ${heap.maxMemory / (1024 * 1024)} MB")
        setState("ready", s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files")
        session.complete(opened)
      } catch {
        case e: Throwable =>
          HostLog.error(s"opening the project at $root failed", e)
          setState("failed", String.valueOf(e.getMessage))
          val seeLog = logFile.fold("")(f => s" (log: $f)")
          client.showMessage(new MessageParams(MessageType.Error, s"scala-psi-host could not open the project: ${e.getMessage}$seeLog"))
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
    builds.shutdownNow()
    pendingDiagnostics.synchronized {
      diagnostics.shutdownNow()
      pendingDiagnostics.clear()
    }
    val closed = if (!projectRequested) CompletableFuture.completedFuture[AnyRef](null)
    else session.handleAsync[AnyRef]((opened, error) => {
      if (error == null) opened.close()
      null
    }, runnable => daemon("scala-psi-host-ending").newThread(runnable).start())
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
      "requests" -> map(requests.summary.map((kind, s) => kind -> map(
        "count" -> java.lang.Long.valueOf(s.count), "p50Ms" -> java.lang.Long.valueOf(s.p50),
        "p95Ms" -> java.lang.Long.valueOf(s.p95), "maxMs" -> java.lang.Long.valueOf(s.max)))*),
      "logged" -> map("errors" -> java.lang.Long.valueOf(HostLog.count(HostLog.Level.Error)),
        "warnings" -> java.lang.Long.valueOf(HostLog.count(HostLog.Level.Warn))),
      "unsupportedAdapterCalls" -> CallLog.snapshot.map(_._1).filter(_.startsWith("UNSUPPORTED ")).map(_.stripPrefix("UNSUPPORTED ")).asJava,
      "recentProblems" -> HostLog.recentProblems.takeRight(ReportedProblems).map(_.toString.linesIterator.next()).asJava,
    ))
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

  /** A request: answered in order on the documents thread; a failure is logged and answered as an error. */
  private def onDocuments[T](kind: String)(request: ProjectSession => T): CompletableFuture[T] = {
    val submitted = System.nanoTime()
    CompletableFuture.supplyAsync(() =>
      try timed(kind, submitted)(request(session.join()))
      catch {
        case _: ProcessCanceledException => throw requestError(ResponseErrorCode.RequestCancelled, "cancelled by a newer edit")
        case e: DiffTooBigException =>
          HostLog.warn(s"$kind: the change is too large to compare", e)
          throw requestError(ResponseErrorCode.RequestFailed, "the change is too large to compare")
        case e: Rename.Refused => throw requestError(ResponseErrorCode.RequestFailed, e.getMessage)
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
      }, documents)
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

  /** Resolves only a URI currently tracked as a project source; other open documents are outside the session. */
  private def sourcePath(s: ProjectSession, uri: String): Option[Path] =
    Option.when(uri.startsWith("file:"))(pathOf(uri)).filter(s.isSource)

  /** A request about a document: answered for project sources, `outside` for other documents. */
  private def onSource[T](kind: String, document: TextDocumentIdentifier, outside: => T)(request: (ProjectSession, Path) => T): CompletableFuture[T] =
    onDocuments(kind)(s => sourcePath(s, document.getUri).fold(outside)(request(s, _)))

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

  /** Text-document notifications and requests, executed in document order. */
  private object TextDocuments extends LspServices.TextDocuments {
    // Documents that are not project sources are not tracked. A Scala or Java file that is not one yet may have just
    // been created (the editor may open it before reporting its creation): the build is imported again first.
    /** Tracks versions, syncs unsaved text and schedules diagnostics for project sources. */
    override def didOpen(params: DidOpenTextDocumentParams): Unit = onDocuments("didOpen") { s =>
      val uri = params.getTextDocument.getUri
      if (uri.startsWith("file:") && sourcePath(s, uri).isEmpty) {
        val path = pathOf(uri)
        val name = path.getFileName.toString
        if ((name.endsWith(".scala") || name.endsWith(".java")) && !path.startsWith(host.stateDir)) importAgain(s, reload = false)
      }
      sourcePath(s, uri).foreach { path =>
        versions.put(path, params.getTextDocument.getVersion)
        // The editor's buffer may differ from the file on disk (unsaved changes).
        if (s.text(path) != params.getTextDocument.getText) s.change(path, None, params.getTextDocument.getText)
        scheduleDiagnostics(path)
      }
    }

    /** Applies the client's incremental edits in order and replaces diagnostics for the new version. */
    override def didChange(params: DidChangeTextDocumentParams): Unit = {
      editsReceived.incrementAndGet()
      onDocuments("didChange") { s =>
        sourcePath(s, params.getTextDocument.getUri).foreach { path =>
          versions.put(path, params.getTextDocument.getVersion)
          for (change <- params.getContentChanges.asScala) {
            val range = Option(change.getRange).map(r => (lineColumn(r.getStart), lineColumn(r.getEnd)))
            s.change(path, range, change.getText)
          }
          scheduleDiagnostics(path)
        }
      }
    }

    /** Keeps project sources open until a build reimport or session shutdown, even after editor tabs close. */
    override def didClose(params: DidCloseTextDocumentParams): Unit = ()

    /** Queues a build compile without holding up edits and requests; pending saves share the next compile. */
    override def didSave(params: DidSaveTextDocumentParams): Unit = onDocuments("didSave") { s =>
      if (compileQueued.compareAndSet(false, true)) {
        val submitted = System.nanoTime()
        builds.execute { () =>
          compileQueued.set(false)
          try timed("compile on save", submitted)(s.compileBuild().foreach(scheduleDiagnostics))
          catch {
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(e) => HostLog.error("compile on save failed", e)
          }
        }
      }
    }

    /** Returns the documentation at the requested project-source position as Markdown. */
    override def hover(params: HoverParams): CompletableFuture[Hover] = onSource("hover", params.getTextDocument, null) { (s, path) =>
      s.hover(path, lineColumn(params.getPosition)).map { markdown =>
        new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown))
      }.orNull
    }

    /** Returns completion choices and whether more choices may be requested as the user types. */
    override def completion(params: CompletionParams): CompletableFuture[LspEither[java.util.List[CompletionItem], CompletionList]] =
      onSource("completion", params.getTextDocument, LspEither.forRight(new CompletionList())) { (s, path) =>
        val choices = s.completion(path, lineColumn(params.getPosition))
        val items = choices.items.zipWithIndex.map((choice, rank) => completionItem(params.getTextDocument.getUri, choice, rank))
        LspEither.forRight(new CompletionList(choices.incomplete, items.asJava))
      }

    /** Returns parameter and other inline hints within the requested source range. */
    override def inlayHint(params: InlayHintParams): CompletableFuture[JavaList[InlayHint]] =
      onSource("inlayHint", params.getTextDocument, JavaList.of[InlayHint]()) { (s, path) =>
        val range = params.getRange
        s.inlayHints(path, lineColumn(range.getStart), lineColumn(range.getEnd)).map { hint =>
          new InlayHint(position(hint.at), LspEither.forLeft[String, JavaList[InlayHintLabelPart]](hint.text))
        }.asJava
      }

    /** Returns the signatures and active parameter at the requested call position. */
    override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
      onSource("signatureHelp", params.getTextDocument, null) { (s, path) =>
        s.signatures(path, lineColumn(params.getPosition)).filter(_.items.nonEmpty).map(signatureHelpOf).orNull
      }

    /** Returns the nested syntax selections available at each requested position. */
    override def selectionRange(params: SelectionRangeParams): CompletableFuture[JavaList[SelectionRange]] =
      onSource("selectionRange", params.getTextDocument, JavaList.of[SelectionRange]()) { (s, path) =>
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

    /** Formats a whole project source using the editor's indentation options. */
    override def formatting(params: DocumentFormattingParams): CompletableFuture[java.util.List[? <: TextEdit]] =
      onEditsRequest("formatting", params.getTextDocument, java.util.List.of[TextEdit]()) { (s, path) =>
        textEdits(s.formatting(path, None, params.getOptions.getTabSize, !params.getOptions.isInsertSpaces))
      }

    /** Formats only the requested range using the editor's indentation options. */
    override def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[java.util.List[? <: TextEdit]] =
      onEditsRequest("rangeFormatting", params.getTextDocument, java.util.List.of[TextEdit]()) { (s, path) =>
        val range = params.getRange
        val options = params.getOptions
        textEdits(s.formatting(path, Some(lineColumn(range.getStart) -> lineColumn(range.getEnd)), options.getTabSize,
          !options.isInsertSpaces))
      }

    /** Returns declaration locations for the symbol at the requested position. */
    override def definition(params: DefinitionParams)
        : CompletableFuture[LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
      onSource("definition", params.getTextDocument, locations(Seq.empty)) { (s, path) =>
        locations(s.definition(path, lineColumn(params.getPosition)).toSeq)
      }

    /** Returns type-declaration locations for the symbol at the requested position. */
    override def typeDefinition(params: TypeDefinitionParams)
        : CompletableFuture[LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
      onSource("typeDefinition", params.getTextDocument, locations(Seq.empty)) { (s, path) =>
        locations(s.typeDefinition(path, lineColumn(params.getPosition)))
      }

    /** Returns the source locations that reference the symbol at the requested position. */
    override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
      onSource("references", params.getTextDocument, java.util.List.of[Location]()) { (s, path) =>
        s.references(path, lineColumn(params.getPosition), params.getContext.isIncludeDeclaration).map(location).asJava
      }

    /** Returns source spans for the implementations of the symbol at the requested position. */
    override def implementation(params: ImplementationParams)
        : CompletableFuture[LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
      onSource("implementation", params.getTextDocument, spanLocations(Seq.empty)) { (s, path) =>
        spanLocations(s.implementations(path, lineColumn(params.getPosition)))
      }

    /** Returns applicable code actions, with versioned edits for actions that can be applied immediately. */
    override def codeAction(params: CodeActionParams): CompletableFuture[java.util.List[LspEither[Command, CodeAction]]] =
      onEditsRequest("codeAction", params.getTextDocument, java.util.List.of[LspEither[Command, CodeAction]]()) { (s, path) =>
        val requested = Option(params.getContext.getOnly).map(_.asScala.toSeq).getOrElse(Seq.empty)
        s.codeActions(path, lineColumn(params.getRange.getStart), lineColumn(params.getRange.getEnd), requested)
          .map { (choice, changes) =>
            val action = new CodeAction(choice.title)
            action.setKind(choice.kind)
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

    /** Returns the symbol-name range that a following rename request may edit. */
    override def prepareRename(params: PrepareRenameParams)
        : CompletableFuture[LspEither3[Range, PrepareRenameResult, PrepareRenameDefaultBehavior]] =
      onSource("prepareRename", params.getTextDocument, null) { (s, path) =>
        LspEither3.forFirst[Range, PrepareRenameResult, PrepareRenameDefaultBehavior](range(s.prepareRename(path, lineColumn(params.getPosition))))
      }

    /** Returns workspace edits for a rename, cancelling if the source changes while they are computed. */
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

    /** Returns occurrences of the symbol at the position, marking writes separately from reads. */
    override def documentHighlight(params: DocumentHighlightParams): CompletableFuture[java.util.List[? <: DocumentHighlight]] =
      onSource("documentHighlight", params.getTextDocument, java.util.List.of[DocumentHighlight]()) { (s, path) =>
        s.highlights(path, lineColumn(params.getPosition)).map { o =>
          new DocumentHighlight(range(o.range), if (o.write) DocumentHighlightKind.Write else DocumentHighlightKind.Read)
        }.asJava
      }

    /** Returns the nested declarations displayed in the document outline. */
    override def documentSymbol(params: DocumentSymbolParams)
        : CompletableFuture[java.util.List[LspEither[SymbolInformation, DocumentSymbol]]] =
      onSource("documentSymbol", params.getTextDocument, java.util.List.of[LspEither[SymbolInformation, DocumentSymbol]]()) { (s, path) =>
        /** Converts an outline declaration and its nested children to LSP document symbols. */
        def symbol(o: OutlineSymbol): DocumentSymbol = {
          val converted = new DocumentSymbol(o.name, lspSymbolKind(o.kind), range(o.range), range(o.nameRange), o.detail)
          converted.setChildren(o.children.map(symbol).asJava)
          converted
        }
        s.outline(path).map(o => LspEither.forRight[SymbolInformation, DocumentSymbol](symbol(o))).asJava
      }

    /** Returns the full semantic-token stream for a project source. */
    override def semanticTokensFull(params: SemanticTokensParams): CompletableFuture[SemanticTokens] =
      onSource("semanticTokens", params.getTextDocument, new SemanticTokens(java.util.List.of[Integer]())) { (s, path) =>
        val data = SemanticColoring.encode(s.semanticTokens(path))
        new SemanticTokens(data.map(Integer.valueOf).asJava)
      }

    /** Returns foldable code, comments, imports and regions in a project source. */
    override def foldingRange(params: FoldingRangeRequestParams): CompletableFuture[java.util.List[FoldingRange]] =
      onSource("foldingRange", params.getTextDocument, java.util.List.of[FoldingRange]()) { (s, path) =>
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
          if (session.join().isSource(path)) session.join().problems(path) else Seq.empty
        }
        if (isCurrent(path, version))
          client.publishDiagnostics(new PublishDiagnosticsParams(path.toUri.toString, problems.map(diagnostic).asJava, version))
      } catch {
        // A later change cancelled this computation; that change scheduled its own.
        case _: ProcessCanceledException => ()
        case NonFatal(e) => HostLog.error(s"problems of $path failed", e)
      }
    }

  /** Converts a host diagnostic to LSP severity, range and source fields. */
  private def diagnostic(problem: FileProblem): org.eclipse.lsp4j.Diagnostic = {
    val converted = new org.eclipse.lsp4j.Diagnostic(new Range(position(problem.start), position(problem.end)), problem.message)
    converted.setSeverity(problem.severity match {
      case ProblemSeverity.Error => DiagnosticSeverity.Error
      case ProblemSeverity.Warning => DiagnosticSeverity.Warning
      case ProblemSeverity.Information => DiagnosticSeverity.Information
    })
    converted.setSource(problem.source)
    converted
  }

  /** Converts the LSP's zero-based UTF-16 position to the session's equivalent coordinate. */
  private def lineColumn(p: Position): LineColumn = LineColumn(p.getLine, p.getCharacter)

  /** Converts a session line and UTF-16 column to an LSP position. */
  private def position(at: LineColumn): Position = new Position(at.line, at.column)

  /** Converts a pair of session positions to an LSP range. */
  private def range(span: (LineColumn, LineColumn)): Range = new Range(position(span._1), position(span._2))

  /** Converts a project source span to a URI-backed LSP location. */
  private def location(span: SourceSpan): Location = new Location(span.path.toUri.toString, range(span.range))

  /** Converts a session text change to an LSP text edit. */
  private def textEdit(change: TextChange): TextEdit = new TextEdit(range(change.start -> change.end), change.text)

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
    item.setTextEdit(LspEither.forLeft(new TextEdit(range(choice.start -> choice.end), suggestion.lookup)))
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
          show.setSelection(range(followUp.caret -> followUp.caret))
          show.setTakeFocus(true)
          client.showDocument(show)
        }
      }
    }

  // ---- Workspace -------------------------------------------------------------------------------------------------

  /** Workspace-level commands, symbol queries and build/source watch notifications. */
  private object Workspace extends LspServices.Workspace {
    /** Configuration changes are not applied; module settings come from the imported build. */
    override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()

    /** Applies a completion insert handler or rejects an unknown command as invalid parameters. */
    override def executeCommand(params: ExecuteCommandParams): CompletableFuture[AnyRef] = params.getCommand match {
      case CompleteInsertion =>
        // lsp4j hands the arguments over as JSON: the ones completionItem gave.
        val arguments = params.getArguments.asScala.toSeq.collect { case json: JsonPrimitive => json }
        if (arguments.size != 6) CompletableFuture.failedFuture(requestError(ResponseErrorCode.InvalidParams, s"$CompleteInsertion: $arguments"))
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

    /** Searches project-wide symbols matching the client's query. */
    override def symbol(params: WorkspaceSymbolParams): CompletableFuture[LspEither[java.util.List[? <: SymbolInformation], java.util.List[? <: WorkspaceSymbol]]] =
      onDocuments("workspaceSymbol") { s =>
        val symbols = s.workspaceSymbols(params.getQuery).map { found =>
          val symbol = new WorkspaceSymbol(found.name, lspSymbolKind(found.kind), LspEither.forLeft[Location, WorkspaceSymbolLocation](location(found.location)))
          found.container.foreach(symbol.setContainerName)
          symbol
        }
        LspEither.forRight[java.util.List[? <: SymbolInformation], java.util.List[? <: WorkspaceSymbol]](symbols.asJava)
      }

    /** Reimports after build or source-file changes, then refreshes diagnostics for tracked files. */
    override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = onDocuments("build change") { s =>
      val paths = params.getChanges.asScala.toSeq.map(_.getUri).filter(_.startsWith("file:")).map(pathOf)
      val buildChanged = paths.exists(BuildDefinition.isPart)
      HostLog.info(s"${if (buildChanged) "build definition changed" else "source files created or deleted"}: ${paths.mkString(", ")}")
      importAgain(s, reload = buildChanged)
      versions.keySet.forEach(scheduleDiagnostics)
    }
  }
}

/** Replays insertion after the client has inserted a completion item's lookup text. */
private val CompleteInsertion = "scalaPsiHost.completeInsertion"

/** Which files define an sbt build: the client watches them, and a change to one reloads the build. */
private object BuildDefinition {
  val SbtFiles = "**/*.sbt"
  val ProjectFiles = "**/project/*.scala"
  val BuildProperties = "**/project/build.properties"

  /** Whether `path` can change sbt's build definition and therefore requires a BSP workspace reload. */
  def isPart(path: Path): Boolean = {
    val name = path.getFileName.toString
    val inProject = Option(path.getParent).exists(_.getFileName.toString == "project")
    name.endsWith(".sbt") || (inProject && (name.endsWith(".scala") || name == "build.properties"))
  }
}

/** Latencies by request kind: counts and maxima since the server started, percentiles over the latest requests. */
private final class RequestStats {
  // Enough recent requests of a kind for stable percentiles, few enough that memory stays flat.
  private val Window = 512

  /** Aggregate count, recent-window percentiles and lifetime maximum for one request kind. */
  final case class Summary(count: Long, p50: Long, p95: Long, max: Long)

  /** Retains one request kind's lifetime totals and bounded latency window. */
  private final class Kind {
    /** Number of requests recorded since startup. */
    var count = 0L
    /** Longest request duration since startup. */
    var max = 0L
    /** Durations retained for percentile summaries. */
    val recent = new java.util.ArrayDeque[Long]()
  }

  private val kinds = new java.util.TreeMap[String, Kind]()

  /** Adds one duration to the kind's lifetime totals and bounded percentile window. */
  def record(kind: String, ms: Long): Unit = kinds.synchronized {
    val stats = kinds.computeIfAbsent(kind, _ => new Kind)
    stats.count += 1
    stats.max = math.max(stats.max, ms)
    stats.recent.addLast(ms)
    if (stats.recent.size > Window) stats.recent.removeFirst()
  }

  /** Returns per-kind summaries in stable name order. */
  def summary: Seq[(String, Summary)] = kinds.synchronized {
    kinds.asScala.toSeq.map { (name, stats) =>
      val sorted = stats.recent.asScala.toVector.sorted
      /** Selects the existing window's upper quantile rank, clamped to its final sample. */
      def percentile(p: Double): Long = sorted(math.min(sorted.size - 1, (p * sorted.size).toInt))
      name -> Summary(stats.count, percentile(0.5), percentile(0.95), stats.max)
    }
  }
}
