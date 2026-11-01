package scalapsihost

import com.intellij.psi.search.EverythingGlobalScope
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass

import scalapsihost.platform.{LibraryFile, LibraryFileSystem}

import java.net.URI
import java.nio.file.{ClosedFileSystemException, FileSystems, Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.util.Using

/** Library binaries reach the plugin's symbol lookups through its own decompiler, stub builder and indexers. */
class LibraryIndexTest extends munit.FunSuite {

  test("indexes scala-library without refusing any class") {
    val report = TestHost.libraryReport
    assertEquals(report.refused, Seq.empty)
    assert(report.scalaIndexed > 0, report)
  }

  test("the plugin's class lookup finds a library class in the host index") {
    TestHost.libraryReport
    val project = TestHost.host.project
    val option = ScalaShortNamesCacheManager.getInstance(using project)
      .getClassByFQName("scala.Option", new EverythingGlobalScope(project))
    option match {
      case c: ScClass => assertEquals(c.qualifiedName, "scala.Option")
      case other => fail(s"expected the decompiled ScClass scala.Option, got $other")
    }
  }

  // The test host attaches no JDK sources: a JDK class is its own navigation element, and its text is decompiled.
  test("a Java library class without sources has decompiled text, with its declarations in it") {
    TestHost.libraryReport
    val host = TestHost.host
    host.read {
      val list = com.intellij.psi.JavaPsiFacade.getInstance(host.project)
        .findClass("java.util.ArrayList", new EverythingGlobalScope(host.project))
      val target = list.getNavigationElement
      val text = target.getContainingFile.getText
      assert(text.startsWith("ArrayList", target.getTextOffset), text.substring(target.getTextOffset, target.getTextOffset + 40))
    }
  }

  test("attached sources with unknown file types use the default provider") {
    val archive = Files.createTempFile("unknown-library-sources", ".jar")
    val libraryFiles = new LibraryFileSystem
    try {
      Using.resource(new ZipOutputStream(Files.newOutputStream(archive))) { zip =>
        zip.putNextEntry(new ZipEntry("example/Library.kt"))
        zip.write("package example\nclass Library".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        zip.closeEntry()
      }
      val source = libraryFiles.mountSources(archive).findFileByRelativePath("example/Library.kt")
      val host = TestHost.host
      host.read {
        assertEquals(source.getFileType, com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE)
        val file = com.intellij.psi.PsiManager.getInstance(host.project).findFile(source)
        assert(file != null)
        assertEquals(file.getVirtualFile, source)
        assert(file.getViewProvider.isPhysical)
      }
    } finally {
      libraryFiles.close()
      Files.deleteIfExists(archive)
    }
  }

  test("closing library mounts closes owned archives and leaves the process JDK filesystem open") {
    val libraryFiles = new LibraryFileSystem
    val archive = Path.of(sys.props("scalapsihost.fixture.scalaLibrary"))
    val archiveRoot = libraryFiles.mountJar(archive).asInstanceOf[LibraryFile]
    val classFile = archiveRoot.nioPath.resolve("scala/Option.class")
    val sources = Path.of(sys.props("scalapsihost.fixture.scalaLibrarySources"))
    val sourceRoot = libraryFiles.mountSources(sources).asInstanceOf[LibraryFile]
    val sourceFile = sourceRoot.nioPath.resolve("scala/Option.scala")
    val sharedJdkFileSystem = FileSystems.getFileSystem(URI.create("jrt:/"))

    try {
      assert(Files.isRegularFile(classFile))
      assert(Files.isRegularFile(sourceFile))
      libraryFiles.mountJdk(Path.of(System.getProperty("java.home")))
      libraryFiles.close()
      libraryFiles.close()

      intercept[ClosedFileSystemException](Files.readAllBytes(classFile))
      intercept[ClosedFileSystemException](Files.readAllBytes(sourceFile))
      assert(Files.isDirectory(sharedJdkFileSystem.getPath("/modules")))
    } finally libraryFiles.close()
  }
}
