package scalapsihost.language

import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ModCommandService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.{PsiAnnotationOwner, SyntaxTraverser}
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker, PsiTreeUtil}
import org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.{DeleteUnusedElementFix, ScalaUnusedDeclarationInspection}
import org.jetbrains.plugins.scala.codeInspection.cast.ScalaRedundantCastInspection
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScImportExpr, ScImportSelector}
import org.jetbrains.plugins.scala.codeInspection.suppression.ScalaInspectionSuppressor
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScMember
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalapsihost.Diagnostic
import scalapsihost.platform.{CallLog, ExtensionBean}

import scala.jdk.CollectionConverters.*

/** Copy-bound previews for legacy fixes whose native preview contract is incomplete. */
private[language] object Inspections {
  /** Recreates audited single-file fixes on copied PSI, excluding parameter/type deletion that invokes Safe Delete. */
  def previewFix(action: IntentionAction, copy: ScalaFile): Option[IntentionPreviewInfo] = {
    val fix = action match {
      case local: LocalQuickFix => local
      case _ => QuickFixWrapper.unwrap(action)
    }
    val implementation = Option(fix).map(_.getClass.getName)
    val removal = implementation.contains("org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.DeleteUnusedElementFix")
    val cast = implementation.contains("org.jetbrains.plugins.scala.codeInspection.cast.ScalaRedundantCastInspection$RemoveCastQuickFix")
    // This pinned plugin fix mutates its captured original expression instead of the ModCommand copy parameter.
    val command = Option(fix).flatMap(local => Option(ModCommandService.getInstance.unwrap(local)))
    val capturesOriginal = command.exists(_.getClass.getName ==
      "org.jetbrains.plugins.scala.codeInspection.controlFlow.ScalaUnusedExpressionInspection$AddReturnQuickFix")
    if (capturesOriginal) Some(IntentionPreviewInfo.EMPTY)
    else if (!removal && !cast) None
    else fix match {
      case anchored: LocalQuickFixOnPsiElement =>
        val target = PsiTreeUtil.findSameElementInCopy(anchored.getStartElement, copy)
        val candidates: Seq[LocalQuickFixOnPsiElement] = target match {
          case named: ScNamedElement if removal && (named.isInstanceOf[ScReferencePattern] || named.isInstanceOf[ScFunctionDefinition]) =>
            DeleteUnusedElementFix.quickfixesFor(named)
          case call: ScGenericCall if cast => call.referencedExpr match {
            case reference: ScReferenceExpression => reference.qualifier.toSeq.map { expression =>
              val inspection = new ScalaRedundantCastInspection
              new inspection.RemoveCastQuickFix(call, expression)
            }
            case _ => Seq.empty
          }
          case _ => Seq.empty
        }
        candidates.filter(_.getText == anchored.getText) match {
          case Seq(rebuilt) =>
            rebuilt.applyFix()
            Some(IntentionPreviewInfo.DIFF)
          case _ => Some(IntentionPreviewInfo.EMPTY)
        }
      case _ => Some(IntentionPreviewInfo.EMPTY)
    }
  }
}

/**
 * Runs the supported Scala inspections with descriptor defaults and native suppression rules.
 *
 * Desktop inspection profiles are not loaded. Results, including fixes, expire on any PSI change;
 * each computation owns its tool instances and inspection sessions. Only physical source buffers are inspected;
 * parsed snippets have no stable anchors for inspection descriptors. Unused-symbol analysis excludes public and
 * annotated declarations, which may have consumers outside local reference searches. Wildcard/given import
 * warnings require the desktop usage-tracking pass and are not reported here. Local unused-symbol checks
 * remain enabled alongside compiler highlighting, independently of build warning flags.
 *
 * @param descriptors local inspection registrations from the pinned Scala plugin
 */
final class Inspections(descriptors: Seq[ExtensionBean]) {
  private val supported = Set("ScalaDeprecation", "ScalaRedundantCast", "SimplifyBoolean",
    "ScalaUnusedSymbol", "ScalaUnusedExpression", "ScalaUnnecessarySemicolon", "RemoveRedundantReturn")
  private val tools = descriptors.filter(bean => supported(bean.attributes.getOrElse("shortName", "")))
  require(tools.map(_.attributes("shortName")).toSet == supported, "missing supported Scala inspections")
  private val cache = Key.create[CachedValue[Seq[Diagnostic]]]("scala-psi-host.inspections")

  /**
   * Collects warnings and their native quick fixes for the current project PSI.
   *
   * @param file source file, queried under read access
   * @return inspection diagnostics against the current buffer
   */
  def diagnostics(file: ScalaFile): Seq[Diagnostic] =
    if (!file.isPhysical) Seq.empty
    else CachedValuesManager.getManager(file.getProject).getCachedValue(file, cache, () =>
      CachedValueProvider.Result.create(collect(file), PsiModificationTracker.MODIFICATION_COUNT), false)

  /** Runs complete visitor lifecycles and preserves the plugin's problem ranges and messages. */
  private def collect(file: ScalaFile): Seq[Diagnostic] = {
    val manager = new InspectionManagerBase(file.getProject) {
      override def createNewGlobalContext(): GlobalInspectionContext = CallLog.unsupported("InspectionManager.globalContext")
      override def createNewGlobalContext(reuse: Boolean): GlobalInspectionContext = createNewGlobalContext()
    }
    val elements = SyntaxTraverser.psiTraverser(file).asScala.toVector
    tools.flatMap { bean =>
      val tool = Class.forName(bean.implementation).getConstructor().newInstance().asInstanceOf[LocalInspectionTool]
      // Batch visitation performs real local reference searches instead of relying on a desktop highlighting pass.
      val onTheFly = tool match {
        case unused: ScalaUnusedDeclarationInspection =>
          unused.reportPublicDeclarations = false
          // Compiler highlighting does not imply that the build enabled unused-symbol warnings.
          unused.disableIfCBHIsUsed = false
          false
        case _ => true
      }
      val id = bean.attributes.getOrElse("id", bean.attributes("shortName"))
      val severity = bean.attributes.getOrElse("level", "WARNING") match {
        case "ERROR" => HighlightSeverity.ERROR
        case "WEAK WARNING" => HighlightSeverity.WEAK_WARNING
        case "WARNING" => HighlightSeverity.WARNING
        case other => throw new IllegalArgumentException(s"unsupported inspection severity: $other")
      }
      val session = new LocalInspectionToolSession(file, file.getTextRange, file.getTextRange, null)
      val holder = new ProblemsHolder(manager, file, onTheFly)
      tool.inspectionStarted(session, onTheFly)
      val visitor = tool.buildVisitor(holder, onTheFly, session)
      val inspected = tool match {
        case _: ScalaUnusedDeclarationInspection => elements.collect {
          case named: ScNamedElement if ScalaPsiUtil.isOnlyVisibleInLocalFile(named) && (named.nameContext match {
            // Annotation-driven consumers (framework entry points, macros) are outside local reference searches.
            case owner: PsiAnnotationOwner => owner.getAnnotations.isEmpty
            case _ => true
          }) => named
        }
        case _ => elements
      }
      inspected.foreach { element =>
        ProgressManager.checkCanceled()
        element.accept(visitor)
      }
      tool.inspectionFinished(session, holder)
      val declarations = problems(holder, id, severity,
        unnecessary = bean.attributes.get("editorAttributes").contains("NOT_USED_ELEMENT_ATTRIBUTES"))
      val imports = tool match {
        case unused: ScalaUnusedDeclarationInspection =>
          // This conservative plugin visitor handles explicit imports without a desktop reference-count pass.
          // Wildcard and given imports stay with the import optimizer until that pass can be hosted.
          val importsHolder = new ProblemsHolder(manager, file, true)
          val importVisitor = unused.buildIncrementalHighlightingVisitor(importsHolder)
          elements.foreach {
            case element @ (_: ScImportExpr | _: ScImportSelector) =>
              ProgressManager.checkCanceled()
              element.accept(importVisitor)
            case _ => ()
          }
          problems(importsHolder, "ScalaUnusedImport", HighlightSeverity.WARNING, unnecessary = true)
        case _ => Seq.empty
      }
      declarations ++ imports
    }
  }

  /** Keeps referenced members when native unusedness propagation starts from a public type excluded by our scope. */
  private def hasLocalUsage(problem: ProblemDescriptor): Boolean =
    PsiTreeUtil.getParentOfType(problem.getPsiElement, classOf[ScNamedElement], false) match {
      case member: ScMember =>
        // Some pinned query executors lose the processor's stop status, so Query.anyMatch can miss actual results.
        val references = ReferencesSearch.search(member, new LocalSearchScope(member.getContainingFile), true).findAll.asScala.toSeq
        references.exists(reference => !PsiTreeUtil.isAncestor(member, reference.getElement, false))
      case _ => false
    }

  /** Converts native descriptors and fixes without discarding suppression, severity or range information. */
  private def problems(holder: ProblemsHolder, id: String, severity: HighlightSeverity, unnecessary: Boolean): Seq[Diagnostic] = {
    val suppressor = new ScalaInspectionSuppressor
    holder.getResults.asScala.toSeq.filterNot { problem =>
      suppressor.isSuppressedFor(problem.getPsiElement, id) || (id == "ScalaUnusedSymbol" && hasLocalUsage(problem))
    }.map { problem =>
      val range = problem match {
        case descriptor: ProblemDescriptorBase => descriptor.getTextRange
        case other => throw new IllegalStateException(s"unexpected problem descriptor: ${other.getClass.getName}")
      }
      val fixes = Option(problem.getFixes).toSeq.flatMap(_.indices).map { index =>
        val action = QuickFixWrapper.wrap(problem, index) match {
          case wrapper: IntentionWrapper => wrapper.getAction
          case other => other
        }
        range -> action
      }
      Diagnostic(severity, range, ProblemDescriptorUtil.renderDescriptionMessage(problem, problem.getPsiElement), fixes,
        Some(id), deprecated = id == "ScalaDeprecation", unnecessary = unnecessary)
    }
  }
}
