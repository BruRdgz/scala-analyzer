package scalaanalyzer.mcp

import scalaanalyzer.LineColumn
import scalaanalyzer.language.{FileProblem, HierarchyItem, SourceSpan, WorkspaceSymbolMatch}
import scalaanalyzer.workspace.ProjectSession

import java.nio.file.Path

/**
 * How the tools show what they found: one line per entry, lines and columns counting from 1 (the session counts from
 * 0), paths relative to the workspace root when they are under it.
 */
private[mcp] object Listings {
  /**
   * Reads the lines of a project source, as the session has its text.
   *
   * @param session the open project
   * @param path a project source
   * @return the lines without their line breaks, the last one empty when the text ends with a line break
   */
  def sourceLines(session: ProjectSession, path: Path): Seq[String] =
    session.text(path).split("\n", -1).toSeq.map(_.stripSuffix("\r"))

  /**
   * The path as a tool shows it.
   *
   * @param root workspace root
   * @param path a file
   * @return the path relative to the root when it is under it, otherwise as it is
   */
  def shownPath(root: Path, path: Path): Path = if (path.startsWith(root)) root.relativize(path) else path

  /** A position as `path:line:column`. */
  private def place(root: Path, path: Path, at: LineColumn): String =
    s"${shownPath(root, path)}:${at.line + 1}:${at.column + 1}"

  /**
   * A project symbol as a line: its kind, name and container, then where its name is declared.
   *
   * @param root workspace root
   * @param found the match
   * @return the line, without the source line
   */
  def symbol(root: Path, found: WorkspaceSymbolMatch): String = {
    val container = found.container.filter(_.nonEmpty).fold("")(name => s" in $name")
    val where = place(root, found.location.path, found.location.range.start)

    s"${found.kind.toString.toLowerCase} ${found.name}$container — $where"
  }

  /**
   * A position as a location line: `path:line:column`, then the trimmed source line when the path is a project source.
   *
   * @param session the open project
   * @param root workspace root
   * @param path the file of the position
   * @param at the position
   * @return the line; a library file has the position only
   */
  def location(session: ProjectSession, root: Path, path: Path, at: LineColumn): String = {
    val where = place(root, path, at)

    if (!session.isSource(path)) where
    else s"$where  ${sourceLines(session, path).lift(at.line).fold("")(_.trim)}"
  }

  /**
   * The start of a span as a location line.
   *
   * @param session the open project
   * @param root workspace root
   * @param found the span
   * @return the line, as `location` gives it
   */
  def span(session: ProjectSession, root: Path, found: SourceSpan): String =
    location(session, root, found.path, found.range.start)

  /**
   * A call or type hierarchy item as a line: its kind and name, then the location line of its name.
   *
   * @param session the open project
   * @param root workspace root
   * @param found the item
   * @return the line
   */
  def item(session: ProjectSession, root: Path, found: HierarchyItem): String = {
    val where = location(session, root, found.location.path, found.selectionRange.start)

    s"${found.kind.toString.toLowerCase} ${found.name} — $where"
  }

  /**
   * A problem as a line: where it starts, its severity and message, then the label that says what reported it.
   *
   * @param root workspace root
   * @param path the source that has the problem
   * @param found the problem
   * @param label what reported it: the inspection's name, or the producer
   * @return the line; a message of several lines, as the Scala 3 compiler writes some, goes on in indented lines
   */
  def problem(root: Path, path: Path, found: FileProblem, label: String): String = {
    val message = found.message.linesIterator.mkString("\n    ")

    s"${place(root, path, found.start)}  ${found.severity.toString.toLowerCase}: $message [$label]"
  }

  /**
   * A count and its noun, singular for one.
   *
   * @param count how many
   * @param noun the noun in the singular; its plural adds an `s`
   * @return the count followed by the noun
   */
  def counted(count: Int, noun: String): String = if (count == 1) s"1 $noun" else s"$count ${noun}s"

  /**
   * Shows the first entries of a list, one per line.
   *
   * @param entries what was found, not empty
   * @param limit the most entries to show
   * @param noun what the entries are, in the plural, for the note that says how many were left out
   * @param format shows one entry as a line; it runs for the entries shown only
   * @return the lines, then a note with the counts when the list was cut
   */
  def list[A](entries: Seq[A], limit: Int, noun: String)(format: A => String): String = {
    val shown = entries.take(limit)
    val note = Option.when(shown.size < entries.size)(s"(showing ${shown.size} of ${entries.size} $noun)")

    (shown.map(format) ++ note).mkString("\n")
  }
}
