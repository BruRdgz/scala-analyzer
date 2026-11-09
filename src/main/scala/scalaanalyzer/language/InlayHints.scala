package scalaanalyzer.language

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.annotator.hints.Hint.HintPosition
import org.jetbrains.plugins.scala.annotator.hints.{AnnotatorHints, Hint, Text}
import org.jetbrains.plugins.scala.codeInsight.ScalaCodeInsightSettings
import org.jetbrains.plugins.scala.codeInsight.hints.ScalaHintsSettings
import org.jetbrains.plugins.scala.codeInsight.implicits.{ImplicitHints, TextPartsHintRenderer}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPatternList
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameters
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition, ScVariableDefinition}

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
   * @param parts the plugin's label parts, which keep their navigation targets and tooltips
   * @param annotation the text that writes the hint into the source, for a hint that shows the inferred type of a
   *                   definition (`: List[Int]`); absent for any other hint
   */
  final case class InlayHint(offset: Int, parts: Seq[Text], annotation: Option[String]) {
    /** The label as one string. */
    def text: String = parts.map(_.string).mkString
  }

  /**
   * Which kinds of hints the plugin's pass produces, as the plugin's settings name them.
   *
   * @param methodResultTypes result types of `def`s without one
   * @param memberVariableTypes types of `val`s and `var`s of classes, objects and traits without one
   * @param localVariableTypes types of local `val`s and `var`s without one
   * @param obviousTypes types of definitions whose initializer already shows the type
   * @param parameterNames parameter names before the arguments whose meaning is unclear
   * @param methodChains the type after each call of a chain of calls
   * @param implicits implicit arguments and conversions
   */
  final case class Toggles(
    methodResultTypes: Boolean,
    memberVariableTypes: Boolean,
    localVariableTypes: Boolean,
    obviousTypes: Boolean,
    parameterNames: Boolean,
    methodChains: Boolean,
    implicits: Boolean,
  )

  object Toggles {
    /** The IDE's defaults, except for implicit hints, which the host shows (see `HostBootstrap`). */
    val Default: Toggles = Toggles(
      methodResultTypes = ScalaCodeInsightSettings.SHOW_METHOD_RESULT_TYPE_DEFAULT,
      memberVariableTypes = ScalaCodeInsightSettings.SHOW_MEMBER_VARIABLE_TYPE_DEFAULT,
      localVariableTypes = ScalaCodeInsightSettings.SHOW_LOCAL_VARIABLE_TYPE_DEFAULT,
      obviousTypes = ScalaCodeInsightSettings.SHOW_OBVIOUS_TYPE_DEFAULT,
      parameterNames = ScalaCodeInsightSettings.SHOW_PARAMETER_NAMES_DEFAULT,
      methodChains = ScalaCodeInsightSettings.SHOW_METHOD_CHAIN_INLAY_HINTS_DEFAULT,
      implicits = true,
    )
  }

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
   * Selects the kinds of hints that later collections produce.
   *
   * The plugin keeps these settings in process-wide singletons, so they apply to every file and request. Takes the
   * write lock, which cancels the queries running meanwhile: they collect under the old settings.
   *
   * @param toggles the kinds of hints to produce
   */
  def configure(toggles: Toggles): Unit = HostLock.write {
    val settings = ScalaCodeInsightSettings.getInstance

    settings.showFunctionReturnType = toggles.methodResultTypes
    settings.showPropertyType = toggles.memberVariableTypes
    settings.showLocalVariableType = toggles.localVariableTypes
    settings.showObviousType = toggles.obviousTypes
    settings.showParameterNames = toggles.parameterNames
    settings.showMethodChainInlayHints = toggles.methodChains
    setImplicitHintsEnabled(toggles.implicits)
  }

  /**
   * The declaration a label part navigates to in the IDE.
   *
   * @param part label part of a hint
   * @return the part's navigation target; absent for a part without one
   */
  def target(part: Text): Option[PsiElement] = part.navigatable.collect { case element: PsiElement => element }

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

      val ordinary = collected(pass).map { hint =>
        InlayHint(hint.position.getOffset(hint.element) + hint.offsetDelta, shown(hint), annotation(hint))
      }
      (ordinary ++ methodChains(pass)).sortBy(_.offset)
    }
  }

  /** The hint's parts, including nested parts when the IDE's implicit-hint expansion toggle is on. */
  private def shown(hint: Hint): Seq[Text] =
    if (ImplicitHints.expanded) {
      val renderer = new TextPartsHintRenderer(hint.parts, hint.menu, hint.corners)
      renderer.expand()
      renderer.parts
    } else hint.parts

  /** The text that writes a definition's type hint into the source, with the types the hint abbreviates written out. */
  private def annotation(hint: Hint): Option[String] =
    Option.when(showsDefinitionType(hint))(unfolded(hint.parts).map(_.string).mkString)

  /** Whether the hint is the one `ScalaTypeHintsPass` puts after a `val`, `var` or `def` that has no type. */
  private def showsDefinitionType(hint: Hint): Boolean =
    hint.position == HintPosition.AfterElement && hint.parts.headOption.exists(_.string == ": ") &&
      ((hint.element, hint.element.getParent) match {
        case (_: ScPatternList, _: ScPatternDefinition | _: ScVariableDefinition) => true
        case (_: ScParameters, _: ScFunctionDefinition) => true
        case _ => false
      })

  /** Replaces the parts that stand for a longer text (a type the hint abbreviates as `...`) by that text. */
  private def unfolded(parts: Seq[Text]): Seq[Text] =
    parts.flatMap(part => part.expansion.fold(Seq(part))(expansion => unfolded(expansion())))

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
        .map(hint => InlayHint(hint.offset, hint.parts, annotation = None))
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
