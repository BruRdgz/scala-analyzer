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
 * Callers replace contributions after edits and remove them when a source file leaves the project.
 *
 * @param project project used by the plugin's import-ordering indexer
 */
final class ImportIndex(project: Project) {
  private val indexer = new ImportOrderingIndexer().getIndexer
  private val importsByFile = new ConcurrentHashMap[VirtualFile, Set[String]]()

  /**
   * Replaces a file's import qualifiers with those in its current PSI text.
   *
   * @param file source file to index under the caller's PSI access lock
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
   * @param file source file whose contribution should be removed
   */
  def removeFile(file: PsiFile): Unit = importsByFile.remove(file.getViewProvider.getVirtualFile)

  /**
   * Looks up a qualifier using the platform's Void-valued index contract.
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
