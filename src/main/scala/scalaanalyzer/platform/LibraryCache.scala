package scalaanalyzer.platform

import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.{AttributeInputStream, AttributeOutputStream, FileAttribute}
import com.intellij.psi.stubs.{
  ObjectStubSerializer, ObjectStubTree, PsiFileStub, PsiFileStubImpl, Stub, StubElement, StubElementRegistryServiceImpl,
  StubInputStream, StubOutputStream, StubTree
}
import com.intellij.psi.tree.IElementType
import com.intellij.util.io.AbstractStringEnumerator

import java.io.{
  BufferedInputStream, BufferedOutputStream, ByteArrayInputStream, DataInputStream, DataOutputStream, IOException
}
import java.nio.file.{Files, NoSuchFileException, Path, StandardCopyOption}
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Keeps, from one server start to the next, what reading a library jar's class files produces: the plugin's
 * persistent file attributes (whether a class file is Scala, and the name of its source) and the stub trees built
 * from the decompiled classes. With both, a jar indexed before is indexed again without decompiling anything.
 *
 * The IDE keeps the same two things in its persistent file system and stub index. Here each jar has one file in the
 * cache directory, named after the jar's identity (its real path, size and modification time) and the classpath the
 * server runs with, so a changed jar, plugin or host never reads another's file. A file that cannot be read is
 * discarded and the jar is read from its classes again.
 *
 * A jar's cache is loaded by [[open]] before its classes are read, and written and released by [[finish]] after they
 * were indexed; it is only served in between. Afterwards the PSI holds the stub trees it needs and the plugin keeps
 * its decompilation results in memory, while the cached bytes would take tens of megabytes of heap for a large
 * build. Files outside an open jar (sources, the JDK's modules, a jar after `finish`) are neither cached nor served:
 * their attributes are not persisted and their stubs are built as before.
 *
 * @param directory where the jars' cache files live; created when the first one is written
 * @param classpath the identity of the code that builds attributes and stubs, see [[LibraryCache.classpathIdentity]]
 */
final class LibraryCache(directory: Path, classpath: String) {
  /**
   * The cached state of one jar.
   *
   * @param jarName the jar's file name, for the debug log
   * @param file the jar's cache file
   * @param loaded what reading the cache file found, for the debug log
   * @param names the strings that the stub bytes refer to by number
   * @param attributes attribute bytes by entry path and attribute id
   * @param stubs serialized stub trees by entry path
   */
  private final class JarCache(
    val jarName: String,
    val file: Path,
    val loaded: String,
    val names: Names,
    val attributes: ConcurrentHashMap[(String, String), StoredAttribute],
    val stubs: ConcurrentHashMap[String, Array[Byte]],
  ) {
    /** Whether something was added since the cache file was read. */
    @volatile var changed = false

    /** Stub trees served from the cache, for the debug log. */
    val treesRead = new AtomicInteger()

    /** Stub trees built because the cache had none, for the debug log. */
    val treesBuilt = new AtomicInteger()
  }

  /** An attribute's bytes with the version of the attribute that wrote them. */
  private final case class StoredAttribute(version: Int, bytes: Array[Byte])

  /** The open jars by their mounted root, between [[open]] and [[finish]]. */
  private val jars = new ConcurrentHashMap[VirtualFile, JarCache]()

  /**
   * The stub serializers by external id, the name a serialized stub records.
   *
   * The registry builds its state from the stub registry extensions on first use, which creates their element
   * types: loading it first lets the enumeration see all of them.
   */
  private lazy val serializers: Map[String, ObjectStubSerializer[Stub, Stub]] = {
    val registry = StubElementRegistryServiceImpl.getInstanceImpl
    registry.ensureStateLoaded()

    IElementType.enumerate(_ => true).iterator
      .flatMap(elementType => Option(registry.getStubSerializer(elementType)))
      // The registry pairs a serializer with stubs of its own element type, which deserialize under any parent.
      .map(serializer => serializer.getExternalId -> serializer.asInstanceOf[ObjectStubSerializer[Stub, Stub]])
      .toMap
  }

  /**
   * Loads the cache of a jar mounted at `root`, so the attributes and stubs of its classes are served from it.
   *
   * Opening a jar that is open already keeps its loaded cache. A cache file that does not exist yet, or cannot be
   * read, leaves the jar's cache empty; an unreadable one is deleted.
   *
   * @param root the jar's mounted root
   * @param jar the jar on disk
   */
  def open(root: VirtualFile, jar: Path): Unit = {
    val file = directory.resolve(cacheFileName(jar))

    jars.computeIfAbsent(root, _ => read(jar.getFileName.toString, file))
  }

  /**
   * Writes the cache of a jar opened with [[open]] if it gained attributes or stubs, and releases it.
   *
   * Finishing a jar that is not open does nothing. A failure to write is logged: the jar stays usable and is read
   * from its classes again next time.
   *
   * @param root the jar's mounted root
   */
  def finish(root: VirtualFile): Unit = {
    val jar = jars.remove(root)
    if (jar == null) return

    val outcome =
      if (!jar.changed) "unchanged"
      else
        try {
          write(jar)
          s"written, ${Files.size(jar.file) / 1024} KB"
        } catch {
          case NonFatal(error) =>
            HostLog.warn(s"library cache not written: ${jar.file}", error)
            "not written"
        }

    HostLog.debug(s"library cache of ${jar.jarName}: ${jar.loaded}; ${jar.treesRead} stub trees read, " +
      s"${jar.treesBuilt} built; $outcome")
  }

  /**
   * Deletes the cache files no server has read for [[LibraryCache.UnusedFor]]: those of jars no project uses
   * any more, and those that a newer jar, plugin or host made obsolete. Failures are logged.
   */
  def prune(): Unit = {
    val oldest = Instant.now().minus(LibraryCache.UnusedFor)

    try {
      if (Files.isDirectory(directory)) Using.resource(Files.list(directory)) { files =>
        files.iterator().asScala
          .filter(file => Files.getLastModifiedTime(file).toInstant.isBefore(oldest))
          .foreach(Files.deleteIfExists)
      }
    } catch { case NonFatal(error) => HostLog.warn(s"library cache not pruned: $directory", error) }
  }

  /**
   * Reads a persistent attribute of a library class file, as the platform's persistent file system does.
   *
   * @param file the class file
   * @param attribute the attribute to read
   * @return the stream the attribute was written to, or null when the file has none of the attribute's version
   */
  def readAttribute(file: VirtualFile, attribute: FileAttribute): AttributeInputStream =
    located(file).flatMap((jar, entry) => Option(jar.attributes.get(entry -> attribute.getId))) match {
      case Some(stored) if stored.version == attribute.getVersion =>
        new AttributeInputStream(new ByteArrayInputStream(stored.bytes), UnsupportedStrings)
      case _ => null
    }

  /**
   * Opens a stream that writes a persistent attribute of a library class file, kept when the stream is closed.
   *
   * Outside an open jar the bytes are discarded, as the host keeps no other persistent attributes.
   *
   * @param file the class file
   * @param attribute the attribute to write
   * @return the stream to write the attribute's bytes to
   */
  def writeAttribute(file: VirtualFile, attribute: FileAttribute): AttributeOutputStream = {
    val buffer = new BufferExposingByteArrayOutputStream()

    new AttributeOutputStream(buffer) {
      // A string table shared by all attributes is not kept; no attribute the host persists writes one.
      override def writeEnumeratedString(value: String): Unit =
        throw new UnsupportedOperationException("enumerated strings in persistent attributes")

      override def close(): Unit = {
        super.close()
        for ((jar, entry) <- located(file)) {
          jar.attributes.put(entry -> attribute.getId, StoredAttribute(attribute.getVersion, buffer.toByteArray))
          jar.changed = true
        }
      }
    }
  }

  /**
   * The stub tree of a library file: read from the cache while its jar is open and the tree was stored, otherwise
   * built and, while its jar is open, stored.
   *
   * A stored tree that cannot be read is dropped and built again. A tree is stored only when its file has one stub
   * root, as a decompiled class has, and every stub has a serializer.
   *
   * @param file the library file
   * @param build builds the tree from the file's content; may answer null when the file has no stubs
   * @return the stub tree, or null when `build` answers null
   */
  def stubTree(file: VirtualFile, build: () => ObjectStubTree[?]): ObjectStubTree[?] = located(file) match {
    case None => build()
    case Some((jar, entry)) =>
      val stored = Option(jar.stubs.get(entry)).flatMap { bytes =>
        try {
          val tree = deserialize(bytes, jar.names)
          jar.treesRead.incrementAndGet()
          Some(tree)
        }
        catch {
          case NonFatal(error) =>
            HostLog.warn(s"cached stubs of $entry in ${jar.file} are unreadable; building them again", error)
            jar.stubs.remove(entry)
            None
        }
      }

      stored.getOrElse {
        val built = build()
        jar.treesBuilt.incrementAndGet()
        val serialized = built match {
          case tree: StubTree if hasSingleRoot(tree.getRoot) =>
            try serialize(tree.getRoot, jar.names)
            catch {
              case NonFatal(error) =>
                HostLog.warn(s"stubs of $entry in ${jar.file} not cached", error)
                None
            }
          case _ => None
        }

        for (bytes <- serialized) {
          jar.stubs.put(entry, bytes)
          jar.changed = true
        }
        built
      }
  }

  /** Whether a built file stub is its file's only stub root; a file of several languages has one root for each. */
  private def hasSingleRoot(root: PsiFileStub[?]): Boolean = root match {
    case built: PsiFileStubImpl[?] => built.rootsAreSet && built.getStubRoots.sameElements(Seq(built))
    case _ => false
  }

  /** The open jar holding `file` and the file's path in it, when there is one. */
  private def located(file: VirtualFile): Option[(JarCache, String)] = file match {
    case library: LibraryFile => Option(jars.get(library.root)).map(_ -> library.entry)
    case _ => None
  }

  /**
   * Names the cache file of `jar`: the jar's file name, for a person looking at the directory, and a digest of
   * everything the cached content depends on.
   */
  private def cacheFileName(jar: Path): String = {
    val real = jar.toRealPath()
    val identity = s"${LibraryCache.FormatVersion}\n$real\n${Files.size(real)}\n${Files.getLastModifiedTime(real).toMillis}\n$classpath"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes("UTF-8"))

    s"${real.getFileName}-${HexFormat.of.formatHex(digest, 0, LibraryCache.DigestBytes)}.bin"
  }

  /** Reads the cache file of the jar `jarName`: a missing file gives an empty cache, an unreadable one is deleted. */
  private def read(jarName: String, file: Path): JarCache = {
    val names = new Names
    val attributes = new ConcurrentHashMap[(String, String), StoredAttribute]()
    val stubs = new ConcurrentHashMap[String, Array[Byte]]()

    val loaded = try {
      Using.resource(new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) { in =>
        if (in.readInt() != LibraryCache.Magic || in.readInt() != LibraryCache.FormatVersion)
          throw new IOException("not a library cache file of this format")
        for (_ <- 0 until in.readInt()) names.enumerate(in.readUTF())
        for (_ <- 0 until in.readInt()) {
          val entry = in.readUTF()
          val id = in.readUTF()
          val version = in.readInt()
          attributes.put(entry -> id, StoredAttribute(version, in.readNBytes(in.readInt())))
        }
        for (_ <- 0 until in.readInt()) stubs.put(in.readUTF(), in.readNBytes(in.readInt()))
      }
      // The modification time records the last use: unused files are deleted by `prune`.
      Files.setLastModifiedTime(file, FileTime.from(Instant.now()))
      s"${stubs.size} stub trees and ${attributes.size} attributes loaded"
    } catch {
      case _: NoSuchFileException => "no cache file yet"
      case NonFatal(error) =>
        HostLog.warn(s"library cache unreadable, reading the jar again: $file", error)
        names.clear()
        attributes.clear()
        stubs.clear()
        Files.deleteIfExists(file)
        "cache file unreadable"
    }

    new JarCache(jarName, file, loaded, names, attributes, stubs)
  }

  /** Writes a jar's cache file whole, replacing the previous one in one step so a reader never sees half of it. */
  private def write(jar: JarCache): Unit = {
    val temporary = Files.createTempFile(Files.createDirectories(directory), jar.file.getFileName.toString, ".tmp")
    val names = jar.names.values
    val attributes = jar.attributes.asScala.toVector
    val stubs = jar.stubs.asScala.toVector

    try {
      Using.resource(new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) { out =>
        out.writeInt(LibraryCache.Magic)
        out.writeInt(LibraryCache.FormatVersion)
        out.writeInt(names.size)
        names.foreach(out.writeUTF)
        out.writeInt(attributes.size)
        for (((entry, id), stored) <- attributes) {
          out.writeUTF(entry)
          out.writeUTF(id)
          out.writeInt(stored.version)
          out.writeInt(stored.bytes.length)
          out.write(stored.bytes)
        }
        out.writeInt(stubs.size)
        for ((entry, bytes) <- stubs) {
          out.writeUTF(entry)
          out.writeInt(bytes.length)
          out.write(bytes)
        }
      }
      Files.move(temporary, jar.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally Files.deleteIfExists(temporary)
  }

  /**
   * Serializes a stub tree in preorder: each stub as its serializer's external id, the serializer's bytes and the
   * number of its children. None when a stub has no serializer, as such a tree cannot be read back.
   */
  private def serialize(root: Stub, names: Names): Option[Array[Byte]] = {
    val registry = StubElementRegistryServiceImpl.getInstanceImpl
    val bytes = new BufferExposingByteArrayOutputStream()
    val out = new StubOutputStream(bytes, names)

    def writeStub(stub: Stub): Boolean = {
      val elementType = stub match {
        case file: PsiFileStub[?] => file.getFileElementType
        case element: StubElement[?] => element.getElementType
        case _ => null
      }
      val serializer = Option(elementType).flatMap(t => Option(registry.getStubSerializer(t)))

      serializer.exists { found =>
        out.writeName(found.getExternalId)
        // The registry pairs the serializer with the stub's element type, so it serializes this stub.
        found.asInstanceOf[ObjectStubSerializer[Stub, Stub]].serialize(stub, out)
        val children = stub.getChildrenStubs.asScala
        out.writeInt(children.size)
        children.forall(writeStub)
      }
    }

    val complete = writeStub(root)

    out.close()
    Option.when(complete)(bytes.toByteArray)
  }

  /** Reads a stub tree written by [[serialize]]. */
  private def deserialize(bytes: Array[Byte], names: Names): StubTree = {
    val in = new StubInputStream(new ByteArrayInputStream(bytes), names)

    // A serializer's stub registers itself as a child of the parent it is given, in the order read.
    def readStub(parent: Stub): Stub = {
      val id = in.readNameString()
      val serializer = serializers.getOrElse(id, throw new IOException(s"no stub serializer $id"))
      val stub = serializer.deserialize(in, parent)
      for (_ <- 0 until in.readInt()) readStub(stub)
      stub
    }

    readStub(null) match {
      case root: PsiFileStubImpl[?] =>
        // As the platform's builder does: PSI refuses a stub tree whose roots were not set.
        root.setStubRoots(Array(root))
        new StubTree(root)
      case other => throw new IOException(s"a stub tree whose root is not a file stub: ${other.getClass.getName}")
    }
  }

  /**
   * The strings of a jar's stub bytes, numbered from 1 in the order first seen (0 stands for null). Stub trees are
   * built and read on several threads at once.
   */
  private final class Names extends AbstractStringEnumerator {
    private val ids = mutable.HashMap.empty[String, Int]
    private val strings = mutable.ArrayBuffer.empty[String]

    override def enumerate(value: String): Int = synchronized {
      if (value == null) 0 else ids.getOrElseUpdate(value, { strings += value; strings.size })
    }

    override def valueOf(id: Int): String = synchronized(if (id == 0) null else strings(id - 1))

    /** The strings in number order. */
    def values: Vector[String] = synchronized(strings.toVector)

    /** Forgets all strings. */
    def clear(): Unit = synchronized {
      ids.clear()
      strings.clear()
    }

    override def markCorrupted(): Unit = ()
    override def isDirty: Boolean = false
    override def force(): Unit = ()
    override def close(): Unit = ()
  }

  /** Answers the string table of attribute streams, which no persisted attribute uses. */
  private object UnsupportedStrings extends com.intellij.util.io.DataEnumerator[String] {
    override def enumerate(value: String): Int =
      throw new UnsupportedOperationException("enumerated strings in persistent attributes")
    override def valueOf(id: Int): String =
      throw new UnsupportedOperationException("enumerated strings in persistent attributes")
  }

}

object LibraryCache {
  /** Marks a library cache file: "SALC". */
  private val Magic = 0x53414c43

  /** The layout of a cache file and of the stub trees in it; a change gives every cache file a new name. */
  private val FormatVersion = 1

  /** Bytes of the identity digest in a cache file's name: 16 hex digits make a collision negligible. */
  private val DigestBytes = 8

  /**
   * How long a cache file may go unread before it is deleted: a jar no project has opened for a month. The debugger's
   * unpacked library sources (`DebugAdapter`) are kept as long.
   */
  private[scalaanalyzer] val UnusedFor = Duration.ofDays(30)

  /**
   * The identity of the code that produces cached attributes and stubs: every entry of the JVM's classpath, a jar by
   * its size and modification time, a directory (a development build) by the newest modification time inside it.
   * A rebuilt host or plugin thus never reads a cache that an older one wrote.
   *
   * @return text that changes whenever the code on the classpath changes
   */
  def classpathIdentity(): String =
    System.getProperty("java.class.path").split(java.io.File.pathSeparator).iterator.map(Path.of(_)).collect {
      case jar if Files.isRegularFile(jar) => s"$jar ${Files.size(jar)} ${Files.getLastModifiedTime(jar).toMillis}"
      case classes if Files.isDirectory(classes) =>
        val newest = Using.resource(Files.walk(classes))(_.iterator().asScala.map(Files.getLastModifiedTime(_).toMillis).max)
        s"$classes $newest"
    }.mkString("\n")
}
