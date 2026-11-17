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
    assertEquals(
      tools.asScala.map(_.getAsJsonObject.get("name").getAsString).toSeq,
      Seq(
        "project_status", "find_symbol", "definition", "references", "implementations", "callers", "callees",
        "supertypes", "subtypes", "type_of", "describe", "outline",
      ))

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

  /**
   * A navigation tool call and what its answer must contain.
   *
   * @param tool the tool called
   * @param arguments the call's arguments, as JSON
   * @param isError whether the call must fail
   * @param pattern a regular expression that the answer must contain a match of
   */
  private final case class Navigation(tool: String, arguments: String, isError: Boolean, pattern: String)

  private val mainText = """"file": "app/src/main/scala/app/Main.scala", "line": 7"""

  private val navigations = Seq(
    // Both forms of naming the target.
    Navigation("definition", s"""{$mainText, "text": "Greeting"}""", false, """Greeting\.scala:3:18  final case class Greeting"""),
    Navigation("definition", """{"symbol": "Greeting"}""", false, """Greeting\.scala:3:18  final case class Greeting"""),
    Navigation("definition", """{"symbol": "Greeting#text"}""", false, """Greeting\.scala:4:7  def text"""),
    Navigation("references", """{"symbol": "core.Greeting"}""", false, """app/src/main/scala/app/Main\.scala:7:\d+  val text"""),
    Navigation("implementations", """{"symbol": "Shape"}""", false, """(?s)(?=.*Shape\.scala:7:13  final class Square)(?=.*Shape\.scala:11:13  final class Circle)"""),
    Navigation("implementations", """{"symbol": "Shape", "limit": 1}""", false, """\(showing 1 of 2 implementations\)"""),
    Navigation("callers", """{"symbol": "Greeting.text"}""", false, """method main — app/src/main/scala/app/Main\.scala:6:7  def main"""),
    Navigation("callees", """{"symbol": "Main.main"}""", false, """method text — core/src/main/scala/core/Greeting\.scala:4:7  def text"""),
    Navigation("supertypes", """{"symbol": "Square"}""", false, """ Shape — core/src/main/scala/core/Shape\.scala:3:7  trait Shape"""),
    Navigation("subtypes", """{"symbol": "core.Shape"}""", false, """(?s)(?=.*class Square — .*Shape\.scala:7:13)(?=.*class Circle — .*Shape\.scala:11:13)"""),
    Navigation("supertypes", """{"file": "core/src/main/scala/core/Shape.scala", "line": 7, "text": "Square"}""", false, "interface Shape"),
    Navigation("type_of", s"""{$mainText, "text": "Greeting(\\"world\\").text"}""", false, "String"),
    Navigation("type_of", """{"symbol": "Counter.count"}""", false, "Int"),
    Navigation("describe", """{"symbol": "Greet3.hello"}""", false, """def hello\(name: String\): String(?s).*warmly"""),
    Navigation("outline", """{"file": "core/src/main/scala/core/Shape.scala"}""", false, """(?m)^interface Shape :3\n  method area : Double :4$"""),
    // Failures the model can correct.
    Navigation("references", """{"symbol": "Counter"}""", true, """(?s)names several symbols:(?=.*Counter\.scala:9:7)(?=.*Counter\.scala:20:8).*Qualify the name"""),
    Navigation("references", """{"symbol": "NoSuchSymbolAnywhere"}""", true, "No project symbol is named NoSuchSymbolAnywhere"),
    Navigation("definition", s"""{$mainText, "text": "Missing"}""", true, """`Missing` is not on line 7 of app/src/main/scala/app/Main\.scala, which reads:\s+val text: String"""),
    Navigation("definition", s"""{$mainText, "text": "Greeting", "symbol": "Greeting"}""", true, "exactly one of two ways"),
    Navigation("definition", """{}""", true, "exactly one of two ways"),
    Navigation("definition", """{"file": "app/src/main/scala/app/Main.scala", "text": "Greeting"}""", true, "`line` is required"),
    Navigation("definition", """{"file": "app/src/main/scala/app/Main.scala", "line": 99, "text": "Greeting"}""", true, "has fewer than 99 lines"),
    Navigation("definition", """{"file": "build.sbt", "line": 1, "text": "x"}""", true, "not a source file of the project"),
    Navigation("callers", """{"symbol": "core.Shape"}""", true, "is not a method"),
    Navigation("supertypes", """{"symbol": "Greeting.text"}""", true, "is not a class, trait or object"),
    Navigation("references", """{"symbol": "core.Greeting", "limit": 0}""", true, "`limit` must be an integer of at least 1"),
    Navigation("outline", """{}""", true, "`file` is required"),
  )

  test("answers the navigation tools by symbol and by position") {
    ready

    for (navigation <- navigations) {
      val answered = call(navigation.tool, navigation.arguments)
      val clue = s"${navigation.tool} ${navigation.arguments}: ${answered.text}"

      assertEquals(answered.isError, navigation.isError, clue)
      assert(navigation.pattern.r.findFirstIn(answered.text).isDefined, clue)
    }
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
