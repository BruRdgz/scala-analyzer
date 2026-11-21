package scalaanalyzer.language

import com.intellij.codeInsight.hint.ShowParameterInfoContext
import com.intellij.codeInsight.hint.api.JavaSignaturePresentation
import com.intellij.codeInsight.hint.api.impls.MethodParameterInfoHandler
import com.intellij.lang.parameterInfo.{LanguageParameterInfo, ParameterInfoHandler, ParameterInfoUIContext, UpdateParameterInfoContext}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiJavaFile}

import scalaanalyzer.platform.{HostLock, RequestEditor, tableProxy}

import java.awt.Color

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Renders call signatures using the Scala plugin's parameter-info handlers and the Java plugin's method handler.
 *
 * Scala handlers receive creation, update and presentation contexts equivalent to the editor hint's lifecycle. Java
 * calls go through the handler's editor-free API (see `inJava`). The result contains text and active-parameter
 * ranges without creating a popup.
 */
object ParameterInfo {
  /**
   * One signature rendered by a parameter-info handler.
   *
   * @param text signature label
   * @param current active parameter's UTF-16 range within the label
   * @param applicable whether the handler leaves the signature enabled
   * @param deprecated whether the handler marks the signature for strikeout
   */
  final case class Signature(text: String, current: Option[TextRange], applicable: Boolean, deprecated: Boolean)

  /**
   * The signatures shown for a call.
   *
   * @param items rendered signatures in handler order; items the handler renders without text are omitted
   * @param active zero-based index into `items` of the highlighted overload; none when the handler highlights no
   *               rendered item
   */
  final case class Signatures(items: Seq[Signature], active: Option[Int])

  /** The hint's background, which the handlers only hand back to `setupUIComponentPresentation`. */
  private val Background = Color.WHITE

  /** The Java plugin's handler for method and constructor calls; it keeps no state between requests. */
  private val JavaHandler = new MethodParameterInfoHandler

  /**
   * Finds signatures for the call around a document offset.
   *
   * The first handler that finds a call supplies the result, even if it renders none of its items (`items` is then
   * empty). Collection runs under a cancellable host read, reusing one the caller already holds; the temporary
   * editor is released before returning.
   *
   * @param file Scala or Java source being queried
   * @param offset caret offset in UTF-16 code units
   * @return rendered signatures, or none when no handler finds a call
   */
  def at(file: PsiFile, offset: Int): Option[Signatures] = HostLock.query {
    file match {
      case javaFile: PsiJavaFile => inJava(javaFile, offset)
      case _ =>
        Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, offset)) { editor =>
          LanguageParameterInfo.INSTANCE.allForLanguage(file.getLanguage).asScala.iterator
            .map(handler => signatures(handler, file, editor.editor, offset))
            .collectFirst { case Some(found) => found }
        }
    }
  }

  /**
   * Renders the overloads of the Java call around an offset through the handler's read-only API.
   *
   * The handler's editor path updates the hint with the editor's parameter-name inlays (`Editor.isDisposed`,
   * `ParameterHintsPresentationManager`) and reads the caret, which the host has no editor to answer. The read-only
   * API returns the same texts, parameter ranges, overload and parameter, from the file and offset alone. It carries
   * neither which overloads fit the arguments nor which are deprecated, so those are reported as applicable and not
   * deprecated.
   */
  private def inJava(file: PsiJavaFile, offset: Int): Option[Signatures] =
    Option(JavaHandler.getParameterInfo(file, offset)).map { info =>
      val items = info.getSignaturePresentationList.asScala.toSeq.map(javaSignature)

      Signatures(items, Option(info.getCurrentSignatureIndex).map(_.intValue))
    }

  /** One Java overload's label, with the range of the parameter the call's current argument fills, if any. */
  private def javaSignature(presentation: JavaSignaturePresentation): Signature = {
    val current = for {
      index <- Option(presentation.getCurrentParameterIndex)
      parameter <- presentation.getParameterRangeList.asScala.lift(index.intValue)
    } yield new TextRange(parameter.getRange.getFrom.intValue, parameter.getRange.getTo.intValue)

    Signature(presentation.getLabel, current, applicable = true, deprecated = false)
  }

  /** Runs one plugin handler's creation and update steps, keeping only the items it renders. */
  private def signatures(handler: ParameterInfoHandler[?, ?], file: PsiFile, editor: Editor,
                         offset: Int): Option[Signatures] = {
    // The handler's owner and item types are those of what it finds itself: it only gets back its own objects.
    val typed = handler.asInstanceOf[ParameterInfoHandler[PsiElement, AnyRef]]
    val creation = new ShowParameterInfoContext(editor, file.getProject, file, offset, -1)

    Option(typed.findElementForParameterInfo(creation)).flatMap { owner =>
      val items = Option(creation.getItemsToShow).fold(Seq.empty[AnyRef])(_.toSeq)

      Option.when(items.nonEmpty) {
        val hint = new HintState(file, editor, offset, owner, items)

        // As the hint does once shown: the handler finds the current parameter and the overload it fits.
        Option(typed.findElementForUpdatingParameterInfo(hint.updating)).foreach { updated =>
          hint.owner = updated
          typed.updateParameterInfo(updated, hint.updating)
        }

        val rendered = items.map(item => item -> render(typed, item, hint, singleOverload = items.size == 1))
        val shown = rendered.collect { case (item, Some(signature)) => item -> signature }

        Signatures(shown.map(_._2), Option(shown.indexWhere(_._1 eq hint.highlighted)).filter(_ >= 0))
      }
    }
  }

  /** What the IDE's hint keeps between its updates: the call, the current parameter and the highlighted overload. */
  private final class HintState(file: PsiFile, editor: Editor, offset: Int, var owner: PsiElement,
                                items: Seq[AnyRef]) {
    var currentParameter = -1
    var highlighted: AnyRef = null

    /** The update context handed to the handler; its setters write back into this state. */
    val updating: UpdateParameterInfoContext =
      tableProxy(classOf[UpdateParameterInfoContext], "UpdateParameterInfoContext") {
        case ("getProject", _) => file.getProject
        case ("getFile", _) => file
        case ("getOffset", _) => Integer.valueOf(offset)
        case ("getEditor", _) => editor
        case ("getParameterOwner", _) => owner
        case ("setParameterOwner", Array(element: PsiElement)) =>
          owner = element
          null
        case ("setCurrentParameter", Array(index: Integer)) =>
          currentParameter = index
          null
        case ("setHighlightedParameter", Array(item)) =>
          highlighted = item
          null
        case ("getHighlightedParameter", _) => highlighted
        case ("getObjectsToView", _) => items.toArray
        // No hint is shown, so none is removed.
        case ("removeHint", _) => null
      }
  }

  /** Renders one item through the handler's presentation callback; none when the handler supplies no text for it. */
  private def render(handler: ParameterInfoHandler[PsiElement, AnyRef], item: AnyRef, hint: HintState,
                     singleOverload: Boolean): Option[Signature] = {
    var shown = Option.empty[Signature]
    // `java.lang.Boolean` stays qualified: the simple name is Scala's `Boolean`, but the proxy matches boxed values.
    val rendering = tableProxy(classOf[ParameterInfoUIContext], "ParameterInfoUIContext") {
      case ("getParameterOwner", _) => hint.owner
      case ("getCurrentParameterIndex", _) => Integer.valueOf(hint.currentParameter)
      case ("getDefaultParameterColor", _) => Background
      case ("isSingleOverload", _) => java.lang.Boolean.valueOf(singleOverload)
      case ("isSingleParameterInfo", _) => java.lang.Boolean.FALSE
      // A handler disables the component when it has nothing to show: nothing is recorded then.
      case ("setUIComponentEnabled", _) => null
      case ("setupUIComponentPresentation",
            Array(text: String, start: Integer, end: Integer, disabled: java.lang.Boolean,
                  strikeout: java.lang.Boolean, _, _)) =>
        val current = Option.when(start >= 0 && end > start)(new TextRange(start, end))
        shown = Some(Signature(text, current, applicable = !disabled, deprecated = strikeout))
        text
    }

    handler.updateUI(item, rendering)

    shown.filter(_.text.nonEmpty)
  }
}
