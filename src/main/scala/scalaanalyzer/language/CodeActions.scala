package scalaanalyzer.language

import com.intellij.codeInsight.daemon.impl.quickfix.{ImplementMethodsFix, ImportClassFixBase}
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.{IntentionPreviewInfo, IntentionPreviewUtils}
import com.intellij.codeInsight.unwrap.UnwrapDescriptor
import com.intellij.lang.ImportOptimizer
import com.intellij.lang.java.JavaImportOptimizer
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.modcommand.{ActionContext, ModChooseAction, ModCommandAction, ModCommandExecutor}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiFile, PsiJavaFile}
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.annotator.quickfix.ImplementMembersQuickFix
import org.jetbrains.plugins.scala.autoImport.quickFix.{MemberToImport, PrefixPackageToImport, ScalaImportTypeFix}
import org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.ScalaOptimizeImportsFix
import org.jetbrains.plugins.scala.editor.importOptimizer.ScalaImportOptimizer
import org.jetbrains.plugins.scala.lang.psi.ScImportsHolder
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.ScReference

import scalaanalyzer.Diagnostic
import scalaanalyzer.platform.{HostLock, HostLog, RequestEditor}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Preview-backed code actions and import fixes exposed by the host. */
object CodeActions {
  /** Intentions that require the desktop rename UI even to check availability. */
  private val UnavailableIntentions = Set(
    "org.jetbrains.plugins.scala.codeInsight.intention.declarations.ChangeAccessModifierIntention",
  )

  /**
   * Java intentions left out of the lightbulb: each needs a dialog, popup, editor template or inline refactoring that
   * the headless host cannot show, or acts on something other than the previewed file.
   */
  private val UnavailableJavaIntentions = Set(
    // Dialogs and popups.
    "com.intellij.codeInsight.intention.impl.CreateSubclassAction", // dialog, then a template
    "com.intellij.testIntegration.createTest.CreateTestAction", // test-creation dialog
    "com.intellij.testIntegration.createTest.GenerateMissedTestsAction", // popup of the missing test methods
    "com.siyeh.ipp.modifiers.ChangeModifierIntention", // popup of the modifiers
    "com.intellij.refactoring.replaceConstructorWithBuilder.ReplaceConstructorWithBuilderAction", // refactoring dialog
    "com.intellij.codeInsight.intention.ReplaceConstructorWithFactoryAction", // asks for the target through a chooser
    // Refactorings that run their handler on the editor.
    "com.intellij.codeInsight.intention.impl.ExtractMethodIntentionAction", // JavaRefactoringService.extractMethod
    "com.intellij.codeInsight.intention.impl.IntroduceVariableIntentionAction", // introduce-variable handler
    "com.intellij.codeInsight.intention.impl.EncapsulateFieldAction", // encapsulate-fields handler and dialog
    // Templates started on the editor.
    "com.intellij.codeInsight.daemon.impl.quickfix.IterateOverIterableIntention", // InvokeTemplateAction
    "com.intellij.codeInsight.daemon.impl.quickfix.CreateLocalVarFromInstanceofAction", // startTemplate
    // External annotations, which choose or ask for an annotation root and edit files other than the previewed one.
    "com.intellij.codeInsight.intention.impl.AnnotateIntentionAction",
    "com.intellij.codeInsight.intention.impl.DeannotateIntentionAction",
    "com.siyeh.ipp.annotation.AnnotateOverriddenMethodsIntention",
    // Writes the real file with its own write command later, instead of editing the preview copy.
    "com.intellij.codeInsight.MakeInferredAnnotationExplicit",
    // Edits the project's auto-import settings, not the file.
    "com.intellij.codeInsight.intention.impl.AddOnDemandStaticImportToAutoImportAction",
  )

  /** One unwrapper of a descriptor, by its position in the descriptor's list, with the title the popup gives it. */
  private final case class Unwrapping(descriptor: UnwrapDescriptor, index: Int, title: String)

  /** Lazily created plugin action, the LSP action kind used for its response and the problems it is a fix for. */
  private final case class Candidate(
    name: String,
    create: () => IntentionAction,
    kind: String,
    problems: Seq[Diagnostic] = Seq.empty,
  )

  /**
   * A previewed action and its availability.
   *
   * @param title user-facing action name
   * @param kind hierarchical action category
   * @param edits replacements against the original file
   * @param disabled reason the preview cannot be applied, or none when available
   * @param problems the problems whose fix or suppression this is; empty for an action that is not about a problem
   */
  final case class Choice(
    title: String,
    kind: String,
    edits: Seq[ChangeEngine.Edit],
    disabled: Option[String],
    problems: Seq[Diagnostic] = Seq.empty,
  )

  /**
   * An Implement members action that is offered but not previewed yet: its preview runs on the dispatch thread, which
   * the code that collects the actions must not wait for while it reads.
   *
   * @param title user-facing action name
   * @param kind `quickfix` when the annotator's fix is offered, `refactor.rewrite` for the action of a class without a
   *             problem
   * @param problems the problems that the fix fixes; empty for the action
   */
  final case class ImplementMembers(title: String, kind: String, problems: Seq[Diagnostic])

  /**
   * The actions of a range: the previewed ones, with Implement members and the extractions apart, since those are not
   * previewed with the rest: the first on its own, the others performed only when the user chooses them.
   *
   * @param choices the previewed actions
   * @param implementMembers Implement members when the range offers it, not previewed yet
   * @param extractions the extractions the range offers, not performed
   */
  final case class Offered(choices: Seq[Choice], implementMembers: Option[ImplementMembers],
      extractions: Seq[Extract.Offer])
}

/**
 * Previews intentions, the fixes of problems (the plugin's annotator and inspection fixes for Scala files, the Java
 * plugin's error and inspection fixes for Java files), inlinings, and for Scala files surround and unwrap actions, as
 * source edits.
 *
 * Callers hold PSI read access. Actions run against copies through the platform's preview contract;
 * unsupported previews are disabled with a reason when the action is known to be available.
 *
 * @param scalaIntentionClasses Scala intention implementation names: the plugin descriptors' and the platform's that
 *                              act through the plugin's extensions; offered in Scala files
 * @param javaIntentionClasses Java intention implementation names from the Java plugin's descriptors; offered in Java
 *                             files
 * @param descriptorClasses surround-descriptor implementation names from the Scala plugin's descriptors
 * @param unwrapClasses unwrap-descriptor implementation names from the Scala plugin's descriptors
 * @param javaDescriptorClasses the Java plugin's surround descriptors for Java
 * @param javaUnwrapClasses the Java plugin's unwrap descriptors for Java
 */
final class CodeActions(scalaIntentionClasses: Seq[String], javaIntentionClasses: Seq[String],
    descriptorClasses: Seq[String], unwrapClasses: Seq[String], javaDescriptorClasses: Seq[String],
    javaUnwrapClasses: Seq[String]) {
  import CodeActions.{Candidate, Choice, ImplementMembers, Unwrapping}

  /**
   * Previews the language's import optimizer on a file copy: the plugin's for a Scala file, the platform's for a Java
   * file.
   *
   * @param file Scala or Java file whose imports should be organized
   * @return the organize-imports action with edits against the original
   * @throws IllegalArgumentException when `file` is neither Scala nor Java
   */
  def organizeImports(file: PsiFile): Choice = {
    val (result, _) = withoutLoggedErrors(ChangeEngine.run(file)(copy => importOptimizer(copy).processFile(copy).run()))
    Choice("Organize imports", "source.organizeImports", result.edits, None)
  }

  /** The import optimizer of a file's language. */
  private def importOptimizer(file: PsiFile): ImportOptimizer = file match {
    case _: ScalaFile => new ScalaImportOptimizer
    case _: PsiJavaFile => new JavaImportOptimizer
    case other => throw new IllegalArgumentException(s"no import optimizer for ${other.getClass.getName}")
  }

  /**
   * Collects previewed intentions for a source range, and the fixes of the problems it touches, and the inlinings at
   * its start; for a Scala file also import candidates, surround actions and unwrappers. A Java file's unresolved class reference gets one
   * import candidate per class its import fix lists.
   *
   * Unusable previews are returned disabled when availability was established. Cancellation propagates;
   * failures inside an action's availability or preview check are logged before continuing with other choices.
   *
   * Implement members is not among them: its insertion needs the dispatch thread, which a caller holding the read lock
   * must not wait for. See [[implementMembersOffer]] and [[implementMembers]]. Nor are the extractions
   * ([[extractionOffers]]).
   *
   * @param file Scala or Java file being queried; a file of another language has no actions
   * @param range selected UTF-16 range, empty for a caret request
   * @param problems current diagnostics carrying fixes
   * @return distinct action choices with edits against the original file
   */
  def collect(file: PsiFile, range: TextRange, problems: Seq[Diagnostic]): Seq[Choice] =
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, range.getStartOffset)) {
      request =>
        val editor = request.editor
        editor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)

        file match {
          case scalaFile: ScalaFile => collectScala(scalaFile, range, problems, editor)
          case javaFile: PsiJavaFile =>
            val fixes = javaFixCandidates(javaFile, range, editor, problems)
            merged(intentionChoices(file, range, editor, fixes ++ intentionCandidates(file))) ++
              surrounded(javaFile, range, javaDescriptorClasses) ++
              unwrapped(javaFile, range, editor, javaUnwrapClasses) ++ inlinings(javaFile, range)
          case _ => merged(intentionChoices(file, range, editor, intentionCandidates(file)))
        }
    }

  /**
   * One choice per action, as the IDE's lightbulb lists them: problems that offer the same fix (an unknown type and
   * its unresolved name in Java; Optimize imports for each unused import) become one choice fixing all of them.
   */
  private def merged(choices: Seq[Choice]): Seq[Choice] = {
    val byAction = mutable.LinkedHashMap.empty[(String, String, Seq[ChangeEngine.Edit], Option[String]), Choice]

    for (choice <- choices)
      byAction.updateWith((choice.title, choice.kind, choice.edits, choice.disabled)) {
        case Some(seen) => Some(seen.copy(problems = (seen.problems ++ choice.problems).distinct))
        case None => Some(choice)
      }

    byAction.values.toSeq
  }

  /** The annotator fixes, intentions, import candidates, surround actions, unwrappers and inlinings of a Scala file. */
  private def collectScala(file: ScalaFile, range: TextRange, problems: Seq[Diagnostic], editor: Editor)
      : Seq[Choice] = {
    val fixes = fixCandidates(range, problems).filterNot(isImplementMembersFix)
    val choices = intentionChoices(file, range, editor, fixes ++ intentionCandidates(file))

    merged(
      typeImports(file, range, problems) ++ choices ++ surrounded(file, range, descriptorClasses) ++
        unwrapped(file, range, editor, unwrapClasses) ++ inlinings(file, range))
  }

  /** The registered intentions of the file's language, as candidates; none for a file of another language. */
  private def intentionCandidates(file: PsiFile): Seq[Candidate] = {
    val classes = file match {
      case _: ScalaFile => scalaIntentionClasses.filterNot(CodeActions.UnavailableIntentions)
      case _: PsiJavaFile => javaIntentionClasses.filterNot(CodeActions.UnavailableJavaIntentions)
      case _ => Seq.empty
    }

    classes.map(name => Candidate(name, () => newIntention(name), "refactor.rewrite"))
  }

  /**
   * Previews each available candidate on a copy of the file, with the selection of `range`; one that is not available
   * is left out, and one whose preview fails is disabled.
   */
  private def intentionChoices(file: PsiFile, range: TextRange, editor: Editor, candidates: Seq[Candidate])
      : Seq[Choice] = {
    val project = file.getProject

    candidates.flatMap { candidate =>
      var title = candidate.name.split('.').last
      var available = false
      try {
        val action = candidate.create()
        if (!action.isAvailable(project, editor, file)) None
        else {
          available = true
          title = action.getText
          val (result, info) = previewIntention(file) { copy =>
            val previewEditor = IntentionPreviewUtils.getPreviewEditor
            previewEditor.getCaretModel.moveToOffset(range.getStartOffset)
            previewEditor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)
            applyIntention(action, file, copy, editor, previewEditor)
          }
          val supported = info == IntentionPreviewInfo.DIFF || info == IntentionPreviewInfo.DIFF_NO_TRIM
          Some(Choice(title, candidate.kind, if (supported) result.edits else Seq.empty,
            Option.when(!supported)("This action does not provide an applicable text preview."), candidate.problems))
        }
      } catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) =>
          HostLog.warn(s"code action $title could not be previewed", e)
          Option.when(available)(Choice(title, candidate.kind, Seq.empty,
            Some("This action is unavailable in the headless host."), candidate.problems))
      }
    }
  }

  /** Applies an intention to its preview `copy`, through the ModCommand pipeline when it is one. */
  private def applyIntention(action: IntentionAction, original: PsiFile, copy: PsiFile, editor: Editor,
      previewEditor: Editor): IntentionPreviewInfo = {
    val commandAction = action.asModCommandAction()

    def generic: IntentionPreviewInfo =
      if (commandAction == null) action.generatePreview(original.getProject, previewEditor, copy)
      else {
        val command = commandAction.perform(ActionContext.from(editor, original))
        ModCommandExecutor.getInstance.executeForFileCopy(command, copy)
        IntentionPreviewInfo.DIFF
      }

    copy match {
      // The desktop import fix delegates to a UI processor and supplies no preview; reuse our optimizer path.
      case _: ScalaFile if action.isInstanceOf[ScalaOptimizeImportsFix] =>
        new ScalaImportOptimizer().processFile(copy).run()
        IntentionPreviewInfo.DIFF
      case scalaCopy: ScalaFile => Inspections.previewFix(action, scalaCopy).getOrElse(generic)
      case _ => generic
    }
  }

  /** The annotator fixes of the problems that the range touches. */
  private def fixCandidates(range: TextRange, problems: Seq[Diagnostic]): Seq[Candidate] =
    problems.flatMap(problem => problem.fixes.map(fix => problem -> fix)).collect {
      case (problem, (fixRange, fix)) if fixRange.intersects(range) || fixRange.containsOffset(range.getStartOffset) =>
        Candidate(fix.getClass.getName, () => fix, "quickfix", Seq(problem))
    }

  /**
   * The error fixes of a Java file's problems that the range touches. An import fix is replaced by a candidate per
   * class it can import, as the chooser of the IDE lists them (see [[importCandidates]]). A fix that fails to
   * create or to list its classes is logged and dropped alone, as the other code actions of the range remain.
   */
  private def javaFixCandidates(file: PsiJavaFile, range: TextRange, editor: Editor, problems: Seq[Diagnostic])
      : Seq[Candidate] =
    fixCandidates(range, problems).flatMap { candidate =>
      try candidate.create() match {
        case imports: ImportClassFixBase[?, ?] => importCandidates(imports, candidate, ActionContext.from(editor, file))
        case _ => Seq(candidate)
      } catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) =>
          HostLog.warn(s"code action ${candidate.name} could not be offered", e)
          Seq.empty
      }
    }

  /**
   * One candidate per class that an import fix offers: the steps of the chooser its ModCommand fallback performs,
   * which is what the IDE runs when the user picks a class. With a single class the fallback imports it without a
   * chooser, and is itself the one candidate.
   */
  private def importCandidates(fix: ImportClassFixBase[?, ?], candidate: Candidate, context: ActionContext)
      : Seq[Candidate] = {
    val fallback = fix.getFallbackModCommandAction

    fallback.perform(context) match {
      case chooser: ModChooseAction =>
        chooser.actions.asScala.toSeq.map { step =>
          candidate.copy(name = step.getClass.getName, create = () => step.asIntention())
        }
      case _ => Seq(candidate.copy(create = () => fallback.asIntention()))
    }
  }

  /** Whether the candidate is the Scala plugin's annotator fix for Implement members or the Java plugin's error fix. */
  private def isImplementMembersFix(candidate: Candidate): Boolean =
    Set(classOf[ImplementMembersQuickFix], classOf[ImplementMethodsFix]).exists(_.getName == candidate.name)

  /**
   * Offers each surrounder that the descriptors find applicable to the selection, as the IDE's Surround With popup
   * lists them, previewed on a copy.
   *
   * @param classes surround-descriptor implementations of the file's language
   */
  private def surrounded(file: PsiFile, range: TextRange, classes: Seq[String]): Seq[Choice] = {
    val project = file.getProject

    if (range.isEmpty) Seq.empty else classes.flatMap { name =>
      // `classes` holds surround-descriptor implementations registered in the plugins' descriptors.
      val descriptor = Class.forName(name).getConstructor().newInstance().asInstanceOf[SurroundDescriptor]
      val elements = descriptor.getElementsToSurround(file, range.getStartOffset, range.getEndOffset)
      if (elements.isEmpty) Seq.empty else descriptor.getSurrounders.toSeq.flatMap { surrounder =>
        try {
          if (!surrounder.isApplicable(elements)) None
          else {
            val (result, _) = previewFile(file) { copy =>
              val selected = descriptor.getElementsToSurround(copy, range.getStartOffset, range.getEndOffset)
              val previewEditor = IntentionPreviewUtils.getPreviewEditor
              previewEditor.getCaretModel.moveToOffset(range.getStartOffset)
              previewEditor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)
              surrounder.surroundElements(project, previewEditor, selected)
            }
            Some(Choice(surrounder.getTemplateDescription, "refactor.rewrite", result.edits, None))
          }
        } catch {
          case e: ProcessCanceledException => throw e
          case e @ (NonFatal(_) | _: LinkageError) =>
            HostLog.warn(s"surround action ${surrounder.getClass.getName} could not be previewed", e)
            Some(Choice(surrounder.getTemplateDescription, "refactor.rewrite", Seq.empty,
              Some("This action is unavailable in the headless host.")))
        }
      }
    }
  }

  /** Creates a registered intention; a ModCommand action is wrapped as the intention the platform registers it as. */
  private def newIntention(name: String): IntentionAction =
    // `intentionClasses` holds intention implementations: IntentionActions or ModCommandActions.
    Class.forName(name).getConstructor().newInstance() match {
      case action: IntentionAction => action
      case command: ModCommandAction => command.asIntention()
      case other => throw new IllegalStateException(s"not an intention: ${other.getClass.getName}")
    }

  /**
   * Offers each unwrapper the descriptors find at the caret, as the IDE's Unwrap/Remove popup lists them.
   *
   * An unwrapper is applied to a copy, found there by its position in the descriptor's list: the copy has the same
   * text, so the list is the same.
   */
  private def unwrapped(file: PsiFile, range: TextRange, editor: Editor, classes: Seq[String]): Seq[Choice] = {
    val project = file.getProject

    classes.flatMap { name =>
      val found =
        try {
          // `classes` holds unwrap-descriptor implementations registered in the plugins' descriptors.
          val descriptor = Class.forName(name).getConstructor().newInstance().asInstanceOf[UnwrapDescriptor]
          val unwrappers = descriptor.collectUnwrappers(project, editor, file).asScala.toSeq

          unwrappers.zipWithIndex.map { case (unwrapper, index) =>
            Unwrapping(descriptor, index, unwrapper.getSecond.getDescription(unwrapper.getFirst))
          }
        } catch {
          case e: ProcessCanceledException => throw e
          case e @ (NonFatal(_) | _: LinkageError) =>
            HostLog.warn(s"unwrap descriptor ${name.split('.').last} could not be looked up", e)
            Seq.empty
        }

      found.map { case Unwrapping(descriptor, index, title) =>
        try {
          val (result, _) = previewFile(file) { copy =>
            val previewEditor = IntentionPreviewUtils.getPreviewEditor
            previewEditor.getCaretModel.moveToOffset(range.getStartOffset)
            val copied = descriptor.collectUnwrappers(project, previewEditor, copy).get(index)
            copied.getSecond.unwrap(previewEditor, copied.getFirst)
          }
          Choice(title, "refactor.rewrite", result.edits, None)
        } catch {
          case e: ProcessCanceledException => throw e
          case e @ (NonFatal(_) | _: LinkageError) =>
            HostLog.warn(s"unwrap action $title could not be previewed", e)
            Choice(title, "refactor.rewrite", Seq.empty, Some("This action is unavailable in the headless host."))
        }
      }
    }
  }

  /**
   * Offers the inlinings at the range's start, each previewed on its own copy; one that the plugin refuses where it
   * is previewed is left out.
   *
   * A failure while looking for them leaves them out; one while previewing leaves the action disabled.
   */
  private def inlinings(file: ScalaFile | PsiJavaFile, range: TextRange): Seq[Choice] = {
    val found =
      try Inline.actions(file, range.getStartOffset)
      catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) =>
          HostLog.warn("inlinings could not be looked up", e)
          Seq.empty
      }

    found.flatMap { action =>
      try {
        val (result, applies) = file match {
          case scala: ScalaFile => preview(scala)(action.perform)
          case java: PsiJavaFile => previewIntention(java)(action.perform)
        }

        Option.when(applies)(Choice(action.title, "refactor.inline", result.edits, None))
      } catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) =>
          HostLog.warn(s"code action ${action.title} could not be previewed", e)
          Some(Choice(action.title, "refactor.inline", Seq.empty, Some("This inlining could not be previewed.")))
      }
    }
  }

  /**
   * The extractions that a non-empty range offers (see [[Extract.offers]]); an extraction is performed only once the
   * user chooses it, for it changes the physical file.
   *
   * A failure while looking for them leaves them out.
   *
   * @param file Scala or Java source being queried
   * @param range UTF-16 range the actions apply to
   * @return the offers; empty for an empty range
   */
  def extractionOffers(file: ScalaFile | PsiJavaFile, range: TextRange): Seq[Extract.Offer] =
    if (range.isEmpty) Seq.empty
    else
      try Extract.offers(file, range)
      catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) =>
          HostLog.warn("extractions could not be looked up", e)
          Seq.empty
      }

  /**
   * Whether Implement members is offered at the range's start: as the annotator's fix when it is available, and
   * otherwise as an action of a class that has abstract members left to implement (an abstract class has no problem to
   * fix). In a Java file the fix of the class's error is one of [[collect]]'s choices, and then this is not offered
   * as well.
   *
   * Callers hold PSI read access. Nothing is previewed (see [[implementMembers]]).
   *
   * @param file Scala or Java file being queried
   * @param range selected UTF-16 range, empty for a caret request
   * @param problems current diagnostics carrying fixes
   * @return the action to preview, or none when the range does not offer it
   */
  def implementMembersOffer(file: ScalaFile | PsiJavaFile, range: TextRange, problems: Seq[Diagnostic])
      : Option[ImplementMembers] =
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, range.getStartOffset)) {
      request =>
      val title = ScalaBundle.message("implement.members.fix")

      request.editor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)

      val fixes = fixCandidates(range, problems).filter(isImplementMembersFix)
        .filter(_.create().isAvailable(file.getProject, request.editor, file))

      file match {
        case _: ScalaFile if fixes.nonEmpty => Some(ImplementMembers(title, "quickfix", fixes.flatMap(_.problems)))
        // The Java plugin's fix is listed by `collect` with its own edits, which are those of this action (its preview
        // inserts the abstract methods through the same call), so the IDE's lightbulb lists the change once.
        case _: PsiJavaFile if fixes.nonEmpty => None
        case _ if MemberOverrides.hasMembersToImplement(file, range.getStartOffset) =>
          Some(ImplementMembers(title, "refactor.rewrite", Seq.empty))
        case _ => None
      }
    }

  /**
   * Previews Implement members, which inserts every member the class at the range's start has to implement, the default
   * of the dialog that the IDE shows.
   *
   * A Scala file's insertion runs on the dispatch thread as a query of its own, so the caller must not hold the read
   * lock (see [[MemberOverrides.implementAll]]). The cancellation of the caller's request propagates. A write that
   * cancels only the preview, such as an edit of another file, is retried once; if it cancels the retry too, the action is
   * disabled rather than failing the caller's other actions. Another failure is logged and disables it too.
   *
   * @param file Scala or Java file the action was collected for
   * @param range the range it was collected for
   * @param offered the action [[implementMembersOffer]] found
   * @return the action with its edits against the current text of `file`
   * @throws ProcessCanceledException when the caller's request is cancelled
   */
  def implementMembers(file: ScalaFile | PsiJavaFile, range: TextRange, offered: ImplementMembers): Choice =
    try {
      def edits = MemberOverrides.implementAll(file, range.getStartOffset).edits
      // An edit of another document cancels the preview without making it wrong, so it gets one more attempt.
      val previewed = try edits catch { case _: ProcessCanceledException if !HostLock.isRequestCancelled => edits }

      Choice(offered.title, offered.kind, previewed, None, offered.problems)
    } catch {
      case e: ProcessCanceledException if HostLock.isRequestCancelled => throw e
      case _: ProcessCanceledException =>
        Choice(offered.title, offered.kind, Seq.empty, Some("An edit interrupted the preview of this action."),
          offered.problems)
      case e @ (NonFatal(_) | _: LinkageError) =>
        HostLog.warn(s"code action ${offered.title} could not be previewed", e)
        Choice(offered.title, offered.kind, Seq.empty, Some("This action is unavailable in the headless host."),
          offered.problems)
    }

  /** Offer each type-import candidate separately; the LSP client replaces the IDE's import chooser. */
  private def typeImports(file: ScalaFile, range: TextRange, problems: Seq[Diagnostic]): Seq[Choice] = {
    val references = problems
      .filter(p => p.range.intersects(range) || p.range.containsOffset(range.getStartOffset))
      .flatMap(p => Option(file.findReferenceAt(p.range.getStartOffset)))
      .collect {
        case reference: ScReference if reference.qualifier.isEmpty && reference.multiResolveScala(false).isEmpty => reference
      }
      .distinct

    references.flatMap { reference =>
      ScalaImportTypeFix.getTypesToImport(reference).map { target =>
        val title = ScalaBundle.message("import.with", target.qualifiedName)
        try {
          val (result, _) = preview(file) { copy =>
            val copied = copy.findReferenceAt(reference.getTextRange.getStartOffset) match {
              case ref: ScReference => ref
              case _ => throw new IllegalStateException("the import reference is missing from the preview copy")
            }
            // The same binding operations as ScalaAddImportAction.ForReference, without its asynchronous chooser.
            target match {
              case PrefixPackageToImport(pack) => copied.bindToPackage(pack, addImport = true)
              case _: MemberToImport => ScImportsHolder(copied).addImportForPath(target.qualifiedName)
              case _ => copied.bindToElement(target.element)
            }
          }
          Choice(title, "quickfix", result.edits, None)
        } catch {
          case e: ProcessCanceledException => throw e
          case e @ (NonFatal(_) | _: LinkageError) =>
            HostLog.warn(s"code action $title could not be previewed", e)
            Choice(title, "quickfix", Seq.empty, Some("This import could not be previewed."))
        }
      }
    }
  }

  /** Previews a transformation of a Scala file copy, checking that it logged no error (see [[withoutLoggedErrors]]). */
  private def preview[A](file: ScalaFile)(modify: ScalaFile => A): (ChangeEngine.Result, A) =
    withoutLoggedErrors(ChangeEngine.run(file)(modify))

  /** Previews a change on a copy of a Scala or Java file, as [[preview]] does for a Scala file. */
  private def previewFile[A](file: PsiFile)(modify: PsiFile => A): (ChangeEngine.Result, A) =
    withoutLoggedErrors(ChangeEngine.run(file)(modify))

  /**
   * Previews an intention on a copy of a Scala or Java file, checking that it logged no error (see
   * [[withoutLoggedErrors]]). The session's editor is the one the IDE previews an intention with (see
   * [[ChangeEngine.runIntention]]).
   */
  private def previewIntention[A](file: PsiFile)(modify: PsiFile => A): (ChangeEngine.Result, A) =
    withoutLoggedErrors(ChangeEngine.runIntention(file)(modify))

  /**
   * Runs a preview and rejects it when the platform logged an error meanwhile, since that can leave a partial preview
   * even when the plugin returns normally. Only errors logged on this thread count: the preview runs on it, while other
   * requests and the diagnostics log on their own threads.
   */
  private def withoutLoggedErrors[A](preview: => (ChangeEngine.Result, A)): (ChangeEngine.Result, A) = {
    val errors = HostLog.threadCount(HostLog.Level.Error)
    val result = preview
    if (HostLog.threadCount(HostLog.Level.Error) != errors)
      throw new UnsupportedOperationException("An error was logged while previewing this action.")
    result
  }
}
