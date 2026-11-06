package scalaanalyzer.oracle

import com.google.gson.GsonBuilder
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

import scalaanalyzer.{HostConfig, ScalaPsiHost}
import scalaanalyzer.lsp.{HostProtocol, LocalConnection, ScalaLanguageServer}

import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.{Files, Path}
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, ConcurrentLinkedQueue, LinkedBlockingQueue, TimeUnit}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Request latency as an editor sees it while the user types: the language server and a client connected through
 * JSON-RPC streams, so requests queue behind each other and a client-side cancellation sends `$/cancelRequest`. Each
 * burst types a statement on a new line in the middle of a file, one character per keystroke, so the file goes through
 * the unresolved and unparsable states real typing does. As VS Code does, every keystroke cancels the requests still
 * pending from the previous one, sends the change, then asks for semantic tokens, highlights, code actions, inlay
 * hints, symbols and folding ranges. Right after the last keystroke it asks for a hover and a completion, which queue
 * behind that keystroke's requests (the latency felt after typing), waits for every answer and reverts the file. The
 * project's files are only read; edits stay in memory. Before typing, the project's largest sources are opened one by
 * one with the same requests, which find their caches empty.
 *
 * Export `Test/fullClasspath` and `Test/javaOptions` with sbt first, then run Java directly with those options and
 * that classpath: sbt, the benchmark and the project's build server together can exhaust memory.
 * Usage: `java <options> -cp <classpath> scalaanalyzer.oracle.TypingBenchmark <project dir> <report.md>
 * --file=<path in the project> [--bursts=N] [--interval-ms=N]`. Defaults: 10 bursts, keystrokes 80 ms apart. The
 * server opens the project over the build connection it finds on its own.
 */
object TypingBenchmark {
  private val ReadyDeadlineMinutes = 10L
  private val AnswerDeadlineMinutes = 5L
  // Lets the server finish the work of the reverted burst before the next one is measured.
  private val PauseBetweenBurstsMs = 1000L
  // Valid Scala 2 and 3 wherever a statement is: resolves, calls and a lambda, typed one character at a time.
  private val Typed = "val probe = List(1, 2).map(_ + 1).sum"
  private val MemberSelection = "\\.([A-Za-z_][A-Za-z0-9_]{3,})".r
  private val KeystrokeKinds = Seq("semanticTokens", "documentHighlight", "codeAction", "inlayHint", "documentSymbol", "foldingRange")
  private val AfterTypingKinds = Seq("hover after typing", "completion after typing")
  // The largest source files, opened cold before the typed one: where a file's first analysis takes longest.
  private val ColdFiles = 6

  private enum Outcome {
    case Answered(ms: Long)
    case CancelledByUs
    case RejectedByServer
    case Failed(message: String)
  }

  private final case class Sample(kind: String, outcome: Outcome)

  /**
   * A client that records the server's lifecycle states and ignores everything else it sends. A proxy, because a Scala
   * class implementing `LanguageClient` makes lsp4j see its RPC methods twice (see `LspServices`).
   */
  private def stateClient(states: LinkedBlockingQueue[HostProtocol.ServerState]): HostProtocol.Client =
    Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[HostProtocol.Client]), (_, method, args) =>
      args match {
        case Array(state: HostProtocol.ServerState) => states.add(state); null
        case _ if method.getReturnType == classOf[CompletableFuture[?]] => CompletableFuture.completedFuture(null)
        case _ => null
      }).asInstanceOf[HostProtocol.Client]

  /** What the benchmark sends for one document, and how each request ended. */
  private final class Requests(server: HostProtocol.LanguageServerWithStatus, document: TextDocumentIdentifier, endOfFile: Position) {
    val samples = new ConcurrentLinkedQueue[Sample]()
    private var version = 1

    def send[T](kind: String)(call: => CompletableFuture[T]): CompletableFuture[T] = {
      val started = System.nanoTime()
      val future = call
      future.whenComplete((_, error) => samples.add(Sample(kind, outcome(error, started))))
      future
    }

    private def outcome(error: Throwable, started: Long): Outcome = error match {
      case null => Outcome.Answered((System.nanoTime() - started) / 1_000_000)
      case _: CancellationException => Outcome.CancelledByUs
      case failure: CompletionException => outcome(failure.getCause, started)
      case failure: ResponseErrorException if failure.getResponseError.getCode == ResponseErrorCode.RequestCancelled.getValue =>
        Outcome.RejectedByServer
      case failure => Outcome.Failed(s"${failure.getClass.getSimpleName}: ${failure.getMessage}")
    }

    def cancelPending(pending: Seq[CompletableFuture[?]]): Unit =
      pending.filterNot(_.isDone).foreach(_.cancel(true))

    def awaitAll(pending: Seq[CompletableFuture[?]]): Unit =
      CompletableFuture.allOf(pending*).handle((_, _) => ()).get(AnswerDeadlineMinutes, TimeUnit.MINUTES)

    def open(text: String): Unit =
      server.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(document.getUri, "scala", version, text)))

    /** Replaces `range` with `text` as the next version of the document. */
    def change(range: Range, text: String): Unit = {
      version += 1
      val edit = new TextDocumentContentChangeEvent(range, text)
      server.getTextDocumentService.didChange(
        new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(document.getUri, version), java.util.List.of(edit)))
    }

    /** What the editor asks for after a keystroke with the caret at `caret`. */
    def afterKeystroke(caret: Position): Seq[CompletableFuture[?]] = {
      val text = server.getTextDocumentService
      val wholeFile = new Range(new Position(0, 0), endOfFile)
      Seq(
        send("semanticTokens")(text.semanticTokensFull(new SemanticTokensParams(document))),
        send("documentHighlight")(text.documentHighlight(new DocumentHighlightParams(document, caret))),
        send("codeAction")(text.codeAction(new CodeActionParams(document, new Range(caret, caret), new CodeActionContext(java.util.List.of())))),
        send("inlayHint")(text.inlayHint(new InlayHintParams(document, wholeFile))),
        send("documentSymbol")(text.documentSymbol(new DocumentSymbolParams(document))),
        send("foldingRange")(text.foldingRange(new FoldingRangeRequestParams(document))),
      )
    }

    def hover(at: Position): CompletableFuture[?] =
      send("hover after typing")(server.getTextDocumentService.hover(new HoverParams(document, at)))

    def completion(at: Position): CompletableFuture[?] =
      send("completion after typing")(server.getTextDocumentService.completion(new CompletionParams(document, at)))
  }

  def main(args: Array[String]): Unit = {
    val (Seq(projectArg, reportPath), flags) = args.toSeq.partition(!_.startsWith("--"))
    def flag(name: String) = flags.collectFirst { case f if f.startsWith(s"--$name=") => f.stripPrefix(s"--$name=") }
    val bursts = flag("bursts").map(_.toInt).getOrElse(10)
    val intervalMs = flag("interval-ms").map(_.toLong).getOrElse(80L)
    val projectDir = Path.of(projectArg).toAbsolutePath
    val file = projectDir.resolve(flag("file").getOrElse(sys.error("--file=<path in the project> is required")))
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
    ))

    val states = new LinkedBlockingQueue[HostProtocol.ServerState]()
    val server = LocalConnection.connect(stateClient(states), new ScalaLanguageServer(host))
    val params = new InitializeParams
    params.setWorkspaceFolders(java.util.List.of(new WorkspaceFolder(projectDir.toUri.toString, projectDir.getFileName.toString)))
    params.setCapabilities(new ClientCapabilities)
    server.initialize(params).get(1, TimeUnit.MINUTES)
    server.initialized(new InitializedParams)
    val (_, openMs) = timed(awaitReady(states))

    val onOpen = largestSources(projectDir, file).flatMap(openCold(server, _))

    val text = Files.readString(file)
    val lines = text.split("\n", -1)
    val endOfFile = new Position(lines.length - 1, lines.last.length)
    val requests = new Requests(server, new TextDocumentIdentifier(file.toUri.toString), endOfFile)
    val (hoverAt, completionAt) = existingMember(lines)
    requests.open(text)
    // Not measured: the typed file's caches are filled first, as the cold files measure an empty start.
    requests.awaitAll(requests.afterKeystroke(hoverAt))
    requests.samples.clear()

    val burstMs = (0 until bursts).map { _ =>
      val (_, ms) = timed(burst(requests, statementAt(lines, hoverAt), intervalMs, hoverAt, completionAt))
      Thread.sleep(PauseBetweenBurstsMs)
      ms
    }
    val status = new GsonBuilder().setPrettyPrinting().create().toJson(server.status().get(1, TimeUnit.MINUTES))
    writeReport(Path.of(reportPath), projectDir, file, (bursts, Typed.length, intervalMs), openMs, onOpen, burstMs,
      requests.samples.asScala.toSeq, status)
    println(s"typing benchmark: report at $reportPath")
    // The platform keeps non-daemon threads running.
    sys.exit(0)
  }

  private def awaitReady(states: LinkedBlockingQueue[HostProtocol.ServerState]): Unit = {
    var state = ""
    while (state != "ready") {
      val next = states.poll(ReadyDeadlineMinutes, TimeUnit.MINUTES)
      require(next != null, s"the server never reported 'ready' (last state '$state')")
      require(next.state != "failed", s"the server failed to open the project: ${next.message}")
      state = next.state
    }
  }

  /** The project's largest Scala sources under a `src` directory other than `typed`, largest first. */
  private def largestSources(projectDir: Path, typed: Path): Seq[Path] =
    Using.resource(Files.walk(projectDir))(_.iterator.asScala.toSeq)
      .filter(path => path != typed && path.toString.endsWith(".scala") && path.toString.contains("/src/"))
      .sortBy(path => (-Files.size(path), path.toString)).take(ColdFiles)

  /** Opens `file` and sends the requests of one keystroke at its middle line, as an editor does on opening it. */
  private def openCold(server: HostProtocol.LanguageServerWithStatus, file: Path): Seq[Sample] = {
    val lines = Files.readAllLines(file).asScala
    val middle = lines.size / 2
    val caret = new Position(middle, lines.lift(middle).fold(0)(_.takeWhile(_.isWhitespace).length))
    val requests = new Requests(server, new TextDocumentIdentifier(file.toUri.toString), new Position(lines.size, 0))
    requests.open(Files.readString(file))
    requests.awaitAll(requests.afterKeystroke(caret))
    requests.samples.asScala.toSeq
  }

  /** A member selection (`.name`) from the middle of the file on: where hover and completion do real work. */
  private def existingMember(lines: Array[String]): (Position, Position) = {
    val found = lines.indices.drop(lines.length / 2).iterator.flatMap { l =>
      if (lines(l).trim.startsWith("//") || lines(l).trim.startsWith("*")) Iterator.empty
      else MemberSelection.findAllMatchIn(lines(l)).map(m => (l, m.start(1), m.end(1)))
    }.nextOption()
    val (line, start, end) = found.getOrElse(sys.error("no member selection in the second half of the file for hover and completion"))
    (new Position(line, (start + end) / 2), new Position(line, end))
  }

  /** The end of the hover's line, where a new line with the same indentation starts a statement. */
  private def statementAt(lines: Array[String], hoverAt: Position): (Position, String) = {
    val line = lines(hoverAt.getLine)
    (new Position(hoverAt.getLine, line.length), line.takeWhile(_.isWhitespace))
  }

  /**
   * Types [[Typed]] on a new line after `statement`, then reverts it. Hover and completion target lines before the new
   * one, so their positions stay valid.
   */
  private def burst(requests: Requests, statement: (Position, String), intervalMs: Long, hoverAt: Position, completionAt: Position): Unit = {
    val (lineEnd, indent) = statement
    requests.change(new Range(lineEnd, lineEnd), "\n" + indent)
    var caret = new Position(lineEnd.getLine + 1, indent.length)
    var pending = Seq.empty[CompletableFuture[?]]
    for (character <- Typed) {
      requests.cancelPending(pending)
      requests.change(new Range(caret, caret), character.toString)
      caret = new Position(caret.getLine, caret.getCharacter + 1)
      pending = requests.afterKeystroke(caret)
      Thread.sleep(intervalMs)
    }
    requests.awaitAll(pending :+ requests.hover(hoverAt) :+ requests.completion(completionAt))
    requests.change(new Range(lineEnd, caret), "")
  }

  private def writeReport(
    reportPath: Path, projectDir: Path, file: Path, shape: (Int, Int, Long), openMs: Long, onOpen: Seq[Sample],
    burstMs: Seq[Long], samples: Seq[Sample], status: String,
  ): Unit = {
    val (bursts, keystrokes, intervalMs) = shape
    def percentileOf(sorted: Seq[Long], p: Double) = sorted(math.min(sorted.size - 1, (p * sorted.size).toInt))
    def stats(values: Seq[Long]) =
      if (values.isEmpty) "| - | - | - |"
      else {
        val sorted = values.sorted
        s"| ${percentileOf(sorted, 0.5)} | ${percentileOf(sorted, 0.95)} | ${sorted.last} |"
      }
    val lines = Vector.newBuilder[String]
    lines += s"# Typing benchmark: ${projectDir.getFileName}"
    lines += ""
    lines += s"JDK ${System.getProperty("java.version")}, max heap ${Runtime.getRuntime.maxMemory / (1024 * 1024)} MB."
    lines += "Requests go through JSON-RPC to one server and its single request queue. Latency is client-side, from send to answer. " +
      "A request still pending at the next keystroke is cancelled with `$/cancelRequest`; the client cannot see whether the server answered it anyway."
    lines += ""
    lines += s"- project: $projectDir; file: ${projectDir.relativize(file)}"
    lines += s"- bursts: $bursts; keystrokes per burst: $keystrokes; interval: $intervalMs ms"
    lines += s"- waiting for the server to be ready: $openMs ms"
    lines += ""
    /** Answered, cancelled and failed counts of each kind, with the answered latencies. */
    def requestTable(title: String, kinds: Seq[String], of: Seq[Sample]): Unit = {
      lines += s"| $title | answered | cancelled by us | rejected by server (stale) | failed | p50 ms | p95 ms | max ms |"
      lines += "|---|---|---|---|---|---|---|---|"
      for (kind <- kinds) {
        val ofKind = of.filter(_.kind == kind).map(_.outcome)
        val answered = ofKind.collect { case Outcome.Answered(ms) => ms }
        val counts = Seq(answered.size, ofKind.count(_ == Outcome.CancelledByUs), ofKind.count(_ == Outcome.RejectedByServer),
          ofKind.count(_.isInstanceOf[Outcome.Failed]))
        lines += s"| $kind | ${counts.mkString(" | ")} ${stats(answered)}"
      }
      lines += ""
    }
    requestTable("While typing", KeystrokeKinds ++ AfterTypingKinds, samples)
    requestTable(s"First requests on ${onOpen.size / KeystrokeKinds.size} files opened cold", KeystrokeKinds, onOpen)
    lines += "| Burst, first keystroke to last answer | p50 ms | p95 ms | max ms |"
    lines += "|---|---|---|---|"
    lines += s"| ${burstMs.size} bursts ${stats(burstMs)}"
    lines += ""
    lines += "## Failures (first of each kind)"
    lines += ""
    val failures = (onOpen ++ samples).collect { case Sample(kind, Outcome.Failed(message)) => kind -> message }.distinct
    if (failures.isEmpty) lines += "None."
    else for ((kind, message) <- failures.groupBy(_._1).map((_, all) => all.head).toSeq) lines += s"- $kind: $message"
    lines += ""
    lines += "## Server status"
    lines += ""
    lines += "```json"
    lines += status
    lines += "```"
    Files.createDirectories(reportPath.toAbsolutePath.getParent)
    Files.write(reportPath, lines.result().asJava)
  }

  private def timed[T](body: => T): (T, Long) = {
    val start = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - start) / 1_000_000)
  }
}
