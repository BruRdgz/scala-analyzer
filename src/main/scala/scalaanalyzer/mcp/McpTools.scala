package scalaanalyzer.mcp

import com.google.gson.{JsonObject, JsonParser, JsonPrimitive}

import scalaanalyzer.language.WorkspaceSymbolMatch
import scalaanalyzer.workspace.ProjectSession

import java.nio.file.Path

import scala.util.Try

/**
 * The tools the MCP server offers, and what the ones that read the project answer.
 *
 * Tools address symbols by name, and report lines and columns counting from 1 (the session counts from 0).
 */
private[mcp] object McpTools {
  /**
   * One tool as `tools/list` describes it to the model.
   *
   * @param name the identifier a call names
   * @param title the name shown to a person
   * @param description what the tool does and answers, for the model
   * @param inputSchema JSON Schema of the arguments object
   */
  final case class Tool(name: String, title: String, description: String, inputSchema: JsonObject)

  /**
   * Fails a tool call with a message the model can act on: the arguments to correct, or the state to wait for.
   *
   * @param message what went wrong and what to do about it
   */
  final class ToolFailure(message: String) extends RuntimeException(message)

  /** The number of matches `find_symbol` lists when the call gives no limit. */
  private val DefaultSymbolLimit = 50

  /** Reports the state of the project; answers while it is still opening. */
  val ProjectStatus: Tool = Tool(
    name = "project_status",
    title = "Project status",
    description = "Reports whether the Scala project is open: its state (starting, importing, ready or failed), " +
      "module and source counts, the workspace root and the log file. Answers at once, also while the project imports.",
    inputSchema = schema("""{"type": "object", "properties": {}, "additionalProperties": false}"""),
  )

  /** Finds the project's declarations by name. */
  val FindSymbol: Tool = Tool(
    name = "find_symbol",
    title = "Find symbol",
    description = "Finds the project's classes, traits, objects, methods and other declarations by name. The query is " +
      "a symbol name, optionally qualified by its package or enclosing type, such as `core.Greeting` or " +
      "`Greeting.hello`. Each match is listed with its kind, its container and the file:line:column of its " +
      "declaration. Library symbols are not searched.",
    inputSchema = schema("""{
      "type": "object",
      "properties": {
        "query": {
          "type": "string",
          "description": "Symbol name, optionally qualified by package or enclosing type, e.g. core.Greeting or Greeting.hello."
        },
        "limit": {
          "type": "integer",
          "minimum": 1,
          "description": "Most matches to list (default 50)."
        }
      },
      "required": ["query"],
      "additionalProperties": false
    }"""),
  )

  /** Every tool, in the order `tools/list` gives them. */
  val All: Seq[Tool] = Seq(ProjectStatus, FindSymbol)

  /** Parses the JSON Schema of a tool's arguments. */
  private def schema(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

  /**
   * Reads a string member of a JSON object.
   *
   * @param from the object
   * @param name member name
   * @return the member's text; `None` when it is absent or is not a string
   */
  def stringMember(from: JsonObject, name: String): Option[String] =
    Option(from.get(name)).collect { case value: JsonPrimitive if value.isString => value.getAsString }

  /**
   * Reads an object member of a JSON object.
   *
   * @param from the object
   * @param name member name
   * @return the member; an empty object when it is absent or is not an object
   */
  def objectMember(from: JsonObject, name: String): JsonObject =
    Option(from.get(name)).collect { case value: JsonObject => value }.getOrElse(new JsonObject)

  /**
   * Lists the project symbols that match a name, one per line.
   *
   * Reads the session's symbol index, so the caller must have brought the session up to date with the disk.
   *
   * @param session the open project
   * @param root workspace root, which the listed paths are relative to when they are under it
   * @param arguments the call's arguments: `query` (required) and `limit` (optional, at least 1)
   * @return the matches in the session's order, best first, cut to the limit with a note saying so; a sentence
   *         saying that nothing matches when the query finds nothing
   * @throws ToolFailure when `query` is missing or blank, or `limit` is not an integer of at least 1
   */
  def findSymbol(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val query = stringMember(arguments, "query").map(_.trim).filter(_.nonEmpty)
      .getOrElse(throw new ToolFailure("`query` is required: give a symbol name such as `Greeting` or `core.Greeting`."))
    val limit = Option(arguments.get("limit")).fold(DefaultSymbolLimit) { value =>
      Try(value.getAsBigDecimal.intValueExact).filter(_ >= 1)
        .getOrElse(throw new ToolFailure(s"`limit` must be an integer of at least 1, not $value."))
    }

    val matches = session.workspaceSymbols(query)
    if (matches.isEmpty) s"No symbol matches $query."
    else {
      val shown = matches.take(limit)
      val note = Option.when(shown.size < matches.size)(s"(showing ${shown.size} of ${matches.size} matches)")

      (shown.map(listing(root, _)) ++ note).mkString("\n")
    }
  }

  /** One match as a line: its kind, name and container, then where its name is declared. */
  private def listing(root: Path, found: WorkspaceSymbolMatch): String = {
    val container = found.container.filter(_.nonEmpty).fold("")(name => s" in $name")
    val path = found.location.path
    val shownPath = if (path.startsWith(root)) root.relativize(path) else path
    val start = found.location.range.start

    s"${found.kind.toString.toLowerCase} ${found.name}$container — $shownPath:${start.line + 1}:${start.column + 1}"
  }
}
