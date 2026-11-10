package scalaanalyzer.language

import com.intellij.codeInsight.completion.{
  CompletionContributor, CompletionParameters, CompletionResultSet, InsertionContext, OffsetTranslator
}
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementBuilder}
import com.intellij.codeInsight.template.postfix.templates.{PostfixLiveTemplate, PostfixTemplate}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.Disposer
import com.intellij.psi.{PsiDocumentManager, PsiElement}
import com.intellij.psi.util.PsiUtilCore
import org.jetbrains.plugins.scala.lang.completion.postfix.ScalaPostfixTemplateProvider

import scalaanalyzer.platform.{CallLog, HostLog}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Postfix templates (`expr.if`, `expr.for`, `expr.var`...) as completion items after `expr.`.
 *
 * The platform's `PostfixTemplateCompletionContributor` depends on IDE settings, live-template services and the
 * editor's auto-popup, which the host does not have; this contributor lists the Scala plugin's own templates and
 * applies their `isApplicable` the way `PostfixLiveTemplate` does. The expansion is the item's insert handler: it
 * runs only once the item is chosen, through [[Completion.insert]], so a request pays for the applicability checks
 * only. Choosing an item replaces the typed `.key` with the template's real expansion, caret included.
 *
 * Only the templates keyed `.name` are listed: the other one (`!`) is an alias that is typed after the expression
 * and is not a completion position.
 */
object PostfixCompletion {
  /** Whether the current thread is expanding a postfix template, see [[isAnsweringDialogs]]. */
  private val expanding = ThreadLocal.withInitial(() => false)

  /**
   * Tells whether the platform's questions on the current thread should get the unit-test defaults.
   *
   * A template that finds several expressions to apply to asks the user in a popup, and the variable and field
   * templates ask for a name in a dialog or an in-place editor. Unit-test mode is the plugin's way of taking the
   * first expression (the innermost) and the suggested name, and the host has no UI to ask.
   *
   * @return `true` only while a postfix template expands with the host's default answers
   */
  def isAnsweringDialogs: Boolean = expanding.get

  /** The Scala plugin's templates whose key is `.name`, ordered by key, then identity, as the list must be stable. */
  private lazy val templates: Seq[PostfixTemplate] =
    new ScalaPostfixTemplateProvider().getTemplates.asScala.toSeq
      .filter(_.getKey.startsWith("."))
      .sortBy(template => (template.getKey, template.getId))

  /** The identifier typed after a dot, and the offset of that dot. */
  private final case class TypedKey(dot: Int, prefix: String)

  /** Offers the applicable postfix templates once the user has typed `.` and part of a template name. */
  final class Contributor extends CompletionContributor {
    /** Adds one item per template that applies to the expression before the typed `.key`, if there is one. */
    override def fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet): Unit =
      for (key <- typedKey(parameters.getOriginalFile.getText, parameters.getOffset)) {
        val items = applicable(parameters, key).map(element)

        result.withPrefixMatcher(key.prefix).addAllElements(items.asJava)
      }
  }

  /** The `.identifier` that ends at `offset`, or none when what precedes the caret is not a template key. */
  private def typedKey(text: String, offset: Int): Option[TypedKey] = {
    val before = text.substring(0, offset)
    val dot = before.lastIndexWhere(c => !Character.isJavaIdentifierPart(c))
    val prefix = before.substring(dot + 1)

    Option.when(dot >= 0 && before(dot) == '.' && (prefix.isEmpty || Character.isJavaIdentifierStart(prefix.head))) {
      TypedKey(dot, prefix)
    }
  }

  /** The templates that apply to the expression ending at the dot, found in a copy of the file without the key. */
  private def applicable(parameters: CompletionParameters, key: TypedKey): Seq[PostfixTemplate] =
    Using.Manager { resources =>
      val original = parameters.getOriginalFile
      val text = original.getText
      val withoutKey = text.substring(0, key.dot) + text.substring(parameters.getOffset)
      val copy = resources(new Completion.TemporaryCopy(original, withoutKey)).file
      val copyDocument = copy.getViewProvider.getDocument
      // The copy's elements know their originals through the translated offsets, as in `PostfixLiveTemplate`.
      val translator = new OffsetTranslator(parameters.getEditor.getDocument, original, copyDocument, key.dot,
        parameters.getOffset, "")
      resources((() => Disposer.dispose(translator)): AutoCloseable)

      val context = PsiUtilCore.getElementAtOffset(copy, PostfixLiveTemplate.positiveOffset(key.dot))

      templates.filter(isApplicable(_, context, copyDocument, key.dot))
    }.get

  /** Asks the template; one that fails is logged and left out without dropping the others. */
  private def isApplicable(template: PostfixTemplate, context: PsiElement, copyDocument: Document,
                           offset: Int): Boolean = {
    ProgressManager.checkCanceled()
    try template.isApplicable(context, copyDocument, offset)
    catch {
      case e: ProcessCanceledException => throw e
      case NonFatal(e) =>
        // One template that fails on this code must not take the others with it; the error is reported.
        HostLog.error(s"checking the postfix template ${template.getKey} failed", e)
        CallLog.hit("Completion.postfixCheckFailed")
        false
    }
  }

  /** The completion item of a template: its key without the dot, its example as type text, its expansion as handler. */
  private def element(template: PostfixTemplate): LookupElement =
    LookupElementBuilder.create(template, template.getKey.stripPrefix("."))
      .withTypeText(template.getExample, true)
      .withInsertHandler((context, _) => expand(template, context))

  /**
   * Replaces the key the user typed, `.name`, by the template's expansion, as `PostfixLiveTemplate` does.
   *
   * The insertion context's file is the preview copy, which has the lookup string in place of the typed prefix.
   */
  private def expand(template: PostfixTemplate, context: InsertionContext): Unit = {
    val document = context.getDocument
    val editor = context.getEditor
    val dot = context.getStartOffset - 1

    document.deleteString(dot, editor.getCaretModel.getOffset)
    editor.getCaretModel.moveToOffset(dot)
    PsiDocumentManager.getInstance(context.getProject).commitDocument(document)

    val expression = PsiUtilCore.getElementAtOffset(context.getFile, PostfixLiveTemplate.positiveOffset(dot))
    val expansion: Runnable = () => template.expand(expression, editor)
    val previous = expanding.get

    expanding.set(true)
    try {
      if (template.startInWriteAction) ApplicationManager.getApplication.runWriteAction(expansion)
      else expansion.run()
    } finally expanding.set(previous)
  }
}
