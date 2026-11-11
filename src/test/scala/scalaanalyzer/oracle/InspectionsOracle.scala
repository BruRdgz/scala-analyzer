package scalaanalyzer.oracle

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.extensions.PsiModifierListOwnerExt
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScGenericCall, ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.{LatestScalaVersions, ScalaVersion}

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost, TestHost}
import scalaanalyzer.language.CodeActions
import scalaanalyzer.platform.{Descriptors, ExtensionBean, HostLog}

import java.io.File
import java.nio.file.{Files, Path}
import java.text.MessageFormat
import java.util.{Locale, ResourceBundle}

import scala.collection.mutable
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Replays the plugin's tests of its collection inspections (`codeInspection/collections`) over the host's
 * diagnostics and code actions: the `doTest`, `checkTextHasError`, `checkTextHasNoErrors` and `testQuickFix` calls
 * with literal texts, as `ScalaQuickFixTestFixture` and `ScalaAnnotatorQuickFixTestBase` run them. PSI identifies the
 * calls and the members they read (the inspection, the hint, the supported versions), resolved through the bases in
 * the directory; the test setup is never executed. Each call is one case, and tests of an inspection that the IDE's
 * default profile does not enable are skipped, since the host does not run it.
 *
 * The host runs in unit-test mode, as the plugin's fixture does: the texts have top-level definitions, which the
 * plugin resolves in Scala 2 files only then (`FileDeclarationsHolder`).
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.InspectionsOracle <intellij-scala checkout> <report.md>`.
 */
object InspectionsOracle {
  /** One check of a plugin test, on a text that may hold `<selection>` markers. */
  private enum Step {
    /** The text has exactly the expected highlights (`checkTextHasError`). */
    case HasError(text: String)
    /** The text has no highlight (`checkTextHasNoErrors`). */
    case HasNoErrors(text: String)
    /** The fix titled `hint` turns the text into `expected` (`testQuickFix`). */
    case Fix(text: String, expected: String, hint: String)
  }

  /** What a case runs: the inspection's id and the message of its problems, the module to open files in, the checks. */
  private final case class Replay(id: String, description: String, module: ModuleSpec, steps: Seq[Step])
  /** What a test class resolves to: the inspection's registration, the module of its Scala version, its literal values. */
  private final case class Setup(bean: ExtensionBean, module: ModuleSpec, loader: ClassLoader, values: Map[String, String])
  /** A call of a plugin test, with what it replays or the reason it cannot be replayed. */
  private final case class Planned(name: String, inspection: String, replay: Either[String, Replay])
  /** The outcome of a case: `agree`, `disagree: <reason>` or `skip: <reason>`. */
  private final case class Result(name: String, inspection: String, outcome: String)

  /** The package of the plugin's collection inspections, and of their tests. */
  private val Package = "org.jetbrains.plugins.scala.codeInspection.collections."
  private val Start = "<selection>"
  private val End = "</selection>"
  private val Caret = "<caret>"
  /** The calls of `ScalaQuickFixTestFixture` and `OperationsOnCollectionInspectionTest` that the tests check with. */
  private val Checks = Set("doTest", "checkTextHasError", "checkTextHasNoErrors", "testQuickFix")
  /** The calls a test may make besides its checks: the ones that evaluate its texts. */
  private val Evaluating = Checks ++ Set("stripMargin", "trim")
  /** `supportedIn` bodies of the form the tests use, such as `version >= LatestScalaVersions.Scala_2_13`. */
  private val Bound = """version\s*(<=|>=|==|<|>)\s*LatestScalaVersions\.(Scala_\d_\d+)""".r

  // ScalaSdkOwner.preferableSdkVersion, which ScalaInspectionTestBase.defaultVersionOverride selects.
  private val Preferable = LatestScalaVersions.Scala_2_13
  // ScalaSdkOwner.allTestVersions: every minor version of the stable versions without Scala Next.
  private val AllVersions = LatestScalaVersions.allStableWithoutScalaNext.flatMap(_.generateAllMinorVersions).distinct.sorted

  def main(args: Array[String]): Unit = {
    val Seq(repo, report) = args.toSeq
    val jars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_))
    // The plugin's tests run in unit-test mode, which resolves the top-level definitions their texts rely on, and
    // without the build's compiler highlighting.
    val host = ScalaPsiHost.start(HostConfig(Path.of(sys.props("scalaanalyzer.stateDir")), jars, unitTestMode = true,
      compilerErrorsScala3 = false))
    val library = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary"))
    val library212 = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary212"))
    val library3 = Path.of(classOf[Mirror].getProtectionDomain.getCodeSource.getLocation.toURI)
    host.addJdk(Path.of(System.getProperty("java.home")))
    Seq(library, library212, library3).foreach(host.addLibrary(_))
    val registrations = Descriptors.scalaExtensionBeans(jars, "com.intellij.localInspection")
    val directory = Path.of(repo).resolve("scala/scala-impl/test/org/jetbrains/plugins/scala/codeInspection/collections")
    val paths = Using.resource(Files.list(directory))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toVector.sorted)
    val sources = paths.map(path => path -> host.parse(Files.readString(path)))
    val classpaths = Map("2.12" -> Seq(library212), "2.13" -> Seq(library))
    val modules = mutable.Map.empty[String, HostModule]

    val results = try plan(host, registrations, sources, classpaths, Seq(library, library3)).map { planned =>
      val outcome = planned.replay.fold(reason => s"skip: $reason", run(host, modules, planned.name, _))
      println(s"${planned.name}: $outcome")
      Result(planned.name, planned.inspection, outcome)
    } finally sources.foreach((_, source) => host.closeSource(source))

    def summary(cases: Seq[Result]) = s"${cases.count(_.outcome == "agree")} agree, " +
      s"${cases.count(_.outcome.startsWith("disagree"))} disagree, ${cases.count(_.outcome.startsWith("skip"))} skip"
    val inspections = results.groupBy(_.inspection).toSeq.sortBy(_._1).map((name, cases) => s"- $name: ${summary(cases)}")
    val skips = results.map(_.outcome).filter(_.startsWith("skip: ")).groupBy(identity).toSeq.sortBy(_._1)
      .map((reason, cases) => s"- ${reason.stripPrefix("skip: ")}: ${cases.size}")
    Files.writeString(Path.of(report), "# Inspections oracle\n\nCollection inspection tests of the plugin: " +
      "`doTest`, `checkTextHasError`, `checkTextHasNoErrors` and `testQuickFix` calls with literal texts.\n\n" +
      summary(results) + "\n\n## Per inspection\n\n" + inspections.mkString("\n") + "\n\n## Skips\n\n" +
      skips.mkString("\n") + "\n\n## Cases\n\n" + results.map(result => s"- ${result.name}: ${result.outcome}").mkString("\n") + "\n")
    println(summary(results))
    sys.exit(0)
  }

  /** Reads every call of every concrete test class: its checks, or why they cannot be replayed. */
  private def plan(host: ScalaPsiHost, registrations: Seq[ExtensionBean], sources: Seq[(Path, ScalaFile)],
                   classpaths: Map[String, Seq[Path]], classpath3: Seq[Path]): Vector[Planned] =
    host.read {
      val declared = sources.flatMap((path, file) =>
        PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeDefinition]).asScala.map(_ -> path.getFileName.toString)).toVector
      val classes = declared.map((cls, _) => cls.name -> cls).toMap

      declared.filterNot((cls, _) => cls.hasAbstractModifier).flatMap { (cls, fileName) =>
        // The class and the bases the plugin's tests inherit through, most derived first.
        val chain = Iterator.iterate(Option(cls))(_.flatMap(current =>
          current.extendsBlock.templateParents.flatMap(_.typeElements.headOption).flatMap(parent => classes.get(parent.getText))))
          .takeWhile(_.nonEmpty).flatten.toVector
        val tests = chain.flatMap(_.functions).collect { case method: ScFunctionDefinition if method.name.startsWith("test") => method }
          .distinctBy(_.name)
        val inspectionClass = chain.iterator.flatMap(_.members).collectFirst {
          case definition: ScPatternDefinition if definition.bindings.exists(_.name == "classOfInspection") => definition.expr
        }.flatten.collect { case call: ScGenericCall => call.typeArguments.head.getText }
        val registration = inspectionClass.flatMap(name => registrations.find(_.implementation == Package + name))

        val setup: Either[String, Setup] = for {
          bean <- registration.toRight("inspection is not a registered local inspection")
          // ScalaInspectionTestBase enables the inspection whatever its default, but the host runs the enabled ones only.
          _ <- Either.cond(bean.attributes.get("enabledByDefault").contains("true"), (), "disabled in the IDE's default profile")
          supported <- supportedIn(chain).toRight("unsupported supportedIn override")
          version <- selectVersion(supported).toRight("no Scala version supports the test")
          classpath <- (if (version.isScala3) Some(classpath3) else classpaths.get(version.major))
            .toRight(s"no Scala ${version.major} library among the fixtures")
          // The plugin's bundles are resources of the jars that the inspection's class is loaded from.
          loader = Class.forName(bean.implementation).getClassLoader
          values <- classValues(chain, loader)
        } yield Setup(bean, ModuleSpec(s"inspections-${version.minor}", version.minor, Seq.empty, classpath), loader, values)

        tests.flatMap { method =>
          val calls = PsiTreeUtil.findChildrenOfType(method, classOf[ScMethodCall]).asScala.toVector
          val checks = calls.filter(call => Checks(called(call)))
          val foreign = calls.map(called).filterNot(Evaluating).distinct
          val base = s"$fileName/${cls.name}/${method.name}"
          val names = if (checks.size == 1) Seq(base) else checks.indices.map(index => s"$base#${index + 1}")

          if (checks.isEmpty) Seq(Planned(base, inspectionClass.getOrElse("unknown"), Left("no replayable call")))
          else checks.zip(names).map { (call, name) =>
            val replay = for {
              resolved <- setup
              _ <- Either.cond(foreign.isEmpty, (), s"custom setup: ${foreign.mkString(", ")}")
              known = methodValues(method, resolved.values, resolved.loader)
              steps <- stepsOf(call, known, resolved.loader)
              bean = resolved.bean
            } yield Replay(bean.attributes.getOrElse("id", bean.attributes("shortName")), known("description"), resolved.module, steps)
            Planned(name, inspectionClass.getOrElse("unknown"), replay)
          }
        }
      }
    }

  /** The name of the method a call invokes when it is a plain reference, otherwise empty. */
  private def called(call: ScMethodCall): String = call.getInvokedExpr match {
    case reference: ScReferenceExpression => reference.refName
    case _ => ""
  }

  /** The predicate of the most derived `supportedIn` override, true everywhere when there is none. */
  private def supportedIn(chain: Seq[ScTypeDefinition]): Option[ScalaVersion => Boolean] =
    chain.flatMap(_.functions).collectFirst { case method: ScFunctionDefinition if method.name == "supportedIn" => method } match {
      case None => Some(_ => true)
      case Some(method) => method.body.map(_.getText.trim).flatMap {
        case "version.isScala3" => Some((version: ScalaVersion) => version.isScala3)
        case Bound(operator, constant) =>
          LatestScalaVersions.allScala2.find(version => s"Scala_${version.major.replace('.', '_')}" == constant).map { bound =>
            operator match {
              case "<=" => (version: ScalaVersion) => version <= bound
              case ">=" => (version: ScalaVersion) => version >= bound
              case "<" => (version: ScalaVersion) => version < bound
              case ">" => (version: ScalaVersion) => version > bound
              case _ => (version: ScalaVersion) => version == bound
            }
          }
        case _ => None
      }
    }

  /** ScalaSdkOwner.version: the first supported version from the preferable one up (the latest, among Scala 3), else the last one. */
  private def selectVersion(supported: ScalaVersion => Boolean): Option[ScalaVersion] = {
    val versions = AllVersions.filter(supported)
    val above = versions.dropWhile(_ < Preferable)

    above.headOption.map(first => if (first.isScala3) above.last else first).orElse(versions.lastOption)
  }

  /**
   * The literal values of the class and its bases, with the markers. The most derived definition of a name wins;
   * `description` is the hint unless a test says otherwise (`OperationsOnCollectionInspectionTest`).
   */
  private def classValues(chain: Seq[ScTypeDefinition], loader: ClassLoader): Either[String, Map[String, String]] = {
    val definitions = chain.reverse.flatMap(_.members).flatMap {
      case definition: ScPatternDefinition if definition.bindings.size == 1 => definition.expr.map(definition.bindings.head.name -> _)
      case _ => None
    }.toMap
    var values = Map("CARET" -> Caret, "START" -> Start, "END" -> End)
    var settled = false

    // A definition may read one that comes after it, such as the base's `description = hint`.
    while (!settled) {
      val resolved = definitions.filterNot((name, _) => values.contains(name))
        .flatMap((name, expression) => evaluate(expression, values, loader).map(name -> _))
      values ++= resolved
      settled = resolved.isEmpty
    }

    values.get("description").orElse(values.get("hint")).toRight("non-literal hint")
      .map(description => values + ("description" -> description.replace("\r\n", "\n").trim))
  }

  /** The values a test's own `val`s add, in order. */
  private def methodValues(method: ScFunctionDefinition, values: Map[String, String], loader: ClassLoader): Map[String, String] =
    PsiTreeUtil.findChildrenOfType(method, classOf[ScPatternDefinition]).asScala.toVector.sortBy(_.getTextOffset)
      .foldLeft(values) { (known, definition) =>
        if (definition.bindings.size == 1)
          definition.expr.flatMap(evaluate(_, known, loader)).fold(known)(value => known + (definition.bindings.head.name -> value))
        else known
      }

  /** The checks of a call, from its literal arguments. */
  private def stepsOf(call: ScMethodCall, values: Map[String, String], loader: ClassLoader): Either[String, Seq[Step]] = {
    val texts = call.argumentExpressions.map(evaluate(_, values, loader))

    (called(call), texts) match {
      case _ if texts.exists(_.isEmpty) => Left("non-literal test text")
      case _ if texts.flatten.exists(_.contains(Caret)) => Left("caret-filtered highlights are not replayed")
      case ("checkTextHasError", Seq(Some(text))) => Right(Seq(Step.HasError(text)))
      case ("checkTextHasNoErrors", Seq(Some(text))) => Right(Seq(Step.HasNoErrors(text)))
      case ("testQuickFix", Seq(Some(text), Some(expected), Some(hint))) => Right(Seq(Step.Fix(text, expected, hint)))
      // OperationsOnCollectionInspectionTest.doTest: the highlight of `selected`, then the fix of `text` that the hint names.
      case ("doTest", Seq(Some(selected), Some(text), Some(result))) =>
        values.get("hint").toRight("non-literal hint").map(hint => Seq(Step.HasError(selected), Step.Fix(text, result, hint)))
      case _ => Left("unexpected arguments")
    }
  }

  /**
   * The text of an expression: a literal as `CompletionOracle.staticString` reads it, or a
   * `ScalaInspectionBundle.message` call, read from the plugin's bundle as the platform's `AbstractBundle` formats it
   * (the arguments apply when the message has a `{`).
   */
  private def evaluate(expression: ScExpression, values: Map[String, String], loader: ClassLoader): Option[String] = expression match {
    case call: ScMethodCall if call.getInvokedExpr.getText == "ScalaInspectionBundle.message" =>
      val bundle = ResourceBundle.getBundle("messages.ScalaInspectionBundle", Locale.ROOT, loader)
      call.argumentExpressions.map(evaluate(_, values, loader)) match {
        case Some(key) +: arguments if bundle.containsKey(key) && arguments.forall(_.nonEmpty) =>
          val pattern = bundle.getString(key)
          Some(if (arguments.nonEmpty && pattern.contains('{')) MessageFormat.format(pattern, arguments.flatten*) else pattern)
        case _ => None
      }
    case _ => CompletionOracle.staticString(expression, values).orElse(objectMessage(expression, loader))
  }

  /**
   * A message that one of the plugin's collection objects defines, such as `RedundantHeadOption.hint`: read by
   * calling it, since the tests name the simplification rather than its bundle key.
   */
  private def objectMessage(expression: ScExpression, loader: ClassLoader): Option[String] = expression match {
    case member: ScReferenceExpression => member.qualifier match {
      case Some(owner: ScReferenceExpression) if owner.qualifier.isEmpty =>
        try {
          val module = Class.forName(Package + owner.refName + "$", true, loader)
          module.getMethod(member.refName).invoke(module.getField("MODULE$").get(null)) match {
            case message: String => Some(message)
            case _ => None
          }
        } catch {
          case _: ClassNotFoundException | _: NoSuchMethodException => None
        }
      case _ => None
    }
    case _ => None
  }

  /** Runs the checks of a case in a module of its version; the first failing one decides. */
  private def run(host: ScalaPsiHost, modules: mutable.Map[String, HostModule], name: String, replay: Replay): String = {
    val module = modules.getOrElseUpdate(replay.module.name, host.addModule(replay.module))
    val errors = HostLog.count(HostLog.Level.Error)

    try replay.steps.view.flatMap(check(host, module, name, replay, _)).headOption match {
      case Some(reason) => s"disagree: $reason"
      case None if HostLog.count(HostLog.Level.Error) != errors => "disagree: platform logged an error"
      case None => "agree"
    } catch {
      case e @ (NonFatal(_) | _: LinkageError) => s"disagree: ${e.getClass.getSimpleName}: ${e.getMessage}"
    }
  }

  /** Why the host disagrees with one check, none when it agrees. */
  private def check(host: ScalaPsiHost, module: HostModule, name: String, replay: Replay, step: Step): Option[String] = {
    val (source, selections) = step match {
      case Step.HasError(text) => withoutMarkers(text)
      case Step.HasNoErrors(text) => withoutMarkers(text)
      case Step.Fix(text, _, _) => withoutMarkers(text)
    }
    val file = host.openFile(Path.of("/inspections-oracle", s"$name.scala"), source, module).asInstanceOf[ScalaFile]

    try {
      val highlights = host.diagnostics(file)
      // The fixture's highlights are those of the one enabled inspection whose description matches.
      val matching = highlights.filter(problem => problem.inspectionId.contains(replay.id) && problem.message == replay.description)
      val found = matching.map(_.range)

      step match {
        // ScalaQuickFixTestFixture.assertTextHasError, without allowAdditionalHighlights.
        case Step.HasError(_) =>
          if (found.isEmpty) Some(s"no highlight with the description; highlights: ${highlights.map(h => (h.inspectionId, h.range, h.message))}")
          else if (!selections.forall(found.contains)) Some(s"highlights at $found, not at ${selections.filterNot(found.contains)}")
          else if (found.distinct.size != found.size) Some(s"duplicate highlights at $found")
          else if (found.size != selections.size) Some(s"highlights at $found, expected only $selections")
          else None
        // ScalaAnnotatorQuickFixTestBase.checkTextHasNoErrors.
        case Step.HasNoErrors(_) => Option.when(found.nonEmpty)(s"highlights at $found")
        // ScalaQuickFixTestFixture.testQuickFix: the first fix titled `hint` of the matching highlights.
        case Step.Fix(_, expected, hint) =>
          val actions = new CodeActions(Seq.empty, Seq.empty, Seq.empty)
          val offered = matching.flatMap(problem => host.read(actions.collect(file, problem.range, Seq(problem))))
          require(host.read(file.getText) == source, "code actions changed the source")
          offered.find(_.title == hint) match {
            case _ if matching.isEmpty => Some("no highlight with the description")
            case None => Some(s"fix not offered; offered: ${offered.map(_.title)}")
            case Some(action) if action.disabled.nonEmpty => Some(s"fix disabled: ${action.disabled.mkString}")
            case Some(action) if action.edits.isEmpty => Some("fix without edits")
            case Some(action) =>
              val actual = TestHost.applyEdits(source, action.edits).trim
              val wanted = expected.replace("\r\n", "\n").replace(Caret, "").trim
              Option.when(actual != wanted)(s"expected ${wanted.replace("\n", "\\n")}; actual ${actual.replace("\n", "\\n")}")
          }
      }
    } finally host.closeSource(file)
  }

  /** The text the fixture opens (separators normalized, trimmed) without its selection markers, and the ranges they enclose. */
  private def withoutMarkers(text: String): (String, Seq[TextRange]) = {
    val normalized = text.replace("\r\n", "\n").trim
    val source = new StringBuilder
    val open = mutable.Stack.empty[Int]
    val ranges = Vector.newBuilder[TextRange]
    var index = 0

    while (index < normalized.length) {
      if (normalized.startsWith(Start, index)) {
        open.push(source.length)
        index += Start.length
      } else if (normalized.startsWith(End, index)) {
        ranges += new TextRange(open.pop(), source.length)
        index += End.length
      } else {
        source += normalized(index)
        index += 1
      }
    }
    require(open.isEmpty, "unclosed selection marker")

    (source.toString, ranges.result())
  }
}
