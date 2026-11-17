package scalaanalyzer.mcp

import com.google.gson.JsonObject

import scalaanalyzer.{LineColumn, LineRange}
import scalaanalyzer.language.WorkspaceSymbolMatch
import scalaanalyzer.mcp.McpTools.ToolFailure
import scalaanalyzer.workspace.ProjectSession

import java.nio.file.Path

/**
 * The symbol or expression a tool call is about, once its arguments are resolved.
 *
 * @param path the project source that holds it, or the library file when it is a library declaration
 * @param range the name of a symbol given by `symbol`, or the first occurrence of `text` on the line given with
 *              `file` and `line`; the caret is its start
 * @param description the target as the call named it, for the answers that talk about it
 */
private[mcp] final case class ToolTarget(path: Path, range: LineRange, description: String) {
  /** The caret: where the target starts. */
  def at: LineColumn = range.start
}

/** Resolves the arguments that name a tool's target: either a `symbol`, or a `file`, a `line` and a `text`. */
private[mcp] object ToolTarget {
  /** The separator that `symbol` accepts for `.`, as in `Greeting#text`. */
  private val AlternativeSeparator = '#'

  /**
   * Resolves the target the arguments name, in one of two ways.
   *
   * With `symbol`, a project symbol found by name, qualified by its package and enclosing types as far as needed to be
   * the only one. With `file`, `line` (from 1) and `text`, the first occurrence of `text` on that line of that source.
   * Reads the session's symbol index and the sources' text, so the caller must have brought the session up to date with
   * the disk.
   *
   * @param session the open project
   * @param root workspace root, which a relative `file` is relative to
   * @param arguments the call's arguments
   * @return the target
   * @throws ToolFailure when both forms or neither are given, or the form given does not find exactly one target
   */
  def resolve(session: ProjectSession, root: Path, arguments: JsonObject): ToolTarget = {
    val named = arguments.has("symbol")
    val positioned = Seq("file", "line", "text").exists(arguments.has)

    if (named == positioned) {
      throw new ToolFailure(
        "Name the target in exactly one of two ways: `symbol` (such as `core.Greeting` or `Greeting.text`), or " +
          "`file`, `line` and `text` together.")
    }

    if (named) bySymbol(session, root, arguments) else byPosition(session, root, arguments)
  }

  /**
   * Resolves the `file` argument to a project source.
   *
   * @param session the open project
   * @param root workspace root, which a relative `file` is relative to
   * @param arguments the call's arguments
   * @return the path of the source
   * @throws ToolFailure when `file` is missing, or is not a source of the project
   */
  def sourceFile(session: ProjectSession, root: Path, arguments: JsonObject): Path = {
    val file = McpTools.stringMember(arguments, "file").filter(_.nonEmpty)
      .getOrElse(throw new ToolFailure("`file` is required: give a project source, relative to the root or absolute."))
    val path = root.resolve(file).normalize()

    if (!session.isSource(path)) {
      throw new ToolFailure(s"$file is not a source file of the project; give a path relative to the root or absolute.")
    }

    path
  }

  /** The symbol the call names, resolved through the session's symbol search. */
  private def bySymbol(session: ProjectSession, root: Path, arguments: JsonObject): ToolTarget = {
    val symbol = McpTools.stringMember(arguments, "symbol").map(_.trim).filter(_.nonEmpty)
      .getOrElse(throw new ToolFailure("`symbol` must be a symbol name such as `Greeting` or `core.Greeting`."))
    val wanted = symbol.replace(AlternativeSeparator, '.')
    val candidates = session.workspaceSymbols(wanted)
      .filter(isNamed(_, wanted))
      // A case class and its synthetic companion share the span of the name.
      .distinctBy(_.location)

    candidates match {
      case Seq() => throw new ToolFailure(s"No project symbol is named $symbol; find_symbol searches by partial name.")
      case Seq(only) => ToolTarget(only.location.path, only.location.range, symbol)
      case several =>
        val listed = several.map(Listings.symbol(root, _)).mkString("\n")

        throw new ToolFailure(
          s"$symbol names several symbols:\n$listed\nQualify the name, or give `file`, `line` and `text`.")
    }
  }

  /** Whether the symbol's qualified name is `wanted`, or ends with it after a `.`. */
  private def isNamed(found: WorkspaceSymbolMatch, wanted: String): Boolean = {
    val qualified = found.container.filter(_.nonEmpty).fold(found.name)(container => s"$container.${found.name}")

    qualified == wanted || qualified.endsWith("." + wanted)
  }

  /** The first occurrence of `text` on the `line` of the `file` the call gives. */
  private def byPosition(session: ProjectSession, root: Path, arguments: JsonObject): ToolTarget = {
    def required(name: String): ToolFailure =
      new ToolFailure(s"`$name` is required, as the target is given by `file`, `line` and `text` together.")

    val path = sourceFile(session, root, arguments)
    val shown = Listings.shownPath(root, path)
    val lineNumber = McpTools.positiveInteger(arguments, "line").getOrElse(throw required("line"))
    val text = McpTools.stringMember(arguments, "text").map(_.trim).filter(_.nonEmpty).getOrElse(throw required("text"))

    val lines = Listings.sourceLines(session, path)
    val line = lines.lift(lineNumber - 1)
      .getOrElse(throw new ToolFailure(s"$shown has fewer than $lineNumber lines."))
    // The first occurrence is the tie-break when the text is on the line more than once.
    val column = line.indexOf(text)

    if (column < 0) throw new ToolFailure(s"`$text` is not on line $lineNumber of $shown, which reads: $line")

    val start = LineColumn(lineNumber - 1, column)

    ToolTarget(path, LineRange(start, LineColumn(start.line, column + text.length)), s"`$text` at $shown:$lineNumber")
  }
}
