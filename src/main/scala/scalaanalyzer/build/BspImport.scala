package scalaanalyzer.build

import ch.epfl.scala.bsp4j.*
import com.google.gson.{Gson, JsonElement}
import com.intellij.lang.annotation.HighlightSeverity
import org.eclipse.lsp4j.jsonrpc.{Launcher, ResponseErrorException}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

import scalaanalyzer.{LineColumn, ModuleSpec}
import scalaanalyzer.platform.HostLog

import java.net.URI
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Imports and compiles Scala modules over an initialized BSP connection.
 *
 * Only Scala targets (data kind `scala`) become modules: sbt's own build definition targets are left out. Classpath
 * entries that are directories (the class output of other targets) are dropped, since target dependencies are
 * modules' `dependsOn`.
 * Callers serialize requests and close the connection after use. Closing ends this client process while
 * leaving a build server shared by other clients available.
 *
 * @param process BSP client process, started by [[BspImport.connect]]
 * @param server remote proxy of the build server
 * @param capabilities capabilities the server announced when it initialized
 * @param client receives the diagnostics the server publishes; compiles return its snapshot
 * @param log file holding the client process's stderr, named in the error reported when the process exits early
 * @param timeoutMinutes deadline for each import or compile request
 */
final class BuildConnection private[scalaanalyzer] (
  process: Process,
  server: BspImport.HostBuildServer,
  capabilities: BuildServerCapabilities,
  client: BspImport.DiagnosticsClient,
  log: Path,
  timeoutMinutes: Long,
) extends AutoCloseable {
  import BspImport.gson

  /** Shutdown deadline: a local handshake needs seconds, not the minutes allowed for importing and compiling. */
  private val ShutdownDeadlineSeconds = 2L
  private val closed = new AtomicBoolean()
  /** Caches Scala targets until a successful build reload. */
  @volatile private var targets: Option[Seq[BuildTarget]] = None

  /** Waits with the connection's import/compile deadline and reports early process exit with its log path. */
  private def await[T](f: CompletableFuture[T]): T = BspImport.await(process, log, f, timeoutMinutes)

  /** Loads and caches Scala build targets until [[reload]] invalidates the build definition. */
  private def scalaTargets: Seq[BuildTarget] = targets.getOrElse {
    val found = await(server.workspaceBuildTargets()).getTargets.asScala.toSeq
      .filter(_.getDataKind == BuildTargetDataKind.SCALA)
      .sortBy(_.getId.getUri)
    targets = Some(found)
    found
  }

  /**
   * Imports Scala targets, source ownership and compiler settings.
   *
   * Stable target URIs identify modules even when display names collide. Duplicate identities, shared sources,
   * missing dependencies and dependency cycles are rejected before a host can apply the result.
   *
   * @return imported modules ordered after their dependencies
   */
  def modules(): Seq[ImportedModule] = {
    val buildTargets = scalaTargets
    val ids = buildTargets.map(_.getId).asJava
    val optionsById = await(server.buildTargetScalacOptions(new ScalacOptionsParams(ids))).getItems.asScala
      .map(item => item.getTarget -> item).toMap
    val sourcesById = await(server.buildTargetSources(new SourcesParams(ids))).getItems.asScala
      .map(item => item.getTarget -> item).toMap
    val nameById = buildTargets.map(t => t.getId -> BspImport.moduleName(t)).toMap
    val identityById = buildTargets.map(t => t.getId -> BspImport.targetIdentity(t)).toMap

    val imported = buildTargets.map { target =>
      // bsp4j reads `BuildTarget.data` through lsp4j's JsonElementTypeAdapter, and `scalaTargets` keeps only
      // targets whose data kind is `scala`, so the cast holds and the data decodes as a ScalaBuildTarget.
      val scala = gson.fromJson(target.getData.asInstanceOf[JsonElement], classOf[ScalaBuildTarget])
      val options = optionsById.getOrElse(target.getId,
        throw new IllegalStateException(s"build server sent no scalac options for ${target.getId.getUri}"))
      val fullClasspath = options.getClasspath.asScala.toSeq.map(uri => Path.of(URI.create(uri)))
      val spec = ModuleSpec(
        name = nameById(target.getId),
        scalaVersion = scala.getScalaVersion,
        scalacOptions = options.getOptions.asScala.toSeq,
        classpath = fullClasspath.filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".jar")),
        // Dependencies on non-Scala targets have no module.
        dependsOn = target.getDependencies.asScala.toSeq.flatMap(identityById.get),
        identity = Some(identityById(target.getId)),
      )
      val compile = CompileSpec(
        scalaVersion = scala.getScalaVersion,
        compilerJars = scala.getJars.asScala.toSeq.map(uri => Path.of(URI.create(uri))),
        classpath = fullClasspath,
        classDirectory = Path.of(URI.create(options.getClassDirectory)),
        scalacOptions = options.getOptions.asScala.toSeq,
      )
      ImportedModule(spec, sourcesById.get(target.getId).toSeq.flatMap(BspImport.sourceFiles), compile)
    }

    BspImport.dependenciesFirst(imported)
  }

  /**
   * Reloads the build definition and invalidates cached targets after success.
   *
   * A failed reload leaves the cached targets intact; callers decide whether to retry the import.
   */
  def reload(): Unit = {
    await(server.workspaceReload())
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
      val params = new DependencySourcesParams(scalaTargets.map(_.getId).asJava)
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
   * Requests an incremental compile of every Scala target.
   *
   * Diagnostics are published during the request and retained across compiles according to BSP reset flags.
   * A compile error response is treated as a failed compile; other connection failures propagate.
   *
   * @return compile success, the latest published compiler diagnostics by file, and those of the compiled targets
   */
  def compile():scalaanalyzer.build.CompileResult = compileTargets(scalaTargets.map(_.getId))

  /**
   * Requests an incremental compile of one Scala target, with the diagnostic and error handling of a build compile.
   * An unknown target URI is rejected with `BspImport.UnknownTarget`.
   *
   * @param target BSP target URI of an imported Scala target
   * @return compile success, the latest published compiler diagnostics by file, and those of the target and its
   *         dependencies
   */
  def compile(target: String): scalaanalyzer.build.CompileResult = compileTargets(Seq(scalaTarget(target).getId))

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
    val dependencies = scalaTargets.map(target => target.getId -> target.getDependencies.asScala.toSeq).toMap

    @tailrec
    def collect(found: Set[BuildTargetIdentifier], next: Seq[BuildTargetIdentifier]): Set[BuildTargetIdentifier] =
      next match {
        case Seq() => found
        case id +: rest if found(id) => collect(found, rest)
        case id +: rest => collect(found + id, dependencies.getOrElse(id, Seq.empty) ++ rest)
      }

    collect(Set.empty, ids)
  }

  /** Finds a cached Scala target by its URI, rejecting unknown ones. */
  private def scalaTarget(uri: String): BuildTarget = scalaTargets.find(_.getId.getUri == uri)
    .getOrElse(throw new BspImport.UnknownTarget(uri))

  /**
   * Gets the test process environment and Java executable for one target.
   *
   * Callers serialize BSP operations. The server must support `buildTarget/jvmTestEnvironment`, and must answer with
   * an environment for the target; otherwise `IllegalStateException` is thrown. The Java executable belongs to the
   * target's JDK, or to the host's running JVM when BSP omits it. An unknown target URI is rejected with
   * `IllegalArgumentException`.
   *
   * @param target BSP target URI of an imported Scala target
   * @return the server's environment item and the `bin/java` executable to run it with
   */
  def testEnvironment(target: String): TestRun.Environment = {
    if (!Option(capabilities.getJvmTestEnvironmentProvider).exists(_.booleanValue))
      throw new IllegalStateException("the build server does not support buildTarget/jvmTestEnvironment")

    val found = scalaTarget(target)
    val params = new JvmTestEnvironmentParams(java.util.List.of(found.getId))
    val items = await(server.jvmTestEnvironment(params)).getItems.asScala
    val environment = items.find(_.getTarget == found.getId)
      .getOrElse(throw new IllegalStateException(s"build server sent no test JVM environment for $target"))
    // Same cast as in `modules`: `scalaTarget` only returns targets whose data kind is `scala`.
    val scala = gson.fromJson(found.getData.asInstanceOf[JsonElement], classOf[ScalaBuildTarget])
    // BSP may omit the target JDK. Use the host's running JVM deliberately, never an arbitrary java on PATH.
    val javaHome = Option(scala.getJvmBuildTarget).flatMap(jvm => Option(jvm.getJavaHome))
      .map(uri => Path.of(URI.create(uri))).getOrElse(Path.of(System.getProperty("java.home")))

    TestRun.Environment(javaHome.resolve("bin").resolve("java"), environment)
  }

  /**
   * Shuts down the BSP connection and terminates its client process.
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
    } finally BspImport.destroyProcess(process)
  }
}

/** BSP connection setup and conversion of build-server results into host module and diagnostic models. */
object BspImport {
  /** Decodes the `.bsp` connection files and the `data` payloads of build targets. */
  private[scalaanalyzer] val gson = new Gson

  /** The protocol version of the bsp4j on the classpath (the Scala plugin's). */
  private val BspVersion = "2.1.0"

  /** The build server's interfaces the host calls. */
  trait HostBuildServer extends BuildServer with ScalaBuildServer with JvmBuildServer

  /**
   * A target URI that names no imported Scala target, so callers can answer it as a bad request.
   *
   * @param uri the requested target URI
   */
  final class UnknownTarget(uri: String) extends IllegalArgumentException(s"unknown Scala build target: $uri")

  /**
   * Starts and initializes the BSP client process selected from a workspace's connection files.
   *
   * Initialization includes a build reload so an already running server sees the current definition.
   * Startup failures terminate the new client process; the caller owns the returned connection.
   *
   * @param projectDir workspace directory containing `.bsp` connection files
   * @param connection the connection file's name (e.g. `sbt`) when `.bsp` has several; with one it may be omitted
   * @param timeoutMinutes deadline for each initialization, import or compile request
   * @return an initialized connection ready to import modules
   */
  def connect(projectDir: Path, connection: Option[String] = None, timeoutMinutes: Long = 10): BuildConnection = {
    val details = connectionDetails(projectDir, connection)
    val log = Files.createTempFile("scala-analyzer-bsp", ".log")

    HostLog.info(
      s"starting build server ${details.getName} ${details.getVersion}: ${details.getArgv.asScala.mkString(" ")} " +
        s"(its output: $log)")
    val process = new ProcessBuilder(details.getArgv)
      .directory(projectDir.toFile)
      .redirectError(ProcessBuilder.Redirect.to(log.toFile))
      .start()
    // SIGTERM can arrive during initialization, before a ProjectSession owns this process.
    val shutdownHook = new Thread(() => destroyProcess(process), s"scala-analyzer-bsp-${process.pid()}")

    try {
      Runtime.getRuntime.addShutdownHook(shutdownHook)
      process.onExit().thenRun(() => {
        try Runtime.getRuntime.removeShutdownHook(shutdownHook)
        catch { case e: IllegalStateException => HostLog.debug(s"build server exited during JVM shutdown: $e") }
      })

      val client = new DiagnosticsClient
      val launcher = new Launcher.Builder[HostBuildServer]()
        .setRemoteInterface(classOf[HostBuildServer])
        .setInput(process.getInputStream)
        .setOutput(process.getOutputStream)
        .setLocalService(client)
        .create()

      launcher.startListening()
      val server = launcher.getRemoteProxy
      val initialized = await(process, log, server.buildInitialize(new InitializeBuildParams(
        "scala-analyzer", "0.1", BspVersion, projectDir.toUri.toString,
        new BuildClientCapabilities(java.util.List.of("scala")))), timeoutMinutes)
      server.onBuildInitialized()
      // A build server that was already running (sbt keeps its server between clients) may hold an older build
      // definition: reload it, as IDEs do on import.
      await(process, log, server.workspaceReload(), timeoutMinutes)

      new BuildConnection(process, server, initialized.getCapabilities, client, log, timeoutMinutes)
    } catch {
      case e: Throwable =>
        destroyProcess(process)
        throw e
    }
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
   * Imports Scala modules using a temporary BSP connection.
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

  /** Reads the selected `.bsp` connection file, requiring an explicit name when several files are present. */
  private def connectionDetails(projectDir: Path, connection: Option[String]): BspConnectionDetails = {
    val bspDir = projectDir.resolve(".bsp")
    val files =
      if (!Files.isDirectory(bspDir)) Seq.empty
      else Using.resource(Files.list(bspDir)) {
        _.iterator().asScala.filter(_.toString.endsWith(".json")).toSeq.sortBy(_.toString)
      }
    val chosen = connection match {
      case Some(name) => files.find(_.getFileName.toString == s"$name.json")
        .getOrElse(throw new IllegalArgumentException(s"no BSP connection file $name.json in $bspDir"))
      case None => files match {
        case Seq(only) => only
        case Seq() =>
          throw new IllegalArgumentException(s"no BSP connection file in $bspDir (for sbt: run `sbt bspConfig`)")
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

  /**
   * Resolves existing Scala and Java files from a BSP source item, expanding directory entries recursively.
   *
   * Entries that do not exist and files with other extensions are skipped.
   *
   * @param item source entries of one build target
   * @return the files in entry order; the files found under one directory entry are sorted by path
   */
  private[scalaanalyzer] def sourceFiles(item: SourcesItem): Seq[Path] =
    item.getSources.asScala.toSeq.flatMap { source =>
      val path = Path.of(URI.create(source.getUri))
      if (!Files.exists(path)) Seq.empty
      else if (Files.isDirectory(path))
        Using.resource(Files.walk(path))(_.iterator().asScala.filter(isSource).toVector.sorted)
      else if (isSource(path)) Seq(path)
      else Seq.empty
    }

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
