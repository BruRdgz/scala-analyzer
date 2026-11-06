package scalaanalyzer.platform

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Key, ModificationTracker, SimpleModificationTracker}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager}
import com.intellij.util.{CachedValueImpl, NullableFunction}
import com.intellij.util.gist.{GistManager, PsiFileGist, VirtualFileGist}
import com.intellij.util.io.DataExternalizer

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Keeps lazy file gists in memory, with PSI/VFS dependencies and explicit invalidation, without IDE persistence.
 *
 * Gist data is computed on lookup under `HostLock.query` and cached until the file, a global invalidation or a
 * per-file invalidation changes. No gist data is written to disk, so the externalizers and versions the platform
 * supplies never select persisted data.
 *
 * @param invalidateDependentCaches called after every invalidation; the host schedules its PSI-cache invalidation here
 */
private[scalaanalyzer] final class HostGistManager(invalidateDependentCaches: () => Unit) extends GistManager {
  private val registered = ConcurrentHashMap.newKeySet[String]()
  private val allInvalidations = new SimpleModificationTracker
  private val fileInvalidations = Key.create[SimpleModificationTracker]("HostGist.invalidations")

  /** Registers each ID once; versions cannot reuse persisted data because the host stores none. */
  private def register(id: String): Unit =
    require(registered.add(id), s"Gist '$id' is already registered")

  /** Keeps each file's invalidation counter with its own lifetime. */
  private def invalidations(file: VirtualFile): SimpleModificationTracker = {
    val existing = file.getUserData(fileInvalidations)
    if (existing != null) existing else file.putUserDataIfAbsent(fileInvalidations, new SimpleModificationTracker)
  }

  /**
   * Creates a gist that caches committed PSI contents, independently of whether the document has been saved.
   *
   * Each file's data is cached with the file as a dependency, alongside the global and per-file invalidation
   * counters.
   *
   * @param id gist identifier, registered once per manager
   * @param version gist version, used only to name the cache key
   * @param externalizer ignored, because the host persists no gist data
   * @param calculator computes the data of a file on lookup
   * @return gist whose data is computed on lookup under `HostLock.query`
   * @throws IllegalArgumentException if `id` is already registered as a gist of either kind
   */
  override def newPsiFileGist[Data](id: String, version: Int, externalizer: DataExternalizer[Data],
                                    calculator: NullableFunction[? >: PsiFile, ? <: Data]): PsiFileGist[Data] = {
    register(id)

    val key = Key.create[CachedValue[Data]](s"HostPsiGist $id:$version")

    file => HostLock.query {
      ProgressManager.checkCanceled()
      CachedValuesManager.getManager(file.getProject).getCachedValue(file, key, () =>
        CachedValueProvider.Result.create(calculator.fun(file), file, allInvalidations,
          invalidations(file.getViewProvider.getVirtualFile)), false)
    }
  }

  /**
   * Creates a gist that caches VFS contents per project (including the project-independent null context), without
   * reading documents.
   *
   * Each cached value depends on the file, its modification stamp and the global and per-file invalidation counters.
   *
   * @param id gist identifier, registered once per manager
   * @param version gist version, used only to name the cache key
   * @param externalizer ignored, because the host persists no gist data
   * @param calculator computes the data of a file and project on lookup
   * @return gist whose data is computed on lookup under `HostLock.query`
   * @throws IllegalArgumentException if `id` is already registered as a gist of either kind
   */
  override def newVirtualFileGist[Data](id: String, version: Int, externalizer: DataExternalizer[Data],
                                        calculator: VirtualFileGist.GistCalculator[Data]): VirtualFileGist[Data] = {
    register(id)

    val key = Key.create[ConcurrentHashMap[Option[Project], CachedValueImpl[Data]]](s"HostVirtualGist $id:$version")

    new VirtualFileGist[Data] {
      /**
       * Returns the file's data for a project, computing and caching it when absent or stale.
       *
       * @param project project context, or `null` for the project-independent context
       * @param file file whose data is requested
       * @return the cached or newly calculated data
       */
      override def getFileData(project: Project, file: VirtualFile): Data = HostLock.query {
        ProgressManager.checkCanceled()

        val existing = file.getUserData(key)
        val values = if (existing != null) existing
                     else file.putUserDataIfAbsent(key, new ConcurrentHashMap[Option[Project], CachedValueImpl[Data]])

        values.computeIfAbsent(Option(project), _ => {
          // LightVirtualFile changes its content stamp without changing its filesystem timestamp.
          val content: ModificationTracker = () => file.getModificationStamp
          new CachedValueImpl[Data](() => CachedValueProvider.Result.create(
            calculator.calcData(project, file), file, content, allInvalidations, invalidations(file)))
        }).getValue
      }

      /**
       * Looks up the file's data without calculating it.
       *
       * @param project project context, or `null` for the project-independent context
       * @param file file whose data is requested
       * @return supplier of the cached data, or `null` if none is cached or it is out of date
       */
      override def getUpToDateOrNull(project: Project, file: VirtualFile): Supplier[Data] = HostLock.query {
        ProgressManager.checkCanceled()

        val cached = for {
          values <- Option(file.getUserData(key))
          value <- Option(values.get(Option(project)))
          data <- Option(value.getUpToDateOrNull())
        } yield data

        cached.fold[Supplier[Data]](null)(data => () => data.getValue)
      }
    }
  }

  /** Invalidates all gists immediately and schedules the host's dependent PSI-cache invalidation. */
  override def invalidateData(): Unit = {
    allInvalidations.incModificationCount()
    invalidateDependentCaches()
  }

  /**
   * Invalidates one file's gists immediately and schedules dependent PSI-cache invalidation.
   *
   * @param file file whose gist data, of every gist, becomes stale
   */
  override def invalidateData(file: VirtualFile): Unit = {
    invalidations(file).incModificationCount()
    invalidateDependentCaches()
  }
}
