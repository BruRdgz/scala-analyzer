package scalaanalyzer

import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.psi.stubs.{
  CoreStubTreeLoader, ObjectStubSerializer, ObjectStubTree, PsiFileStub, Stub, StubElement,
  StubElementRegistryServiceImpl, StubOutputStream
}
import com.intellij.util.io.AbstractStringEnumerator

import scalaanalyzer.platform.{LibraryCache, LibraryFileSystem}

import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** A jar's attributes and stub trees come back from its cache file as they were stored, and a bad file is dropped. */
class LibraryCacheTest extends munit.FunSuite {
  private val scalaLibrary = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary"))

  /** A fresh cache directory and library mount for each test, closed and deleted after it. */
  private val mount = FunFixture[(Path, LibraryFileSystem)](
    _ => (Files.createTempDirectory("library-cache"), new LibraryFileSystem),
    { (directory, libraryFiles) =>
      libraryFiles.close()
      Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
    },
  )

  /** The class files beneath a mounted root. */
  private def classFiles(root: VirtualFile): Seq[VirtualFile] =
    root.getChildren.toSeq.flatMap { child =>
      if (child.isDirectory) classFiles(child) else Seq(child).filter(_.getName.endsWith(".class"))
    }

  /**
   * Each stub of a tree in preorder, as its class and the bytes its serializer writes. Strings are numbered in the
   * order first written, so two equal trees give equal fingerprints.
   */
  private def fingerprint(tree: ObjectStubTree[?]): Seq[(String, Seq[Byte])] = {
    val registry = StubElementRegistryServiceImpl.getInstanceImpl
    val names = new AbstractStringEnumerator {
      private val ids = mutable.LinkedHashMap.empty[String, Int]
      override def enumerate(value: String): Int = if (value == null) 0 else ids.getOrElseUpdate(value, ids.size + 1)
      override def valueOf(id: Int): String = ids.keys.toSeq(id - 1)
      override def markCorrupted(): Unit = ()
      override def isDirty: Boolean = false
      override def force(): Unit = ()
      override def close(): Unit = ()
    }

    def stubs(stub: Stub): Seq[Stub] = stub +: stub.getChildrenStubs.asScala.toSeq.flatMap(stubs)

    stubs(tree.getRoot).map { stub =>
      val elementType = stub match {
        case file: PsiFileStub[?] => file.getFileElementType
        case element: StubElement[?] => element.getElementType
      }
      val bytes = new BufferExposingByteArrayOutputStream()
      val out = new StubOutputStream(bytes, names)
      // The registry pairs the serializer with the stub's element type.
      registry.getStubSerializer(elementType).asInstanceOf[ObjectStubSerializer[Stub, Stub]].serialize(stub, out)
      out.close()
      stub.getClass.getName -> bytes.toByteArray.toSeq
    }
  }

  mount.test("every stub tree of scala-library is read back from the cache as it was built") { (directory, libraryFiles) =>
    val host = TestHost.host
    val root = libraryFiles.mountJar(scalaLibrary)
    val files = classFiles(root)
    val loader = new CoreStubTreeLoader
    val writing = new LibraryCache(directory, "test classpath")

    writing.open(root, scalaLibrary)
    val built = host.read(files.flatMap { file =>
      Option(writing.stubTree(file, () => loader.readOrBuild(host.project, file, null))).map(file.getPath -> fingerprint(_))
    }).toMap
    writing.finish(root)

    val reading = new LibraryCache(directory, "test classpath")
    reading.open(root, scalaLibrary)
    val read = host.read(built.keys.toSeq.map { path =>
      val file = root.getFileSystem.findFileByPath(path)
      path -> fingerprint(reading.stubTree(file, () => fail(s"$path was built again instead of read from the cache")))
    }).toMap
    reading.finish(root)

    assert(built.size > 100, s"only ${built.size} stub trees were built")
    assertEquals(read.keySet, built.keySet)
    for (path <- built.keys) assertEquals(read(path), built(path), path)
  }

  mount.test("an attribute is read back only by the version of the attribute that wrote it") { (directory, libraryFiles) =>
    val root = libraryFiles.mountJar(scalaLibrary)
    val option = root.findFileByRelativePath("scala/Option.class")
    val writing = new LibraryCache(directory, "test classpath")

    writing.open(root, scalaLibrary)
    val out = writing.writeAttribute(option, new FileAttribute("library-cache-test", 2, false))
    out.writeUTF("Option.scala")
    out.close()
    writing.finish(root)

    val reading = new LibraryCache(directory, "test classpath")
    reading.open(root, scalaLibrary)
    assertEquals(reading.readAttribute(option, new FileAttribute("library-cache-test", 2, false)).readUTF(), "Option.scala")
    assertEquals(reading.readAttribute(option, new FileAttribute("library-cache-test", 3, false)), null)
    reading.finish(root)

    val otherClasspath = new LibraryCache(directory, "another classpath")
    otherClasspath.open(root, scalaLibrary)
    assertEquals(otherClasspath.readAttribute(option, new FileAttribute("library-cache-test", 2, false)), null)
    otherClasspath.finish(root)
  }

  mount.test("an unreadable cache file is deleted and the jar's stubs are built again") { (directory, libraryFiles) =>
    val host = TestHost.host
    val root = libraryFiles.mountJar(scalaLibrary)
    val option = root.findFileByRelativePath("scala/Option.class")
    val loader = new CoreStubTreeLoader
    val writing = new LibraryCache(directory, "test classpath")

    writing.open(root, scalaLibrary)
    host.read(writing.stubTree(option, () => loader.readOrBuild(host.project, option, null)))
    writing.finish(root)
    val cacheFile = Files.list(directory).iterator().asScala.toSeq match {
      case Seq(file) => file
      case other => fail(s"expected one cache file, found $other")
    }
    Files.write(cacheFile, "not a cache file".getBytes)

    val reading = new LibraryCache(directory, "test classpath")
    reading.open(root, scalaLibrary)
    assert(!Files.exists(cacheFile), "the unreadable cache file was kept")
    var builds = 0
    host.read(reading.stubTree(option, () => { builds += 1; loader.readOrBuild(host.project, option, null) }))
    reading.finish(root)
    assertEquals(builds, 1)
  }
}
