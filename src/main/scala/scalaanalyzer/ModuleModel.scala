package scalaanalyzer

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope

import scalaanalyzer.platform.LibraryFile

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * A build target as a build server describes it (BSP `buildTarget/scalacOptions`): the Scala version, the compiler
 * options and the compile classpath (which includes scala-library), and the targets it depends on.
 */
final case class ModuleSpec(
  name: String,
  scalaVersion: String,
  scalacOptions: Seq[String],
  classpath: Seq[Path],
  dependsOn: Seq[String] = Seq.empty,
  identity: Option[String] = None,
) {
  /** A stable build identity, or the display name for existing callers without one. */
  def stableId: String = identity.getOrElse(name)
}

/**
 * One module of the host's project: the IDE `Module` object the plugin sees, the library roots of its classpath and
 * the source files opened in it.
 */
final class HostModule private[scalaanalyzer] (val spec: ModuleSpec, val libraryRoots: Set[VirtualFile]) {
  private[scalaanalyzer] var module: Module = scala.compiletime.uninitialized
  private[scalaanalyzer] val sources = ConcurrentHashMap.newKeySet[VirtualFile]()
}

/**
 * The host's modules and which module each source file belongs to. Resolution from a module's file sees the
 * module's own sources, those of the modules it depends on (transitively), its classpath and the JDK — as the IDE's
 * module-with-dependencies scope.
 */
final class ModuleModel(project: Project) {
  private val modules = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap[String, HostModule]())
  private val moduleOfFile = new ConcurrentHashMap[VirtualFile, HostModule]()
  @volatile private var jdkRoots: Set[VirtualFile] = Set.empty

  /** All modules currently in the project, in their insertion order. */
  def all: Seq[HostModule] = modules.synchronized(modules.values().asScala.toVector)

  /** The module registered under its stable identity, if present. */
  def byId(id: String): Option[HostModule] = Option(modules.get(id))

  /** The unique module with this display name, if exactly one exists. */
  def named(name: String): Option[HostModule] = all.filter(_.spec.name == name) match {
    case Seq(module) => Some(module)
    case _ => None
  }

  /** The module that owns `file` as a project source, if any. */
  def ofFile(file: VirtualFile): Option[HostModule] = Option(moduleOfFile.get(file))

  /** The host module represented by an IntelliJ `Module` object, if it belongs to this project. */
  def ofModule(module: Module): Option[HostModule] = all.find(_.module eq module)

  /** Replaces the JDK roots visible to every module's resolve scope. */
  private[scalaanalyzer] def setJdkRoots(roots: Seq[VirtualFile]): Unit = jdkRoots = roots.toSet

  /** The mounted JDK's module roots. */
  private[scalaanalyzer] def jdkModuleRoots: Set[VirtualFile] = jdkRoots

  /** Adds a module after its dependencies have been added; duplicate identities and missing dependencies are rejected. */
  private[scalaanalyzer] def add(hostModule: HostModule): Unit = {
    val id = hostModule.spec.stableId
    if (id == null || id.isBlank) throw new IllegalArgumentException(s"module ${hostModule.spec.name} has an empty stable identity")
    val missing = hostModule.spec.dependsOn.filterNot(modules.containsKey)
    if (missing.nonEmpty) throw new IllegalArgumentException(s"module ${id} (${hostModule.spec.name}) depends on unknown module identities: ${missing.mkString(", ")}")
    if (modules.putIfAbsent(id, hostModule) != null)
      throw new IllegalArgumentException(s"module identity $id (${hostModule.spec.name}) already exists")
    libraryScopes.clear()
  }

  /** Removes a module whose sources were removed; `id` is its stable identity. */
  private[scalaanalyzer] def remove(id: String): Unit = {
    modules.synchronized {
      val existing = Option(modules.get(id)).getOrElse(throw new IllegalArgumentException(s"no module with identity $id"))
      if (!existing.sources.isEmpty) throw new IllegalStateException(s"module $id still has sources")
      modules.remove(id)
    }
    // Scopes of the modules that depend on it reach it.
    scopes.clear()
    useScopes.clear()
    libraryScopes.clear()
  }

  /** Registers a source-to-module mapping for the same lifetime as the source file. */
  private[scalaanalyzer] def addSource(file: VirtualFile, hostModule: HostModule): Unit = {
    hostModule.sources.add(file)
    moduleOfFile.put(file, hostModule)
  }

  /** Removes a source-to-module mapping and the file from its former module. */
  private[scalaanalyzer] def removeSource(file: VirtualFile): Unit =
    Option(moduleOfFile.remove(file)).foreach(_.sources.remove(file))

  /** The module and the modules it depends on, transitively, each once. */
  private def withDependencies(hostModule: HostModule): Seq[HostModule] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[HostModule]
    /** Visits each dependency once, preserving the build's dependencies-first order. */
    def visit(m: HostModule): Unit = if (seen.add(m)) m.spec.dependsOn.flatMap(byId).foreach(visit)
    visit(hostModule)
    seen.toSeq
  }

  // One scope object per module, as the IDE's ModuleWithDependenciesScope: the plugin keys its caches by scope,
  // so a new object per request would make every lookup miss and the caches grow without bound.
  private val scopes = new ConcurrentHashMap[HostModule, GlobalSearchScope]()
  // Each library root's scope, asked for on every resolve from a library class: found once, None outside modules.
  private val libraryScopes = new ConcurrentHashMap[VirtualFile, Option[GlobalSearchScope]]()

  /** The cached scope containing this module, its transitive dependencies, classpath roots and the JDK. */
  def resolveScope(hostModule: HostModule): GlobalSearchScope =
    scopes.computeIfAbsent(hostModule, module => newScope(Seq(module)))

  /**
   * Where `file` resolves: a source in its module's scope, a library's class in the scope of the modules that have
   * it, as the IDE's library scope; the JDK and libraries no module has are in none.
   */
  def resolveScope(file: VirtualFile): Option[GlobalSearchScope] = file match {
    case library: LibraryFile => libraryScopes.computeIfAbsent(library.root, libraryScope)
    case source => ofFile(source).map(resolveScope)
  }

  /**
   * The project's source files, the IDE's project and content scope: library files and the JDK are in none of it. One
   * object, for the plugin's caches (see above); a file is in it from when its module has it.
   */
  val sourcesScope: GlobalSearchScope = new GlobalSearchScope(project) {
    override def contains(file: VirtualFile): Boolean = ofFile(file).isDefined
    override def isSearchInModuleContent(module: Module): Boolean = true
    override def isSearchInLibraries: Boolean = false
    override def toString: String = "ProjectSources"
  }

  /**
   * The library files (classes, attached sources and the JDK): the IDE's libraries scope, and its library-classes
   * scope, which nothing asks for apart.
   */
  val librariesScope: GlobalSearchScope = new GlobalSearchScope(project) {
    override def contains(file: VirtualFile): Boolean = file.isInstanceOf[LibraryFile]
    override def isSearchInModuleContent(module: Module): Boolean = false
    override def isSearchInLibraries: Boolean = true
    override def toString: String = "Libraries"
  }

  // One scope per module here too.
  private val useScopes = new ConcurrentHashMap[HostModule, GlobalSearchScope]()

  /**
   * Where a source's declarations are used: its module and the modules that depend on it, transitively, as the IDE's
   * module-with-dependents scope; a library class is used where it resolves.
   */
  def useScope(file: VirtualFile): Option[GlobalSearchScope] = file match {
    case _: LibraryFile => resolveScope(file)
    case source => ofFile(source).map(module => useScopes.computeIfAbsent(module, newUseScope))
  }

  /** Creates the scope of sources that can use declarations from `used`. */
  private def newUseScope(used: HostModule): GlobalSearchScope = new GlobalSearchScope(project) {
    /** Whether `user` can see `used` through its own module or dependency chain. */
    private def usesModule(user: HostModule) = withDependencies(user).contains(used)

    override def contains(file: VirtualFile): Boolean = ofFile(file).exists(usesModule)
    override def isSearchInModuleContent(module: Module): Boolean = ofModule(module).exists(usesModule)
    override def isSearchInLibraries: Boolean = false
    override def toString: String = s"ModuleWithDependents(${used.spec.name})"
  }

  /** The union of the module scopes that own this library root, if any module uses it. */
  private def libraryScope(root: VirtualFile): Option[GlobalSearchScope] = {
    val owners = all.filter(_.libraryRoots.contains(root))
    Option.when(owners.nonEmpty)(newScope(owners))
  }

  /**
   * Builds the cached resolve scope for `owners`: dependencies are fixed at registration, while source membership
   * and JDK roots are checked against current project state.
   */
  private def newScope(owners: Seq[HostModule]): GlobalSearchScope = new GlobalSearchScope(project) {
    private val reachable = owners.flatMap(withDependencies).distinct
    // A module's classpath is the build's resolved one, transitive jars included: the jars of the modules it depends on
    // may be versions that resolution replaced (zio 1 for a module on zio 2), so only its own are reachable.
    private val roots = owners.flatMap(_.libraryRoots).toSet

    override def contains(file: VirtualFile): Boolean = file match {
      case library: LibraryFile => roots.contains(rootOf(library)) || jdkRoots.contains(rootOf(library))
      case source => reachable.exists(_.sources.contains(source))
    }
    override def isSearchInModuleContent(module: Module): Boolean = reachable.exists(_.module eq module)
    override def isSearchInLibraries: Boolean = true
    override def toString: String = s"ModuleWithDependencies(${owners.map(_.spec.name).mkString(", ")})"
  }

  /** Finds the mounted virtual root that owns a library or JDK file. */
  private def rootOf(file: VirtualFile): VirtualFile = {
    var f = file
    while (f.getParent != null) f = f.getParent
    f
  }
}
