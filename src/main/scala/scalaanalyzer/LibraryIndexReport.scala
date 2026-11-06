package scalaanalyzer

/**
 * Outcome of indexing one library root.
 *
 * @param classFiles   class and TASTy files accepted by the caller's entry filter
 * @param scalaIndexed top-level Scala class files and TASTy files whose decompiled PSI went into the index
 * @param javaClasses  Java class files: not indexed, the host's class finder reads them by path on demand
 * @param notTopLevel  class files without a PSI file of their own (inner and companion classes, classes of TASTy files)
 * @param refused      root-relative paths of Scala class files with no stub tree to index (their syntax tree was
 *                     already loaded)
 * @param occurrences  postings in the whole symbol index after this root was indexed, including those of earlier roots
 *                     and project sources
 */
final case class LibraryIndexReport(
  classFiles: Int,
  scalaIndexed: Int,
  javaClasses: Int,
  notTopLevel: Int,
  refused: Seq[String],
  occurrences: Int,
)
