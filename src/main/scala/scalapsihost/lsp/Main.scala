package scalapsihost.lsp

import org.eclipse.lsp4j.launch.LSPLauncher

import scalapsihost.platform.HostLog
import scalapsihost.{HostConfig, ScalaPsiHost}

import java.io.{File, InputStream, OutputStream}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Runs the language server over stdin and stdout.
 *
 * System properties: `scalapsihost.stateDir` (where the platform keeps its state) and `scalapsihost.scalaPluginJars`
 * (the Scala plugin's jars, which must also be on the classpath). Environment: `SCALA_PSI_HOST_LOG=debug` logs every
 * request.
 *
 * The log is written to `<stateDir>/logs/scala-psi-host-<start time>.log` (the latest logs are kept) and sent to the
 * client.
 */
object Main {
  /** The packaged release version, or a development marker when launched from loose classes. */
  val version: String = Option(getClass.getPackage.getImplementationVersion).getOrElse("development")

  // Logs of earlier runs kept next to the current one.
  private val KeptLogs = 10
  // Child-process hooks only send kill signals; a stuck platform hook must not keep the JVM alive after cleanup.
  private val HaltDelayMs = 1000L

  /**
   * Starts the PSI host and serves LSP on standard input and output.
   *
   * Configuration comes from the system properties and environment described on [[Main]]. Standard output is
   * reserved for protocol traffic; other process output is redirected to standard error.
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
    val stateDir = Path.of(property("scalapsihost.stateDir"))
    if (sys.env.get("SCALA_PSI_HOST_LOG").exists(_.equalsIgnoreCase("debug"))) HostLog.threshold = HostLog.Level.Debug
    val logFile = openLogFile(stateDir.resolve("logs"))
    // What is logged before the client connects (the host's startup) is sent to it when it connects.
    val startup = new java.util.concurrent.ConcurrentLinkedQueue[HostLog.Entry]()
    val buffering = HostLog.addSink(entry => startup.add(entry))

    val heap = Runtime.getRuntime.maxMemory / (1024 * 1024)
    HostLog.info(s"scala-psi-host $version starting: JDK ${System.getProperty("java.version")}, heap max $heap MB, state $stateDir, log $logFile")
    val host = try HostLog.timed("host started")(ScalaPsiHost.start(HostConfig(
      stateDir = stateDir,
      scalaPluginJars = property("scalapsihost.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
    )))
    finally buffering.close()
    val server = new ScalaLanguageServer(host, code => {
      val watchdog = new Thread(() => {
        Thread.sleep(HaltDelayMs)
        Runtime.getRuntime.halt(code)
      }, "scala-psi-host-halt")
      watchdog.setDaemon(true)
      watchdog.start()
      sys.exit(code)
    }, Some(logFile), startup.asScala.toSeq)
    listen(server, System.in, protocolOut)
  }

  /** A disconnected transport has the same lifetime as an explicit `exit`, including failed reads. */
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
    val name = s"scala-psi-host-${LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${ProcessHandle.current.pid}.log"
    val file = directory.resolve(name)
    val earlier = scala.util.Using.resource(Files.list(directory))(_.iterator.asScala.toVector)
      .filter(_.getFileName.toString.startsWith("scala-psi-host-")).sorted
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
