package scalaanalyzer.build

import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import org.scalafmt.dynamic.{ScalafmtDynamic, ScalafmtDynamicError, ScalafmtVersion}
import org.scalafmt.interfaces.{RepositoryPackage, RepositoryPackageDownloader, RepositoryPackageDownloaderFactory,
  RepositoryProperties, ScalafmtReporter}

import scalaanalyzer.platform.HostLog

import java.io.{File, OutputStream, OutputStreamWriter, PrintWriter, Writer}
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Scalafmt configuration lookup and failures shared by project sessions. */
object ScalafmtFormatting {
  /**
   * A formatting or configuration failure surfaced to the caller.
   *
   * @param message explanation suitable for the formatting response
   * @param cause underlying failure, or null when absent
   */
  final class Failed(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

  /**
   * Finds the nearest scalafmt configuration within the workspace.
   *
   * Sources outside the workspace use its root configuration. The search never ascends above the workspace root.
   *
   * @param root workspace directory
   * @param file source path whose parent directories should be searched
   * @return the nearest configuration, or none when the workspace contains no applicable configuration
   */
  def configFor(root: Path, file: Path): Option[Path] = {
    val workspace = root.toAbsolutePath.normalize()
    var directory = file.toAbsolutePath.normalize().getParent

    while (directory != null && directory.startsWith(workspace)) {
      val config = directory.resolve(".scalafmt.conf")
      if (Files.isRegularFile(config)) return Some(config)
      directory = directory.getParent
    }

    Option(workspace.resolve(".scalafmt.conf")).filter(Files.isRegularFile(_))
  }
}

/**
 * Formats Scala sources using the version declared by their scalafmt configuration.
 *
 * Runtime artifacts are resolved from Maven Central and cached on disk. Calls to format and close are serialized;
 * the session owns this instance and clears its formatter resources when closing.
 *
 * @param cache directory for downloaded formatter dependencies
 */
final class ScalafmtFormatting(cache: Path) extends AutoCloseable {
  import ScalafmtFormatting.Failed

  /** Turns scalafmt errors into [[Failed]] and sends its informational reports to the host log. */
  private val reporter = new ScalafmtReporter {
    override def error(file: Path, message: String): Unit = throw new Failed(s"$file: $message")
    override def error(file: Path, cause: Throwable): Unit = throw new Failed(s"$file: ${cause.getMessage}", cause)
    override def missingVersion(file: Path, defaultVersion: String): Unit =
      throw new Failed(s"$file must declare the scalafmt version")
    override def excluded(file: Path): Unit = HostLog.debug(s"scalafmt excludes $file")
    override def parsedConfig(file: Path, version: String): Unit = HostLog.debug(s"scalafmt $version: $file")
    // The host's downloader reports through HostLog rather than the dynamic library's console progress writers.
    override def downloadWriter(): PrintWriter = new PrintWriter(Writer.nullWriter())
    override def downloadOutputStreamWriter(): OutputStreamWriter =
      new OutputStreamWriter(OutputStream.nullOutputStream())
  }

  /** Resolves formatter artifacts through `download` instead of the dynamic library's own downloader. */
  private val downloader = new RepositoryPackageDownloaderFactory {
    override def create(reporter: ScalafmtReporter, properties: RepositoryProperties): RepositoryPackageDownloader =
      (_, version, _, packages) => download(version, packages.asScala.toSeq)
  }

  private val formatter = new ScalafmtDynamic(downloader).withReporter(reporter).withRespectVersion(true)
    .withRespectProjectFilters(true)

  /**
   * Formats a document snapshot with the configured scalafmt version and project filters.
   *
   * Cancellation is checked before and after formatting. Configuration, download and formatter failures
   * are reported as [[ScalafmtFormatting.Failed]]; cancellation propagates unchanged.
   *
   * @param config scalafmt configuration file declaring the formatter version
   * @param file source path used for dialect selection and project filters
   * @param text current document contents
   * @return formatted text, or the original text when excluded by project filters
   */
  def format(config: Path, file: Path, text: String): String = synchronized {
    ProgressManager.checkCanceled()
    try {
      val result = formatter.createSession(config).formatOrError(file, text)
      if (result.exception != null) throw new Failed(s"scalafmt: ${result.exception.getMessage}", result.exception)
      if (result.value == null) throw new Failed("scalafmt returned neither formatted text nor an error")
      ProgressManager.checkCanceled()
      result.value
    } catch {
      case e: ProcessCanceledException => throw e
      case e: Failed => throw e
      case e @ (NonFatal(_) | _: ScalafmtDynamicError) => throw new Failed(s"scalafmt: ${e.getMessage}", e)
    }
  }

  /** Resolves the formatter's runtime artifacts from Maven Central, using the state cache before downloading. */
  private def download(version: String, packages: Seq[RepositoryPackage]): java.lang.Iterable[File] = {
    // As in the plugin's ScalafmtDynamicDownloader: old scalafmt breaks with Scala 2.13.12 and newer.
    val legacy = ScalafmtVersion.parse(version).exists(_ < ScalafmtVersion(3, 8, 1))

    val coordinates = packages.map { dependency =>
      val reflect = dependency.group == "org.scala-lang" && dependency.artifact == "scala-reflect" &&
        dependency.version.startsWith("2.13.")

      MavenDownload.Coordinates(dependency.group, dependency.artifact, if (legacy && reflect) "2.13.11" else dependency.version)
    }

    try MavenDownload.resolve(cache, "scalafmt", coordinates).asJava
    catch { case unresolved: MavenDownload.Unresolved => throw new Failed(unresolved.getMessage) }
  }

  /**
   * Clears formatter sessions and class-loader caches.
   *
   * Waits for an active `format` call to finish, because both synchronize on this instance.
   */
  override def close(): Unit = synchronized(formatter.clear())
}
