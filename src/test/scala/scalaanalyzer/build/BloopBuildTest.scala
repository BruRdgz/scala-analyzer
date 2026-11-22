package scalaanalyzer.build

import scalaanalyzer.workspace.BuildDefinition

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/** The parts of the Maven and Gradle import that decide without running a tool: which build a root holds, which launcher runs it, which files define it. */
class BloopBuildTest extends munit.FunSuite {
  private def root(files: String*): Path = {
    val directory = Files.createTempDirectory("bloop-build-test")
    files.foreach(file => Files.createFile(directory.resolve(file)))
    directory
  }

  test("a root's build tool follows its files, and a root with both tools is ambiguous") {
    val cases = Seq(
      Seq.empty[String] -> None,
      Seq("pom.xml") -> Some(BloopBuild.Tool.Maven),
      Seq("settings.gradle") -> Some(BloopBuild.Tool.Gradle),
      Seq("settings.gradle.kts") -> Some(BloopBuild.Tool.Gradle),
      Seq("build.gradle") -> Some(BloopBuild.Tool.Gradle),
      Seq("build.gradle.kts") -> Some(BloopBuild.Tool.Gradle),
      Seq("build.sbt") -> None,
    )

    cases.foreach { (files, expected) =>
      assertEquals(BloopBuild.detect(root(files*)), expected, files.toString)
    }

    val ambiguous = intercept[IllegalArgumentException](BloopBuild.detect(root("pom.xml", "build.gradle.kts")))
    assert(ambiguous.getMessage.contains("Maven (pom.xml)") && ambiguous.getMessage.contains("Gradle (build.gradle.kts)"), ambiguous.getMessage)
  }

  test("a tool's own wrapper runs only when it is an executable file in the root") {
    def wrapperRoot(name: String, permissions: String) = {
      val directory = root()
      val wrapper = Files.createFile(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions)))
      directory -> wrapper
    }

    val (mavenRoot, mvnw) = wrapperRoot("mvnw", "rwxr-xr-x")
    val (gradleRoot, gradlew) = wrapperRoot("gradlew", "rwxr-xr-x")
    val (plainRoot, _) = wrapperRoot("mvnw", "rw-r--r--")

    assertEquals(BloopBuild.executable(BloopBuild.Tool.Maven, mavenRoot), mvnw.toAbsolutePath.toString)
    assertEquals(BloopBuild.executable(BloopBuild.Tool.Gradle, gradleRoot), gradlew.toAbsolutePath.toString)
    assertEquals(BloopBuild.executable(BloopBuild.Tool.Maven, plainRoot), "mvn")
    assertEquals(BloopBuild.executable(BloopBuild.Tool.Maven, gradleRoot), "mvn")
    assertEquals(BloopBuild.executable(BloopBuild.Tool.Gradle, root()), "gradle")
  }

  test("the install commands run Bloop's goal and init script without touching the build files") {
    val directory = root()

    assertEquals(
      BloopBuild.mavenCommand(directory),
      Seq("mvn", "-B", "ch.epfl.scala:bloop-maven-plugin:2.0.5:bloopInstall"))
    assertEquals(
      BloopBuild.gradleCommand(directory, Path.of("/init.gradle")),
      Seq("gradle", "--init-script", "/init.gradle", "bloopInstall"))
  }

  test("the build tools' JDK is inherited when the variable is unset, removed when empty, and the path otherwise") {
    import BloopBuild.BuildJdk
    val cases = Seq(
      Map.empty[String, String] -> BuildJdk.Inherit,
      Map("JAVA_HOME" -> "/jdk-25") -> BuildJdk.Inherit,
      Map(BloopBuild.BuildJavaHomeVariable -> "") -> BuildJdk.Unset,
      Map(BloopBuild.BuildJavaHomeVariable -> "/jdk-17", "JAVA_HOME" -> "/jdk-25") -> BuildJdk.Home("/jdk-17"),
    )

    cases.foreach { (environment, expected) =>
      assertEquals(BloopBuild.buildJdk(environment), expected, environment.toString)
    }
  }

  test("in a Maven or Gradle workspace the build files count at any depth, outside the tool directories") {
    val workspace = Path.of("/work")
    val cases = Seq(
      "/work/pom.xml" -> true,
      "/work/app/pom.xml" -> true,
      "/work/modules/aggregator/pom.xml" -> true,
      "/work/build.gradle" -> true,
      "/work/app/build.gradle.kts" -> true,
      "/work/settings.gradle.kts" -> true,
      "/work/gradle.properties" -> true,
      "/work/app/gradle.properties" -> true,
      "/work/gradle/libs.versions.toml" -> true,
      "/work/libs.versions.toml" -> false,
      "/work/app/src/main/java/App.java" -> false,
      "/elsewhere/pom.xml" -> false,
      "/work/build.sbt" -> true,
      "/work/app/build.sbt" -> false,
      "/work/build/tmp/x.gradle.kts" -> false,
      "/work/app/target/classes/pom.xml" -> false,
      "/work/.gradle/caches/x.gradle" -> false,
      "/work/build-logic/convention.gradle.kts" -> true,
      "/work/buildSrc/build.gradle.kts" -> true,
    )

    cases.foreach { (path, expected) =>
      assertEquals(BuildDefinition.isPart(workspace, mavenOrGradle = true, _ => false, Path.of(path)), expected, path)
    }
  }

  test("an sbt workspace takes no Maven or Gradle file as its build definition") {
    val workspace = Path.of("/work")
    val cases = Seq("/work/pom.xml" -> false, "/work/fixture/pom.xml" -> false, "/work/fixture/build.gradle" -> false,
      "/work/build.sbt" -> true)

    cases.foreach { (path, expected) =>
      assertEquals(BuildDefinition.isPart(workspace, mavenOrGradle = false, _ => false, Path.of(path)), expected, path)
    }
  }
}
