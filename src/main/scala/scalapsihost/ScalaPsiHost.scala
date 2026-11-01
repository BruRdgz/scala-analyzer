package scalapsihost

import com.intellij.codeInsight.daemon.impl.{AnnotationHolderImpl, AnnotationSessionImpl}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiErrorElement, PsiFile, SyntaxTraverser}
import com.intellij.openapi.application.Application
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Key, UserDataHolderBase}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiManager}
import com.intellij.util.Processor
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.project.{ProjectContext, ScalaFeatures}

import scalapsihost.language.{ChangeEngine, CodeActions, Completion, InlayHints, ParameterInfo, Rename, SelectionRanges}
import scalapsihost.platform.{CallLog, Descriptors, ExtensionBean, HostBootstrap, HostDispatchThread, HostLock, Host_OrderEnumerator, Host_ModuleRootManager, HostPsiDocumentManager, ImportIndex, LibraryFileSystem, SourceFile, SymbolIndex, WordIndex, dispatch, tableProxy}

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
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
 * plugin's PSI. Whatever is not in a table fails loudly and is recorded in [[CallLog]].
 */
final class ScalaPsiHost private[scalapsihost] (
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
) {
  // Library jars by real path: a jar on the classpath of several modules is mounted and indexed once.
  private val libraryRoots = new ConcurrentHashMap[Path, VirtualFile]()
  // The mounted JDK's home (written in the write lock).
  @volatile private var jdkHome: Option[Path] = None

  /**
   * Runs `query` as the IDE's read actions run: concurrently with other queries, never during an edit. An edit that
   * starts meanwhile cancels it: it throws `ProcessCanceledException`. For callers that read PSI directly; the host's
   * own queries ([[diagnostics]]) do this themselves. Nested reads are part of the outer one.
   */
  def read[T](query: => T): T = HostLock.query(query)

  /** The completion suggestions at the UTF-16 text `offset`; nothing is inserted until one is chosen. */
  def completion(file: ScalaFile, offset: Int): Completion.Suggestions = Completion.complete(file, offset)

  /** The inline hints collected by the plugin's hints pass. */
  def inlayHints(file: ScalaFile): Seq[InlayHints.InlayHint] = InlayHints.in(file)

  /** The signatures of the call around `offset`, as the IDE's parameter info shows them. */
  def parameterInfo(file: ScalaFile, offset: Int): Option[ParameterInfo.Signatures] = ParameterInfo.at(file, offset)

  /** Successive Extend Selection ranges, smallest first. */
  def selectionRanges(file: ScalaFile, offset: Int): Seq[TextRange] = SelectionRanges.at(file, offset)

  /** What the insert handler of a chosen suggestion changes (see [[Completion.insert]]). */
  def insertCompletion(file: ScalaFile, start: Int, prefixText: String, lookup: String, identity: String)
      : Option[Completion.Inserted] =
    Completion.insert(file, start, prefixText, lookup, identity)

  /** The edits that format `range` of `file` (all of it without one); the file itself is left as it is. */
  def formatting(file: ScalaFile, range: Option[TextRange], indentSize: Int, useTabs: Boolean): Seq[ChangeEngine.Edit] =
    ChangeEngine.format(file, range, indentSize, useTabs).edits

  /** A file outside the project: parsed, but invisible to symbol lookups (as the IDE's non-physical files). */
  def parse(text: String, features: ScalaFeatures = ScalaFeatures.default): ScalaFile = HostLock.readLocked {
    ScalaPsiElementFactory.createScalaFileFromText(text, features)(using new ProjectContext(project))
  }

  /**
   * A project source file: its declarations are indexed, so other files and the file itself find them by name,
   * as for a file under an IDE source root. Fails when its stubs cannot be paired with its PSI.
   */
  def openSource(text: String, features: ScalaFeatures = ScalaFeatures.default): ScalaFile =
    HostLock.write(index(parse(text, features)))

  /** Adds a source's stubs, words and imports to the project indexes and notifies PSI listeners. */
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
   */
  def rename(file: PsiFile, offset: Int, newName: String): Seq[(PsiFile, Seq[ChangeEngine.Edit])] = {
    val (originals, changed) = try HostLock.writeIntent {
      // Keep the read plan valid until its write: another editor change must not enter between the two.
      val target = renameTarget(file, offset)
      val plan = read(Rename.plan(target, newName))
      HostLock.write {
        val originals = plan.files.map(f => f -> f.getText)
        try {
          // The platform changes physical PSI inside a command or an undo-transparent action; the host has no undo.
          com.intellij.openapi.command.CommandProcessor.getInstance.runUndoTransparentAction(() => Rename.perform(plan))
          (originals, plan.files.map(_.getText))
        } finally com.intellij.openapi.progress.ProgressManager.getInstance.executeNonCancelableSection((() => {
          val documents = com.intellij.psi.PsiDocumentManager.getInstance(project)
          originals.foreach { (f, text) =>
            val document = documents.getDocument(f)
            documents.doPostponedOperationsAndUnblockDocument(document)
            replaceText(f, 0, document.getTextLength, text)
          }
        }): Runnable)
      }
    } finally HostDispatchThread.drain()
    originals.zip(changed).map { case ((f, before), after) => f -> ChangeEngine.diff(before, after) }.filter(_._2.nonEmpty)
  }

  /**
   * The element the IDE renames for the symbol at `offset` (see [[Rename.target]]). The Java processors ask for the
   * event thread when they look for a method's base methods, which they do for a symbol of a Java source.
   */
  def renameTarget(file: PsiFile, offset: Int): PsiElement = HostDispatchThread.call(read(Rename.target(file, offset)))

  /** Removes a source file opened with [[openSource]] or [[openFile]] from the project. */
  def closeSource(file: PsiFile): Unit = HostLock.write {
    val virtualFile = file.getViewProvider.getVirtualFile
    symbolIndex.removeFile(file)
    wordIndex.removeFile(file)
    importIndex.removeFile(file)
    modules.removeSource(virtualFile)
    // The file store lets go of its view provider (and PSI): a file opened again at the same path is a new file.
    PsiManager.getInstance(project).asInstanceOf[com.intellij.psi.impl.PsiManagerEx].getFileManager.setViewProvider(virtualFile, null)
    psiChanged()
  }

  /**
   * Removes the module identified by its stable ID after its source files are closed. A module that depends on it is
   * removed too, or added again with it. Its libraries stay mounted, for other modules and for the module added again.
   */
  def removeModule(name: String): Unit = HostLock.write {
    modules.remove(name)
    psiChanged()
  }

  /** Mounts a library jar and indexes its Scala class files accepted by `includeEntry` (e.g. `scala/Option.class`). */
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
   */
  def addLibrarySources(jar: Path, sources: Path): Unit = HostLock.write {
    val root = Option(libraryRoots.get(jar.toRealPath())).getOrElse(throw new IllegalArgumentException(s"library jar is not mounted: $jar"))
    librarySources.put(root, libraryFiles.mountSources(sources))
    psiChanged()
  }

  /** Attaches the JDK's sources (its `lib/src.zip`, one directory per module) to the mounted JDK's modules. */
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

  /**
   * The IDE `Module` object of a host module. Its only order entry the plugin reads is the Scala SDK library, which
   * carries the module's Scala version; the classpath jars are not presented as libraries (scopes come from
   * [[ModuleModel]]).
   */
  private def newModule(hostModule: HostModule): Module = {
    val data = new UserDataHolderBase
    val sdkProperties = org.jetbrains.plugins.scala.project.ScalaLibraryProperties(
      Some(hostModule.spec.scalaVersion), Seq.empty, Seq.empty, None, org.jetbrains.plugins.scala.project.ReplClasspath.Bundled)
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
      case ("getName", _) => hostModule.spec.stableId
      case ("getProject", _) => project
      case ("isDisposed", _) => java.lang.Boolean.FALSE
      case ("getModuleTypeName", _) => "JAVA_MODULE"
      case ("getService", Array(c: Class[?])) if c == classOf[com.intellij.openapi.roots.ModuleRootManager] => rootManager
      case ("getUserData", Array(k: Key[?])) => data.getUserData(k).asInstanceOf[AnyRef]
      case ("putUserData", Array(k: Key[?], v)) => data.putUserData(k.asInstanceOf[Key[AnyRef]], v); null
    }
    module
  }

  /**
   * Whether `file`'s errors come from the build's compiler (see [[HostConfig.compilerErrorsScala3]]). For such a file
   * [[diagnostics]] has only the plugin's checks that do not need types, and the caller adds the compiler's problems.
   */
  def isCompilerHighlighted(file: ScalaFile): Boolean = HostLock.query {
    org.jetbrains.plugins.scala.settings.ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(file)
  }

  /** The plugin's organize-imports action, with edits against a copy of `file`. */
  def organizeImports(file: ScalaFile): CodeActions.Choice = read(actions.organizeImports(file))

  /** Text actions for the selected range, preserving their annotator fixes and previewing on file copies. */
  def codeActions(file: ScalaFile, range: TextRange): Seq[CodeActions.Choice] =
    read(actions.collect(file, range, diagnostics(file)))

  /**
   * Collects parser errors accepted by the platform's highlighting filters.
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
   * The problems the IDE's highlighting pass reports for `file`: syntax errors, and the plugin's `ScalaAnnotator`
   * run over every element through the platform's annotation session. Presentation-only annotations (no message)
   * are left out.
   */
  def diagnostics(file: ScalaFile): Seq[Diagnostic] = HostLock.query {
    val syntax = syntaxErrors(file)
    val annotator = new org.jetbrains.plugins.scala.annotator.ScalaAnnotator
    val annotations = AnnotationSessionImpl.computeWithSession(file, /* batchMode = */ false, annotator, { holder =>
      val impl = holder.asInstanceOf[AnnotationHolderImpl]
      SyntaxTraverser.psiTraverser(file).forEach(e => impl.runAnnotatorWithContext(e))
      impl.asScala.toSeq
    })
    syntax ++ annotations.collect {
      case a if a.getMessage != null =>
        Diagnostic(a.getSeverity, new TextRange(a.getStartOffset, a.getEndOffset), a.getMessage,
          Option(a.getQuickFixes).toSeq.flatMap(_.asScala).map(f => f.textRange -> f.quickFix))
    }
  }

  /**
   * The colors the IDE gives `file`'s identifiers and soft keywords beyond its lexer's: the plugin's syntax
   * highlighting visitor (declarations, parameters, named arguments, annotations) and its color-scheme annotator
   * (references, by what they resolve to), in that order, as the IDE's highlighting pass runs them.
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
    val annotator = new org.jetbrains.plugins.scala.highlighter.ScalaColorSchemeAnnotator
    val annotations = AnnotationSessionImpl.computeWithSession(file, /* batchMode = */ false, annotator, { holder =>
      val impl = holder.asInstanceOf[AnnotationHolderImpl]
      elements.foreach(e => impl.runAnnotatorWithContext(e))
      impl.asScala.toSeq
    })
    fromVisitor ++ annotations.flatMap(a => Option(a.getTextAttributes).map(new TextRange(a.getStartOffset, a.getEndOffset) -> _))
  }
}

/** A PSI diagnostic and the plugin quick fixes available for its source range. */
final case class Diagnostic(severity: HighlightSeverity, range: TextRange, message: String,
                            fixes: Seq[(TextRange, com.intellij.codeInsight.intention.IntentionAction)] = Seq.empty)

/** Process-wide host lifecycle and metadata needed to install the Scala plugin's language services. */
object ScalaPsiHost {
  @volatile private var started: Option[ScalaPsiHost] = None

  /** Plugin extension points that carry language semantics; every other plugin extension point stays empty. */
  val SemanticExtensionPoints: Seq[String] = Seq(
    "org.intellij.scala.syntheticMemberInjector",
    // The inheritors of a class, from the stub index: case clauses and exhaustive matches list a sealed type's.
    "com.intellij.directClassInheritorsSearch", "com.intellij.classInheritorsSearch",
    "com.intellij.extendWordSelectionHandler", "com.intellij.basicWordSelectionFilter",
    // Find usages: the references to an element, its overriders and implementations, and the use scope widened for
    // sources shared between modules.
    "com.intellij.referencesSearch", "com.intellij.methodReferencesSearch", "com.intellij.definitionsSearch",
    "com.intellij.overridingMethodsSearch", "com.intellij.useScopeEnlarger",
    // Rename: the descriptions of elements in the conflicts the Java rename processors report ("method foo(): Int").
    "com.intellij.elementDescriptionProvider",
  )

  /**
   * The completion contributors the plugin's descriptors declare for Scala, as the IDE registers them: references and
   * members, keywords, class names to import, overrides, case clauses, end markers... Their insert handlers run once
   * a suggestion is chosen (see [[Completion.insert]]), so the ones that generate code work too.
   */
  def scalaCompletionContributors(scalaPluginJars: Seq[Path]): Seq[ExtensionBean] =
    Descriptors.scalaExtensionBeans(scalaPluginJars, "com.intellij.completion.contributor")
      .filter(bean => bean.attributes.get("language").contains("Scala") && !UnsupportedCompletionContributors(bean.implementation))

  /** Contributors that need what the host does not have yet, and why. */
  private val UnsupportedCompletionContributors: Set[String] = Set(
    // The bodies of the members it generates come from the IDE's file templates (FileTemplateManager).
    "org.jetbrains.plugins.scala.lang.completion.ScalaOverrideContributor",
    // It suggests names the file uses but does not resolve, from the highlights of the IDE's daemon in the editor.
    "org.jetbrains.plugins.scala.lang.completion.ScalaUnresolvedNameContributor",
  )

  /** The plugin's completion and proximity weighers, used with their declared keys and ordering. */
  val CompletionWeighers: Set[String] = Set(
    "ScalaContainingClassWeigher", "ScalaMethodCompletionWeigher", "ScalaByNameWeigher", "ScalaKindCompletionWeigher",
    "ScalaScopeWeigher", "ScalaClassObjectWeigher", "ScalaExplicitlyImportedWeigher",
  ).map("org.jetbrains.plugins.scala.lang.completion.weighter." + _)

  /**
   * Starts the host. The platform keeps the Application and the root extension area in process-wide statics,
   * so there is one host per JVM and it lives until the JVM exits.
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
   */
  private[scalapsihost] def notifyPsiChanged(project: Project): Unit =
    project.getMessageBus.syncPublisher(com.intellij.psi.impl.PsiManagerImpl.ANY_PSI_CHANGE_TOPIC).beforePsiChanged(true)

}
