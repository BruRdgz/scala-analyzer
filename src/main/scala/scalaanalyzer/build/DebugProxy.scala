package scalaanalyzer.build

import com.google.gson.{JsonArray, JsonObject, JsonParser}

import scalaanalyzer.platform.HostLog

import java.io.{BufferedInputStream, ByteArrayOutputStream, IOException}
import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{InvalidPathException, Path}
import java.util.concurrent.{CompletableFuture, ExecutorService, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.reflect.NameTransformer
import scala.util.control.NonFatal

/**
 * A Debug Adapter Protocol proxy between the editor and the debug adapter, inside the host: the editor connects to
 * the proxy, the proxy connects to the adapter when the editor does and forwards every message. It closes three gaps
 * with the IDE's debugger that the adapter (java-debug) leaves open, by rewriting or answering what concerns them:
 * values the IDE shows as values (strings, the `scala.runtime.*Ref` holders of captured `var`s), collections listed
 * by their elements, and Smart Step Into (`stepInTargets`).
 *
 * Like the adapter's server, the proxy listens on the loopback address and takes one client; it ends with that
 * client's connection or with the adapter's.
 *
 * @param listener the socket the editor connects to
 * @param adapter the address of the adapter's server
 * @param stepTargets the calls of a source line the program can step into, as `DebugAdapter.start` documents it
 * @param executor the threads of the connection's readers and of the requests the proxy sends itself, shut down with
 *                 the proxy
 */
final class DebugProxy private (listener: ServerSocket, adapter: URI,
                                stepTargets: (Path, Int) => Seq[DebugAdapter.StepTarget],
                                executor: ExecutorService) extends AutoCloseable {
  /** The address of the proxy, `tcp://host:port`, which a DAP client connects to. */
  val uri: URI = URI.create(s"tcp://${listener.getInetAddress.getHostAddress}:${listener.getLocalPort}")

  private var session: Option[DebugProxy.Session] = None
  private var closed = false

  /** Waits for the editor, connects to the adapter, and then relays until either side ends. */
  private def serve(): Unit =
    try {
      val clientSocket = listener.accept()
      listener.close()
      val adapterSocket =
        try new Socket(adapter.getHost, adapter.getPort)
        catch {
          case error: IOException =>
            clientSocket.close()
            throw error
        }
      val created = new DebugProxy.Session(clientSocket, adapterSocket, executor, stepTargets)
      val stillOpen = synchronized {
        if (!closed) session = Some(created)
        !closed
      }

      if (stillOpen) created.relay() else created.close()
    } catch {
      case _: IOException if synchronized(closed) => ()
      case NonFatal(error) => HostLog.warn("debug proxy: the connection ended with an error", error)
    }

  /** Closes the listener and the connection in progress, which ends the adapter's session. Closing is idempotent. */
  override def close(): Unit = {
    val current = synchronized {
      closed = true
      session
    }

    listener.close()
    current.foreach(_.close())
    executor.shutdownNow()
  }
}

object DebugProxy {
  /**
   * The most elements of a collection the proxy lists. Each element costs a round trip to the debuggee (an evaluation
   * that rebuilds an indexed view of the collection), and an editor lists every child it is given: a hundred keeps a
   * large collection expandable in about a second, and the last child tells how many are left out.
   */
  private val MaxElements = 100

  /**
   * The most steps a Smart Step Into takes to reach its target: each is a step into a call of the line and a step
   * back out of it when it is not the target. A line with more calls than this stops in the last one stepped into.
   */
  private val MaxSmartSteps = 30

  /** How long the proxy waits for the adapter's answer to its own request, or for the stop a step ends in. */
  private val InternalTimeout = 30.seconds

  /**
   * The first variables reference the proxy hands out for a collection's elements. The adapter numbers its own
   * references from 1 up, one per object it shows, so this range cannot meet them in a session of any length.
   */
  private val FirstProxyReference = 1_000_000_000

  /** The classes scala-library 2.13 and 3 compile a captured `var` into, whose value is the `elem` field. */
  private val RefHolders: Set[String] = Seq("Int", "Long", "Double", "Float", "Short", "Byte", "Char", "Boolean", "Object")
    .flatMap(kind => Seq(s"scala.runtime.${kind}Ref", s"scala.runtime.Volatile${kind}Ref"))
    .toSet

  /**
   * A package-qualified class name at the start of a value, before its `@id` or array brackets
   * (`scala.collection.immutable.$colon$colon@743 "..."`); the group is the name without the package.
   */
  private val QualifiedTypeAtStart = raw"^(?:[\w$$]+\.)+([\w$$]+(?=[@\[]))".r

  /** The package of the standard collections, whose types the proxy lists as collections when they are not lazy. */
  private val CollectionPackage = "scala.collection."

  /**
   * The factories of the collections that may be infinite or costly to force: listing the elements of one would
   * evaluate them, which the IDE avoids as well.
   */
  private val LazyFactories = Seq(
    "scala.collection.immutable.LazyList$", "scala.collection.immutable.Stream$", "scala.collection.View$")

  /**
   * Starts a proxy for a debug adapter's server, ready for one client.
   *
   * The caller owns the result and closes it.
   *
   * @param adapter the address of the adapter's server, which the proxy connects to when its client does
   * @param stepTargets the calls of a source line, as `DebugAdapter.start` documents it
   * @return the running proxy; its `uri` is where a DAP client connects
   */
  def start(adapter: URI, stepTargets: (Path, Int) => Seq[DebugAdapter.StepTarget]): DebugProxy = {
    val executor = java.util.concurrent.Executors.newCachedThreadPool { task =>
      val thread = new Thread(task, "scala-analyzer-debug-proxy")
      thread.setDaemon(true)
      thread
    }
    val listener =
      try new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      catch {
        case error: IOException =>
          executor.shutdown()
          throw error
      }
    val proxy = new DebugProxy(listener, adapter, stepTargets, executor)

    executor.execute(() => proxy.serve())
    proxy
  }

  /** What a message the proxy sent to the adapter waits for. */
  private sealed trait Pending

  /**
   * A request of the client, forwarded: the response goes back under the client's sequence number.
   *
   * @param clientSeq the sequence number of the client's request
   * @param command the request's command
   * @param frameId the frame the request concerns, when it names one or a reference of one
   */
  private final case class Forwarded(clientSeq: Int, command: String, frameId: Option[Int]) extends Pending

  /** A request of the proxy: the response completes `response` and goes no further. */
  private final case class Internal(response: CompletableFuture[JsonObject]) extends Pending

  /** The stopped location of a frame, which the step targets of its line are looked up by. */
  private final case class Frame(path: Path, line: Int)

  /**
   * A collection the proxy lists the elements of.
   *
   * @param frameId the frame its expression is evaluated in
   * @param expression the expression the debugger evaluates to the collection
   * @param size the number of elements
   */
  private final case class Collection(frameId: Int, expression: String, size: Int)

  /** What `InputStream.read` answers at the end of the stream, and the line break of a header. */
  private val EndOfStream = -1
  private val LineFeed = '\n'.toInt
  private val CarriageReturn = '\r'.toInt

  extension (json: JsonObject) {
    /** The text of a member, when it is a primitive. */
    private def string(key: String): Option[String] =
      Option(json.get(key)).collect { case member if member.isJsonPrimitive => member.getAsString }

    /** The value of a member that is a number. */
    private def int(key: String): Option[Int] =
      Option(json.get(key)).collect { case member if member.isJsonPrimitive && member.getAsJsonPrimitive.isNumber => member.getAsInt }

    /** The value of a member that is a boolean. */
    private def bool(key: String): Boolean =
      Option(json.get(key)).exists(member => member.isJsonPrimitive && member.getAsJsonPrimitive.isBoolean && member.getAsBoolean)

    /** The member that is an object. */
    private def obj(key: String): Option[JsonObject] =
      Option(json.get(key)).collect { case member if member.isJsonObject => member.getAsJsonObject }

    /** The elements of a member that is an array of objects; empty when it is absent. */
    private def objects(key: String): Seq[JsonObject] =
      Option(json.get(key)).collect { case member if member.isJsonArray => member.getAsJsonArray }
        .fold(Seq.empty[JsonObject])(array => (0 until array.size).map(array.get(_).getAsJsonObject))

    /** Sets an integer member. */
    private def put(key: String, value: Int): Unit = json.addProperty(key, Int.box(value))
  }

  /** One endpoint of a connection: reads and writes DAP messages (`Content-Length: N`, a blank line, N bytes of JSON). */
  private final class Channel(socket: Socket) {
    private val input = new BufferedInputStream(socket.getInputStream)
    private val output = socket.getOutputStream
    private var written = 0

    /** Reads one message; `None` when the peer closed the connection. */
    def read(): Option[JsonObject] = {
      @tailrec def contentLength(found: Option[Int]): Option[Int] = readLine() match {
        case None => None
        case Some("") => found.orElse(throw new IOException("a DAP message without Content-Length"))
        case Some(header) =>
          val (name, value) = header.span(_ != ':')
          contentLength(if (name.equalsIgnoreCase("Content-Length")) Some(value.drop(1).trim.toInt) else found)
      }

      contentLength(None).flatMap { length =>
        val body = input.readNBytes(length)
        Option.when(body.length == length)(JsonParser.parseString(new String(body, UTF_8)).getAsJsonObject)
      }
    }

    /** Reads a header line without its line break; `None` at the end of the stream. */
    private def readLine(): Option[String] = {
      val line = new ByteArrayOutputStream

      @tailrec def loop(): Option[String] = input.read() match {
        case EndOfStream => None
        case LineFeed => Some(line.toString(UTF_8))
        case CarriageReturn => loop()
        case byte =>
          line.write(byte)
          loop()
      }

      loop()
    }

    /**
     * Writes a message under the next sequence number of this endpoint, which the proxy numbers itself because it
     * sends requests of its own.
     *
     * @param register receives the sequence number before the message is sent, so that the answer finds what waits for it
     */
    def write(message: JsonObject, register: Int => Unit = _ => ()): Unit = synchronized {
      written += 1
      register(written)
      message.put("seq", written)
      val body = message.toString.getBytes(UTF_8)
      output.write(s"Content-Length: ${body.length}\r\n\r\n".getBytes(UTF_8))
      output.write(body)
      output.flush()
    }
  }

  /**
   * The relay of one connection. The editor's requests are forwarded under new sequence numbers and the adapter's
   * responses and events are forwarded back, except what the proxy answers or swallows itself. Reading never waits
   * for the adapter: what needs a request of the proxy's own runs on the executor, as that request's response is read
   * by the thread that would be waiting.
   *
   * What the frames and variables of the current stop are is tracked from the traffic; the adapter forgets its
   * references when the program resumes, so the tracking is cleared at every `continued` and `stopped`.
   */
  private final class Session(clientSocket: Socket, adapterSocket: Socket, executor: ExecutorService,
                              stepTargets: (Path, Int) => Seq[DebugAdapter.StepTarget]) extends AutoCloseable {
    private val client = new Channel(clientSocket)
    private val adapter = new Channel(adapterSocket)
    private val closed = new AtomicBoolean

    /** What each request sent to the adapter waits for, by the sequence number the proxy gave it. */
    private val pending = TrieMap.empty[Int, Pending]
    /** The adapter's own requests to the client: the sequence number the client sees, to the adapter's. */
    private val reverseRequests = TrieMap.empty[Int, Int]
    private val frames = TrieMap.empty[Int, Frame]
    /** The frame each variables reference of the stop belongs to, which evaluating its children needs. */
    private val referenceFrames = TrieMap.empty[Int, Int]
    private val collections = TrieMap.empty[Int, Collection]
    private val nextReference = new AtomicInteger(FirstProxyReference)
    /** The targets of the last `stepInTargets` answer, by id. */
    @volatile private var issuedTargets = Map.empty[Int, DebugAdapter.StepTarget]
    /**
     * Whether the client launched without debugging (VS Code's Run Without Debugging): the adapter attaches all the
     * same, as the program waits for it, so the proxy keeps the program from stopping by setting no breakpoint.
     */
    @volatile private var noDebug = false
    /** The thread a Smart Step Into is stepping, whose stops and resumes the client does not see. */
    @volatile private var stepping: Option[Int] = None
    private val steppingStops = new LinkedBlockingQueue[JsonObject]

    /** Relays until either side ends; the reading of the adapter runs on its own thread. */
    def relay(): Unit = {
      executor.execute(() => pump(adapter, onAdapterMessage))
      pump(client, onClientMessage)
    }

    override def close(): Unit = if (closed.compareAndSet(false, true)) {
      // Closing is how a blocked read ends; a socket that fails to close has nothing left to release.
      Seq(clientSocket, adapterSocket).foreach(socket => try socket.close() catch { case _: IOException => () })
    }

    /** Reads a side's messages until it ends, then closes the connection: a side that ends ends the session. */
    private def pump(channel: Channel, handle: JsonObject => Unit): Unit =
      try
        Iterator.continually(channel.read()).takeWhile(_.isDefined).flatten.foreach { message =>
          try handle(message)
          catch { case NonFatal(error) => HostLog.warn(s"debug proxy: a message was not relayed: $message", error) }
        }
      catch { case NonFatal(error) => HostLog.debug(s"debug proxy: the connection ended: $error") }
      finally close()

    /** Runs a request of the proxy's own on the executor, answering the client with its body or with its failure. */
    private def answerAsync(request: JsonObject)(body: => JsonObject): Unit = executor.execute { () =>
      val response = responseTo(request)

      try {
        val answer = body
        response.addProperty("success", true)
        response.add("body", answer)
      } catch {
        case NonFatal(error) =>
          HostLog.warn(s"debug proxy: ${request.string("command").getOrElse("a request")} failed", error)
          response.addProperty("success", false)
          response.addProperty("message", Option(error.getMessage).getOrElse(error.toString))
      }
      client.write(response)
    }

    /**
     * The answer to a breakpoint request of a session without debugging, which the adapter never sees: every source
     * or function breakpoint is reported as not set, so that the editor shows them disabled.
     */
    private def withoutBreakpoints(request: JsonObject, command: String): JsonObject = {
      val response = responseTo(request)
      val requested = request.obj("arguments").fold(Seq.empty[JsonObject])(arguments => command match {
        case "setExceptionBreakpoints" => Seq.empty
        case _ => arguments.objects("breakpoints")
      })
      val breakpoints = new JsonArray
      requested.foreach { _ =>
        val breakpoint = new JsonObject
        breakpoint.addProperty("verified", false)
        breakpoint.addProperty("message", "Run Without Debugging")
        breakpoints.add(breakpoint)
      }
      val body = new JsonObject
      if (command != "setExceptionBreakpoints") body.add("breakpoints", breakpoints)
      response.addProperty("success", true)
      response.add("body", body)
      response
    }

    /** The response to a client's request, without its outcome. */
    private def responseTo(request: JsonObject): JsonObject = {
      val response = new JsonObject
      response.addProperty("type", "response")
      response.put("request_seq", request.int("seq").getOrElse(0))
      response.addProperty("command", request.string("command").getOrElse(""))
      response
    }

    private def onClientMessage(message: JsonObject): Unit = message.string("type") match {
      case Some("request") => onClientRequest(message)
      case Some("response") =>
        message.int("request_seq").flatMap(seq => reverseRequests.remove(seq)).foreach { adapterSeq =>
          message.put("request_seq", adapterSeq)
          adapter.write(message)
        }
      case other => HostLog.debug(s"debug proxy: ignored a client message of type $other")
    }

    /** Forwards a request of the client, or answers it when it is one of those the proxy implements. */
    private def onClientRequest(request: JsonObject): Unit = {
      val arguments = request.obj("arguments")

      request.string("command") match {
        case Some("initialize") =>
          // Without it the adapter leaves out the variables' types, which the renderings are chosen by.
          val initializeArguments = arguments.getOrElse {
            val created = new JsonObject
            request.add("arguments", created)
            created
          }
          initializeArguments.addProperty("supportsVariableType", true)
          forward(request)
        case Some("launch") =>
          noDebug = arguments.exists(_.bool("noDebug"))
          forward(request)
        case Some(command @ ("setBreakpoints" | "setFunctionBreakpoints" | "setExceptionBreakpoints" | "setDataBreakpoints"))
            if noDebug =>
          client.write(withoutBreakpoints(request, command))
        case Some("stepInTargets") => answerAsync(request)(stepInTargets(arguments))
        case Some("stepIn") =>
          val chosen = for {
            stepArguments <- arguments
            thread <- stepArguments.int("threadId")
            id <- stepArguments.int("targetId")
            target <- issuedTargets.get(id)
          } yield (thread, target)

          chosen match {
            case Some((thread, target)) => startSmartStep(request, thread, target)
            case None => forward(request)
          }
        case Some("variables") =>
          arguments.filter(_.int("variablesReference").exists(_ >= FirstProxyReference))
            .fold(forward(request))(proxied => answerAsync(request)(elements(proxied)))
        case _ => forward(request)
      }
    }

    /** Sends a request of the client to the adapter, remembering how to answer the client when the response comes. */
    private def forward(request: JsonObject): Unit = {
      val command = request.string("command").getOrElse("")
      val arguments = request.obj("arguments")
      val frameId = command match {
        case "scopes" | "evaluate" => arguments.flatMap(_.int("frameId"))
        case "variables" => arguments.flatMap(_.int("variablesReference")).flatMap(referenceFrames.get)
        case _ => None
      }
      val clientSeq = request.int("seq").getOrElse(0)

      adapter.write(request, seq => pending.put(seq, Forwarded(clientSeq, command, frameId)))
    }

    private def onAdapterMessage(message: JsonObject): Unit = message.string("type") match {
      case Some("response") => onAdapterResponse(message)
      case Some("event") => onAdapterEvent(message)
      case Some("request") =>
        val adapterSeq = message.int("seq").getOrElse(0)
        client.write(message, clientSeq => reverseRequests.put(clientSeq, adapterSeq))
      case other => HostLog.debug(s"debug proxy: ignored an adapter message of type $other")
    }

    private def onAdapterResponse(response: JsonObject): Unit =
      response.int("request_seq").flatMap(seq => pending.remove(seq)) match {
        case Some(Internal(answer)) => answer.complete(response)
        case Some(forwarded: Forwarded) =>
          val success = response.bool("success")
          response.put("request_seq", forwarded.clientSeq)
          if (success) inspect(forwarded, response)

          if (success && forwarded.command == "variables") executor.execute { () =>
            // The unrendered variables are the fallback: the client gets a response whatever happens here.
            try renderResponse(response, forwarded.frameId)
            catch { case NonFatal(error) => HostLog.warn("debug proxy: variables were not rendered", error) }
            client.write(response)
          }
          else client.write(response)
        case None => HostLog.debug(s"debug proxy: a response to no request of the proxy: $response")
      }

    /** Tracks the stop's frames and references from a response on its way to the client, and extends the capabilities. */
    private def inspect(forwarded: Forwarded, response: JsonObject): Unit = {
      val body = response.obj("body").getOrElse {
        val created = new JsonObject
        response.add("body", created)
        created
      }

      forwarded.command match {
        case "initialize" => body.addProperty("supportsStepInTargetsRequest", true)
        case "stackTrace" =>
          body.objects("stackFrames").foreach { frame =>
            for {
              id <- frame.int("id")
              line <- frame.int("line")
              path <- frame.obj("source").flatMap(_.string("path")).flatMap(pathOf)
            } frames.put(id, Frame(path, line))
          }
        case "scopes" =>
          for (frame <- forwarded.frameId; scope <- body.objects("scopes"); reference <- scope.int("variablesReference"))
            referenceFrames.put(reference, frame)
        case "evaluate" =>
          shortenType(body, "result")
          if (body.string("type").contains("java.lang.String")) body.put("variablesReference", 0)
          for (frame <- forwarded.frameId; reference <- body.int("variablesReference") if reference > 0)
            referenceFrames.put(reference, frame)
        case _ => ()
      }
    }

    /**
     * Shows the type at the start of a value without its package (`List@12 "List(1)"`), as java-debug shows it by
     * default: the session asks java-debug for qualified type names, which the renderings need (`DebugAdapter.start`).
     */
    private def shortenType(json: JsonObject, member: String): Unit =
      json.string(member).foreach(text => json.addProperty(member, QualifiedTypeAtStart.replaceFirstIn(text, "$1")))

    /** The path of a frame's source; `None` for what is not a path. */
    private def pathOf(text: String): Option[Path] =
      try Some(Path.of(text))
      catch { case _: InvalidPathException => None }

    private def onAdapterEvent(event: JsonObject): Unit = {
      val thread = event.obj("body").flatMap(_.int("threadId"))
      val ofSmartStep = thread.isDefined && thread == stepping

      event.string("event") match {
        case Some("stopped") =>
          clearTracking()
          if (ofSmartStep) steppingStops.put(event) else client.write(event)
        case Some("continued") =>
          clearTracking()
          if (!ofSmartStep) client.write(event)
        case _ => client.write(event)
      }
    }

    /** Forgets the frames and references of the stop that ended: the adapter invalidates them when the program resumes. */
    private def clearTracking(): Unit = {
      frames.clear()
      referenceFrames.clear()
      collections.clear()
    }

    /**
     * Sends a request of the proxy's own and waits for its response, which the adapter's reading thread delivers: it
     * must not be called from that thread.
     *
     * @return the response's body
     * @throws IllegalStateException when the adapter answers that the request failed
     */
    private def request(command: String, arguments: JsonObject): JsonObject = {
      val message = new JsonObject
      val answer = new CompletableFuture[JsonObject]
      message.addProperty("type", "request")
      message.addProperty("command", command)
      message.add("arguments", arguments)
      adapter.write(message, seq => pending.put(seq, Internal(answer)))

      val response = answer.get(InternalTimeout.toMillis, TimeUnit.MILLISECONDS)
      if (!response.bool("success"))
        throw new IllegalStateException(s"$command failed: ${response.string("message").getOrElse("no reason given")}")
      response.obj("body").getOrElse(new JsonObject)
    }

    // --- Smart Step Into -------------------------------------------------------------------------------------------

    /** The calls of the line of a frame, as DAP step-in targets; none for a frame the proxy does not know. */
    private def stepInTargets(arguments: Option[JsonObject]): JsonObject = {
      val targets = arguments.flatMap(_.int("frameId")).flatMap(frames.get)
        .fold(Seq.empty[DebugAdapter.StepTarget])(frame => stepTargets(frame.path, frame.line))
      val listed = new JsonArray

      targets.foreach { target =>
        val entry = new JsonObject
        entry.put("id", target.id)
        entry.addProperty("label", target.label)
        listed.add(entry)
      }
      issuedTargets = targets.map(target => target.id -> target).toMap

      val body = new JsonObject
      body.add("targets", listed)
      body
    }

    /**
     * Answers a `stepIn` of a target right away, as the adapter would answer a step, and then steps on the executor.
     * Its end is an event, as for any step.
     */
    private def startSmartStep(request: JsonObject, threadId: Int, target: DebugAdapter.StepTarget): Unit = {
      stepping = Some(threadId)
      val response = responseTo(request)
      response.addProperty("success", true)
      client.write(response)

      executor.execute(() => smartStep(threadId, target))
    }

    /**
     * Steps into the calls of the line one by one, stepping out of each that is not the target, until the program is
     * at the start of the target's method. When the line has two calls of the same method, the first is the one found.
     * A stop of another kind, such as a breakpoint, ends the search and is the stop the client sees. If anything
     * fails, the client gets a stop at wherever the program is, so that it never waits for one.
     */
    private def smartStep(threadId: Int, target: DebugAdapter.StepTarget): Unit = {
      var last = Option.empty[JsonObject]

      /** Takes a step and waits for the stop it ends in, which the client does not see. */
      def step(command: String): JsonObject = {
        steppingStops.clear()
        val arguments = new JsonObject
        arguments.put("threadId", threadId)
        request(command, arguments)

        val stop = Option(steppingStops.poll(InternalTimeout.toMillis, TimeUnit.MILLISECONDS))
          .getOrElse(throw new IllegalStateException(s"no stop after $command"))
        last = Some(stop)
        stop
      }

      def isStep(stop: JsonObject): Boolean = stop.obj("body").flatMap(_.string("reason")).contains("step")

      def frameNames(): Seq[String] = {
        val arguments = new JsonObject
        arguments.put("threadId", threadId)
        request("stackTrace", arguments).objects("stackFrames").map(_.string("name").getOrElse(""))
      }

      /** Whether the frame is the target's method: its depth is one deeper than the line's frame, and its name is its. */
      def entered(names: Seq[String], startDepth: Int): Boolean =
        names.size == startDepth + 1 && names.headOption.exists(isMethodOf(_, target))

      @tailrec def search(startDepth: Int, remaining: Int): JsonObject = {
        val stop = step("stepIn")

        if (!isStep(stop)) stop
        else {
          val names = frameNames()

          if (entered(names, startDepth) || names.size <= startDepth) stop
          else {
            val left = step("stepOut")
            if (isStep(left) && remaining > 1) search(startDepth, remaining - 1) else left
          }
        }
      }

      val outcome =
        try Some(search(frameNames().size, MaxSmartSteps))
        catch {
          case NonFatal(error) =>
            HostLog.warn(s"debug proxy: smart step into ${target.label} failed", error)
            None
        }

      stepping = None
      client.write(outcome.getOrElse(stoppedEvent(threadId, last)))
    }

    /** The `stopped` event of a step, for a Smart Step Into that failed halfway. */
    private def stoppedEvent(threadId: Int, previous: Option[JsonObject]): JsonObject = {
      val body = new JsonObject
      val event = new JsonObject
      body.addProperty("reason", "step")
      body.put("threadId", threadId)
      previous.flatMap(_.obj("body")).flatMap(stop => Option(stop.get("allThreadsStopped"))).foreach(body.add("allThreadsStopped", _))
      event.addProperty("type", "event")
      event.addProperty("event", "stopped")
      event.add("body", body)
      event
    }

    /**
     * Whether a frame's name is that of the target's method. The adapter names a frame `Main.main(String[]): void` or
     * `Debugged.triple(n: Int): Int`: the class, a dot, the method, its parameters. A Scala operator may appear
     * decoded (`+`) where the JVM name is encoded (`$plus`).
     */
    private def isMethodOf(frameName: String, target: DebugAdapter.StepTarget): Boolean = {
      val qualified = frameName.takeWhile(_ != '(')
      val method = qualified.substring(qualified.lastIndexOf('.') + 1)

      method == target.methodName || NameTransformer.encode(method) == target.methodName
    }

    // --- Variables -------------------------------------------------------------------------------------------------

    /** Renders the variables of a `variables` response in place. */
    private def renderResponse(response: JsonObject, frameId: Option[Int]): Unit =
      for {
        body <- response.obj("body")
        variables <- Option(body.getAsJsonArray("variables"))
        index <- 0 until variables.size
      } variables.set(index, render(variables.get(index).getAsJsonObject, frameId))

    /**
     * Shows a variable as the IDE does: a string is not expandable, a captured `var` shows what it holds, and a
     * collection lists its elements. A rendering that cannot be done leaves the adapter's variable as it is.
     *
     * @param frameId the frame the variable belongs to, when known: collections need it
     * @return the variable to show; also registers the frame of the references it carries
     */
    private def render(variable: JsonObject, frameId: Option[Int]): JsonObject = {
      shortenType(variable, "value")
      val rendered = variable.string("type") match {
        case Some("java.lang.String") => unexpandable(variable)
        case Some(kind) if RefHolders.contains(kind) => attempt(variable, "a captured var")(unwrapped(variable, frameId))
        case Some(kind) if kind.startsWith(CollectionPackage) => attempt(variable, "a collection")(listed(variable, frameId))
        case _ => variable
      }

      for (frame <- frameId; reference <- rendered.int("variablesReference") if reference > 0 && reference < FirstProxyReference)
        referenceFrames.put(reference, frame)
      rendered
    }

    /** The rendering, or `variable` when it fails: the reason is logged at debug level, as it is the usual case for a type that is not what it seems. */
    private def attempt(variable: JsonObject, what: String)(rendering: => JsonObject): JsonObject =
      try rendering
      catch {
        case NonFatal(error) =>
          HostLog.debug(s"debug proxy: ${variable.string("name").getOrElse("a variable")} is not shown as $what: $error")
          variable
      }

    /** A variable without children. */
    private def unexpandable(variable: JsonObject): JsonObject = {
      val copy = variable.deepCopy()
      copy.put("variablesReference", 0)
      copy.remove("namedVariables")
      copy.remove("indexedVariables")
      copy
    }

    /** The variable of a `scala.runtime.*Ref` holder, shown as its `elem`, under the holder's name. */
    private def unwrapped(variable: JsonObject, frameId: Option[Int]): JsonObject = {
      val reference = variable.int("variablesReference").filter(_ > 0).getOrElse(throw new IllegalStateException("no children"))
      val arguments = new JsonObject
      arguments.put("variablesReference", reference)
      val elem = request("variables", arguments).objects("variables").find(_.string("name").contains("elem"))
        .getOrElse(throw new IllegalStateException("no elem"))
      val copy = variable.deepCopy()

      Seq("value", "type", "variablesReference").foreach { key =>
        if (elem.has(key)) copy.add(key, elem.get(key)) else copy.remove(key)
      }
      copy.remove("namedVariables")
      copy.remove("indexedVariables")
      render(copy, frameId)
    }

    /**
     * A collection variable with the proxy's reference for its elements. Only a collection that is certainly not lazy
     * is listed: `iterableFactory` exists on the 2.13 and 3 collections and not on iterators, and the lazy factories
     * are left to the adapter.
     */
    private def listed(variable: JsonObject, frameId: Option[Int]): JsonObject = {
      val expression = variable.string("evaluateName").getOrElse(throw new IllegalStateException("no evaluate name"))
      val frame = frameId.getOrElse(throw new IllegalStateException("no frame"))
      val factory = evaluate(frame, s"$expression.iterableFactory")
      val named = Seq("result", "type").flatMap(factory.string)

      if (LazyFactories.exists(lazyFactory => named.exists(_.contains(lazyFactory)))) variable
      else {
        val size = evaluate(frame, s"$expression.size").string("result").flatMap(_.toIntOption)
          .getOrElse(throw new IllegalStateException("no size"))
        val copy = variable.deepCopy()
        copy.remove("namedVariables")

        if (size == 0) unexpandable(copy)
        else {
          val reference = nextReference.getAndIncrement()
          collections.put(reference, Collection(frame, expression, size))
          copy.put("variablesReference", reference)
          // The children listed: a paging client asks for no more, and the marker counts as one.
          copy.put("indexedVariables", elementCount(size))
          copy
        }
      }
    }

    /** The number of children a collection of `size` elements is listed with: the shown elements and the marker. */
    private def elementCount(size: Int): Int = math.min(size, MaxElements) + (if (size > MaxElements) 1 else 0)

    /** Evaluates an expression for the proxy, as a watch in a frame. */
    private def evaluate(frameId: Int, expression: String): JsonObject = {
      val arguments = new JsonObject
      arguments.addProperty("expression", expression)
      arguments.put("frameId", frameId)
      arguments.addProperty("context", "watch")
      request("evaluate", arguments)
    }

    /**
     * The children of a collection the proxy lists, answering a `variables` request for its reference: the elements
     * named by their indexes, as many as the request pages, and the marker for the elements left out. A collection
     * has no named children.
     */
    private def elements(arguments: JsonObject): JsonObject = {
      val reference = arguments.int("variablesReference").getOrElse(0)
      val collection = collections.get(reference)
        .getOrElse(throw new IllegalStateException("the variables reference is no longer valid"))
      val start = arguments.int("start").getOrElse(0)
      val count = arguments.int("count").filter(_ > 0).getOrElse(Int.MaxValue)
      val indexes = if (arguments.string("filter").contains("named")) Seq.empty
        else (start until elementCount(collection.size)).take(count)
      val listed = new JsonArray

      indexes.foreach { index =>
        listed.add(if (index < MaxElements && index < collection.size) element(collection, index) else marker(collection))
      }

      val body = new JsonObject
      body.add("variables", listed)
      body
    }

    /** The variable of an element, shown as any other; one that cannot be evaluated says so. */
    private def element(collection: Collection, index: Int): JsonObject = {
      val expression = s"${collection.expression}.toIndexedSeq.apply($index)"
      val child = new JsonObject
      child.addProperty("name", index.toString)
      child.addProperty("evaluateName", expression)

      try {
        val result = evaluate(collection.frameId, expression)
        child.addProperty("value", result.string("result").getOrElse(""))
        result.string("type").foreach(child.addProperty("type", _))
        child.put("variablesReference", result.int("variablesReference").getOrElse(0))
      } catch {
        case NonFatal(error) =>
          HostLog.debug(s"debug proxy: element $index of ${collection.expression} was not evaluated: $error")
          child.addProperty("value", "<not evaluated>")
          child.put("variablesReference", 0)
      }
      render(child, Some(collection.frameId))
    }

    /** The last child of a collection with more elements than are listed. */
    private def marker(collection: Collection): JsonObject = {
      val child = new JsonObject
      child.addProperty("name", "...")
      child.addProperty("value", s"${collection.size - MaxElements} more")
      child.put("variablesReference", 0)
      child
    }
  }
}
