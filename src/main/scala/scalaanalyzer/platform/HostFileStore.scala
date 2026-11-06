package scalaanalyzer.platform

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{FileTypeFileViewProviders, FileViewProvider, LanguageFileViewProviders, LanguageSubstitutors, PsiDirectory, PsiManager, SingleRootFileViewProvider}
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.file.PsiDirectoryImpl
import com.intellij.psi.impl.file.impl.FileManagerEx
import com.intellij.testFramework.LightVirtualFile

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Caches project/library providers in the project and temporary providers on their collectable light files.
 *
 * A temporary file is a `LightVirtualFile` that is not a `SourceFile`; its provider is stored in the file's user data
 * so it is collected with the file. Every other file is cached in the store, and only library files and
 * `SourceFile`s are accepted for caching.
 *
 * @param project project used to substitute the language of a source file
 * @param psiManager supplies the project's `PsiManager`; it is called only when a provider is created, after the
 *                   manager has been registered, which breaks the construction cycle with it
 */
private[scalaanalyzer] final class HostFileStore(project: Project, psiManager: () => PsiManager) {
  private val viewProviders = new ConcurrentHashMap[VirtualFile, FileViewProvider]()
  private val temporaryViewProvider = Key.create[FileViewProvider]("scala-analyzer.temporaryViewProvider")
  private val directories = new ConcurrentHashMap[VirtualFile, PsiDirectory]()

  /** Light files that need their provider attached to the file instead of retained in the project cache. */
  private def isTemporary(file: VirtualFile): Boolean =
    file.isInstanceOf[LightVirtualFile] && !file.isInstanceOf[SourceFile]

  /** Reads the provider from the matching cache used for physical or temporary files. */
  private def cachedViewProvider(f: VirtualFile): FileViewProvider =
    if (isTemporary(f)) f.getUserData(temporaryViewProvider) else viewProviders.get(f)

  /** Returns a cached provider or creates one according to the host's source, library and temporary-file policies. */
  private def viewProvider(f: VirtualFile): FileViewProvider = Option(cachedViewProvider(f)).getOrElse {
    if (isTemporary(f)) newTemporaryViewProvider(f)
    else viewProviders.computeIfAbsent(f, {
      // Physical (event system enabled), as the IDE's library files: a non-physical file loads its syntax tree up
      // front, while a physical one stays stub-based until something navigates into it.
      case lib: LibraryFile if FileTypeFileViewProviders.INSTANCE.forFileType(lib.getFileType) != null =>
        FileTypeFileViewProviders.INSTANCE
          .forFileType(lib.getFileType)
          .createFileViewProvider(lib, null, psiManager(), true)
      // Library sources, as project sources below (the plugin's substitutor reads Scala 3 from the source jar's name).
      case lib: LibraryFile if lib.isLibrarySource => sourceViewProvider(lib, physical = true)
      // Project sources are physical too: their syntax tree follows document commits (reparse) and can be dropped
      // when no one holds it. The language's view provider when it has one (Scala), the platform's default otherwise.
      case source: SourceFile => sourceViewProvider(source, physical = true)
      case other => CallLog.unsupported(s"FileManager.findViewProvider for a file the host does not own: $other")
    })
  }

  /** Creates and attaches a nonphysical provider for a temporary file first opened by a plugin query. */
  private def newTemporaryViewProvider(f: VirtualFile): FileViewProvider = {
    val provider = sourceViewProvider(f, physical = false)

    f.putUserData(temporaryViewProvider, provider)
    provider
  }

  /** Uses the substituted language when available, otherwise the platform's default provider. */
  private def sourceViewProvider(source: VirtualFile, physical: Boolean): FileViewProvider = source.getFileType match {
    case fileType: LanguageFileType =>
      val language = LanguageSubstitutors.getInstance.substituteLanguage(fileType.getLanguage, source, project)

      Option(LanguageFileViewProviders.INSTANCE.forLanguage(language))
        .map(_.createFileViewProvider(source, language, psiManager(), physical))
        .getOrElse(new SingleRootFileViewProvider(psiManager(), source, physical))
    case _ => new SingleRootFileViewProvider(psiManager(), source, physical)
  }

  /**
   * Answers file-manager lookups using the host's physical and temporary provider policies.
   *
   * Lookups that only read the cache (`findCachedViewProvider`, `getCachedPsiFile`) never create a provider;
   * `findViewProvider`, `findFile` and `findDirectory` create and cache one on first use. `setViewProvider` stores
   * the given provider, or drops the cached one when it is `null`. Calls outside this table are rejected by
   * `tableProxy` after being recorded by `CallLog`.
   */
  private[scalaanalyzer] val adapter: FileManagerEx = tableProxy(classOf[FileManagerEx], "FileManager") {
    case ("findCachedViewProvider", Array(f: VirtualFile)) => cachedViewProvider(f)
    // One view provider per file (no code-insight contexts).
    case ("findCachedViewProviders", Array(f: VirtualFile)) => Option(cachedViewProvider(f)).toList.asJava
    case ("getCachedPsiFile", Array(f: VirtualFile, _*)) =>
      Option(cachedViewProvider(f)).map(vp => vp.getPsi(vp.getBaseLanguage)).orNull
    case ("setViewProvider", Array(f: VirtualFile, vp)) =>
      // The platform's `FileManagerEx.setViewProvider` takes a `FileViewProvider` or null; the table passes it untyped.
      if (isTemporary(f)) f.putUserData(temporaryViewProvider, vp.asInstanceOf[FileViewProvider])
      else if (vp == null) viewProviders.remove(f)
      else viewProviders.put(f, vp.asInstanceOf[FileViewProvider])
      null
    case ("forceReload", Array(f: VirtualFile)) if isTemporary(f) &&
        Option(cachedViewProvider(f)).forall(vp => !vp.supportsSendingPsiEvents()) =>
      // FileManagerImpl drops nonphysical providers on reload; it never reloads their backing file from disk.
      f.putUserData(temporaryViewProvider, null)
      null
    case ("findViewProvider", Array(f: VirtualFile)) => viewProvider(f)
    case ("findFile", Array(f: VirtualFile)) =>
      if (f.isDirectory) null
      else {
        val provider = viewProvider(f)
        provider.getPsi(provider.getBaseLanguage)
      }
    case ("findDirectory", Array(f: VirtualFile)) =>
      if (!f.isDirectory) null
      // The host's `PsiManager` is a `HostPsiManager`, which extends `PsiManagerEx`.
      else directories.computeIfAbsent(f, d => new PsiDirectoryImpl(psiManager().asInstanceOf[PsiManagerEx], d))
  }

  /**
   * Tells whether a virtual file belongs to a mounted library or JDK root.
   *
   * @param file virtual file to classify
   * @return `true` if the file lives in the host's library file system, whether it is bytecode or attached source
   */
  def isLibrary(file: VirtualFile): Boolean = file.getFileSystem.isInstanceOf[LibraryFileSystem]

  /**
   * Tells whether a library virtual file is under an attached source root.
   *
   * @param file virtual file to classify
   * @return `true` if the file is a `LibraryFile` under a source root; `false` otherwise
   */
  def isLibrarySource(file: VirtualFile): Boolean = file match {
    case library: LibraryFile => library.isLibrarySource
    case _ => false
  }

  /**
   * Tells whether a library virtual file represents bytecode rather than attached source.
   *
   * @param file virtual file to classify
   * @return `true` if the file is in a library root but not under an attached source root
   */
  def isLibraryClass(file: VirtualFile): Boolean = isLibrary(file) && !isLibrarySource(file)
}
