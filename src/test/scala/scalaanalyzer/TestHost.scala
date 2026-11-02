package scalaanalyzer

import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScPatternDefinition}
import com.intellij.psi.util.PsiTreeUtil

import scalaanalyzer.language.{ChangeEngine}
import scalaanalyzer.platform.{CallLog}

import java.io.File
import java.nio.file.Path

import scala.jdk.CollectionConverters.*

/** The one host of the test JVM (see `Test / fork` in build.sbt), with the scala-library fixture indexed. */
object TestHost {
  private def property(name: String): String =
    sys.props.getOrElse(name, throw new IllegalStateException(s"system property $name is not set; run the tests through sbt"))

  lazy val host: ScalaPsiHost = {
    // The adapter surface test lists every call the host answers.
    CallLog.countsAnswered = true
    ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(property("scalaanalyzer.stateDir")),
      scalaPluginJars = property("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
    ))
  }

  /** scala-library 2.13 and the test JVM's JDK, the classpath of a plain Scala 2.13 project. */
  lazy val libraryReport: LibraryIndexReport = {
    host.addJdk(Path.of(System.getProperty("java.home")))
    host.addLibrary(Path.of(property("scalaanalyzer.fixture.scalaLibrary")))
  }

  val scenarioSource: String =
    """object Main {
      |  val a = 1
      |  val b = a
      |  def inc(x: Int): Int = x + 1
      |  val c = inc(b)
      |  class Box[A](val v: A) {
      |    def map[B](f: A => B): Box[B] = new Box(f(v))
      |  }
      |  val d = for (x <- new Box(c)) yield x
      |}
      |""".stripMargin

  /** The scenario as the project's one source file, opened after the libraries so every query sees the same symbols. */
  private lazy val scenario: ScalaFile = {
    libraryReport
    host.openSource(scenarioSource)
  }

  private lazy val completionModule = host.addModule(ModuleSpec("surface-completion", "2.13.18", Seq.empty,
    Seq(Path.of(property("scalaanalyzer.fixture.scalaLibrary")))))

  def scenarioFile(): ScalaFile = scenario

  /** Applies replacements at original-text offsets, checking that no two edits overlap. */
  def applyEdits(text: String, edits: Seq[ChangeEngine.Edit]): String = {
    val sorted = edits.sortBy(_.range.getStartOffset)
    sorted.sliding(2).foreach {
      case Seq(left, right) =>
        require(left.range.getStartOffset < right.range.getStartOffset &&
          left.range.getEndOffset <= right.range.getStartOffset, "edits overlap")
      case _ => ()
    }
    sorted.reverse.foldLeft(text) { (current, edit) =>
      current.substring(0, edit.range.getStartOffset) + edit.text + current.substring(edit.range.getEndOffset)
    }
  }

  def references(file: ScalaFile): Seq[ScReferenceExpression] =
    PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).asScala.toSeq

  def patternDefinitions(file: ScalaFile): Seq[ScPatternDefinition] =
    PsiTreeUtil.findChildrenOfType(file, classOf[ScPatternDefinition]).asScala.toSeq

  /**
   * Chooses the suggestion `lookup` at `offset` as an editor does: its lookup string replaces the prefix, then its
   * insert handler runs. The file's text after both, with `<caret>` where the handler left the caret.
   */
  def choose(file: ScalaFile, offset: Int, lookup: String): String = {
    val suggestions = host.completion(file, offset).items
    val chosen = suggestions.find(_.lookup == lookup)
      .getOrElse(throw new IllegalStateException(s"no suggestion $lookup in $suggestions"))
    host.edit(file, chosen.prefix.getStartOffset, chosen.prefix.getEndOffset, lookup)
    val inserted = host.insertCompletion(file, chosen.prefix.getStartOffset, chosen.prefixText, lookup, chosen.identity)
      .getOrElse(throw new IllegalStateException(s"the insert handler of $lookup did not run"))
    inserted.result.text.patch(inserted.caret, "<caret>", 0)
  }

  /** Runs every query the semantic tests make; the adapter surface test measures this. */
  def runScenario(): Unit = {
    val file = scenarioFile()
    host.read {
      references(file).foreach(_.resolve())
      patternDefinitions(file).flatMap(_.expr).foreach(_.`type`())
      val shortNames = com.intellij.psi.search.PsiShortNamesCache.getInstance(host.project)
      shortNames.getAllClassNames
      shortNames.getClassesByName("Box", com.intellij.psi.search.GlobalSearchScope.allScope(host.project))
      val inc = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunction]).asScala.find(_.name == "inc").get
      com.intellij.psi.search.searches.ReferencesSearch.search(inc).findAll()
    }
    host.completion(file, scenarioSource.indexOf("inc(b)") + "inc".length)
    host.completion(file, scenarioSource.indexOf("x + 1") + "x +".length)
    val completionSource = "object InsertScenario { val x: ArrayBuff = ???; def invoke(n: Int): Unit = (); inv; def values = Option(1).toList }"
    val completionFile = host.openFile(Path.of("/surface/Insert.scala"), completionSource, completionModule) match {
      case f: ScalaFile => f
      case other => throw new IllegalStateException(s"not a Scala scenario: $other")
    }
    try {
      choose(completionFile, completionSource.indexOf("ArrayBuff") + "ArrayBuff".length, "ArrayBuffer")
      host.edit(completionFile, 0, host.read(completionFile.getTextLength), completionSource)
      choose(completionFile, completionSource.indexOf("; inv") + "; inv".length, "invoke")
      host.edit(completionFile, 0, host.read(completionFile.getTextLength), completionSource)
      host.diagnostics(completionFile)
      host.inlayHints(completionFile)
      host.selectionRanges(completionFile, completionSource.indexOf("values"))
      val selectionText = "object SelectionScenario {\n  val path = s\"/one/$value\\n/two\"\n  \n}\n"
      host.edit(completionFile, 0, host.read(completionFile.getTextLength), selectionText)
      for (offset <- Seq(selectionText.indexOf("one"), selectionText.indexOf("\n  \n") + 2, selectionText.length))
        host.selectionRanges(completionFile, offset)
    } finally host.closeSource(completionFile)
    for ((text, prefix, lookup) <- Seq(
      ("object StringScenario { val member = 1; val s = \"$mem\" }", "$mem", "member"),
      ("object KeywordScenario {\n  val n = 1\n  n mat\n}", "mat", "match"),
      ("trait ImplementScenario { def run(): Unit }; object Use { new ImplementScen }", "ImplementScen", "ImplementScenario"),
      ("object ConstructorScenario { val value = new ArrayBuff }", "ArrayBuff", "ArrayBuffer"))) {
      // A file with a document: the editor puts the lookup string in it before its handler runs.
      val source = host.openFile(Path.of(s"/surface/$lookup.scala"), text, completionModule) match {
        case f: ScalaFile => f
        case other => throw new IllegalStateException(s"not a Scala scenario: $other")
      }
      try choose(source, text.lastIndexOf(prefix) + prefix.length, lookup)
      finally host.closeSource(source)
    }
    val settingsManager = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(host.project)
    val settings = host.read(settingsManager.getCurrentSettings.clone())
    settings.getCustomSettings(classOf[org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings])
      .setImportsWithPrefix(Array("scala.collection.mutable._"))
    val prefixedText = "object PrefixScenario { val value = new ArrayBuff }"
    val prefixed = host.openFile(Path.of("/surface/Prefix.scala"), prefixedText, completionModule) match {
      case f: ScalaFile => f
      case other => throw new IllegalStateException(s"not a Scala scenario: $other")
    }
    try settingsManager.computeWithLocalSettings(settings, _ =>
      choose(prefixed, prefixedText.indexOf("ArrayBuff") + "ArrayBuff".length, "ArrayBuffer"))
    finally host.closeSource(prefixed)
    host.formatting(file, None, 2, useTabs = false)
    val importFile = host.parse("object ImportUser { val x: scala.collection.mutable.HashSet[Int] = ??? }")
    val target = host.read {
      com.intellij.psi.JavaPsiFacade.getInstance(host.project).findClass("scala.collection.mutable.HashSet",
        com.intellij.psi.search.GlobalSearchScope.allScope(host.project))
    }
    ChangeEngine.run(importFile)(_.addImportForClass(target, null))

  }
}
