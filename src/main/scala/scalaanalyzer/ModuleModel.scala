package scalaanalyzer

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope

import scalaanalyzer.platform.LibraryFile

import java.nio.file.Path
import java.util.{Collections, LinkedHashMap}
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

/**
 * A build target as a build server describes it (BSP `buildTarget/scalacOptions`): the Scala version, the compiler
 * options and the compile classpath (which includes scala-library), and the targets it depends on.
 *
 * @param name display name; several modules may share it when they have distinct identities
 * @param scalaVersion Scala version of the target
 * @param scalacOptions compiler options reported by the build
 * @param classpath compile classpath jars; the host mounts and indexes each one
 * @param dependsOn stable identities of the modules this one depends on, which must be added to the host first
 * @param identity stable build identity, or none to use `name`
 */
final case class ModuleSpec(
  name: String,
  scalaVersion: String,
  scalacOptions: Seq[String],
  classpath: Seq[Path],
  dependsOn: Seq[String] = Seq.empty,
  identity: Option[String] = None,
) {
  /**
   * The key under which the host registers this module.
   *
   * @return `identity` when present, otherwise `name`
   */
  def stableId: String = identity.getOrElse(name)
}

/**
 * One module of the host's project: the IDE `Module` object the plugin sees, the library roots of its classpath and
 * the source files opened in it.
 *
 * @param spec build description of the module
 * @param libraryRoots mounted roots of the jars on the module's classpath
 */
final class HostModule private[scalaanalyzer] (val spec: ModuleSpec, val libraryRoots: Set[VirtualFile]) {
  /**
   * The IDE `Module` object the plugin sees for this module.
   *
   * `ScalaPsiHost.addModule` assigns it right after construction and before registering the module; until then it
   * is uninitialized.
   */
  private[scalaanalyzer] var module: Module = uninitialized

  /** The project source files currently mapped to this module; `ModuleModel.addSource` and `removeSource` change it. */
  private[scalaanalyzer] val sources = ConcurrentHashMap.newKeySet[VirtualFile]()
}

/**
 * The host's modules and which module each source file belongs to. Resolution from a module's file sees the
 * module's own sources, those of the modules it depends on (transitively), its classpath and the JDK — as the IDE's
 * module-with-dependencies scope.
 *
 * `ScalaPsiHost` changes the registered modules, the source mapping and the JDK roots with the host write lock held;
 * queries read concurrent or synchronized state. Scope objects are cached per module because the plugin keys its
 * caches by scope; `add` and `remove` clear the caches they invalidate.
 *
 * @param project project the created scopes belong to
 */
final class ModuleModel(project: Project) {
  private val modules = Collections.synchronizedMap(new LinkedHashMap[String, HostModule]())
  private val moduleOfFile = new ConcurrentHashMap[VirtualFile, HostModule]()
  @volatile private var jdkRoots: Set[VirtualFile] = Set.empty

  /**
   * Lists the modules currently in the project.
   *
   * @return a snapshot in insertion order, which is dependencies first
   */
  def all: Seq[HostModule] = modules.synchronized(modules.values().asScala.toVector)

  /**
   * Finds a module by its stable identity.
   *
   * @param id value of `ModuleSpec.stableId`
   * @return the registered module, or none when no module has this identity
   */
  def byId(id: String): Option[HostModule] = Option(modules.get(id))

  /**
   * Finds a module by its display name.
   *
   * @param name value of `ModuleSpec.name`
   * @return the module when exactly one has this name; none when no module or several modules have it
   */
  def named(name: String): Option[HostModule] = all.filter(_.spec.name == name) match {
    case Seq(module) => Some(module)
    case _ => None
  }

  /**
   * Finds the module that owns a project source.
   *
   * @param file virtual file registered with `addSource`
   * @return the owning module, or none for a file that is not a registered project source
   */
  def ofFile(file: VirtualFile): Option[HostModule] = Option(moduleOfFile.get(file))

  /**
   * Finds the host module behind an IDE `Module` object.
   *
   * @param module IDE module to look up, compared by reference
   * @return the host module that holds exactly this object, or none when none does
   */
  def ofModule(module: Module): Option[HostModule] = all.find(_.module eq module)

  /**
   * Replaces the JDK roots visible to every module's resolve scope.
   *
   * The caller holds the host write lock.
   *
   * @param roots mounted JDK module roots
   */
  private[scalaanalyzer] def setJdkRoots(roots: Seq[VirtualFile]): Unit = jdkRoots = roots.toSet

  /**
   * Lists the mounted JDK's module roots.
   *
   * @return the roots given to `setJdkRoots`; empty before the first call
   */
  private[scalaanalyzer] def jdkModuleRoots: Set[VirtualFile] = jdkRoots

  /**
   * Registers a module under its stable identity and forgets which modules own which library roots.
   *
   * The caller holds the host write lock and has already added every module in `hostModule.spec.dependsOn`.
   *
   * @param hostModule module to register
   * @throws IllegalArgumentException when the stable identity is blank, a dependency is not registered, or the
   *                                  identity is already registered
   */
  private[scalaanalyzer] def add(hostModule: HostModule): Unit = {
    val id = hostModule.spec.stableId
    if (id == null || id.isBlank)
      throw new IllegalArgumentException(s"module ${hostModule.spec.name} has an empty stable identity")
    val missing = hostModule.spec.dependsOn.filterNot(modules.containsKey)
    if (missing.nonEmpty) {
      throw new IllegalArgumentException(
        s"module ${id} (${hostModule.spec.name}) depends on unknown module identities: ${missing.mkString(", ")}")
    }

    if (modules.putIfAbsent(id, hostModule) != null)
      throw new IllegalArgumentException(s"module identity $id (${hostModule.spec.name}) already exists")

    libraryScopes.clear()
  }

  /**
   * Unregisters a module and clears every cached scope.
   *
   * The caller holds the host write lock and has closed the module's sources first.
   *
   * @param id stable identity of the module to remove
   * @throws IllegalArgumentException when no module has this identity
   * @throws IllegalStateException when the module still has sources
   */
  private[scalaanalyzer] def remove(id: String): Unit = {
    modules.synchronized {
      val existing = Option(modules.get(id))
        .getOrElse(throw new IllegalArgumentException(s"no module with identity $id"))
      if (!existing.sources.isEmpty) throw new IllegalStateException(s"module $id still has sources")
      modules.remove(id)
    }

    // Scopes of the modules that depend on it reach it.
    scopes.clear()
    useScopes.clear()
    libraryScopes.clear()
  }

  /**
   * Maps a project source to its module, until `removeSource` is called for the file.
   *
   * The caller holds the host write lock.
   *
   * @param file project source file
   * @param hostModule module that owns the file
   */
  private[scalaanalyzer] def addSource(file: VirtualFile, hostModule: HostModule): Unit = {
    hostModule.sources.add(file)
    moduleOfFile.put(file, hostModule)
  }

  /**
   * Removes a source's module mapping and the file from its module's sources.
   *
   * The caller holds the host write lock. A file with no mapping is ignored.
   *
   * @param file project source file
   */
  private[scalaanalyzer] def removeSource(file: VirtualFile): Unit =
    Option(moduleOfFile.remove(file)).foreach(_.sources.remove(file))

  /** The module and the modules it depends on, transitively, each once. */
  private def withDependencies(hostModule: HostModule): Seq[HostModule] = {
    val seen = mutable.LinkedHashSet.empty[HostModule]

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

  /**
   * Gives the cached scope containing a module, its transitive dependencies, classpath roots and the JDK.
   *
   * @param hostModule registered module
   * @return the same scope object on every call for this module
   */
  def resolveScope(hostModule: HostModule): GlobalSearchScope =
    scopes.computeIfAbsent(hostModule, module => newScope(Seq(module)))

  /**
   * Where `file` resolves: a source in its module's scope, a library's class in the scope of the modules that have
   * it, as the IDE's library scope; the JDK and libraries no module has are in none.
   *
   * @param file project source or library file
   * @return the scope, or none for a source without a module, the JDK, and a library no module has
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
   *
   * @param file project source or library file
   * @return the scope, cached per module for sources; none when the file has no scope (see `resolveScope`)
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
