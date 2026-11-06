package scalaanalyzer.language

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.progress.ProgressManager
import org.jetbrains.plugins.scala.annotator.hints.{AnnotatorHints, Hint}
import org.jetbrains.plugins.scala.codeInsight.hints.ScalaHintsSettings
import org.jetbrains.plugins.scala.codeInsight.implicits.{ImplicitHints, TextPartsHintRenderer}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.platform.{HostImplicitHintsPass, HostLock, RequestEditor}

import scala.collection.mutable.Buffer
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Collects inline hints from the Scala plugin's own editor pass.
 *
 * Includes inferred types, parameter names, implicit/using arguments, conversions, method chains and type arguments.
 * Range hints requiring desktop editor presentations are excluded.
 */
object InlayHints {

  /**
   * A rendered inline hint.
   *
   * @param offset UTF-16 document offset between characters
   * @param text rendered hint label
   */
  final case class InlayHint(offset: Int, text: String)

  /** Reads registered plugin settings on demand, excluding range hints that require desktop presentations. */
  private lazy val Settings = new ScalaHintsSettings.CodeInsightSettingsAdapter {
    // RangeInlayHintsPass requires EditorImpl and produces drawn presentations rather than text.
    override def showRangeHintsForToAndUntil: Boolean = false
    override def showExclusiveRangeHint: Boolean = false
  }

  /** The plugin's hint-collecting pass class, whose private result buffer is read reflectively. */
  private val PassClass = Class.forName("org.jetbrains.plugins.scala.codeInsight.implicits.ImplicitHintsPass")

  // The pass exposes collection but keeps its results private, with no accessor before editor rendering.
  /** The pass's private `hints` field. */
  private val CollectedHints = {
    val field = PassClass.getDeclaredField("hints")
    field.setAccessible(true)
    field
  }

  // The public setter rebinds IDE shortcuts and fails without KeymapManager. A field write is smaller than
  // adding the absent keymap API and adapters solely for those shortcuts; the host only needs the enabled flag.
  /** The plugin's private implicit-hints enabled flag, written reflectively. */
  private val Enabled = {
    val field = ImplicitHints.getClass.getDeclaredField("_enabled")
    field.setAccessible(true)
    field
  }

  /**
   * Sets the plugin's implicit-hints flag without registering the IDE keymap services it otherwise requires.
   *
   * The flag lives in the plugin's `ImplicitHints` singleton, so it applies to every file and request.
   *
   * @param enabled whether the plugin's pass should produce implicit argument and conversion hints
   */
  private[scalaanalyzer] def setImplicitHintsEnabled(enabled: Boolean): Unit = Enabled.setBoolean(ImplicitHints, enabled)

  /**
   * Collects the plugin's inline hints for a Scala file.
   *
   * Collection runs under a cancellable host read with a temporary request editor, released before returning.
   *
   * @param file source file to query
   * @return hints in document order, preserving collection order at equal offsets
   */
  def in(file: ScalaFile): Seq[InlayHint] = HostLock.query {
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, 0)) { editor =>
      val pass = HostImplicitHintsPass.create(editor.editor, file, Settings)

      pass.doCollectInformation(ProgressManager.getInstance.getProgressIndicator)

      val ordinary = collected(pass).map(hint => InlayHint(hint.position.getOffset(hint.element) + hint.offsetDelta, text(hint)))
      (ordinary ++ methodChains(pass)).sortBy(_.offset)
    }
  }

  /** Renders the hint, including nested parts when the IDE's implicit-hint expansion toggle is on. */
  private def text(hint: Hint): String =
    if (ImplicitHints.expanded) {
      val renderer = new TextPartsHintRenderer(hint.parts, hint.menu, hint.corners)
      renderer.expand()
      renderer.parts.map(_.string).mkString
    } else hint.parts.map(_.string).mkString

  /** Converts the plugin's chained-call hints and omits layout-only or mismatch-hidden entries. */
  private def methodChains(pass: TextEditorHighlightingPass): Seq[InlayHint] =
    HostImplicitHintsPass.methodChains(pass).asScala.toSeq.flatMap { group =>
      val hints = group.hints.asScala.toSeq
      // As regenerateMethodChainHints does, omit the last hint after an annotated type mismatch.
      val shown = if (AnnotatorHints.in(group.outer).nonEmpty) hints.dropRight(1) else hints

      if (shown.size < 2) Seq.empty
      // Blank templates only pad the IDE's vertical alignment; they have no label to show in LSP.
      else shown
        .filterNot(_.parts.forall(_.string.isBlank))
        .map(hint => InlayHint(hint.offset, hint.parts.map(_.string).mkString))
    }

  /** Reads the plugin pass's private result buffer, rejecting an unexpected runtime shape. */
  private def collected(pass: TextEditorHighlightingPass): Seq[Hint] = CollectedHints.get(pass) match {
    case hints: Buffer[?] => hints.toSeq.map {
      case hint: Hint => hint
      case other => throw new IllegalStateException(s"not a plugin hint: $other")
    }
    case other => throw new IllegalStateException(s"not the plugin's hints buffer: $other")
  }
}
