package scalaanalyzer.build

import ch.epfl.scala.debugadapter.{
  CancelableFuture, DebugServer, DebugToolsResolver, Debuggee, DebuggeeListener, JavaRuntime, Library, Logger, Module,
  ScalaVersion, SourceDirectory, SourceEntry, SourceJar, StandaloneSourceFile, UnmanagedEntry,
}

import scalaanalyzer.platform.HostLog

import java.io.{BufferedReader, Closeable}
import java.net.{InetSocketAddress, URI, URLClassLoader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

/**
 * A debug session of one program: Scala Center's debug adapter, listening for one DAP client on a local port, and the
 * program it launches when the client connects.
 *
 * The adapter runs inside the host and the program is a child process of the host, started with the environment of a
 * normal run plus a JDWP agent that the adapter attaches to; this is how Metals debugs when its build server cannot
 * (`MetalsDebuggee`). The session ends when the client disconnects or the program exits, or when it is closed.
 *
 * @param server the adapter's server, which accepts the client's connection and runs the debuggee
 * @param finished completes when the server has stopped accepting clients: after its session has ended, or after it
 *                 was closed
 * @param executor the threads of the server and its sessions, shut down with it
 */
final class DebugAdapter private (server: DebugServer, val finished: Future[Unit], executor: ExecutorService)
    extends AutoCloseable {
  /** The address of the server, `tcp://host:port`, which a DAP client connects to. */
  val uri: URI = server.uri

  /**
   * Closes the server and ends the session in progress, terminating the program. Closing is idempotent.
   */
  override def close(): Unit = {
    server.close()
    executor.shutdown()
  }
}

/** Builds the debug adapter's view of a build target, and starts its server. */
object DebugAdapter {
  /** The line the JDWP agent prints when it listens, which carries the port the adapter attaches to. */
  private val Listening: Regex = """Listening for transport dt_socket at address: (\d+)""".r

  /**
   * The JDWP agent of the debuggee: the program waits for the debugger before it starts, as the adapter expects; the
   * port is chosen by the system and announced on standard output. The loopback address keeps the agent off the network.
   */
  private val Agent = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0"

  /**
   * The Scala version of a Java target, which the adapter needs for every debuggee: the adapter's own default 2.13,
   * so the expression compiler and decoder it asks for are those of a version that exists. (Metals passes its own
   * version here.)
   */
  private val JavaTargetScalaVersion = ScalaVersion.`2.13`

  /**
   * Starts a debug server for a main class, ready for a client to connect; the program starts when it does.
   *
   * The caller owns the result and closes it. The program is the one `TestRun.startMain` would run: the same classpath,
   * JVM options, environment and working directory.
   *
   * @param main the main class and the target that holds it
   * @param target the imported target of `main`, which names it and gives its Scala version and sources
   * @param modules every imported module: the output directories of the classpath are matched with them to find their
   *                sources
   * @param environment the target's run environment, as prepared after compiling it
   * @param librarySources asks the build for the source archives of jars, which it pairs with them
   * @param toolsCache directory that keeps the expression compilers and decoders downloaded for Scala versions
   * @return the running server; its `uri` is where a DAP client connects
   */
  def start(main: TestRun.MainSelection, target: ImportedModule, modules: Seq[ImportedModule],
            environment: TestRun.Environment, librarySources: Set[Path] => Map[Path, Path], toolsCache: Path): DebugAdapter = {
    val executor = Executors.newCachedThreadPool(new ThreadFactory {
      override def newThread(task: Runnable): Thread = {
        val thread = new Thread(task, "scala-analyzer-debug")
        thread.setDaemon(true)
        thread
      }
    })
    given ExecutionContext = ExecutionContext.fromExecutor(executor)

    try {
      val debuggee = mainClassDebuggee(main, target, modules, environment, librarySources)
      val server = DebugServer(debuggee, new ToolsResolver(toolsCache), HostLogger)

      new DebugAdapter(server, server.run(), executor)
    } catch {
      case error: Throwable =>
        executor.shutdown()
        throw error
    }
  }

  /** Sends the adapter's log to the host's. */
  private object HostLogger extends Logger {
    override def debug(message: => String): Unit = HostLog.debug(s"debug adapter: $message")
    override def info(message: => String): Unit = HostLog.info(s"debug adapter: $message")
    override def warn(message: => String): Unit = HostLog.warn(s"debug adapter: $message")
    override def error(message: => String): Unit = HostLog.error(s"debug adapter: $message")
    override def trace(cause: => Throwable): Unit = HostLog.debug(s"debug adapter: $cause")
  }

  /**
   * Downloads the tools the adapter loads for a Scala version, as `MetalsDebugToolsResolver` does: the expression
   * compiler and the TASTy decoder, which the adapter publishes per Scala version. Each is resolved once per version
   * and kept in memory; a failure is returned to the adapter, which then goes without the tool.
   *
   * @param cache directory that keeps the downloaded files across sessions
   */
  private final class ToolsResolver(cache: Path) extends DebugToolsResolver {
    private val expressionCompilers = mutable.Map.empty[ScalaVersion, ClassLoader]
    private val decoders = mutable.Map.empty[ScalaVersion, Seq[Path]]

    override def resolveExpressionCompiler(scalaVersion: ScalaVersion): Try[ClassLoader] = synchronized {
      Try(expressionCompilers.getOrElseUpdate(scalaVersion, {
        val jars = download(ch.epfl.scala.debugadapter.BuildInfo.expressionCompilerName + "_" + scalaVersion)
        // As Metals: the compiler sees nothing of the host's classes, only its own.
        new URLClassLoader(jars.map(_.toUri.toURL).toArray, null)
      }))
    }

    override def resolveDecoder(scalaVersion: ScalaVersion): Try[Seq[Path]] = synchronized {
      Try(decoders.getOrElseUpdate(scalaVersion,
        download(ch.epfl.scala.debugadapter.BuildInfo.decoderName + "_" + scalaVersion.binaryVersion)))
    }

    /** Resolves one of the adapter's artifacts at the adapter's own version, with what it depends on. */
    private def download(artifact: String): Seq[Path] = {
      val adapter = ch.epfl.scala.debugadapter.BuildInfo

      MavenDownload.resolve(cache, artifact, Seq(MavenDownload.Coordinates(adapter.organization, artifact, adapter.version)))
        .map(_.toPath)
    }
  }

  /**
   * The adapter's description of the program: what it is made of, so that the adapter finds the source of a class that
   * a breakpoint or a frame names, and how to start it.
   */
  private def mainClassDebuggee(main: TestRun.MainSelection, target: ImportedModule, modules: Seq[ImportedModule],
                                environment: TestRun.Environment, librarySources: Set[Path] => Map[Path, Path]): Debuggee = {
    val classpath = environment.item.getClasspath.asScala.toSeq.map(TestRun.path(_).toAbsolutePath.normalize())
    val (directories, jars) = classpath.partition(Files.isDirectory(_))
    val sourcesByJar = librarySources(jars.toSet)
    val modulesByClasses = modules.flatMap(module => module.classDirectory.map(_.toAbsolutePath.normalize() -> module)).toMap
    val (projectModules, otherDirectories) = directories.partition(modulesByClasses.contains)

    new Debuggee {
      override val name: String = s"${target.spec.name}: ${main.mainClass}"
      override val scalaVersion: ScalaVersion = target.spec.scalaVersion.fold(JavaTargetScalaVersion)(ScalaVersion(_))
      override val modules: Seq[Module] = projectModules.map { classes =>
        val module = modulesByClasses(classes)

        Module(module.spec.name, module.spec.scalaVersion.map(ScalaVersion(_)), module.spec.scalacOptions, classes,
          sourceEntries(module))
      }
      override val libraries: Seq[Library] = jars.flatMap(jar => library(jar, sourcesByJar.get(jar)))
      // The directories that no module of the build owns (resources) and the jars of an unknown layout.
      override val unmanagedEntries: Seq[UnmanagedEntry] =
        (otherDirectories ++ jars.filter(jar => library(jar, None).isEmpty)).map(UnmanagedEntry(_))
      override val javaRuntime: Option[JavaRuntime] = JavaRuntime(environment.java.getParent.getParent)

      // Hot code replace is a later wave; as in Metals, no class update is ever observed.
      override def observeClassUpdates(onClassUpdate: Seq[String] => Unit): Closeable = () => ()

      override def run(listener: DebuggeeListener): CancelableFuture[Unit] = launch(main, environment, listener)
    }
  }

  /**
   * The source entries of a module: the directories its build lists, and the sources that lie outside of them, which
   * the adapter takes one by one.
   */
  private def sourceEntries(module: ImportedModule): Seq[SourceEntry] = {
    val directories = module.sourceDirectories.map(_.toAbsolutePath.normalize())
    val loose = module.sources.filterNot(source => directories.exists(source.startsWith))

    directories.map(SourceDirectory(_)) ++ loose.map(source => StandaloneSourceFile(source, source.getFileName.toString))
  }

  /**
   * The library of a jar of a Maven repository, named after its place there (`.../artifact/version/artifact-version.jar`),
   * with its sources when known. The adapter knows the Scala library by this name; a jar elsewhere is not a library.
   */
  private def library(jar: Path, sources: Option[Path]): Option[Library] = for {
    versionDirectory <- Option(jar.getParent)
    artifactDirectory <- Option(versionDirectory.getParent)
    version = versionDirectory.getFileName.toString
    artifact = artifactDirectory.getFileName.toString
    if jar.getFileName.toString.startsWith(s"$artifact-$version")
  } yield Library(artifact, version, jar, sources.map(SourceJar(_)).toSeq)

  /**
   * Starts the program with the JDWP agent, in the environment's working directory with its variables, as `Run.launch`
   * does for a run, and reports what it prints to the listener. The agent's announcement of its port is not output: it
   * becomes the listener's `onListening`.
   *
   * @return a future that completes when the program has exited and its output has been read, and fails when it exited
   *         with an error, unless it was cancelled; cancelling ends it and its child processes
   */
  private def launch(main: TestRun.MainSelection, environment: TestRun.Environment,
                     listener: DebuggeeListener): CancelableFuture[Unit] = {
    val command = TestRun.jvmCommand(environment, Seq.empty, main.mainClass, Seq.empty)
    val builder = new ProcessBuilder((command.head +: Agent +: command.tail).asJava)
      .directory(TestRun.path(environment.item.getWorkingDirectory).toFile)
    builder.environment().putAll(environment.item.getEnvironmentVariables)
    val process = builder.start()
    val result = Promise[Unit]()
    val cancelled = new AtomicBoolean

    /** Reads a stream of the process line by line on a thread of its own. */
    def forward(reader: BufferedReader, line: String => Unit): Thread = {
      val thread = new Thread(() => reader.lines().forEach(line(_)), "scala-analyzer-debuggee-output")
      thread.setDaemon(true)
      thread.start()
      thread
    }

    // Both streams are read for the agent's line: it prints to standard output, but the JVM may be told otherwise.
    def output(report: String => Unit)(line: String): Unit = line match {
      case Listening(port) => listener.onListening(new InetSocketAddress("127.0.0.1", port.toInt))
      case _ => report(line)
    }

    val readers = Seq(
      forward(process.inputReader(UTF_8), output(listener.out)),
      forward(process.errorReader(UTF_8), output(listener.err)))
    val waiter = new Thread(() => {
      val exit = process.waitFor()
      readers.foreach(_.join())
      if (exit == 0 || cancelled.get) result.success(()) else result.failure(new IllegalStateException(s"the program exited with code $exit"))
      ()
    }, "scala-analyzer-debuggee")
    waiter.setDaemon(true)
    waiter.start()

    new CancelableFuture[Unit] {
      override val future: Future[Unit] = result.future

      override def cancel(): Unit = {
        cancelled.set(true)
        process.descendants().forEach(_.destroyForcibly())
        if (process.isAlive) process.destroyForcibly()
      }
    }
  }
}
