package scalaanalyzer.language

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile}
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.{PsiElementRenameHandler, RenamePsiElementProcessor, RenameUtil}
import com.intellij.refactoring.util.{MoveRenameUsageInfo, RelatedUsageInfo}
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

import scalaanalyzer.platform.SourceFile

import java.util

import scala.jdk.CollectionConverters.*

/**
 * Plans and performs project-symbol renames using the plugin's processors.
 *
 * Processors choose related elements and references as in the IDE. Conflicts refuse the rename; dialog choices use
 * deterministic test defaults. Callers coordinate read/write access and restore physical files when planning edits.
 * Optional automatic renames (variables named after a class, overloads, parameters of overriders) and renames of
 * files are left out.
 */
object Rename {
  /**
   * Explains why a rename was refused.
   *
   * @param reasons platform validation messages or host scope restrictions
   */
  final class Refused(val reasons: Seq[String]) extends RuntimeException(reasons.mkString("\n"))

  /**
   * The elements and references selected for a rename.
   *
   * The plan holds live PSI and must remain valid until performed under the caller's write coordination.
   *
   * @param project owning project
   * @param renames related declarations and their new names in processor order
   * @param usages references selected by the processors
   */
  final class Plan private[Rename] (val project: Project,
                                    val renames: util.LinkedHashMap[PsiElement, String],
                                    val usages: Array[UsageInfo]) {
    /**
     * Distinct project files that contain a renamed declaration or a selected usage.
     *
     * Attached library sources are excluded: only files backed by the host's editable `SourceFile` are listed.
     */
    val files: Seq[PsiFile] = (renames.keySet.asScala.flatMap(e => Option(e.getContainingFile)) ++ usages.map(_.getFile))
      .filter(_.getViewProvider.getVirtualFile.isInstanceOf[SourceFile]).toSeq.distinct
  }

  // The plugin's processors ask where the IDE has a popup or dialog (rename the base member, rename the getters and
  // setters too): unit-test mode is their way of answering with the defaults, and the host has no UI to ask.
  /** Whether the current thread is inside `withDefaultAnswers`. */
  private val answeringDialogs = ThreadLocal.withInitial(() => false)

  /**
   * Tells whether rename processors on the current thread should use their unit-test defaults instead of
   * requesting UI choices.
   *
   * @return `true` only while a rename planning or target step runs with the host's default answers
   */
  def isAnsweringDialogs: Boolean = answeringDialogs.get

  /** Temporarily makes processor questions resolve to the host's deterministic defaults on this thread. */
  private def withDefaultAnswers[A](body: => A): A = {
    val previous = answeringDialogs.get
    answeringDialogs.set(true)
    try body
    finally answeringDialogs.set(previous)
  }

  /**
   * Resolves and validates the element to rename at an editor position.
   *
   * Callers provide PSI read access and the dispatch thread when required by Java processors.
   * Library declarations, vetoed elements and targets without an editable project source are refused.
   *
   * @param file source file being queried
   * @param offset caret offset in UTF-16 code units
   * @return the processor's substituted rename target
   * @throws Refused when the position does not identify a renameable project symbol
   */
  def target(file: PsiFile, offset: Int): PsiElement = {
    val found = ProjectSearch.usageTarget(file, offset).getOrElse(throw new Refused(Seq("There is no symbol to rename here.")))
    if (PsiElementRenameHandler.isVetoed(found)) throw new Refused(Seq("This element cannot be renamed."))
    val substituted = withDefaultAnswers(RenamePsiElementProcessor.forElement(found).substituteElementToRename(found, null))
    if (substituted == null) throw new Refused(Seq("This element cannot be renamed."))
    // Only source files belong to the editable workspace; attached sources remain read-only.
    if (!Option(substituted.getContainingFile).exists(_.getViewProvider.getVirtualFile.isInstanceOf[SourceFile]))
      throw new Refused(Seq("This element is not located inside the project."))
    Option(PsiElementRenameHandler.getRenameErrorMessage(file.getProject, null, substituted)).foreach(m => throw new Refused(Seq(m)))
    substituted
  }

  /**
   * Plans related declarations and usages for a rename without applying it.
   *
   * Callers hold PSI read access and keep the plan valid until its write phase. File renames are excluded.
   *
   * @param target validated project declaration
   * @param newName replacement name checked by the platform's validators
   * @return the ordered rename plan
   * @throws Refused for an invalid name or a conflict
   */
  def plan(target: PsiElement, newName: String): Plan = {
    val project = target.getProject

    if (!RenameUtil.isValidName(project, target, newName)) throw new Refused(Seq(s"'$newName' is not a valid name for this element."))

    val renames = new util.LinkedHashMap[PsiElement, String]
    val conflicts = new MultiMap[PsiElement, String]

    renames.put(target, newName)
    withDefaultAnswers(RenamePsiElementProcessor.allForElement(target).asScala.foreach(_.prepareRenaming(target, newName, renames)))
    // The file of a top-level class follows its name in the IDE; the host's files have fixed paths.
    renames.keySet.removeIf(_.isInstanceOf[PsiFile])

    val scope = GlobalSearchScope.projectScope(project)
    val usages = renames.asScala.toSeq.flatMap { (element, name) =>
      RenameUtil.findUsages(element, name, scope, false, false, renames)
    }.toArray

    RenameUtil.addConflictDescriptions(usages, conflicts)
    RenamePsiElementProcessor.forElement(target).findExistingNameConflicts(target, newName, conflicts, renames)
    if (!conflicts.isEmpty) throw new Refused(conflicts.values.asScala.toSeq.distinct)

    new Plan(project, renames, usages)
  }

  /**
   * Applies a rename plan to physical project PSI and commits affected documents.
   *
   * Callers hold the write lock and a platform command or undo-transparent action. This mutates project files;
   * preview callers are responsible for restoring their original text afterward.
   *
   * @param plan validated plan whose PSI has remained unchanged since planning
   */
  def perform(plan: Plan): Unit = {
    ProgressManager.checkCanceled()

    val classified = classify(plan)
    val ignored = new RefactoringElementListener {
      override def elementMoved(newElement: PsiElement): Unit = ()
      override def elementRenamed(newElement: PsiElement): Unit = ()
    }
    val documents = PsiDocumentManager.getInstance(plan.project)

    // The IDE formats what a rename changed once its command ends.
    val callbacks = PostprocessReformattingAspect.getInstance(plan.project).postponeFormattingInside((() => {
      val callbacks = plan.renames.asScala.toSeq.flatMap { (element, name) =>
        val processor = RenamePsiElementProcessor.forElement(element)
        val usages = classified.getOrElse(element, Seq.empty).asJava
        val callback = processor.getPostRenameCallback(element, name, usages, plan.renames, ignored)
        processor.renameElement(element, name, usages.toArray(UsageInfo.EMPTY_ARRAY), ignored)
        Option(callback)
      }
      plan.files.foreach(f => documents.doPostponedOperationsAndUnblockDocument(documents.getDocument(f)))
      callbacks
    }): Computable[Seq[Runnable]])

    plan.files.foreach(f => documents.commitDocument(documents.getDocument(f)))
    callbacks.foreach(_.run())
  }

  /** Groups usages by their renamed declaration or navigation element, excluding implicit light references. */
  private def classify(plan: Plan): Map[PsiElement, Seq[UsageInfo]] = {
    val renamed = plan.renames.keySet.asScala

    /** Resolves a usage to an element in the planned rename set, following navigation when needed. */
    def renamedElement(usage: MoveRenameUsageInfo): Option[PsiElement] = usage match {
      case related: RelatedUsageInfo => Some(related.getRelatedElement).filter(renamed)
      case _ => Option(usage.getReferencedElement).flatMap { element =>
        if (renamed(element)) Some(element) else Option(element.getNavigationElement).filter(renamed)
      }
    }

    plan.usages.toSeq
      .collect { case usage: MoveRenameUsageInfo if !usage.getReference.isInstanceOf[LightElement] => usage }
      .flatMap(usage => renamedElement(usage).map(_ -> (usage: UsageInfo)))
      .groupMap(_._1)(_._2)
  }
}
