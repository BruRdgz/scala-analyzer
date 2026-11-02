package scalaanalyzer.platform

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.cache.impl.id.{IdIndexEntry, IdTableBuilding}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileContentImpl

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Describes required word keys and their accepted occurrence contexts.
 *
 * Each word's combined context mask must satisfy `accepts`. In `WordIndex.filesWithAll`, the first query must
 * supply a starting word; empty word lists in subsequent queries impose no additional conditions.
 *
 * @param words required index keys, including their case-sensitivity mode
 * @param accepts predicate over the combined `UsageSearchContext` mask recorded for each word in a file
 */
final case class WordQuery(words: Seq[IdIndexEntry], accepts: Int => Boolean)

/**
 * Indexes project-source word keys and occurrence contexts for reference searches.
 *
 * Uses the platform's file-type indexers and hashed `IdIndexEntry` keys. Results identify candidate files;
 * reference searches still inspect PSI to establish actual symbol usages.
 *
 * Callers protect queries with the host read lock and updates with its write lock. Updating the two maps is
 * not atomic merely because they are concurrent maps. Remove a file's old entries before indexing it again.
 *
 * @param project host project used to create file content for the platform indexers
 */
final class WordIndex(project: Project) {
  private val wordsByFile = new ConcurrentHashMap[VirtualFile, java.util.Map[IdIndexEntry, Integer]]()
  private val filesByWord = new ConcurrentHashMap[IdIndexEntry, java.util.Set[VirtualFile]]()

  /**
   * Adds a source's current word keys and occurrence masks to the index.
   *
   * The caller holds the host write lock. For a previously indexed file, call `removeFile` first so its old
   * reverse entries are discarded. A file type without a word indexer causes `IllegalStateException`.
   *
   * @param file source PSI whose current text and virtual-file identity are indexed
   */
  def indexFile(file: PsiFile): Unit = {
    val virtualFile = file.getViewProvider.getVirtualFile
    val fileType = virtualFile.getFileType
    val indexer = IdTableBuilding.getFileTypeIndexer(fileType)

    if (indexer == null) throw new IllegalStateException(s"no word indexer for $fileType")

    val occurrenceMasks = indexer.map(FileContentImpl.createByText(virtualFile, file.getText, project))

    wordsByFile.put(virtualFile, occurrenceMasks)
    for (word <- occurrenceMasks.keySet.asScala)
      filesByWord.computeIfAbsent(word, _ => ConcurrentHashMap.newKeySet[VirtualFile]()).add(virtualFile)
  }

  /**
   * Removes a source's entries from both lookup directions.
   *
   * The caller holds the host write lock. Removing a file that is not indexed has no effect; reverse entries
   * are removed when their last contributing file disappears.
   *
   * @param file source PSI with the same virtual-file identity used when indexing
   */
  def removeFile(file: PsiFile): Unit = {
    val virtualFile = file.getViewProvider.getVirtualFile
    val previousWords = wordsByFile.remove(virtualFile)

    if (previousWords != null)
      for (word <- previousWords.keySet.asScala) {
        val matchingFiles = filesByWord.get(word)

        matchingFiles.remove(virtualFile)
        if (matchingFiles.isEmpty) filesByWord.remove(word)
      }
  }

  /**
   * Looks up the occurrence contexts recorded for a word in a file.
   *
   * The mask combines contexts such as code, comments and strings; it does not enumerate individual occurrences.
   *
   * @param word index key to look up
   * @param file indexed virtual-file identity
   * @return `Option[Int]` containing the combined context mask, or `None` if the file or key is absent
   */
  def occurrences(word: IdIndexEntry, file: VirtualFile): Option[Int] =
    Option(wordsByFile.get(file))
      .flatMap(words => Option(words.get(word)))
      .map(_.intValue)

  /**
   * Finds candidate files for a word within a search scope.
   *
   * This lookup does not filter occurrence contexts. Use `filesWithAll` when context masks must be checked.
   *
   * @param word index key to look up
   * @param scope scope that every returned file must belong to
   * @return `Seq[VirtualFile]` of distinct candidate files, in unspecified order
   */
  def filesWith(word: IdIndexEntry, scope: GlobalSearchScope): Seq[VirtualFile] =
    Option(filesByWord.get(word)).fold(Seq.empty[VirtualFile]) { candidates =>
      candidates.iterator.asScala.filter(scope.contains).toVector
    }

  /**
   * Finds candidate files satisfying every required word and context predicate.
   *
   * The first word of the first query selects the initial candidates. An empty query list or an empty first
   * query matches nothing, even when later queries contain words. Subsequent empty queries add no restrictions.
   *
   * @param queries required word groups, each with its own occurrence-context predicate
   * @param scope scope that every returned file must belong to
   * @return `Seq[VirtualFile]` of distinct candidates satisfying all queries, in unspecified order
   */
  def filesWithAll(queries: Seq[WordQuery], scope: GlobalSearchScope): Seq[VirtualFile] = {
    val firstWord = queries.headOption.flatMap(_.words.headOption)

    firstWord match {
      case None => Seq.empty
      case Some(word) =>
        filesWith(word, scope).filter { file =>
          queries.forall { query =>
            query.words.forall { requiredWord =>
              occurrences(requiredWord, file).exists(query.accepts)
            }
          }
        }
    }
  }
}
