package scalaanalyzer.workspace

import java.nio.file.Path

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
   * inside the root, since their modules and included builds sit in nested directories.
   *
   * @param root workspace root
   * @param isModuleBase whether a directory is the base directory of one of the build's imported modules
   * @param path changed file; must have a file name, so not a filesystem root
   * @return `true` for a `.sbt` file in the root, its `project` directory or a module's base directory, for a
   *         `.scala` file or `build.properties` in that `project` directory, and for a `pom.xml`, `*.gradle`,
   *         `*.gradle.kts`, `gradle.properties` or `gradle/libs.versions.toml` anywhere under the root; `false`
   *         otherwise
   */
  def isPart(root: Path, isModuleBase: Path => Boolean, path: Path): Boolean = {
    val name = path.getFileName.toString
    val parent = path.getParent
    val inProject = parent == root.resolve("project")

    (name.endsWith(".sbt") && (parent == root || inProject || isModuleBase(parent))) ||
      (inProject && (name.endsWith(".scala") || name == "build.properties")) ||
      (path.startsWith(root) && isMavenOrGradleFile(path))
  }

  /** Whether the file's name is one that Maven or Gradle reads to define a build. */
  private def isMavenOrGradleFile(path: Path): Boolean = {
    val name = path.getFileName.toString
    val inGradleDirectory = Option(path.getParent).flatMap(parent => Option(parent.getFileName)).exists(_.toString == "gradle")

    name == "pom.xml" || name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name == "gradle.properties" ||
      (name == "libs.versions.toml" && inGradleDirectory)
  }
}
