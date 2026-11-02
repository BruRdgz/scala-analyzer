package scalaanalyzer.oracle

import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.{HostConfig, HostModule, ModuleSpec, ScalaPsiHost}
import scalaanalyzer.platform.{CallLog, HostLog}
import scalaanalyzer.language.{ParameterInfo}

import java.io.File
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Replays the plugin's parameter-info fixtures (function, pattern and type parameter info) without the IDE test harness.
 * The cases are the `doTest()` methods of the plugin's test classes, since a data file only counts when a method runs it.
 *
 * Usage: `Test/runMain scalaanalyzer.oracle.ParameterInfoOracle <intellij-scala checkout> <report.md> [--only=<name>,...]`.
 */
object ParameterInfoOracle {
  private final case class TestCase(suite: String, name: String, path: Path, skip: Option[String])
  private final case class Result(outcome: String, reason: String)

  /** A test class as written in its source: its own members, and the class whose tests it inherits. */
  private final case class TestClass(name: String, parent: String, isAbstract: Boolean, text: String) {
    def methods: Vector[String] = TestMethod.findAllMatchIn(text).map(_.group(1)).toVector
    def dataDir: Option[String] = DataDir.findFirstMatchIn(text).map(_.group(1))
    def isScala3: Boolean = name.contains("Scala3") || Scala3Only.findFirstIn(text).isDefined
  }

  private val ClassHeader = """(?m)^(abstract )?(?:final )?class (\w+) extends (\w+)""".r
  private val TestMethod = """def test(\w+)\(\): Unit = doTest\(\)""".r
  private val DataDir = """getTestDataPath\}(\w+)/""".r
  // Every Scala 3 restriction in these classes is a supportedIn override naming Scala 3.
  private val Scala3Only = """supportedIn[^\n]*\n[^\n]*(?:isScala3|Scala_3)""".r
  private val NoElements = "NO_ELEMENTS"
  private val AlternativeSeparator = "<--->"

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
    val module = host.addModule(ModuleSpec("parameter-info-oracle", "2.13.18", Seq.empty,
      Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
    val only = flags.collectFirst { case f if f.startsWith("--only=") => f.stripPrefix("--only=").split(',').toSet }
    val cases = collect(pluginRoot).filter(c => only.forall(_.contains(c.name)))
    val started = System.nanoTime()
    val results = cases.map { c =>
      val errorsBefore = HostLog.count(HostLog.Level.Error)
      val result = c.skip.fold(runCase(host, module, c))(Result("skip", _))
      c -> (if (result.outcome == "agree" && HostLog.count(HostLog.Level.Error) != errorsBefore)
        Result("disagree", "platform logged an error during replay") else result)
    }
    val suites = results.map(_._1.suite).distinct.sorted
    val lines = Vector.newBuilder[String]
    lines += "# Parameter info oracle"
    lines += ""
    lines += s"Scala 2.13.18, unit-test mode, JDK ${System.getProperty("java.version")}; ${(System.nanoTime() - started) / 1_000_000} ms."
    lines += ""
    lines += "Each case is a `doTest()` method of the plugin's test classes, over the data file named after it, with the `<caret>` marker removed " +
      "and the last comment as the expectation. The host runs the creation, update and rendering steps the test performs, so one comparison covers " +
      "both of the test's checks. Items are compared as sorted sets of `TEXT: <text>, STRIKEOUT: <deprecated>` lines; `<--->` separates alternative " +
      "accepted results and `NO_ELEMENTS` means nothing shown. Classes restricted to Scala 3 are skipped, as are fixtures that need scala-xml."
    lines += ""
    lines += "| Suite | Agree | Disagree | Skip |"
    lines += "|---|---:|---:|---:|"
    for (suite <- suites) {
      val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
      lines += s"| $suite | ${outcomes.count(_ == "agree")} | ${outcomes.count(_ == "disagree")} | ${outcomes.count(_ == "skip")} |"
    }
    lines += ""
    lines += "## Disagreements and skips by cause"
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
    for ((entry, count) <- CallLog.snapshot if entry.startsWith("UNSUPPORTED ") || entry.startsWith("LOGGED ERROR "))
      lines += s"- $entry: $count"
    val report = Path.of(reportPath)
    Files.createDirectories(report.toAbsolutePath.getParent)
    Files.write(report, lines.result().asJava)
    for (suite <- suites) {
      val outcomes = results.filter(_._1.suite == suite).map(_._2.outcome)
      println(s"$suite: ${outcomes.count(_ == "agree")} agree, ${outcomes.count(_ == "disagree")} disagree, ${outcomes.count(_ == "skip")} skip")
    }
    println(s"parameter info oracle: report at $reportPath")
    sys.exit(0)
  }

  private def collect(root: Path): Vector[TestCase] = {
    val testRoot = root.resolve("test/org/jetbrains/plugins/scala/lang/parameterInfo")
    val sources = Using.resource(Files.walk(testRoot))(_.iterator().asScala.filter(_.getFileName.toString.endsWith("Test.scala")).toVector.sorted)
    val cases = sources.flatMap { source =>
      val suite = source.getParent.getFileName.toString
      testClasses(Files.readString(source)).flatMap(c => classCases(root, suite, c._1, c._2))
    }
    // A Scala 3 subclass inherits the base methods the Scala 2 class already runs: report each data file once, preferring a replay.
    cases.sortBy(_.skip.isDefined).distinctBy(c => c.suite + "/" + c.name).sortBy(c => c.suite -> c.name)
  }

  /** The classes of one source file, each with the classes of the same file (its possible parent) to inherit tests from. */
  private def testClasses(source: String): Vector[(TestClass, Vector[TestClass])] = {
    val headers = ClassHeader.findAllMatchIn(source).toVector
    val ends = headers.map(_.start).drop(1) :+ source.length
    val classes = headers.zip(ends).map { (header, end) =>
      TestClass(header.group(2), header.group(3), header.group(1) != null, source.substring(header.start, end))
    }
    classes.map(c => c -> classes)
  }

  private def classCases(root: Path, suite: String, c: TestClass, siblings: Vector[TestClass]): Vector[TestCase] =
    if (c.isAbstract) Vector.empty
    else {
      val inherited = siblings.find(_.name == c.parent)
      val dataDir = c.dataDir.orElse(inherited.flatMap(_.dataDir))
      (inherited.fold(Vector.empty[String])(_.methods) ++ c.methods).flatMap { method =>
        dataDir.map { dir =>
          val path = root.resolve(s"testdata/parameterInfo/$suite/$dir/$method.scala")
          TestCase(suite, s"$dir/$method", path, skipReason(c, path))
        }
      }
    }

  private def skipReason(c: TestClass, path: Path): Option[String] =
    if (c.isScala3) Some("Scala 3 fixture")
    else if (!Files.exists(path)) Some("no data file for this test method")
    // The plugin's test adds scala-xml as an additional library; the oracle's module has only the standard library.
    else if (Files.readString(path).contains("scala.xml")) Some("needs the scala-xml library")
    else None

  private def runCase(host: ScalaPsiHost, module: HostModule, c: TestCase): Result =
    try {
      val text = Files.readString(c.path).replace("\r\n", "\n")
      val offset = text.indexOf("<caret>")
      if (offset < 0 || text.indexOf("<caret>", offset + 1) >= 0) return Result("skip", "requires exactly one caret marker")
      val file = host.openFile(Path.of("/parameter-info-oracle").resolve(c.path.getFileName), text.replace("<caret>", ""), module) match {
        case f: ScalaFile => f
        case other => throw new IllegalStateException(s"not a Scala fixture: $other")
      }
      try {
        host.read(Expected.lastComment(file)) match {
          case None => Result("skip", "no last-comment expectation")
          case Some(comment) => compare(expected(comment), actual(ParameterInfo.at(file, offset)))
        }
      } finally host.closeSource(file)
    } catch {
      case NonFatal(e) =>
        HostLog.error(s"parameter info oracle ${c.suite}/${c.name} failed", e)
        Result("disagree", s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  /** The accepted results: one sorted set of lines per `<--->` alternative. */
  private def expected(comment: String): Vector[Vector[String]] =
    comment.split(AlternativeSeparator).toVector.map(lines)

  /** What the test records for each item: its text and whether it is struck out, or `NO_ELEMENTS` for an empty hint. */
  private def actual(found: Option[ParameterInfo.Signatures]): Vector[String] = {
    val items = found.fold(Vector.empty[ParameterInfo.Signature])(_.items.toVector)
    if (items.isEmpty) Vector(NoElements)
    else lines(items.map(item => s"TEXT: ${item.text}, STRIKEOUT: ${item.deprecated}").mkString("\n"))
  }

  private def lines(text: String): Vector[String] =
    text.split('\n').map(_.trim).filter(_.nonEmpty).toVector.distinct.sorted

  /** Reports the difference to the closest alternative, so a near miss is not described by a distant one. */
  private def compare(accepted: Vector[Vector[String]], actual: Vector[String]): Result =
    if (accepted.contains(actual)) Result("agree", "")
    else {
      val closest = accepted.minBy(alternative => alternative.diff(actual).size + actual.diff(alternative).size)
      Result("disagree", s"missing: ${closest.diff(actual).mkString(", ")}; extra: ${actual.diff(closest).mkString(", ")}")
    }
}
