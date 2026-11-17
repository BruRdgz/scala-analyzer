package scalaanalyzer.mcp

import com.google.gson.{Gson, GsonBuilder, JsonArray, JsonElement, JsonNull, JsonObject, JsonParseException, JsonParser, JsonPrimitive}
import com.intellij.openapi.progress.ProcessCanceledException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

import scalaanalyzer.ScalaPsiHost
import scalaanalyzer.lsp.Main
import scalaanalyzer.platform.{HostLock, HostLog}
import scalaanalyzer.workspace.ProjectSession

import java.io.{BufferedReader, BufferedWriter, IOException, InputStream, InputStreamReader, OutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, Executors, ThreadFactory, TimeUnit}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Serves MCP (the Model Context Protocol, revision 2025-11-25 with its legacy `initialize` lifecycle) on a pair of
 * streams, over the workspace's imported build session, until the client closes its input.
 *
 * A message is one line of JSON, without line breaks inside it (MCP's stdio framing: not LSP's `Content-Length`
 * headers). Requests are answered as soon as their answers are ready. `ping`, `tools/list`, `project_status` and
 * cancellations run on the reading thread, so that they are answered while a tool runs; the other tools run one at a
 * time on a thread of their own, in the order they arrive, so that the disk scan before each is consistent with it.
 *
 * The project opens on a thread of its own as soon as the server serves, without waiting for `initialize`. A tool
 * that needs it answers with an error until it is ready. Before each such tool, the server compares the source files
 * and the build definition with how it last saw them on disk (see [[DiskScan]]): the build is imported again when
 * the changes require it, and the sources edited on disk take their new text. What the server does is logged
 * ([[scalaanalyzer.platform.HostLog]]) and nothing but protocol messages is written to the output.
 *
 * @param host process-owned PSI host used by the workspace session
 * @param root workspace directory, which holds the BSP connection files
 * @param input stream that carries the client's messages
 * @param output stream that carries the server's messages
 * @param exitProcess ends the server's process with the given code when the client closes its input; nothing for a
 *                    server embedded in another process
 * @param logFile where the log is written, for messages that point to it
 */
final class McpServer(
  host: ScalaPsiHost,
  root: Path,
  input: InputStream,
  output: OutputStream,
  exitProcess: Int => Unit = _ => (),
  logFile: Option[Path] = None,
) {
  import McpServer.*

  /** Allows a short BSP shutdown handshake, but never waits for a full build import when the client has gone. */
  private val EndingDeadlineSeconds = 5L

  /** Reads the client's messages, one per line. */
  private val reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))

  /** Writes the server's messages; guarded by its own monitor, as several threads answer. */
  private val writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))

  /** Writes JSON on one line. A null member is kept: a response to a message without an id must carry `"id": null`. */
  private val gson: Gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create()

  /** The latest reported project state. */
  @volatile private var state = State(Phase.Starting, s"opening $root")

  /** Completes with the project session once it is open, or exceptionally if opening failed. */
  private val session = new CompletableFuture[ProjectSession]

  /** What changed on disk since the tools last ran; used by one thread at a time (see `followChangesOnDisk`). */
  private val scan = new DiskScan(root)

  /** The cancellation of each tool call that is waiting or running, by the request id the client gave it. */
  private val pending = new ConcurrentHashMap[JsonElement, HostLock.RequestCancellation]()

  /** Runs the tools that read the project, one at a time. */
  private val tools = Executors.newSingleThreadExecutor(daemon("scala-analyzer-mcp-tools"))

  /** Creates a daemon-thread factory for server work that must not keep the process alive. */
  private def daemon(name: String): ThreadFactory = runnable => {
    val thread = new Thread(runnable, name)
    thread.setDaemon(true)
    thread
  }

  /**
   * Opens the project and serves the client's messages until its input ends, then closes the project and exits.
   *
   * Blocks the calling thread. A failed read is logged as a warning and ends the service like the end of the input.
   * The session is closed within a deadline, and `exitProcess(0)` runs however the service ends.
   */
  def serve(): Unit = {
    HostLog.info(s"serving MCP for $root")
    openProjectInBackground()

    try reader.lines().iterator().asScala.filterNot(_.isBlank).foreach(handle)
    catch { case NonFatal(e) => HostLog.warn("client transport failed", e) }
    finally exit()
  }

  /** Opens the project on a thread of its own, keeping the state and the session as the opening goes. */
  private def openProjectInBackground(): Unit = {
    val opening = daemon("scala-analyzer-mcp-opening").newThread { () =>
      val started = System.nanoTime()
      try {
        val opened = ProjectSession.open(host, root, progress = message => setState(Phase.Importing, message))

        // The files may have been edited during the import: the session has the text they had then.
        scan.restart(opened)
        followEverySource(opened)
        HostLog.info(s"ready in ${(System.nanoTime() - started) / 1_000_000} ms: ${counts(opened)}")
        session.complete(opened)
        setState(Phase.Ready, counts(opened))
      } catch {
        case e: Throwable =>
          HostLog.error(s"opening the project at $root failed", e)
          setState(Phase.Failed, describe(e))
          session.completeExceptionally(e)
      }
    }

    opening.start()
  }

  /** Reports a new project state. */
  private def setState(phase: Phase, message: String): Unit = state = State(phase, message)

  /** Describes the module and source counts of the session's last completed import. */
  private def counts(opened: ProjectSession): String = {
    val snapshot = opened.snapshot

    s"${snapshot.moduleCount} modules, ${snapshot.sourceFileCount} source files"
  }

  /** The message of a failure, or its class when it has none. */
  private def describe(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  /** Stops the tools and closes the project within the deadline, then ends the process. */
  private def exit(): Unit = {
    HostLog.info("exiting: the client closed its input")
    tools.shutdownNow()

    val closed = session.handleAsync[AnyRef]((opened, error) => {
      if (error == null) opened.close()
      null
    }, runnable => daemon("scala-analyzer-ending").newThread(runnable).start())

    try closed.orTimeout(EndingDeadlineSeconds, TimeUnit.SECONDS).get()
    catch {
      case e: InterruptedException =>
        HostLog.warn("interrupted while closing the project", e)
        Thread.currentThread().interrupt()
      case NonFatal(e) => HostLog.warn("could not close the project before exiting", e)
    } finally exitProcess(0)
  }

  /** Parses one line and hands the message to `dispatch`; a line that is not a JSON-RPC message is answered as an error. */
  private def handle(line: String): Unit = {
    val parsed =
      try Some(JsonParser.parseString(line))
      catch { case _: JsonParseException => None }

    parsed match {
      case None => fail(JsonNull.INSTANCE, ResponseErrorCode.ParseError, "Parse error: the message is not JSON")
      case Some(message: JsonObject) => dispatch(message)
      case Some(_) =>
        fail(JsonNull.INSTANCE, ResponseErrorCode.InvalidRequest, "Invalid Request: a message is one JSON object")
    }
  }

  /** Runs a request or a notification; a request that fails unexpectedly is answered with an internal error. */
  private def dispatch(message: JsonObject): Unit = {
    val id = Option(message.get("id"))
    val method = McpTools.stringMember(message, "method")
    val params = McpTools.objectMember(message, "params")

    try (id, method) match {
      case (_, None) =>
        fail(id.getOrElse(JsonNull.INSTANCE), ResponseErrorCode.InvalidRequest, "Invalid Request: `method` is a string")
      case (None, Some(name)) => handleNotification(name, params)
      case (Some(requestId), Some(name)) => handleRequest(requestId, name, params)
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"${method.getOrElse("a message")} failed", e)
        id.foreach(fail(_, ResponseErrorCode.InternalError, s"Internal error: ${describe(e)}"))
    }
  }

  /** Answers a request, or hands a tool call to the tools thread, which answers it. */
  private def handleRequest(id: JsonElement, method: String, params: JsonObject): Unit = method match {
    case "initialize" => reply(id, initializeResult(params))
    case "ping" => reply(id, new JsonObject)
    case "tools/list" => reply(id, toolList)
    case "tools/call" => callTool(id, params)
    // `server/discover`, which newer clients try first, is among these: they fall back to `initialize` on the error.
    case _ => fail(id, ResponseErrorCode.MethodNotFound, s"Method not found: $method")
  }

  /** Handles a notification, which has no answer: only a cancellation matters, and the rest are ignored. */
  private def handleNotification(method: String, params: JsonObject): Unit =
    if (method == "notifications/cancelled") {
      Option(params.get("requestId")).flatMap(id => Option(pending.get(id))).foreach { cancellation =>
        HostLog.info(s"cancelled by the client: request ${params.get("requestId")}")
        cancellation.cancel()
      }
    }

  /** Negotiates the protocol revision and describes the server. */
  private def initializeResult(params: JsonObject): JsonObject = {
    val version = McpTools.stringMember(params, "protocolVersion")
      .filter(SupportedVersions.contains)
      .getOrElse(SupportedVersions.head)

    HostLog.info(s"initialized with protocol version $version")
    objectOf(
      "protocolVersion" -> new JsonPrimitive(version),
      "capabilities" -> objectOf("tools" -> objectOf("listChanged" -> new JsonPrimitive(false))),
      "serverInfo" -> objectOf("name" -> new JsonPrimitive("scala-analyzer"), "version" -> new JsonPrimitive(Main.version)),
      "instructions" -> new JsonPrimitive(Instructions),
    )
  }

  /** Describes every tool, as `tools/list` answers. */
  private def toolList: JsonObject = {
    val described = new JsonArray
    McpTools.All.foreach { tool =>
      described.add(objectOf(
        "name" -> new JsonPrimitive(tool.name),
        "title" -> new JsonPrimitive(tool.title),
        "description" -> new JsonPrimitive(tool.description),
        "inputSchema" -> tool.inputSchema,
      ))
    }

    objectOf("tools" -> described)
  }

  /** Answers `project_status` at once and queues any other tool for the tools thread; an unknown tool is an error. */
  private def callTool(id: JsonElement, params: JsonObject): Unit = {
    val name = McpTools.stringMember(params, "name").getOrElse("")

    McpTools.All.find(_.name == name) match {
      case None => fail(id, ResponseErrorCode.InvalidParams, s"Unknown tool: $name")
      case Some(McpTools.ProjectStatus) => reply(id, toolResult(projectStatus, isError = false))
      case Some(tool) =>
        val call = ToolCall(id, tool, McpTools.objectMember(params, "arguments"), new HostLock.RequestCancellation)

        pending.put(id, call.cancellation)
        tools.execute(() => run(call))
    }
  }

  /** Describes the project state, as `project_status` answers. */
  private def projectStatus: String = {
    val current = state
    val opened = Option.when(session.isDone && !session.isCompletedExceptionally)(session.join())
    val lines = Seq(
      Some(s"state: ${current.phase.toString.toLowerCase}"),
      Some(s"message: ${current.message}"),
      Some(s"root: $root"),
      opened.map(s => s"modules: ${s.snapshot.moduleCount}"),
      opened.map(s => s"source files: ${s.snapshot.sourceFileCount}"),
      logFile.map(file => s"log file: $file"),
      Some(s"server version: ${Main.version}"),
    )

    lines.flatten.mkString("\n")
  }

  /** Runs a queued tool call and answers it, unless the client cancelled it: MCP answers no cancelled request. */
  private def run(call: ToolCall): Unit =
    try HostLock.cancelledBy(call.cancellation) {
      if (!HostLock.isRequestCancelled) {
        val result = runningResult(call)

        if (!HostLock.isRequestCancelled) reply(call.id, result)
      }
    }
    finally pending.remove(call.id)

  /** Runs a tool call into a result: a failure the model can act on, or an unexpected one, is an error result. */
  private def runningResult(call: ToolCall): JsonObject =
    try toolResult(callProject(call), isError = false)
    catch {
      case e: McpTools.ToolFailure => toolResult(e.getMessage, isError = true)
      // A write to the host cancels the queries that run, and so does the client's cancellation, whose call gets no answer.
      case _: ProcessCanceledException =>
        toolResult(s"${call.tool.name} was cancelled by a change of the project; retry.", isError = true)
      case e @ (NonFatal(_) | _: LinkageError) =>
        HostLog.error(s"${call.tool.name} failed", e)
        val seeLog = logFile.fold("")(file => s" See the log: $file")
        toolResult(s"${call.tool.name} failed unexpectedly: ${describe(e)}.$seeLog", isError = true)
    }

  /** Runs a tool that reads the project, after bringing the project up to date with the disk. */
  private def callProject(call: ToolCall): String = {
    val current = state

    current.phase match {
      case Phase.Failed => throw new McpTools.ToolFailure(s"The project could not be opened: ${current.message}.")
      case Phase.Starting | Phase.Importing =>
        throw new McpTools.ToolFailure(
          s"The project is still importing (${current.message}); call project_status and retry.")
      case Phase.Ready => ()
    }

    val opened = session.join()
    followChangesOnDisk(opened)
    McpTools.call(call.tool, opened, root, call.arguments)
  }

  /**
   * Brings the session up to date with the disk: imports the build again when the changes require it, and
   * otherwise gives the sources that changed their new text.
   *
   * A failed import is reported to the tool call and leaves the changes unaccepted, so the next call tries again.
   */
  private def followChangesOnDisk(opened: ProjectSession): Unit = {
    val observed = scan.observe(opened)

    opened.importNeeded(root, observed.changes) match {
      case Some(reason) =>
        HostLog.info(s"${reason.description}: ${observed.changes.map(_.path).mkString(", ")}")
        importAgain(opened, reason)
        scan.restart(opened)
        followEverySource(opened)
      case None =>
        observed.changes
          .filter(_.kind == ProjectSession.DiskChange.Kind.Changed)
          .map(_.path)
          .filter(opened.isSource)
          .foreach(path => opened.followDisk(path))
        scan.accept(observed)
    }
  }

  /**
   * Imports the build again, as the project state.
   *
   * The state is `importing` meanwhile and `ready` afterwards. A failure is rethrown as a tool failure after the
   * state is set: `failed` when the session is no longer usable, otherwise `ready` with a message saying that the last
   * imported snapshot remains active.
   */
  private def importAgain(opened: ProjectSession, reason: ProjectSession.ImportReason): Unit = {
    setState(Phase.Importing, "importing the build again")
    try {
      opened.importBuild(reason.reload, message => setState(Phase.Importing, message))
      setState(Phase.Ready, counts(opened))
    } catch {
      case error: Throwable =>
        HostLog.error("importing the build again failed", error)
        val detail = describe(error)
        if (!opened.isUsable) setState(Phase.Failed, opened.unavailableMessage.getOrElse(detail))
        else setState(Phase.Ready,
          s"reimport failed before host updates; the last imported snapshot remains active and can be retried: $detail")
        throw new McpTools.ToolFailure(
          s"The build could not be imported again after files changed on disk: $detail. " +
            "Correct the build or the sources and retry.")
    }
  }

  /** Gives every source its text on disk; a source whose buffer already has it is left alone. */
  private def followEverySource(opened: ProjectSession): Unit =
    opened.sourceFiles.foreach(path => opened.followDisk(path))

  /** Answers a request with its result. */
  private def reply(id: JsonElement, result: JsonElement): Unit =
    send(objectOf("jsonrpc" -> new JsonPrimitive("2.0"), "id" -> id, "result" -> result))

  /** Answers a request with a protocol error. */
  private def fail(id: JsonElement, code: ResponseErrorCode, message: String): Unit = {
    val error = objectOf("code" -> new JsonPrimitive(code.getValue), "message" -> new JsonPrimitive(message))

    send(objectOf("jsonrpc" -> new JsonPrimitive("2.0"), "id" -> id, "error" -> error))
  }

  /** Writes one message as a line; a client that is gone is logged, its input then ends the service. */
  private def send(message: JsonObject): Unit = writer.synchronized {
    try {
      writer.write(gson.toJson(message))
      writer.write("\n")
      writer.flush()
    } catch { case e: IOException => HostLog.warn("could not write to the client", e) }
  }
}

/** The protocol revisions the server speaks, the guidance it gives the model and the shapes of its messages. */
private object McpServer {
  /** The revisions the server implements, the latest first: a client asking for another gets the latest. */
  private val SupportedVersions = Seq("2025-11-25", "2025-06-18")

  /** What the model is told when it connects, on top of each tool's own description. */
  private val Instructions =
    "scala-analyzer answers questions about a Scala project from its build and the IntelliJ Scala plugin's analysis. " +
      "Its navigation tools take a target in one of two ways: `symbol`, a name optionally qualified by package or " +
      "enclosing type (find_symbol searches by partial name), or `file`, `line` and `text`, the identifier as written " +
      "on that line. Lines and columns in their answers count from 1. The tools only read: edit files as usual, and " +
      "the server follows what is saved on disk. While the project imports, call project_status until it is ready."

  /** Where the project is in opening and importing. */
  private enum Phase { case Starting, Importing, Ready, Failed }

  /**
   * The project state as `project_status` reports it.
   *
   * @param phase where the project is in opening and importing
   * @param message the step in progress, the counts when ready, or the failure
   */
  private final case class State(phase: Phase, message: String)

  /**
   * A tool call on its way to the tools thread.
   *
   * @param id the request id the client gave the call, which its answer repeats
   * @param tool the tool called
   * @param arguments the call's arguments; empty when the client gave none
   * @param cancellation cancels the call when the client asks, whether it waits or runs
   */
  private final case class ToolCall(
    id: JsonElement,
    tool: McpTools.Tool,
    arguments: JsonObject,
    cancellation: HostLock.RequestCancellation,
  )

  /** The JSON object with these members, in order. */
  private def objectOf(members: (String, JsonElement)*): JsonObject = {
    val result = new JsonObject

    members.foreach((name, value) => result.add(name, value))
    result
  }

  /** The result of a tool call: a text for the model, flagged when it reports a failure. */
  private def toolResult(text: String, isError: Boolean): JsonObject = {
    val content = new JsonArray

    content.add(objectOf("type" -> new JsonPrimitive("text"), "text" -> new JsonPrimitive(text)))
    objectOf("content" -> content, "isError" -> new JsonPrimitive(isError))
  }
}
