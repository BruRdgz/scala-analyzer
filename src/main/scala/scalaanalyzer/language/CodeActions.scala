package scalaanalyzer.language

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.{IntentionPreviewInfo, IntentionPreviewUtils}
import com.intellij.codeInsight.unwrap.UnwrapDescriptor
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.modcommand.{ActionContext, ModCommandAction, ModCommandExecutor}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
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

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Preview-backed code actions and import fixes exposed by the host. */
object CodeActions {
  /** Intentions that require the desktop rename UI even to check availability. */
  private val UnavailableIntentions = Set(
    "org.jetbrains.plugins.scala.codeInsight.intention.declarations.ChangeAccessModifierIntention",
  )

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
   * The actions of a range: the previewed ones, and Implement members apart, since it is previewed on its own.
   *
   * @param choices the previewed actions
   * @param implementMembers Implement members when the range offers it, not previewed yet
   */
  final case class Offered(choices: Seq[Choice], implementMembers: Option[ImplementMembers])
}

/**
 * Previews plugin intentions, annotator fixes, surround and unwrap actions and inlinings as source edits.
 *
 * Callers hold PSI read access. Actions run against copies through the platform's preview contract;
 * unsupported previews are disabled with a reason when the action is known to be available.
 *
 * @param intentionClasses intention implementation names: the plugin descriptors' and the platform's that act through
 *                         the plugin's extensions
 * @param descriptorClasses surround-descriptor implementation names from plugin descriptors
 * @param unwrapClasses unwrap-descriptor implementation names from plugin descriptors
 */
final class CodeActions(intentionClasses: Seq[String], descriptorClasses: Seq[String], unwrapClasses: Seq[String]) {
  import CodeActions.{Candidate, Choice, ImplementMembers}

  /**
   * Previews the plugin's import optimizer on a file copy.
   *
   * @param file Scala file whose imports should be organized
   * @return the organize-imports action with edits against the original
   */
  def organizeImports(file: ScalaFile): Choice = {
    val (result, _) = preview(file)(copy => new ScalaImportOptimizer().processFile(copy).run())
    Choice("Organize imports", "source.organizeImports", result.edits, None)
  }

  /**
   * Collects previewed fixes, intentions, surround actions and inlinings for a source range.
   *
   * Unusable previews are returned disabled when availability was established. Cancellation propagates;
   * failures inside an action's availability or preview check are logged before continuing with other choices.
   *
   * Implement members is not among them: its insertion needs the dispatch thread, which a caller holding the read lock
   * must not wait for. See [[implementMembersOffer]] and [[implementMembers]].
   *
   * @param file Scala file being queried
   * @param range selected UTF-16 range, empty for a caret request
   * @param problems current diagnostics carrying annotator fixes
   * @return distinct action choices with edits against the original file
   */
  def collect(file: ScalaFile, range: TextRange, problems: Seq[Diagnostic]): Seq[Choice] = {
    val project = file.getProject
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, project, range.getStartOffset)) { request =>
      val editor = request.editor
      val intentions = intentionClasses.filterNot(CodeActions.UnavailableIntentions).map { name =>
        Candidate(name, () => newIntention(name), "refactor.rewrite")
      }
      val fixes = fixCandidates(range, problems).filterNot(isImplementMembersFix)

      editor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)
      val choices = (fixes ++ intentions).flatMap { candidate =>
        var title = candidate.name.split('.').last
        var available = false
        try {
          val action = candidate.create()
          if (!action.isAvailable(project, editor, file)) None
          else {
            available = true
            title = action.getText
            val (result, info) = preview(file, intentionEditor = true) { copy =>
              val previewEditor = IntentionPreviewUtils.getPreviewEditor
              previewEditor.getCaretModel.moveToOffset(range.getStartOffset)
              previewEditor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)
              val commandAction = action.asModCommandAction()
              // The desktop import fix delegates to a UI processor and supplies no preview; reuse our optimizer path.
              if (action.isInstanceOf[ScalaOptimizeImportsFix]) {
                new ScalaImportOptimizer().processFile(copy).run()
                IntentionPreviewInfo.DIFF
              } else Inspections.previewFix(action, copy).getOrElse {
                if (commandAction == null) action.generatePreview(project, previewEditor, copy)
                else {
                  val command = commandAction.perform(ActionContext.from(editor, file))
                  ModCommandExecutor.getInstance.executeForFileCopy(command, copy)
                  IntentionPreviewInfo.DIFF
                }
              }
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

      val surrounded = if (range.isEmpty) Seq.empty else descriptorClasses.flatMap { name =>
        // `descriptorClasses` holds the surround-descriptor implementations registered in the plugin descriptors.
        val descriptor = Class.forName(name).getConstructor().newInstance().asInstanceOf[SurroundDescriptor]
        val elements = descriptor.getElementsToSurround(file, range.getStartOffset, range.getEndOffset)
        if (elements.isEmpty) Seq.empty else descriptor.getSurrounders.toSeq.flatMap { surrounder =>
          try {
            if (!surrounder.isApplicable(elements)) None
            else {
              val (result, _) = preview(file) { copy =>
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

      (typeImports(file, range, problems) ++ choices ++ surrounded ++ unwrapped(file, range, editor) ++
        inlinings(file, range)).distinct
    }
  }

  /** The annotator fixes of the problems that the range touches. */
  private def fixCandidates(range: TextRange, problems: Seq[Diagnostic]): Seq[Candidate] =
    problems.flatMap(problem => problem.fixes.map(fix => problem -> fix)).collect {
      case (problem, (fixRange, fix)) if fixRange.intersects(range) || fixRange.containsOffset(range.getStartOffset) =>
        Candidate(fix.getClass.getName, () => fix, "quickfix", Seq(problem))
    }

  private def isImplementMembersFix(candidate: Candidate): Boolean =
    candidate.name == classOf[ImplementMembersQuickFix].getName

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
  private def unwrapped(file: ScalaFile, range: TextRange, editor: Editor): Seq[Choice] = {
    val project = file.getProject

    unwrapClasses.flatMap { name =>
      // `unwrapClasses` holds the unwrap-descriptor implementations registered in the plugin descriptors.
      val descriptor = Class.forName(name).getConstructor().newInstance().asInstanceOf[UnwrapDescriptor]

      descriptor.collectUnwrappers(project, editor, file).asScala.toSeq.zipWithIndex.map { case (found, index) =>
        val title = found.getSecond.getDescription(found.getFirst)
        try {
          val (result, _) = preview(file) { copy =>
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
   * Offers the inlinings at the range's start, each previewed on its own copy.
   *
   * A failure while looking for them leaves them out; one while previewing leaves the action disabled.
   */
  private def inlinings(file: ScalaFile, range: TextRange): Seq[Choice] = {
    val found =
      try Inline.actions(file, range.getStartOffset)
      catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) =>
          HostLog.warn("inlinings could not be looked up", e)
          Seq.empty
      }

    found.map { action =>
      try {
        val (result, _) = preview(file)(action.perform)
        Choice(action.title, "refactor.inline", result.edits, None)
      } catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) =>
          HostLog.warn(s"code action ${action.title} could not be previewed", e)
          Choice(action.title, "refactor.inline", Seq.empty, Some("This inlining could not be previewed."))
      }
    }
  }

  /**
   * Whether Implement members is offered at the range's start: as the annotator's fix when it is available, and
   * otherwise as an action of a class that has abstract members left to implement (an abstract class has no problem to
   * fix).
   *
   * Callers hold PSI read access. Nothing is previewed (see [[implementMembers]]).
   *
   * @param file Scala file being queried
   * @param range selected UTF-16 range, empty for a caret request
   * @param problems current diagnostics carrying annotator fixes
   * @return the action to preview, or none when the range does not offer it
   */
  def implementMembersOffer(file: ScalaFile, range: TextRange, problems: Seq[Diagnostic]): Option[ImplementMembers] =
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, range.getStartOffset)) {
      request =>
      val title = ScalaBundle.message("implement.members.fix")

      request.editor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)

      val fixes = fixCandidates(range, problems).filter(isImplementMembersFix)
        .filter(_.create().isAvailable(file.getProject, request.editor, file))

      if (fixes.nonEmpty) Some(ImplementMembers(title, "quickfix", fixes.flatMap(_.problems)))
      else if (MemberOverrides.hasMembersToImplement(file, range.getStartOffset))
        Some(ImplementMembers(title, "refactor.rewrite", Seq.empty))
      else None
    }

  /**
   * Previews Implement members, which inserts every member the class at the range's start has to implement, the default
   * of the dialog that the IDE shows.
   *
   * The insertion runs on the dispatch thread as a query of its own, so the caller must not hold the read lock (see
   * [[MemberOverrides.implementAll]]). The cancellation of the caller's request propagates. A write that cancels only
   * the preview, such as an edit of another file, is retried once; if it cancels the retry too, the action is
   * disabled rather than failing the caller's other actions. Another failure is logged and disables it too.
   *
   * @param file Scala file the action was collected for
   * @param range the range it was collected for
   * @param offered the action [[implementMembersOffer]] found
   * @return the action with its edits against the current text of `file`
   * @throws ProcessCanceledException when the caller's request is cancelled
   */
  def implementMembers(file: ScalaFile, range: TextRange, offered: ImplementMembers): Choice =
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

  /**
   * Logged platform failures can leave a partial preview even when the plugin returns normally. Only errors logged on
   * this thread count: the preview runs on it, while other requests and the diagnostics log on their own threads.
   *
   * @param intentionEditor whether the session's editor is the one the IDE previews an intention with (see
   *                        [[ChangeEngine.runIntention]])
   */
  private def preview[A](file: ScalaFile, intentionEditor: Boolean = false)(modify: ScalaFile => A)
      : (ChangeEngine.Result, A) = {
    val errors = HostLog.threadCount(HostLog.Level.Error)
    val result =
      if (intentionEditor) ChangeEngine.runIntention(file)(modify) else ChangeEngine.run(file)(modify)
    if (HostLog.threadCount(HostLog.Level.Error) != errors)
      throw new UnsupportedOperationException("An error was logged while previewing this action.")
    result
  }
}
