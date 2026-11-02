package scalaanalyzer.oracle

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition

import scalaanalyzer.{TestHost}
import scalaanalyzer.language.{ChangeEngine}
import scalaanalyzer.platform.{HostLog}
import scalaanalyzer.build.{ScalafmtFormatting}

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Selected literal whole-file contracts from the plugin's custom-config and legacy scalafmt suites. */
object ScalafmtOracle {
  private final case class Contract(name: String, config: Path, before: String, expected: String)

  def main(args: Array[String]): Unit = {
    val Array(repo, report) = args
    val host = TestHost.host
    val root = Path.of(repo, "scala/scala-impl")
    val sources = root.resolve("test/org/jetbrains/plugins/scala/lang/formatter/scalafmt")
    val configs = root.resolve("testdata/formatter/scalafmt")
    val custom = host.parse(Files.readString(sources.resolve("ScalaFmtCustomConfigTest.scala")))
    val common = host.parse(Files.readString(sources.resolve("ScalaFmtCommonTest.scala")))
    val contracts = try host.read {
      val classes = Seq(custom, common).flatMap(file =>
        PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeDefinition]).asScala).map(cls => cls.name -> cls).toMap
      val base = classes("ScalaFmtCustomConfigTestBase")
      val derived = classes("ScalaFmtCustomConfigTest_3_11")
      val inherited = base.functions.filterNot(method => derived.functions.exists(_.name == method.name))
      val methods = (inherited ++ derived.functions).collect {
        case method: ScFunctionDefinition if method.name.startsWith("test") => method
      }.map(method => (method, derived, configs.resolve("3_11_5"), Option.empty[String])) ++
        classes("ScalaFmtCommonTest_2_7").functions.collect {
          case method: ScFunctionDefinition if method.name == "testCompleteFile" =>
            (method, classes("ScalaFmtCommonTest_2_7"), configs, Some("empty_config_2_7_5.conf"))
        }
      methods.map { (method, owner, directory, defaultConfig) =>
        val name = s"${owner.name}/${method.name}"
        var values = Map.empty[String, String]
        // Some inherited tests use expected text declared on the derived suite.
        val fields = owner.members.collect { case definition: ScPatternDefinition => definition }
        val locals = PsiTreeUtil.findChildrenOfType(method, classOf[ScPatternDefinition]).asScala.toSeq
        (fields ++ locals).foreach { definition =>
          if (definition.bindings.size == 1)
            definition.expr.flatMap(CompletionOracle.staticString(_, values)).foreach(value =>
              values += definition.bindings.head.name -> value)
        }
        val calls = PsiTreeUtil.findChildrenOfType(method, classOf[ScMethodCall]).asScala.toSeq
        def called(call: ScMethodCall): String = call.getInvokedExpr match {
          case ref: ScReferenceExpression => ref.refName
          case _ => ""
        }
        val tests = calls.filter(called(_) == "doTextTest")
        val configured = calls.filter(called(_) == "setScalafmtConfig")
        val config = configured.headOption.flatMap(_.argumentExpressions.headOption)
          .flatMap(CompletionOracle.staticString(_, values)).orElse(defaultConfig)
        require(tests.size == 1 && configured.size <= 1, s"$name: unsupported test setup")
        require(calls.forall(call => Set("doTextTest", "setScalafmtConfig", "stripMargin")(called(call))),
          s"$name: custom test behavior")
        val texts = tests.head.argumentExpressions.map(CompletionOracle.staticString(_, values))
        require(texts.size == 2 && texts.forall(_.nonEmpty), s"$name: non-literal test text")
        Contract(name, directory.resolve(config.getOrElse(sys.error(s"$name: missing config"))),
          texts.head.get, texts.last.get)
      }
    } finally {
      host.closeSource(custom)
      host.closeSource(common)
    }
    val formatter = new ScalafmtFormatting(host.stateDir.resolve("scalafmt"))
    val results = try contracts.map { contract =>
      val errors = HostLog.count(HostLog.Level.Error)
      val outcome = try {
        val actual = formatter.format(contract.config.toAbsolutePath, configs.resolve("Oracle.scala").toAbsolutePath,
          contract.before)
        val edits = ChangeEngine.diff(contract.before, actual)
        require(TestHost.applyEdits(contract.before, edits) == actual, "edits do not reconstruct formatted text")
        if (HostLog.count(HostLog.Level.Error) != errors) "disagree: platform logged an error"
        else if (actual == contract.expected) "agree"
        else s"disagree: expected ${contract.expected.replace("\n", "\\n")}; actual ${actual.replace("\n", "\\n")}"
      } catch {
        case e @ (NonFatal(_) | _: LinkageError) => s"disagree: ${e.getClass.getSimpleName}: ${e.getMessage}"
      }
      println(s"${contract.name}: $outcome")
      contract.name -> outcome
    } finally formatter.close()
    val summary = s"${results.count(_._2 == "agree")} agree, ${results.count(_._2.startsWith("disagree"))} disagree"
    Files.writeString(Path.of(report), "# Scalafmt oracle\n\n" +
      "Selected whole-file contracts: ScalaFmtCustomConfigTest_3_11 (including inherited tests), " +
      "and ScalaFmtCommonTest_2_7.testCompleteFile. Configurations and expected text come from the plugin. " +
      "Range, incomplete-source wrapping and save-task harnesses are outside this replay.\n\n" + summary + "\n\n" +
      results.map((name, outcome) => s"- $name: $outcome").mkString("\n") + "\n")
    println(summary)
    sys.exit(0)
  }
}
