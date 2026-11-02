package scalaanalyzer.oracle

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.LatestScalaVersions
import org.jetbrains.plugins.scala.autoImport.quickFix.ScalaImportTypeFix
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScInterpolatedStringLiteral, ScLiteral, ScReference}
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes.*

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost, TestHost}
import scalaanalyzer.platform.{CallLog, HostDispatchThread, HostLog}
import scalaanalyzer.language.{ChangeEngine, Completion}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.FutureTask

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Replays upstream completion and auto-import contracts without their IDE UI harness.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.CompletionOracle <intellij-scala checkout> <report.md> [--only=<name>,...]`.
 */
object CompletionOracle {
  private final case class InsertionCase(before: String, after: String, lookup: String, char: Char)
  private final case class TestCase(suite: String, name: String, path: Path, skip: Option[String],
                                    insertion: Option[InsertionCase] = None)
  private final case class Result(outcome: String, reason: String)

  def main(args: Array[String]): Unit = {
    val (Seq(repo, reportPath), flags) = args.toSeq.partition(!_.startsWith("--"))
    val pluginRoot = Path.of(repo, "scala/scala-impl")
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      unitTestMode = true,
    ))
    host.addJdk(Path.of(System.getProperty("java.home")))
    host.addLibrary(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))
    val module = host.addModule(ModuleSpec("completion-oracle", "2.13.18", Seq.empty,
      Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
    lazy val importModule = {
      val library = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary212"))
      host.addLibrary(library)
      host.addModule(ModuleSpec("auto-import-oracle", "2.12.21", Seq.empty, Seq(library)))
    }
    val only = flags.collectFirst { case f if f.startsWith("--only=") => f.stripPrefix("--only=").split(',').toSet }
    val cases = host.read(collect(host, pluginRoot) ++ collectMethods(host, pluginRoot) ++ collectImports(pluginRoot)).filter(c => only.forall(_.contains(c.name)))
    val started = System.nanoTime()
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = c.skip.fold {
        if (c.suite == "autoImport") runImport(host, importModule, c)
        else c.insertion.fold(runCase(host, module, c))(replayInsertion(host, module, c, _))
      }(r => Result("skip", r))
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    val suites = results.map(_._1.suite).distinct.sorted
    val lines = Vector.newBuilder[String]
    lines += "# Completion oracle"
    lines += ""
    lines += s"Completion Scala 2.13.18; auto-import Scala 2.12.21 (ScalaSdkOwner default); unit-test mode, JDK ${System.getProperty("java.version")}; ${(System.nanoTime() - started) / 1_000_000} ms."
    lines += ""
    lines += "File fixtures compare lookup membership, strikeout and multiplicity. completion3 methods are parsed with the host's Scala PSI; " +
      "statically evaluable before/after strings and lookup selections are replayed, including CARET interpolation and stripMargin. " +
      "Each method is reported once under its declaring test class. Unsupported setup, dynamic expressions, SMART, second invocation, " +
      "selections/templates and predicate-based lookup selections have explicit skip reasons. Auto-import checks resulting text through the copy engine."
    lines += ""
    lines += "| Suite | Agree | Disagree | Skip |"
    lines += "|---|---:|---:|---:|"
    for (suite <- suites) {
      val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
      lines += s"| $suite | ${outcomes.count(_ == "agree")} | ${outcomes.count(_ == "disagree")} | ${outcomes.count(_ == "skip")} |"
    }
    lines += ""
    lines += "## Replay limits and disagreements"
    lines += ""
    lines += "Interactive lists render up to Completion.MaxSuggestions items and look ahead for an incomplete list; handlers run only after choosing an item. File-set replay disables the list budget; keyword fixtures collect only keyword results, as upstream. Selected-item replay has no list budget. " +
      "Completion uses Scala 2.13.18 and the host's registered contributors; Java binary short-name enumeration and other contributors remain unavailable. " +
      "Auto-import mounts Source*.scala fixture companions from the case directory; additional IDE fixture configuration is not inferred."
    lines += ""
    results.filter(_._2.outcome != "agree").groupMapReduce { (_, result) =>
      result.outcome + ": " + result.reason.takeWhile(_ != ':')
    }(_ => 1)(_ + _).toSeq.sortBy((reason, count) => (-count, reason)).foreach { (reason, count) =>
      lines += s"- $reason: $count"
    }
    lines += ""
    lines += "## Cases"
    lines += ""
    lines += "| Suite / case | Outcome | Reason |"
    lines += "|---|---|---|"
    def cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
    for ((c, r) <- results) lines += s"| ${c.suite}/${c.name} | ${r.outcome} | ${cell(r.reason)} |"
    lines += ""
    lines += "## Adapter observations"
    lines += ""
    for ((entry, count) <- CallLog.snapshot if entry.startsWith("UNSUPPORTED ") || entry.startsWith("LOGGED ERROR ") || entry.startsWith("Completion.skipped."))
      lines += s"- $entry: $count"
    val report = Path.of(reportPath)
    Files.createDirectories(report.toAbsolutePath.getParent)
    Files.write(report, lines.result().asJava)
    for (suite <- suites) {
      val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
      println(s"$suite: ${outcomes.count(_ == "agree")} agree, ${outcomes.count(_ == "disagree")} disagree, ${outcomes.count(_ == "skip")} skip")
    }
    println(s"completion oracle: report at $reportPath")
    sys.exit(0)
  }

  private def collect(host: ScalaPsiHost, root: Path): Vector[TestCase] = {
    val testRoot = root.resolve("test/org/jetbrains/plugins/scala/lang/completion")
    Vector("completion" -> testRoot.resolve("generated"), "keywordCompletion" -> testRoot.resolve("keyword/generated")).flatMap { (suite, dir) =>
      val sources = Using.resource(Files.list(dir))(_.iterator().asScala.filter(_.toString.endsWith(".scala")).toVector.sorted)
      sources.flatMap { source =>
        val file = host.parse(Files.readString(source))
        PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).asScala.toVector.flatMap { cls =>
          val folders = cls.functions.collect { case f: ScFunctionDefinition if f.name == "folderPath" => f }
            .flatMap(f => PsiTreeUtil.findChildrenOfType(f, classOf[ScLiteral]).asScala.map(_.getValue.toString))
          cls.functions.collect {
            case f: ScFunctionDefinition if f.name.startsWith("test") =>
              val name = f.name.stripPrefix("test")
              val fileName = if (suite == "keywordCompletion") name.head.toLower + name.tail else name
              val path = folders.foldLeft(root.resolve(s"testdata/$suite"))(_.resolve(_)).resolve(fileName + ".scala")
              val reason = if (cls.name.contains("Smart")) Some("SMART completion is out of scope")
                else if (!f.body.exists(_.getText == "doTest()")) Some("custom test setup is not replayed")
                else None
              TestCase(suite, name, path, reason)
          }
        }
      }
    }
  }

  /** Only PSI-recognized expressions are evaluated; arbitrary test code is never executed. */
  private[oracle] def staticString(expression: ScExpression, values: Map[String, String]): Option[String] = expression match {
    case literal: ScInterpolatedStringLiteral if literal.referenceName == "s" =>
      val injections = literal.getInjections.toSet
      var firstPart = true
      val pieces = literal.getNode.getChildren(null).toVector.map { node =>
        node.getElementType match {
          case `tINTERPOLATED_STRING` | `tINTERPOLATED_MULTILINE_STRING` =>
            val raw = if (firstPart) node.getText.stripPrefix(if (literal.isMultiLineString) "\"\"\"" else "\"") else node.getText
            firstPart = false
            Some(StringContext.processEscapes(raw))
          case `tINTERPOLATED_STRING_ESCAPE` => Some("$")
          case `tINTERPOLATED_STRING_END` | `tINTERPOLATED_STRING_INJECTION` => Some("")
          case _ => node.getPsi match {
            case expression: ScExpression if injections(expression) => staticString(expression, values)
            case expression: ScExpression if expression eq literal.reference => Some("")
            case _ => None
          }
        }
      }
      if (pieces.forall(_.nonEmpty)) Some(pieces.flatten.mkString) else None
    case literal: ScLiteral => literal.getValue match {
      case value: String => Some(value)
      case _ => None
    }
    case reference: ScReferenceExpression if reference.qualifier.isEmpty => values.get(reference.refName)
    case reference: ScReferenceExpression if reference.refName == "stripMargin" => reference.qualifier.flatMap(staticString(_, values)).map(_.stripMargin)
    case reference: ScReferenceExpression if reference.refName == "trim" => reference.qualifier.flatMap(staticString(_, values)).map(_.trim)
    case infix: ScInfixExpr if infix.operation.refName == "+" =>
      for (left <- staticString(infix.left, values); right <- staticString(infix.right, values)) yield left + right
    case call: ScMethodCall if call.argumentExpressions.isEmpty => staticString(call.getInvokedExpr, values)
    case block: ScBlockExpr if block.exprs.size == 1 => staticString(block.exprs.head, values)
    case _ => None
  }

  private def collectMethods(host: ScalaPsiHost, root: Path): Vector[TestCase] = {
    val dir = root.resolve("test/org/jetbrains/plugins/scala/lang/completion3")
    val sources = Using.resource(Files.walk(dir))(_.iterator().asScala.filter(_.toString.endsWith(".scala")).toVector.sorted)
    sources.flatMap { path =>
      val file = host.parse(Files.readString(path))
      PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeDefinition]).asScala.toVector.flatMap { cls =>
        val declaredVersions = cls.annotations.filter(_.typeElement.getText.endsWith("RunWithScalaVersions")).flatMap { annotation =>
          PsiTreeUtil.findChildrenOfType(annotation, classOf[ScReferenceExpression]).asScala.map(_.refName).filter(_.startsWith("Scala_"))
        }
        val scala3Only = declaredVersions.nonEmpty && declaredVersions.forall(_.startsWith("Scala_3"))
        val setupApis = Set("configureJavaFile", "addFileToProject", "configureFromFileText", "configureFromExistingVirtualFile")
        val fixtureHelpers = cls.functions.collect { case definition: ScFunctionDefinition if
          PsiTreeUtil.findChildrenOfType(definition, classOf[ScMethodCall]).asScala.exists { call =>
            call.getInvokedExpr match {
              case ref: ScReferenceExpression => setupApis(ref.refName)
              case _ => false
            }
          } => definition.name
        }.toSet
        cls.functions.collect { case method: ScFunctionDefinition if method.name.startsWith("test") =>
          val suite = "completion3/" + cls.name
          def skipped(reason: String) = TestCase(suite, method.name, path, Some(reason))
          val calls = PsiTreeUtil.findChildrenOfType(method, classOf[ScMethodCall]).asScala.filter { call =>
            call.getInvokedExpr match {
              case ref: ScReferenceExpression => Set("doCompletionTest", "doRawCompletionTest")(ref.refName)
              case _ => false
            }
          }.toVector
          if (scala3Only || cls.name.contains("Scala3") || cls.name.contains("Scala_3") || cls.name.contains("_3") || cls.name.contains("with_3")) skipped("Scala 3 fixture")
          else if (calls.size != 1) skipped("no single static insertion contract (membership/order/custom harness)")
          else {
            val call = calls.head
            val parameters = Vector("fileText", "resultText", "item", "char", "invocationCount", "completionType")
            val arguments = call.argumentExpressions.zipWithIndex.map {
              case (assignment: ScAssignment, _) => assignment.referenceName.getOrElse("") -> assignment.rightExpression
              case (expression, index) => parameters.lift(index).getOrElse("") -> Some(expression)
            }.collect { case (name, Some(expression)) => name -> expression }.toMap
            var values = Map("CARET" -> "<caret>")
            PsiTreeUtil.findChildrenOfType(method, classOf[ScPatternDefinition]).asScala.toVector.sortBy(_.getTextOffset).foreach { definition =>
              if (definition.bindings.size == 1) definition.expr.flatMap(staticString(_, values)).foreach { value =>
                values += definition.bindings.head.name -> value
              }
            }
            val extracted = Vector("fileText", "resultText", "item").map { name =>
              name -> arguments.get(name).flatMap(staticString(_, values))
            }.toMap
            val unsupportedSetup = method.body.exists { body =>
              val setupCalls = PsiTreeUtil.findChildrenOfType(body, classOf[ScMethodCall]).asScala
              setupCalls.exists { nested =>
                nested.getInvokedExpr match {
                  case ref: ScReferenceExpression => setupApis(ref.refName) || fixtureHelpers(ref.refName)
                  case _ => false
                }
              }
            }
            if (arguments.get("invocationCount").exists(_.getText != "1")) skipped("second/custom invocation")
            else if (arguments.get("completionType").exists(e => !Set("BASIC", "CompletionType.BASIC")(e.getText))) skipped("SMART/custom completion type")
            else if (unsupportedSetup || fixtureHelpers("setUp")) skipped("fixture-file setup is not replayed (direct call or class helper)")
            else if (arguments.get("char").exists(e => !Set("REPLACE_SELECT_CHAR", "Lookup.REPLACE_SELECT_CHAR", "NORMAL_SELECT_CHAR", "Lookup.NORMAL_SELECT_CHAR")(e.getText)))
              skipped("typed completion character requires IDE typed actions")
            else if (!arguments.contains("item")) skipped("predicate lookup selection has no static item")
            else if (extracted.values.exists(_.isEmpty))
              skipped("non-static expression or unsupported interpolation in " + extracted.toSeq.collect { case (name, None) => name }.sorted.mkString(", "))
            else (for (before <- extracted("fileText"); after <- extracted("resultText"); lookup <- extracted("item")) yield (before, after, lookup)) match {
              case Some((before, after, lookup)) if before.contains("<caret>") =>
                TestCase(suite, method.name, path, None, Some(InsertionCase(before, after, lookup,
                  if (arguments.get("char").exists(_.getText.contains("NORMAL"))) '\n' else '\t')))
              case _ => skipped("fileText has no input CARET marker")
            }
          }
        }.toVector
      }
    }
  }

  private def collectImports(root: Path): Vector[TestCase] = {
    val dir = root.resolve("testdata/autoImport")
    Using.resource(Files.walk(dir))(_.iterator().asScala.filter(p => p.toString.endsWith(".scala") && !p.getFileName.toString.startsWith("Source"))
      .toVector.sorted.map(p => TestCase("autoImport", dir.relativize(p).toString, p, None)))
  }

  private def replayInsertion(host: ScalaPsiHost, module: HostModule, c: TestCase, contract: InsertionCase): Result = {
    val before = contract.before.trim
    val after = contract.after.trim
    val offset = before.indexOf("<caret>")
    if (offset < 0 || before.indexOf("<caret>", offset + 1) >= 0) return Result("skip", "requires exactly one input caret")
    val file = host.openFile(Path.of("/completion-oracle/Method.scala"), before.replace("<caret>", ""), module) match {
      case f: ScalaFile => f
      case other => throw new IllegalStateException(s"not a Scala fixture: $other")
    }
    try {
      val chosen = Completion.choose(file, offset, contract.lookup, contract.char)
      val inserted = chosen.result.text.patch(chosen.caret, "<caret>", 0).trim
      val actual = if (after.contains("<caret>")) inserted else inserted.replace("<caret>", "")
      require(host.read(file.getText) == before.replace("<caret>", ""), "completion changed original")
      if (actual == after) Result("agree", "")
      else Result("disagree", s"resulting text/caret: expected ${after.replace("\n", " ↵ ")}; obtained ${actual.replace("\n", " ↵ ")}")
    } catch {
      case e: UnsupportedOperationException =>
        HostLog.warn(s"completion oracle ${c.suite}/${c.name} cannot replay insertion", e)
        Result("skip", "unsupported insertion: " + e.getMessage)
      case NonFatal(e) =>
        HostLog.warn(s"completion oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    } finally host.closeSource(file)
  }

  private def runImport(host: ScalaPsiHost, module: HostModule, c: TestCase): Result = {
    val text = Files.readString(c.path).trim.replace("\r\n", "\n")
    val offset = text.indexOf("/*ref*/")
    if (offset < 0) return Result("skip", "no auto-import reference marker")
    val companions = Using.resource(Files.walk(c.path.getParent))(_.iterator().asScala
      .filter(p => p.toString.endsWith(".scala") && p.getFileName.toString.startsWith("Source")).toVector.sorted)
      .map(p => host.openFile(Path.of("/auto-import-oracle").resolve(c.path.getParent.relativize(p)), Files.readString(p), module))
    val original = text.replace("/*ref*/", "")
    val file = host.openFile(Path.of("/auto-import-oracle/Case.scala"), original, module) match {
      case f: ScalaFile => f
      case other => throw new IllegalStateException(s"not a Scala fixture: $other")
    }
    try {
      val expectation = host.read(Expected.lastComment(file))
      if (expectation.isEmpty) return Result("skip", "no resulting-text comment")
      val expected = expectation.get
      val result = importChange(file, offset)
      require(TestHost.applyEdits(original, result.edits) == result.text, "edits do not reconstruct the import")
      require(host.read(file.getText) == original, "auto-import changed original")
      val parsed = host.parse(result.text.trim)
      val commentStart = host.read(parsed.findElementAt(parsed.getTextLength - 1).getTextRange.getStartOffset)
      val actual = result.text.substring(0, commentStart).trim
      if (actual == expected.replace("/*ref*/", "").trim) Result("agree", "")
      else Result("disagree", s"resulting import text: expected ${expected.replace("\n", " ↵ ")}; obtained ${actual.replace("\n", " ↵ ")}")
    } catch {
      case NonFatal(e) =>
        HostLog.warn(s"auto-import oracle ${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    } finally { host.closeSource(file); companions.foreach(host.closeSource) }
  }

  private def importChange(file: ScalaFile, offset: Int): ChangeEngine.Result = {
    val change = new FutureTask[ChangeEngine.Result](() => ChangeEngine.run(file) { copy =>
      val reference = PsiTreeUtil.getParentOfType(copy.findElementAt(offset), classOf[ScReference], false)
      require(reference != null, "no reference at marker")
      val fix = ScalaImportTypeFix(reference)
      val candidates = fix.elements
      require(candidates.nonEmpty, "no import candidate")
      val editor = IntentionPreviewUtils.getPreviewEditor
      editor.getCaretModel.moveToOffset(offset)
      fix.createAddImportAction(editor).addImportTestOnly(candidates.head)
    }._1)
    HostDispatchThread.invokeAndWait(change)
    change.get()
  }

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase): Result = {
    val marker = if (c.suite == "completion") "/*caret*/" else "<caret>"
    try {
      val text = Files.readString(c.path).trim.replace("\r\n", "\n")
      val offset = text.indexOf(marker)
      if (offset < 0 || text.indexOf(marker, offset + marker.length) >= 0) return Result("skip", "requires exactly one caret marker")
      // The fixture opens a physical file. Synthetic ScalaPsiElementFactory files have empty backing VFS text,
      // which changes findReferenceAt and therefore the plugin's operator/backtick dummy selection.
      val configured = if (c.suite == "completion") text else text.replace(marker, "")
      val file = host.openFile(Path.of("/completion-oracle").resolve(c.path.getFileName), configured, module) match {
        case f: ScalaFile => f
        case other => throw new IllegalStateException(s"not a Scala fixture: $other")
      }
      try {
        host.read(Expected.lastComment(file)) match {
          case None => Result("skip", "no last-comment expectation")
          case Some(comment) =>
            val expected = Expected.forVersion(comment, LatestScalaVersions.Scala_2_13).trim.linesIterator.filter(_.nonEmpty).toVector.sorted
            val completed = Completion.complete(file, offset, limit = Int.MaxValue)
            val items = completed.items.filter(i => c.suite != "keywordCompletion" || i.kind == Completion.Kind.Keyword)
            val actual = items.map { i =>
              i.lookup + (if (i.deprecated && c.suite == "completion") " (strikeout)" else "")
            }.toVector.sorted
            if (expected == actual) Result("agree", "")
            else Result("disagree", s"missing: ${expected.diff(actual).mkString(", ")}; extra: ${actual.diff(expected).mkString(", ")}")
        }
      } finally host.closeSource(file)
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"completion oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  }
}
