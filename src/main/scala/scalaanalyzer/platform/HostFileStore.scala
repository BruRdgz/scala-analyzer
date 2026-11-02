package scalaanalyzer.platform

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{FileViewProvider, LanguageFileViewProviders, PsiManager}
import com.intellij.psi.impl.file.impl.FileManagerEx

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/** Caches project/library providers in the project and temporary providers on their collectable light files. */
private[scalaanalyzer] final class HostFileStore(project: Project, psiManager: () => PsiManager) {
  private val viewProviders = new ConcurrentHashMap[VirtualFile, FileViewProvider]()
  private val temporaryViewProvider = Key.create[FileViewProvider]("scala-analyzer.temporaryViewProvider")
  private val directories = new ConcurrentHashMap[VirtualFile, com.intellij.psi.PsiDirectory]()

  /** Light files that need their provider attached to the file instead of retained in the project cache. */
  private def isTemporary(file: VirtualFile): Boolean =
    file.isInstanceOf[com.intellij.testFramework.LightVirtualFile] && !file.isInstanceOf[SourceFile]

  /** Reads the provider from the matching cache used for physical or temporary files. */
  private def cachedViewProvider(f: VirtualFile): FileViewProvider =
    if (isTemporary(f)) f.getUserData(temporaryViewProvider) else viewProviders.get(f)

  /** Returns a cached provider or creates one according to the host's source, library and temporary-file policies. */
  private def viewProvider(f: VirtualFile): FileViewProvider = Option(cachedViewProvider(f)).getOrElse {
    if (isTemporary(f)) newTemporaryViewProvider(f)
    else viewProviders.computeIfAbsent(f, {
      // Physical (event system enabled), as the IDE's library files: a non-physical file loads its syntax tree up
      // front, while a physical one stays stub-based until something navigates into it.
      case lib: LibraryFile if com.intellij.psi.FileTypeFileViewProviders.INSTANCE.forFileType(lib.getFileType) != null =>
        com.intellij.psi.FileTypeFileViewProviders.INSTANCE.forFileType(lib.getFileType).createFileViewProvider(lib, null, psiManager(), true)
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
    case fileType: com.intellij.openapi.fileTypes.LanguageFileType =>
      // The supplier breaks the construction cycle with HostPsiManager; providers are created only after registration.
      val language = com.intellij.psi.LanguageSubstitutors.getInstance.substituteLanguage(
        fileType.getLanguage, source, project)
      Option(LanguageFileViewProviders.INSTANCE.forLanguage(language))
        .map(_.createFileViewProvider(source, language, psiManager(), physical))
        .getOrElse(new com.intellij.psi.SingleRootFileViewProvider(psiManager(), source, physical))
    case _ => new com.intellij.psi.SingleRootFileViewProvider(psiManager(), source, physical)
  }

  /** Answers file-manager lookups using the host's physical and temporary provider policies. */
  private[scalaanalyzer] val adapter: FileManagerEx = tableProxy(classOf[FileManagerEx], "FileManager") {
    case ("findCachedViewProvider", Array(f: VirtualFile)) => cachedViewProvider(f)
    // One view provider per file (no code-insight contexts).
    case ("findCachedViewProviders", Array(f: VirtualFile)) => Option(cachedViewProvider(f)).toList.asJava
    case ("getCachedPsiFile", Array(f: VirtualFile, _*)) =>
      Option(cachedViewProvider(f)).map(vp => vp.getPsi(vp.getBaseLanguage)).orNull
    case ("setViewProvider", Array(f: VirtualFile, vp)) =>
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
      else directories.computeIfAbsent(f, d => new com.intellij.psi.impl.file.PsiDirectoryImpl(psiManager().asInstanceOf[com.intellij.psi.impl.PsiManagerEx], d))
  }

  /** Whether the virtual file belongs to a mounted library or JDK root. */
  def isLibrary(file: VirtualFile): Boolean = file.getFileSystem.isInstanceOf[LibraryFileSystem]

  /** Whether a library virtual file is under an attached source root. */
  def isLibrarySource(file: VirtualFile): Boolean = file match {
    case library: LibraryFile => library.isLibrarySource
    case _ => false
  }

  /** Whether a library virtual file represents bytecode rather than attached source. */
  def isLibraryClass(file: VirtualFile): Boolean = isLibrary(file) && !isLibrarySource(file)
}
