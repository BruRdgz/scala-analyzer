package scalaanalyzer.mcp

import scalaanalyzer.workspace.ProjectSession
import scalaanalyzer.workspace.ProjectSession.DiskChange

import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.nio.file.{Files, NoSuchFileException, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Notices the edits made to a workspace on disk, which an MCP client cannot report: it compares the source files and
 * the build definition with how they were when it last looked.
 *
 * It looks at the `.scala` and `.java` files under the session's source directories, and at the files that define
 * the build (see `BuildDefinition.isPart`): the `.sbt` files in the root, its `project` directory and the modules'
 * base directories, and the `.scala` and `build.properties` files in the `project` directory. For Maven and Gradle
 * it looks at `pom.xml`, `*.gradle`, `*.gradle.kts` and `gradle.properties` in the root and the modules' base
 * directories, and at `gradle/libs.versions.toml` in the root. A file counts as changed when its modification time or size differs.
 *
 * Not thread safe: one scan runs at a time, and what a scan leaves is visible to the next thread that scans after it.
 *
 * @param root workspace root, whose build definition is scanned
 */
private[mcp] final class DiskScan(root: Path) {
  /** The files as the last accepted scan saw them, by path. */
  private var accepted = Map.empty[Path, DiskScan.FileStamp]

  /**
   * Reads the files as they are now and compares them with the last accepted scan.
   *
   * Nothing is remembered until `accept`: a caller that fails to apply the changes observes them again next time.
   *
   * @param session session whose last imported source directories and module base directories are scanned
   * @return the files found and what differs from the last accepted scan
   */
  def observe(session: ProjectSession): DiskScan.Observation = {
    val files = stampsOf(session)

    DiskScan.Observation(files, changesBetween(accepted, files))
  }

  /**
   * Takes an observation as the state that later observations are compared with.
   *
   * @param observation what `observe` returned, once its changes are applied
   */
  def accept(observation: DiskScan.Observation): Unit = accepted = observation.files

  /**
   * Takes the files as they are now as the state that later observations are compared with, reporting no change.
   *
   * Call it before reading the files that the session is brought up to date with: an edit made while they are read
   * then differs from what was taken, and is reported next time.
   *
   * @param session session whose last imported source directories and module base directories are scanned
   */
  def restart(session: ProjectSession): Unit = accepted = stampsOf(session)

  /** Reads the stamp of every file of the scan. */
  private def stampsOf(session: ProjectSession): Map[Path, DiskScan.FileStamp] = {
    val project = root.resolve("project")
    val buildFiles =
      Seq(root -> "*.sbt", project -> "*.sbt", project -> "*.scala", project -> "build.properties") ++
        session.moduleBaseDirectories.map(_ -> "*.sbt") ++
        (root +: session.moduleBaseDirectories).flatMap(directory => DiskScan.MavenAndGradleGlobs.map(directory -> _)) :+
        (root.resolve("gradle") -> "libs.versions.toml")
    val files = session.sourceDirectories.flatMap(sourcesUnder) ++
      buildFiles.distinct.flatMap((directory, glob) => listed(directory, glob))

    files.flatMap(path => stampOf(path).map(path -> _)).toMap
  }

  /** The `.scala` and `.java` files at any depth under a directory; none when it does not exist yet. */
  private def sourcesUnder(directory: Path): Seq[Path] =
    if (!Files.isDirectory(directory)) Seq.empty
    else Using.resource(
      Files.find(directory, Int.MaxValue, (path, attributes) => attributes.isRegularFile && isSourceName(path))
    )(_.iterator.asScala.toSeq)

  /** The entries of a directory whose names match the glob; none when the directory does not exist. */
  private def listed(directory: Path, glob: String): Seq[Path] =
    if (!Files.isDirectory(directory)) Seq.empty
    else Using.resource(Files.newDirectoryStream(directory, glob))(_.asScala.toSeq)

  /** Whether a path is named as a Scala or Java source. */
  private def isSourceName(path: Path): Boolean = {
    val name = path.getFileName.toString

    name.endsWith(".scala") || name.endsWith(".java")
  }

  /** The stamp of a regular file; `None` when it is another kind of entry, or was deleted since it was listed. */
  private def stampOf(path: Path): Option[DiskScan.FileStamp] =
    try {
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])

      Option.when(attributes.isRegularFile)(DiskScan.FileStamp(attributes.lastModifiedTime, attributes.size))
    } catch { case _: NoSuchFileException => None }

  /** The files created, changed and deleted from `before` to `after`, each kind in path order. */
  private def changesBetween(
    before: Map[Path, DiskScan.FileStamp],
    after: Map[Path, DiskScan.FileStamp],
  ): Seq[DiskChange] = {
    val created = (after.keySet -- before.keySet).toSeq.sorted.map(DiskChange(_, DiskChange.Kind.Created))
    val changed = after.keySet.intersect(before.keySet).filter(path => before(path) != after(path)).toSeq.sorted
      .map(DiskChange(_, DiskChange.Kind.Changed))
    val deleted = (before.keySet -- after.keySet).toSeq.sorted.map(DiskChange(_, DiskChange.Kind.Deleted))

    created ++ changed ++ deleted
  }
}

/** The values a [[DiskScan]] exchanges with its caller. */
private[mcp] object DiskScan {
  /** Names of the files that define a Maven or Gradle build, as globs of the directory that holds them. */
  private val MavenAndGradleGlobs = Seq("pom.xml", "*.gradle", "*.gradle.kts", "gradle.properties")

  /**
   * What identifies a file's content cheaply: files with the same stamp are taken to have the same content.
   *
   * @param modified the file's modification time
   * @param size the file's length in bytes
   */
  private[mcp] final case class FileStamp(modified: FileTime, size: Long)

  /**
   * What one scan found.
   *
   * @param files the stamp of every file of the scan, by path
   * @param changes how the files differ from the last accepted scan: created first, then changed, then deleted
   */
  private[mcp] final case class Observation(files: Map[Path, FileStamp], changes: Seq[DiskChange])
}
