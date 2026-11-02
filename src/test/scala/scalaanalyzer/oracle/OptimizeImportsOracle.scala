package scalaanalyzer.oracle

import com.intellij.psi.PsiComment
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.{ModuleSpec, TestHost}
import scalaanalyzer.platform.{HostLog}

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Replays OptimizeImportsTestBase's file fixtures: source between the first and last comments, result in the last. */
object OptimizeImportsOracle {
  def main(args: Array[String]): Unit = {
    val Seq(repo, report) = args.toSeq
    val host = TestHost.host
    TestHost.libraryReport
    val module = host.addModule(ModuleSpec("optimize-oracle", "2.13.18", Seq.empty,
      Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
    val library212 = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary212"))
    host.addLibrary(library212)
    val module212 = host.addModule(ModuleSpec("optimize-oracle-212", "2.12.21", Seq.empty, Seq(library212)))
    val root = Path.of(repo).resolve("scala/scala-impl/testdata/optimize")
    val paths = Using.resource(Files.walk(root))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toVector.sorted)
    val results = paths.map { path =>
      val name = root.relativize(path).toString
      val caseModule = if (name.startsWith("simple/") || name.startsWith("wildcard/")) module else module212
      val text = Files.readString(path).replace("\r\n", "\n").trim
      val result = if (name.startsWith("scalafmt/")) "skip: requires scalafmt integration"
      else if (name.startsWith("scalastyle/")) "skip: custom scalastyle grouping settings"
      else if (!text.startsWith("// Notification message:")) "skip: supporting source, not a test contract"
      else {
        val parsed = host.openFile(path, text, caseModule)
        val extracted = try host.read {
          (parsed.findElementAt(0), parsed.getLastChild) match {
            case (first: PsiComment, last: PsiComment) if last.getText.startsWith("/*") =>
              Some(text.substring(first.getTextRange.getEndOffset, last.getTextRange.getStartOffset)
                .stripPrefix("\n").stripTrailing -> last.getText.stripPrefix("/*").stripSuffix("*/")
                .stripPrefix("\n").stripTrailing)
            case _ => None
          }
        } finally host.closeSource(parsed)
        extracted.fold("skip: no expected block comment") { (before, expected) =>
          val errors = HostLog.count(HostLog.Level.Error)
          // SimpleTest and WildcardTest set sourceRootPath to their fixture directory, making siblings visible.
          val siblings = if (name.startsWith("simple/") || name.startsWith("wildcard/"))
            paths.filter(p => p.getParent == path.getParent && p != path)
          else Vector.empty
          val opened = siblings.map(p => host.openFile(p, Files.readString(p), caseModule))
          val file = host.openFile(path, before, caseModule).asInstanceOf[ScalaFile]
          try {
            val settingsManager = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(host.project)
            val settings = host.read(settingsManager.getCurrentSettings.clone())
            // OptimizeImportsImplicitsTest.testSCL7158 enables the Unicode arrow setting for this fixture.
            if (name == "implicits/SCL7158.scala")
              settings.getCustomSettings(classOf[ScalaCodeStyleSettings]).REPLACE_CASE_ARROW_WITH_UNICODE_CHAR = true
            val action = settingsManager.computeWithLocalSettings(settings, _ => host.organizeImports(file))
            val actual = TestHost.applyEdits(before, action.edits)
            if (host.read(file.getText) != before) "disagree: source changed"
            else if (actual != expected) s"disagree: expected ${expected.replace("\n", "\\n")}; actual ${actual.replace("\n", "\\n")}"
            else if (HostLog.count(HostLog.Level.Error) != errors) "disagree: platform logged an error"
            else "agree"
          } catch {
            case e @ (NonFatal(_) | _: LinkageError) => s"disagree: ${e.getClass.getSimpleName}: ${e.getMessage}"
          } finally {
            host.closeSource(file)
            opened.foreach(host.closeSource)
          }
        }
      }
      println(s"$name: $result")
      name -> result
    }
    val summary = s"${results.count(_._2 == "agree")} agree, ${results.count(_._2.startsWith("disagree"))} disagree, " +
      s"${results.count(_._2.startsWith("skip"))} skip"
    Files.writeString(Path.of(report), "# Optimize imports oracle\n\nScala 2.13.18 (simple/wildcard), default 2.12.21 (other suites), host JDK. Source text only; " +
      "the IDE notification is not compared. Simple/wildcard cases see sibling fixture sources, as their test root does.\n\n" +
      summary + "\n\n" + results.map((name, result) => s"- $name: $result").mkString("\n") + "\n")
    println(summary)
    sys.exit(0)
  }
}
