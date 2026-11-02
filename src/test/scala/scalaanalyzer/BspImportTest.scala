package scalaanalyzer

import com.google.gson.JsonObject
import com.intellij.lang.annotation.HighlightSeverity

import scalaanalyzer.build.{BspImport, CompileSpec, DocumentCompiler, ImportedModule}
import scalaanalyzer.platform.{CallLog}

import java.net.{URI, UnixDomainSocketAddress}
import java.nio.channels.SocketChannel
import java.nio.file.{Files, Path}

import scala.concurrent.duration.*
import scala.util.{Try, Using}

/**
 * A real build through BSP: `fixtures/bsp-project` (sbt; `app` depends on `core` and compiles with `-Xsource:3`;
 * `core` mixes Scala and Java).
 * Needs the fixture's connection file: run `sbt bspConfig` in the fixture once. scalac compiles the fixture without
 * errors, so the host must report none.
 */
class BspImportTest extends munit.FunSuite {
  override val munitTimeout: Duration = 10.minutes

  private val fixture = Path.of("fixtures/bsp-project").toAbsolutePath
  private lazy val imported = BspImport.importBuild(fixture)
  private def module(name: String) = imported.find(_.spec.name == name).getOrElse(fail(s"no module $name in ${imported.map(_.spec.name)}"))

  test("closing a connection leaves the sbt server running for the build's other clients") {
    BspImport.connect(fixture).close()
    assert(sbtServerListening(), "closing the connection stopped the sbt server")
  }

  // The server reads the request and exits, as sbt does when the build fails to load.
  test("a build server that exits without answering fails the connection at once, not at the timeout") {
    val project = Files.createTempDirectory("scala-analyzer-exiting-server")
    Files.createDirectory(project.resolve(".bsp"))
    Files.writeString(project.resolve(".bsp/exiting.json"),
      """{"name":"exiting","version":"0","bspVersion":"2.1.0","languages":["scala"],"argv":["sh","-c","head -c 1 >/dev/null; exit 3"]}""")
    val started = System.nanoTime()
    val failure = intercept[BspImport.BuildServerExited](BspImport.connect(project, timeoutMinutes = 1))
    assert((System.nanoTime() - started).nanos < 30.seconds, "waited for the timeout")
    assert(failure.getMessage.contains("code 3"), failure.getMessage)
  }

  // While sbt's server runs, project/target/active.json names the socket it listens on.
  private def sbtServerListening(): Boolean = {
    val active = BspImport.gson.fromJson(Files.readString(fixture.resolve("project/target/active.json")), classOf[JsonObject])
    val socket = Path.of(URI.create(active.get("uri").getAsString).getPath)
    Try(Using.resource(SocketChannel.open(UnixDomainSocketAddress.of(socket)))(_ => ())).isSuccess
  }

  test("imports the build's Scala targets with their settings") {
    val app = module("app")
    assert(app.spec.identity.exists(_ == app.spec.stableId), app.spec)
    assert(app.spec.stableId != app.spec.name, app.spec)
    assertEquals(app.spec.scalaVersion, "2.13.18")
    assertEquals(app.spec.dependsOn, Seq(module("core").spec.stableId))
    assertEquals(imported.map(_.spec.stableId).distinct.size, imported.size)
    assert(app.spec.scalacOptions.contains("-Xsource:3"), app.spec.scalacOptions)
    assert(app.spec.classpath.exists(_.getFileName.toString == "scala-library-2.13.18.jar"), app.spec.classpath)
    assertEquals(app.sources.map(fixture.relativize(_).toString), Seq("app/src/main/scala/app/Main.scala"))
    assert(!module("core").spec.scalacOptions.contains("-Xsource:3"))
    assert(module("core").sources.map(fixture.relativize(_).toString).contains("core/src/main/java/core/Shout.java"))
  }

  test("module ordering rejects duplicate identities, unknown dependencies and ambiguous shared sources") {
    val compile = CompileSpec("2.13.18", Seq.empty, Seq.empty, Path.of("/classes"), Seq.empty)
    def target(name: String, id: String, dependencies: Seq[String] = Seq.empty, sources: Seq[Path] = Seq.empty) =
      ImportedModule(ModuleSpec(name, "2.13.18", Seq.empty, Seq.empty, dependsOn = dependencies, identity = Some(id)), sources, compile)

    val duplicate = intercept[IllegalArgumentException] {
      BspImport.dependenciesFirst(Seq(target("first", "duplicate"), target("second", "duplicate")))
    }
    assert(duplicate.getMessage.contains("duplicate module identity"), duplicate.getMessage)

    val missing = intercept[IllegalArgumentException] {
      BspImport.dependenciesFirst(Seq(target("client", "client", Seq("missing-target"))))
    }
    assert(missing.getMessage.contains("missing-target"), missing.getMessage)

    val cycle = intercept[IllegalArgumentException] {
      BspImport.dependenciesFirst(Seq(target("first", "first", Seq("second")), target("second", "second", Seq("first"))))
    }
    assert(cycle.getMessage.contains("first") && cycle.getMessage.contains("second"), cycle.getMessage)

    val shared = Path.of("/shared/Source.scala")
    val ambiguous = intercept[IllegalArgumentException] {
      BspImport.dependenciesFirst(Seq(target("first", "target-one", sources = Seq(shared)),
        target("second", "target-two", sources = Seq(shared))))
    }
    assert(ambiguous.getMessage.contains(shared.toString), ambiguous.getMessage)
    assert(ambiguous.getMessage.contains("target-one") && ambiguous.getMessage.contains("target-two"), ambiguous.getMessage)
  }

  // In the fixture itself, with a broken file for the duration of the test, so its build outputs remain reusable.
  test("compiling through the build server reports the compiler's errors by file and position") {
    val broken = fixture.resolve("core/src/main/scala/core/Broken.scala")
    Files.writeString(broken, "package core\n\nobject Broken {\n  val n: Int = \"one\"\n}\n")
    val result =
      try Using.resource(BspImport.connect(fixture))(_.compile())
      finally Files.delete(broken)
    assert(!result.succeeded)
    val errors = result.diagnostics.getOrElse(broken, Seq.empty).filter(_.severity == HighlightSeverity.ERROR)
    assertEquals(errors.map(_.start.line), Seq(3))
    assert(errors.head.message.contains("type mismatch"), errors)
  }

  test("compiles a document of a Scala 3 module with the module's compiler") {
    val core3 = module("core3")
    // The module's class output holds its other files' classes (Greet3), which the document uses.
    Using.resource(BspImport.connect(fixture))(_.compile())
    Using.resource(new DocumentCompiler) { compiler =>
      def errors(text: String) = compiler.compile(core3.compile, "Use.scala", text).filter(_.severity == HighlightSeverity.ERROR)

      assertEquals(errors("package core3\n\nobject Use:\n  val s: String = Greet3.hello(\"x\")\n"), Seq.empty)
      // The error alone: not the compiler's summary of the run ("1 error found").
      val mismatch = compiler.compile(core3.compile, "Use.scala", "package core3\n\nobject Use:\n  val n: Int = Greet3.hello(\"x\")\n")
      assertEquals(mismatch.map(d => (d.severity, d.start.line)), Seq((HighlightSeverity.ERROR, 3)))
      assert(mismatch.head.message.contains("Required: Int"), mismatch)
    }
  }

  test("the imported project highlights without errors") {
    TestHost.libraryReport // the JDK
    val hostModules = imported.map(m => m -> TestHost.host.addModule(m.spec))
    val files = for ((m, hostModule) <- hostModules; source <- m.sources) yield {
      fixture.relativize(source).toString -> TestHost.host.openFile(source, Files.readString(source), hostModule)
    }
    try {
      val errors = files.collect { case (path, file: org.jetbrains.plugins.scala.lang.psi.api.ScalaFile) => path -> file }.flatMap { (path, file) =>
        TestHost.host.diagnostics(file).filter(_.severity == HighlightSeverity.ERROR).map(d => s"$path ${d.range}: ${d.message}")
      }
      assertEquals(errors, Seq.empty)
      // Java source PSI has its typed leaves (the class name is a PsiIdentifier).
      val javaClasses = TestHost.host.read {
        files.collect { case (_, file: com.intellij.psi.PsiJavaFile) => file.getClasses.toSeq.map(_.getName) }.flatten
      }
      assertEquals(javaClasses, Seq("Shout"))
      // Nothing left the host's tables or was logged as an error on the way (both would hide problems).
      val swallowed = CallLog.snapshot.map(_._1).filter(e => e.startsWith("UNSUPPORTED ") || e.startsWith("LOGGED ERROR "))
      assertEquals(swallowed, Seq.empty)
    } finally files.foreach((_, file) => TestHost.host.closeSource(file))
  }
}
