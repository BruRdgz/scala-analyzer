package scalaanalyzer.build

import com.intellij.openapi.progress.ProgressManager
import org.apache.ivy.Ivy
import org.apache.ivy.core.module.descriptor.{DefaultDependencyDescriptor, DefaultModuleDescriptor}
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.resolver.IBiblioResolver
import org.apache.ivy.util.{AbstractMessageLogger, Message}

import scalaanalyzer.platform.HostLog

import java.io.File
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

/** Downloads the tools the host runs on demand (the formatter, the debugger's compilers) from Maven Central. */
private[build] object MavenDownload {
  /** An artifact to resolve, with its transitive dependencies. */
  final case class Coordinates(group: String, artifact: String, revision: String)

  /** A dependency that Maven Central, or the cache, could not provide. */
  final class Unresolved(message: String) extends RuntimeException(message)

  /**
   * Resolves artifacts and their transitive dependencies from Maven Central, using the cache before downloading.
   *
   * @param cache directory that keeps the downloaded files
   * @param tool what the artifacts are for, as the log and the failure message name it
   * @param dependencies the artifacts to resolve
   * @return the files of the artifacts and of everything they depend on
   * @throws Unresolved when a dependency cannot be found or downloaded
   */
  def resolve(cache: Path, tool: String, dependencies: Seq[Coordinates]): Seq[File] = {
    Files.createDirectories(cache)
    val settings = new IvySettings
    settings.setDefaultCache(cache.toFile)
    val central = new IBiblioResolver
    central.setName("central")
    central.setM2compatible(true)
    central.setUsepoms(true)
    central.setRoot("https://repo.maven.apache.org/maven2/")
    settings.addResolver(central)
    settings.setDefaultResolver("central")

    val ivy = new Ivy
    var cacheOnly = true
    ivy.getLoggerEngine.pushLogger(new AbstractMessageLogger {
      override def log(message: String, level: Int): Unit = {
        ProgressManager.checkCanceled()
        // Messages of the cache-only attempt stay at debug level; warnings surface once downloading has started.
        if (!cacheOnly && level <= Message.MSG_WARN) HostLog.warn(message) else HostLog.debug(message)
      }
      override def rawlog(message: String, level: Int): Unit = log(message, level)
      override protected def doProgress(): Unit = ProgressManager.checkCanceled()
      override protected def doEndProgress(message: String): Unit = ()
    })

    try {
      ivy.setSettings(settings)
      ivy.bind()
      ivy.pushContext()
      try {
        // Ivy keeps its resolution report under the module's revision: the dependencies tell resolutions apart.
        val revision = dependencies.hashCode.toHexString
        val module = DefaultModuleDescriptor.newDefaultInstance(ModuleRevisionId.newInstance("scala-analyzer", tool, revision))
        dependencies.foreach { dependency =>
          val id = ModuleRevisionId.newInstance(dependency.group, dependency.artifact, dependency.revision)
          val descriptor = new DefaultDependencyDescriptor(module, id, false, false, true)
          descriptor.addDependencyConfiguration("default", "default")
          module.addDependency(descriptor)
        }
        val options = new ResolveOptions().setConfs(Array("default")).setTransitive(true).setCheckIfChanged(false)
        val cached = ivy.resolve(module, options.setUseCacheOnly(true))
        val resolved = if (!cached.hasError) cached else {
          cacheOnly = false
          HostLog.info(s"downloading $tool dependencies to $cache")
          ivy.resolve(module, options.setUseCacheOnly(false))
        }
        if (resolved.hasError)
          throw new Unresolved(resolved.getAllProblemMessages.asScala.mkString(s"Could not resolve $tool: ", "; ", ""))
        resolved.getAllArtifactsReports.toSeq.map(_.getLocalFile)
      } finally ivy.popContext()
    } finally ivy.getLoggerEngine.popLogger()
  }
}
