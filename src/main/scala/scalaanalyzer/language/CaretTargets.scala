package scalaanalyzer.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtilKt
import com.intellij.psi.{PsiElement, PsiFile, PsiNameIdentifierOwner, PsiReference, ReferenceRange}

import scalaanalyzer.platform.{HostLog, RequestEditor}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Finds the symbol an editor operation acts on at a caret, as the IDE does, so that a caret right after a name (`foo|(`
 * or `foo|` at the end of a line) stands for that name.
 *
 * The IDE has two ways to look. Quick documentation, find usages and rename use `TargetElementUtil.findTargetElement`
 * (see [[reference]] and [[declaration]]); Go to Declaration, Go to Type Declaration and the identifier highlighting
 * use the platform's symbol lookup (see [[at]]). Callers hold the host read lock.
 */
private[scalaanalyzer] object CaretTargets {
  /** What the symbol lookup found at a caret. */
  enum Target {
    /** The elements a reference there resolves to. */
    case Referenced(elements: Seq[PsiElement])

    /** The declaration whose name is there. */
    case Declared(element: PsiElement)
  }

  /** A [[Target]] with the range of the text it was found by. */
  private final case class Candidate(target: Target, range: TextRange)

  /**
   * Finds the reference at a caret as the editor does: the reference of the identifier at the caret or just before it.
   *
   * Mirrors `TargetElementUtil.findReference`.
   *
   * @param file source being queried
   * @param offset caret offset in UTF-16 code units
   * @return the reference whose text the caret touches, or none
   */
  def reference(file: PsiFile, offset: Int): Option[PsiReference] =
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, offset)) { request =>
      Option(TargetElementUtil.findReference(request.editor, offset))
    }

  /**
   * Finds the named declaration at a caret as the editor does: the one whose name is at the caret or just before it.
   *
   * Mirrors the element-name step of `TargetElementUtil.findTargetElement`, which looks at the leaf under the caret
   * after `TargetElementUtil.adjustOffset`.
   *
   * @param file source being queried
   * @param offset caret offset in UTF-16 code units
   * @return the declaration, or none
   */
  def declaration(file: PsiFile, offset: Int): Option[PsiElement] = {
    val adjusted = TargetElementUtil.adjustOffset(file, file.getViewProvider.getDocument, offset)

    Option(file.findElementAt(adjusted)).flatMap(leaf => Option(TargetElementUtil.getNamedElement(leaf)))
  }

  /**
   * Finds what the platform's symbol lookup finds at a caret: the symbol referenced there, or else the one declared.
   *
   * Mirrors `com.intellij.model.psi.impl.declaredReferencedData`, for languages whose references are `PsiReference`s
   * (Scala and Java sources): the symbol API has no references or declarations of its own there, so the lookup's
   * candidates are the named element around the caret and the `TargetElementUtil` evaluator's reference.
   *
   * @param file source being queried
   * @param offset caret offset in UTF-16 code units
   * @return the referenced elements, which win over the declaration when both touch the caret at equal range; none
   *         when neither is there, or when their ranges overlap
   */
  def at(file: PsiFile, offset: Int): Option[Target] = {
    val declared = declaredAround(file, offset)
    val candidates = declared.toSeq ++ referencedAround(file, offset, declared.map(_.target))

    chooseByRange(candidates, offset).flatMap { chosen =>
      chosen.collectFirst { case Candidate(referenced: Target.Referenced, _) => referenced }
        .orElse(chosen.collectFirst { case Candidate(named: Target.Declared, _) => named })
    }
  }

  /** The platform's `namedElement`: the named element of the leaf at the caret, or else of the leaf before it. */
  private def declaredAround(file: PsiFile, offset: Int): Option[Candidate] =
    PsiTreeUtilKt.leavesAroundOffset(file, offset).asScala.iterator.map(_.getFirst).flatMap { leaf =>
      Option(TargetElementUtil.getNamedElement(leaf)).map { named =>
        // As `PsiElement2Declaration`: the name identifier when it lies in the leaf, else the leaf.
        val range = named match {
          case owner: PsiNameIdentifierOwner
              if owner.getNameIdentifier != null && leaf.getTextRange.contains(owner.getNameIdentifier.getTextRange) =>
            owner.getNameIdentifier.getTextRange
          case _ => leaf.getTextRange
        }

        Candidate(Target.Declared(named), range)
      }
    }.nextOption()

  /** The platform's `fromTargetEvaluator`: what the editor's target lookup finds, without declarations. */
  private def referencedAround(file: PsiFile, offset: Int, declared: Option[Target]): Option[Candidate] = {
    val flags = TargetElementUtil.getInstance.getAllAccepted & ~TargetElementUtil.ELEMENT_NAME_ACCEPTED
    val found = reference(file, offset)
    val elements = ProjectSearch.targetAt(file, offset, flags).map(Seq(_))
      .getOrElse(found.toSeq.flatMap(TargetElementUtil.getInstance.getTargetCandidates(_).asScala))

    elements match {
      case Seq() => None
      // The platform treats a reference to the declaration under the caret as that declaration.
      case Seq(only) if declared.exists { case Target.Declared(element) => element eq only; case _ => false } => None
      case _ =>
        // The range of the reference's text that holds the caret, or of the leaf the caret adjusts to.
        val range = found.flatMap { reference =>
          val start = reference.getElement.getTextRange.getStartOffset

          ReferenceRange.getRanges(reference).asScala.map(_.shiftRight(start)).find(_.containsOffset(offset))
        }.orElse {
          val adjusted = TargetElementUtil.adjustOffset(file, file.getViewProvider.getDocument, offset)

          Option(file.findElementAt(adjusted)).map(_.getTextRange)
        }

        range.map(Candidate(Target.Referenced(elements.distinct), _))
    }
  }

  /**
   * The platform's `chooseByRange`: of the candidates, those whose range contains the caret and is the smallest, else
   * those that end at the caret and start last.
   *
   * @return the chosen candidates; none when two ranges that contain the caret overlap without nesting
   */
  private def chooseByRange(candidates: Seq[Candidate], offset: Int): Option[Seq[Candidate]] =
    if (candidates.size < 2) Some(candidates)
    else {
      val (endingAtCaret, containing) = candidates.partition(_.range.getEndOffset == offset)

      if (containing.isEmpty) {
        val latestStart = endingAtCaret.map(_.range.getStartOffset).max

        Some(endingAtCaret.filter(_.range.getStartOffset == latestStart))
      }
      else if (containing.combinations(2).exists { case Seq(a, b) =>
          !a.range.contains(b.range) && !b.range.contains(a.range)
        }) {
        HostLog.warn(s"overlapping target ranges at $offset: ${containing.map(_.range)}")
        None
      }
      else Some(containing.filterNot(a => containing.exists(b => a.range.contains(b.range) && a.range != b.range)))
    }
}
