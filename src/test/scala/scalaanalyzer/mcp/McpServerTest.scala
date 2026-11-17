package scalaanalyzer.mcp

import com.google.gson.{JsonElement, JsonObject, JsonParser}

import scalaanalyzer.TestHost

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.channels.{Channels, Pipe}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The MCP server end to end: a client that writes JSON lines and reads them back, connected through pipes, on the BSP
 * fixture (`fixtures/bsp-project`). Runs in a JVM of its own (see `Test / testGrouping`): it opens the fixture's
 * modules in the host.
 */
class McpServerTest extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private val fixture = Path.of("fixtures/bsp-project").toAbsolutePath
  private val coreSources = fixture.resolve("core/src/main/scala/core")
  private val counter = coreSources.resolve("Counter.scala")

  private val toServer = Pipe.open()
  private val toClient = Pipe.open()
  private val clientOutput = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(toServer.sink), StandardCharsets.UTF_8))

  /** The lines the server wrote, read on a thread of their own so that a missing answer fails the test, not hangs it. */
  private val answers = new LinkedBlockingQueue[String]()

  private val server = new McpServer(
    TestHost.host, fixture, Channels.newInputStream(toServer.source), Channels.newOutputStream(toClient.sink))

  private val serving = new Thread(() => server.serve(), "mcp-test-server")
  serving.setDaemon(true)
  serving.start()

  private val listening = new Thread(() => {
    val reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(toClient.source), StandardCharsets.UTF_8))
    reader.lines().forEach(line => answers.add(line))
  }, "mcp-test-client")
  listening.setDaemon(true)
  listening.start()

  /** The client closes its input, and the server closes the project and ends. */
  override def afterAll(): Unit = {
    toServer.sink.close()
    serving.join(1.minute.toMillis)
    assert(!serving.isAlive, "the server did not end with its input")
  }

  private var lastId = 0

  /** Sends a line as it is. */
  private def sendLine(line: String): Unit = {
    clientOutput.write(line)
    clientOutput.write("\n")
    clientOutput.flush()
  }

  /** The next message the server wrote. */
  private def nextMessage(): JsonObject = {
    val line = Option(answers.poll(1, TimeUnit.MINUTES)).getOrElse(fail("the server wrote nothing"))

    JsonParser.parseString(line).getAsJsonObject
  }

  /** Sends a request with the given id, a JSON literal, and returns its response. */
  private def requestWithId(id: String, method: String, params: String = "{}"): JsonObject = {
    sendLine(s"""{"jsonrpc": "2.0", "id": $id, "method": "$method", "params": $params}""")
    nextMessage()
  }

  /** Sends a request with a fresh numeric id and returns its response. */
  private def request(method: String, params: String = "{}"): JsonObject = {
    lastId += 1
    requestWithId(lastId.toString, method, params)
  }

  /** What a tool call answered: its text and whether it reports a failure. */
  private final case class ToolAnswer(text: String, isError: Boolean)

  /** Calls a tool and returns its result. */
  private def call(tool: String, arguments: String = "{}"): ToolAnswer = {
    val response = request("tools/call", s"""{"name": "$tool", "arguments": $arguments}""")
    val result = response.getAsJsonObject("result")

    ToolAnswer(result.getAsJsonArray("content").get(0).getAsJsonObject.get("text").getAsString, result.get("isError").getAsBoolean)
  }

  /** The code of an error response. */
  private def errorCode(response: JsonObject): Int = response.getAsJsonObject("error").get("code").getAsInt

  /** Polls `project_status` until the project is ready. */
  private lazy val ready: Unit = {
    val deadline = System.nanoTime() + 5.minutes.toNanos
    var status = call("project_status").text

    while (!status.contains("state: ready")) {
      assert(!status.contains("state: failed"), status)
      assert(System.nanoTime() < deadline, s"the project did not become ready: $status")
      Thread.sleep(500)
      status = call("project_status").text
    }
  }

  test("speaks the protocol and rejects what it does not") {
    for ((asked, answered) <- Seq("2025-06-18" -> "2025-06-18", "1999-01-01" -> "2025-11-25")) {
      val result = request("initialize", s"""{"protocolVersion": "$asked", "capabilities": {}, "clientInfo": {"name": "test"}}""")
        .getAsJsonObject("result")

      assertEquals(result.get("protocolVersion").getAsString, answered)
      assertEquals(result.getAsJsonObject("serverInfo").get("name").getAsString, "scala-analyzer")
      assert(!result.get("instructions").getAsString.isEmpty)
    }
    sendLine("""{"jsonrpc": "2.0", "method": "notifications/initialized"}""")

    val tools = request("tools/list").getAsJsonObject("result").getAsJsonArray("tools")
    assertEquals(tools.asScala.map(_.getAsJsonObject.get("name").getAsString).toSeq, Seq("project_status", "find_symbol"))

    val ping = requestWithId("\"abc\"", "ping")
    assertEquals(ping.get("id").getAsString, "abc")
    assertEquals(ping.getAsJsonObject("result").size, 0)

    assertEquals(errorCode(request("server/discover")), -32601)
    assertEquals(errorCode(request("tools/call", """{"name": "no_such_tool"}""")), -32602)

    sendLine("{not json")
    val garbage = nextMessage()
    assertEquals(errorCode(garbage), -32700)
    assert(garbage.get("id").isJsonNull)

    sendLine("""[{"jsonrpc": "2.0", "id": 1, "method": "ping"}]""")
    assertEquals(errorCode(nextMessage()), -32600)
  }

  test("answers find_symbol once the project is ready") {
    // Answers at once, whatever the state of the import.
    assert(call("project_status").text.contains(s"root: $fixture"))
    ready

    val found = call("find_symbol", """{"query": "Greeting"}""")
    assert(!found.isError, found.text)
    assert(found.text.linesIterator.exists(_.matches("""class Greeting.* — core/src/main/scala/core/Greeting\.scala:3:18""")), found.text)

    assertEquals(call("find_symbol", """{"query": "Greeting", "limit": 1}""").text.linesIterator.size, 2)
    assertEquals(call("find_symbol", """{"query": "NoSuchSymbolAnywhere"}"""), ToolAnswer("No symbol matches NoSuchSymbolAnywhere.", false))
    assert(call("find_symbol", """{"limit": 1}""").isError)
  }

  test("follows the files edited on disk") {
    ready

    val original = Files.readString(counter)
    val created = coreSources.resolve("McpCreated.scala")
    val withMethod = original.replace("def zero: Counter = new Counter(0)", "def zero: Counter = new Counter(0)\n  def followedFromDisk: Counter = zero")
    assertNotEquals(withMethod, original)

    try {
      Files.writeString(counter, withMethod)
      assert(call("find_symbol", """{"query": "followedFromDisk"}""").text.contains("Counter.scala"))

      // A new source needs the build to be imported again.
      Files.writeString(created, "package core\n\nclass McpCreated\n")
      assert(call("find_symbol", """{"query": "McpCreated"}""").text.contains("McpCreated.scala"))
    } finally {
      Files.writeString(counter, original)
      Files.deleteIfExists(created)
    }

    // The restoration and the deletion are followed too, so that nothing outlives the test.
    assertEquals(call("find_symbol", """{"query": "followedFromDisk"}""").text, "No symbol matches followedFromDisk.")
    assertEquals(call("find_symbol", """{"query": "McpCreated"}""").text, "No symbol matches McpCreated.")
  }
}
