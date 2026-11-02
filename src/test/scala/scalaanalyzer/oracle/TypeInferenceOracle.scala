package scalaanalyzer.oracle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiComment, PsiErrorElement}
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScInfixExpr, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.psi.types.api.presentation.TypePresentation
import org.jetbrains.plugins.scala.lang.psi.types.result.*
import org.jetbrains.plugins.scala.lang.psi.types.{Context, ScType, ScTypeExt, TypePresentationContext}
import org.jetbrains.plugins.scala.project.ScalaFeatures
import org.jetbrains.plugins.scala.{LatestScalaVersions, ScalaVersion}

import scalaanalyzer.{HostConfig, ScalaPsiHost}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Differential oracle: runs the plugin's own type-inference test data through the host and compares with the
 * expected types those files carry, which the plugin's CI checks inside IntelliJ.
 *
 * Which files to run, and under which Scala version, is read from the plugin's generated test classes
 * (`lang/typeInference/generated`), parsed with the host itself. A test class is used only when it runs with the
 * fixture's default setup: Scala 2.12 (`ScalaSdkOwner.defaultSdkVersion`), scala-library only, expected to pass.
 * Everything else is reported as skipped, with the reason.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.TypeInferenceOracle <intellij-scala checkout> <report.md> [--production-mode]
 * [--only=<test name>,...]`. `--only` runs just those tests (e.g. `SCL5475`), in a fresh host.
 *
 * By default the host runs as the plugin's fixture does (`isUnitTestMode`), because the plugin changes behaviour in
 * that mode and the expectations were recorded with it — e.g. top-level declarations of Scala 2 files resolve only
 * in tests (FileDeclarationsHolder, SCL-20481). `--production-mode` runs it as an IDE outside tests.
 */
object TypeInferenceOracle {
  /** The version the plugin's light fixture selects for tests without a `supportedIn` restriction. */
  private[oracle] val TestVersion: ScalaVersion = LatestScalaVersions.Scala_2_12

  def main(args: Array[String]): Unit = {
    val (Seq(repo, reportPath), flags) = args.toSeq.partition(!_.startsWith("--"))
    val unitTestMode = !flags.contains("--production-mode")
    val pluginRoot = Path.of(repo, "scala/scala-impl")
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      unitTestMode = unitTestMode,
    ))
    host.addJdk(Path.of(System.getProperty("java.home")))
    host.addLibrary(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary212")))

    val only = flags.collectFirst { case f if f.startsWith("--only=") => f.stripPrefix("--only=").split(',').toSet }
    val (allCases, skippedClasses) = host.read(TestClasses.collect(host, pluginRoot))
    val cases = only.fold(allCases)(names => allCases.filter(c => names.contains(c.testName)))
    val features = ScalaFeatures.onlyByVersion(TestVersion)
    val t0 = System.nanoTime()
    val outcomes = cases.map(c => c -> runCase(host, features, c))
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
    Report.write(Path.of(reportPath), outcomes, skippedClasses, elapsedMs)
    val agree = outcomes.count(_._2 == Outcome.Agree)
    println(s"oracle: $agree / ${outcomes.size} agree; report at $reportPath")
  }

  private def runCase(host: ScalaPsiHost, features: ScalaFeatures, c: Case): Outcome =
    try {
      // As TypeInferenceTestBase.configureFromFileText: trimmed text with normalized line separators.
      val text = Files.readString(c.file, StandardCharsets.UTF_8).trim.replace("\r\n", "\n")
      // As in the fixture, the test file is the project's only source file while the test runs.
      val file = host.openSource(text, features)
      try host.read {
        val parseErrors = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).asScala
        if (parseErrors.nonEmpty) Outcome.ParseError(parseErrors.head.getErrorDescription)
        else Expected.lastComment(file) match {
          case None => Outcome.Unusable("the file does not end with a comment")
          case Some(comment) =>
            compare(selectedExpression(file), Expected.forVersion(comment, TestVersion))
        }
      }
      finally host.closeSource(file)
    } catch {
      case NonFatal(e) =>
        // Where the failure came from: the first plugin or platform frames below the host's adapter layer.
        val origin = e.getStackTrace.iterator
          .filter(f => f.getClassName.startsWith("org.jetbrains.plugins.scala") || f.getClassName.startsWith("com.intellij"))
          .filterNot(f => f.getMethodName == "getInstance" || f.getMethodName == "getService")
          .take(3).map(f => s"${f.getClassName.split('.').last}.${f.getMethodName}:${f.getLineNumber}").mkString(" < ")
        Outcome.Crash(s"${e.getClass.getSimpleName}: ${e.getMessage}".take(300), origin)
    }

  /** As TypeInferenceTestFixture.findSelectedExpression. */
  private def selectedExpression(file: ScalaFile): ScExpression = {
    val text = file.getText
    val startOffset = text.indexOf("/*start*/") + "/*start*/".length
    val endOffset = text.indexOf("/*end*/")
    val atStart = PsiTreeUtil.getParentOfType(file.findElementAt(startOffset), classOf[ScExpression])
    val addOne = if (atStart != null) 0 else 1 // XML literals
    val expr = PsiTreeUtil.findElementOfClassAtRange(file, startOffset + addOne, endOffset, classOf[ScExpression])
    if (expr == null) throw new IllegalStateException("no expression between /*start*/ and /*end*/")
    expr
  }

  private val ExpectedPattern = """expected: (.*)""".r
  private val SimplifiedPattern = """simplified: (.*)""".r
  private val JavaTypePattern = """java type: (.*)""".r
  private val FewVariantsMarker = "Few variants:"

  /** As TypeInferenceTestFixture.assertExpressionType, with shouldPass = true. */
  private def compare(expression: ScExpression, expected: String): Outcome = {
    given TypePresentationContext = TypePresentationContext.emptyContextIn(TestVersion)
    given Context = Context.Empty
    val typeResult = expression.`type`() match {
      case Right(t) if t.isUnit => expression.getTypeIgnoreBaseType
      case other => other
    }
    typeResult match {
      case Failure(message) => Outcome.TypeFailure(message)
      case Right(actualType) =>
        def check(expectedText: String, actualText: String) =
          if (expectedText == actualText) Outcome.Agree else Outcome.Differ(expectedText, actualText)
        if (expected.startsWith(FewVariantsMarker)) {
          val variants = expected.substring(FewVariantsMarker.length).trim.split('\n').toSeq
          val actualText = actualType.presentableText
          if (variants.contains(actualText)) Outcome.Agree else Outcome.Differ(variants.head, actualText)
        }
        else expected match {
          case ExpectedPattern(expectedType) =>
            check(expectedType, expression.expectedType().map(_.presentableText).getOrElse("<none>"))
          case SimplifiedPattern(text) => check(text, TypePresentation.withoutAliases(actualType))
          case JavaTypePattern(text) =>
            check(text, expression.`type`().map(_.toPsiType.getPresentableText()).getOrElse("<none>"))
          case _ => check(expected, actualType.presentableText)
        }
    }
  }
}

final case class Case(testClass: String, testName: String, file: Path)

enum Outcome {
  case Agree
  case Differ(expected: String, actual: String)
  case TypeFailure(message: String)
  case ParseError(message: String)
  case Crash(message: String, origin: String)
  case Unusable(reason: String)
}

/** Expected results as TestUtils.extractExpectedResultFromLastComment and TypeInferenceTestFixture read them. */
object Expected {
  def lastComment(file: ScalaFile): Option[String] =
    file.findElementAt(file.getTextLength - 1) match {
      case comment: PsiComment =>
        val text = comment.getText
        comment.getNode.getElementType match {
          case ScalaTokenTypes.tLINE_COMMENT => Some(text.substring(2).trim)
          case ScalaTokenTypes.tBLOCK_COMMENT => Some(text.substring(2, text.length - 2).trim)
          case ScalaTokenTypes.tDOC_COMMENT => Some(text.substring(3, text.length - 2).trim)
          case _ => None
        }
      case _ => None
    }

  private val VersionPrefix = """^\[Scala_([\w\d_]*)\](.*)""".r
  // As ScalaSdkOwner.allTestVersions.
  private val allTestVersions: Seq[ScalaVersion] =
    LatestScalaVersions.allStableWithoutScalaNext.flatMap(_.generateAllMinorVersions).distinct.sorted

  /** As TypeInferenceTestFixture.extractTextForCurrentVersion: `[Scala_2_13]...` lines select per-version text. */
  def forVersion(text: String, version: ScalaVersion): String = {
    def selectVersion(name: String): Option[ScalaVersion] = {
      val dotted = name.replace('_', '.')
      allTestVersions.find(_.minor == dotted).orElse(allTestVersions.filter(_.major == dotted).lastOption)
    }
    // (version, text) sections in order; a section without a prefix starts with no version.
    val sections = text.split('\n').foldLeft(Vector((Option.empty[ScalaVersion], ""))) { (acc, line) =>
      val prefixed = line match {
        case VersionPrefix(name, tail) => selectVersion(name.trim).map(_ -> tail)
        case _ => None
      }
      prefixed match {
        case Some((v, tail)) => acc :+ (Some(v) -> tail)
        case None =>
          val (v, current) = acc.last
          acc.init :+ (v -> (if (current.isEmpty) line else current + "\n" + line))
      }
    }
    if (sections.size == 1) sections.head._2
    else {
      val withVersions = sections.map { case (v, t) => v.getOrElse(allTestVersions.head) -> t }
      withVersions.zip(withVersions.tail)
        .find { case ((v1, _), (v2, _)) => v1 <= version && version < v2 }
        .map(_._1._2)
        .getOrElse(withVersions.last._2)
    }
  }
}

/** Test cases from the plugin's generated type-inference test classes, read as PSI. */
object TestClasses {
  private val UnsupportedSetup = Set("additionalLibraries", "librariesLoaders", "shouldPass", "defaultVersionOverride")

  /** Returns the runnable cases and, per skipped class, why it was skipped. */
  def collect(host: ScalaPsiHost, pluginRoot: Path): (Seq[Case], Seq[(String, String)]) = {
    val testDir = pluginRoot.resolve("test/org/jetbrains/plugins/scala/lang/typeInference/generated")
    val dataRoot = pluginRoot.resolve("testdata/typeInference")
    val sources = Files.list(testDir).iterator().asScala.filter(_.toString.endsWith(".scala")).toSeq.sortBy(_.toString)
    val features = ScalaFeatures.onlyByVersion(LatestScalaVersions.Scala_3_LTS) // the plugin's tests are Scala 3
    val results = for {
      source <- sources
      file = host.parse(Files.readString(source), features)
      classes = PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).asScala.toSeq
      byName = classes.map(c => c.name -> c).toMap
      cls <- classes if !cls.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)
    } yield {
      // The class and its bases declared in the same file (where folderPath and the setup are overridden).
      val chain = Iterator.iterate(Option(cls))(_.flatMap(c => c.extendsBlock.templateParents.flatMap(_.firstParentClause)
        .flatMap(p => byName.get(p.typeElement.getText)))).takeWhile(_.isDefined).flatten.toSeq
      def overridden(name: String) = chain.flatMap(_.functions.collect { case f: ScFunctionDefinition if f.name == name => f })
      val folder = overridden("folderPath").headOption.flatMap(f =>
        PsiTreeUtil.findChildrenOfType(f, classOf[ScLiteral]).asScala.headOption.map(_.getValue.toString))
      val unsupportedSetup = UnsupportedSetup.filter(overridden(_).nonEmpty)
      val versionSupported = overridden("supportedIn").headOption.map(f => admitsTestVersion(f))
      if (unsupportedSetup.nonEmpty) Left(cls.name -> s"overrides ${unsupportedSetup.mkString(", ")}")
      else if (versionSupported.contains(None)) Left(cls.name -> "supportedIn not understood")
      else if (versionSupported.contains(Some(false))) Left(cls.name -> "not run on Scala 2.12")
      else folder match {
        case None => Left(cls.name -> "no folderPath")
        case Some(dir) =>
          Right(cls.functions.collect {
            case f: ScFunctionDefinition if f.name.startsWith("test") && f.body.exists(_.getText == "doTest()") =>
              val name = f.name.stripPrefix("test")
              Case(cls.name, name, dataRoot.resolve(dir).resolve(name + ".scala"))
          })
      }
    }
    (results.collect { case Right(cases) => cases }.flatten, results.collect { case Left(skip) => skip })
  }

  /**
   * Evaluates `supportedIn` bodies of the form `version <op> LatestScalaVersions.Scala_X_Y` (or `ScalaVersion.Latest`)
   * for the test version; None for any other form.
   */
  private def admitsTestVersion(f: ScFunctionDefinition): Option[Boolean] = f.body match {
    case Some(infix: ScInfixExpr) =>
      val bound = infix.right match {
        case ref: ScReferenceExpression => LatestScalaVersions.allStableWithoutScalaNext.find(v => s"Scala_${v.major.replace('.', '_')}" == ref.refName)
        case _ => None
      }
      bound.flatMap { b =>
        val cmp = TypeInferenceOracle.TestVersion.compare(b)
        infix.operation.refName match {
          case ">=" => Some(cmp >= 0)
          case ">" => Some(cmp > 0)
          case "<=" => Some(cmp <= 0)
          case "<" => Some(cmp < 0)
          case "==" => Some(cmp == 0)
          case _ => None
        }
      }
    case _ => None
  }
}

object Report {
  def write(path: Path, outcomes: Seq[(Case, Outcome)], skipped: Seq[(String, String)], elapsedMs: Long): Unit = {
    def kind(o: Outcome) = o match {
      case Outcome.Agree => "agree"
      case _: Outcome.Differ => "differ"
      case _: Outcome.TypeFailure => "type failure"
      case _: Outcome.ParseError => "parse error"
      case _: Outcome.Crash => "crash"
      case _: Outcome.Unusable => "unusable"
    }
    val counts = outcomes.groupBy(o => kind(o._2)).view.mapValues(_.size).toMap
    val lines = Vector.newBuilder[String]
    lines += "# Type-inference oracle"
    lines += ""
    val mode = if (ApplicationManager.getApplication.isUnitTestMode) "unit-test mode, as the fixture" else "production mode"
    lines += s"${outcomes.size} cases in ${elapsedMs} ms (Scala 2.12 test data, scala-library 2.12.21, JDK ${System.getProperty("java.version")}, $mode)."
    lines += ""
    lines += "| Outcome | Cases |"
    lines += "|---|---|"
    for (k <- Seq("agree", "differ", "type failure", "parse error", "crash", "unusable"))
      lines += s"| $k | ${counts.getOrElse(k, 0)} |"
    lines += ""
    lines += "## Disagreements"
    lines += ""
    lines += "| Test | Outcome | Expected | Actual / message |"
    lines += "|---|---|---|---|"
    def cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
    for ((c, o) <- outcomes if o != Outcome.Agree) {
      val (expected, actual) = o match {
        case Outcome.Differ(e, a) => (e, a)
        case Outcome.TypeFailure(m) => ("", m)
        case Outcome.ParseError(m) => ("", m)
        case Outcome.Crash(m, origin) => ("", s"$m (at $origin)")
        case Outcome.Unusable(r) => ("", r)
        case Outcome.Agree => ("", "")
      }
      lines += s"| ${c.testClass}.${c.testName} | ${kind(o)} | ${cell(expected)} | ${cell(actual)} |"
    }
    lines += ""
    lines += "## Skipped test classes"
    lines += ""
    for ((cls, reason) <- skipped) lines += s"- $cls: $reason"
    Files.createDirectories(path.toAbsolutePath.getParent)
    Files.write(path, lines.result().asJava)
  }
}
