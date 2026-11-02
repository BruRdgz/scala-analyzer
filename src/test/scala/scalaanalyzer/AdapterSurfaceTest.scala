package scalaanalyzer

import scalaanalyzer.platform.{CallLog}

import java.nio.file.{Files, Path}

import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Bootstrap guard: the set of adapter entries the scenario reaches must equal the reviewed surface in
 * `adapter-surface.txt`. A new platform or plugin version that asks the host for something else fails here,
 * with the difference, instead of changing behaviour unnoticed. Insertion and formatting belong to this scenario too,
 * so editing-only adapter growth receives the same review as semantic reads.
 */
class AdapterSurfaceTest extends munit.FunSuite {

  // Index and classpath lookups vary with the symbols indexed (phase F1), not with the adapter surface.
  private def isSymbolLookup(entry: String) = entry.startsWith("GAP ") || entry.startsWith("HIT ")

  test("the scenario stays within the host's tables") {
    TestHost.runScenario()
    val unsupported = CallLog.snapshot.map(_._1).filter(_.startsWith("UNSUPPORTED "))
    assertEquals(unsupported, Seq.empty)
  }

  test("completion beans retain their descriptor implementations and weigher IDs") {
    TestHost.host.read {
      val contributors = com.intellij.codeInsight.completion.CompletionContributor.forLanguage(
        org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE, null).asScala.map(_.getClass.getName).toSet
      val declared = ScalaPsiHost.scalaCompletionContributors(
        sys.props("scalaanalyzer.scalaPluginJars").split(java.io.File.pathSeparator).toSeq.map(java.nio.file.Path.of(_))).map(_.implementation).toSet
      assertEquals(contributors, declared)
      assert(declared.exists(_.endsWith("ScalaClassNameCompletionContributor")), declared)
      val weighers = com.intellij.psi.WeighingService.getWeighers(com.intellij.codeInsight.completion.CompletionService.RELEVANCE_KEY)
        .asScala.map(_.toString).toSeq
      assertEquals(weighers.toSet, Set("scalaContainingClassWeigher", "scalaMethodCompletionWeigher", "scalaByNameWeigher",
        "scalaKindWeigher", "ScopeWeigher", "stats", "priority", "prefix", "grouping", "explicitProximity", "proximity"))
      assert(weighers.indexOf("scalaContainingClassWeigher") < weighers.indexOf("scalaKindWeigher"), weighers)
      val proximity = com.intellij.psi.WeighingService.getWeighers(com.intellij.psi.util.proximity.PsiProximityComparator.WEIGHER_KEY)
        .asScala.map(_.toString).toSet
      assertEquals(proximity, Set("scalaClassObjectWeigher", "scalaExplicitlyImportedWeigher"))
    }
  }

  test("formatting and imports use the reviewed Scala implementations and defaults") {
    TestHost.host.read {
      val language = org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE
      assert(com.intellij.lang.LanguageFormatting.INSTANCE.forLanguage(language)
        .isInstanceOf[org.jetbrains.plugins.scala.lang.formatting.ScalaFormattingModelBuilder])
      val optimizers = com.intellij.lang.LanguageImportStatements.INSTANCE.allForLanguage(language).asScala
      assertEquals(optimizers.map(_.getClass.getName).toSeq, Seq("org.jetbrains.plugins.scala.editor.importOptimizer.ScalaImportOptimizer"))
      val settings = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(TestHost.host.project).getCurrentSettings
      val indent = settings.getCommonSettings(language).getIndentOptions
      assertEquals((indent.INDENT_SIZE, indent.TAB_SIZE, indent.CONTINUATION_INDENT_SIZE), (2, 2, 2))
      val scala = settings.getCustomSettings(classOf[org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings])
      assertEquals(scala.FORMATTER, org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings.INTELLIJ_FORMATTER)
      assertEquals(scala.getClassCountToUseImportOnDemand, 5)
      assert(scala.isAddFullQualifiedImports && scala.isSortImports && !scala.isSortAsScalastyle)
    }
  }

  test("the adapter surface matches the reviewed list") {
    TestHost.runScenario()
    val actual = CallLog.snapshot.map(_._1).filterNot(isSymbolLookup)
    val expected = Using.resource(Source.fromResource("adapter-surface.txt"))(_.getLines().filter(_.nonEmpty).toSeq)
    if (actual != expected) {
      val out = Path.of(sys.props("scalaanalyzer.stateDir")).resolve("adapter-surface.actual.txt")
      Files.write(out, actual.asJava)
      fail(
        s"""adapter surface changed (actual list written to $out)
           |added:   ${actual.diff(expected).mkString("\n         ")}
           |removed: ${expected.diff(actual).mkString("\n         ")}""".stripMargin)
    }
  }
}
