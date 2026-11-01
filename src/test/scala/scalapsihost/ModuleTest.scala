package scalapsihost

import com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory

import java.nio.file.Path

/**
 * Modules as a build server describes them: each resolves against its own classpath, compiler options and module
 * dependencies. Expectations are what scalac reports when compiling the same code in the same setup.
 */
class ModuleTest extends munit.FunSuite {
  private def host = TestHost.host
  private val library213 = Path.of(sys.props("scalapsihost.fixture.scalaLibrary"))
  private val library212 = Path.of(sys.props("scalapsihost.fixture.scalaLibrary212"))

  private lazy val (scala213, scala212, noPredef) = {
    TestHost.libraryReport // the JDK
    (
      host.addModule(ModuleSpec("scala213", "2.13.18", Seq.empty, Seq(library213))),
      host.addModule(ModuleSpec("scala212", "2.12.21", Seq.empty, Seq(library212))),
      host.addModule(ModuleSpec("noPredef", "2.13.18", Seq("-Yno-predef"), Seq(library213))),
    )
  }

  private val sourceCount = new java.util.concurrent.atomic.AtomicInteger()

  private def errorsIn(module: HostModule, source: String): Seq[String] = {
    val file = host.openFile(Path.of(s"/${module.spec.name}/Source${sourceCount.incrementAndGet()}.scala"), source, module)
      .asInstanceOf[org.jetbrains.plugins.scala.lang.psi.api.ScalaFile]
    try host.diagnostics(file).filter(_.severity == HighlightSeverity.ERROR).map(_.message)
    finally host.closeSource(file)
  }

  test("each module resolves against its own classpath") {
    val source = "object UsesLazyList { val l = LazyList(1, 2) }"
    assertEquals(errorsIn(scala213, source), Seq.empty)
    val in212 = errorsIn(scala212, source)
    assert(in212.contains("Cannot resolve symbol LazyList"), in212)
  }

  test("compiler options from the build apply to the module's files") {
    val source = """object UsesPredef { def f(): Unit = println("x") }"""
    assertEquals(errorsIn(scala213, source), Seq.empty)
    assertEquals(errorsIn(noPredef, source), Seq("Cannot resolve symbol println"))
  }

  test("a module sees the sources of the modules it depends on, and no others") {
    val lib = host.addModule(ModuleSpec("lib", "2.13.18", Seq.empty, Seq(library213)))
    val client = host.addModule(ModuleSpec("client", "2.13.18", Seq.empty, Seq(library213), dependsOn = Seq("lib")))
    val libFile = host.openFile(Path.of("/lib/lib/Lib.scala"), "package lib\nobject Lib { val answer: Int = 42 }", lib)
    try {
      val clientSource = "object Client { val n: Int = lib.Lib.answer }"
      assertEquals(errorsIn(client, clientSource), Seq.empty)
      assertEquals(errorsIn(scala213, clientSource), Seq("Cannot resolve symbol lib"))
    } finally host.closeSource(libFile)
  }

  test("a package in an unrelated module does not shadow a library package") {
    val unrelated = host.addModule(ModuleSpec("unrelated-package", "2.13.18", Seq.empty, Seq(library213)))
    val hidden = host.openFile(Path.of("/unrelated/Hidden.scala"),
      "package visibility.scala.child\nclass Hidden", unrelated)
    try assertEquals(errorsIn(scala213, "package visibility\nobject Client { val xs = scala.List(1) }"), Seq.empty)
    finally host.closeSource(hidden)
  }

  test("library signatures resolve against their own Scala version") {
    val modules = Seq(scala213, scala212, scala213)
    host.read {
      val cache = ScalaShortNamesCacheManager.getInstance(using host.project)
      for (module <- modules) {
        val list = cache.getClassByFQName("scala.collection.immutable.List", host.modules.resolveScope(module))
        val signature = ScalaPsiElementFactory.createTypeElementFromText("scala.collection.immutable.List[Int]", list, null)
        val fromSignature = cache.getClassByFQName("scala.collection.immutable.List", signature.getResolveScope)
        assertEquals(fromSignature.getContainingFile.getVirtualFile, list.getContainingFile.getVirtualFile)
      }
    }
  }

  test("a module's compile classpath replaces dependency library versions") {
    val dependency = scala212
    val client = host.addModule(ModuleSpec("resolved-classpath", "2.13.18", Seq.empty, Seq(library213),
      dependsOn = Seq(dependency.spec.name)))
    host.read {
      val cache = ScalaShortNamesCacheManager.getInstance(using host.project)
      val inClient = cache.getClassesByFQName("scala.collection.immutable.List", host.modules.resolveScope(client))
      val in213 = cache.getClassesByFQName("scala.collection.immutable.List", host.modules.resolveScope(scala213))
      assertEquals(inClient.map(_.getContainingFile.getVirtualFile).toSet, in213.map(_.getContainingFile.getVirtualFile).toSet)
    }
  }

  test("stable identities distinguish equal display names and dependencies use identities") {
    val firstId = "bsp://identity-test/first"
    val secondId = "bsp://identity-test/second"
    val clientId = "bsp://identity-test/client"
    val first = host.addModule(ModuleSpec("shared-display-name", "2.13.18", Seq.empty, Seq(library213), identity = Some(firstId)))
    var second: Option[HostModule] = None
    var client: Option[HostModule] = None
    var source: Option[com.intellij.psi.PsiFile] = None
    try {
      second = Some(host.addModule(ModuleSpec("shared-display-name", "2.13.18", Seq.empty, Seq(library213), identity = Some(secondId))))
      client = Some(host.addModule(ModuleSpec("identity-client", "2.13.18", Seq.empty, Seq(library213),
        dependsOn = Seq(firstId), identity = Some(clientId))))

      assertEquals(host.modules.byId(firstId), Some(first))
      assertEquals(host.modules.byId(secondId), second)
      assertEquals(host.modules.named("shared-display-name"), None)
      assertEquals(client.map(_.spec.dependsOn), Some(Seq(firstId)))
      val manager = com.intellij.openapi.module.ModuleManager.getInstance(host.project)
      assert(manager.findModuleByName(first.module.getName) eq first.module)
      assert(second.exists(module => manager.findModuleByName(module.module.getName) eq module.module))

      source = Some(host.openFile(Path.of("/identity-test/First.scala"), "object First", first))
      intercept[IllegalStateException](host.removeModule(firstId))
      assertEquals(host.modules.byId(firstId), Some(first))
    } finally {
      source.foreach(host.closeSource)
      Seq(clientId, secondId, firstId).foreach(id => if (host.modules.byId(id).nonEmpty) host.removeModule(id))
    }
  }

  test("Java-only source packages and short names follow edits and module scopes") {
    // Short-name lookup also includes JDK binaries (including jline's Widget); this fixture checks source ownership.
    def sourceFiles(classes: Array[com.intellij.psi.PsiClass]): Set[com.intellij.psi.PsiFile] =
      classes.iterator.map(_.getContainingFile).filter(_.getVirtualFile.isInstanceOf[scalapsihost.platform.SourceFile]).toSet

    val first = host.addModule(ModuleSpec("java-lookup-first", "2.13.18", Seq.empty, Seq(library213)))
    val second = host.addModule(ModuleSpec("java-lookup-second", "2.13.18", Seq.empty, Seq(library213)))
    val text = "package hostjava.deep; public class Widget { public static class Nested {} }"
    val a = host.openFile(Path.of("/java-lookup-first/Widget.java"), text, first)
    val b = host.openFile(Path.of("/java-lookup-second/Widget.java"), text, second)
    val use = host.openFile(Path.of("/java-lookup-first/Use.java"),
      "package hostclient; import hostjava.deep.*; class Use { Widget value; }", first)
    val info = host.openFile(Path.of("/java-lookup-first/package-info.java"), "package hostjava.info;", first)
    try {
      host.read {
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(host.project)
        val cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(host.project)
        val scope = host.modules.resolveScope(first)
        val parent = facade.findPackage("hostjava")
        assert(parent != null)
        assertEquals(parent.getSubPackages(scope).map(_.getQualifiedName).toSet, Set("hostjava.deep", "hostjava.info"))
        assertEquals(facade.findPackage("hostjava.deep").getClasses(scope).map(_.getContainingFile).toSet, Set(a))
        assert(facade.findPackage("hostjava.info") != null)
        assertEquals(sourceFiles(cache.getClassesByName("Widget", scope)), Set(a))
        assertEquals(sourceFiles(cache.getClassesByName("Widget", host.modules.resolveScope(second))), Set(b))
        assert(cache.getAllClassNames.contains("Widget"))
        assertEquals(facade.findClass("hostjava.deep.Widget.Nested", scope).getContainingFile, a)
        val field = use.asInstanceOf[com.intellij.psi.PsiJavaFile].getClasses.head.getFields.head
        val referenced = field.getType.asInstanceOf[com.intellij.psi.PsiClassType].resolve()
        assert(referenced != null, "Java wildcard imports must resolve source-only packages")
        assertEquals(referenced.getContainingFile, a)
      }
      host.edit(a, 0, text.length, "package hostjava.moved; public class Renamed {}")
      host.read {
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(host.project)
        val cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(host.project)
        val scope = host.modules.resolveScope(first)
        assertEquals(facade.findClass("hostjava.deep.Widget", scope), null)
        assertEquals(sourceFiles(cache.getClassesByName("Widget", scope)), Set.empty)
        assertEquals(sourceFiles(cache.getClassesByName("Renamed", scope)), Set(a))
        assertEquals(facade.findPackage("hostjava").getSubPackages(scope).map(_.getQualifiedName).toSet,
          Set("hostjava.moved", "hostjava.info"))
      }
    } finally {
      Seq(a, b, use, info).foreach(host.closeSource)
      host.removeModule(second.spec.stableId)
      host.removeModule(first.spec.stableId)
    }
    host.read {
      val facade = com.intellij.psi.JavaPsiFacade.getInstance(host.project)
      assertEquals(facade.findPackage("hostjava"), null)
      assert(!com.intellij.psi.search.PsiShortNamesCache.getInstance(host.project).getAllClassNames.contains("Renamed"))
    }
  }

}
