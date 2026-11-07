package scalaanalyzer.lsp

import org.eclipse.lsp4j.launch.LSPLauncher

import scalaanalyzer.{HostConfig, ScalaPsiHost}
import scalaanalyzer.platform.HostLog

import java.io.{File, InputStream, OutputStream}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Runs the language server over stdin and stdout.
 *
 * System properties: `scalaanalyzer.stateDir` (where the platform keeps its state) and `scalaanalyzer.scalaPluginJars`
 * (the Scala plugin's jars, which must also be on the classpath). Environment: `SCALA_ANALYZER_LOG=debug` logs every
 * request.
 *
 * The log is written to `<stateDir>/logs/scala-analyzer-<start time>.log` (the latest logs are kept) and sent to the
 * client.
 */
object Main {
  /**
   * The packaged release version, or a development marker when launched from loose classes.
   *
   * It is the implementation version of the jar manifest; classes without one report `development`.
   */
  val version: String = Option(getClass.getPackage.getImplementationVersion).getOrElse("development")

  /** Number of log files kept in the log directory, counting the one being opened. */
  private val KeptLogs = 10

  // Child-process hooks only send kill signals; a stuck platform hook must not keep the JVM alive after cleanup.
  /** Milliseconds `sys.exit` may take before the watchdog halts the JVM. */
  private val HaltDelayMs = 1000L

  /**
   * Starts the PSI host and serves LSP on standard input and output.
   *
   * Configuration comes from the system properties and environment described on [[Main]]. Standard output is
   * reserved for protocol traffic; other process output is redirected to standard error. A missing required system
   * property fails with `IllegalArgumentException` before the host starts.
   *
   * @param args launcher arguments, currently unused
   */
  def main(args: Array[String]): Unit = {
    /** Reads required launcher configuration, failing before the host is started when it is absent. */
    def property(name: String): String =
      sys.props.getOrElse(name, throw new IllegalArgumentException(s"system property $name is not set"))

    // Stdout carries the protocol: anything else printed there would corrupt it, so it goes to stderr.
    val protocolOut = System.out
    System.setOut(System.err)
    val stateDir = Path.of(property("scalaanalyzer.stateDir"))
    if (sys.env.get("SCALA_ANALYZER_LOG").exists(_.equalsIgnoreCase("debug"))) HostLog.threshold = HostLog.Level.Debug
    val logFile = openLogFile(stateDir.resolve("logs"))
    // What is logged before the client connects (the host's startup) is sent to it when it connects.
    val startup = new java.util.concurrent.ConcurrentLinkedQueue[HostLog.Entry]()
    val buffering = HostLog.addSink(entry => startup.add(entry))
    val heap = Runtime.getRuntime.maxMemory / (1024 * 1024)

    HostLog.info(s"scala-analyzer $version starting: JDK ${System.getProperty("java.version")}, heap max $heap MB, state $stateDir, log $logFile")
    val host = try HostLog.timed("host started")(ScalaPsiHost.start(HostConfig(
      stateDir = stateDir,
      scalaPluginJars = property("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
    )))
    finally buffering.close()
    val server = new ScalaLanguageServer(host, code => {
      val watchdog = new Thread(() => {
        Thread.sleep(HaltDelayMs)
        Runtime.getRuntime.halt(code)
      }, "scala-analyzer-halt")
      watchdog.setDaemon(true)
      watchdog.start()
      sys.exit(code)
    }, Some(logFile), startup.asScala.toSeq)

    listen(server, System.in, protocolOut)
  }

  /**
   * Serves `server` on the given streams until the client's transport ends, then exits the server.
   *
   * A disconnected transport has the same lifetime as an explicit `exit`, including failed reads: a transport
   * failure is logged as a warning, and `server.exit()` runs however the listening ends.
   *
   * @param server language server that answers the client
   * @param input stream that carries the client's messages
   * @param output stream that carries the server's messages
   */
  private[lsp] def listen(server: ScalaLanguageServer, input: InputStream, output: OutputStream): Unit = {
    val launcher = new LSPLauncher.Builder[HostProtocol.Client]()
      .setLocalService(server)
      .setRemoteInterface(classOf[HostProtocol.Client])
      .setInput(input)
      .setOutput(output)
      .create()

    try {
      server.connect(launcher.getRemoteProxy)
      try launcher.startListening().get()
      catch { case NonFatal(e) => HostLog.warn("client transport failed", e) }
    }
    finally server.exit()
  }

  /** Opens a process-lifetime log sink and retains only the newest KeptLogs files. */
  private def openLogFile(directory: Path): Path = {
    Files.createDirectories(directory)

    val name = s"scala-analyzer-${LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${ProcessHandle.current.pid}.log"
    val file = directory.resolve(name)
    val earlier = scala.util.Using.resource(Files.list(directory))(_.iterator.asScala.toVector)
      .filter(_.getFileName.toString.startsWith("scala-analyzer-")).sorted

    earlier.dropRight(KeptLogs - 1).foreach(Files.deleteIfExists)
    val out = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

    HostLog.addSink { entry =>
      out.synchronized {
        out.write(entry.toString)
        out.newLine()
        out.flush()
      }
    }
    file
  }
}
