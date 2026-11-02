package scalaanalyzer.oracle

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.surroundWith.descriptors.ScalaExpressionSurroundDescriptor

import scalaanalyzer.{HostModule, ModuleSpec, ScalaPsiHost, TestHost}
import scalaanalyzer.language.{ChangeEngine, CodeActions}
import scalaanalyzer.platform.{Descriptors, HostLog}

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Replays Scala 2/3 surround fixtures and selected Scala 2 intention contracts through code-action previews. */
object CodeActionsOracle {
  private val EndMarkers = Seq("<if>", "<else>", "<while>", "<do>", "<for>", "<yield>", "<catch>",
    "<finally>", "<try>", "<braces>", "<match>", "<parenthesis>", "<if_cond>", "<else_cond>", "<unary>")

  def main(args: Array[String]): Unit = {
    val Seq(repo, report) = args.toSeq
    val host = TestHost.host
    TestHost.libraryReport
    val library = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary"))
    val modules = Seq("2.13.18", "3.9.0").map(version =>
      host.addModule(ModuleSpec(s"actions-$version", version, Seq.empty, Seq(library))))
    val root = Path.of(repo).resolve("scala/scala-impl/testdata/surroundWith/data")
    val paths = Using.resource(Files.walk(root))(_.iterator.asScala.filter(_.toString.endsWith(".test")).toVector.sorted)
    val surroundResults = paths.map { path =>
      val name = root.relativize(path).toString
      val sections = Files.readString(path).replace("\r\n", "\n").split("\\n?(?m)^-{4,}\\n?", -1)
      val before = sections.head.trim
      val index = EndMarkers.indexWhere(before.contains)
      val start = before.indexOf("<start>")
      val errors = HostLog.count(HostLog.Level.Error)
      val result = if (sections.length != 2 || index < 0 || start < 0) "skip: unsupported fixture layout"
      else {
        val marker = EndMarkers(index)
        val end = before.indexOf(marker) - "<start>".length
        val original = before.replace("<start>", "").replace(marker, "")
        val expected = sections(1).replace("<preserve-trailing-spaces>", "").trim
        val scala3 = name.startsWith("3/")
        val file = host.openFile(Path.of(s"/actions-oracle/$name.scala"), original, modules(if (scala3) 1 else 0))
          .asInstanceOf[ScalaFile]
        try {
          val manager = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(host.project)
          val settings = host.read(manager.getCurrentSettings.clone())
          settings.getCustomSettings(classOf[ScalaCodeStyleSettings]).USE_SCALA3_INDENTATION_BASED_SYNTAX = scala3
          manager.computeWithLocalSettings(settings, _ => {
            val title = new ScalaExpressionSurroundDescriptor().getSurrounders.apply(index).getTemplateDescription
            val actions = host.codeActions(file, new TextRange(start, end))
            require(host.read(file.getText) == original, "code actions changed the source")
            actions.find(_.title == title) match {
              case None => "disagree: surrounder was not offered"
              case Some(action) if action.disabled.nonEmpty => s"disagree: ${action.disabled.get}"
              case Some(action) =>
                val actual = TestHost.applyEdits(original, action.edits).trim
                if (actual != expected) s"disagree: expected ${expected.replace("\n", "\\n")}; actual ${actual.replace("\n", "\\n")}"
                else if (HostLog.count(HostLog.Level.Error) != errors) "disagree: platform logged an error"
                else "agree"
            }
          })
        } catch {
          case e @ (NonFatal(_) | _: LinkageError) =>
            s"disagree: ${e.getClass.getSimpleName}: ${e.getMessage}"
        } finally host.closeSource(file)
      }
      println(s"$name: $result")
      name -> result
    }
    val intentionResults = intentions(host, Path.of(repo), modules.head)
    val results = surroundResults ++ intentionResults
    val summary = s"${results.count(_._2 == "agree")} agree, ${results.count(_._2.startsWith("disagree"))} disagree, " +
      s"${results.count(_._2.startsWith("skip"))} skip"
    Files.writeString(Path.of(report), "# Code actions oracle\n\nSurroundWithTest Scala 2/3 fixtures and selected intention suites (Scala 2).\n\n" + summary +
      "\n\n" + results.map((name, result) => s"- $name: $result").mkString("\n") + "\n")
    println(summary)
    sys.exit(0)
  }

  /** Replay literal doTest contracts; PSI identifies calls and bindings, and test setup is never executed. */
  private def intentions(host: ScalaPsiHost, repo: Path, module: HostModule): Vector[(String, String)] = {
    val directory = repo.resolve("scala/codeInsight/test/org/jetbrains/plugins/scala/codeInsight/intention")
    val suites = Vector("booleans/NegateComparisonIntention", "booleans/ExpandBooleanIntention",
      "argument/ArgumentToBlockExpressionIntention")
    val jars = sys.props("scalaanalyzer.scalaPluginJars").split(java.io.File.pathSeparator).toSeq.map(Path.of(_))
    val registered = Descriptors.scalaExtensions(jars, "com.intellij.intentionAction").toSet
    suites.flatMap { suite =>
      val className = "org.jetbrains.plugins.scala.codeInsight.intention." + suite.replace('/', '.')
      require(registered(className), s"intention missing from plugin descriptors: $className")
      val source = host.parse(Files.readString(directory.resolve(suite + "Test.scala")))
      val contracts = try host.read {
        PsiTreeUtil.findChildrenOfType(source, classOf[ScTypeDefinition]).asScala.toVector.flatMap { cls =>
          cls.functions.collect { case method: ScFunctionDefinition if method.name.startsWith("test") =>
            val name = s"intentions/$suite/${cls.name}/${method.name}"
            val calls = PsiTreeUtil.findChildrenOfType(method, classOf[ScMethodCall]).asScala.toVector
            def called(call: ScMethodCall): String = call.getInvokedExpr match {
              case ref: ScReferenceExpression => ref.refName
              case _ => ""
            }
            val tests = calls.filter(called(_) == "doTest")
            val texts = if (cls.name.contains("Scala3")) Left("requires a Scala 3 test harness")
            else if (tests.size != 1 || calls.exists(c => !Set("doTest", "stripMargin", "trim")(called(c))))
              Left("custom setup or no single doTest contract")
            else {
              var values = Map("CARET" -> "<caret>")
              PsiTreeUtil.findChildrenOfType(method, classOf[ScPatternDefinition]).asScala.toVector
                .sortBy(_.getTextOffset).foreach { definition =>
                  if (definition.bindings.size == 1)
                    definition.expr.flatMap(CompletionOracle.staticString(_, values)).foreach { value =>
                      values += definition.bindings.head.name -> value
                    }
                }
              tests.head.argumentExpressions.take(2).map(CompletionOracle.staticString(_, values)) match {
                case Seq(Some(before), Some(expected)) => Right(before -> expected)
                case _ => Left("non-literal test text")
              }
            }
            name -> texts
          }
        }
      } finally host.closeSource(source)
      contracts.map { (name, texts) =>
        val result = texts.fold(reason => s"skip: $reason", (before, expected) =>
          replayIntention(host, module, name, className, before, expected))
        println(s"$name: $result")
        name -> result
      }
    }
  }

  private def replayIntention(host: ScalaPsiHost, module: HostModule, name: String, className: String,
                              before: String, expected: String): String = {
    val at = before.indexOf("<caret>")
    if (at < 0) return "skip: no caret"
    val original = before.replace("<caret>", "")
    val file = host.openFile(Path.of(s"/actions-oracle/$name.scala"), original, module).asInstanceOf[ScalaFile]
    try {
      val errors = HostLog.count(HostLog.Level.Error)
      val actions = host.read(new CodeActions(Seq(className), Seq.empty).collect(file, new TextRange(at, at), Seq.empty))
      require(host.read(file.getText) == original, "intention changed the source")
      actions.filter(_.disabled.isEmpty) match {
        case Seq(action) =>
          val changed = TestHost.applyEdits(original, action.edits)
          // ScalaIntentionTestBase reformats the resulting file before comparing it with the fixture.
          val (formatted, _) = ChangeEngine.run(file) { copy =>
            val document = copy.getViewProvider.getDocument
            document.setText(changed)
            PsiDocumentManager.getInstance(host.project).commitDocument(document)
            CodeStyleManager.getInstance(host.project).reformat(copy)
          }
          if (formatted.text.trim != expected.replace("<caret>", "").trim) "disagree: different resulting text"
          else if (HostLog.count(HostLog.Level.Error) != errors) "disagree: platform logged an error"
          else "agree"
        case _ => "disagree: intention unavailable or preview disabled"
      }
    } catch {
      case e @ (NonFatal(_) | _: LinkageError) => s"disagree: ${e.getClass.getSimpleName}: ${e.getMessage}"
    } finally host.closeSource(file)
  }
}
