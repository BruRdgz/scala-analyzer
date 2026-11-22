package scalaanalyzer.workspace

import java.nio.file.Path

import scala.jdk.CollectionConverters.*

/**
 * Which files define the workspace's build (sbt, Maven or Gradle): a client watches them, and a change to one
 * reloads the build.
 */
private[scalaanalyzer] object BuildDefinition {
  /** Watcher glob for sbt build files at any depth. */
  val SbtFiles = "**/*.sbt"

  /** Watcher glob for the Scala sources of a build's `project` directory. */
  val ProjectFiles = "**/project/*.scala"

  /** Watcher glob for the `build.properties` file of a build's `project` directory. */
  val BuildProperties = "**/project/build.properties"

  /** Watcher glob for Maven's `pom.xml` at any depth. */
  val MavenFiles = "**/pom.xml"

  /** Watcher globs for Gradle's build scripts, properties and version catalog at any depth. */
  val GradleFiles: Seq[String] =
    Seq("**/*.gradle", "**/*.gradle.kts", "**/gradle.properties", "**/gradle/libs.versions.toml")

  /**
   * Whether `path` can change the workspace's build definition and therefore requires reloading the build (a BSP
   * workspace reload for sbt, Bloop's install step for Maven and Gradle).
   *
   * For sbt only the build at the workspace root counts: the build files of other builds under it, such as test
   * fixtures or other checkouts, are not part of it. sbt loads the `.sbt` files in the base directory of each of the
   * build's projects, so a subproject's `.sbt` file is part of it as well. Maven and Gradle files count at any depth
   * inside the root, since their modules and included builds sit in nested directories, but only in a workspace
   * imported through Maven or Gradle, and not under the directories that tools write their output to
   * (see [[isInToolDirectory]]).
   *
   * @param root workspace root
   * @param mavenOrGradle whether the workspace is a Maven or Gradle build (see `BspImport.importsThroughBloop`)
   * @param isModuleBase whether a directory is the base directory of one of the build's imported modules
   * @param path changed file; must have a file name, so not a filesystem root
   * @return `true` for a `.sbt` file in the root, its `project` directory or a module's base directory, for a
   *         `.scala` file or `build.properties` in that `project` directory, and for a `pom.xml`, `*.gradle`,
   *         `*.gradle.kts`, `gradle.properties` or `gradle/libs.versions.toml` under the root of a Maven or Gradle
   *         workspace, outside tool directories; `false` otherwise
   */
  def isPart(root: Path, mavenOrGradle: Boolean, isModuleBase: Path => Boolean, path: Path): Boolean = {
    val name = path.getFileName.toString
    val parent = path.getParent
    val inProject = parent == root.resolve("project")

    (name.endsWith(".sbt") && (parent == root || inProject || isModuleBase(parent))) ||
      (inProject && (name.endsWith(".scala") || name == "build.properties")) ||
      (mavenOrGradle && path.startsWith(root) && isMavenOrGradleFile(path) && !isInToolDirectory(root, path))
  }

  /**
   * Whether a directory is where a build tool or an editor writes its output or its cache, so that no build
   * definition of the project lives in it: `target`, `build`, `node_modules` and any hidden directory (`.gradle`,
   * `.bloop`, `.bsp`, `.git`, ...).
   *
   * @param name the directory's name
   */
  private[scalaanalyzer] def isToolDirectoryName(name: String): Boolean =
    name.startsWith(".") || name == "target" || name == "build" || name == "node_modules"

  /** Whether a directory between the root and the file is a tool directory; the root's own name does not count. */
  private def isInToolDirectory(root: Path, path: Path): Boolean = {
    val directories = root.relativize(path).getParent

    directories != null && directories.iterator.asScala.exists(directory => isToolDirectoryName(directory.toString))
  }

  /**
   * Whether the file's name is one that Maven or Gradle reads to define a build.
   *
   * @param path the file, whose directory decides only for `libs.versions.toml`
   */
  private[scalaanalyzer] def isMavenOrGradleFile(path: Path): Boolean = {
    val name = path.getFileName.toString
    val inGradleDirectory = Option(path.getParent).flatMap(parent => Option(parent.getFileName)).exists(_.toString == "gradle")

    name == "pom.xml" || name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name == "gradle.properties" ||
      (name == "libs.versions.toml" && inGradleDirectory)
  }
}
