package scalaanalyzer.mcp

import com.google.gson.{JsonObject, JsonParser, JsonPrimitive}
import com.intellij.openapi.progress.ProcessCanceledException

import scalaanalyzer.language.{FileProblem, OutlineSymbol, ProblemSeverity}
import scalaanalyzer.platform.HostLock
import scalaanalyzer.workspace.ProjectSession

import java.nio.file.Path

import scala.util.Try

/**
 * The tools the MCP server offers, and what the ones that read the project answer.
 *
 * Tools address symbols by name, or by the text on a line (see [[ToolTarget]]), and report lines and columns counting
 * from 1 (the session counts from 0). A tool that lists what it found cuts the list to its `limit` argument.
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

  /** The number of entries the other tools list when the call gives no limit. */
  private val DefaultLimit = 100

  /** The number of problems `diagnostics` and `inspect` list when the call gives no limit: more than a model reads at once. */
  private val DefaultProblemLimit = 200

  /** What the descriptions of the tools that take a target say about how to name it. */
  private val TargetForms =
    "The target is named in exactly one of two ways: `symbol`, a name optionally qualified by package and enclosing " +
      "types (`core.Greeting`, `Greeting.text`); or `file`, `line` and `text`, the identifier as written on that line " +
      "of a project source."

  /** The properties of the arguments that name a target, as members of a JSON Schema's `properties`. */
  private val TargetProperties = """
    "symbol": {
      "type": "string",
      "description": "Symbol name, optionally qualified by package and enclosing types, e.g. core.Greeting or Greeting.text. A '#' separates like a '.'."
    },
    "file": {
      "type": "string",
      "description": "Project source, relative to the workspace root or absolute. Goes with line and text."
    },
    "line": {
      "type": "integer",
      "minimum": 1,
      "description": "Line of the file, counting from 1. Goes with file and text."
    },
    "text": {
      "type": "string",
      "description": "The identifier (for type_of, the expression) as written on that line. Its first occurrence on the line is the target. Goes with file and line."
    }"""

  /** The property of the arguments that cuts a list, as a member of a JSON Schema's `properties`. */
  private val LimitProperty = """
    "limit": {
      "type": "integer",
      "minimum": 1,
      "description": "Most entries to list (default 100)."
    }"""

  /** The properties of the arguments that cut the problems a tool reports, as members of a JSON Schema's properties. */
  private val ProblemOptions = """
    "severity": {
      "type": "string",
      "enum": ["error", "warning", "information"],
      "description": "The least severe level to report (default warning): error, warning or information."
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "description": "Most problems to list across all files (default 200)."
    }"""

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

  /** Says where a symbol is declared. */
  val Definition: Tool = Tool(
    name = "definition",
    title = "Go to definition",
    description = "Finds where the symbol at the target is declared, in the project or in a library: its " +
      s"file:line:column and source line. Most useful on a use of the symbol, with file, line and text; with symbol it " +
      s"answers the symbol's own declaration. $TargetForms Example: {\"file\": \"app/src/main/scala/app/Main.scala\", " +
      "\"line\": 7, \"text\": \"loud\"}.",
    inputSchema = targetSchema(limited = false),
  )

  /** Lists the usages of a symbol. */
  val References: Tool = Tool(
    name = "references",
    title = "Find references",
    description = "Lists the usages of the symbol at the target across the project sources, each with its " +
      s"file:line:column and source line; the declaration itself is not listed. $TargetForms " +
      "Example: {\"symbol\": \"core.Greeting\"}.",
    inputSchema = targetSchema(limited = true),
  )

  /** Lists the implementations of a trait, class or method. */
  val Implementations: Tool = Tool(
    name = "implementations",
    title = "Find implementations",
    description = "Lists the project declarations that implement or override the trait, class or method at the " +
      s"target, each with its file:line:column and source line. $TargetForms Example: {\"symbol\": \"Shape\"}.",
    inputSchema = targetSchema(limited = true),
  )

  /** Lists the methods that call a method. */
  val Callers: Tool = Tool(
    name = "callers",
    title = "Find callers",
    description = "Lists the methods that call the method at the target, one level: the kind, name and " +
      s"file:line:column of each caller. $TargetForms Example: {\"symbol\": \"Greeting.text\"}.",
    inputSchema = targetSchema(limited = true),
  )

  /** Lists the methods that a method calls. */
  val Callees: Tool = Tool(
    name = "callees",
    title = "Find callees",
    description = "Lists the methods that the method at the target calls, one level: the kind, name and " +
      s"file:line:column of each callee. $TargetForms Example: {\"symbol\": \"Main.main\"}.",
    inputSchema = targetSchema(limited = true),
  )

  /** Lists the direct supertypes of a class, trait or object. */
  val Supertypes: Tool = Tool(
    name = "supertypes",
    title = "Find supertypes",
    description = "Lists the direct supertypes of the class, trait or object at the target, one level: the kind, " +
      s"name and file:line:column of each, in the project or in a library. $TargetForms Example: {\"symbol\": \"Square\"}.",
    inputSchema = targetSchema(limited = true),
  )

  /** Lists the direct subtypes of a class, trait or object. */
  val Subtypes: Tool = Tool(
    name = "subtypes",
    title = "Find subtypes",
    description = "Lists the project's direct subtypes of the class, trait or object at the target, one level: the " +
      s"kind, name and file:line:column of each. $TargetForms Example: {\"symbol\": \"Shape\"}.",
    inputSchema = targetSchema(limited = true),
  )

  /** Tells the type of a symbol or an expression. */
  val TypeOf: Tool = Tool(
    name = "type_of",
    title = "Type of",
    description = "Tells the type of the symbol or expression at the target, as the IDE's Type Info does. With " +
      s"file, line and text, the text may be any expression on the line. $TargetForms " +
      "Example: {\"file\": \"app/src/main/scala/app/Main.scala\", \"line\": 7, \"text\": \"Greeting(\\\"world\\\").text\"}.",
    inputSchema = targetSchema(limited = false),
  )

  /** Shows the signature and documentation of a symbol. */
  val Describe: Tool = Tool(
    name = "describe",
    title = "Describe symbol",
    description = "Shows the signature and the documentation of the symbol at the target, as the IDE shows them on " +
      s"hover, in Markdown. $TargetForms Example: {\"symbol\": \"Greet3.hello\"}.",
    inputSchema = targetSchema(limited = false),
  )

  /** Lists the declarations of a file. */
  val Outline: Tool = Tool(
    name = "outline",
    title = "File outline",
    description = "Lists the declarations of a project source as an indented tree, as the IDE's structure view: each " +
      "line has the kind, the name, the signature and the line of the name. Example: " +
      "{\"file\": \"core/src/main/scala/core/Shape.scala\"}.",
    inputSchema = schema("""{
      "type": "object",
      "properties": {
        "file": {
          "type": "string",
          "description": "Project source, relative to the workspace root or absolute."
        }
      },
      "required": ["file"],
      "additionalProperties": false
    }"""),
  )

  /** Lists the problems the editor shows for a source or for the whole project. */
  val Diagnostics: Tool = Tool(
    name = "diagnostics",
    title = "Diagnostics",
    description = "Lists the problems the editor shows for a project source, or for every source: the Scala plugin's " +
      "annotator and inspections (type mismatches, unresolved names, unused code and the like) and, for Scala 3 " +
      "sources, the compiler's diagnostics of the current text. Scala 2 compiler errors come only from a build " +
      "compile, which this server does not run, so Scala 2 and Java sources are reported by the plugin's own " +
      "analysis (for Java, without javac). Each problem is a line `path:line:column  severity: message [source, " +
      "inspection name]`. Without `file`, every source is read, which can take minutes on a large project: prefer " +
      "`file` after editing one. Example: {\"file\": \"core/src/main/scala/core/Counter.scala\", \"severity\": \"error\"}.",
    inputSchema = schema(s"""{
      "type": "object",
      "properties": {
        "file": {
          "type": "string",
          "description": "Project source, relative to the workspace root or absolute. Without it, every source of the project."
        },$ProblemOptions
      },
      "additionalProperties": false
    }"""),
  )

  /** Runs the IDE's Inspect Code over a source or the whole project. */
  val Inspect: Tool = Tool(
    name = "inspect",
    title = "Inspect code",
    description = "Runs the IDE's Inspect Code over a Scala source, or over every Scala source of the project except " +
      "the generated ones: the selected inspections only, such as unused symbols, redundant code and possible " +
      "simplifications. It does not run the annotator or the compiler, and does not cover Java: `diagnostics` " +
      "reports those. Each problem is a line `path:line:column  severity: message [inspection name]`. Without " +
      "`file`, every source is read, which can take minutes on a large project: prefer `file` after editing one. " +
      "Example: {\"file\": \"core/src/main/scala/core/Counter.scala\"}.",
    inputSchema = schema(s"""{
      "type": "object",
      "properties": {
        "file": {
          "type": "string",
          "description": "Scala source, relative to the workspace root or absolute. Without it, every Scala source of the project."
        },$ProblemOptions
      },
      "additionalProperties": false
    }"""),
  )

  /** Every tool, in the order `tools/list` gives them. */
  val All: Seq[Tool] = Seq(
    ProjectStatus, FindSymbol, Definition, References, Implementations, Callers, Callees, Supertypes, Subtypes, TypeOf,
    Describe, Outline, Diagnostics, Inspect,
  )

  /** Parses the JSON Schema of a tool's arguments. */
  private def schema(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

  /** The JSON Schema of a tool that takes a target, and a `limit` when it lists what it found. */
  private def targetSchema(limited: Boolean): JsonObject = {
    val properties = if (limited) s"$TargetProperties,$LimitProperty" else TargetProperties

    schema(s"""{"type": "object", "properties": {$properties}, "additionalProperties": false}""")
  }

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
   * Reads an integer member of a JSON object that must be at least 1.
   *
   * @param from the object
   * @param name member name
   * @return the member; `None` when it is absent
   * @throws ToolFailure when the member is not an integer of at least 1
   */
  def positiveInteger(from: JsonObject, name: String): Option[Int] =
    Option(from.get(name)).map { value =>
      Try(value.getAsBigDecimal.intValueExact).filter(_ >= 1)
        .getOrElse(throw new ToolFailure(s"`$name` must be an integer of at least 1, not $value."))
    }

  /**
   * Runs a tool that reads the project.
   *
   * Reads the session's indexes and sources, so the caller must have brought the session up to date with the disk.
   *
   * @param tool the tool called, not `project_status`
   * @param session the open project
   * @param root workspace root, which the shown paths are relative to when they are under it
   * @param arguments the call's arguments
   * @return the answer as text: what the tool found, or a sentence saying that it found nothing
   * @throws ToolFailure when the arguments do not name what the tool needs
   * @throws IllegalStateException when the tool is not one that reads the project
   */
  def call(tool: Tool, session: ProjectSession, root: Path, arguments: JsonObject): String = tool match {
    case FindSymbol => findSymbol(session, root, arguments)
    case Definition => definition(session, root, arguments)
    case References => references(session, root, arguments)
    case Implementations => implementations(session, root, arguments)
    case Callers => calls(session, root, arguments, "callers", incoming = true)
    case Callees => calls(session, root, arguments, "callees", incoming = false)
    case Supertypes => types(session, root, arguments, "supertypes", subtypes = false)
    case Subtypes => types(session, root, arguments, "subtypes", subtypes = true)
    case TypeOf => typeOf(session, root, arguments)
    case Describe => describe(session, root, arguments)
    case Outline => outline(session, root, arguments)
    case Diagnostics => diagnostics(session, root, arguments)
    case Inspect => inspect(session, root, arguments)
    case other => throw new IllegalStateException(s"${other.name} is not a tool of the project")
  }

  /** Lists the symbols that match `query`, best first, cut to `limit` (at least 1, default 50) with a note saying so. */
  private def findSymbol(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val query = stringMember(arguments, "query").map(_.trim).filter(_.nonEmpty)
      .getOrElse(throw new ToolFailure("`query` is required: give a symbol name such as `Greeting` or `core.Greeting`."))
    val limit = positiveInteger(arguments, "limit").getOrElse(DefaultSymbolLimit)

    val matches = session.workspaceSymbols(query)
    if (matches.isEmpty) s"No symbol matches $query."
    else Listings.list(matches, limit, "matches")(Listings.symbol(root, _))
  }

  /** Locates the declarations of the symbol at the target. */
  private def definition(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val target = ToolTarget.resolve(session, root, arguments)
    val found = session.definition(target.path, target.at)

    answer(target, arguments, "declarations", found)((path, at) => Listings.location(session, root, path, at))
  }

  /** Lists the usages of the symbol at the target, without its declaration. */
  private def references(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val target = ToolTarget.resolve(session, root, arguments)
    val found = session.references(target.path, target.at, includeDeclaration = false)

    answer(target, arguments, "references", found)(Listings.span(session, root, _))
  }

  /** Lists the implementations and overriders of the symbol at the target. */
  private def implementations(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val target = ToolTarget.resolve(session, root, arguments)
    val found = session.implementations(target.path, target.at)

    answer(target, arguments, "implementations", found)(Listings.span(session, root, _))
  }

  /** Lists, one level, the callers or the callees of the method at the target. */
  private def calls(session: ProjectSession, root: Path, arguments: JsonObject, noun: String, incoming: Boolean): String = {
    val target = ToolTarget.resolve(session, root, arguments)
    val prepared = session.prepareHierarchy(target.path, target.at, types = false)
    if (prepared.isEmpty) throw new ToolFailure(s"${target.description} is not a method, so it has no $noun.")

    val found = prepared.flatMap(item => session.hierarchyCalls(target.path, item.target, incoming)).map(_.item).distinct

    answer(target, arguments, noun, found)(Listings.item(session, root, _))
  }

  /** Lists, one level, the supertypes or the subtypes of the class, trait or object at the target. */
  private def types(session: ProjectSession, root: Path, arguments: JsonObject, noun: String, subtypes: Boolean): String = {
    val target = ToolTarget.resolve(session, root, arguments)
    // The type hierarchy of a position inside a class is the class's; only the class a name declares or refers to counts.
    val declared = session.definition(target.path, target.at).toSet
    val prepared = session.prepareHierarchy(target.path, target.at, types = true)
      .filter(item => declared.contains((item.location.path, item.selectionRange.start)))
    if (prepared.isEmpty) {
      throw new ToolFailure(s"${target.description} is not a class, trait or object, so it has no $noun.")
    }

    val found = prepared.flatMap(item => session.hierarchyTypes(target.path, item.target, subtypes)).distinct

    answer(target, arguments, noun, found)(Listings.item(session, root, _))
  }

  /** Tells the type of the target: the name of a symbol, or the whole `text` of a line. */
  private def typeOf(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val target = ToolTarget.resolve(session, root, arguments)

    session.typeInfo(target.path, target.range).getOrElse(s"No type is known for ${target.description}.")
  }

  /** Shows the signature and documentation of the symbol at the target. */
  private def describe(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val target = ToolTarget.resolve(session, root, arguments)

    session.hover(target.path, target.at).getOrElse(s"No signature or documentation found for ${target.description}.")
  }

  /** Draws the declarations of the `file` as a tree, two spaces per level. */
  private def outline(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val path = ToolTarget.sourceFile(session, root, arguments)
    val symbols = session.outline(path)

    if (symbols.isEmpty) s"No declarations found in ${Listings.shownPath(root, path)}."
    else outlineLines(symbols, depth = 0).mkString("\n")
  }

  /** The lines of the symbols and, indented under each, of its children: kind, name, signature and line of the name. */
  private def outlineLines(symbols: Seq[OutlineSymbol], depth: Int): Seq[String] =
    symbols.flatMap { symbol =>
      val detail = if (symbol.detail.isEmpty) "" else s" ${symbol.detail}"
      val kind = symbol.kind.toString.toLowerCase
      val line = s"${"  " * depth}$kind ${symbol.name}$detail :${symbol.nameRange.start.line + 1}"

      line +: outlineLines(symbol.children, depth + 1)
    }

  /** Lists the problems the editor shows for the `file`, or for every source of the project. */
  private def diagnostics(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val names = inspectionNames(session)

    problemReport(session, root, arguments, session.sourceFiles.sorted, "checked")(path => session.problems(path)) {
      problem => (Seq(problem.source) ++ problem.inspectionId.map(id => names.getOrElse(id, id))).mkString(", ")
    }
  }

  /** Lists the problems of the selected inspections for the `file`, or for every inspectable source of the project. */
  private def inspect(session: ProjectSession, root: Path, arguments: JsonObject): String = {
    val names = inspectionNames(session)

    problemReport(session, root, arguments, session.inspectableSources, "inspected")(path => session.inspect(path)) {
      problem => problem.inspectionId.fold(problem.source)(id => names.getOrElse(id, id))
    }
  }

  /** The display name of each inspection, by the id that its problems carry. */
  private def inspectionNames(session: ProjectSession): Map[String, String] =
    session.inspectionDescriptions.map(description => description.id -> description.displayName).toMap

  /**
   * The answer of a tool that reports problems: those of the `file`, or of every source, at the `severity` argument or
   * above, cut to `limit` with a note saying so. A run over every source starts with a line that counts what it read.
   * Checks, before each source, that the call is still wanted.
   */
  private def problemReport(
    session: ProjectSession,
    root: Path,
    arguments: JsonObject,
    everySource: Seq[Path],
    verb: String,
  )(problemsOf: Path => Seq[FileProblem])(label: FileProblem => String): String = {
    val least = severityMember(arguments)
    val limit = positiveInteger(arguments, "limit").getOrElse(DefaultProblemLimit)
    val file = Option.when(arguments.has("file"))(ToolTarget.sourceFile(session, root, arguments))
    val sources = file.fold(everySource)(Seq(_))

    val byFile = sources.map { path =>
      if (HostLock.isRequestCancelled) throw new ProcessCanceledException

      // A write that cancels the query is not retried, as the language server does: tool calls run one at a time and
      // the disk is followed only before a call starts, so nothing edits the session while one runs.
      val problems = problemsOf(path)
        // ProblemSeverity declares the most severe level first.
        .filter(_.severity.ordinal <= least.ordinal)
        .sortBy(problem => (problem.start.line, problem.start.column))

      path -> problems
    }
    val found = byFile.flatMap((path, problems) => problems.map(path -> _))

    val scope = file.fold(Listings.counted(sources.size, "source"))(path => Listings.shownPath(root, path).toString)
    if (found.isEmpty) s"No problems at ${least.toString.toLowerCase} level or above in $scope."
    else {
      val filesWithProblems = byFile.count((_, problems) => problems.nonEmpty)
      val summary = Option.when(file.isEmpty)(
        s"${Listings.counted(sources.size, "source")} $verb: ${Listings.counted(found.size, "problem")} in " +
          s"${Listings.counted(filesWithProblems, "file")}.")
      val lines = Listings.list(found, limit, "problems") { (path, problem) =>
        Listings.problem(root, path, problem, label(problem))
      }

      (summary.toSeq :+ lines).mkString("\n")
    }
  }

  /** The `severity` argument: the least severe level a problem may have to be reported, warning when it is absent. */
  private def severityMember(arguments: JsonObject): ProblemSeverity =
    if (!arguments.has("severity")) ProblemSeverity.Warning
    else {
      val named = stringMember(arguments, "severity")

      ProblemSeverity.values
        .find(severity => named.contains(severity.toString.toLowerCase))
        .getOrElse(throw new ToolFailure(
          s"`severity` must be \"error\", \"warning\" or \"information\", not ${arguments.get("severity")}."))
    }

  /** The answer of a tool that lists its findings: the entries cut to `limit`, or a sentence saying that none was found. */
  private def answer[A](target: ToolTarget, arguments: JsonObject, noun: String, found: Seq[A])(
    format: A => String,
  ): String =
    if (found.isEmpty) s"No $noun found for ${target.description}."
    else Listings.list(found, positiveInteger(arguments, "limit").getOrElse(DefaultLimit), noun)(format)
}
