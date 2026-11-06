package scalaanalyzer

/**
 * A 0-based line and UTF-16 column used by the build and workspace APIs.
 *
 * @param line zero-based line index
 * @param column zero-based UTF-16 code-unit offset within the line
 */
final case class LineColumn(line: Int, column: Int)
