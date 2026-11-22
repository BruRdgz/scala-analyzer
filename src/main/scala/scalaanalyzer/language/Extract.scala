package scalaanalyzer.language

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.modcommand.{ActionContext, ModChooseAction}
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, PsiJavaFile}
import com.intellij.refactoring.{IntroduceSite, IntroduceVariableUtil}
import com.intellij.refactoring.introduceField.ElementToWorkOn
import com.intellij.refactoring.introduceVariable.JavaIntroduceVariableModCommandService
import com.intellij.refactoring.introduceVariable.JavaIntroduceVariableModCommandService.ToVariableContext
import com.intellij.refactoring.util.CommonRefactoringUtil.RefactoringErrorHintException
import org.jetbrains.plugins.scala.extensions.PsiElementExt
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody
import org.jetbrains.plugins.scala.lang.refactoring.ExtractMethodChecks
import org.jetbrains.plugins.scala.lang.refactoring.extractMethod.ScalaExtractMethodHandler
import org.jetbrains.plugins.scala.lang.refactoring.introduceVariable.ScalaIntroduceVariableHandler
import org.jetbrains.plugins.scala.lang.refactoring.introduceVariable.ScalaIntroduceVariableHandler.ReplaceTestOptions
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaRefactoringUtil

import scalaanalyzer.LineColumn
import scalaanalyzer.platform.RequestEditor

import java.lang.reflect.{InvocationTargetException, Method}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Offers and performs the plugin's Introduce Variable (Extract to val) and Extract Method refactorings for a
 * selection.
 *
 * The plugin's handlers ask in a dialog or a popup what to replace and what to call the new declaration, and show an
 * error hint where they refuse. The host has neither: it takes the handlers' unit-test path, which answers with the
 * IDE's defaults (see [[isAnsweringDialogs]]), and offers nothing where a handler would refuse. The handlers change
 * the file they are given, so a refactoring is performed on the physical file, which the caller restores (see
 * [[perform]]); a copy has no physical elements, which Extract Method needs to find the parameters of the new method
 * and whose absence the platform asserts for the inspections that Introduce Variable runs.
 *
 * Introduce Variable offers the IDE's choice of occurrences as two refactorings, and names the variable as the IDE's
 * dialog proposes. Its type annotation is the plugin's setting, which by default asks for an explicit type, as the
 * dialog does and the in-place editor does not. Extract Method goes to the scope the plugin picks, replaces the
 * duplicates of the selection (which its unit-test path does where the IDE asks) and has a name of its own, since the
 * plugin's dialog leaves the name to the user.
 * Introduce Type Alias, which the handler takes for a selected type, is not offered.
 *
 * Java sources go the Java plugin's ModCommand and headless routes, which compute their changes without touching the
 * file, so they are performed on a preview copy (see [[performJava]]). Extract variable offers each choice of
 * occurrences the IDE's chooser lists, with the IDE's description, and names the variable as the IDE suggests.
 * Java's Extract method is not offered: the Java plugin's analysis of the selection reads the default visibility from
 * its settings dialog's class (`ExtractMethodDialog.getDefaultVisibility`), whose superclass is the platform's dialog,
 * which the host does not have.
 */
object Extract {
  /** The refactorings a selection can be offered, which a client names by `id` when it asks for one. */
  sealed abstract class Refactoring(val id: String)

  /** The refactorings, which are all but one without parameters. */
  object Refactoring {
    /** Introduces a variable for the selected expression and replaces all its occurrences. */
    case object IntroduceAll extends Refactoring("introduceVariable.all")

    /** Introduces a variable for the selected expression and replaces this occurrence only. */
    case object IntroduceOne extends Refactoring("introduceVariable.one")

    /** Extracts the selected statements into a method. */
    case object Method extends Refactoring("extractMethod")

    /**
     * Introduces a variable for the selected Java expression and replaces the occurrences of the IDE's choice at
     * `index`, in the order the IDE's chooser lists them (this occurrence only first, all of them last).
     *
     * @param index position of the choice among those the Java plugin offers for the expression
     */
    final case class IntroduceChoice(index: Int) extends Refactoring(s"$ChoicePrefix$index")

    /** The identifier of an [[IntroduceChoice]] starts with this, and ends with the choice's index. */
    private val ChoicePrefix = "introduceVariable.choice."

    /** The refactorings that have an identifier of their own. */
    val values: Seq[Refactoring] = Seq(IntroduceAll, IntroduceOne, Method)

    /**
     * Finds a refactoring by the identifier a client sends.
     *
     * @param id a refactoring's `id`
     * @return the refactoring; none when no refactoring has that identifier
     */
    def withId(id: String): Option[Refactoring] =
      values.find(_.id == id).orElse {
        Option.when(id.startsWith(ChoicePrefix))(id.drop(ChoicePrefix.length).toIntOption).flatten
          .filter(_ >= 0).map(IntroduceChoice(_))
      }
  }

  /**
   * A refactoring that a selection offers.
   *
   * @param title user-facing action name
   * @param refactoring what choosing the action performs
   */
  final case class Offer(title: String, refactoring: Refactoring)

  /**
   * What a performed refactoring changed.
   *
   * @param edits replacements against the file's text before the refactoring
   * @param name position of the name the refactoring introduced, in the text the edits produce
   */
  final case class Result(edits: Seq[ChangeEngine.Edit], name: LineColumn)

  /** The name the plugin's unit-test path gives an extracted method, which [[extractMethod]] replaces. */
  private val TestMethodName = "testMethodName"

  /** The comments that make the plugin's unit-test path extract an inner or case class for the output. */
  private val TestClassComments = Seq("//case class", "//inner class")

  /**
   * The name an extracted method is given. The plugin's dialog leaves the name empty for the user to type, so there is
   * no prefilled name to take; the client renames this one next.
   */
  private val MethodName = "extracted"

  /** The handler's private `findScopeBound` and `getSiblings`, which list the scopes the new method may go to. */
  private val FindScopeBound = privateHandlerMethod("findScopeBound", classOf[Seq[?]])
  private val GetSiblings = privateHandlerMethod("getSiblings", classOf[PsiElement], classOf[PsiElement])

  /** Matches [[TestMethodName]] as a whole word. */
  private val TestMethodNameWord = s"\\b$TestMethodName\\b".r

  // The handlers ask where the IDE has a dialog or a popup (replace all occurrences? which scope?): unit-test mode is
  // their way of answering with the defaults, and the host has no UI to ask.
  /** Whether the current thread is inside `withDefaultAnswers`. */
  private val answeringDialogs = ThreadLocal.withInitial(() => false)

  /**
   * Tells whether the handlers on the current thread should take their unit-test path instead of requesting UI
   * choices.
   *
   * @return `true` only while a refactoring is looked for or performed with the host's default answers
   */
  def isAnsweringDialogs: Boolean = answeringDialogs.get

  /** Temporarily makes the handlers' questions resolve to the IDE's defaults on this thread. */
  private def withDefaultAnswers[A](body: => A): A = {
    val previous = answeringDialogs.get
    answeringDialogs.set(true)
    try body
    finally answeringDialogs.set(previous)
  }

  /**
   * Finds the refactorings a selection offers, as far as the handlers' checks before their dialogs tell: Introduce
   * Variable for an expression that the handler accepts, with a refactoring for this occurrence only when it has
   * others, and Extract Method for statements that the handler does not refuse for what they contain.
   *
   * The caller holds PSI read access and the file is unchanged: the expression is looked up on a copy, since the
   * handler's lookup inserts parentheses into the document for a moment. Extract Method may still refuse when it
   * performs, where the handler finds no scope to extract to; the plugin's check for that is private.
   *
   * A Java file's offers are those of the Java plugin's services, which look at the file without changing it; the
   * choices of Extract variable are the IDE's, in its order (see [[Refactoring.IntroduceChoice]]).
   *
   * @param file Scala or Java source being queried
   * @param selection selected UTF-16 range, not empty
   * @return the offered refactorings, in the order Extract to val (all occurrences, this one) and Extract method for
   *         Scala, Extract variable (a choice each) and Extract method for Java; empty for a selection that no
   *         handler accepts
   */
  def offers(file: ScalaFile | PsiJavaFile, selection: TextRange): Seq[Offer] = file match {
    case scala: ScalaFile => scalaOffers(scala, selection)
    case java: PsiJavaFile => javaOffers(java, selection)
  }

  /** The offers of a Scala file, as [[offers]] describes them. */
  private def scalaOffers(file: ScalaFile, selection: TextRange): Seq[Offer] = {
    val introductions = introducible(file, selection).toSeq.flatMap { occurrences =>
      if (occurrences > 1)
        Seq(
          Offer(s"Extract to val, replacing all $occurrences occurrences", Refactoring.IntroduceAll),
          Offer("Extract to val, this occurrence only", Refactoring.IntroduceOne))
      else Seq(Offer("Extract to val", Refactoring.IntroduceAll))
    }

    introductions ++ Option.when(mayExtractMethod(file, selection))(Offer("Extract method", Refactoring.Method))
  }

  /** The offers of a Java file, as [[offers]] describes them. */
  private def javaOffers(file: PsiJavaFile, selection: TextRange): Seq[Offer] =
    JavaIntroduceVariableModCommandService.getInstance.getContext(file, selection) match {
      case available: ToVariableContext.Available =>
        available.choices.asScala.toSeq match {
          case Seq(only) => Seq(Offer("Extract variable", Refactoring.IntroduceChoice(only.index)))
          case choices =>
            choices.map(choice => Offer(s"Extract variable: ${choice.description}", Refactoring.IntroduceChoice(choice.index)))
        }
      case _: ToVariableContext.Error => Seq.empty
    }

  /**
   * Performs a refactoring on the file as the handler does without its dialog. The handler changes the file, and the
   * caller must put its text back: this does not.
   *
   * The caller holds the write lock on the host's dispatch thread, which the handlers insist on, and the file's PSI
   * is committed. The changes are committed and formatted before this returns.
   *
   * @param file physical Scala source to change
   * @param selection selected UTF-16 range, not empty
   * @param refactoring the refactoring to perform
   * @return the offset, in the changed text, of the name that the refactoring introduced; none when the handler
   *         refuses, in which case the file is unchanged
   */
  def perform(file: ScalaFile, selection: TextRange, refactoring: Refactoring): Option[Int] = withDefaultAnswers {
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, selection.getStartOffset)) {
      request =>
        request.editor.getSelectionModel.setSelection(selection.getStartOffset, selection.getEndOffset)

        try refactoring match {
          case Refactoring.Method => extractMethod(file, request.editor)
          case Refactoring.IntroduceAll => introduceVariable(file, selection, request.editor, replaceAll = true)
          case Refactoring.IntroduceOne => introduceVariable(file, selection, request.editor, replaceAll = false)
          // The choices are the Java plugin's: a Scala file has no choice of that index to perform.
          case _: Refactoring.IntroduceChoice => None
        } catch {
          // In unit-test mode the handlers' error hint is an exception.
          case _: RefactoringErrorHintException => None
        }
    }
  }

  /** The selection the handler works on: without the white space and comments around it. */
  private def trimmed(file: ScalaFile, selection: TextRange): TextRange = {
    val (start, end) = ScalaRefactoringUtil.trimSelectionOffsets(
      file, selection.getStartOffset, selection.getEndOffset, trimComments = true)

    new TextRange(start, end)
  }

  /** How many occurrences of the expression the selection is the handler finds, when it accepts the selection. */
  private def introducible(file: ScalaFile, selection: TextRange): Option[Int] = {
    val target = trimmed(file, selection)

    if (target.isEmpty) None
    else ChangeEngine.run(file) { copy =>
      val editor = IntentionPreviewUtils.getPreviewEditor

      for {
        (expression, _) <- ScalaRefactoringUtil.getExpressionWithTypes(copy, editor.getDocument, target.getStartOffset,
          target.getEndOffset)(using copy.getProject)
        if ScalaRefactoringUtil.cannotBeIntroducedReason(expression).isEmpty
        encloser <- ScalaRefactoringUtil.fileEncloser(copy, target.getStartOffset)
      } yield ScalaRefactoringUtil.getOccurrenceRanges(expression, encloser).size
    }._2
  }

  /**
   * Introduces a variable for the selected expression as the handler does in its unit-test path, which takes the
   * choice of occurrences and the name from the data context.
   *
   * The handler does not say which declaration is new: it is the first one named as the handler suggested that the
   * file did not have.
   */
  private def introduceVariable(file: ScalaFile, selection: TextRange, editor: Editor, replaceAll: Boolean)
      : Option[Int] = {
    val project = file.getProject
    val target = trimmed(file, selection)
    val handler = new ScalaIntroduceVariableHandler
    val name = handler
      .suggestedNamesForExpression(file, target.getStartOffset, target.getEndOffset)(using project, editor).head
    val options = ReplaceTestOptions(definitionName = Some(name), replaceAllOccurrences = Some(replaceAll))
    // The platform's simple data context is in a module the host does not have; a context is a lookup by key name.
    val data: DataContext = key =>
      if (key == ScalaIntroduceVariableHandler.ForcedReplaceTestOptions.getName) options else null
    val existing = declarationsNamed(file, name).toSet

    handler.invokeExpression(file, target.getStartOffset, target.getEndOffset)(using project, editor, data)
    settle(file)

    val introduced = declarationsNamed(file, name).find(!existing(_))
      .getOrElse(throw new IllegalStateException(s"no new declaration of $name in ${file.getName}"))

    Some(introduced.nameId.getTextRange.getStartOffset)
  }

  /**
   * Extracts the selected statements into a method, as the handler does in its unit-test path, and names it
   * `MethodName` where the handler wrote its test name.
   *
   * @return the offset of the first of the method's name and its call; none when the file has a name or a comment
   *         that the unit-test path treats specially
   */
  private def extractMethod(file: ScalaFile, editor: Editor): Option[Int] =
    if (usesTestNames(file.getText)) None
    else {
      new ScalaExtractMethodHandler().invoke(file.getProject, editor, file, DataContext.EMPTY_CONTEXT)
      settle(file)

      val document = file.getViewProvider.getDocument
      val named = TestMethodNameWord.findAllMatchIn(document.getImmutableCharSequence).toSeq

      // From the last occurrence to the first, so that a replacement does not move those before it.
      named.reverseIterator.foreach(found => document.replaceString(found.start, found.end, MethodName))
      PsiDocumentManager.getInstance(file.getProject).commitDocument(document)

      named.headOption.map(_.start)
    }

  /** Whether the text makes the plugin's unit-test path pick something other than the IDE's defaults. */
  private def usesTestNames(text: String): Boolean =
    text.contains(TestMethodName) || TestClassComments.exists(text.startsWith)

  /**
   * Whether the handler would go on to extract the selection: it is statements it accepts, as its checks before the
   * scope and the dialog say.
   */
  private def mayExtractMethod(file: ScalaFile, selection: TextRange): Boolean =
    !usesTestNames(file.getText) && withDefaultAnswers {
      Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, selection.getStartOffset)) {
        request =>
          val editor = request.editor
          editor.getSelectionModel.setSelection(selection.getStartOffset, selection.getEndOffset)

          try {
            val elements = ScalaRefactoringUtil.selectedElements(editor, file, trimComments = false)

            !ExtractMethodChecks.refuses(elements)(using file.getProject, editor) &&
              !targetsLastIndentedMember(file, elements)
          } catch {
            case _: RefactoringErrorHintException => false
          }
      }
    }

  /**
   * Whether the scope the handler picks for the new method is a member of a class body that has nothing after it, as
   * the last member of a Scala 3 body without braces has. The handler inserts the method before the member's next
   * sibling and fails on its absence (the IDE does too); a braced body has its closing brace there.
   *
   * The handler picks the only scope there is, or else the innermost one that is a class-body member, or else the
   * outermost one, as its unit-test path and its IDE chooser's preselection do.
   */
  private def targetsLastIndentedMember(file: ScalaFile, elements: Seq[PsiElement]): Boolean = {
    val handler = new ScalaExtractMethodHandler
    // The results are a Scala `Option` and `Seq` of PSI elements, as the private methods declare.
    val bound = invoke(FindScopeBound, handler, elements).asInstanceOf[Option[PsiElement]].getOrElse(file)
    val scopes = invoke(GetSiblings, handler, elements.head, bound).asInstanceOf[Seq[PsiElement]]
    val inClassBody = (scope: PsiElement) => scope.getParent.isInstanceOf[ScTemplateBody]
    val target = scopes match {
      case Seq(only) => Some(only)
      case _ => scopes.findLast(inClassBody).orElse(scopes.headOption)
    }

    target.exists(scope => inClassBody(scope) && scope.getNextSiblingNotWhitespaceComment == null)
  }

  /** A private method of the handler, made callable. */
  private def privateHandlerMethod(name: String, parameters: Class[?]*): Method = {
    val method = classOf[ScalaExtractMethodHandler].getDeclaredMethod(name, parameters*)
    method.setAccessible(true)
    method
  }

  /** Calls a reflected method, rethrowing what the method itself threw. */
  private def invoke(method: Method, target: AnyRef, arguments: AnyRef*): AnyRef =
    try method.invoke(target, arguments*)
    catch { case error: InvocationTargetException => throw error.getCause }

  /** Applies the formatting that the handler postponed and commits the document, so the PSI and the text agree. */
  private def settle(file: PsiFile): Unit = {
    val documents = PsiDocumentManager.getInstance(file.getProject)
    val document = file.getViewProvider.getDocument

    documents.doPostponedOperationsAndUnblockDocument(document)
    documents.commitDocument(document)
  }

  /** The bindings named `name` in the file, in document order. */
  private def declarationsNamed(file: ScalaFile, name: String): Seq[ScBindingPattern] =
    file.depthFirst().collect { case binding: ScBindingPattern if binding.name == name => binding }.toSeq

  /**
   * Performs a refactoring on a Java file as the IDE's headless routes do, on a preview copy: the file itself is
   * unchanged, and nothing is written.
   *
   * The caller holds PSI read access and the file is unchanged since `selection` was read.
   *
   * @param file Java source to change
   * @param selection selected UTF-16 range, not empty
   * @param refactoring the refactoring to perform, as [[offers]] offered it
   * @return the edits against the file's text and the position of the name that the refactoring introduced, in the
   *         text they produce; none when the plugin refuses the selection, or when `refactoring` is a Scala one
   */
  def performJava(file: PsiJavaFile, selection: TextRange, refactoring: Refactoring): Option[Result] =
    refactoring match {
      case Refactoring.IntroduceChoice(index) => introduceJavaVariable(file, selection, index)
      // Java has no Extract method here (see the object's description), and Scala's choices are not Java's.
      case Refactoring.Method | Refactoring.IntroduceAll | Refactoring.IntroduceOne => None
    }

  /**
   * Introduces a variable for the selected expression with the command the Java plugin builds for its choice of
   * occurrences, and takes the new name's place from the rename that command asks the editor to start.
   *
   * The command is built on the preview copy's session, which makes the copies its updates work on listened to (see
   * [[ChangeEngine.copyForModCommand]]); it is executed there but for the rename, which a copy cannot take.
   */
  private def introduceJavaVariable(file: PsiJavaFile, selection: TextRange, index: Int): Option[Result] = {
    val service = JavaIntroduceVariableModCommandService.getInstance
    val (changed, name) = ChangeEngine.runIntention(file) { copy =>
      val expression =
        IntroduceVariableUtil.findExpressionInRange(file.getProject, file, selection.getStartOffset, selection.getEndOffset)

      service.getContext(expression) match {
        // The choices are those of the file's current text: an index beyond them is a stale request.
        case analysis: ToVariableContext.Available if index < analysis.choices.size =>
          val context = ActionContext.from(null, file).withSelection(selection)
          // The command finds the expression again in each copy of the file it updates, from the original's.
          val site: IntroduceSite = updater => ElementToWorkOn.getWritable(expression, updater)
          val command = service.introduceVariableCommand(context, site, analysis, null) match {
            // Several choices come as a chooser, which the IDE shows; the choice's action is the command to run.
            case chooser: ModChooseAction => chooser.actions.get(index).perform(context)
            case single => single
          }

          ChangeEngine.applyModCommand(command, copy)
            .map(rename => Option(rename.symbolRange.nameIdentifierRange).getOrElse(rename.symbolRange.range))
        case _ => None
      }
    }

    name.map(range => Result(changed.edits, lineColumn(changed.text, range.getStartOffset)))
  }

  /** The line and column of an offset of a text. */
  private def lineColumn(text: String, offset: Int): LineColumn = {
    val at = StringUtil.offsetToLineColumn(text, offset)

    LineColumn(at.line, at.column)
  }
}
