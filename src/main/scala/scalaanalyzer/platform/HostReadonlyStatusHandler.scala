package scalaanalyzer.platform

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.vfs.{ReadonlyStatusHandler, VirtualFile}

import scala.jdk.CollectionConverters.*

/**
 * Answers the platform's "may these files be edited?" question for a host that edits only copies.
 *
 * Refactorings ask before they change a file. Inside a preview session the file is the preview's copy, which the host
 * discards, so it may be edited; anywhere else it is a file of the user's project, which the host never writes.
 */
private[scalaanalyzer] final class HostReadonlyStatusHandler extends ReadonlyStatusHandler {
  private object Writable extends ReadonlyStatusHandler.OperationStatus {
    override def getReadonlyFiles: Array[VirtualFile] = Array.empty
    override def hasReadonlyFiles: Boolean = false
    override def getReadonlyFilesMessage: String = ""
  }

  private final class Refused(files: Array[VirtualFile]) extends ReadonlyStatusHandler.OperationStatus {
    override def getReadonlyFiles: Array[VirtualFile] = files
    override def hasReadonlyFiles: Boolean = true
    override def getReadonlyFilesMessage: String = "scala-analyzer edits only the copies it previews changes on"
  }

  override def ensureFilesWritable(
      files: java.util.Collection[? <: VirtualFile]): ReadonlyStatusHandler.OperationStatus =
    if (IntentionPreviewUtils.isIntentionPreviewActive) Writable
    // A caller may pass the null virtual file of a non-physical PSI file; it names no file to report.
    else new Refused(files.asScala.filter(_ != null).toArray)
}
