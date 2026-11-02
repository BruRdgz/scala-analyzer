package scalaanalyzer

import scalaanalyzer.build.{ScalafmtFormatting}
import scalaanalyzer.platform.{HostLog}

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*

class ScalafmtFormattingTest extends munit.FunSuite {
  // A cold run resolves the formatter and its transitive dependencies from Maven Central.
  override val munitTimeout: Duration = 10.minutes
  private val root = Path.of("fixtures/scalafmt").toAbsolutePath.normalize()

  test("scalafmt honors its declared version, indentation, exclusions and cached dependencies") {
    TestHost.host
    val formatter = new ScalafmtFormatting(Path.of(sys.props("scalaanalyzer.stateDir")).resolve("scalafmt"))
    val config = root.resolve(".scalafmt.conf")
    val original = "object Formatted {\nval x=1+2\n}\n"
    try {
      val expected = "object Formatted {\n    val x = 1 + 2\n}\n"
      assertEquals(formatter.format(config, root.resolve("Formatted.scala"), original), expected)
      assertEquals(formatter.format(config, root.resolve("Excluded.scala"), original), original)
      assertEquals(formatter.format(config, root.resolve("Formatted.scala"), expected), expected)
    } finally formatter.close()

    val cachedFormatter = new ScalafmtFormatting(Path.of(sys.props("scalaanalyzer.stateDir")).resolve("scalafmt"))
    val downloads = new java.util.concurrent.atomic.AtomicInteger()
    val log = HostLog.addSink { entry =>
      if (entry.message.startsWith("downloading scalafmt dependencies")) {
        downloads.incrementAndGet()
        ()
      }
    }
    try {
      assertEquals(cachedFormatter.format(config, root.resolve("Cached.scala"), "object Cached{}"), "object Cached {}\n")
      assertEquals(downloads.get(), 0)
    } finally {
      log.close()
      cachedFormatter.close()
    }
  }

  test("the nearest workspace config wins and a malformed config fails instead of falling back") {
    TestHost.host
    val temporary = Files.createTempDirectory("scalafmt-config-")
    val nested = Files.createDirectories(temporary.resolve("module/src"))
    val rootConfig = temporary.resolve(".scalafmt.conf")
    val moduleConfig = temporary.resolve("module/.scalafmt.conf")
    Files.writeString(rootConfig, "version = \"3.9.8\"\nrunner.dialect = scala213\n")
    Files.writeString(moduleConfig, "version = [\n")
    val formatter = new ScalafmtFormatting(Path.of(sys.props("scalaanalyzer.stateDir")).resolve("scalafmt"))
    try {
      assertEquals(ScalafmtFormatting.configFor(temporary, nested.resolve("Code.scala")), Some(moduleConfig))
      intercept[ScalafmtFormatting.Failed](formatter.format(moduleConfig, nested.resolve("Code.scala"), "object C"))
      Files.writeString(moduleConfig, "runner.dialect = scala213\n")
      val missing = intercept[ScalafmtFormatting.Failed](
        formatter.format(moduleConfig, nested.resolve("Code.scala"), "object C"))
      assert(missing.getMessage.contains("must declare the scalafmt version"))
      Files.delete(moduleConfig)
      assertEquals(ScalafmtFormatting.configFor(temporary, nested.resolve("Code.scala")), Some(rootConfig))
    } finally {
      formatter.close()
      Files.deleteIfExists(moduleConfig)
      Files.delete(rootConfig)
      Files.delete(nested)
      Files.delete(temporary.resolve("module"))
      Files.delete(temporary)
    }
  }
}
