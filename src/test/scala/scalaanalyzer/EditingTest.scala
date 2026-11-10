package scalaanalyzer

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction

import scalaanalyzer.TestHost.{applyEdits, choose}
import scalaanalyzer.build.{DocumentCompiler, ScalafmtFormatting}
import scalaanalyzer.language.{
  ChangeEngine, CodeActions, Completion, MemberOverrides, ProjectLanguageOperations, ProjectSearch, Rename,
}
import scalaanalyzer.platform.{CallLog, HostDispatchThread, HostLock, HostLog, RequestEditor}

import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Editing as an editor does it: a change to one file's text is reparsed in place and reaches every query that
 * depends on it, including other files' highlighting. Expectations are what scalac reports for each version.
 */
class EditingTest extends munit.FunSuite {
  private def host = TestHost.host

  // The platform is the host's: a test that only uses the lock or the dispatch thread needs it started, whichever
  // test of the JVM runs first.
  override def beforeAll(): Unit = host

  private lazy val module = {
    TestHost.libraryReport // the JDK
    host.addModule(ModuleSpec("editing", "2.13.18", Seq.empty, Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
  }

  private def errors(file: ScalaFile): Seq[String] =
    host.diagnostics(file).filter(_.severity == HighlightSeverity.ERROR).map(_.message)

  // The platform picks a physical file's language; for a module on Scala 3 the plugin's substitutor makes it Scala 3.
  test("a source of a Scala 3 module is Scala 3") {
    val scala3 = host.addModule(ModuleSpec("editing3", "3.3.4", Seq.empty, Seq(Path.of(sys.props("scalaanalyzer.fixture.scalaLibrary")))))
    val file = host.openFile(Path.of("/editing3/C.scala"), "object C:\n  def f(b: Boolean): Int = if b then 1 else 2\n", scala3)
    try {
      assertEquals(host.read(file.getLanguage), org.jetbrains.plugins.scala.Scala3Language.INSTANCE)
      assertEquals(errors(file.asInstanceOf[ScalaFile]), Seq.empty)
    } finally host.closeSource(file)
  }

  test("request editor positions preserve boundary affinity through document edits") {
    final case class EditCase(
      name: String,
      caret: Int,
      selection: TextRange,
      edit: ChangeEngine.Edit,
      expectedCaret: Int,
      expectedSelection: TextRange,
    )

    val project = host.project
    val cases = Seq(
      EditCase("insertion at an unselected caret", caret = 2, selection = new TextRange(2, 2),
        edit = ChangeEngine.Edit(new TextRange(2, 2), "XY"),
        expectedCaret = 4, expectedSelection = new TextRange(4, 4)),
      EditCase("insertion at the selection start", caret = 4, selection = new TextRange(2, 4),
        edit = ChangeEngine.Edit(new TextRange(2, 2), "XY"),
        expectedCaret = 6, expectedSelection = new TextRange(2, 6)),
      EditCase("insertion at the selection end", caret = 4, selection = new TextRange(2, 4),
        edit = ChangeEngine.Edit(new TextRange(4, 4), "XY"),
        expectedCaret = 6, expectedSelection = new TextRange(2, 6)),
      EditCase("replacement covering the caret and selection start", caret = 3, selection = new TextRange(2, 5),
        edit = ChangeEngine.Edit(new TextRange(1, 4), "X"),
        expectedCaret = 2, expectedSelection = new TextRange(2, 3)),
      EditCase("deletion collapsing the selection onto the caret", caret = 6, selection = new TextRange(2, 4),
        edit = ChangeEngine.Edit(new TextRange(2, 4), ""),
        expectedCaret = 4, expectedSelection = new TextRange(4, 4)),
    )

    for (testCase <- cases) HostLock.write {
      val document = new DocumentImpl("abcdef", /* forUseInNonAWTThread = */ true)

      Using.resource(new RequestEditor(document, project, testCase.caret)) { request =>
        val caret = request.editor.getCaretModel
        val selection = request.editor.getSelectionModel
        val edit = testCase.edit

        selection.setSelection(testCase.selection.getStartOffset, testCase.selection.getEndOffset)
        document.replaceString(edit.range.getStartOffset, edit.range.getEndOffset, edit.text)

        assertEquals(caret.getOffset, testCase.expectedCaret, testCase.name)
        assertEquals(caret.getPrimaryCaret.getSelectionRange, testCase.expectedSelection, testCase.name)
      }
    }
  }

  // As the IDE's background reads: a query checks for cancellation as it works, and an edit cancels it rather than
  // waiting for it to finish.
  test("an edit cancels the queries running meanwhile") {
    val file = host.openFile(Path.of("/editing/D.scala"), "object D\n", module)
    try {
      val started = new java.util.concurrent.CountDownLatch(1)
      val query = scala.concurrent.Future {
        host.read {
          started.countDown()
          while (true) com.intellij.openapi.progress.ProgressManager.checkCanceled()
        }
      }(using scala.concurrent.ExecutionContext.global)
      assert(started.await(30, java.util.concurrent.TimeUnit.SECONDS), "the query did not start")

      host.edit(file, 0, 0, "// edited\n")

      intercept[com.intellij.openapi.progress.ProcessCanceledException](
        scala.concurrent.Await.result(query, scala.concurrent.duration.Duration(30, "s")))
      assertEquals(host.read(file.getText), "// edited\nobject D\n")
    } finally host.closeSource(file)
  }

  test("a cancelled request cancels its running query and the queries it starts afterwards") {
    val cancellation = new HostLock.RequestCancellation
    val started = new java.util.concurrent.CountDownLatch(1)
    val request = scala.concurrent.Future {
      HostLock.cancelledBy(cancellation) {
        val running = scala.util.Try(host.read {
          started.countDown()
          while (true) com.intellij.openapi.progress.ProgressManager.checkCanceled()
        })
        (running, scala.util.Try(host.read(42)))
      }
    }(using scala.concurrent.ExecutionContext.global)
    assert(started.await(30, java.util.concurrent.TimeUnit.SECONDS), "the query did not start")

    cancellation.cancel()

    val (running, later) = scala.concurrent.Await.result(request, scala.concurrent.duration.Duration(30, "s"))
    assert(running.failed.toOption.exists(_.isInstanceOf[com.intellij.openapi.progress.ProcessCanceledException]), running)
    assert(later.failed.toOption.exists(_.isInstanceOf[com.intellij.openapi.progress.ProcessCanceledException]), later)
    assertEquals(host.read(42), 42)
  }

  test("a writer cancels a query between read acquisition and progress registration") {
    host.project
    val executor = Executors.newFixedThreadPool(2)
    val readerThread = new AtomicReference[Thread]()
    val writerThread = new AtomicReference[Thread]()
    val reading = new CountDownLatch(1)
    val writing = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val listeners = com.intellij.openapi.util.Disposer.newDisposable()
    HostLock.addReadListener(new com.intellij.openapi.application.ReadActionListener {
      override def readActionStarted(action: Class[?]): Unit =
        if (Thread.currentThread() eq readerThread.get()) {
          reading.countDown()
          if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("the reader was not released")
        }
    }, listeners)
    HostLock.addWriteListener(new com.intellij.openapi.application.WriteActionListener {
      override def beforeWriteActionStart(action: Class[?]): Unit =
        if (Thread.currentThread() eq writerThread.get()) writing.countDown()
    }, listeners)
    try {
      val query = CompletableFuture.supplyAsync(() => {
        readerThread.set(Thread.currentThread())
        HostLock.query(42)
      }, executor)
      assert(reading.await(30, TimeUnit.SECONDS))
      val writer = CompletableFuture.runAsync(() => {
        writerThread.set(Thread.currentThread())
        HostLock.write(())
      }, executor)
      assert(writing.await(30, TimeUnit.SECONDS))
      release.countDown()
      val error = intercept[java.util.concurrent.ExecutionException](query.get(30, TimeUnit.SECONDS))
      assert(error.getCause.isInstanceOf[com.intellij.openapi.progress.ProcessCanceledException])
      writer.get(30, TimeUnit.SECONDS)
      assertEquals(HostLock.query(42), 42)
    } finally {
      release.countDown()
      executor.shutdown()
      val stopped = executor.awaitTermination(30, TimeUnit.SECONDS)
      com.intellij.openapi.util.Disposer.dispose(listeners)
      assert(stopped)
    }
  }

  test("queries arriving while a writer waits for intent are cancelled too") {
    host.project
    val executor = Executors.newFixedThreadPool(2)
    val writerThread = new AtomicReference[Thread]()
    val writing = new CountDownLatch(1)
    val listeners = com.intellij.openapi.util.Disposer.newDisposable()
    HostLock.addIntentListener(new com.intellij.openapi.application.WriteIntentReadActionListener {
      override def beforeWriteIntentReadActionStart(action: Class[?]): Unit =
        if (Thread.currentThread() eq writerThread.get()) writing.countDown()
    }, listeners)
    try {
      val writer = HostLock.writeIntent {
        val pending = CompletableFuture.runAsync(() => {
          writerThread.set(Thread.currentThread())
          HostLock.write(())
        }, executor)
        assert(writing.await(30, TimeUnit.SECONDS))
        val query = CompletableFuture.supplyAsync(() => HostLock.query(42), executor)
        val error = intercept[java.util.concurrent.ExecutionException](query.get(30, TimeUnit.SECONDS))
        assert(error.getCause.isInstanceOf[com.intellij.openapi.progress.ProcessCanceledException])
        pending
      }
      writer.get(30, TimeUnit.SECONDS)
      assertEquals(HostLock.query(42), 42)
    } finally {
      executor.shutdown()
      val stopped = executor.awaitTermination(30, TimeUnit.SECONDS)
      com.intellij.openapi.util.Disposer.dispose(listeners)
      assert(stopped)
    }
  }

  test("a write listener can inspect PSI before the write starts") {
    val file = host.openFile(Path.of("/editing/WriteListener.scala"), "object WriteListener\n", module)
    val listeners = com.intellij.openapi.util.Disposer.newDisposable()
    val writer = Thread.currentThread()
    var observed = ""
    HostLock.addWriteListener(new com.intellij.openapi.application.WriteActionListener {
      override def beforeWriteActionStart(action: Class[?]): Unit =
        if (Thread.currentThread() eq writer) observed = host.read(file.getText)
    }, listeners)
    try {
      host.edit(file, 0, 0, "// edited\n")
      assertEquals(observed, "object WriteListener\n")
      assertEquals(host.read(file.getText), "// edited\nobject WriteListener\n")
    } finally {
      com.intellij.openapi.util.Disposer.dispose(listeners)
      host.closeSource(file)
    }
  }

  // The word index follows edits: a usage added to or removed from a file is found, or no longer found.
  test("reference search finds usages in other files and follows edits") {
    val aText = "object Searched { def target: Int = 1 }\n"
    val bText = "object SearchUser { val x: Int = 1 }\n"
    val a = host.openFile(Path.of("/editing/Searched.scala"), aText, module).asInstanceOf[ScalaFile]
    val b = host.openFile(Path.of("/editing/SearchUser.scala"), bText, module).asInstanceOf[ScalaFile]
    def usages: Seq[(String, String)] = host.read {
      val target = PsiTreeUtil.findChildrenOfType(a, classOf[ScFunction]).asScala.head
      ReferencesSearch.search(target).findAll().asScala.map(ref => ref.getElement.getContainingFile.getName -> ref.getElement.getText).toSeq
    }
    try {
      assertEquals(usages, Seq.empty)

      val usage = bText.indexOf("1")
      host.edit(b, usage, usage + 1, "Searched.target")
      assertEquals(usages, Seq("SearchUser.scala" -> "Searched.target"))

      host.edit(b, usage, usage + "Searched.target".length, "1")
      assertEquals(usages, Seq.empty)
    } finally {
      host.closeSource(a)
      host.closeSource(b)
    }
  }

  test("rename restores class and word indices before the editor applies its edits") {
    val original = "package renamed\nclass BeforeRename\nobject RenameUser { val x = new BeforeRename }\n"
    val file = scalaFile("BeforeRename", original)
    try {
      val documents = com.intellij.psi.PsiDocumentManager.getInstance(host.project)
      for (_ <- 1 to 2) {
        val changes = host.rename(file, original.indexOf("BeforeRename"), "AfterRename")
        assertEquals(changes.map(_._1), Seq(file))
        assertEquals(applyEdits(original, changes.head._2), original.replace("BeforeRename", "AfterRename"))
        host.read {
          assertEquals(file.getText, original)
          assertEquals(documents.getDocument(file).getText, original)
          assert(documents.isCommitted(documents.getDocument(file)))
          val facade = com.intellij.psi.JavaPsiFacade.getInstance(host.project)
          val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(host.project)
          assert(facade.findClass("renamed.BeforeRename", scope) != null)
          assertEquals(facade.findClass("renamed.AfterRename", scope), null)
          assertEquals(ProjectSearch.usages(ProjectSearch.usageTarget(file, original.indexOf("BeforeRename")).get).size, 1)
        }
      }
    } finally host.closeSource(file)
  }

  // PackagePrivateUseScopeTest: the scope includes the declaring package and its children, but not another package.
  test("package-private use scopes include sources in child packages") {
    val texts = Seq(
      "package scoped\nprivate[scoped] class ScopedClass\n",
      "package scoped\nobject SamePackage\n",
      "package scoped.child\nobject ChildPackage\n",
      "package elsewhere\nobject OtherPackage\n",
    )
    val files = texts.zipWithIndex.map((text, i) => scalaFile(s"Scope$i", text))
    try host.read {
      val target = ProjectSearch.usageTarget(files.head, texts.head.indexOf("ScopedClass")).get
      val scope = target.getUseScope
      assertEquals(files.map(f => scope.contains(f.getVirtualFile)), Seq(true, true, true, false))
    } finally files.foreach(host.closeSource)
  }

  test("an edit is reparsed in place and changes what other files see") {
    val aText = "object A { def f: Int = 1 }\n"
    val a = host.openFile(Path.of("/editing/A.scala"), aText, module).asInstanceOf[ScalaFile]
    val b = host.openFile(Path.of("/editing/B.scala"), "object B { val x: Int = A.f }\n", module).asInstanceOf[ScalaFile]
    try {
      assertEquals(errors(b), Seq.empty)

      val start = aText.indexOf("Int = 1")
      host.edit(a, start, start + "Int = 1".length, "String = \"s\"")

      assertEquals(host.read(a.getText), "object A { def f: String = \"s\" }\n")
      assert(host.read(a.isValid), "the edited file is the same PSI file, reparsed in place")
      assertEquals(errors(b), Seq("Type mismatch. Required: Int, found: String"))
      // Nothing left the host's tables or was logged as an error on the way (both would hide problems).
      val swallowed = CallLog.snapshot.map(_._1).filter(e => e.startsWith("UNSUPPORTED ") || e.startsWith("LOGGED ERROR "))
      assertEquals(swallowed, Seq.empty)
    } finally {
      host.closeSource(a)
      host.closeSource(b)
    }
  }

  private def scalaFile(name: String, text: String): ScalaFile =
    host.openFile(Path.of(s"/editing/$name.scala"), text, module) match {
      case f: ScalaFile => f
      case other => fail(s"not Scala: $other")
    }

  /**
   * Runs `query` on the language operations over one source, whose text is `marked` without its `|`, the caret's place.
   *
   * @param fileName the source's name, whose extension picks its language
   * @param marked the source's text, with `|` where the caret is
   * @param query reads the operations, the source's path, its text and the caret's position
   */
  private def atCaret[A](fileName: String, marked: String)(
      query: (ProjectLanguageOperations, Path, String, LineColumn) => A): A = {
    val text = marked.replace("|", "")
    val before = marked.substring(0, marked.indexOf('|'))
    val caret = LineColumn(before.count(_ == '\n'), before.length - (before.lastIndexOf('\n') + 1))
    val path = Path.of(s"/editing/$fileName")
    val file = host.openFile(path, text, module)
    val formatterCache = Path.of(sys.props("scalaanalyzer.stateDir")).resolve("scalafmt")

    try Using.resources(new DocumentCompiler, new ScalafmtFormatting(formatterCache)) { (compiler, scalafmt) =>
      query(new ProjectLanguageOperations(host, Path.of("/editing"), compiler, scalafmt, _ => file), path, text, caret)
    } finally host.closeSource(file)
  }

  /** The identifier, or else the run of operator characters, that starts at a position of `text`. */
  private def nameAt(text: String, at: LineColumn): String = {
    val rest = text.drop(text.linesWithSeparators.take(at.line).map(_.length).sum + at.column)

    if (rest.head.isUnicodeIdentifierPart) rest.takeWhile(_.isUnicodeIdentifierPart)
    else rest.takeWhile("+-*/<>=!&|^%~?:#@\\".contains(_))
  }

  // The IDE looks around the caret (TargetElementUtil.adjustOffset, and the platform's leavesAroundOffset), so that a
  // caret right after a name stands for it; at the start of a name the name is to the right of the caret.
  test("go to definition finds the name around a caret as the IDE does") {
    final case class Case(label: String, fileName: String, marked: String, definitions: Seq[String])
    val cases = Seq(
      Case("Scala, before a call's arguments", "Sample.scala",
        "object Sample {\n  def foo(i: Int): Int = i\n  val x = foo|(1)\n}\n", Seq("foo")),
      Case("Scala, at the end of a line", "Sample.scala",
        "object Sample {\n  def foo: Int = 1\n  val x = foo|\n}\n", Seq("foo")),
      Case("Scala, in a name", "Sample.scala", "object Sample {\n  def foo: Int = 1\n  val x = f|oo\n}\n", Seq("foo")),
      Case("Scala, before a member selection", "Sample.scala",
        "object Sample {\n  def foo: Int = 1\n  val x = Sample|.foo\n}\n", Seq("Sample")),
      Case("Scala, at the start of a selected member", "Sample.scala",
        "object Sample {\n  def foo: Int = 1\n  val x = Sample.|foo\n}\n", Seq("foo")),
      Case("Scala, an operator after a value", "Sample.scala",
        "class Point {\n  def *(i: Int): Point = this\n}\nobject Sample {\n  val p = new Point\n  val q = p|*2\n}\n",
        Seq("*")),
      Case("Scala, after a closing parenthesis", "Sample.scala",
        "object Sample {\n  def foo(i: Int): Int = i\n  val x = foo(1)| + 1\n}\n", Seq.empty),
      Case("Java, before a call's arguments", "Sample.java",
        "class Sample {\n  int foo(int i) { return i; }\n  int x = foo|(1);\n}\n", Seq("foo")),
      Case("Java, before an operator", "Sample.java", "class Sample {\n  int x = 1;\n  int y = x|+2;\n}\n", Seq("x")),
      Case("Java, at the end of a statement", "Sample.java",
        "class Sample {\n  int x = 1;\n  int y = x|;\n}\n", Seq("x")),
      Case("Java, before a member selection", "Sample.java",
        "class Sample {\n  static int foo() { return 1; }\n  int y = Sample|.foo();\n}\n", Seq("Sample")),
      Case("Java, at the start of a selected member", "Sample.java",
        "class Sample {\n  static int foo() { return 1; }\n  int y = Sample.|foo();\n}\n", Seq("foo")),
      // Go to Declaration falls through to the declaration's own element; the client then shows the usages.
      Case("Scala, a declared value", "Sample.scala", "object Sample {\n  val a| = 1\n  val b = a\n}\n", Seq("a")),
      Case("Scala, in a declared method's name", "Sample.scala",
        "object Sample {\n  def f|oo(i: Int): Int = i\n}\n", Seq("foo")),
      Case("Scala, a declared class", "Sample.scala", "class Po|int\nobject Sample {\n  val p = new Point\n}\n",
        Seq("Point")),
      Case("Scala, a declared parameter", "Sample.scala", "object Sample {\n  def foo(|i: Int): Int = i\n}\n",
        Seq("i")),
      Case("Java, a declared field", "Sample.java", "class Sample {\n  int x| = 1;\n  int y = x;\n}\n", Seq("x")),
      Case("Java, a declared method", "Sample.java", "class Sample {\n  int f|oo() { return 1; }\n}\n", Seq("foo")),
      Case("Java, a declared class", "Sample.java", "class Sam|ple {\n}\n", Seq("Sample")),
    )

    for (c <- cases) atCaret(c.fileName, c.marked) { (operations, path, text, caret) =>
      val definitions = operations.definition(path, caret).map((_, position) => nameAt(text, position))

      assertEquals(definitions, c.definitions, c.label)
    }
  }

  test("go to type definition finds the name around a caret as the IDE does") {
    final case class Case(label: String, fileName: String, marked: String)
    val cases = Seq(
      Case("Scala, a value before a member selection", "Sample.scala",
        "class Point\nobject Sample {\n  val p = new Point\n  val q = p|.toString\n}\n"),
      Case("Scala, a value at the end of a line", "Sample.scala",
        "class Point\nobject Sample {\n  val p = new Point\n  val q = p|\n}\n"),
      Case("Scala, a declared name", "Sample.scala", "class Point\nobject Sample {\n  val p| = new Point\n}\n"),
      Case("Java, a field before a member selection", "Sample.java",
        "class Point {}\nclass Sample {\n  Point p;\n  int h = p|.hashCode();\n}\n"),
      Case("Java, a field at the end of a statement", "Sample.java",
        "class Point {}\nclass Sample {\n  Point p;\n  Point q = p|;\n}\n"),
      Case("Java, a declared name", "Sample.java", "class Point {}\nclass Sample {\n  Point p|;\n}\n"),
    )

    for (c <- cases) atCaret(c.fileName, c.marked) { (operations, path, text, caret) =>
      val types = operations.typeDefinition(path, caret).map((_, position) => nameAt(text, position))

      assertEquals(types, Seq("Point"), c.label)
    }
  }

  test("hover, highlights and renaming find the name before a caret as the IDE does") {
    final case class Case(label: String, fileName: String, marked: String, name: String, hover: String)
    val declaration = LineColumn(1, 6)
    val usage = LineColumn(2, 10)
    val cases = Seq(
      Case("Scala, before a call's arguments", "Sample.scala",
        "object Sample {\n  def foo(i: Int): Int = i\n  val x = foo|(1)\n}\n", "foo", "def foo(i: Int): Int"),
      Case("Scala, at the end of a line", "Sample.scala",
        "object Sample {\n  val a = 1\n  val b = a|\n}\n", "a", "val a: Int"),
      Case("Scala, a declared name", "Sample.scala",
        "object Sample {\n  val a| = 1\n  val b = a\n}\n", "a", "val a: Int"),
      Case("Java, before an operator", "Sample.java",
        "class Sample {\n  int x = 1;\n  int y = x|+2;\n}\n", "x", "int x"),
      Case("Java, at the end of a statement", "Sample.java",
        "class Sample {\n  int x = 1;\n  int y = x|;\n}\n", "x", "int x"),
    )

    for (c <- cases) atCaret(c.fileName, c.marked) { (operations, path, _, caret) =>
      val hover = operations.hover(path, caret)
      val occurrences = operations.highlights(path, caret).map(_.range.start).sortBy(p => (p.line, p.column))
      val renamed = operations.prepareRename(path, caret)
      // The name the caret touches is the one that renaming replaces: the occurrence on the caret's line.
      val touched = Seq(declaration, usage).filter(_.line == caret.line).head
      val name = LineRange(touched, touched.copy(column = touched.column + c.name.length))

      assert(hover.exists(_.contains(c.hover)), s"hover, ${c.label}: $hover")
      assertEquals(occurrences, Seq(declaration, usage), s"highlights, ${c.label}")
      assertEquals(renamed, name, s"rename, ${c.label}")
    }
  }

  // The IDE asks whether to rename the base method too; the host answers yes (SuperMethodWarningUtil's unit-test
  // answer), so the target is the deepest declaration the method overrides.
  test("renaming a Java method targets the base method it overrides") {
    final case class Case(label: String, marked: String, target: Either[String, String])
    val cases = Seq(
      Case("implements an interface method",
        "interface Base { void run(); }\nclass Impl implements Base { public void ru|n() {} }", Right("Base.run")),
      Case("overrides a class method",
        "class Base { void run() {} }\nclass Impl extends Base { @Override void ru|n() {} }", Right("Base.run")),
      Case("overrides through an intermediate class",
        "class Base { void run() {} }\nclass Mid extends Base { @Override void run() {} }\n" +
          "class Impl extends Mid { @Override void ru|n() {} }",
        Right("Base.run")),
      Case("is called on the overrider",
        "interface Base { void run(); }\nclass Impl implements Base { public void run() {} }\n" +
          "class Use { void f(Impl i) { i.ru|n(); } }",
        Right("Base.run")),
      Case("overrides nothing", "class Solo { void ru|n() {} }", Right("Solo.run")),
      Case("overrides a library method", "class Impl { @Override public String toS|tring() { return \"\"; } }",
        Left("This element is not located inside the project.")),
    )

    for ((c, i) <- cases.zipWithIndex) {
      val offset = c.marked.indexOf('|')
      val file = host.openFile(Path.of(s"/editing/Rename$i.java"), c.marked.replace("|", ""), module)
      try {
        val described = scala.util.Try(host.renameTarget(file, offset)).toEither
          .left.map { case refused: Rename.Refused => refused.getMessage; case other => fail(c.label, other) }
          .map { case method: PsiMethod =>
            host.read(s"${method.getContainingClass.getName}.${method.getName}")
          }

        assertEquals(described, c.target, c.label)
      } finally host.closeSource(file)
    }
  }

  test("completion renders member signatures, and its handler adds the parentheses once chosen") {
    val text = "object Complete { val emoji = \"😀\"; def message(n: Int): String = \"hi\"; Complete.mes }"
    val file = scalaFile("Complete", text)
    try {
      val offset = text.indexOf("Complete.mes") + "Complete.mes".length
      val result = host.completion(file, offset)
      val item = result.items.find(_.lookup == "message").getOrElse(fail(result.toString))
      assertEquals(item.kind, Completion.Kind.Method)
      assert(item.tail.exists(_.contains("Int")) && item.typeText.contains("String"), item.toString)
      assertEquals(item.prefix, new TextRange(offset - 3, offset))
      assert(item.hasHandler, item.toString)
      assertEquals(host.read(file.getText), text)
      assert(choose(file, offset, "message").contains("Complete.message(<caret>)"))
    } finally host.closeSource(file)
  }

  test("Java completion preserves overloads, prefix ranges and insertion without changing the source") {
    val text = "class JavaComplete { String message(int n) { return \"hi\"; } @Deprecated String message(String s) { return s; } void use() { String emoji = \"😀\"; this.mes; boolean flag = tr; } }"
    val file = host.openFile(Path.of("/editing/JavaComplete.java"), text, module)
    try {
      val offset = text.indexOf("this.mes") + "this.mes".length
      val result = host.completion(file, offset)
      val methods = result.items.filter(_.lookup == "message")
      assertEquals(methods.size, 2, result.toString)
      assert(methods.forall(_.kind == Completion.Kind.Method))
      assert(methods.forall(_.prefix == new TextRange(offset - 3, offset)))
      assertEquals(methods.count(_.deprecated), 1)
      assertEquals(methods.map(_.identity).distinct.size, 2)
      val keywords = host.completion(file, text.indexOf("= tr") + "= tr".length).items
      assert(keywords.exists(item => item.lookup == "true" && item.kind == Completion.Kind.Keyword), keywords.toString)
      val inserted = Completion.choose(file, offset, "message", com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
      assert(inserted.result.text.contains("this.message()"), inserted.toString)
      assertEquals(inserted.caret, inserted.result.text.indexOf("this.message(") + "this.message(".length)
      val keyword = Completion.choose(file, text.indexOf("= tr") + "= tr".length, "true",
        com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
      assert(keyword.result.text.contains("boolean flag = true;"), keyword.toString)
      assertEquals(keyword.caret, keyword.result.text.indexOf("flag = true") + "flag = true".length)
      assertEquals(host.read(file.getText), text)
      // As in LSP replay, the editor has already replaced the prefix when the command arrives.
      val start = text.indexOf("= tr") + "= ".length
      val candidate = keywords.find(_.lookup == "true").getOrElse(fail(keywords.toString))
      host.edit(file, start, start + 2, candidate.lookup)
      val selected = text.substring(0, start) + candidate.lookup + text.substring(start + 2)
      val replayed = host.insertCompletion(file, start, candidate.prefixText, candidate.lookup, candidate.identity)
        .getOrElse(fail("the keyword could not be replayed"))
      assertEquals(replayed.result.edits, Seq.empty)
      assertEquals(replayed.caret, start + candidate.lookup.length)
      assertEquals(host.read(file.getText), selected)
    } finally host.closeSource(file)
  }

  test("Java member completion respects inherited access and static qualifiers") {
    val declarations = "class Parent { public int visible; private int hidden; public static int shared; public <T> T echo(T value) { return value; } } "
    Seq("this" -> Set("visible", "echo"), "Parent" -> Set("shared")).foreach { (qualifier, expected) =>
      val text = declarations + s"class Child extends Parent { void use() { $qualifier. } }"
      val file = host.openFile(Path.of("/editing/Child.java"), text, module)
      try {
        val offset = text.indexOf(s"$qualifier. }") + qualifier.length + 1
        val names = host.completion(file, offset).items.map(_.lookup).toSet
        assert(expected.subsetOf(names), names.toString)
        assert(!names.contains("hidden"), names.toString)
        val excluded = if (qualifier == "this") Set("shared") else Set("visible", "echo")
        assert(names.intersect(excluded).isEmpty, names.toString)
      } finally host.closeSource(file)
    }
  }

  test("Java class completion imports source and JDK classes without changing the original") {
    val declaration = host.openFile(Path.of("/editing/models/Model.java"), "package models; public class Model {}", module)
    try {
      Seq(("Mod", "Model", "models.Model"), ("ArrayL", "ArrayList", "java.util.ArrayList")).foreach { (prefix, name, qualified) =>
        val text = s"class JavaClient { Object value = new $prefix; }"
        val file = host.openFile(Path.of("/editing/JavaClient.java"), text, module)
        try {
          val offset = text.indexOf(prefix + ";") + prefix.length
          val items = host.completion(file, offset).items
          assert(items.exists(item => item.lookup == name && item.kind == Completion.Kind.Class), items.toString)
          val inserted = Completion.choose(file, offset, name, com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
          assert(inserted.result.text.contains(s"import $qualified;"), inserted.toString)
          assert(inserted.result.text.contains(s"new $name") && inserted.result.text.contains("()"), inserted.toString)
          assertEquals(host.read(file.getText), text)
        } finally host.closeSource(file)
      }
    } finally host.closeSource(declaration)
  }

  test("completion after `new` presents library Java classes") {
    val text = "object AfterNew { val t = new Threa; val l = new java.util.ArrayLi }"
    val file = scalaFile("AfterNew", text)
    try {
      for ((prefix, name) <- Seq("new Threa" -> "Thread", "new java.util.ArrayLi" -> "ArrayList")) {
        val result = host.completion(file, text.indexOf(prefix) + prefix.length)
        assert(result.items.exists(_.lookup == name), s"$prefix: $result")
      }
    } finally host.closeSource(file)
  }

  test("completion preserves overloads and deprecation, and imports a chosen class") {
    val hidden = host.openFile(Path.of("/editing/Hidden.scala"), "package hidden\nclass ZebraUnimported", module)
    val text = "package visible\nobject Lookup { @deprecated(\"old\", \"1\") def message(n: Int): String = \"\"; " +
      "def message(n: String): Int = 0; Lookup.mes }"
    val file = scalaFile("Lookup", text)
    try {
      val offset = text.indexOf("Lookup.mes") + "Lookup.mes".length
      val completed = host.completion(file, offset)
      val limited = Completion.complete(file, offset, limit = 1)
      assertEquals(limited.items, completed.items.take(1))
      assert(limited.incomplete)
      val unlimited = Completion.complete(file, offset, limit = Int.MaxValue)
      assertEquals(unlimited, completed)
      val items = completed.items.filter(_.lookup == "message")
      assertEquals(items.size, 2)
      assertEquals(items.map(_.identity).distinct.size, 2)
      assertEquals(items.count(_.deprecated), 1)
      for (expression <- Seq("val value: ZebraUn = ???", "val value: Any = new ZebraUn")) {
        val changed = s"package visible\nobject Lookup { $expression }"
        host.edit(file, 0, host.read(file.getTextLength), changed)
        val chosen = choose(file, changed.indexOf("ZebraUn") + "ZebraUn".length, "ZebraUnimported")
        assert(chosen.contains("import hidden.ZebraUnimported"), chosen)
        assert(chosen.replace("<caret>", "").contains(expression.replace("ZebraUn", "ZebraUnimported")), chosen)
      }
    } finally {
      host.closeSource(file)
      host.closeSource(hidden)
    }
  }

  test("completion ranks a local before a named argument, whose handler adds the assignment") {
    val text = "object Named { def use = { val namelocal = \"s\"; " +
      "def printName(nameParam: String = \"Unknown\"): Unit = (); printName(name) } }"
    val file = scalaFile("Named", text)
    try {
      val offset = text.indexOf("printName(name)") + "printName(name".length
      val names = host.completion(file, offset).items.map(_.lookup)
      assert(names.contains("namelocal") && names.contains("nameParam"), names)
      assert(names.indexOf("namelocal") < names.indexOf("nameParam"), names)
      assert(choose(file, offset, "nameParam").contains("printName(nameParam = <caret>)"))
    } finally host.closeSource(file)
  }

  test("a chosen constructor gets its import and the caret between its parentheses") {
    val target = host.openFile(Path.of("/editing/ConstructorTarget.scala"), "package imported\nclass ConstructorTarget(n: Int)\n", module)
    val text = "object ConstructorUser { val x = new ConstructorT }"
    val file = scalaFile("ConstructorUser", text)
    try {
      val chosen = choose(file, text.indexOf("ConstructorT }") + "ConstructorT".length, "ConstructorTarget")
      assert(chosen.contains("new ConstructorTarget(<caret>)"), chosen)
      assert(chosen.contains("import imported.ConstructorTarget"), chosen)
    } finally {
      host.closeSource(file)
      host.closeSource(target)
    }
  }

  test("string, backtick and keyword handlers preserve surrounding text and their caret") {
    val cases = Seq(
      ("object Strings { val member = 1; val s = \"$mem<caret>\" }", "member", "object Strings { val member = 1; val s = s\"$member<caret>\" }"),
      ("object Strings { object value { val member = 1 }; val s = s\"$value.mem<caret>\" }", "member",
        "object Strings { object value { val member = 1 }; val s = s\"${value.member<caret>}\" }"),
      ("object Ticks { def `type`(): Unit = (); Ticks.`ty<caret>` }", "`type`", "object Ticks { def `type`(): Unit = (); Ticks.`type`()<caret> }"),
      ("object Keywords {\n  val n = 1\n  n mat<caret>\n}", "match", "object Keywords {\n  val n = 1\n  n match {\n    case <caret>\n  }\n}")
    )
    for ((marked, lookup, expected) <- cases) {
      val file = scalaFile("Handler", marked.replace("<caret>", ""))
      try assertEquals(choose(file, marked.indexOf("<caret>"), lookup), expected)
      finally host.closeSource(file)
    }
  }

  // Before and after texts are those of the plugin's postfixTemplate/tabCompletion test data.
  test("postfix templates complete after a dot and expand to what the IDE writes, caret included") {
    def program(body: String) = s"object Example {\n  def foo(o: AnyRef, list: List[Int]) = {\n    $body\n  }\n}\n"
    val cases = Seq(
      ("if", "o != null.if", "if (o != null) {<caret>}"),
      ("match", "o.match", "o match {\n      case <caret> =>\n    }"),
      ("for", "list.for", "for (elem <- list) {<caret>}"),
      ("var", "2.var + 5", "val i: Int = 2\n    i<caret> + 5"),
      ("not", "if (1 == 1.not) {}", "if (!(1 == 1)<caret>) {}"),
      ("par", "o.toString.par", "(o.toString)<caret>"),
      ("notnull", "o.notnull", "if (o != null) {<caret>}"),
    )
    for ((lookup, typed, expected) <- cases) {
      // The caret is at the end of the typed key.
      val marked = program(typed.replaceFirst(s"\\.$lookup", s".$lookup<caret>"))
      val file = scalaFile("Postfix", marked.replace("<caret>", ""))
      try {
        val offset = marked.indexOf("<caret>")
        val items = host.completion(file, offset).items
        val item = items.find(_.lookup == lookup).getOrElse(fail(s"$typed: no item $lookup"))
        assertEquals(item.kind, Completion.Kind.Snippet, typed)
        assert(item.hasHandler, typed)
        assertEquals(choose(file, offset, lookup), program(expected), typed)
      } finally host.closeSource(file)
    }
  }

  test("a postfix template is offered with its example, for the key typed so far") {
    // `if` applies to a Boolean expression only; the caret is at the end of the key, as when typing it.
    for (typed <- Seq("i", "if")) {
      val text = s"object Keyed { def foo(flag: Boolean) = flag.$typed }"
      val file = scalaFile("Keyed", text)
      try {
        val item = host.completion(file, text.indexOf(s"flag.$typed") + s"flag.$typed".length).items
          .find(_.lookup == "if").getOrElse(fail(s"no item if after `$typed`"))
        assertEquals((item.typeText, item.prefixText), (Some("if (expr)"), typed), typed)
      } finally host.closeSource(file)
    }
  }

  test("no postfix template is offered after a package or type qualifier") {
    val cases = Seq(
      ("import scala.col\nobject ImportQualifier", "scala.col"),
      ("object TypeQualifier { val x: scala.Lis = null }", "scala.Lis"),
    )
    for ((text, qualified) <- cases) {
      val file = scalaFile("Qualifier", text)
      try {
        val items = host.completion(file, text.indexOf(qualified) + qualified.length).items
        assert(items.nonEmpty, qualified)
        assertEquals(items.filter(_.kind == Completion.Kind.Snippet), Seq.empty, qualified)
      } finally host.closeSource(file)
    }
  }

  test("a chosen class is imported by its package prefix when the code style asks for it") {
    val manager = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(host.project)
    val settings = host.read(manager.getCurrentSettings.clone())
    settings.getCustomSettings(classOf[org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings])
      .setImportsWithPrefix(Array("scala.collection.mutable._"))
    for (expression <- Seq("val value: ArrayBuff = ???", "val value = new ArrayBuff")) {
      val text = s"object PrefixUser { $expression }"
      val file = scalaFile("PrefixUser", text)
      try manager.computeWithLocalSettings(settings, _ => {
        val chosen = choose(file, text.indexOf("ArrayBuff") + "ArrayBuff".length, "ArrayBuffer")
        assert(chosen.contains("mutable.ArrayBuffer"), chosen)
        assert(chosen.contains("import scala.collection.mutable"), chosen)
      }) finally host.closeSource(file)
    }
  }

  test("completion offers an abstract class after `new`, though its handler's dialog cannot run") {
    val text = "trait NeedsImplementation { def run(): Unit }; object User { new NeedsImpl }"
    val file = scalaFile("NeedsImplementation", text)
    try {
      val chosen = choose(file, text.lastIndexOf("NeedsImpl") + "NeedsImpl".length, "NeedsImplementation")
      assert(chosen.contains("new NeedsImplementation"), chosen)
    } finally host.closeSource(file)
  }

  test("word-aligned character diffs separate imports and preserve UTF-16 boundaries") {
    val original = "object Diff { val n = 1 }\n"
    val imported = "import example.Value\n\n"
    val changed = imported + original.replace("1", "2")
    val edits = ChangeEngine.diff(original, changed)
    assertEquals(edits.map(_.text), Seq(imported, "2"))
    assertEquals(applyEdits(original, edits), changed)
    assertEquals(ChangeEngine.diff(original, original), Seq.empty)
    val supplementary = "// 😀\nval x=1\n"
    val replacement = "// 🦊\nval x = 1\n"
    val unicodeEdits = ChangeEngine.diff(supplementary, replacement)
    assertEquals(unicodeEdits.head.range, new TextRange(3, 5))
    assertEquals(applyEdits(supplementary, unicodeEdits), replacement)
  }

  test("copy edits reconstruct exactly without changing source PSI, document or index") {
    val text = "object CopyOriginal { val emoji = \"😀\"; val number = 1 }\n"
    val file = host.openFile(Path.of("/editing/CopyOriginal.scala"), text, module) match {
      case f: ScalaFile => f
      case other => fail(s"not Scala: $other")
    }
    try {
      val documents = com.intellij.psi.PsiDocumentManager.getInstance(host.project)
      val stamp = host.read(documents.getDocument(file).getModificationStamp)
      val key = com.intellij.openapi.util.Key.create[String]("change-engine.copyable")
      host.read(file.putCopyableUserData(key, "copied"))
      val (changed, _) = ChangeEngine.run(file) { copy =>
        assert(!copy.isPhysical)
        assertEquals(copy.getCopyableUserData(key), "copied")
        assertEquals(org.jetbrains.plugins.scala.project.ScalaFeatures.forPsiOrDefault(copy).isScala3,
          org.jetbrains.plugins.scala.project.ScalaFeatures.forPsiOrDefault(file).isScala3)
        val document = copy.getViewProvider.getDocument
        val start = document.getText.indexOf("😀")
        document.replaceString(start, start + "😀".length, "🦊")
        documents.commitDocument(document)
        assert(copy.getText.contains("🦊"), copy.getText)
        document.insertString(document.getTextLength, "// done\n")
      }
      assertEquals(changed.text, text.replace("😀", "🦊") + "// done\n")
      assertEquals(applyEdits(text, changed.edits), changed.text)
      assertEquals(host.read(file.getText), text)
      assertEquals(host.read(documents.getDocument(file).getText), text)
      assertEquals(host.read(documents.getDocument(file).getModificationStamp), stamp)
      host.read {
        val classes = com.intellij.psi.search.PsiShortNamesCache.getInstance(host.project)
          .getClassesByName("CopyOriginal", com.intellij.psi.search.GlobalSearchScope.allScope(host.project))
        assert(classes.nonEmpty)
        assert(classes.forall(_.getContainingFile eq file), classes.toSeq)
      }
    } finally host.closeSource(file)
  }

  test("code actions preview intentions and annotator fixes without changing the source") {
    val cases = Seq(
      ("object Action { val a = 1; val b = 2; val x = a ==<caret> b }", "Negate",
        "object Action { val a = 1; val b = 2; val x = !(a != b)\n}"),
      ("object wrapper { val string: String = ???; val value: Int = <caret>string }", "Change type 'Int' to 'String'",
        "object wrapper { val string: String = ???; val value: String = string }"),
      // Its preview moves the caret along the line after inserting the gap.
      ("object Gap { val s = \"ab<caret>cd\" }", "Insert gap with concatenation",
        "object Gap { val s = \"ab\" +  + \"cd\" }"),
      // The platform's list intentions, through the plugin's split/join contexts (ScalaSplitArgumentsIntentionTest and
      // ScalaJoinArgumentsIntentionTest, `testMethodCall1`).
      ("object Action {\n  def foo(i: Int, s: String): Unit = {}\n  foo(<caret>42, \"boo\")\n}",
        "Put arguments on separate lines",
        "object Action {\n  def foo(i: Int, s: String): Unit = {}\n  foo(42,\n    \"boo\")\n}"),
      ("object Action {\n  def foo(i: Int, s: String): Unit = {}\n  foo<caret>(42,\n    \"boo\")\n}",
        "Put arguments on one line",
        "object Action {\n  def foo(i: Int, s: String): Unit = {}\n  foo(42, \"boo\")\n}"),
      ("object Action { def f(a: Int, b: Int) = 0; val x = f(1<caret>, 2) }", "Flip",
        "object Action { def f(a: Int, b: Int) = 0; val x = f(2, 1) }"),
    )
    TestHost.libraryReport
    for ((marked, title, expected) <- cases) {
      val original = marked.replace("<caret>", "")
      val file = scalaFile("Action", original)
      try {
        val at = marked.indexOf("<caret>")
        val tracker = com.intellij.psi.util.PsiModificationTracker.getInstance(host.project)
        val modifications = tracker.getModificationCount
        val actions = host.codeActions(file, new TextRange(at, at)).choices
        // Previews change only copies: the source's cached diagnostics stay valid for the next request.
        assertEquals(tracker.getModificationCount, modifications)
        val chosen = actions.find(_.title.startsWith(title)).getOrElse(fail(actions.toString))
        assertEquals(chosen.disabled, None)
        assertEquals(applyEdits(original, chosen.edits), expected)
        assertEquals(host.read(file.getText), original)
      } finally host.closeSource(file)
    }
  }

  // Unwrapping leaves the whitespace around the extracted statements to the formatter, so the texts are compared
  // with their whitespace collapsed.
  test("unwrap actions apply the plugin's unwrappers on a copy and keep the source") {
    val cases = Seq(
      ("object Action { def f(b: Boolean): Unit = { if (b) { <caret>println(1) } } }", "Unwrap 'if...'",
        "object Action { def f(b: Boolean): Unit = { println(1) } }"),
      ("object Action { def f(b: Boolean): Unit = if (b) println(1) <caret>else println(2) }", "Remove 'else...'",
        "object Action { def f(b: Boolean): Unit = if (b) println(1) }"),
    )
    TestHost.libraryReport
    for ((marked, title, expected) <- cases) {
      val original = marked.replace("<caret>", "")
      val file = scalaFile("Action", original)
      try {
        val at = marked.indexOf("<caret>")
        val actions = host.codeActions(file, new TextRange(at, at)).choices
        val chosen = actions.find(_.title.startsWith(title)).getOrElse(fail(actions.toString))
        assertEquals(chosen.disabled, None)
        assertEquals(applyEdits(original, chosen.edits).replaceAll("\\s+", " "), expected)
        assertEquals(host.read(file.getText), original)
      } finally host.closeSource(file)
    }
  }

  // Expected texts follow the plugin's inline tests (testdata/refactoring/inline), moved into a block or a private
  // member.
  test("inline actions replace the usages of a declaration that only its file sees, on a copy") {
    val all = "Inline all and remove the declaration"
    val one = "Inline this occurrence"
    final case class InlineCase(name: String, offered: Seq[String], title: String, marked: String, expected: String)

    val cases = Seq(
      InlineCase("every usage of a local val, from its declaration", Seq(all), all,
        """object InlineVal {
          |  def f: Int = {
          |    val <caret>r = 33
          |    r + r
          |  }
          |}
          |""".stripMargin,
        """object InlineVal {
          |  def f: Int = {
          |    33 + 33
          |  }
          |}
          |""".stripMargin),
      InlineCase("every usage of a local val, from a usage", Seq(all, one), all,
        """object InlineVal {
          |  def f: Int = {
          |    val r = 33
          |    r + <caret>r
          |  }
          |}
          |""".stripMargin,
        """object InlineVal {
          |  def f: Int = {
          |    33 + 33
          |  }
          |}
          |""".stripMargin),
      InlineCase("one usage of a local val", Seq(all, one), one,
        """object InlineVal {
          |  def f: Int = {
          |    val r = 33
          |    r + <caret>r
          |  }
          |}
          |""".stripMargin,
        """object InlineVal {
          |  def f: Int = {
          |    val r = 33
          |    r + 33
          |  }
          |}
          |""".stripMargin),
      InlineCase("a local def with parameters", Seq(all), all,
        """object InlineDef {
          |  def f: String = {
          |    def <caret>label(a: Int, b: String): String = a.toString + b
          |    label(5, "foo")
          |  }
          |}
          |""".stripMargin,
        """object InlineDef {
          |  def f: String = {
          |    5.toString + "foo"
          |  }
          |}
          |""".stripMargin),
      InlineCase("a private type alias", Seq(all), all,
        """object InlineAlias {
          |  private type <caret>My = Int
          |  private val first: My = 3
          |  private val second: My = 4
          |}
          |""".stripMargin,
        """object InlineAlias {
          |  private val first: Int = 3
          |  private val second: Int = 4
          |}
          |""".stripMargin),
    )
    val refused = Seq(
      "a public member" -> "object Public { val <caret>a = 1; def f: Int = a }",
      "a val that is never used" -> "object Unused { def f: Int = { val <caret>a = 1; 2 } }",
      "a var" -> "object Variable { def f: Int = { var <caret>a = 1; a + a } }",
      "a name of a destructuring" -> "object Pair { def f: Int = { val (<caret>a, b) = (1, 2); a + b } }",
      "a method used without a call" ->
        "object Reference { def f: Int => Int = { def <caret>inc(i: Int) = i + 1; inc } }",
      "a recursive method" ->
        "object Recursive { def f: Int = { def <caret>down(i: Int): Int = if (i == 0) 0 else down(i - 1); down(3) } }",
      "a private member used in its companion" ->
        "class Box(n: Int) { private val <caret>doubled = n * 2 }; object Box { def peek(b: Box): Int = b.doubled }",
      "a member of a local class used outside it" ->
        "object Local { def f: Int = { class Box(n: Int) { def <caret>doubled = n * 2 }; new Box(1).doubled } }",
    )
    def inlinings(marked: String): (ScalaFile, String, Seq[CodeActions.Choice]) = {
      val original = marked.replace("<caret>", "")
      val file = scalaFile("Inlined", original)
      val at = marked.indexOf("<caret>")

      (file, original, host.codeActions(file, new TextRange(at, at)).choices.filter(_.kind == "refactor.inline"))
    }

    for (testCase <- cases) {
      val (file, original, actions) = inlinings(testCase.marked)
      try {
        assertEquals(actions.map(_.title), testCase.offered, testCase.name)
        val chosen = actions.find(_.title == testCase.title).getOrElse(fail(actions.toString))
        assertEquals(chosen.disabled, None)
        assertEquals(applyEdits(original, chosen.edits), testCase.expected, testCase.name)
        assertEquals(host.read(file.getText), original)
      } finally host.closeSource(file)
    }
    for ((name, marked) <- refused) {
      val (file, _, actions) = inlinings(marked)
      try assertEquals(actions, Seq.empty, name)
      finally host.closeSource(file)
    }
  }

  // The oracle is the plugin's ImplementMembersQuickFixTest (`testProperDefaultValueForVariousMemberTypes`): the quick
  // fix of a class that must implement members, and the same members as an action for an abstract class, which has no
  // problem to fix.
  test("Implement members inserts every abstract member on a copy and keeps the source") {
    val cases = Seq(
      ("quick fix", "quickfix",
        "abstract class MyTrait {\n  var myVariable: Int\n  val myValue: Int\n  def myDef: Int\n  type MyType\n}\n\n" +
          "class<caret> MyClass1 extends MyTrait\n",
        "abstract class MyTrait {\n  var myVariable: Int\n  val myValue: Int\n  def myDef: Int\n  type MyType\n}\n\n" +
          "class MyClass1 extends MyTrait {\n  override var myVariable: Int = ???\n" +
          "  override val myValue: Int = ???\n\n" +
          "  override def myDef: Int = ???\n\n  override type MyType = this.type\n}\n"),
      ("action", "refactor.rewrite",
        "trait Shape {\n  def area: Double\n  def name: String\n}\nabstract class Square<caret> extends Shape\n",
        "trait Shape {\n  def area: Double\n  def name: String\n}\nabstract class Square extends Shape {\n" +
          "  override def area: Double = ???\n\n  override def name: String = ???\n}\n"),
    )
    TestHost.libraryReport
    for ((name, kind, marked, expected) <- cases) {
      val original = marked.replace("<caret>", "")
      val file = scalaFile("Implement", original)
      try {
        val at = marked.indexOf("<caret>")
        val range = new TextRange(at, at)
        val actions = host.codeActions(file, range)
        // Offered without a preview: the preview occupies the dispatch thread.
        assert(!actions.choices.exists(_.title == "Implement members"), s"$name: ${actions.choices}")
        val offered = actions.implementMembers.getOrElse(fail(s"$name: not offered"))
        assertEquals(offered.kind, kind, name)
        val chosen = host.previewImplementMembers(file, range, offered)
        assertEquals((chosen.title, chosen.kind, chosen.disabled), ("Implement members", kind, None), name)
        assertEquals(applyEdits(original, chosen.edits), expected, name)
        assertEquals(host.implementMembers(file, at), chosen.edits, name)
        assertEquals(host.read(file.getText), original, name)
      } finally host.closeSource(file)
    }
  }

  // The dispatch thread runs the platform's `invokeLater` tasks, some of which write: a thread that reads, edits or holds
  // write intent and waits for it can wait for a writer that waits for it. That the hop refuses such a caller is what keeps the previews
  // that need the event thread from deadlocking with those tasks; the race itself cannot be made deterministic.
  test("a query on the dispatch thread is refused inside a read, an edit or write intent") {
    assertEquals(HostLock.queryOnDispatchThread(HostDispatchThread.isCurrent), true)
    intercept[IllegalStateException](host.read(HostLock.queryOnDispatchThread(())))
    intercept[IllegalStateException](HostLock.write(HostLock.queryOnDispatchThread(())))
    intercept[IllegalStateException](HostLock.writeIntent(HostLock.queryOnDispatchThread(())))
  }

  // The request's cancellation is a thread-local of the thread that waits: the dispatch thread must observe it, or a
  // client could not cancel a preview there.
  test("a query on the dispatch thread is cancelled by the waiting request's cancellation") {
    val cancellation = new HostLock.RequestCancellation
    cancellation.cancel()
    var ran = false
    intercept[ProcessCanceledException] {
      HostLock.cancelledBy(cancellation)(HostLock.queryOnDispatchThread { ran = true })
    }
    assert(!ran)
  }

  // Without it the request's read thread stays blocked until the dispatch thread dequeues the task, however long the
  // work ahead of it takes.
  test("a request's cancellation releases its query that still waits in the dispatch thread's queue") {
    val cancellation = new HostLock.RequestCancellation
    val blocked = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val ran = new java.util.concurrent.atomic.AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor()

    HostDispatchThread.invokeLater { () =>
      blocked.countDown()
      release.await()
    }
    try {
      assert(blocked.await(30, TimeUnit.SECONDS), "the dispatch thread did not start the blocking task")
      val waiting = CompletableFuture.supplyAsync(
        () => scala.util.Try(HostLock.cancelledBy(cancellation)(HostLock.queryOnDispatchThread(ran.set(true)))),
        executor)
      // Let the query reach the queue behind the blocking task before cancelling.
      Thread.sleep(200)
      cancellation.cancel()
      // The dispatch thread is still blocked: the waiting thread was released by the cancellation alone.
      assert(waiting.get(30, TimeUnit.SECONDS).failed.get.isInstanceOf[ProcessCanceledException])
    } finally {
      release.countDown()
      executor.shutdown()
    }
    HostDispatchThread.drain()
    assert(!ran.get)
  }

  // The oracle is the plugin's ScalaOverrideImplementTest (`testOverrideFunction`).
  test("Override Members lists what a class can override and overrides the chosen members") {
    val marked = "package test\n\nclass A {\n  def foo(): A = null\n  def bar: Int = 1\n}\n" +
      "class FunctionOverride extends A {\n  <caret>\n}\n"
    val original = marked.replace("<caret>", "")
    TestHost.libraryReport
    val file = scalaFile("Override", original)
    try {
      val at = marked.indexOf("<caret>")
      val offered = host.overridableMembers(file, at)
      val foo = offered.find(_.signature == "foo(): A").getOrElse(fail(offered.toString))
      assertEquals(foo.owner, "test.A")
      assert(offered.exists(_.signature == "bar: Int"), offered.toString)

      val expected = "package test\n\nclass A {\n  def foo(): A = null\n  def bar: Int = 1\n}\n" +
        "class FunctionOverride extends A {\n  override def foo(): A = super.foo()\n}\n"
      assertEquals(applyEdits(original, host.overrideMembers(file, at, Seq(foo.id))), expected)
      assertEquals(host.read(file.getText), original)

      val stale = intercept[MemberOverrides.UnknownMembers] {
        host.overrideMembers(file, at, Seq(foo.id, "test.A#gone(): Unit"))
      }
      assertEquals(stale.ids, Seq("test.A#gone(): Unit"))
    } finally host.closeSource(file)
  }

  // The chooser shows both overloads as `f(x: Shown): Int`; each is overridden alone, and an identifier listed before
  // one of them disappeared is unknown rather than the other's.
  test("Override Members tells apart overloads that the chooser shows alike") {
    val parameterTypes = Seq("left", "right").map { pack =>
      scalaFile(s"${pack}Shown", s"package $pack\nclass Shown\n")
    }
    val base =
      "package test\n\nclass Overloaded {\n  def f(x: left.Shown): Int = 1\n  def f(x: right.Shown): Int = 2\n}\n"
    val marked = base + "class Overloads extends Overloaded {\n  <caret>\n}\n"
    val original = marked.replace("<caret>", "")
    // The overload in the chooser's order, and the package of the parameter type that the override imports.
    val cases = Seq(0 -> "left", 1 -> "right")
    TestHost.libraryReport
    val file = scalaFile("Overloads", original)
    try {
      val at = marked.indexOf("<caret>")
      val overloads = host.overridableMembers(file, at).filter(_.owner == "test.Overloaded")
      assertEquals(overloads.map(_.signature), Seq("f(x: Shown): Int", "f(x: Shown): Int"))
      assertEquals(overloads.map(_.id).distinct.size, 2, overloads.toString)

      for ((index, pack) <- cases) {
        val expected = s"package test\n\nimport $pack.Shown\n\n" + base.stripPrefix("package test\n\n") +
          "class Overloads extends Overloaded {\n  override def f(x: Shown): Int = super.f(x)\n}\n"
        assertEquals(applyEdits(original, host.overrideMembers(file, at, Seq(overloads(index).id))), expected, pack)
      }

      val first = "  def f(x: left.Shown): Int = 1\n"
      val firstAt = original.indexOf(first)
      host.edit(file, firstAt, firstAt + first.length, "")
      intercept[MemberOverrides.UnknownMembers] {
        host.overrideMembers(file, at - first.length, Seq(overloads.head.id))
      }
    } finally (file +: parameterTypes).foreach(host.closeSource)
  }

  test("type-import actions keep colliding candidates separate and preserve the source") {
    val targets = Seq("first", "second").map { pack =>
      scalaFile(s"${pack}ImportChoice", s"package $pack\nclass ImportChoice\n")
    }
    val original = "object ImportClient { val value: ImportChoice = ??? }\n"
    val file = scalaFile("ImportClient", original)
    try {
      val at = original.indexOf("ImportChoice")
      val actions = host.codeActions(file, new TextRange(at, at)).choices.filter(_.title.startsWith("Import '"))
      for (pack <- Seq("first", "second")) {
        val title = s"Import '$pack.ImportChoice'"
        val action = actions.find(_.title == title).getOrElse(fail(actions.toString))
        assertEquals(action.disabled, None)
        val changed = applyEdits(original, action.edits)
        assert(changed.contains(s"import $pack.ImportChoice"), changed)
        assertEquals(host.read(file.getText), original)
      }
      val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(host.project)
      def importValues = host.read {
        com.intellij.util.indexing.FileBasedIndex.getInstance.getValues(
          org.jetbrains.plugins.scala.autoImport.ImportOrderingIndexer.Id, "first", scope).size()
      }
      assertEquals(importValues, 0)
      val chosen = actions.find(_.title == "Import 'first.ImportChoice'").getOrElse(fail(actions.toString))
      val changed = applyEdits(original, chosen.edits)
      host.edit(file, 0, original.length, changed)
      assertEquals(importValues, 1)
      host.edit(file, 0, changed.length, original)
      assertEquals(importValues, 0)
    } finally {
      host.closeSource(file)
      targets.foreach(host.closeSource)
    }
  }

  test("organize imports removes unused imports on a copy and keeps used imports") {
    TestHost.libraryReport
    val original = "import scala.util.Try\nimport scala.collection.mutable.ArrayBuffer\n\n" +
      "object Organized { val buffer = ArrayBuffer.empty[Int] }\n"
    val file = scalaFile("Organized", original)
    try {
      val action = host.organizeImports(file)
      val changed = applyEdits(original, action.edits)
      assertEquals(changed, original.replace("import scala.util.Try\n", ""))
      assertEquals(host.read(file.getText), original)
      host.edit(file, 0, original.length, changed)
      assertEquals(host.organizeImports(file).edits, Seq.empty)
    } finally host.closeSource(file)
  }

  test("organize imports preserves a local import dependency") {
    val original = """object LocalChain {
      |  object R {
      |    object C {
      |      class D
      |    }
      |  }
      |
      |  import R._
      |  import C.D
      |  val x : D = new D
      |}""".stripMargin
    val file = scalaFile("LocalChain", original)
    try assertEquals(applyEdits(original, host.organizeImports(file).edits), original)
    finally host.closeSource(file)
  }

  test("IntelliJ formatting returns minimal edits with request indentation") {
    val text = "object FormatMe {\nval emoji=\"😀\"\nval n=1+2\n}\n"
    val file = host.openFile(Path.of("/editing/FormatMe.scala"), text, module) match {
      case f: ScalaFile => f
      case other => fail(s"not Scala: $other")
    }
    try {
      val result = ChangeEngine.format(file, None, 4, useTabs = false)
      assertEquals(result.text, "object FormatMe {\n    val emoji = \"😀\"\n    val n = 1 + 2\n}\n")
      assertEquals(applyEdits(text, result.edits), result.text)
      assertEquals(host.read(file.getText), text)
      assert(result.edits.size > 1, result.edits)
    } finally host.closeSource(file)
  }

  test("formatting a copy completes while another thread holds a read") {
    val text = "object Concurrent { val n=1+2 }\n"
    val file = scalaFile("Concurrent", text)
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val reader = Future {
      HostLock.readLocked {
        started.countDown()
        release.await()
      }
    }(using ExecutionContext.global)
    try {
      assert(started.await(30, TimeUnit.SECONDS), "the read did not start")
      val formatting = Future {
        ChangeEngine.format(file, None, 2, useTabs = false)
      }(using ExecutionContext.global)
      val result = Await.result(formatting, Duration(5, "s"))
      assertEquals(result.text, "object Concurrent {\n  val n = 1 + 2\n}\n")
      assertEquals(applyEdits(text, result.edits), result.text)
    } finally {
      release.countDown()
      Await.result(reader, Duration(30, "s"))
      host.closeSource(file)
    }
  }

  test("a newer edit cancels a copy modification and releases its read lock") {
    val file = host.openFile(Path.of("/editing/Cancelled.scala"), "object Cancelled\n", module) match {
      case f: ScalaFile => f
      case other => fail(s"not Scala: $other")
    }
    try {
      val started = new java.util.concurrent.CountDownLatch(1)
      val query = scala.concurrent.Future {
        ChangeEngine.run(file) { _ =>
          started.countDown()
          while (true) com.intellij.openapi.progress.ProgressManager.checkCanceled()
        }
      }(using scala.concurrent.ExecutionContext.global)
      assert(started.await(30, java.util.concurrent.TimeUnit.SECONDS))
      host.edit(file, 0, 0, "// newer\n")
      intercept[com.intellij.openapi.progress.ProcessCanceledException](
        scala.concurrent.Await.result(query, scala.concurrent.duration.Duration(30, "s")))
      assertEquals(host.read(file.getText), "// newer\nobject Cancelled\n")
    } finally host.closeSource(file)
  }

  test("addImportForClass changes only the import line through the copy engine") {
    val target = host.openFile(Path.of("/editing/ImportTarget.scala"), "package imported\nclass ImportTarget\n", module)
    val text = "object ImportUser { val x: ImportTarget = ??? }\n"
    val file = host.openFile(Path.of("/editing/ImportUser.scala"), text, module) match {
      case f: ScalaFile => f
      case other => fail(s"not Scala: $other")
    }
    try {
      val cls = host.read {
        com.intellij.psi.JavaPsiFacade.getInstance(host.project).findClass("imported.ImportTarget",
          com.intellij.psi.search.GlobalSearchScope.allScope(host.project))
      }
      assert(cls != null)
      val (result, _) = ChangeEngine.run(file)(_.addImportForClass(cls, null))
      assertEquals(result.edits.size, 1, result.edits)
      assertEquals(result.edits.head.range, TextRange.EMPTY_RANGE)
      assertEquals(result.text, "import imported.ImportTarget\n\n" + text)
      assertEquals(applyEdits(text, result.edits), result.text)
      assertEquals(host.read(file.getText), text)
    } finally {
      host.closeSource(file)
      host.closeSource(target)
    }
  }


  test("PSI gists follow committed buffers while virtual gists retain VFS contents") {
    val host = TestHost.host
    val errorsBefore = HostLog.count(HostLog.Level.Error)
    val unsupportedBefore = CallLog.snapshot.filter(_._1.startsWith("UNSUPPORTED "))
    val manager = com.intellij.util.gist.GistManager.getInstance()
    val serializer = com.intellij.util.io.EnumeratorStringDescriptor.INSTANCE
    val original = "object GistBuffer { val value = 1 }"
    val changed = "object GistBuffer { val value = 2 }"
    val file = host.openFile(Path.of("/editing/GistBuffer.scala"), original, module)
    val virtual = file.getViewProvider.getVirtualFile
    var computations = 0
    val psiGist = manager.newPsiFileGist[String]("host-test.psi-buffer", 1, serializer, source => {
      computations += 1
      source.getText
    })
    val vfsGist = manager.newVirtualFileGist[String]("host-test.vfs-buffer", 1, serializer,
      (_, source) => new String(source.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8))
    try {
      assertEquals(psiGist.getFileData(file), original)
      assertEquals(vfsGist.getFileData(host.project, virtual), original)
      val documents = com.intellij.psi.PsiDocumentManager.getInstance(host.project)
      val document = documents.getDocument(file)
      HostLock.write(document.setText(changed))
      assertEquals(psiGist.getFileData(file), original)
      HostLock.write(documents.commitDocument(document))
      assertEquals(psiGist.getFileData(file), changed)
      assertEquals(vfsGist.getFileData(host.project, virtual), original)
      val beforeFileInvalidation = computations
      manager.invalidateData(virtual)
      HostDispatchThread.drain()
      assertEquals(psiGist.getFileData(file), changed)
      assert(computations > beforeFileInvalidation)
      val beforeGlobalInvalidation = computations
      manager.invalidateData()
      HostDispatchThread.drain()
      assertEquals(psiGist.getFileData(file), changed)
      assert(computations > beforeGlobalInvalidation)
      assertEquals(HostLog.count(HostLog.Level.Error), errorsBefore)
      assertEquals(CallLog.snapshot.filter(_._1.startsWith("UNSUPPORTED ")), unsupportedBefore)
    } finally host.closeSource(file)
  }

  test("virtual gists distinguish cached nulls and contexts and invalidate by content, file and manager") {
    val host = TestHost.host
    val errorsBefore = HostLog.count(HostLog.Level.Error)
    val unsupportedBefore = CallLog.snapshot.filter(_._1.startsWith("UNSUPPORTED "))
    val manager = com.intellij.util.gist.GistManager.getInstance()
    val serializer = com.intellij.util.io.EnumeratorStringDescriptor.INSTANCE
    val first = new com.intellij.testFramework.LightVirtualFile("first.txt", "one")
    val second = new com.intellij.testFramework.LightVirtualFile("second.txt", "two")
    val gist = manager.newVirtualFileGist[String]("host-test.virtual-invalidation", 1, serializer,
      (project, file) => if (project == null) null else new String(file.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8))
    assertEquals(gist.getUpToDateOrNull(host.project, first), null)
    assertEquals(gist.getFileData(null, first), null)
    val cachedNull = gist.getUpToDateOrNull(null, first)
    assert(cachedNull != null)
    assertEquals(cachedNull.get(), null)
    assertEquals(gist.getUpToDateOrNull(host.project, first), null)
    assertEquals(gist.getFileData(host.project, first), "one")
    assertEquals(gist.getUpToDateOrNull(host.project, first).get(), "one")
    assertEquals(gist.getFileData(host.project, second), "two")
    HostLock.write(first.setContent(null, "changed", false))
    assertEquals(gist.getUpToDateOrNull(host.project, first), null)
    assertEquals(gist.getFileData(host.project, first), "changed")
    manager.invalidateData(first)
    HostDispatchThread.drain()
    assertEquals(gist.getUpToDateOrNull(host.project, first), null)
    assertEquals(gist.getUpToDateOrNull(host.project, second).get(), "two")
    assertEquals(gist.getFileData(host.project, first), "changed")
    manager.invalidateData()
    HostDispatchThread.drain()
    assertEquals(gist.getUpToDateOrNull(host.project, first), null)
    assertEquals(gist.getUpToDateOrNull(host.project, second), null)
    intercept[IllegalArgumentException] {
      manager.newPsiFileGist[String]("host-test.virtual-invalidation", 2, serializer, _.getText)
    }
    assertEquals(HostLog.count(HostLog.Level.Error), errorsBefore)
    assertEquals(CallLog.snapshot.filter(_._1.startsWith("UNSUPPORTED ")), unsupportedBefore)
  }

}
