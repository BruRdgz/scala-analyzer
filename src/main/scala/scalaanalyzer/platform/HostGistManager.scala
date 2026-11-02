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

/** Keeps lazy file gists in memory, with PSI/VFS dependencies and explicit invalidation, without IDE persistence. */
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

  /** Caches committed PSI contents, independently of whether the document has been saved. */
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

  /** Caches VFS contents per project (including the project-independent null context), without reading documents. */
  override def newVirtualFileGist[Data](id: String, version: Int, externalizer: DataExternalizer[Data],
                                      calculator: VirtualFileGist.GistCalculator[Data]): VirtualFileGist[Data] = {
    register(id)
    val key = Key.create[ConcurrentHashMap[Option[Project], CachedValueImpl[Data]]](s"HostVirtualGist $id:$version")
    new VirtualFileGist[Data] {
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

  /** Invalidates one file's gists immediately and schedules dependent PSI-cache invalidation. */
  override def invalidateData(file: VirtualFile): Unit = {
    invalidations(file).incModificationCount()
    invalidateDependentCaches()
  }
}
