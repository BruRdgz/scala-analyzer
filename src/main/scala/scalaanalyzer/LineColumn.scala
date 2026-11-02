package scalaanalyzer

/** A 0-based line and UTF-16 column used by the build and workspace APIs. */
final case class LineColumn(line: Int, column: Int)
