package scalaanalyzer

import com.intellij.codeInsight.daemon.impl.{AnnotationHolderImpl, AnnotationSessionImpl}
import com.intellij.lang.annotation.{Annotation, Annotator, HighlightSeverity}
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Key, TextRange, UserDataHolderBase}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiErrorElement, PsiFile, PsiJavaFile, PsiManager, SyntaxTraverser}
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.project.{ProjectContext, ScalaFeatures}

import scalaanalyzer.language.{
  ChangeEngine, CodeActions, Completion, InlayHints, InspectionDescription, Inspections, MemberOverrides, ParameterInfo,
  Rename, SelectionRanges,
}
import scalaanalyzer.platform.{Descriptors, ExtensionBean, HostBootstrap, HostDispatchThread, HostLock, HostPsiDocumentManager, Host_ModuleRootManager, Host_OrderEnumerator, ImportIndex, LibraryFileSystem, SourceFile, SymbolIndex, WordIndex, dispatch, tableProxy}

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Startup settings of the process's one host, passed to [[ScalaPsiHost.start]].
 *
 * @param stateDir        where the host writes to disk: the platform's state (index ID enumeration under
 *                        `idea.system.path`) and library files extracted for an editor to open
 * @param scalaPluginJars the Scala plugin's jars; they must also be on the classpath
 * @param unitTestMode    answer `isUnitTestMode` as the plugin's test fixture does; only for comparing with the
 *                        plugin's own tests, since the plugin changes behaviour in that mode
 * @param compilerErrorsScala3 errors of Scala 3 files come from the build's compiler, as by default in the IDE: the
 *                             plugin then reports only checks that do not need types (see
 *                             [[ScalaPsiHost.isCompilerHighlighted]])
 * @param compilerErrorsScala2 the same for Scala 2 files (off by default, as in the IDE)
 */
final case class HostConfig(
  stateDir: Path,
  scalaPluginJars: Seq[Path],
  unitTestMode: Boolean = false,
  compilerErrorsScala3: Boolean = true,
  compilerErrorsScala2: Boolean = false,
)

/**
 * Scala PSI and type checker hosted without an IntelliJ application container. The host installs three fixed
 * tables — an `Application` object, a root extension area and a `Project` object — and then only consumes the
 * plugin's PSI. Whatever is not in a table fails loudly and is recorded in [[scalaanalyzer.platform.CallLog]].
 *
 * Methods that change the project take the host write lock themselves, which cancels the queries running meanwhile.
 * Query methods say whether they take read access themselves.
 *
 * @param stateDir        where the host writes to disk (see [[HostConfig.stateDir]])
 * @param project         the host's `Project` object, which the platform's PSI belongs to
 * @param symbolIndex     stub-index postings of project sources and library classes
 * @param wordIndex       word index of project sources
 * @param importIndex     import-ordering index of project sources
 * @param libraryFiles    file system that holds the mounted library jars, source archives and JDK modules
 * @param librarySources  maps a library class root to its attached source root
 * @param modules         module metadata that the project, file-index, module and scope services answer from
 * @param psiChanged      invalidates the PSI caches after the host changes PSI or the mounted libraries
 * @param actions         code actions built from the plugin's intention and surround descriptors
 * @param inspections     the host's selected native inspections, built from the plugin's local inspection descriptors
 */
final class ScalaPsiHost private[scalaanalyzer] (
  val stateDir: Path,
  val project: Project,
  symbolIndex: SymbolIndex,
  wordIndex: WordIndex,
  importIndex: ImportIndex,
  libraryFiles: LibraryFileSystem,
  librarySources: ConcurrentHashMap[VirtualFile, VirtualFile],
  val modules: ModuleModel,
  psiChanged: () => Unit,
  private val actions: CodeActions,
  private val inspections: Inspections,
) {
  /** Library jars by real path: a jar on the classpath of several modules is mounted and indexed once. */
  private val libraryRoots = new ConcurrentHashMap[Path, VirtualFile]()

  /** The mounted JDK's home, written while the write lock is held. */
  @volatile private var jdkHome: Option[Path] = None

  /** A file's problems until PSI changes: code actions reuse those the diagnostics computed for the same buffer. */
  private val diagnosticsCache = Key.create[CachedValue[Seq[Diagnostic]]]("scala-analyzer.diagnostics")

  /**
   * Runs `query` as the IDE's read actions run: concurrently with other queries, never during an edit. An edit that
   * starts meanwhile cancels it: it throws `ProcessCanceledException`. For callers that read PSI directly; the host's
   * own queries ([[diagnostics]]) do this themselves. Nested reads are part of the outer one.
   *
   * @param query the PSI reads to run under read access
   * @return what `query` returns; an edit that starts meanwhile makes the call throw instead
   */
  def read[T](query: => T): T = HostLock.query(query)

  /**
   * The completion suggestions at the UTF-16 text `offset`; nothing is inserted until one is chosen.
   *
   * Takes read access itself. At most [[Completion.MaxSuggestions]] suggestions are returned, and the result says
   * whether more were left out.
   *
   * @param file Scala or Java source to complete in
   * @param offset caret offset in UTF-16 code units
   * @return the suggestions in relevance order
   */
  def completion(file: PsiFile, offset: Int): Completion.Suggestions = Completion.complete(file, offset)

  /**
   * The inline hints collected by the plugin's hints pass.
   *
   * Takes read access itself.
   *
   * @param file Scala source to collect hints for
   * @return the hints in document order
   */
  def inlayHints(file: ScalaFile): Seq[InlayHints.InlayHint] = InlayHints.in(file)

  /**
   * Selects the kinds of hints [[inlayHints]] produces, for every file of the host.
   *
   * Takes the write lock itself: the queries running meanwhile are cancelled.
   *
   * @param toggles the kinds of hints to produce
   */
  def configureInlayHints(toggles: InlayHints.Toggles): Unit = InlayHints.configure(toggles)

  /**
   * The signatures of the call around `offset`, as the IDE's parameter info shows them.
   *
   * Takes read access itself.
   *
   * @param file Scala source being queried
   * @param offset caret offset in UTF-16 code units
   * @return the rendered signatures, or none when no parameter-info handler finds a call
   */
  def parameterInfo(file: ScalaFile, offset: Int): Option[ParameterInfo.Signatures] = ParameterInfo.at(file, offset)

  /**
   * Successive Extend Selection ranges, smallest first.
   *
   * Takes read access itself.
   *
   * @param file Scala source being queried
   * @param offset caret offset in UTF-16 code units
   * @return the strict expansions from innermost to outermost, excluding the initial empty range
   */
  def selectionRanges(file: ScalaFile, offset: Int): Seq[TextRange] = SelectionRanges.at(file, offset)

  /**
   * What the insert handler of a chosen suggestion changes (see [[Completion.insert]]).
   *
   * Takes read access itself and leaves `file` unchanged. `file` must already contain the lookup text in place of
   * the typed prefix.
   *
   * @param file Scala or Java file that contains the inserted lookup text
   * @param start UTF-16 start offset of that text
   * @param prefixText the prefix the suggestion was obtained for
   * @param lookup the inserted lookup string
   * @param identity the chosen suggestion's identity, as [[Completion.Suggestion]] carries it
   * @return the follow-up edits and caret, or none when the selection can no longer be replayed
   */
  def insertCompletion(file: PsiFile, start: Int, prefixText: String, lookup: String, identity: String)
      : Option[Completion.Inserted] =
    Completion.insert(file, start, prefixText, lookup, identity)

  /**
   * The members Override Members offers for the class at `offset` (see [[MemberOverrides.overridable]]).
   *
   * Takes read access itself and leaves `file` unchanged.
   *
   * @param file Scala source being queried
   * @param offset UTF-16 caret offset
   * @return the members in the chooser's order; empty when the caret is not in a class, object or trait
   */
  def overridableMembers(file: ScalaFile, offset: Int): Seq[MemberOverrides.Member] =
    read(MemberOverrides.overridable(file, offset))

  /**
   * The edits that override the chosen members in the class at `offset` (see [[MemberOverrides.overrideMembers]]).
   *
   * Takes read access itself and leaves `file` unchanged.
   *
   * @param file Scala source to edit
   * @param offset UTF-16 caret offset, where the members are inserted
   * @param ids identifiers of the members to override, as `overridableMembers` gave them
   * @return the edits against the current text of `file`
   * @throws MemberOverrides.UnknownMembers when an identifier is not that of a member the class offers
   */
  def overrideMembers(file: ScalaFile, offset: Int, ids: Seq[String]): Seq[ChangeEngine.Edit] =
    MemberOverrides.overrideMembers(file, offset, ids)

  /**
   * The edits that format `range` of `file` (all of it without one); the file itself is left as it is.
   *
   * Takes read access itself.
   *
   * @param file Scala source to format
   * @param range UTF-16 range to format, or none for the whole file
   * @param indentSize positive indentation width in spaces
   * @param useTabs whether indentation may use tab characters
   * @return the edits against the current text of `file`; empty when formatting changes nothing
   * @throws IllegalArgumentException when `indentSize` is not positive
   */
  def formatting(file: ScalaFile, range: Option[TextRange], indentSize: Int, useTabs: Boolean): Seq[ChangeEngine.Edit] =
    ChangeEngine.format(file, range, indentSize, useTabs).edits

  /**
   * A file outside the project: parsed, but invisible to symbol lookups (as the IDE's non-physical files).
   *
   * Takes read access itself. The file is not indexed and belongs to no module.
   *
   * @param text complete Scala source text
   * @param features language features to parse the text with
   * @return the parsed file
   */
  def parse(text: String, features: ScalaFeatures = ScalaFeatures.default): ScalaFile = HostLock.readLocked {
    ScalaPsiElementFactory.createScalaFileFromText(text, features)(using new ProjectContext(project))
  }

  /**
   * A project source file: its declarations are indexed, so other files and the file itself find them by name,
   * as for a file under an IDE source root. Fails when its stubs cannot be paired with its PSI.
   *
   * Takes the write lock itself.
   *
   * @param text complete Scala source text
   * @param features language features to parse the text with
   * @return the parsed and indexed file
   * @throws IllegalStateException when the file's stubs cannot be paired with its PSI
   */
  def openSource(text: String, features: ScalaFeatures = ScalaFeatures.default): ScalaFile =
    HostLock.write(index(parse(text, features)))

  /** Indexes a source's stubs, words and imports and notifies PSI listeners; a file with unpairable stubs is rejected. */
  private def index[F <: PsiFile](file: F): F = {
    if (!symbolIndex.indexSourceFile(file)) {
      modules.removeSource(file.getViewProvider.getVirtualFile)
      throw new IllegalStateException("source file stubs could not be paired with its PSI")
    }

    wordIndex.indexFile(file)
    importIndex.indexFile(file)

    psiChanged()
    file
  }

  /**
   * Opens a project source file of `module` (Scala or Java, by extension) with the given text: a physical file whose
   * PSI follows edits made with [[edit]], as a file open in an IDE editor.
   *
   * Takes the write lock itself.
   *
   * @param path location that names the file and picks its file type by extension
   * @param text complete source text
   * @param module host module that owns the file
   * @return the file's PSI, indexed and ready for [[edit]]
   * @throws IllegalStateException when the file's stubs cannot be paired with its PSI
   */
  def openFile(path: Path, text: String, module: HostModule): PsiFile = HostLock.write {
    val fileType = FileTypeRegistry.getInstance.getFileTypeByFileName(path.getFileName.toString)
    val source = new SourceFile(path, fileType, text)

    modules.addSource(source, module)
    val file = index(PsiManager.getInstance(project).findFile(source))
    // The document manager learns of document changes as a document listener (in the IDE, through the editor's
    // global event multicaster), so that a commit reparses what changed.
    val documents = com.intellij.psi.PsiDocumentManager.getInstance(project)
    documents match {
      case manager: HostPsiDocumentManager => manager.listenTo(documents.getDocument(file))
      case other => throw new IllegalStateException(s"unexpected document manager: ${other.getClass.getName}")
    }

    file
  }

  /**
   * Replaces `file`'s text between `start` and `end` (offsets) with `replacement`, as an editor change: the document
   * changes and is committed, the platform reparses the changed range, and the resulting tree changes reach the
   * plugin's caches. The file's index entries are then rebuilt. Queries running meanwhile are cancelled.
   *
   * Takes the write lock itself, then lets the commit's work for the dispatch thread run before returning.
   *
   * @param file a file opened with [[openFile]]
   * @param start UTF-16 offset where the replaced range starts
   * @param end UTF-16 offset where the replaced range ends, exclusive
   * @param replacement the text that takes the place of the range
   * @throws IllegalArgumentException when `file` has no document
   * @throws IllegalStateException when the edited file's stubs cannot be paired with its PSI
   */
  def edit(file: PsiFile, start: Int, end: Int, replacement: String): Unit = {
    HostLock.write(replaceText(file, start, end, replacement))
    // Work the commit scheduled for the UI thread (after-commit actions) runs before the edit returns.
    HostDispatchThread.drain()
  }

  /** Replaces and commits document text, then rebuilds the source indexes while the write lock is held. */
  private def replaceText(file: PsiFile, start: Int, end: Int, replacement: String): Unit = {
    val documents = com.intellij.psi.PsiDocumentManager.getInstance(project)
    val document = documents.getDocument(file)
    if (document == null) throw new IllegalArgumentException(s"$file has no document: only files opened with openFile can be edited")

    document.replaceString(start, end, replacement)
    documents.commitDocument(document)

    symbolIndex.removeFile(file)
    if (!symbolIndex.indexSourceFile(file)) throw new IllegalStateException(s"stubs of the edited $file could not be paired with its PSI")
    wordIndex.removeFile(file)
    importIndex.removeFile(file)
    wordIndex.indexFile(file)
    importIndex.indexFile(file)

    ScalaPsiHost.notifyPsiChanged(project)
  }

  /**
   * The edits that rename the symbol at `offset` of `file` to `newName`, by file (see [[Rename]]). The plugin's rename
   * runs on the project's files, so the files change while it does; their texts are restored before this returns, as
   * the editor applies the edits and reports them back. Queries running meanwhile are cancelled.
   *
   * Takes the write-intent and write locks itself, then lets the work for the dispatch thread run before returning.
   *
   * @param file source file that contains the symbol
   * @param offset UTF-16 offset of the symbol in `file`
   * @param newName the symbol's new name
   * @return the edits of each changed file, against its text before the rename and in the order of the plan's files;
   *         files whose text does not change are left out
   * @throws Rename.Refused when the position has no renameable symbol, the name is invalid or the rename conflicts
   */
  def rename(file: PsiFile, offset: Int, newName: String): Seq[(PsiFile, Seq[ChangeEngine.Edit])] = {
    /** Puts each file's text back and commits it; non-cancelable so that a cancelled query cannot leave a renamed file. */
    def restore(originals: Seq[(PsiFile, String)]): Unit =
      com.intellij.openapi.progress.ProgressManager.getInstance.executeNonCancelableSection((() => {
        val documents = com.intellij.psi.PsiDocumentManager.getInstance(project)
        originals.foreach { (f, text) =>
          val document = documents.getDocument(f)
          documents.doPostponedOperationsAndUnblockDocument(document)
          replaceText(f, 0, document.getTextLength, text)
        }
      }): Runnable)

    /** Performs the plan, restoring the files' texts even when it fails; returns the texts before and after. */
    def performAndRestore(plan: Rename.Plan): (Seq[(PsiFile, String)], Seq[String]) = {
      val originals = plan.files.map(f => f -> f.getText)

      try {
        // The platform changes physical PSI inside a command or an undo-transparent action; the host has no undo.
        com.intellij.openapi.command.CommandProcessor.getInstance.runUndoTransparentAction(() => Rename.perform(plan))
        (originals, plan.files.map(_.getText))
      } finally restore(originals)
    }

    val (originals, changed) = try HostLock.writeIntent {
      // Keep the read plan valid until its write: another editor change must not enter between the two.
      val target = renameTarget(file, offset)
      val plan = read(Rename.plan(target, newName))

      HostLock.write(performAndRestore(plan))
    } finally HostDispatchThread.drain()

    originals.zip(changed)
      .map { case ((f, before), after) => f -> ChangeEngine.diff(before, after) }
      .filter(_._2.nonEmpty)
  }

  /**
   * The element the IDE renames for the symbol at `offset` (see [[Rename.target]]). The Java processors ask for the
   * event thread when they look for a method's base methods, which they do for a symbol of a Java source.
   *
   * Takes read access itself and runs on the host's dispatch thread.
   *
   * @param file source file that contains the symbol
   * @param offset UTF-16 offset of the symbol in `file`
   * @return the element the IDE's rename processors substitute for the symbol
   * @throws Rename.Refused when the position does not identify a renameable project symbol
   */
  def renameTarget(file: PsiFile, offset: Int): PsiElement = HostDispatchThread.call(read(Rename.target(file, offset)))

  /**
   * Removes a source file opened with [[openSource]] or [[openFile]] from the project.
   *
   * Takes the write lock itself. The file's index entries go, and a file opened again at the same path is a new file.
   *
   * @param file the file to remove
   */
  def closeSource(file: PsiFile): Unit = HostLock.write {
    val virtualFile = file.getViewProvider.getVirtualFile

    symbolIndex.removeFile(file)
    wordIndex.removeFile(file)
    importIndex.removeFile(file)
    modules.removeSource(virtualFile)
    // The file store lets go of its view provider (and PSI): a file opened again at the same path is a new file.
    PsiManagerEx.getInstanceEx(project).getFileManager.setViewProvider(virtualFile, null)

    psiChanged()
  }

  /**
   * Removes the module identified by its stable ID after its source files are closed. A module that depends on it is
   * removed too, or added again with it. Its libraries stay mounted, for other modules and for the module added again.
   *
   * Takes the write lock itself.
   *
   * @param name the module's stable ID
   */
  def removeModule(name: String): Unit = HostLock.write {
    modules.remove(name)
    psiChanged()
  }

  /**
   * Mounts a library jar and indexes its Scala class files accepted by `includeEntry` (e.g. `scala/Option.class`).
   *
   * Takes the write lock itself. The jar becomes available to [[addLibrarySources]] by its real path.
   *
   * @param jar library jar to mount
   * @param includeEntry predicate over root-relative paths such as `scala/Option.class`; it decides which class and
   *                     TASTy files are indexed
   * @return the traversal outcomes of the jar's indexing (see [[SymbolIndex.indexLibrary]])
   */
  def addLibrary(jar: Path, includeEntry: String => Boolean = _ => true): LibraryIndexReport = HostLock.write {
    val root = libraryFiles.mountJar(jar)

    libraryRoots.put(jar.toRealPath(), root)
    val report = symbolIndex.indexLibrary(project, root, includeEntry)

    psiChanged()
    report
  }

  /**
   * Attaches the sources of a mounted library jar (a source jar), as the IDE attaches library sources: navigation
   * from the jar's classes leads to them. Source files are parsed when navigated to, never indexed.
   *
   * Takes the write lock itself.
   *
   * @param jar library jar already mounted with [[addLibrary]] or [[addModule]]
   * @param sources the library's source jar
   * @throws IllegalArgumentException when `jar` is not mounted
   */
  def addLibrarySources(jar: Path, sources: Path): Unit = HostLock.write {
    val root = Option(libraryRoots.get(jar.toRealPath())).getOrElse(throw new IllegalArgumentException(s"library jar is not mounted: $jar"))

    librarySources.put(root, libraryFiles.mountSources(sources))

    psiChanged()
  }

  /**
   * Attaches the JDK's sources (its `lib/src.zip`, one directory per module) to the mounted JDK's modules.
   *
   * Takes the write lock itself. A module the archive has no sources for is left without them.
   *
   * @param srcZip the JDK's source archive
   */
  def addJdkSources(srcZip: Path): Unit = HostLock.write {
    for (moduleRoot <- modules.jdkModuleRoots) {
      val module = moduleRoot.getName.substring(moduleRoot.getName.lastIndexOf("!/") + 2)
      val sources = libraryFiles.mountSources(srcZip, module)

      // A JDK image may ship sources for some of its modules only.
      if (sources.getChildren.nonEmpty) librarySources.put(moduleRoot, sources)
    }

    psiChanged()
  }

  /**
   * Mounts the JDK's modules. Java classes are read on demand, not indexed. Every module sees the JDK. Mounting the
   * JDK that is already mounted does nothing.
   *
   * Takes the write lock itself.
   *
   * @param javaHome home directory of the JDK, compared by its real path
   * @throws IllegalStateException when a different JDK is already mounted
   */
  def addJdk(javaHome: Path): Unit = HostLock.write {
    if (!jdkHome.contains(javaHome.toRealPath())) {
      if (jdkHome.nonEmpty) throw new IllegalStateException(s"a JDK is already mounted (${jdkHome.get}); the host has one JDK")

      modules.setJdkRoots(libraryFiles.mountJdk(javaHome))
      jdkHome = Some(javaHome.toRealPath())

      psiChanged()
    }
  }

  /**
   * Adds a module as an IDE module after a build import: the jars of its classpath are mounted and indexed (each
   * jar once, however many modules use it), its Scala version becomes a Scala SDK library and its compiler options
   * a compiler-settings profile, from which the plugin derives the module's language features.
   * Classpath entries must be jars; the class directories of other modules are given by `dependsOn`.
   *
   * Takes the write lock itself.
   *
   * @param spec the module's identity, Scala version, compiler options, classpath and dependencies
   * @return the registered host module
   * @throws IllegalArgumentException when a classpath entry is not a jar
   */
  def addModule(spec: ModuleSpec): HostModule = HostLock.write {
    val notJars = spec.classpath.filterNot(_.getFileName.toString.endsWith(".jar"))
    if (notJars.nonEmpty) throw new IllegalArgumentException(s"module ${spec.name}: classpath entries are not jars: ${notJars.mkString(", ")}")

    val roots = spec.classpath.map { jar =>
      Option(libraryRoots.get(jar.toRealPath())).getOrElse { addLibrary(jar); libraryRoots.get(jar.toRealPath()) }
    }
    val hostModule = new HostModule(spec, roots.toSet)
    hostModule.module = newModule(hostModule)

    modules.add(hostModule)
    org.jetbrains.plugins.scala.project.settings.ScalaCompilerConfiguration.instanceIn(project).configureSettingsForModule(
      spec.stableId, "bsp",
      org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettings.fromOptions(
        spec.scalacOptions, org.jetbrains.plugins.scala.compiler.data.CompileOrder.Mixed))
    org.jetbrains.plugins.scala.project.settings.ScalaCompilerConfiguration.incModificationCount()

    psiChanged()
    hostModule
  }

  /** The IDE `Module` of a host module; its only order entry is the Scala SDK, and scopes come from [[ModuleModel]]. */
  private def newModule(hostModule: HostModule): Module = {
    val data = new UserDataHolderBase
    val sdkProperties = org.jetbrains.plugins.scala.project.ScalaLibraryProperties(
      Some(hostModule.spec.scalaVersion), Seq.empty, Seq.empty, None, org.jetbrains.plugins.scala.project.ReplClasspath.Bundled)

    // The SDK library carries the module's Scala version; the classpath jars are not presented as libraries.
    lazy val scalaSdk: com.intellij.openapi.roots.impl.libraries.LibraryEx =
      tableProxy(classOf[com.intellij.openapi.roots.impl.libraries.LibraryEx], "ScalaSdkLibrary") {
        // ScalaModuleSettings reads the compiler version from the library name, as for an imported "scala-sdk-x.y.z".
        case ("getName", _) => s"scala-sdk-${hostModule.spec.scalaVersion}"
        case ("getKind", _) => org.jetbrains.plugins.scala.project.ScalaLibraryType.Kind
        case ("getProperties", _) => sdkProperties
        case ("isDisposed", _) => java.lang.Boolean.FALSE
      }

    lazy val orderEntries: com.intellij.openapi.roots.OrderEnumerator = new Host_OrderEnumerator(dispatch {
      // Filters that keep libraries leave the one entry in place.
      case ("OrderEnumerator.withoutSdk" | "OrderEnumerator.withoutDepModules" | "OrderEnumerator.withoutModuleSourceEntries"
            | "OrderEnumerator.recursively" | "OrderEnumerator.exportedOnly" | "OrderEnumerator.compileOnly"
            | "OrderEnumerator.productionOnly", _) => orderEntries
      case ("OrderEnumerator.forEachLibrary", Array(p: com.intellij.util.Processor[?])) =>
        // The processor is typed `?` by the pattern; `forEachLibrary` hands it libraries, which is all this one gets.
        p.asInstanceOf[com.intellij.util.Processor[com.intellij.openapi.roots.libraries.Library]].process(scalaSdk); null
    })

    lazy val rootManager: com.intellij.openapi.roots.ModuleRootManager = new Host_ModuleRootManager(dispatch {
      case ("ModuleRootManager.orderEntries", _) => orderEntries
      case ("ModuleRootManager.getModule", _) => module
      // JDK roots belong to ModuleModel; no IntelliJ SDK entity is assigned to this module.
      case ("ModuleRootManager.getSdk", _) => null
      // Host modules declare source membership, but no content entries or inherited package prefixes.
      case ("ModuleRootManager.getContentEntries", _) => Array.empty[com.intellij.openapi.roots.ContentEntry]
      // The modules this one depends on; the plugin looks among them for sbt's shared-source modules, which the host has none of.
      case ("ModuleRootManager.getDependencies", _) => hostModule.spec.dependsOn.flatMap(modules.byId).map(_.module).toArray
    })

    lazy val module: Module = tableProxy(classOf[Module], "Module") {
      case ("getModuleWithDependenciesAndLibrariesScope", _) => modules.resolveScope(hostModule)
      case ("getModuleScope", _) => new com.intellij.psi.search.GlobalSearchScope(project) {
        override def contains(file: VirtualFile): Boolean = hostModule.sources.contains(file)
        override def isSearchInModuleContent(candidate: Module): Boolean = candidate eq module
        override def isSearchInLibraries: Boolean = false
      }
      case ("getName", _) => hostModule.spec.stableId
      case ("getProject", _) => project
      case ("isDisposed", _) => java.lang.Boolean.FALSE
      case ("getModuleTypeName", _) => "JAVA_MODULE"
      case ("getService", Array(c: Class[?])) if c == classOf[com.intellij.openapi.roots.ModuleRootManager] => rootManager
      // The stored value is a reference of unknown type `?`.
      case ("getUserData", Array(k: Key[?])) => data.getUserData(k).asInstanceOf[AnyRef]
      case ("putUserData", Array(k: Key[?], v)) =>
        // The proxy passes the value untyped; the holder stores it without checking the key's type parameter.
        data.putUserData(k.asInstanceOf[Key[AnyRef]], v)
        null
    }

    module
  }

  /**
   * Whether `file`'s errors come from the build's compiler (see [[HostConfig.compilerErrorsScala3]]). For such a file
   * [[diagnostics]] has only the plugin's checks that do not need types, and the caller adds the compiler's problems.
   *
   * Takes read access itself.
   *
   * @param file Scala source to ask about
   * @return `true` when the plugin's highlighting mode takes the file's errors from the compiler
   */
  def isCompilerHighlighted(file: ScalaFile): Boolean = HostLock.query {
    org.jetbrains.plugins.scala.settings.ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(file)
  }

  /**
   * The plugin's organize-imports action, with edits against a copy of `file`.
   *
   * Runs under read access.
   *
   * @param file Scala source whose imports are organized
   * @return the action's choice; `file` itself is not changed
   */
  def organizeImports(file: ScalaFile): CodeActions.Choice = read(actions.organizeImports(file))

  /**
   * Text actions for the selected range, preserving their annotator fixes and previewing on file copies. Until the
   * file's diagnostics are computed, only what the range touches is analyzed and inspection fixes are left out: the
   * first request on an opened file does not wait for the whole file's analysis, and editors ask again when the
   * diagnostics arrive. A caller that already shows inspection problems in the range asks for their fixes with
   * `withInspections`, since the editor will not ask again.
   *
   * Takes read access itself, except for Implement members: its preview runs on the dispatch thread, which cannot be
   * awaited under a read, so it is the last action in the list and follows the others after their read ended. The
   * caller must not be reading or editing.
   *
   * @param file Scala source being queried
   * @param range UTF-16 range the actions apply to
   * @param withInspections whether to run the inspections for their fixes when the file's diagnostics are not cached
   * @return the actions available for the range; those that fix or suppress an inspection problem say which
   */
  def codeActions(file: ScalaFile, range: TextRange, withInspections: Boolean = false): Seq[CodeActions.Choice] = {
    val (choices, implementMembers) = read {
      val computed = Option(file.getUserData(diagnosticsCache)).filter(_.hasUpToDateValue).map(_.getValue)
      def uncached = problemsAround(file, range) ++ (if (withInspections) inspections.diagnostics(file) else Seq.empty)
      val problems = computed.getOrElse(uncached)

      (actions.collect(file, range, problems), actions.implementMembersOffer(file, range, problems))
    }

    choices ++ implementMembers.map(actions.implementMembers(file, range, _))
  }

  /**
   * Collects parser errors accepted by the platform's highlighting filters.
   *
   * Takes read access itself. A file without parser errors gives an empty result.
   *
   * @param file source PSI, including an incomplete Scala or Java buffer
   * @return syntax diagnostics against the current document text
   */
  def syntaxErrors(file: PsiFile): Seq[Diagnostic] = HostLock.query {
    val filters = com.intellij.codeInsight.highlighting.HighlightErrorFilter.EP_NAME.getExtensions(project).asScala

    SyntaxTraverser.psiTraverser(file).filter(classOf[PsiErrorElement]).asScala.toSeq
      .filter(error => filters.forall(_.shouldHighlightErrorElement(error)))
      .map(error => Diagnostic(HighlightSeverity.ERROR, error.getTextRange, error.getErrorDescription))
  }

  /**
   * Collects Java compilation errors from the current PSI, including unresolved names and type errors.
   *
   * Uses the platform's error collector and module resolve scope; no compiler output is generated. Analysis uses
   * the host's Java language level and classpath, without javac options or annotation processors.
   *
   * Takes read access itself and checks for cancellation between elements.
   *
   * @param file Java source to analyze
   * @return errors with UTF-16 ranges in the current buffer
   */
  def diagnostics(file: PsiJavaFile): Seq[Diagnostic] = HostLock.query {
    val errors = Vector.newBuilder[Diagnostic]
    val collector = new com.intellij.java.codeserver.highlighting.JavaErrorCollector(file, error =>
      errors += Diagnostic(HighlightSeverity.ERROR, error.range(), error.description()))

    SyntaxTraverser.psiTraverser(file).forEach { element =>
      com.intellij.openapi.progress.ProgressManager.checkCanceled()
      collector.processElement(element)
    }

    errors.result()
  }

  /**
   * Collects syntax errors, annotator problems and the host's selected native inspections.
   *
   * Presentation-only annotations are omitted. Inspections require a physical source buffer and preserve native
   * suppression comments; unused-symbol analysis is restricted to unannotated declarations local to the file.
   * The problems of a physical file are cached until PSI changes.
   *
   * @param file Scala source to analyze under cancellable read access
   * @return current problems and available plugin fixes with UTF-16 source ranges
   */
  def diagnostics(file: ScalaFile): Seq[Diagnostic] = HostLock.query {
    if (!file.isPhysical) collectDiagnostics(file)
    else CachedValuesManager.getManager(project).getCachedValue(file, diagnosticsCache, () =>
      CachedValueProvider.Result.create(collectDiagnostics(file), PsiModificationTracker.MODIFICATION_COUNT), false)
  }

  /**
   * Collects only the host's selected native inspections, as the IDE's Inspect Code runs them.
   *
   * Syntax errors and annotator problems are left out, and nothing is cached: a project-wide run inspects each file
   * once. The rules of [[diagnostics]] for inspections apply.
   *
   * @param file Scala source to inspect under cancellable read access
   * @return the inspection problems and their plugin fixes with UTF-16 source ranges
   */
  def inspect(file: ScalaFile): Seq[Diagnostic] = HostLock.query(inspections.diagnostics(file))

  /**
   * Describes every inspection that [[inspect]] may report, as the IDE names and groups it.
   *
   * @return one description for each inspection id that a diagnostic's `inspectionId` can carry
   */
  def inspectionDescriptions: Seq[InspectionDescription] = inspections.descriptions

  /** Runs the syntax check, the annotator and the inspections over the whole file. */
  private def collectDiagnostics(file: ScalaFile): Seq[Diagnostic] =
    syntaxErrors(file) ++ annotatorProblems(file, SyntaxTraverser.psiTraverser(file)) ++ inspections.diagnostics(file)

  /** The syntax and annotator problems of the elements `range` touches, without descending into the others. */
  private def problemsAround(file: ScalaFile, range: TextRange): Seq[Diagnostic] = {
    def touches(element: PsiElement) = element.getTextRange.intersects(range)

    val touched = SyntaxTraverser.psiTraverser(file).expand(touches).filter(touches)

    syntaxErrors(file).filter(_.range.intersects(range)) ++ annotatorProblems(file, touched)
  }

  /** Runs `annotator` over `elements` in a fresh annotation session and returns what it reported, in order. */
  private def annotate(file: ScalaFile, annotator: Annotator, elements: java.lang.Iterable[PsiElement]): Seq[Annotation] =
    AnnotationSessionImpl.computeWithSession(file, /* batchMode = */ false, annotator, { holder =>
      // The session's holder is declared as `AnnotationHolder`; it is the `AnnotationHolderImpl` the session creates.
      val impl = holder.asInstanceOf[AnnotationHolderImpl]
      elements.forEach(e => impl.runAnnotatorWithContext(e))
      impl.asScala.toSeq
    })

  /** Runs the plugin's annotator on `elements`, keeping the annotations that carry a message. */
  private def annotatorProblems(file: ScalaFile, elements: SyntaxTraverser[PsiElement]): Seq[Diagnostic] = {
    val annotations = annotate(file, new org.jetbrains.plugins.scala.annotator.ScalaAnnotator, elements)

    annotations.collect {
      case a if a.getMessage != null =>
        Diagnostic(a.getSeverity, new TextRange(a.getStartOffset, a.getEndOffset), a.getMessage,
          Option(a.getQuickFixes).toSeq.flatMap(_.asScala).map(f => f.textRange -> f.quickFix))
    }
  }

  /**
   * The colors the IDE gives `file`'s identifiers and soft keywords beyond its lexer's: the plugin's syntax
   * highlighting visitor (declarations, parameters, named arguments, annotations) and its color-scheme annotator
   * (references, by what they resolve to), in that order, as the IDE's highlighting pass runs them.
   *
   * Takes read access itself.
   *
   * @param file Scala source to color
   * @return UTF-16 ranges with their text attribute keys: the visitor's first, then the annotator's, each in the
   *         order its pass produced them
   */
  def textAttributes(file: ScalaFile): Seq[(TextRange, com.intellij.openapi.editor.colors.TextAttributesKey)] = HostLock.query {
    val elements = SyntaxTraverser.psiTraverser(file).asScala.toSeq
    val holder = new com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder(file)
    val visitor = new org.jetbrains.plugins.scala.highlighter.ScalaSyntaxHighlightingVisitor

    visitor.analyze(file, /* updateWholeFile = */ true, holder, () => elements.foreach(visitor.visit))
    val fromVisitor = (0 until holder.size).map(holder.get).flatMap { info =>
      Option(info.forcedTextAttributesKey).orElse(Option(info.`type`.getAttributesKey))
        .map(new TextRange(info.getStartOffset, info.getEndOffset) -> _)
    }

    val annotations = annotate(file, new org.jetbrains.plugins.scala.highlighter.ScalaColorSchemeAnnotator, elements.asJava)

    fromVisitor ++ annotations.flatMap { a =>
      Option(a.getTextAttributes).map(new TextRange(a.getStartOffset, a.getEndOffset) -> _)
    }
  }
}

/**
 * A PSI diagnostic with the plugin's quick fixes and optional inspection metadata.
 *
 * @param severity highlighting level selected by the producer
 * @param range UTF-16 range in the current source buffer
 * @param message user-facing problem description
 * @param fixes source ranges paired with native plugin actions
 * @param inspectionId stable inspection code, when available
 * @param deprecated whether the highlighted symbol is deprecated
 * @param unnecessary whether the highlighted code is unnecessary
 */
final case class Diagnostic(
  severity: HighlightSeverity,
  range: TextRange,
  message: String,
  fixes: Seq[(TextRange, com.intellij.codeInsight.intention.IntentionAction)] = Seq.empty,
  inspectionId: Option[String] = None,
  deprecated: Boolean = false,
  unnecessary: Boolean = false,
)

/** Process-wide host lifecycle and metadata needed to install the Scala plugin's language services. */
object ScalaPsiHost {
  /** The host this JVM started, set once by `start`; none before it. */
  @volatile private var started: Option[ScalaPsiHost] = None

  /**
   * Plugin extension points that carry language semantics; every other plugin extension point stays empty.
   *
   * Startup fails when the plugin's descriptors register no extension for one of them.
   */
  val SemanticExtensionPoints: Seq[String] = Seq(
    "org.intellij.scala.syntheticMemberInjector",
    // The inheritors of a class, from the stub index: case clauses and exhaustive matches list a sealed type's.
    "com.intellij.directClassInheritorsSearch", "com.intellij.classInheritorsSearch",
    "com.intellij.extendWordSelectionHandler", "com.intellij.basicWordSelectionFilter",
    // Find usages: the references to an element, its overriders and implementations.
    "com.intellij.referencesSearch", "com.intellij.methodReferencesSearch", "com.intellij.definitionsSearch",
    "com.intellij.overridingMethodsSearch",
    // Rename: the descriptions of elements in the conflicts the Java rename processors report ("method foo(): Int").
    "com.intellij.elementDescriptionProvider",
  )

  /**
   * The completion contributors the plugin's descriptors declare for Scala, as the IDE registers them: references and
   * members, keywords, class names to import, overrides, case clauses, end markers... Their insert handlers run once
   * a suggestion is chosen (see [[Completion.insert]]), so the ones that generate code work too.
   *
   * Contributors the host cannot support yet are left out.
   *
   * @param scalaPluginJars the Scala plugin's jars, whose descriptors declare the contributors
   * @return the descriptor beans of the supported contributors that declare the Scala language
   */
  def scalaCompletionContributors(scalaPluginJars: Seq[Path]): Seq[ExtensionBean] =
    Descriptors.scalaExtensionBeans(scalaPluginJars, "com.intellij.completion.contributor")
      .filter(bean => bean.attributes.get("language").contains("Scala") && !UnsupportedCompletionContributors(bean.implementation))

  /** Contributors that need what the host does not have yet, and why. */
  private val UnsupportedCompletionContributors: Set[String] = Set(
    // Its insert handler positions the caret on the new member with the editor's visual positions and visible area
    // (ScalaGenerationInfo.positionCaret), which the request editor does not have. Override Members (MemberOverrides)
    // inserts the same members without that step.
    "org.jetbrains.plugins.scala.lang.completion.ScalaOverrideContributor",
    // It suggests names the file uses but does not resolve, from the highlights of the IDE's daemon in the editor.
    "org.jetbrains.plugins.scala.lang.completion.ScalaUnresolvedNameContributor",
  )

  /**
   * The plugin's completion and proximity weighers, used with their declared keys and ordering.
   *
   * Fully qualified class names; the bootstrap registers only the plugin's weigher descriptors whose implementation is
   * in this set, and instantiates these classes without container injection.
   */
  val CompletionWeighers: Set[String] = Set(
    "ScalaContainingClassWeigher", "ScalaMethodCompletionWeigher", "ScalaByNameWeigher", "ScalaKindCompletionWeigher",
    "ScalaScopeWeigher", "ScalaClassObjectWeigher", "ScalaExplicitlyImportedWeigher",
  ).map("org.jetbrains.plugins.scala.lang.completion.weighter." + _)

  /**
   * Starts the host. The platform keeps the Application and the root extension area in process-wide statics,
   * so there is one host per JVM and it lives until the JVM exits.
   *
   * Callers are serialized on the object's monitor. A failed installation leaves the JVM without a host and rethrows.
   *
   * @param config startup settings for the platform and the Scala plugin
   * @return the host, with every service installed
   * @throws IllegalStateException when a host was already started in this JVM
   */
  def start(config: HostConfig): ScalaPsiHost = synchronized {
    if (started.isDefined) throw new IllegalStateException("ScalaPsiHost is already started in this JVM")

    val host = HostBootstrap.start(config)

    started = Some(host)
    host
  }

  /**
   * Tells the platform's caches that PSI changed, which they clear on (the inheritors the search helper remembers, the
   * resolve cache): the IDE's PsiManagerImpl does so on every tree change, the host once per change it makes.
   *
   * The callers known to the host (`replaceText` and the bootstrap's `psiChanged`) hold the write lock.
   *
   * @param project the host's project, whose message bus carries the notification
   */
  private[scalaanalyzer] def notifyPsiChanged(project: Project): Unit =
    project.getMessageBus.syncPublisher(com.intellij.psi.impl.PsiManagerImpl.ANY_PSI_CHANGE_TOPIC).beforePsiChanged(true)
}
