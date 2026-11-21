package scalaanalyzer.build

import ch.epfl.scala.bsp4j.*
import com.google.gson.{Gson, JsonElement}
import com.intellij.lang.annotation.HighlightSeverity
import org.eclipse.lsp4j.jsonrpc.{Launcher, ResponseErrorException}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

import scalaanalyzer.{LineColumn, ModuleSpec}
import scalaanalyzer.platform.HostLog

import java.io.{InputStream, OutputStream}
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Imports and compiles modules over an initialized BSP connection.
 *
 * Scala targets (data kind `scala`) and Java targets (see [[BspImport.classify]]) become modules: sbt's own build
 * definition targets are left out. Classpath entries that are directories (the class output of other targets) are
 * dropped, since target dependencies are modules' `dependsOn`.
 * Callers serialize requests and close the connection after use. Closing ends this client process while
 * leaving a build server shared by other clients available.
 *
 * @param process BSP client process, started by [[BspImport.connect]]
 * @param server remote proxy of the build server
 * @param capabilities capabilities the server announced when it initialized
 * @param client receives the diagnostics the server publishes; compiles return its snapshot
 * @param log file holding the client process's stderr, named in the error reported when the process exits early
 * @param timeoutMinutes deadline for each import or compile request
 * @param release frees what the transport holds besides the process (a Bloop connection's socket channel and
 *                directory); runs when the connection closes
 * @param regenerate rewrites the build server's configuration from the build files, for a server that reads it from
 *                   disk (Bloop: the build tool's install step); `None` for a server that loads the build itself
 */
final class BuildConnection private[scalaanalyzer] (
  process: Process,
  server: BspImport.HostBuildServer,
  capabilities: BuildServerCapabilities,
  client: BspImport.DiagnosticsClient,
  log: Path,
  timeoutMinutes: Long,
  release: () => Unit = () => (),
  regenerate: Option[() => Unit] = None,
) extends AutoCloseable {
  import BspImport.gson

  /** Shutdown deadline: a local handshake needs seconds, not the minutes allowed for importing and compiling. */
  private val ShutdownDeadlineSeconds = 2L
  private val closed = new AtomicBoolean()
  /** Caches the imported targets until a successful build reload. */
  @volatile private var targets: Option[Seq[BuildTarget]] = None

  /** Waits with the connection's import/compile deadline and reports early process exit with its log path. */
  private def await[T](f: CompletableFuture[T]): T = BspImport.await(process, log, f, timeoutMinutes)

  /** Loads and caches the build targets the host imports until [[reload]] invalidates the build definition. */
  private def importedTargets: Seq[BuildTarget] = targets.getOrElse {
    val found = await(server.workspaceBuildTargets()).getTargets.asScala.toSeq
      .filter(BspImport.classify(_) != BspImport.TargetKind.NotImported)
      .sortBy(_.getId.getUri)
    targets = Some(found)
    found
  }

  /**
   * Imports the build's Scala and Java targets, source ownership and compiler settings.
   *
   * Stable target URIs identify modules even when display names collide. Duplicate identities, shared sources,
   * missing dependencies and dependency cycles are rejected before a host can apply the result.
   *
   * @return imported modules ordered after their dependencies
   */
  def modules(): Seq[ImportedModule] = {
    val buildTargets = importedTargets
    val ids = buildTargets.map(_.getId).asJava
    val optionsById = scalacOptionsById(buildTargets) ++ javacOptionsById(buildTargets)
    val sourcesById = await(server.buildTargetSources(new SourcesParams(ids))).getItems.asScala
      .map(item => item.getTarget -> item).toMap
    val nameById = buildTargets.map(t => t.getId -> BspImport.moduleName(t)).toMap
    val identityById = buildTargets.map(t => t.getId -> BspImport.targetIdentity(t)).toMap

    val imported = buildTargets.map { target =>
      // bsp4j reads `BuildTarget.data` through lsp4j's JsonElementTypeAdapter, and a target classified as Scala has
      // data kind `scala`, so the cast holds and the data decodes as a ScalaBuildTarget.
      val scala = Option.when(BspImport.classify(target) == BspImport.TargetKind.Scala)(
        gson.fromJson(target.getData.asInstanceOf[JsonElement], classOf[ScalaBuildTarget]))
      val optionsRequest = if (scala.isDefined) "buildTarget/scalacOptions" else "buildTarget/javacOptions"
      val options = optionsById.getOrElse(target.getId,
        throw new IllegalStateException(s"build server sent no $optionsRequest result for ${target.getId.getUri}"))
      val spec = ModuleSpec(
        name = nameById(target.getId),
        scalaVersion = scala.map(_.getScalaVersion),
        scalacOptions = if (scala.isDefined) options.options else Seq.empty,
        classpath = options.classpath.filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".jar")),
        // Dependencies on targets that are not imported have no module.
        dependsOn = target.getDependencies.asScala.toSeq.flatMap(identityById.get),
        identity = Some(identityById(target.getId)),
      )
      // The document compiler serves Scala only.
      val compile = scala.map { scalaTarget =>
        CompileSpec(
          scalaVersion = scalaTarget.getScalaVersion,
          compilerJars = scalaTarget.getJars.asScala.toSeq.map(uri => Path.of(URI.create(uri))),
          classpath = options.classpath,
          classDirectory = Path.of(URI.create(options.classDirectory)),
          scalacOptions = options.options,
        )
      }
      val items = sourcesById.get(target.getId).toSeq

      ImportedModule(spec, items.flatMap(BspImport.sourceFiles), compile,
        generatedSources = items.flatMap(BspImport.generatedSourceFiles).toSet,
        sourceDirectories = items.flatMap(BspImport.sourceDirectories),
        baseDirectory = Option(target.getBaseDirectory).map(uri => Path.of(URI.create(uri))))
    }

    BspImport.dependenciesFirst(imported)
  }

  /** Asks for the Scala options of the Scala targets among `targets`; sends no request when there are none. */
  private def scalacOptionsById(targets: Seq[BuildTarget]): Map[BuildTargetIdentifier, BspImport.TargetOptions] = {
    val ids = targets.filter(BspImport.classify(_) == BspImport.TargetKind.Scala).map(_.getId)

    if (ids.isEmpty) Map.empty
    else await(server.buildTargetScalacOptions(new ScalacOptionsParams(ids.asJava))).getItems.asScala.map { item =>
      item.getTarget -> BspImport.TargetOptions(
        item.getOptions.asScala.toSeq, item.getClasspath.asScala.toSeq.map(uri => Path.of(URI.create(uri))),
        item.getClassDirectory)
    }.toMap
  }

  /** Asks for the Java options of the Java targets among `targets`; sends no request when there are none. */
  private def javacOptionsById(targets: Seq[BuildTarget]): Map[BuildTargetIdentifier, BspImport.TargetOptions] = {
    val ids = targets.filter(BspImport.classify(_) == BspImport.TargetKind.Java).map(_.getId)

    if (ids.isEmpty) Map.empty
    else await(server.buildTargetJavacOptions(new JavacOptionsParams(ids.asJava))).getItems.asScala.map { item =>
      item.getTarget -> BspImport.TargetOptions(
        item.getOptions.asScala.toSeq, item.getClasspath.asScala.toSeq.map(uri => Path.of(URI.create(uri))),
        item.getClassDirectory)
    }.toMap
  }

  /**
   * Reloads the build definition and invalidates cached targets after success.
   *
   * A connection with a `regenerate` action (Bloop) runs it first, so the server's configuration matches the
   * changed build files; the server re-reads that configuration by itself. Otherwise only a server that announced
   * `canReload` is asked to reload; one that cannot only has its cached targets dropped.
   *
   * A failed reload leaves the cached targets intact; callers decide whether to retry the import.
   */
  def reload(): Unit = {
    regenerate.foreach(_())
    if (BspImport.canReload(capabilities)) await(server.workspaceReload())
    targets = None
  }

  /**
   * Finds source archives for imported classpath jars.
   *
   * The request may download sources. A `-sources.jar` is paired only with a sibling classpath jar of the same
   * base name. Servers without dependency-source support produce an empty result.
   *
   * @param classpath binary jar paths eligible for source attachments
   * @return binary jar paths mapped to their source archives
   */
  def librarySources(classpath: Set[Path]): Map[Path, Path] =
    if (!Option(capabilities.getDependencySourcesProvider).exists(_.booleanValue)) Map.empty
    else {
      val params = new DependencySourcesParams(importedTargets.map(_.getId).asJava)
      val result = await(server.buildTargetDependencySources(params))
      val sourceJars = result.getItems.asScala.toSeq
        .flatMap(_.getSources.asScala)
        .map(uri => Path.of(URI.create(uri)))
        .distinct

      sourceJars.flatMap { sources =>
        val name = sources.getFileName.toString

        Option.when(name.endsWith("-sources.jar"))(sources.resolveSibling(name.stripSuffix("-sources.jar") + ".jar"))
          .filter(classpath.contains)
          .map(_ -> sources)
      }.toMap
    }

  /**
   * Requests an incremental compile of every imported target.
   *
   * Diagnostics are published during the request and retained across compiles according to BSP reset flags.
   * A compile error response is treated as a failed compile; other connection failures propagate.
   *
   * @return compile success, the latest published compiler diagnostics by file, and those of the compiled targets
   */
  def compile():scalaanalyzer.build.CompileResult = compileTargets(importedTargets.map(_.getId))

  /**
   * Requests an incremental compile of one imported target, with the diagnostic and error handling of a build compile.
   * An unknown target URI is rejected with `BspImport.UnknownTarget`.
   *
   * @param target BSP target URI of an imported target
   * @return compile success, the latest published compiler diagnostics by file, and those of the target and its
   *         dependencies
   */
  def compile(target: String): scalaanalyzer.build.CompileResult = compileTargets(Seq(importedTarget(target).getId))

  /**
   * Compiles the targets and pairs the outcome with the diagnostics published so far, all of them and those of the
   * compiled targets.
   */
  private def compileTargets(ids: Seq[BuildTargetIdentifier]): scalaanalyzer.build.CompileResult = {
    val succeeded =
      try await(server.buildTargetCompile(new CompileParams(ids.asJava))).getStatusCode == StatusCode.OK
      catch {
        // sbt answers a compile that failed with an error response instead of an ERROR status code; its problems were
        // published before the answer, like those of any compile.
        case e: ExecutionException if e.getCause.isInstanceOf[ResponseErrorException] => false
      }

    // Qualified (also in the signatures) because bsp4j's wildcard import brings in its own `CompileResult`.
    scalaanalyzer.build.CompileResult(succeeded, client.snapshot(), client.problemsOf(withDependencies(ids)))
  }

  /** The targets and, transitively, their dependencies: what the build server compiles for a compile of `ids`. */
  private def withDependencies(ids: Seq[BuildTargetIdentifier]): Set[BuildTargetIdentifier] = {
    val dependencies = importedTargets.map(target => target.getId -> target.getDependencies.asScala.toSeq).toMap

    @tailrec
    def collect(found: Set[BuildTargetIdentifier], next: Seq[BuildTargetIdentifier]): Set[BuildTargetIdentifier] =
      next match {
        case Seq() => found
        case id +: rest if found(id) => collect(found, rest)
        case id +: rest => collect(found + id, dependencies.getOrElse(id, Seq.empty) ++ rest)
      }

    collect(Set.empty, ids)
  }

  /** Finds a cached imported target by its URI, rejecting unknown ones. */
  private def importedTarget(uri: String): BuildTarget = importedTargets.find(_.getId.getUri == uri)
    .getOrElse(throw new BspImport.UnknownTarget(uri))

  /**
   * Gets the test process environment and Java executable for one target.
   *
   * Callers serialize BSP operations. The server must support `buildTarget/jvmTestEnvironment`, and must answer with
   * an environment for the target; otherwise `IllegalStateException` is thrown. The Java executable belongs to the
   * target's JDK, or to the host's running JVM when BSP omits it. An unknown target URI is rejected with
   * `BspImport.UnknownTarget`.
   *
   * @param target BSP target URI of an imported target
   * @return the server's environment item and the `bin/java` executable to run it with
   */
  def testEnvironment(target: String): TestRun.Environment = {
    val supported = Option(capabilities.getJvmTestEnvironmentProvider).exists(_.booleanValue)

    jvmEnvironment(target, "buildTarget/jvmTestEnvironment", supported) { id =>
      await(server.jvmTestEnvironment(new JvmTestEnvironmentParams(java.util.List.of(id)))).getItems.asScala
    }
  }

  /**
   * Gets the run process environment and Java executable for one target, as `testEnvironment` does for tests.
   *
   * Callers serialize BSP operations. The server must support `buildTarget/jvmRunEnvironment`, and must answer with
   * an environment for the target; otherwise `IllegalStateException` is thrown. An unknown target URI is rejected
   * with `BspImport.UnknownTarget`.
   *
   * @param target BSP target URI of an imported target
   * @return the server's environment item and the `bin/java` executable to run it with
   */
  def runEnvironment(target: String): TestRun.Environment = {
    val supported = Option(capabilities.getJvmRunEnvironmentProvider).exists(_.booleanValue)

    jvmEnvironment(target, "buildTarget/jvmRunEnvironment", supported) { id =>
      await(server.jvmRunEnvironment(new JvmRunEnvironmentParams(java.util.List.of(id)))).getItems.asScala
    }
  }

  /**
   * Requests a target's JVM environment with `request`, after checking that the server supports `method`.
   *
   * @param method name of the BSP request, for the failure message
   * @param supported whether the server's capabilities include `method`
   * @param request asks the server for the environments of one target
   */
  private def jvmEnvironment(target: String, method: String, supported: Boolean)(
      request: BuildTargetIdentifier => Iterable[JvmEnvironmentItem]): TestRun.Environment = {
    if (!supported) throw new IllegalStateException(s"the build server does not support $method")

    val found = importedTarget(target)
    val environment = request(found.getId).find(_.getTarget == found.getId)
      .getOrElse(throw new IllegalStateException(s"build server sent no JVM environment for $target"))
    // BSP may omit the target JDK. Use the host's running JVM deliberately, never an arbitrary java on PATH.
    val javaHome = jvmBuildTarget(found).flatMap(jvm => Option(jvm.getJavaHome))
      .map(uri => Path.of(URI.create(uri))).getOrElse(Path.of(System.getProperty("java.home")))

    TestRun.Environment(javaHome.resolve("bin").resolve("java"), environment)
  }

  /** The JVM data of a target: inside a Scala target's data, or the data of a `jvm` target; none when it has none. */
  private def jvmBuildTarget(target: BuildTarget): Option[JvmBuildTarget] = {
    // Same cast as in `modules`: the data of a target with a data kind is a JSON element.
    def data = target.getData.asInstanceOf[JsonElement]

    target.getDataKind match {
      case BuildTargetDataKind.SCALA =>
        Option(gson.fromJson(data, classOf[ScalaBuildTarget]).getJvmBuildTarget)
      case BuildTargetDataKind.JVM => Option(gson.fromJson(data, classOf[JvmBuildTarget]))
      case _ => None
    }
  }

  /**
   * Shuts down the BSP connection, terminates its client process and releases its transport.
   *
   * Closing is idempotent. The handshake has a short deadline and process cleanup runs even when it fails.
   * A build server shared by other clients remains running.
   */
  override def close(): Unit = if (closed.compareAndSet(false, true)) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ShutdownDeadlineSeconds)
    try {
      server.buildShutdown().get(ShutdownDeadlineSeconds, TimeUnit.SECONDS)
      server.onBuildExit()
      process.waitFor(math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
    } catch {
      case e: InterruptedException =>
        HostLog.warn("interrupted while shutting down the build server", e)
        Thread.currentThread().interrupt()
      case NonFatal(e) => HostLog.warn("build server shutdown failed", e)
    } finally {
      BspImport.destroyProcess(process)
      release()
    }
  }
}

/** BSP connection setup and conversion of build-server results into host module and diagnostic models. */
object BspImport {
  /** Decodes the `.bsp` connection files and the `data` payloads of build targets. */
  private[scalaanalyzer] val gson = new Gson

  /** The protocol version of the bsp4j on the classpath (the Scala plugin's). */
  private val BspVersion = "2.1.0"

  /** The build server's interfaces the host calls. */
  trait HostBuildServer extends BuildServer with ScalaBuildServer with JavaBuildServer with JvmBuildServer

  /** How an import treats a build target. */
  enum TargetKind {
    /** A Scala target: a module with a Scala SDK and a document compiler. */
    case Scala
    /** A Java-only target: a module without a Scala SDK. */
    case Java
    /** A target that is no module: sbt's build definition, or another language. */
    case NotImported
  }

  /**
   * The options a build server reports for a target, whether through `buildTarget/scalacOptions` or
   * `buildTarget/javacOptions`.
   *
   * @param options compiler options
   * @param classpath compile classpath, including output directories of other targets
   * @param classDirectory URI of the target's class output
   */
  private[build] final case class TargetOptions(options: Seq[String], classpath: Seq[Path], classDirectory: String)

  /**
   * Decides how an import treats a build target.
   *
   * Data kind `scala` is a Scala target. Data kind `jvm` (Gradle servers) or no data kind (Bloop's Java targets) with
   * `java` among the language ids is a Java target. Any other data kind (sbt's own build definition is `sbt`) or
   * language is not imported.
   *
   * @param target a target of `workspace/buildTargets`
   * @return the kind of module the target becomes, or `NotImported`
   */
  def classify(target: BuildTarget): TargetKind = target.getDataKind match {
    case BuildTargetDataKind.SCALA => TargetKind.Scala
    case BuildTargetDataKind.JVM | null if target.getLanguageIds.contains("java") => TargetKind.Java
    case _ => TargetKind.NotImported
  }

  /** Whether the server announced that it supports `workspace/reload`. */
  private[build] def canReload(capabilities: BuildServerCapabilities): Boolean =
    Option(capabilities.getCanReload).exists(_.booleanValue)

  /**
   * A target URI that names no imported target, so callers can answer it as a bad request.
   *
   * @param uri the requested target URI
   */
  final class UnknownTarget(uri: String) extends IllegalArgumentException(s"unknown build target: $uri")

  /**
   * Connects to the workspace's build server and initializes the session.
   *
   * A workspace with `.bsp` connection files (or an explicit `connection`) starts the client process one names. One
   * without them whose root holds a Maven or Gradle build writes Bloop's configuration with the build tool and
   * connects to a Bloop server over a Unix socket (see [[BloopBuild]]). Any other workspace is rejected for its
   * missing connection file. A root holding both a Maven and a Gradle build is rejected as ambiguous.
   *
   * Initialization includes a build reload so an already running server sees the current definition.
   * Startup failures terminate the new client process; the caller owns the returned connection.
   *
   * @param projectDir workspace directory containing `.bsp` connection files, or a Maven or Gradle build
   * @param connection the connection file's name (e.g. `sbt`) when `.bsp` has several; with one it may be omitted
   * @param timeoutMinutes deadline for each initialization, import or compile request, and for the build tool's
   *                       install step
   * @return an initialized connection ready to import modules
   */
  def connect(projectDir: Path, connection: Option[String] = None, timeoutMinutes: Long = 10): BuildConnection =
    if (connection.isDefined || connectionFiles(projectDir).nonEmpty)
      connectWithClientProcess(projectDir, connection, timeoutMinutes)
    else BloopBuild.detect(projectDir) match {
      case Some(tool) => connectThroughBloop(projectDir, tool, timeoutMinutes)
      // Reports the missing connection file.
      case None => connectWithClientProcess(projectDir, connection, timeoutMinutes)
    }

  /** Starts the client process a `.bsp` connection file names and speaks BSP over its standard streams. */
  private def connectWithClientProcess(projectDir: Path, connection: Option[String], timeoutMinutes: Long): BuildConnection = {
    val details = connectionDetails(projectDir, connection)
    val log = Files.createTempFile("scala-analyzer-bsp", ".log")

    HostLog.info(
      s"starting build server ${details.getName} ${details.getVersion}: ${details.getArgv.asScala.mkString(" ")} " +
        s"(its output: $log)")
    val process = new ProcessBuilder(details.getArgv)
      .directory(projectDir.toFile)
      .redirectError(ProcessBuilder.Redirect.to(log.toFile))
      .start()

    try {
      guardShutdown(process)
      initialize(projectDir, process, log, process.getInputStream, process.getOutputStream, timeoutMinutes)
    } catch {
      case e: Throwable =>
        destroyProcess(process)
        throw e
    }
  }

  /**
   * Writes Bloop's configuration with the workspace's build tool, starts Bloop and speaks BSP over its socket.
   *
   * The connection's reload runs the install step again, since Bloop reads the configuration it wrote.
   */
  private def connectThroughBloop(projectDir: Path, tool: BloopBuild.Tool, timeoutMinutes: Long): BuildConnection = {
    def install(): Unit = BloopBuild.install(tool, projectDir, timeoutMinutes)

    install()
    val server = BloopBuild.serve(projectDir, timeoutMinutes)

    try {
      initialize(projectDir, server.process, server.log, Channels.newInputStream(server.channel),
        Channels.newOutputStream(server.channel), timeoutMinutes, release = () => server.close(),
        regenerate = Some(() => install()))
    } catch {
      case e: Throwable =>
        destroyProcess(server.process)
        server.close()
        throw e
    }
  }

  /**
   * Ends the client process when the JVM shuts down, and stops watching for that once the process has exited.
   *
   * SIGTERM can arrive during initialization, before a ProjectSession owns the process.
   */
  private[build] def guardShutdown(process: Process): Unit = {
    val shutdownHook = new Thread(() => destroyProcess(process), s"scala-analyzer-bsp-${process.pid()}")

    Runtime.getRuntime.addShutdownHook(shutdownHook)
    process.onExit().thenRun(() => {
      try Runtime.getRuntime.removeShutdownHook(shutdownHook)
      catch { case e: IllegalStateException => HostLog.debug(s"build server exited during JVM shutdown: $e") }
    })
  }

  /**
   * Initializes BSP over the given streams, reloading the build when the server can, and wraps the session.
   *
   * The caller ends `process` and frees what it holds when this throws.
   *
   * @param process the client process, watched for early exit
   * @param log the process's output file
   * @param input the stream the server's messages arrive on
   * @param output the stream requests are written to
   * @param release see [[BuildConnection]]
   * @param regenerate see [[BuildConnection]]
   */
  private def initialize(
      projectDir: Path, process: Process, log: Path, input: InputStream, output: OutputStream, timeoutMinutes: Long,
      release: () => Unit = () => (), regenerate: Option[() => Unit] = None): BuildConnection = {
    val client = new DiagnosticsClient
    val launcher = new Launcher.Builder[HostBuildServer]()
      .setRemoteInterface(classOf[HostBuildServer])
      .setInput(input)
      .setOutput(output)
      .setLocalService(client)
      .create()

    launcher.startListening()
    val server = launcher.getRemoteProxy
    val initialized = await(process, log, server.buildInitialize(new InitializeBuildParams(
      "scala-analyzer", "0.1", BspVersion, projectDir.toUri.toString,
      new BuildClientCapabilities(java.util.List.of("scala", "java")))), timeoutMinutes)
    server.onBuildInitialized()
    // A build server that was already running (sbt keeps its server between clients) may hold an older build
    // definition: reload it, as IDEs do on import. A server that cannot reload (Bloop) re-reads its own
    // configuration.
    if (canReload(initialized.getCapabilities)) await(process, log, server.workspaceReload(), timeoutMinutes)

    new BuildConnection(process, server, initialized.getCapabilities, client, log, timeoutMinutes, release, regenerate)
  }

  /**
   * Reports that the BSP client process exited before answering a request.
   *
   * @param exitCode client process exit code
   * @param log path containing its stderr output
   */
  final class BuildServerExited(exitCode: Int, log: Path)
    extends RuntimeException(s"the build server exited (code $exitCode) without answering; its output: $log")

  /**
   * Awaits a BSP response within its deadline, ending the wait early if the client process exits.
   *
   * The process exiting first fails with `BuildServerExited` and an expired deadline with `TimeoutException`. Any
   * other failure of the request surfaces as the future's `ExecutionException`.
   *
   * @param process BSP client process, watched for exit
   * @param log stderr file of `process`, named in `BuildServerExited`
   * @param request pending response from the build server
   * @param timeoutMinutes deadline for the response
   * @return the response
   */
  private[scalaanalyzer] def await[T](process: Process, log: Path, request: CompletableFuture[T], timeoutMinutes: Long): T = {
    val exited = process.onExit().thenApply[T](p => throw new BuildServerExited(p.exitValue(), log))

    try request.applyToEither(exited, answer => answer).get(timeoutMinutes, TimeUnit.MINUTES)
    catch { case e: ExecutionException if e.getCause.isInstanceOf[BuildServerExited] => throw e.getCause }
  }

  /**
   * Forcefully ends the client process when initialization fails or its shutdown deadline expires.
   *
   * A failure to kill the process is logged, not thrown.
   *
   * @param process BSP client process; nothing happens if it is no longer alive
   */
  private[scalaanalyzer] def destroyProcess(process: Process): Unit =
    try { if (process.isAlive) process.destroyForcibly() }
    catch { case NonFatal(e) => HostLog.warn(s"could not kill build server ${process.pid()}", e) }

  /**
   * Imports the build's Scala and Java modules using a temporary BSP connection.
   *
   * The connection is closed after import, including on failure.
   *
   * @param projectDir workspace directory containing `.bsp` connection files
   * @param connection connection filename without `.json`, required when several are available
   * @param timeoutMinutes deadline for each BSP request
   * @return imported modules ordered after their dependencies
   */
  def importBuild(projectDir: Path, connection: Option[String] = None, timeoutMinutes: Long = 10): Seq[ImportedModule] =
    Using.resource(connect(projectDir, connection, timeoutMinutes))(_.modules())

  /**
   * Orders each module after the modules it depends on, so they can be added to the host in order.
   *
   * Source paths are made absolute, normalized and distinct before ownership is checked. Modules that become ready in
   * the same round keep their input order. Throws `IllegalArgumentException` for a blank or duplicate stable
   * identity, a source assigned to two modules, a dependency on an unknown identity and a dependency cycle.
   *
   * @param modules imported modules, identified by `spec.stableId`
   * @return the same modules with normalized sources, each after all of its dependencies
   */
  private[scalaanalyzer] def dependenciesFirst(modules: Seq[ImportedModule]): Seq[ImportedModule] = {
    val byId = mutable.LinkedHashMap.empty[String, ImportedModule]
    val sourceOwners = mutable.LinkedHashMap.empty[Path, ImportedModule]
    val ordered = mutable.LinkedHashMap.empty[String, ImportedModule]

    modules.foreach { module =>
      val id = module.spec.stableId
      if (id == null || id.isBlank)
        throw new IllegalArgumentException(s"module ${module.spec.name} has an empty stable identity")
      if (byId.contains(id))
        throw new IllegalArgumentException(s"duplicate module identity $id (${module.spec.name})")
      byId(id) = module.copy(sources = module.sources.map(_.toAbsolutePath.normalize).distinct)
    }

    byId.values.foreach { module =>
      module.sources.foreach { path =>
        sourceOwners.get(path) match {
          case Some(owner) if owner.spec.stableId != module.spec.stableId =>
            throw new IllegalArgumentException(
              s"source $path is assigned to multiple build targets: ${owner.spec.stableId} (${owner.spec.name}) and " +
                s"${module.spec.stableId} (${module.spec.name}); the host cannot choose one module for a shared source")
          case _ => sourceOwners(path) = module
        }
      }
    }

    val missing = byId.values.toSeq.flatMap { module =>
      module.spec.dependsOn.filterNot(byId.contains).map(id => module -> id)
    }
    if (missing.nonEmpty) {
      val details = missing.map { case (module, id) => s"${module.spec.stableId} (${module.spec.name}) -> $id" }
      throw new IllegalArgumentException(s"unknown module dependencies: ${details.mkString(", ")}")
    }

    var waiting = byId.values.toSeq
    while (waiting.nonEmpty) {
      val (ready, blocked) = waiting.partition(_.spec.dependsOn.forall(ordered.contains))
      if (ready.isEmpty) {
        val targets = blocked.map(m => s"${m.spec.stableId} (${m.spec.name})")
        throw new IllegalArgumentException(s"cyclic module dependencies among ${targets.mkString(", ")}")
      }
      ready.foreach(m => ordered(m.spec.stableId) = m)
      waiting = blocked
    }

    ordered.values.toSeq
  }

  /** The `.bsp` connection files of a workspace, sorted by path; none when it has no `.bsp` directory. */
  private def connectionFiles(projectDir: Path): Seq[Path] = {
    val bspDir = projectDir.resolve(".bsp")

    if (!Files.isDirectory(bspDir)) Seq.empty
    else Using.resource(Files.list(bspDir)) {
      _.iterator().asScala.filter(_.toString.endsWith(".json")).toSeq.sortBy(_.toString)
    }
  }

  /** Reads the selected `.bsp` connection file, requiring an explicit name when several files are present. */
  private def connectionDetails(projectDir: Path, connection: Option[String]): BspConnectionDetails = {
    val bspDir = projectDir.resolve(".bsp")
    val files = connectionFiles(projectDir)
    val chosen = connection match {
      case Some(name) => files.find(_.getFileName.toString == s"$name.json")
        .getOrElse(throw new IllegalArgumentException(s"no BSP connection file $name.json in $bspDir"))
      case None => files match {
        case Seq(only) => only
        case Seq() =>
          throw new IllegalArgumentException(
            s"no BSP connection file in $bspDir (for sbt: run `sbt bspConfig`; a Maven or Gradle build opens through " +
              "Bloop when its root has a pom.xml or a Gradle settings or build file)")
        case several => throw new IllegalArgumentException(
          s"several BSP connection files in $bspDir, choose one: ${several.map(_.getFileName).mkString(", ")}")
      }
    }

    gson.fromJson(Files.readString(chosen), classOf[BspConnectionDetails])
  }

  /**
   * Uses the build's display name, falling back to its unique target URI when no name was supplied.
   *
   * @param target build target to name; a missing or empty display name requires a target URI (see [[targetIdentity]])
   * @return the module name, which can repeat across targets when display names collide
   */
  private[scalaanalyzer] def moduleName(target: BuildTarget): String =
    Option(target.getDisplayName).filter(_.nonEmpty).getOrElse(targetIdentity(target))

  /**
   * Reads the BSP target URI, which is stable across display-name collisions and build reloads.
   *
   * @param target build target to identify
   * @return the target's non-empty URI; a target without one is rejected with `IllegalArgumentException`
   */
  private[scalaanalyzer] def targetIdentity(target: BuildTarget): String = {
    Option(target.getId).flatMap(id => Option(id.getUri)).filter(_.nonEmpty)
      .getOrElse(throw new IllegalArgumentException(s"build target has no identity URI: ${target.getDisplayName}"))
  }

  /** The only source extensions the host imports from BSP targets. */
  private def isSource(path: Path): Boolean = path.toString.endsWith(".scala") || path.toString.endsWith(".java")

  /** Resolves one BSP source entry to its existing Scala and Java files: a directory is walked, sorted by path. */
  private def filesOf(source: SourceItem): Seq[Path] = {
    val path = Path.of(URI.create(source.getUri))

    if (!Files.exists(path)) Seq.empty
    else if (Files.isDirectory(path))
      Using.resource(Files.walk(path))(_.iterator().asScala.filter(isSource).toVector.sorted)
    else if (isSource(path)) Seq(path)
    else Seq.empty
  }

  /**
   * Resolves existing Scala and Java files from a BSP source item, expanding directory entries recursively.
   *
   * Entries that do not exist and files with other extensions are skipped.
   *
   * @param item source entries of one build target
   * @return the files in entry order; the files found under one directory entry are sorted by path
   */
  private[scalaanalyzer] def sourceFiles(item: SourcesItem): Seq[Path] =
    item.getSources.asScala.toSeq.flatMap(filesOf)

  /**
   * The directory entries of a BSP source item, whether or not they exist yet.
   *
   * @param item source entries of one build target
   * @return the paths of the entries of kind directory, in entry order
   */
  private[scalaanalyzer] def sourceDirectories(item: SourcesItem): Seq[Path] =
    item.getSources.asScala.toSeq
      .filter(_.getKind == SourceItemKind.DIRECTORY)
      .map(source => Path.of(URI.create(source.getUri)))

  /**
   * Resolves the files of a BSP source item that the build marks as generated.
   *
   * A generated directory entry makes every file under it generated, as in `sourceFiles`' expansion.
   *
   * @param item source entries of one build target
   * @return the generated files among `sourceFiles(item)`
   */
  private[scalaanalyzer] def generatedSourceFiles(item: SourcesItem): Set[Path] =
    item.getSources.asScala.filter(_.getGenerated).flatMap(filesOf).toSet

  /**
   * Retains published diagnostics per source and target, replacing or appending according to BSP's reset flag.
   *
   * Every other build notification is ignored, including sbt's extension notifications.
   */
  private[scalaanalyzer] final class DiagnosticsClient extends BuildClient {
    private val published = new ConcurrentHashMap[(Path, BuildTargetIdentifier), Vector[CompilerDiagnostic]]()

    /**
     * Combines the latest diagnostics from all build targets by source path.
     *
     * @return diagnostics per source path, without paths that have none; their order across targets is unspecified
     */
    def snapshot(): Map[Path, Seq[CompilerDiagnostic]] =
      published.asScala.toSeq.groupMap(_._1._1)(_._2).view.mapValues(_.flatten).filter(_._2.nonEmpty).toMap

    /**
     * Collects the latest diagnostics that the given targets published.
     *
     * @param targets build targets whose diagnostics count
     * @return their diagnostics, in no specified order
     */
    def problemsOf(targets: Set[BuildTargetIdentifier]): Seq[CompilerDiagnostic] =
      published.asScala.toSeq.collect { case ((_, target), problems) if targets(target) => problems }.flatten

    /**
     * Replaces or appends the diagnostics for one source and target, as indicated by BSP's reset flag.
     *
     * @param params diagnostics of one text document and build target
     */
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
      val key = (Path.of(URI.create(params.getTextDocument.getUri)), params.getBuildTarget)
      val problems = params.getDiagnostics.asScala.toVector.map { d =>
        val range = d.getRange
        CompilerDiagnostic(
          severity(d.getSeverity),
          LineColumn(range.getStart.getLine, range.getStart.getCharacter),
          LineColumn(range.getEnd.getLine, range.getEnd.getCharacter),
          d.getMessage)
      }

      if (params.getReset) published.put(key, problems)
      else published.merge(key, problems, _ ++ _)
    }

    /** Maps BSP diagnostic severities to IntelliJ highlighting levels. */
    private def severity(s: DiagnosticSeverity): HighlightSeverity = s match {
      case DiagnosticSeverity.ERROR => HighlightSeverity.ERROR
      case DiagnosticSeverity.WARNING => HighlightSeverity.WARNING
      // Absent severity: LSP lets the client decide; the IDE treats compiler output without one as information.
      case DiagnosticSeverity.INFORMATION | DiagnosticSeverity.HINT | null => HighlightSeverity.INFORMATION
    }

    // Notifications the host ignores.
    override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
    override def onBuildLogMessage(params: LogMessageParams): Unit = ()
    override def onBuildTaskStart(params: TaskStartParams): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()

    /**
     * Consumes sbt's out-of-BSP command notification; the host does not execute it.
     *
     * @param params notification payload, unused
     */
    @JsonNotification("sbt/exec")
    def onSbtExec(params: AnyRef): Unit = ()

    /**
     * Consumes sbt's shutdown notification; connection shutdown remains owned by BSP.
     *
     * @param params notification payload, unused
     */
    @JsonNotification("shutdown")
    def onSbtShutdown(params: AnyRef): Unit = ()
  }
}
