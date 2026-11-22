package scalaanalyzer.build

import com.intellij.lang.annotation.HighlightSeverity

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Maven and Gradle builds imported through Bloop: `fixtures/maven-project` and `fixtures/gradle-project` (`core` with
 * a JUnit 5 test, `app` depending on `core` and commons-lang3). Each case runs the real build tool, so it is skipped
 * when `bloop` or the tool is not on the `PATH`. Every test imports a fresh copy of the fixture, which keeps the
 * fixture free of generated files and lets the tests edit the build.
 *
 * The Bloop server these tests start is stopped afterwards, unless one was already running when they began.
 */
class BloopImportTest extends munit.FunSuite {
  override val munitTimeout: Duration = 15.minutes

  /** What differs between the two builds: the fixture, the tool and the edit that adds a dependency to `app`. */
  /**
   * A fixture build and what its import must give.
   *
   * @param javaModules the targets that matter: Bloop's Gradle plugin makes no test target for a project without tests,
   *                    and the Maven plugin also adds targets for the aggregator pom, which has no sources
   */
  private case class Build(label: String, fixture: String, tool: String, javaModules: Seq[String], appBuildFile: String,
      addGuava: String => String)

  private val builds = Seq(
    Build("Maven", "fixtures/maven-project", "mvn", Seq("core", "core-test", "app", "app-test"), "app/pom.xml",
      _.replace("</dependencies>",
        "<dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.0.0-jre</version></dependency>\n</dependencies>")),
    Build("Gradle", "fixtures/gradle-project", "gradle", Seq("core", "core-test", "app"), "app/build.gradle.kts",
      _.replace("dependencies {", "dependencies {\n    implementation(\"com.google.guava:guava:33.0.0-jre\")")),
  )

  private var bloopWasRunning = true

  override def beforeAll(): Unit = bloopWasRunning = bloopServerRunning()

  override def afterAll(): Unit = if (!bloopWasRunning && onPath("bloop")) bloop("exit")

  /**
   * Whether a Bloop server is running: `bloop status` prints `running` or `stopped`. Any other answer counts as running,
   * so this never stops a server it did not start.
   */
  private def bloopServerRunning(): Boolean =
    onPath("bloop") && bloop("status").trim != "stopped"

  /** Runs `bloop` with the arguments and returns its combined output, waiting up to a minute for it to exit. */
  private def bloop(arguments: String*): String = {
    val process = new ProcessBuilder(("bloop" +: arguments).asJava).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes())

    process.waitFor(1, TimeUnit.MINUTES)
    output
  }

  private def onPath(executable: String): Boolean =
    System.getenv("PATH").split(File.pathSeparator).exists(directory => Files.isExecutable(Path.of(directory, executable)))

  private def copyOf(fixture: String): Path = {
    val source = Path.of(fixture).toAbsolutePath
    val target = Files.createTempDirectory("bloop-fixture").toRealPath()

    Using.resource(Files.walk(source)) { paths =>
      paths.iterator.asScala.foreach { path =>
        val destination = target.resolve(source.relativize(path).toString)
        if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
      }
    }
    target
  }

  private def delete(directory: Path): Unit =
    Using.resource(Files.walk(directory))(_.iterator.asScala.toSeq.reverse.foreach(Files.deleteIfExists(_)))

  /** Imports a fresh copy of the fixture through `BspImport.connect`, skipping the test when the tools are missing. */
  private def withConnection[T](build: Build)(body: (Path, BuildConnection) => T): T = {
    assume(onPath("bloop"), "bloop is not on the PATH")
    assume(onPath(build.tool), s"${build.tool} is not on the PATH")

    val copy = copyOf(build.fixture)
    try Using.resource(BspImport.connect(copy))(body(copy, _))
    finally delete(copy)
  }

  private def jarNames(module: ImportedModule): Seq[String] = module.spec.classpath.map(_.getFileName.toString)

  private def named(modules: Seq[ImportedModule], name: String): ImportedModule =
    modules.find(_.spec.name == name).getOrElse(fail(s"no module $name in ${modules.map(_.spec.name)}"))

  builds.foreach { build =>
    test(s"${build.label} modules are Java modules with their classpath, dependencies and sources") {
      withConnection(build) { (copy, connection) =>
        val modules = connection.modules()
        val core = named(modules, "core")
        val app = named(modules, "app")

        val javaModules = build.javaModules.map(named(modules, _))
        javaModules.foreach { module =>
          assertEquals(module.spec.scalaVersion, None, module.spec.name)
          assertEquals(module.compile, None, module.spec.name)
        }
        assert(core.spec.javacOptions.sliding(2).contains(Seq("--release", "17")), core.spec.javacOptions)
        assert(jarNames(app).exists(_.startsWith("commons-lang3")), jarNames(app))
        assert(app.spec.dependsOn.contains(core.spec.stableId), app.spec.dependsOn)
        assertEquals(core.sources.map(_.toRealPath()), Seq(copy.resolve("core/src/main/java/demo/core/Greeter.java")))
        assertEquals(app.sources.map(_.toRealPath()), Seq(copy.resolve("app/src/main/java/demo/app/Main.java")))
      }
    }

    test(s"${build.label} build compile reports a Java type error on its file") {
      withConnection(build) { (copy, connection) =>
        val broken = copy.resolve("core/src/main/java/demo/core/Broken.java")
        Files.writeString(broken, "package demo.core;\n\nclass Broken {\n  int n = \"one\";\n}\n")

        val result = connection.compile()
        val errors = result.diagnostics.getOrElse(broken.toRealPath(), Seq.empty).filter(_.severity == HighlightSeverity.ERROR)

        assert(!result.succeeded)
        assertEquals(errors.map(_.start.line), Seq(3), result.diagnostics.toString)
      }
    }

    // The selections of the JUnit 5 lenses (a class, then one method) run by the IDE's JUnitStarter on the build's own
    // test classpath, which lists junit-platform-launcher (JUnit 5 needs it, and the IDE would download it).
    test(s"${build.label} runs a JUnit 5 test class and method through the IDE's runner") {
      withConnection(build) { (_, connection) =>
        val target = named(connection.modules(), "core-test").spec.stableId
        assert(connection.compile().succeeded)

        val runner = new TestRun
        try Seq(None, Some("greets")).foreach { method =>
          val summaries = new LinkedBlockingQueue[TestRun.Result]
          val log = new LinkedBlockingQueue[String]
          runner.start(TestRun.Selection(target, "junit5", "demo.core.GreeterTest", method),
            () => connection.testEnvironment(target), log.add(_), summaries.add(_)).get(5, TimeUnit.MINUTES)

          assertEquals(summaries.poll(), TestRun.Result("GreeterTest: 1 passed, 0 failed, 0 ignored"), log.asScala.mkString("\n"))
        } finally runner.close()
      }
    }

    // A Java target has no Scala version: the adapter takes its default one, and Java frames need no download.
    test(s"${build.label} debugs a Java main class: a breakpoint in the module it depends on, and the parameter's value") {
      withConnection(build) { (copy, connection) =>
        val modules = connection.modules()
        val app = named(modules, "app")
        assert(connection.compile().succeeded)
        val environment = connection.runEnvironment(app.spec.stableId)
        val caches = Files.createTempDirectory("debug-caches")

        val adapter = DebugAdapter.start("demo.app.Main",
          DebugAdapter.Launch(TestRun.mainCommand(TestRun.MainSelection(app.spec.stableId, "demo.app.Main"), environment)),
          app, modules, environment, connection.librarySources, caches.resolve("tools"), caches.resolve("sources"))
        try Using.resource(new scalaanalyzer.DapClient(adapter.uri)) { client =>
          val launched = client.start()
          // The line of `return "Hello, " + name;` in `Greeter.greet`.
          val greeter = copy.resolve("core/src/main/java/demo/core/Greeter.java")
          client.breakAt(greeter, 5)
          client.run(launched)

          val stop = client.nextStop()
          val top = client.topFrame(stop)
          assertEquals(Path.of(top.getSource.getPath).toRealPath(), greeter)
          assert(client.evaluate(top, "name").contains("world"))
          client.resume(stop)
          client.awaitEnd()
          assert(client.output.contains("Hello, world"), client.output)
        } finally {
          adapter.close()
          delete(caches)
        }
      }
    }

    test(s"${build.label} reload runs the install step again, so an added dependency appears in the modules") {
      withConnection(build) { (copy, connection) =>
        assert(!jarNames(named(connection.modules(), "app")).exists(_.startsWith("guava")))

        val buildFile = copy.resolve(build.appBuildFile)
        Files.writeString(buildFile, build.addGuava(Files.readString(buildFile)))
        connection.reload()

        assert(jarNames(named(connection.modules(), "app")).exists(_.startsWith("guava")))
      }
    }
  }
}
