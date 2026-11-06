package scalaanalyzer.platform

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.{DeprecatedVirtualFileSystem, VirtualFile, VirtualFileWithId}

import java.io.{InputStream, OutputStream}
import java.net.URI
import java.nio.file.{FileSystem, FileSystems, Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Exposes library archives and JDK modules as read-only virtual files.
 *
 * Mounted contents must remain unchanged: there is no persistence, refresh or event delivery.
 * The owner must close this instance after use. It owns archives and alternate-JDK file systems it opens,
 * while borrowing the running JVM's shared `jrt:` file system.
 *
 * Mounting and closing are serialized on this instance's monitor, which also guards the owned file systems and the
 * closed flag; lookups do not take it. Mounting after close fails with `IllegalStateException`.
 */
final class LibraryFileSystem extends DeprecatedVirtualFileSystem with AutoCloseable {
  /** Root name (shown in paths) → root directory. Insertion order is classpath order; iterate under its monitor. */
  private val roots = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap[String, LibraryFile]())
  /** One VirtualFile per nio path, so PSI caches keyed by VirtualFile see stable identities. */
  private val files = new ConcurrentHashMap[Path, LibraryFile]()
  private val ids = new AtomicInteger()
  /** Zip and alternate JDK file systems opened by this instance; the current JDK's shared jrt is borrowed. */
  private val ownedFileSystems = mutable.LinkedHashSet.empty[FileSystem]
  /** Each source archive is opened once even when several of its directories are mounted. */
  private val sourceArchives = new ConcurrentHashMap[Path, FileSystem]()
  /** Filename index across class and source roots, invalidated by mounts and rebuilt on demand. */
  @volatile private var byName: Map[String, Seq[VirtualFile]] = null
  private var closed = false

  override def getProtocol: String = LibraryFileSystem.Protocol

  /**
   * Opens and mounts a class archive.
   *
   * @param jar archive to keep open until this file system is closed
   * @return the mounted root directory
   * @throws IllegalStateException if this file system is closed
   */
  def mountJar(jar: Path): VirtualFile = synchronized {
    ensureOpen()
    val zip = FileSystems.newFileSystem(jar)
    ownedFileSystems += zip
    mount(jar.toAbsolutePath.toString, zip.getPath("/"), sources = false)
  }

  /**
   * Mounts a source archive or a directory within it.
   *
   * Repeated requests for the same archive path and directory reuse the mounted root.
   * Several directories in one archive share its open ZIP file system.
   *
   * @param archive source jar or JDK source ZIP to keep open until close
   * @param directory directory within the archive, or empty for its root
   * @return the mounted source root
   * @throws IllegalStateException if this file system is closed
   */
  def mountSources(archive: Path, directory: String = ""): VirtualFile = synchronized {
    ensureOpen()
    val name = if (directory.isEmpty) archive.toAbsolutePath.toString else s"${archive.toAbsolutePath}!/$directory"
    Option(roots.get(name)).getOrElse {
      val zip = sourceArchives.computeIfAbsent(archive.toAbsolutePath, a => {
        val opened = FileSystems.newFileSystem(a)
        ownedFileSystems += opened
        opened
      })
      mount(name, zip.getPath("/" + directory), sources = true)
    }
  }

  /**
   * Mounts every module of a JDK as a class root.
   *
   * The running JVM's JRT file system is borrowed; an alternate JDK's file system is owned until close.
   *
   * @param javaHome JDK installation directory
   * @return module roots sorted by module path
   * @throws IllegalStateException if this file system is closed
   */
  def mountJdk(javaHome: Path): Seq[VirtualFile] = synchronized {
    ensureOpen()
    val (jrt, owned) =
      if (Files.isSameFile(javaHome, Path.of(System.getProperty("java.home"))))
        (FileSystems.getFileSystem(URI.create("jrt:/")), false)
      else (FileSystems.newFileSystem(URI.create("jrt:/"), Map("java.home" -> javaHome.toString).asJava), true)
    if (owned) ownedFileSystems += jrt
    val modules = Using.resource(Files.list(jrt.getPath("/modules")))(_.iterator().asScala.toVector.sortBy(_.toString))
    modules.map(module => mount(s"jrt:$javaHome!/${module.getFileName}", module, sources = false))
  }

  /**
   * Closes owned file systems and clears mounted-file caches.
   *
   * Closing is idempotent and leaves the JVM's borrowed JRT file system open. All owned systems are attempted
   * in reverse acquisition order; later failures are suppressed on the first, which is rethrown after all state is
   * cleared. Mounted files must no longer be used.
   */
  override def close(): Unit = synchronized {
    if (!closed) {
      closed = true
      var failure: Option[Throwable] = None

      ownedFileSystems.toVector.reverse.foreach { fileSystem =>
        try fileSystem.close()
        catch {
          case NonFatal(error) =>
            failure match {
              case Some(first) => first.addSuppressed(error)
              case None => failure = Some(error)
            }
        }
      }

      ownedFileSystems.clear()
      sourceArchives.clear()
      files.clear()
      roots.clear()
      byName = null
      failure.foreach(error => throw error)
    }
  }

  /** Rejects mounts after this file system has been closed. */
  private def ensureOpen(): Unit =
    if (closed) throw new IllegalStateException("library file system is closed")

  /**
   * Captures mounted class roots.
   *
   * @return class roots in mount order, excluding attached sources
   */
  def classRoots: Seq[VirtualFile] = roots.synchronized(roots.values().asScala.toVector.filterNot(_.isLibrarySource))

  /**
   * Captures mounted source roots.
   *
   * @return library and JDK source roots in mount order
   */
  def sourceRoots: Seq[VirtualFile] = roots.synchronized(roots.values().asScala.toVector.filter(_.isLibrarySource))

  /**
   * Finds mounted files by name across class and source roots.
   *
   * The filename index is built on first lookup and invalidated by each mount.
   *
   * @param name exact filename to look up
   * @return matching files, or an empty sequence when absent
   */
  def filesNamed(name: String): Seq[VirtualFile] = filenameIndex.getOrElse(name, Seq.empty)

  /**
   * Lists binary filenames without opening their PSI.
   *
   * Uses the mounted filename index; names can contain the JVM's nested-class separator.
   * Callers must still check the resolved class name and its module scope.
   *
   * @return the set of classfile base names
   */
  def classFileNames: Set[String] = filenameIndex.keySet.filter(_.endsWith(".class"))
    .map(_.stripSuffix(".class"))

  /** Builds the shared filename index lazily, publishing it only after a complete traversal. */
  private def filenameIndex: Map[String, Seq[VirtualFile]] = {
    var index = byName
    if (index == null) {
      val all = Vector.newBuilder[VirtualFile]

      /** Collects a file and its descendants. */
      def visit(file: VirtualFile): Unit = {
        ProgressManager.checkCanceled()
        all += file
        if (file.isDirectory) file.getChildren.foreach(visit)
      }

      roots.synchronized(roots.values().asScala.toVector).foreach(r => r.getChildren.foreach(visit))
      index = all.result().groupBy(_.getName)
      byName = index
    }
    index
  }

  /** Adds a stable root identity and invalidates the cached filename index. */
  private def mount(name: String, rootPath: Path, sources: Boolean): VirtualFile = {
    val root =
      new LibraryFile(this, ids.incrementAndGet(), rootPath, parent = null, rootName = name, sourcesRoot = sources)
    files.put(rootPath, root)
    roots.put(name, root)
    byName = null
    root
  }

  /**
   * Returns the single file for `path`, creating it on first lookup.
   *
   * Reuses the virtual-file identity for a path already reached through another lookup.
   *
   * @param parent directory the file is created under; ignored when the file already exists
   * @param path underlying archive or JRT path
   * @return the file registered for `path`
   */
  private[scalaanalyzer] def child(parent: LibraryFile, path: Path): LibraryFile =
    files.computeIfAbsent(
      path,
      p => new LibraryFile(this, ids.incrementAndGet(), p, parent, rootName = null, sourcesRoot = false),
    )

  /**
   * Resolves a virtual path within a mounted root.
   *
   * @param path root name alone or `<root name>!/<entry>`
   * @return the matching file, or null if the root or entry is absent
   */
  override def findFileByPath(path: String): VirtualFile = {
    val separator = path.indexOf("!/")
    val (rootName, entry) =
      if (separator < 0) (path, "")
      else (path.substring(0, separator), path.substring(separator + 2))

    roots.get(rootName) match {
      case null => null
      case root => if (entry.isEmpty) root else root.findFileByRelativePath(entry)
    }
  }

  override def refresh(asynchronous: Boolean): Unit = ()
  override def refreshAndFindFileByPath(path: String): VirtualFile = findFileByPath(path)
  override def isReadOnly: Boolean = true
}

/** Identifiers shared by the host-owned library virtual file system. */
object LibraryFileSystem {
  /** The protocol used to identify this host-owned virtual file system. */
  val Protocol = "scala-analyzer-library"
}

/**
 * A read-only file or directory within a mounted library root.
 *
 * The host-assigned ID supports plugin decompilation and is never persisted. Instances and cached children
 * retain their identity while mounted; the owning file system must remain open during use.
 *
 * @param fs owning library file system
 * @param id identifier assigned by the owning file system
 * @param nioPath underlying archive or JRT path
 * @param parent parent directory, or null for a mounted root
 * @param rootName virtual path name for a root, unused for descendants
 * @param sourcesRoot whether a mounted root contains attached sources
 */
final class LibraryFile private[scalaanalyzer] (
  fs: LibraryFileSystem,
  id: Int,
  val nioPath: Path,
  parent: LibraryFile,
  rootName: String,
  sourcesRoot: Boolean,
) extends VirtualFile with VirtualFileWithId {
  override def getId: Int = id

  /** Under a source root (library sources), not a class root. */
  val isLibrarySource: Boolean = if (parent == null) sourcesRoot else parent.isLibrarySource

  /**
   * Finds the mounted root containing this file.
   *
   * @return this file for a root, otherwise its root ancestor
   */
  def root: LibraryFile = if (parent == null) this else parent.root

  /**
   * Computes the path relative to the mounted root.
   *
   * @return a slash-separated entry path, empty for the root
   */
  def entry: String = if (parent == null) "" else root.nioPath.relativize(nioPath).toString.stripSuffix("/")

  /** Whether the underlying path is a directory, probed once on first use. */
  private lazy val directory = Files.isDirectory(nioPath)

  override def getName: String = if (parent == null) rootName else nioPath.getFileName.toString.stripSuffix("/")
  override def getFileSystem: LibraryFileSystem = fs

  /** The root name for a root, otherwise `<root name>!/<entry>`, the form `findFileByPath` resolves. */
  override def getPath: String =
    if (parent == null) rootName
    else {
      val parentPath = parent.getPath
      if (parent.getParent == null) s"$parentPath!/$getName" else s"$parentPath/$getName"
    }
  override def isWritable: Boolean = false
  override def isDirectory: Boolean = directory
  override def isValid: Boolean = true
  override def getParent: VirtualFile = parent

  /** Caches immutable mounted contents for repeated package lookups. */
  private lazy val childrenByName: Map[String, LibraryFile] =
    if (!directory) Map.empty
    else Using.resource(Files.list(nioPath)) { entries =>
      entries.iterator().asScala.map(p => p.getFileName.toString.stripSuffix("/") -> fs.child(this, p)).toMap
    }

  /** Lists the children sorted by name; empty for a file. */
  override def getChildren: Array[VirtualFile] =
    childrenByName.toVector.sortBy(_._1).map(_._2: VirtualFile).toArray

  override def findChild(name: String): VirtualFile = childrenByName.getOrElse(name, null)

  override def contentsToByteArray(): Array[Byte] = Files.readAllBytes(nioPath)
  override def getInputStream: InputStream = Files.newInputStream(nioPath)
  /** Rejects writes: library files are read-only. */
  override def getOutputStream(requestor: AnyRef, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
    throw new UnsupportedOperationException(s"library files are read-only: $getPath")
  // Library contents do not change while mounted, so a constant stamp is accurate.
  override def getTimeStamp: Long = 0L
  override def getModificationStamp: Long = 0L
  override def getLength: Long = if (directory) 0L else Files.size(nioPath)
  override def refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable): Unit =
    if (postRunnable != null) postRunnable.run()
  override def toString: String = s"LibraryFile($getPath)"
}
