package scalaanalyzer.oracle

import com.intellij.psi.{PsiClass, PsiElement, PsiNamedElement}
import org.jetbrains.plugins.scala.extensions.{PsiClassExt, PsiMemberExt, PsiNamedElementExt}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScMember, ScObject, ScTrait, ScTypeDefinition}

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost}
import scalaanalyzer.platform.{CallLog, HostLog}
import scalaanalyzer.language.{ProjectSearch}

import java.io.File
import java.nio.file.{Files, Path}

import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
 * Replays the plugin's search-by-name tests (`ChooseClassOrSymbolByNameTest`, for Scala 2.13 and Scala 3) without the
 * IDE test harness: the files each test adds to the project, the symbols the host finds for its query, and the test's
 * expectations of them (which elements are among them, how many, or exactly which names).
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.WorkspaceSymbolsOracle <intellij-scala checkout> <report.md> [--only=<name>,...]`.
 */
object WorkspaceSymbolsOracle {
  private final case class TestCase(suite: String, name: String, body: String, scala3: Boolean)
  private final case class Result(outcome: String, reason: String)
  /** What a test asks the popup for: classes or symbols matching `pattern`. */
  private final case class Query(classesOnly: Boolean, pattern: String)

  private val Suites = Seq("ChooseClassOrSymbolByNameTest_Scala213", "ChooseClassOrSymbolByNameTest_Scala3")
  private val TestMethod = "(?m)^  def test(\\S+)\\(\\): Unit =".r
  private val AddedFile = "(?s)addFileToProject\\(\\s*\"([^\"]+)\",\\s*(?:\"\"\"(.*?)\"\"\"(\\.stripMargin)?|\"([^\"]*)\")".r
  private val QueryCall = "goto(Class|Symbol)Elements\\(\"([^\"]*)\"\\)".r
  private val Contains = "\\((is\\[(\\w+)\\]|isPackageObject), \"([^\"]+)\"\\)".r
  private val Size = "checkSize\\(elements, (\\d+)\\)".r
  private val ExpectedNames = "(?s)val expectedNames = Seq\\((.*?)\\)".r
  private val Quoted = "\"([^\"]+)\"".r
  // The element classes the tests name in `is[...]`.
  private val Classes: Map[String, Class[?]] =
    Map("ScTrait" -> classOf[ScTrait], "ScObject" -> classOf[ScObject], "ScClass" -> classOf[ScClass], "ScFunction" -> classOf[ScFunction])

  def main(args: Array[String]): Unit = {
    val (Seq(repo, reportPath), flags) = args.toSeq.partition(!_.startsWith("--"))
    val only = flags.collectFirst { case f if f.startsWith("--only=") => f.stripPrefix("--only=").split(',').toSet }
    val cases = collect(Path.of(repo)).filter(c => only.forall(names => names(c.name) || names(c.suite + "/" + c.name)))
    val host = ScalaPsiHost.start(HostConfig(
      stateDir = Path.of(sys.props("scalaanalyzer.stateDir")),
      scalaPluginJars = sys.props("scalaanalyzer.scalaPluginJars").split(File.pathSeparator).toSeq.map(Path.of(_)),
      unitTestMode = true,
      compilerErrorsScala3 = false,
    ))
    host.addJdk(Path.of(System.getProperty("java.home")))
    val library = Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary"))
    val library3 = Path.of(classOf[Mirror].getProtectionDomain.getCodeSource.getLocation.toURI)
    host.addLibrary(library)
    host.addLibrary(library3)
    val module2 = host.addModule(ModuleSpec("workspace-symbols-oracle-2", "2.13.18", Seq.empty, Seq(library)))
    val module3 = host.addModule(ModuleSpec("workspace-symbols-oracle-3", "3.9.0", Seq.empty, Seq(library, library3)))
    val started = System.nanoTime()
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = runCase(host, if (c.scala3) module3 else module2, c)
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    writeReport(Path.of(reportPath), results, (System.nanoTime() - started) / 1_000_000)
    for (suite <- Suites) println(summary(suite, results))
    println(s"workspace symbols oracle: report at $reportPath")
    sys.exit(0)
  }

  /** The tests of the shared base class, once for each of its two subclasses (Scala 2.13 and Scala 3). */
  private def collect(repo: Path): Vector[TestCase] = {
    val source = Files.readString(repo.resolve("scala/scala-impl/test/org/jetbrains/plugins/scala/lang/navigation/ChooseClassOrSymbolByNameTest.scala"))
    val common = source.substring(source.indexOf("abstract class ChooseClassOrSymbolByNameCommonTests"), source.indexOf("class ChooseClassOrSymbolByNameTest_Scala213"))
    val starts = TestMethod.findAllMatchIn(common).toVector
    val methods = starts.zip(starts.map(_.start).drop(1) :+ common.length).map((method, end) => method.group(1) -> common.substring(method.end, end))
    (for ((suite, scala3) <- Suites.zip(Seq(false, true)); (name, body) <- methods) yield TestCase(suite, name, body, scala3)).toVector
  }

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase): Result =
    try {
      val added = AddedFile.findAllMatchIn(c.body).toSeq
      val queries = QueryCall.findAllMatchIn(c.body).map(m => Query(m.group(1) == "Class", m.group(2))).toSeq
      if (added.isEmpty || queries.size != 1)
        Result("skip", "searches the JDK's classes: the host lists the project's symbols only")
      else {
        val files = added.map { m =>
          val text = Option(m.group(2)).map(t => if (m.group(3) == null) t else t.stripMargin).getOrElse(m.group(4))
          host.openFile(Path.of(s"/workspace-symbols-oracle/${c.suite}/${c.name}/${m.group(1)}"), text, module)
        }
        try host.read(compare(c.body, found(host, queries.head)))
        finally files.foreach(host.closeSource)
      }
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"workspace symbols oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  /** The elements the popup lists: for symbols all the host finds, for classes the type definitions among them. */
  private def found(host: ScalaPsiHost, query: Query): Seq[PsiNamedElement] = {
    val symbols = ProjectSearch.symbols(host.project, query.pattern).map(_.element)
    if (query.classesOnly) symbols.filter(_.isInstanceOf[ScTypeDefinition]) else symbols
  }

  /** The name the tests compare: qualified for top-level members and classes (`GoToTestUtilities.actualName`). */
  private def actualName(element: PsiElement): String = element match {
    case member: ScMember if member.isTopLevel && member.qualifiedNameOpt.isDefined => member.qualifiedNameOpt.get
    case clazz: PsiClass => clazz.qualifiedName
    case named: PsiNamedElement => named.name
    case other => other.toString
  }

  private def isPackageObject(element: PsiElement): Boolean = element match {
    case scObject: ScObject => scObject.isPackageObject
    case _ => false
  }

  /** The test's expectations: each named element is listed, the number of elements, or the exact names. */
  private def compare(body: String, elements: Seq[PsiNamedElement]): Result = {
    val names = elements.map(actualName)
    val missing = Contains.findAllMatchIn(body).filterNot { m =>
      val kind = Option(m.group(2)).map(Classes)
      elements.exists(e => actualName(e) == m.group(3) && kind.fold(isPackageObject(e))(_.isInstance(e)))
    }.map(_.group(3)).toSeq
    val size = Size.findFirstMatchIn(body).map(_.group(1).toInt)
    val exact = ExpectedNames.findFirstMatchIn(body).map(m => Quoted.findAllMatchIn(m.group(1)).map(_.group(1)).toSeq.sorted)
    if (missing.nonEmpty) Result("disagree", s"missing: ${missing.mkString(", ")}; found: ${names.mkString(", ")}")
    else if (size.exists(_ != elements.size)) Result("disagree", s"expected ${size.get} elements; found: ${names.mkString(", ")}")
    else if (exact.exists(_ != names.sorted)) Result("disagree", s"expected ${exact.get.mkString(", ")}; found: ${names.sorted.mkString(", ")}")
    else Result("agree", "")
  }

  private def summary(suite: String, results: Vector[(TestCase, Result)]): String = {
    val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
    s"$suite: ${outcomes.count(_ == "agree")} agree, ${outcomes.count(_ == "disagree")} disagree, ${outcomes.count(_ == "skip")} skip"
  }

  private def writeReport(path: Path, results: Vector[(TestCase, Result)], millis: Long): Unit = {
    val lines = Vector.newBuilder[String]
    lines += "# Workspace symbols oracle"
    lines += ""
    lines += s"Scala 2.13.18 and Scala 3.9.0, unit-test mode, JDK ${System.getProperty("java.version")}; $millis ms."
    lines += ""
    lines += "Each case is a test method of `ChooseClassOrSymbolByNameCommonTests`, run for its Scala 2.13 and Scala 3 subclasses. The host opens the " +
      "files the test adds, and lists the symbols for its query as the language server does (`ProjectSearch.symbols`: the plugin's go-to " +
      "contributors, names matched as the IDE does). The class model of the popup lists the type definitions among them. Each test's " +
      "expectations are checked: the elements it names are listed, the number of elements, or the exact names."
    lines += ""
    lines += "| Suite | Agree | Disagree | Skip |"
    lines += "|---|---:|---:|---:|"
    for (suite <- Suites) {
      val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
      lines += s"| $suite | ${outcomes.count(_ == "agree")} | ${outcomes.count(_ == "disagree")} | ${outcomes.count(_ == "skip")} |"
    }
    lines += ""
    lines += "## Disagreements and skips by cause"
    lines += ""
    for ((reason, count) <- results.filter(_._2.outcome != "agree").groupMapReduce { (_, r) =>
      r.outcome + ": " + r.reason.takeWhile(_ != ':')
    }(_ => 1)(_ + _).toSeq.sortBy((reason, count) => (-count, reason))) lines += s"- $reason: $count"
    lines += ""
    lines += "## Cases"
    lines += ""
    lines += "| Suite / case | Outcome | Reason |"
    lines += "|---|---|---|"
    for ((c, r) <- results) lines += s"| ${c.suite}/${c.name} | ${r.outcome} | ${r.reason.replace("|", "\\|").replace("\n", " ")} |"
    lines += ""
    lines += "## Adapter observations"
    lines += ""
    for ((entry, count) <- CallLog.snapshot if entry.startsWith("UNSUPPORTED ") || entry.startsWith("LOGGED ERROR "))
      lines += s"- $entry: $count"
    Files.createDirectories(path.toAbsolutePath.getParent)
    Files.write(path, lines.result().asJava)
  }
}
