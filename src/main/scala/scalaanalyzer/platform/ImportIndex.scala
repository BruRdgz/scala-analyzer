package scalaanalyzer.platform

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileContentImpl
import org.jetbrains.plugins.scala.autoImport.ImportOrderingIndexer

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Indexes import qualifiers for the plugin's ordering of auto-import candidates.
 *
 * Callers replace contributions after edits and remove them when a source file leaves the project, holding the
 * host write lock.
 *
 * @param project project used by the plugin's import-ordering indexer
 */
final class ImportIndex(project: Project) {
  private val indexer = new ImportOrderingIndexer().getIndexer
  /** Each indexed file's import qualifiers, replaced as a whole whenever the file is indexed again. */
  private val importsByFile = new ConcurrentHashMap[VirtualFile, Set[String]]()

  /**
   * Replaces a file's import qualifiers with those in its current PSI text.
   *
   * The caller holds the host write lock. A file indexed before keeps no qualifier that its current text lacks.
   *
   * @param file source file whose current text and virtual-file identity are indexed
   */
  def indexFile(file: PsiFile): Unit = {
    val virtualFile = file.getViewProvider.getVirtualFile
    val content = FileContentImpl.createByText(virtualFile, file.getText, project)
    val qualifiers = indexer.map(content).keySet.asScala.toSet

    importsByFile.put(virtualFile, qualifiers)
  }

  /**
   * Removes a source file's indexed qualifiers.
   *
   * The caller holds the host write lock. Removing a file that is not indexed has no effect.
   *
   * @param file source file with the same virtual-file identity used when indexing
   */
  def removeFile(file: PsiFile): Unit = importsByFile.remove(file.getViewProvider.getVirtualFile)

  /**
   * Looks up a qualifier using the platform's Void-valued index contract.
   *
   * A match is any indexed file inside `scope` whose imports include `qualifier`.
   *
   * @param qualifier import qualifier to find
   * @param scope files eligible to contribute a match
   * @return a singleton containing null when a match exists, otherwise an empty list
   */
  def values(qualifier: String, scope: GlobalSearchScope): java.util.List[Void] =
    if (importsByFile.asScala.exists((file, imports) => scope.contains(file) && imports(qualifier)))
      java.util.Collections.singletonList(null)
    else java.util.List.of()
}
