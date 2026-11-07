package scalaanalyzer

/**
 * A span of one source text, in the coordinates of [[LineColumn]].
 *
 * @param start position of the span's first character
 * @param end position just after the span's last character, so an empty span has `start == end`
 */
final case class LineRange(start: LineColumn, end: LineColumn)
