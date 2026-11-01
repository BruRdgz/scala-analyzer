package scalapsihost.language

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.completion.impl.CompletionSorterImpl
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.modcommand.{ActionContext, ModCommandExecutor, ModNavigate}
import com.intellij.modcompletion.ModCompletionItem
import com.intellij.codeInsight.lookup.{Classifier, Lookup, LookupElement, LookupElementBuilder, LookupElementPresentation, LookupElementRenderer, WeighingContext}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.{Disposer, TextRange}
import com.intellij.psi.*
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.util.{Consumer, ProcessingContext}
import org.jetbrains.plugins.scala.lang.completion.handlers.ScalaInsertHandler
import org.jetbrains.plugins.scala.lang.completion.lookups.ScalaKeywordLookupItem.KeywordInsertHandler
import org.jetbrains.plugins.scala.lang.completion.lookups.ScalaLookupItem
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScEnumCase, ScFunction, ScTypeAlias, ScValueOrVariable}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter, ScTypeParam}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScEnum, ScObject, ScTrait, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.ScSyntheticFunction
import org.jetbrains.plugins.scala.project.ScalaFeatures

import scalapsihost.platform.{CallLog, HostLock, HostLog, RequestEditor}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Computes BASIC completion using the plugin's contributors and relevance weighers.
 *
 * As in the IDE, a suggestion's insert handler runs only once the user has chosen it ([[insert]]): the list inserts
 * the lookup string, and the handler (parentheses, an import, generated code) then runs on the text that has it,
 * through the [[ChangeEngine]].
 */
object Completion {
  /** Completion categories exposed by the host, before a protocol adapter maps them to editor enums. */
  enum Kind {
    case EnumMember, Enum, Interface, Module, TypeParameter, Class, Constructor, Method, Field, Variable, Value, Keyword
  }

  /** Bounds rendering and response size while retaining enough choices for the user to narrow the query. */
  val MaxSuggestions = 200

  /**
   * A rendered completion suggestion with its insertion identity.
   *
   * Choosing it replaces the typed prefix with `lookup`. Retain its prefix text and identity to replay
   * the insert handler through [[insert]] against that updated document.
   *
   * @param label presentation text
   * @param tail additional context following the label
   * @param typeText rendered result type when available
   * @param kind completion category
   * @param deprecated whether the presentation marks the item as deprecated
   * @param lookup text inserted when the suggestion is chosen
   * @param prefix UTF-16 range replaced by the lookup text
   * @param prefixText original text within the prefix range
   * @param hasHandler whether choosing the item can do more than insert its lookup text
   * @param identity opaque key distinguishing rendered candidates during insertion replay
   */
  final case class Suggestion(
    label: String,
    tail: Option[String],
    typeText: Option[String],
    kind: Kind,
    deprecated: Boolean,
    lookup: String,
    prefix: TextRange,
    prefixText: String,
    hasHandler: Boolean,
    identity: String,
  )

  /**
   * Ranked completion results.
   *
   * @param items suggestions in relevance order
   * @param incomplete whether additional renderable suggestions were omitted by the result limit
   */
  final case class Suggestions(items: Seq[Suggestion], incomplete: Boolean)

  /**
   * The previewed result of choosing a completion suggestion.
   *
   * @param result changed text and edits against the input file snapshot
   * @param caret final UTF-16 caret offset within `result.text`
   */
  final case class Inserted(result: ChangeEngine.Result, caret: Int)

  /** The platform service keeps the current completion in a field; host requests complete on several threads. */
  private[scalapsihost] final class Service extends BaseCompletionService {
    private val current = new ThreadLocal[CompletionProcess]
    override def getCurrentCompletion: CompletionProcess = current.get()
    override def performCompletion(parameters: CompletionParameters, consumer: Consumer[? >: CompletionResult]): Unit = {
      current.set(parameters.getProcess)
      try super.performCompletion(parameters, consumer)
      finally current.remove()
    }
  }

  /**
   * Computes and renders completion suggestions at a document offset.
   *
   * Runs under a cancellable read without changing the source. Temporary editors, PSI copies and offset maps
   * are released after rendering; the result contains presentation values only.
   *
   * @param file Scala or Java source being queried
   * @param offset caret offset in UTF-16 code units
   * @param limit maximum number of rendered suggestions to return
   * @return ranked suggestions and whether more renderable items remain
   */
  def complete(file: PsiFile, offset: Int, limit: Int = MaxSuggestions): Suggestions = HostLock.query {
    completionResults(file, file.getText, offset) { results =>
      // The lookup renders visible items lazily. One more successful rendering determines whether the list is incomplete.
      val suggestions = sort(results).iterator.flatMap(result => rendered(result, offset))
      val items = suggestions.take(limit).toVector
      Suggestions(items, incomplete = suggestions.hasNext)
    }
  }

  /**
   * Previews the insert handler of a previously chosen suggestion.
   *
   * The input file must already contain the lookup text in place of the typed prefix. The candidate is found again
   * using its original prefix and identity; a missing lookup or candidate produces no follow-up.
   *
   * @param file Scala or Java file containing the inserted lookup text
   * @param start UTF-16 start offset of that text
   * @param prefixText prefix used to obtain the original suggestion
   * @param lookup inserted lookup string
   * @param identity suggestion identity returned by [[complete]]
   * @return previewed follow-up edits and caret, or none when the selection can no longer be replayed
   */
  def insert(file: PsiFile, start: Int, prefixText: String, lookup: String, identity: String): Option[Inserted] =
    HostLock.query {
      val end = start + lookup.length
      val text = file.getText
      if (!text.startsWith(lookup, start)) None
      else {
        // The suggestion is found again where it was made: in the text with the typed prefix, not the lookup string.
        val typed = text.substring(0, start) + prefixText + text.substring(end)
        val offset = start + prefixText.length
        completionResults(file, typed, offset) { results =>
          val chosen = results.find(result => result.getLookupElement.getLookupString == lookup &&
            rendered(result, offset).exists(_.identity == identity))
          chosen.map(handle(file, _, results, new TextRange(start, end), Lookup.NORMAL_SELECT_CHAR))
        }
      }
    }

  /** Previews the best matching suggestion and its insert handler, as the plugin's completion tests do. */
  private[scalapsihost] def choose(file: PsiFile, offset: Int, lookup: String, completionChar: Char): Inserted =
    HostLock.query {
      completionResults(file, file.getText, offset) { results =>
        val chosen = sort(results).find(_.getLookupElement.getAllLookupStrings.contains(lookup))
          .getOrElse(throw new IllegalArgumentException(s"no suggestion for $lookup"))
        handle(file, chosen, results, new TextRange(offset - chosen.getPrefixMatcher.getPrefix.length, offset), completionChar)
      }
    }

  /** Replaces `prefix` with the chosen lookup string and runs its insert handler, on a copy of the file. */
  private def handle(file: PsiFile, chosen: CompletionResult, results: Vector[CompletionResult], prefix: TextRange,
                     completionChar: Char): Inserted = {
    val element = chosen.getLookupElement
    val (changed, editor) = ChangeEngine.run(file) { copy =>
      val document = copy.getViewProvider.getDocument
      document.replaceString(prefix.getStartOffset, prefix.getEndOffset, element.getLookupString)
      val editor = IntentionPreviewUtils.getPreviewEditor
      editor.getCaretModel.moveToOffset(prefix.getStartOffset + element.getLookupString.length)
      runHandler(copy, editor, element, results.map(_.getLookupElement).toArray, prefix, completionChar)
      // Keep the caret model until postponed formatting has updated its offset in ChangeEngine.
      editor
    }
    Inserted(changed, editor.getCaretModel.getOffset)
  }

  /** Runs an insert handler on the copy using the lookup's offsets, candidates and completion character. */
  private def runHandler(copy: PsiFile, editor: Editor, element: LookupElement, elements: Array[LookupElement],
                         prefix: TextRange, completionChar: Char): Unit = {
    val start = prefix.getStartOffset
    val document = copy.getViewProvider.getDocument
    val tail = editor.getCaretModel.getOffset
    val modItem = element.as(classOf[CompletionItemLookupElement])
    if (modItem != null) {
      val mode = if (completionChar == Lookup.REPLACE_SELECT_CHAR) ModCompletionItem.InsertionMode.OVERWRITE
                 else ModCompletionItem.InsertionMode.INSERT
      // Compute against the queried snapshot, before its prefix was replaced on the preview copy.
      val action = ActionContext.from(null, copy.getOriginalFile).withOffset(start).withSelection(prefix)
      val command = modItem.computeCommand(action, new ModCompletionItem.InsertionContext(mode, completionChar))
      // ModCommand completion consumes its prefix before applying the computed update, as LookupImpl does.
      document.deleteString(start, tail)
      PsiDocumentManager.getInstance(copy.getProject).commitDocument(document)
      ModCommandExecutor.getInstance.executeForFileCopy(command, copy)
      command.unpack().asScala.foreach {
        case navigate: ModNavigate if navigate.file() == copy.getOriginalFile.getVirtualFile && navigate.caret() >= 0 =>
          editor.getCaretModel.moveToOffset(navigate.caret())
        case _ => ()
      }
      return
    }
    val identifierEnd = CompletionInitializationContext.calcDefaultIdentifierEnd(editor, tail)
    val offsets = new OffsetMap(document)
    try {
      offsets.addOffset(CompletionInitializationContext.START_OFFSET, start)
      offsets.addOffset(CompletionInitializationContext.SELECTION_END_OFFSET, tail)
      offsets.addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, identifierEnd)
      // Replacing (Tab) also removes the rest of the identifier the caret was in.
      if (completionChar == Lookup.REPLACE_SELECT_CHAR && identifierEnd > tail) document.deleteString(tail, identifierEnd)
      val context = new InsertionContext(offsets, completionChar, elements, copy, editor, false)
      context.commitDocument()
      element.handleInsert(context)
      // What a handler leaves for later is UI (a parameter info popup, a dialog): its edits are made, the UI is not.
      if (context.getLaterRunnable != null) CallLog.hit("Completion.laterRunnableSkipped")
    } finally offsets.dispose()
  }

  /** Renders one result; a broken presentation is logged and omitted without dropping the other suggestions. */
  private def rendered(result: CompletionResult, offset: Int): Option[Suggestion] =
    try Some(suggestion(result, offset))
    catch {
      case e: ProcessCanceledException => throw e
      case NonFatal(e) =>
        // One suggestion the plugin fails to render must not take the list with it; the error is reported.
        HostLog.error(s"rendering the suggestion ${result.getLookupElement.getLookupString} failed", e)
        CallLog.hit("Completion.renderingFailed")
        None
    }

  /** Converts the plugin's lookup element and presentation into the host's completion value. */
  private def suggestion(result: CompletionResult, offset: Int): Suggestion = {
    /** Trims optional presentation text and omits values that render empty. */
    def text(value: String): Option[String] = Option(value).map(_.trim).filter(_.nonEmpty)

    val element = result.getLookupElement
    val presentation = new LookupElementPresentation
    element.renderElement(presentation)
    // The cheap rendering leaves out Scala's types and signatures: they come from the expensive renderer.
    Option(element.getExpensiveRenderer).foreach { renderer =>
      // The API types the renderer existentially; its contract is to render this very element.
      renderer.asInstanceOf[LookupElementRenderer[LookupElement]].renderElement(element, presentation)
    }
    val label = text(presentation.getItemText).getOrElse(element.getLookupString)
    val tail = text(presentation.getTailText)
    val typeText = text(presentation.getTypeText)
    Suggestion(
      label = label,
      tail = tail,
      typeText = typeText,
      kind = kind(element),
      deprecated = presentation.isStrikeout,
      lookup = element.getLookupString,
      prefix = new TextRange(offset - result.getPrefixMatcher.getPrefix.length, offset),
      prefixText = result.getPrefixMatcher.getPrefix,
      hasHandler = hasHandler(element),
      // What the lookup shows tells overloads apart; the element's class, suggestions of different contributors.
      identity = Seq(element.getClass.getName, label, tail.getOrElse(""), typeText.getOrElse("")).mkString("\u0000"),
    )
  }

  /** Whether choosing an item can add parentheses, imports, keyword spacing or other generated text. */
  private def hasHandler(element: LookupElement): Boolean = ScalaLookupItem.delegate(element) match {
    case item: ScalaLookupItem => !insertsLookupOnly(item)
    case builder: LookupElementBuilder => builder.getInsertHandler != null
    case _ => true
  }

  /** Whether ScalaInsertHandler would leave the lookup string as it is: no parentheses, quotes, backticks or import. */
  private def insertsLookupOnly(item: ScalaLookupItem): Boolean = {
    val changesText = item.getInsertHandler != null && !item.getInsertHandler.isInstanceOf[ScalaInsertHandler] ||
      item.isClassName || item.prefixCompletion || item.shouldImport || item.elementToImport.nonEmpty ||
      item.someSmartCompletion || item.isInSimpleString || item.isInInterpolatedString ||
      item.isNamedParameterOrAssignment || item.isInStableElementPattern || item.getLookupString.contains("`")
    !changesText && (item.getPsiElement match {
      case function: ScFunction =>
        // classOf gets its type argument brackets.
        function.name != "classOf" && ScalaInsertHandler.getItemParametersAndAccessorStatus(function)._1 < 0
      case function: ScSyntheticFunction => function.paramClauses.isEmpty
      case _: PsiMethod | _: ScTypeDefinition => false
      case _ => true
    })
  }

  /** Supplies contributor results while their dummy-identifier copy and offset translations remain alive. */
  private def completionResults[T](file: PsiFile, text: String, offset: Int)(use: Vector[CompletionResult] => T): T =
    Using.Manager { resources =>
      require(offset >= 0 && offset <= text.length, s"completion offset outside the text: $offset")
      ProgressManager.checkCanceled()
      // Other text than the file's, or a file of the plugin's PSI factory (which has no document), is completed on a copy.
      val asIs = file.isPhysical && text == file.getText
      val document = if (asIs) file.getViewProvider.getDocument else new DocumentImpl(text)
      val editor = resources(new RequestEditor(document, file.getProject, offset))
      val initializationFile = if (asIs) file else resources(new TemporaryCopy(file, text)).file
      val init = new CompletionInitializationContext(editor.editor, editor.editor.getCaretModel.getCurrentCaret,
        file.getLanguage, initializationFile, CompletionType.BASIC, 1)
      releasing(resources)(init.getOffsetMap.dispose())
      CompletionContributor.forLanguageHonorDumbness(file.getLanguage, file.getProject, editor.editor).asScala.foreach { contributor =>
        ProgressManager.checkCanceled()
        contributor.beforeCompletion(init)
      }
      val copied = dummyIdentifierCopy(file, text, init, offset, resources)
      val translator = new OffsetTranslator(document, initializationFile, copied.getOffsets.getDocument, init.getStartOffset,
        init.getSelectionEndOffset, init.getDummyIdentifier)
      releasing(resources)(Disposer.dispose(translator))
      val parameters = CompletionInitializationUtil.createCompletionParameters(init, () => false, copied)
      val results = Vector.newBuilder[CompletionResult]
      CompletionService.getCompletionService.performCompletion(parameters, result => results += result)
      use(results.result())
    }.get

  /** Builds the completion PSI copy with its dummy identifier and translates initialization offsets around it. */
  private def dummyIdentifierCopy(file: PsiFile, text: String, init: CompletionInitializationContext, offset: Int,
                                  resources: Using.Manager): OffsetsInFile = {
    val dummy = init.getDummyIdentifier
    val copy = resources(new TemporaryCopy(file, text.substring(0, init.getStartOffset) + dummy + text.substring(init.getSelectionEndOffset))).file
    val offsets = new OffsetMap(copy.getViewProvider.getDocument)
    releasing(resources)(offsets.dispose())
    for (key <- init.getOffsetMap.getAllOffsets.asScala) {
      val at = init.getOffsetMap.getOffset(key)
      offsets.addOffset(key, if (at > offset || at == offset && key.isMovableToRight) at + dummy.length else at)
    }
    new OffsetsInFile(copy, offsets)
  }

  /** Adds a by-name cleanup action to the enclosing resource scope. */
  private def releasing(resources: Using.Manager)(release: => Unit): Unit = {
    val closeable: AutoCloseable = () => release
    resources(closeable)
  }

  /** Creates completion-only PSI through the file factory without the write access required by PsiFile.copy. */
  private final class TemporaryCopy(original: PsiFile, text: String) extends AutoCloseable {
    val file: PsiFile = PsiFileFactory.getInstance(original.getProject)
      .createFileFromText(original.getName, original.getLanguage, text, false, false)
    file match {
      case source: PsiFileImpl => source.setOriginalFile(original)
      case other => throw new IllegalStateException(s"completion copy is not a source file: ${other.getClass.getName}")
    }
    // Module scopes come from the original file; Scala features are plain user data, set before the tree is parsed.
    original match {
      case scala: ScalaFile => ScalaFeatures.setAttachedScalaFeatures(file, ScalaFeatures.forPsiOrDefault(scala))
      case _ => ()
    }

    /** Drops the temporary copy's view-provider cache entry. */
    override def close(): Unit =
      PsiManagerEx.getInstanceEx(original.getProject).getFileManager.setViewProvider(file.getViewProvider.getVirtualFile, null)
  }

  /** Each result keeps the sorter of the contributor that made it: results are ordered within their sorter's group. */
  private def sort(results: Vector[CompletionResult]): Vector[CompletionResult] = {
    val byElement = results.map(result => result.getLookupElement -> result).toMap
    val context = weighingContext(byElement)
    results.map(sorterOf).distinct.flatMap { sorter =>
      // Sorters are compared by value: contributors make equal ones for each result.
      val group = results.filter(sorterOf(_) == sorter)
      // Equal weights are ordered by source position, never by hash iteration, so the order is the same every time.
      classified(sorter, group.map(_.getLookupElement).sortBy(sourceOrder), context).map(byElement)
    }
  }

  /** Orders items through the classifier chain, removing failed items with their partial weights. */
  private def classified(sorter: CompletionSorterImpl, elements: Vector[LookupElement], context: ProcessingContext): Vector[LookupElement] = {
    val classifier = sorter.buildClassifier(Classifier.empty[LookupElement]())
    val weighed = elements.filter { element =>
      ProgressManager.checkCanceled()
      try {
        classifier.addElement(element, context)
        true
      } catch {
        case e: ProcessCanceledException => throw e
        case NonFatal(e) =>
          HostLog.error(s"weighing the suggestion ${describe(element)} failed", e)
          CallLog.hit("Completion.weighingFailed")
          classifier.removeElement(element, context)
          false
      }
    }
    classifier.classify(weighed.asJava, context).asScala.toVector
  }

  /** What a failure report needs to find the element: its kind, and where its PSI is (or is not). */
  private def describe(element: LookupElement): String = {
    val psi = Option(element.getPsiElement)
    val file = psi.flatMap(e => Option(e.getContainingFile)).fold("no file")(f => s"in ${f.getName} (${f.getClass.getSimpleName})")
    s"${element.getLookupString} (${element.getClass.getSimpleName}; ${psi.fold("no PSI")(e => s"${e.getClass.getSimpleName}, valid ${e.isValid}, $file")})"
  }

  /** Requires the plugin's sorter implementation because the host reproduces its classifier ordering. */
  private def sorterOf(result: CompletionResult): CompletionSorterImpl = result.getSorter match {
    case sorter: CompletionSorterImpl => sorter
    case other => throw new IllegalStateException(s"unsupported completion sorter: ${other.getClass.getName}")
  }

  /** The weighing inputs the IDE's lookup arranger supplies before the user types again. */
  private def weighingContext(byElement: Map[LookupElement, CompletionResult]): ProcessingContext = {
    val context = new ProcessingContext
    context.put(CompletionLookupArranger.PREFIX_CHANGES, Integer.valueOf(0))
    context.put(CompletionLookupArranger.WEIGHING_CONTEXT, new WeighingContext {
      override def itemMatcher(item: LookupElement): PrefixMatcher = byElement(item).getPrefixMatcher
      override def itemPattern(item: LookupElement): String = itemMatcher(item).getPrefix
    })
    context
  }

  /** Stable tie-break fields for suggestions with equal platform weights. */
  private def sourceOrder(element: LookupElement): (String, String, Int, String) = {
    val psi = Option(element.getPsiElement)
    val path = psi.flatMap(e => Option(e.getContainingFile)).map(_.getViewProvider.getVirtualFile.getPath).getOrElse("")
    // Synthetic functions (the standard library's operators) have no source: their signature tells overloads apart.
    val signature = psi match {
      case Some(function: ScSyntheticFunction) =>
        function.paramClauses.map(_.map(_.paramType.canonicalText).mkString("(", ",", ")")).mkString + function.retType.canonicalText
      case _ => ""
    }
    (element.getLookupString, path, psi.fold(-1)(_.getTextOffset), signature)
  }

  /** Maps the suggestion's PSI element to the closest host completion category. */
  private def kind(element: LookupElement): Kind = element.getPsiElement match {
    case _: ScEnumCase | _: PsiEnumConstant => Kind.EnumMember
    case _: ScEnum => Kind.Enum
    case _: ScTrait => Kind.Interface
    case _: ScObject => Kind.Module
    case _: ScTypeParam | _: PsiTypeParameter => Kind.TypeParameter
    case _: ScTypeDefinition | _: ScTypeAlias => Kind.Class
    case cls: PsiClass if cls.isEnum => Kind.Enum
    case cls: PsiClass if cls.isInterface => Kind.Interface
    case _: PsiClass => Kind.Class
    case method: PsiMethod if method.isConstructor => Kind.Constructor
    case function: ScFunction if function.isConstructor => Kind.Constructor
    case _: ScFunction | _: PsiMethod | _: ScSyntheticFunction => Kind.Method
    case _: ScClassParameter => Kind.Field
    case _: ScParameter | _: PsiParameter | _: PsiLocalVariable => Kind.Variable
    case _: PsiField => Kind.Field
    case _: PsiPackage => Kind.Module
    case _: PsiKeyword => Kind.Keyword
    case named: ScNamedElement =>
      if (Option(element.as(classOf[ScalaLookupItem])).exists(_.isLocalVariable)) Kind.Variable
      else named.nameContext match {
        case _: ScValueOrVariable => Kind.Field
        case _ => Kind.Value
      }
    case _ if isKeyword(element) => Kind.Keyword
    case _ => Kind.Value
  }

  /** Identifies lookup items whose insert handler represents a Scala keyword. */
  private def isKeyword(element: LookupElement): Boolean = element match {
    case builder: LookupElementBuilder => builder.getInsertHandler.isInstanceOf[KeywordInsertHandler]
    case _ => false
  }
}
