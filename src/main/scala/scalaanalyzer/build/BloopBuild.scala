package scalaanalyzer.build

import scalaanalyzer.platform.HostLog

import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Maven and Gradle builds, which the host imports through Bloop: it runs the build tool's Bloop install step in the
 * project (the way an IDE imports such a build), which writes the JSON files of `.bloop/`, and then speaks BSP with a Bloop server
 * over a Unix domain socket, since `bloop bsp` has no standard input and output mode.
 *
 * `BspImport.connect` chooses this transport when the workspace has no BSP connection files and its root holds a
 * Maven or Gradle build.
 */
private[build] object BloopBuild {
  /**
   * A build tool whose projects the host imports through Bloop.
   *
   * @param label name used in messages
   * @param rootFiles files whose presence in the workspace root makes it a build of this tool
   * @param wrapper the project's own launcher script, preferred when it is executable
   * @param command the launcher looked up on the `PATH` otherwise
   */
  enum Tool(val label: String, val rootFiles: Seq[String], val wrapper: String, val command: String) {
    case Maven extends Tool("Maven", Seq("pom.xml"), "mvnw", "mvn")
    case Gradle extends Tool(
      "Gradle", Seq("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"), "gradlew", "gradle")
  }

  /** Environment variable naming the JDK that runs the build tools, whose versions can be too old for the server's. */
  val BuildJavaHomeVariable = "SCALA_ANALYZER_BUILD_JAVA_HOME"

  /** Maven goal that writes Bloop's configuration; the plugin is fetched by Maven, so the pom stays untouched. */
  private val MavenGoal = "ch.epfl.scala:bloop-maven-plugin:2.0.5:bloopInstall"

  /** Gradle init script that applies the Bloop plugin to every project, so the build file stays untouched. */
  private val GradleInitScript =
    """initscript {
      |  repositories { mavenCentral() }
      |  dependencies { classpath 'ch.epfl.scala:gradle-bloop_2.13:1.6.4' }
      |}
      |allprojects {
      |  apply plugin: bloop.integrations.gradle.BloopPlugin
      |}
      |""".stripMargin

  /** Time between attempts to connect to the Bloop socket while the server starts. */
  private val SocketPollMillis = 100L

  /**
   * Which build tool the workspace root belongs to, from the files in it.
   *
   * @param root workspace root
   * @return the tool, or `None` when the root has neither a Maven nor a Gradle build; a root with both is rejected
   *         with `IllegalArgumentException` naming the files found, as the host does not choose between them
   */
  def detect(root: Path): Option[Tool] = {
    val found = Tool.values.toSeq
      .map(tool => tool -> tool.rootFiles.filter(file => Files.isRegularFile(root.resolve(file))))
      .filter((_, files) => files.nonEmpty)

    found match {
      case Seq() => None
      case Seq((tool, _)) => Some(tool)
      case several =>
        val described = several.map((tool, files) => s"${tool.label} (${files.mkString(", ")})")
        throw new IllegalArgumentException(
          s"$root holds both ${described.mkString(" and ")}: the host cannot choose which build to import; " +
            "open a directory with only one of them")
    }
  }

  /**
   * The launcher to run for a tool: the project's wrapper when it is an executable file in the root, else the
   * tool's launcher from the `PATH`.
   *
   * @param tool the project's build tool
   * @param root workspace root
   * @return the wrapper's absolute path, or the bare command name
   */
  def executable(tool: Tool, root: Path): String = {
    val wrapper = root.resolve(tool.wrapper)

    if (Files.isRegularFile(wrapper) && Files.isExecutable(wrapper)) wrapper.toAbsolutePath.toString else tool.command
  }

  /**
   * The command that writes Bloop's configuration for a Maven build.
   *
   * @param root workspace root
   * @return the command and its arguments; Maven runs in batch mode, so it never waits for input
   */
  def mavenCommand(root: Path): Seq[String] = Seq(executable(Tool.Maven, root), "-B", MavenGoal)

  /**
   * The command that writes Bloop's configuration for a Gradle build.
   *
   * @param root workspace root
   * @param initScript the init script that applies the Bloop plugin
   * @return the command and its arguments
   */
  def gradleCommand(root: Path, initScript: Path): Seq[String] =
    Seq(executable(Tool.Gradle, root), "--init-script", initScript.toString, "bloopInstall")

  /**
   * The JDK the build tools run on, as the environment selects it.
   *
   * @param environment the server's environment
   * @return the value of [[BuildJavaHomeVariable]] when it is set and not empty; `None` leaves the tool's own
   *         `JAVA_HOME` lookup untouched
   */
  def buildJavaHome(environment: collection.Map[String, String]): Option[String] =
    environment.get(BuildJavaHomeVariable).filter(_.nonEmpty)

  /**
   * Runs the tool's Bloop install step in the workspace root, so `.bloop/` describes the build as it is now.
   *
   * The tool's output goes to a temporary log file, named in the failure. It runs with the server's environment, and
   * with `JAVA_HOME` set from [[BuildJavaHomeVariable]] when that is set.
   *
   * @param tool the workspace's build tool
   * @param root workspace root, where the tool runs
   * @param timeoutMinutes deadline for the step
   * @throws IllegalStateException when the tool is missing, exits with a non-zero code or does not finish in time
   */
  def install(tool: Tool, root: Path, timeoutMinutes: Long): Unit = tool match {
    case Tool.Maven => run(mavenCommand(root), root, timeoutMinutes)
    case Tool.Gradle =>
      val initScript = Files.createTempFile("scala-analyzer-bloop", ".gradle")

      try {
        Files.writeString(initScript, GradleInitScript)
        run(gradleCommand(root, initScript), root, timeoutMinutes)
      } finally Files.deleteIfExists(initScript)
  }

  /** Runs one install command, logging to a new temporary file, until it exits or the deadline passes. */
  private def run(command: Seq[String], root: Path, timeoutMinutes: Long): Unit = {
    val log = Files.createTempFile("scala-analyzer-build", ".log")
    val shown = command.mkString(" ")
    val builder = new ProcessBuilder(command.asJava)
      .directory(root.toFile)
      .redirectErrorStream(true)
      .redirectOutput(log.toFile)
    buildJavaHome(System.getenv.asScala).foreach(builder.environment().put("JAVA_HOME", _))

    HostLog.info(s"writing Bloop's configuration: $shown (its output: $log)")
    val process =
      try builder.start()
      catch {
        case e: IOException =>
          throw new IllegalStateException(s"could not run `$shown`: ${e.getMessage}; its output: $log", e)
      }

    try {
      // The tool has no input to give: closing its standard input keeps it from waiting for any.
      process.getOutputStream.close()
      if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES))
        throw new IllegalStateException(s"`$shown` did not finish within $timeoutMinutes minutes; its output: $log")
      if (process.exitValue() != 0)
        throw new IllegalStateException(s"`$shown` exited with code ${process.exitValue()}; its output: $log")
    } finally BspImport.destroyProcess(process)
  }

  /**
   * A running Bloop BSP launcher and the socket connected to it.
   *
   * Closing releases the channel and the socket directory; ending the launcher process is the owner's part. The
   * Bloop server itself outlives the launcher, as sbt's does.
   *
   * @param process the `bloop bsp` launcher process
   * @param log file holding the launcher's output
   * @param channel connected socket, over which BSP is spoken
   * @param directory the directory holding the socket file; it is the launcher's alone
   */
  final case class Server(process: Process, log: Path, channel: SocketChannel, directory: Path) extends AutoCloseable {
    /** Closes the channel and deletes the socket directory; a failure of either is logged, not thrown. */
    override def close(): Unit = {
      try channel.close()
      catch { case NonFatal(e) => HostLog.warn("could not close the Bloop socket", e) }

      deleteDirectory(directory)
    }
  }

  /**
   * Starts `bloop bsp` on a new socket and connects to it.
   *
   * The launcher is ended on any failure, and so are the socket directory and its channel; on success the caller
   * owns the returned server and its process.
   *
   * @param root workspace root, where the launcher runs
   * @param timeoutMinutes how long to wait for the socket to accept a connection
   * @return the launcher, its log and the connected channel
   * @throws IllegalStateException when `bloop` is not on the `PATH`, or the socket does not accept in time
   * @throws BspImport.BuildServerExited when the launcher exits before the socket accepts
   */
  def serve(root: Path, timeoutMinutes: Long): Server = {
    // Unix socket paths hold about 100 bytes, which rules out the state directory and long temporary directories.
    val directory = Files.createTempDirectory("sa-bloop")
    val socket = directory.resolve("bsp.sock")
    val log = Files.createTempFile("scala-analyzer-bloop", ".log")
    val command = Seq("bloop", "bsp", "--protocol", "local", "--socket", socket.toString)

    HostLog.info(s"starting Bloop: ${command.mkString(" ")} (its output: $log)")
    val process =
      try new ProcessBuilder(command.asJava)
        .directory(root.toFile)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile)
        .start()
      catch {
        case e: IOException =>
          deleteDirectory(directory)
          throw new IllegalStateException(
            s"Maven and Gradle projects need Bloop on the PATH: could not run `bloop`: ${e.getMessage}", e)
      }

    try {
      BspImport.guardShutdown(process)
      val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)

      Server(process, log, awaitSocket(process, log, socket, deadline), directory)
    } catch {
      case e: Throwable =>
        BspImport.destroyProcess(process)
        deleteDirectory(directory)
        throw e
    }
  }

  /** Connects to the socket as soon as it accepts, giving up when the launcher exits or the deadline passes. */
  @tailrec
  private def awaitSocket(process: Process, log: Path, socket: Path, deadlineNanos: Long): SocketChannel = {
    if (!process.isAlive) throw new BspImport.BuildServerExited(process.exitValue(), log)
    if (System.nanoTime() - deadlineNanos > 0)
      throw new IllegalStateException(s"Bloop did not accept a connection on $socket in time; its output: $log")

    // The channel that connects carries the BSP session: a server may accept only one connection.
    val connected =
      try Some(SocketChannel.open(UnixDomainSocketAddress.of(socket)))
      catch {
        // Expected while the server starts: the socket file is missing or nothing listens on it yet.
        case e: IOException =>
          HostLog.debug(s"Bloop's socket is not accepting yet: $e")
          None
      }

    connected match {
      case Some(channel) => channel
      case None =>
        Thread.sleep(SocketPollMillis)
        awaitSocket(process, log, socket, deadlineNanos)
    }
  }

  /** Deletes a directory and what it holds; a failure is logged, not thrown. */
  private def deleteDirectory(directory: Path): Unit =
    try Using.resource(Files.walk(directory))(_.iterator.asScala.toSeq.reverse.foreach(Files.deleteIfExists(_)))
    catch { case NonFatal(e) => HostLog.warn(s"could not delete $directory", e) }
}
