package scalaanalyzer.language

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.impl.actions.AbstractBatchSuppressByNoInspectionCommentFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ModCommandService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiAnnotationOwner, PsiElement, PsiFile, SyntaxTraverser}
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.codeInspection.cast.ScalaRedundantCastInspection
import org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.{DeleteUnusedElementFix, ScalaUnusedDeclarationInspection}
import org.jetbrains.plugins.scala.codeInspection.suppression.ScalaInspectionSuppressor
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScImportExpr, ScImportSelector}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScMember

import scalaanalyzer.Diagnostic
import scalaanalyzer.platform.ExtensionBean

import java.util.{Locale, ResourceBundle}

import scala.jdk.CollectionConverters.*

/** Copy-bound previews for legacy fixes whose native preview contract is incomplete. */
private[language] object Inspections {
  /** The id of the unused-import problems, which the unused-declaration tool reports without a registration of its own. */
  val UnusedImportId = "ScalaUnusedImport"

  /**
   * Previews the audited fixes whose native preview does not work on the copy.
   *
   * Rebuilds the unused-declaration removal and the redundant-cast removal on copied PSI and applies them there;
   * parameter/type deletion, which invokes Safe Delete, is excluded. A fix known to mutate its captured original
   * expression instead of the copy is answered with an empty preview. A suppression writes its comment into the copy.
   * Any other fix is left to the caller.
   *
   * @param action quick fix, or the intention wrapping one, to preview
   * @param copy preview copy that the rebuilt fix mutates
   * @return `None` when this object does not handle the fix, `Some(DIFF)` when the rebuilt fix was applied to the
   *         copy, and `Some(EMPTY)` when the fix is handled but offers no applicable preview
   */
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
    else fix match {
      case suppression: Suppression => Some(suppression.applyTo(copy))
      case _ if !removal && !cast => None
      case _ => Some(rebuiltPreview(fix, copy, removal, cast))
    }
  }

  /** Applies the unused-declaration or redundant-cast removal, rebuilt on the copy's PSI. */
  private def rebuiltPreview(fix: LocalQuickFix, copy: ScalaFile, removal: Boolean, cast: Boolean): IntentionPreviewInfo =
    fix match {
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
            IntentionPreviewInfo.DIFF
          case _ => IntentionPreviewInfo.EMPTY
        }
      case _ => IntentionPreviewInfo.EMPTY
    }

  /**
   * One of the plugin's `//noinspection` suppressions, anchored at its problem's element.
   *
   * The platform gives suppressions no preview, and the wrapper the IDE offers them through builds a problem
   * descriptor that a copy's element cannot anchor; the anchor lets the preview find that element in the copy.
   *
   * @param element the problem's element in the inspected file
   * @param fix the plugin's suppression for the problem's inspection
   */
  private[language] final class Suppression(element: PsiElement, fix: AbstractBatchSuppressByNoInspectionCommentFix)
      extends LocalQuickFixOnPsiElement(element) {
    override def getText: String = fix.getText
    override def getFamilyName: String = fix.getFamilyName
    override def invoke(project: Project, file: PsiFile, start: PsiElement, end: PsiElement): Unit = fix.invoke(project, start)

    /** Writes the suppression into the copy, at the problem's element there. */
    def applyTo(copy: ScalaFile): IntentionPreviewInfo = {
      fix.invoke(copy.getProject, PsiTreeUtil.findSameElementInCopy(getStartElement, copy))
      IntentionPreviewInfo.DIFF
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
  /** Short names of the inspections this host runs; construction fails unless the descriptors register all of them. */
  private val supported = Set("ScalaDeprecation", "ScalaRedundantCast", "SimplifyBoolean",
    "ScalaUnusedSymbol", "ScalaUnusedExpression", "ScalaUnnecessarySemicolon", "RemoveRedundantReturn")

  /** The registrations of the supported inspections. */
  private val tools =descriptors.filter(bean => supported(bean.attributes.getOrElse("shortName", "")))
  require(tools.map(_.attributes("shortName")).toSet == supported, "missing supported Scala inspections")

  /**
   * The inspections that can report a problem, as the IDE names and groups them in its inspection results.
   *
   * Read from the plugin's registrations. The unused-declaration tool also reports unused imports under their own
   * id: they share that registration's name and group, as the IDE lists them with its unused declarations.
   */
  val descriptions: Seq[InspectionDescription] = tools.flatMap { bean =>
    val description = describe(bean)
    val imports = Option.when(bean.implementation == classOf[ScalaUnusedDeclarationInspection].getName)(
      description.copy(id = Inspections.UnusedImportId))

    description +: imports.toSeq
  }
  // The plugin builds the suppression fixes of an inspection from its display key, which the IDE registers with the
  // inspection profile that the host does not load. Suppressing by comment names the key's id.
  descriptions.foreach(description => HighlightDisplayKey.findOrRegister(description.id, description.displayName))

  /**
   * Collects warnings and their native quick fixes for the current project PSI.
   *
   * @param file source file, queried under read access
   * @return inspection diagnostics against the current buffer
   */
  def diagnostics(file: ScalaFile): Seq[Diagnostic] =
    if (!file.isPhysical) Seq.empty else collect(file)

  /** The id a registration's problems carry: its `id` attribute, which defaults to its short name. */
  private def inspectionId(bean: ExtensionBean): String = bean.attributes.getOrElse("id", bean.attributes("shortName"))

  /**
   * Reads a registration's display name and group path as the platform's `InspectionEP` does.
   *
   * The name is the `displayName` attribute or the `key` in the registration's `bundle`. The group path is the
   * comma-separated `groupPath` followed by the `groupName` attribute or the `groupKey` in the `groupBundle`, which
   * defaults to `bundle`. Bundles are read in the root locale: the English text the plugin ships.
   */
  private def describe(bean: ExtensionBean): InspectionDescription = {
    val attributes = bean.attributes
    // The bundles are resources of the plugin jars, which the registration's implementation class is loaded from.
    val loader = Class.forName(bean.implementation).getClassLoader
    def message(bundle: String, key: String): String = ResourceBundle.getBundle(bundle, Locale.ROOT, loader).getString(key)

    val displayName = attributes.getOrElse("displayName", message(attributes("bundle"), attributes("key")))
    val groupName = attributes.get("groupName")
      .orElse(attributes.get("groupKey").map(message(attributes.getOrElse("groupBundle", attributes("bundle")), _)))
    val groupPath = attributes.get("groupPath").toSeq.flatMap(_.split(",")).map(_.trim) ++ groupName

    InspectionDescription(inspectionId(bean), displayName, groupPath)
  }

  /** Runs complete visitor lifecycles and preserves the plugin's problem ranges and messages. */
  private def collect(file: ScalaFile): Seq[Diagnostic] = {
    val manager = InspectionManager.getInstance(file.getProject)
    val elements = SyntaxTraverser.psiTraverser(file).asScala.toVector
    tools.flatMap { bean =>
      // `tools` holds only local inspection registrations, whose implementation classes are LocalInspectionTool.
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
      val id = inspectionId(bean)
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
          problems(importsHolder, Inspections.UnusedImportId, HighlightSeverity.WARNING, unnecessary = true)
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
        val references = ReferencesSearch
          .search(member, new LocalSearchScope(member.getContainingFile), true)
          .findAll
          .asScala
          .toSeq
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
      // The IDE offers these beside the problem's fixes; the host previews them like any other fix.
      val suppressions = suppressor.getSuppressActions(problem.getPsiElement, id).toSeq.collect {
        case fix: AbstractBatchSuppressByNoInspectionCommentFix =>
          range -> QuickFixWrapper.wrap(problem, new Inspections.Suppression(problem.getPsiElement, fix))
      }
      Diagnostic(severity, range, ProblemDescriptorUtil.renderDescriptionMessage(problem, problem.getPsiElement),
        fixes ++ suppressions, Some(id), deprecated = id == "ScalaDeprecation", unnecessary = unnecessary)
    }
  }
}
