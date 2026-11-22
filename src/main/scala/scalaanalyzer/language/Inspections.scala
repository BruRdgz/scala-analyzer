package scalaanalyzer.language

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.impl.actions.{
  AbstractBatchSuppressByNoInspectionCommentFix, AbstractBatchSuppressByNoInspectionCommentModCommandFix
}
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ModCommandService
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiAnnotationOwner, PsiElement, PsiFile, PsiJavaFile, SyntaxTraverser}
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
import scalaanalyzer.platform.{ExtensionBean, HostLog}

import java.util.{Locale, ResourceBundle}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Copy-bound previews for legacy fixes whose native preview contract is incomplete, and the Java inspections' runs. */
private[scalaanalyzer] object Inspections {
  /** The id of the unused-import problems, which the unused-declaration tool reports without a registration of its own. */
  val UnusedImportId = "ScalaUnusedImport"

  /**
   * The Java inspections the host does not run, by id, each with the reason: the loaded ones that the audit in
   * `JavaInspectionsTest` finds failing. The audit fails on an entry that no longer fails, so the set only shrinks
   * as the host registers what they ask for.
   */
  val JavaExclusions: Map[String, String] = Map(
    "EnumSwitchStatementWhichMissesCases" ->
      "needs com.intellij.ide.nls.NlsMessages, which the host's slice of the platform's ide-core-impl leaves out",
    "MarkdownDocumentationCommentsMigration" -> "needs jsoup, which is not on the host's class path",
    "MagicConstant" ->
      "attaches the IDE's JDK annotations through VirtualFileManager and the IDE's SDK model, which the host lacks",
    "ClassOverriddenAtRuntime" -> (
      "applies to IntelliJ's own (JPS) module model, found through ExternalProjectSystemRegistry; the host's modules " +
        "come from a build server"),
    "NullableProblems" ->
      "needs the nullability annotations the IDE configures; the host's NullableNotNullManager answers none",
  )

  /** The phase of a Java inspection's run that failed. */
  enum JavaPhase { case Construction, Run }

  /**
   * The failure of one phase of a Java inspection's run.
   *
   * @param phase what was being done
   * @param error what was thrown, including the host's `UnsupportedOperationException` for a service it does not answer
   */
  final case class JavaFailure(phase: JavaPhase, error: Throwable)

  /**
   * One Java inspection's run over one file.
   *
   * @param id the inspection's id, which its problems carry
   * @param nanos time spent constructing and running it
   * @param problems what it reported, with its fixes; empty when it failed
   * @param loggedErrors errors the platform logged on the running thread meanwhile (see `HostLog`), which do not stop
   *                     an inspection as in the IDE, but show that a service misbehaved
   * @param failure why it reported nothing, when it threw
   */
  final case class JavaRun(id: String, nanos: Long, problems: Seq[Diagnostic], loggedErrors: Long,
      failure: Option[JavaFailure])

  /** How a problem is shown: its severity and whether it marks deprecated or unnecessary code. */
  private final case class Presentation(severity: HighlightSeverity, deprecated: Boolean, unnecessary: Boolean)

  /** Inspections named in a file's debug timing: enough to find a hot spot among some 60, few enough for one line. */
  private val SlowestLogged = 5

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
 * Runs the supported Scala inspections and the Java inspections that the IDE's default profile enables, with
 * descriptor defaults and native suppression rules.
 *
 * Desktop inspection profiles are not loaded. Results, including fixes, expire on any PSI change;
 * each computation owns its tool instances and inspection sessions. Only physical source buffers are inspected;
 * parsed snippets have no stable anchors for inspection descriptors. Unused-symbol analysis excludes public and
 * annotated declarations, which may have consumers outside local reference searches. Wildcard/given import
 * warnings require the desktop usage-tracking pass and are not reported here. Local unused-symbol checks
 * remain enabled alongside compiler highlighting, independently of build warning flags.
 *
 * Each Java inspection runs apart from the others: one that throws is logged and reports nothing, as the IDE's
 * highlighting pass does, and the rest still run.
 *
 * @param scalaDescriptors local inspection registrations from the pinned Scala plugin
 * @param javaDescriptors the Java plugin's local inspection registrations for Java that the IDE enables by default,
 *                        including those that [[Inspections.JavaExclusions]] keeps from running
 */
final class Inspections(scalaDescriptors: Seq[ExtensionBean], javaDescriptors: Seq[ExtensionBean]) {
  /** Short names of the inspections chosen one by one; construction fails unless the descriptors register all of them. */
  private val supported = Set("ScalaDeprecation", "ScalaRedundantCast", "SimplifyBoolean",
    "ScalaUnusedSymbol", "ScalaUnusedExpression", "ScalaUnnecessarySemicolon", "RemoveRedundantReturn")

  /**
   * Whether a registration is one of the plugin's collection inspections that the IDE's default profile enables.
   *
   * They share one visitor and one fix (`OperationOnCollectionInspection`), so they are taken as a group; the
   * `InspectionsOracle` replays the plugin's tests of each.
   */
  private def defaultCollectionInspection(bean: ExtensionBean): Boolean =
    bean.implementation.startsWith("org.jetbrains.plugins.scala.codeInspection.collections.") &&
      bean.attributes.get("enabledByDefault").contains("true")

  /** The registrations of the supported inspections. */
  private val tools = scalaDescriptors.filter(bean =>
    supported(bean.attributes.getOrElse("shortName", "")) || defaultCollectionInspection(bean))
  require(supported.subsetOf(tools.map(_.attributes("shortName")).toSet), "missing supported Scala inspections")

  /** The registrations of the Java inspections that run: the loaded ones that no exclusion keeps out. */
  private val javaTools = javaDescriptors.filterNot(bean => Inspections.JavaExclusions.contains(inspectionId(bean)))

  /**
   * The IDE's suppressor for Java: `@SuppressWarnings` and `//noinspection`. The platform keeps its class
   * package-private and registers it by language, which the host does not use, so it is built by reflection.
   */
  private val javaSuppressor: InspectionSuppressor = {
    val constructor = Class.forName("com.intellij.codeInspection.JavaInspectionSuppressor")
      .asSubclass(classOf[InspectionSuppressor]).getDeclaredConstructor()
    constructor.setAccessible(true)
    constructor.newInstance()
  }

  /** The Scala inspections' descriptions. The unused-declaration tool also describes its unused imports. */
  private val scalaDescriptions: Seq[InspectionDescription] = tools.flatMap { bean =>
    val description = describe(bean, Class.forName(bean.implementation).getClassLoader)
    val imports = Option.when(bean.implementation == classOf[ScalaUnusedDeclarationInspection].getName)(
      description.copy(id = Inspections.UnusedImportId))

    description +: imports.toSeq
  }

  /**
   * The Java inspections' descriptions, of every loaded registration: the excluded ones are run by the audit, whose
   * suppress actions need their display keys.
   */
  private val javaDescriptions: Seq[InspectionDescription] = javaDescriptors.map(describeJava)

  require(scalaDescriptions.map(_.id).toSet.intersect(javaDescriptions.map(_.id).toSet).isEmpty,
    "a Scala and a Java inspection share an id")

  /**
   * The inspections that can report a problem, as the IDE names and groups them in its inspection results.
   *
   * Read from the registrations of the Scala plugin and of the Java plugin. The unused-declaration tool also reports
   * unused imports under their own id: they share that registration's name and group, as the IDE lists them with its
   * unused declarations.
   */
  val descriptions: Seq[InspectionDescription] =
    scalaDescriptions ++ javaDescriptions.filterNot(description => Inspections.JavaExclusions.contains(description.id))
  // The plugin builds the suppression fixes of an inspection from its display key, which the IDE registers with the
  // inspection profile that the host does not load. Suppressing by comment names the key's id.
  (scalaDescriptions ++ javaDescriptions).foreach(description =>
    HighlightDisplayKey.findOrRegister(description.id, description.displayName))

  /**
   * Collects warnings and their native quick fixes for the current project PSI.
   *
   * @param file Scala or Java source file, queried under read access
   * @return inspection diagnostics against the current buffer; empty for a file that is neither, or that has no buffer
   */
  def diagnostics(file: PsiFile): Seq[Diagnostic] = file match {
    case _ if !file.isPhysical => Seq.empty
    case scalaFile: ScalaFile => collect(scalaFile)
    case javaFile: PsiJavaFile => collectJava(javaFile)
    case _ => Seq.empty
  }

  /**
   * A registration's short name: its `shortName` attribute, which defaults to the implementation's class name without
   * its `Inspection` suffix, as the platform's `InspectionEP` derives it.
   */
  private def shortName(bean: ExtensionBean): String = bean.attributes.getOrElse("shortName",
    InspectionProfileEntry.getShortName(StringUtil.getShortName(bean.implementation)))

  /** The id a registration's problems carry: its `id` attribute, which defaults to its short name. */
  private def inspectionId(bean: ExtensionBean): String = bean.attributes.getOrElse("id", shortName(bean))

  /**
   * Reads a registration's display name and group path as the platform's `InspectionEP` does.
   *
   * The name is the `displayName` attribute or the `key` in the registration's `bundle`. The group path is the
   * comma-separated `groupPath`, or the `groupPathKey` in the `groupBundle`, followed by the `groupName` attribute or
   * the `groupKey` in the `groupBundle`, which defaults to `bundle`. Bundles are read in the root locale: the English
   * text the plugin ships.
   *
   * @param bean the registration
   * @param loader the class loader that holds the bundles: those of the plugin that registers it
   */
  private def describe(bean: ExtensionBean, loader: ClassLoader): InspectionDescription = {
    val attributes = bean.attributes
    def message(bundle: String, key: String): String = ResourceBundle.getBundle(bundle, Locale.ROOT, loader).getString(key)
    def groupMessage(key: String): String = message(attributes.getOrElse("groupBundle", attributes("bundle")), key)

    val displayName = attributes.getOrElse("displayName", message(attributes("bundle"), attributes("key")))
    val groupName = attributes.get("groupName").orElse(attributes.get("groupKey").map(groupMessage))
    val path = attributes.get("groupPath").orElse(attributes.get("groupPathKey").map(groupMessage))
    val groupPath = path.toSeq.flatMap(_.split(",")).map(_.trim) ++ groupName

    InspectionDescription(inspectionId(bean), displayName, groupPath)
  }

  /** Describes a registration of the Java plugin, whose bundles are in the same jars as the platform's. */
  private def describeJava(bean: ExtensionBean): InspectionDescription =
    describe(bean, classOf[LocalInspectionTool].getClassLoader)

  /**
   * The names that the platform's `InspectionEP` gives each tool it instantiates, from the registration. Tools read
   * them in `getDisplayName` and the like, some while reporting a problem, and log an error when they have none.
   */
  private def nameProvider(bean: ExtensionBean): InspectionProfileEntry.DefaultNameProvider = {
    val description = describeJava(bean)

    new InspectionProfileEntry.DefaultNameProvider {
      override def getDefaultShortName: String = shortName(bean)
      override def getGroupKey: String = bean.attributes.get("groupKey").orNull
      override def getDefaultDisplayName: String = description.displayName
      override def getDefaultGroupDisplayName: String = description.groupPath.lastOption.orNull
    }
  }

  /** The severity a registration's `level` names. */
  private def level(bean: ExtensionBean): HighlightSeverity = bean.attributes.getOrElse("level", "WARNING") match {
    case "ERROR" => HighlightSeverity.ERROR
    case "WEAK WARNING" => HighlightSeverity.WEAK_WARNING
    case "WARNING" => HighlightSeverity.WARNING
    // Inspections that mark code without reporting it (reassigned variables, code that could be cleaner).
    case "INFORMATION" | "TEXT ATTRIBUTES" => HighlightSeverity.INFORMATION
    case other => throw new IllegalArgumentException(s"unsupported inspection severity: $other")
  }

  /**
   * Runs a tool's complete visitor lifecycle over `elements` of `file`: a session of its own, the tool's start and
   * finish, and the visitor on each element.
   *
   * @return what the tool reported
   */
  private def visit(file: PsiFile, manager: InspectionManager, tool: LocalInspectionTool, onTheFly: Boolean,
      elements: Seq[PsiElement]): ProblemsHolder = {
    val session = new LocalInspectionToolSession(file, file.getTextRange, file.getTextRange, null)
    val holder = new ProblemsHolder(manager, file, onTheFly)

    tool.inspectionStarted(session, onTheFly)
    val visitor = tool.buildVisitor(holder, onTheFly, session)
    elements.foreach { element =>
      ProgressManager.checkCanceled()
      element.accept(visitor)
    }
    tool.inspectionFinished(session, holder)

    holder
  }

  /**
   * Runs complete visitor lifecycles and preserves the plugin's problem ranges and messages. With debug logging, logs
   * the file's inspection time and its slowest inspections.
   */
  private def collect(file: ScalaFile): Seq[Diagnostic] = {
    val manager = InspectionManager.getInstance(file.getProject)
    val elements = SyntaxTraverser.psiTraverser(file).asScala.toVector
    val nanosById = Vector.newBuilder[(String, Long)]

    val found = tools.flatMap { bean =>
      val started = System.nanoTime()
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
      val severity = level(bean)
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
      val holder = visit(file, manager, tool, onTheFly, inspected)
      val unnecessary = bean.attributes.get("editorAttributes").contains("NOT_USED_ELEMENT_ATTRIBUTES")
      val suppressor = new ScalaInspectionSuppressor
      val declarations = problems(holder, id, suppressor, None, htmlMessages = false)(_ =>
        Inspections.Presentation(severity, deprecated = id == "ScalaDeprecation", unnecessary = unnecessary))
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
          val shown = Inspections.Presentation(HighlightSeverity.WARNING, deprecated = false, unnecessary = true)
          problems(importsHolder, Inspections.UnusedImportId, suppressor, None, htmlMessages = false)(_ => shown)
        case _ => Seq.empty
      }
      nanosById += id -> (System.nanoTime() - started)
      declarations ++ imports
    }

    logTimes(file, nanosById.result())
    found
  }

  /**
   * Collects the problems of the Java inspections that run, each apart from the others (see [[runJava]]); a failure
   * is logged with the inspection's id, and its problems are none. With debug logging, logs the file's inspection time
   * and its slowest inspections.
   */
  private def collectJava(file: PsiJavaFile): Seq[Diagnostic] = {
    val runs = runJava(file, javaTools)

    runs.foreach(run => run.failure.foreach { failure =>
      HostLog.warn(s"Java inspection ${run.id} failed in ${file.getName} (${failure.phase})", failure.error)
    })
    logTimes(file, runs.map(run => run.id -> run.nanos))

    runs.flatMap(_.problems)
  }

  /** With debug logging, logs the file's inspection time and its slowest inspections. */
  private def logTimes(file: PsiFile, nanos: Seq[(String, Long)]): Unit =
    HostLog.debug {
      val slowest = nanos
        .sortBy(-_._2)
        .take(Inspections.SlowestLogged)
        .map((id, spent) => s"$id ${spent / 1_000_000} ms")
      s"inspections of ${file.getName}: ${nanos.map(_._2).sum / 1_000_000} ms; slowest: ${slowest.mkString(", ")}"
    }

  /**
   * Runs Java inspections over a file, each one constructed and run apart from the others: an inspection that throws
   * (or is missing a class) yields a run that failed, and the next still runs on the same file. Runs under the read
   * access of the caller. A cancellation propagates.
   *
   * The audit in `JavaInspectionsTest` runs every loaded registration through this, since the exclusions are the
   * ones that fail.
   *
   * @param file Java source with a buffer, queried under read access
   * @param beans the registrations to run, from the Java plugin's descriptors
   * @return one run per registration, in order, with its problems (and their fixes), time and failure
   * @throws ProcessCanceledException when the request is cancelled meanwhile
   */
  private[scalaanalyzer] def runJava(file: PsiJavaFile, beans: Seq[ExtensionBean]): Seq[Inspections.JavaRun] = {
    val manager = InspectionManager.getInstance(file.getProject)
    val elements = SyntaxTraverser.psiTraverser(file).asScala.toVector

    /** The result of `body`, or the failure of `phase`: a cancellation is not a failure. */
    def attempt[T](phase: Inspections.JavaPhase)(body: => T): Either[Inspections.JavaFailure, T] =
      try Right(body)
      catch {
        case e: ProcessCanceledException => throw e
        case e @ (NonFatal(_) | _: LinkageError) => Left(Inspections.JavaFailure(phase, e))
      }

    beans.map { bean =>
      val started = System.nanoTime()
      val loggedBefore = HostLog.threadCount(HostLog.Level.Error)
      val tool = attempt(Inspections.JavaPhase.Construction) {
        // `javaDescriptors` holds only local inspection registrations, whose classes are LocalInspectionTool.
        val constructed = Class.forName(bean.implementation).getConstructor().newInstance()
          .asInstanceOf[LocalInspectionTool]
        constructed.setNameProvider(nameProvider(bean))
        constructed
      }
      val found = tool.flatMap { constructed =>
        attempt(Inspections.JavaPhase.Run)(javaProblems(file, manager, elements, bean, constructed))
      }

      Inspections.JavaRun(inspectionId(bean), System.nanoTime() - started, found.getOrElse(Seq.empty),
        HostLog.threadCount(HostLog.Level.Error) - loggedBefore, found.left.toOption)
    }
  }

  /** The problems of one Java inspection, suppressed as the IDE does; none when it is not available for the file. */
  private def javaProblems(file: PsiJavaFile, manager: InspectionManager, elements: Seq[PsiElement],
      bean: ExtensionBean, tool: LocalInspectionTool): Seq[Diagnostic] =
    if (!tool.isAvailableForFile(file)) Seq.empty
    else {
      val holder = visit(file, manager, tool, onTheFly = true, elements)
      val unnecessaryTool = bean.attributes.get("editorAttributes").contains("NOT_USED_ELEMENT_ATTRIBUTES")

      problems(holder, inspectionId(bean), javaSuppressor, bean.attributes.get("alternativeId"), htmlMessages = true)(
        javaPresentation(level(bean), unnecessaryTool))
    }

  /**
   * How the IDE shows a Java problem: by the descriptor's highlight type when that says more than the registration's
   * level (a problem that only marks code, one that is an error whatever its inspection's level), else by the level.
   * Deprecated and unused code are struck through or greyed whatever the level.
   *
   * @param registered the registration's severity
   * @param unnecessaryTool whether the registration marks all its problems as unnecessary code
   */
  private def javaPresentation(registered: HighlightSeverity, unnecessaryTool: Boolean)(problem: ProblemDescriptor)
      : Inspections.Presentation = {
    val highlight = problem.getHighlightType
    val severity = highlight match {
      case ProblemHighlightType.ERROR | ProblemHighlightType.GENERIC_ERROR | ProblemHighlightType.LIKE_UNKNOWN_SYMBOL =>
        HighlightSeverity.ERROR
      case ProblemHighlightType.WARNING => HighlightSeverity.WARNING
      case ProblemHighlightType.WEAK_WARNING => HighlightSeverity.WEAK_WARNING
      case ProblemHighlightType.INFORMATION | ProblemHighlightType.INFO => HighlightSeverity.INFORMATION
      case _ => registered
    }
    val deprecated = highlight == ProblemHighlightType.LIKE_DEPRECATED ||
      highlight == ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL
    val unnecessary = unnecessaryTool || highlight == ProblemHighlightType.LIKE_UNUSED_SYMBOL

    Inspections.Presentation(severity, deprecated, unnecessary)
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

  /**
   * Converts native descriptors and fixes without discarding suppression, severity or range information.
   *
   * @param holder what an inspection reported
   * @param id the inspection's id, which suppressions name and the diagnostics carry
   * @param suppressor the language's suppressor: its comments and annotations drop a problem, and it offers the
   *                   suppressions beside the problem's fixes
   * @param alternativeId a second id that suppressions may name, when the registration has one
   * @param htmlMessages whether the messages are HTML, as the IDE shows them in a tooltip (the Java inspections'
   *                     are: `<code>` around names, escaped characters), to be rendered as plain text
   * @param presentation the severity and marks of a problem
   */
  private def problems(holder: ProblemsHolder, id: String, suppressor: InspectionSuppressor,
      alternativeId: Option[String], htmlMessages: Boolean)(presentation: ProblemDescriptor => Inspections.Presentation)
      : Seq[Diagnostic] = {
    def suppressed(problem: ProblemDescriptor): Boolean =
      (id +: alternativeId.toSeq).exists(suppressor.isSuppressedFor(problem.getPsiElement, _))

    holder.getResults.asScala.toSeq.filterNot { problem =>
      suppressed(problem) || (id == "ScalaUnusedSymbol" && hasLocalUsage(problem))
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
      // The IDE offers these beside the problem's fixes; the host previews them like any other fix. The plugin
      // returns one for every kind of container and the IDE offers those whose container encloses the problem:
      // the wrapper never asks a quick fix whether it is available, so any other would be offered with no edit.
      val suppressions = suppressor.getSuppressActions(problem.getPsiElement, id).toSeq.collect {
        case fix: AbstractBatchSuppressByNoInspectionCommentFix
            if fix.isAvailable(holder.getProject, problem.getPsiElement) =>
          range -> QuickFixWrapper.wrap(problem, new Inspections.Suppression(problem.getPsiElement, fix))
        // The Java plugin's suppressions are ModCommand fixes, which the platform previews on its own.
        case fix: AbstractBatchSuppressByNoInspectionCommentModCommandFix
            if fix.isAvailable(holder.getProject, problem.getPsiElement) =>
          range -> QuickFixWrapper.wrap(problem, fix)
      }
      val shown = presentation(problem)
      val rendered = ProblemDescriptorUtil.renderDescriptionMessage(problem, problem.getPsiElement)
      val message =
        if (htmlMessages) StringUtil.unescapeXmlEntities(StringUtil.removeHtmlTags(rendered, true)) else rendered

      Diagnostic(shown.severity, range, message, fixes ++ suppressions, Some(id), shown.deprecated, shown.unnecessary)
    }
  }
}
