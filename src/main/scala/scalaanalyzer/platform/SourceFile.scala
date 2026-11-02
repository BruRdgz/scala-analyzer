package scalaanalyzer.platform

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.testFramework.LightVirtualFile

import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Represents a project source with a supplied path and in-memory text.
 *
 * The path is retained as supplied, without canonicalization or filesystem lookup. Construction uses the supplied
 * text; subsequent edits go through the document via [[scalaanalyzer.ScalaPsiHost.edit]]. Neither operation saves
 * the content to disk. The initial charset is UTF-8; per-file IDE encoding settings are not imported.
 *
 * @param path source-file path retained by the host
 * @param fileType file type used to select the parser and participate in language substitution
 * @param text initial content from disk or an editor buffer, supplied by the caller
 */
final class SourceFile(val path: Path, fileType: FileType, text: String)
  extends LightVirtualFile(path.getFileName.toString, fileType, text) {
  setCharset(StandardCharsets.UTF_8)

  /**
   * Exposes the supplied source path to the virtual-file API.
   *
   * @return `String` representation of `path`, without normalization or canonicalization
   */
  override def getPath: String = path.toString

  /**
   * Leaves language selection to file-type and module-based substitution.
   *
   * The absent override is intentional: the host uses module settings to select Scala 3 where appropriate.
   *
   * @return `null`, indicating no explicitly assigned `Language`
   */
  override def getLanguage: Language = null
}
