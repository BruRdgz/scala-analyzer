package scalaanalyzer.workspace

import java.nio.file.Path

/** Which files define an sbt build: a client watches them, and a change to one reloads the build. */
private[scalaanalyzer] object BuildDefinition {
  /** Watcher glob for sbt build files at any depth. */
  val SbtFiles = "**/*.sbt"

  /** Watcher glob for the Scala sources of a build's `project` directory. */
  val ProjectFiles = "**/project/*.scala"

  /** Watcher glob for the `build.properties` file of a build's `project` directory. */
  val BuildProperties = "**/project/build.properties"

  /**
   * Whether `path` can change the workspace's sbt build definition and therefore requires a BSP workspace reload.
   *
   * Only the build at the workspace root counts: the build files of other builds under it, such as test fixtures or
   * other checkouts, are not part of it. sbt loads the `.sbt` files in the base directory of each of the build's
   * projects, so a subproject's `.sbt` file is part of it as well.
   *
   * @param root workspace root
   * @param isModuleBase whether a directory is the base directory of one of the build's imported modules
   * @param path changed file; must have a file name, so not a filesystem root
   * @return `true` for a `.sbt` file in the root, its `project` directory or a module's base directory, and for a
   *         `.scala` file or `build.properties` in that `project` directory; `false` otherwise
   */
  def isPart(root: Path, isModuleBase: Path => Boolean, path: Path): Boolean = {
    val name = path.getFileName.toString
    val parent = path.getParent
    val inProject = parent == root.resolve("project")

    (name.endsWith(".sbt") && (parent == root || inProject || isModuleBase(parent))) ||
      (inProject && (name.endsWith(".scala") || name == "build.properties"))
  }
}
