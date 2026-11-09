package scalaanalyzer.workspace

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile

import scalaanalyzer.{HostModule, LineColumn, LineRange, ScalaPsiHost}
import scalaanalyzer.build.{
  BspImport, BuildConnection, CompileSpec, CompilerDiagnostic, DocumentCompiler, ImportedModule, ScalafmtFormatting,
  TestRun,
}
import scalaanalyzer.language.{
  CodeActionResult, CompletionChoices, CompletionFollowUp, FileChanges, FileProblem, Fold, HierarchyCall,
  HierarchyItem, HierarchyTarget, InlineHint, Occurrence, OutlineSymbol, ProjectLanguageOperations, SemanticToken,
  SignatureChoices, SourceSpan, TestLens, TextChange, WorkspaceSymbolMatch,
}
import scalaanalyzer.platform.{HostLock, HostLog}

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.util.Using

/**
 * Owns an imported workspace, its source buffers and its build connection.
 *
 * Sources stay registered when editor tabs close. Positions use zero-based lines and UTF-16 columns throughout
 * this API; edits update in-memory documents rather than saving files to disk. Source requests require a path
 * registered by the build and fail if a partial import has left the session unavailable.
 *
 * Callers serialize document operations and imports, as the LSP documents executor does. `isSource` and
 * `problems` support concurrent diagnostic work: their guard excludes partial host updates. `compileBuild` may
 * run alongside document operations; BSP compilation, imports and closure are serialized within the session.
 * `snapshot` provides counts from the last completed import without waiting for the current one.
 *
 * Close the session to release its BSP connection, formatter and document compiler. The shared host and its
 * mounted libraries remain owned by the process.
 *
 * @param host process-scoped PSI host in which this workspace's modules and sources are registered
 * @param build BSP connection owned by this session and closed with it
 * @param root workspace directory used as the boundary for formatter-configuration lookup
 */
final class ProjectSession private (
  host: ScalaPsiHost,
  build: BuildConnection,
  root: Path,
) extends AutoCloseable {
  /** The live registry of project sources by path; an import adds and removes entries while it applies. */
  private val files = new ConcurrentHashMap[Path, PsiFile]()
  /** Diagnostics run apart from the documents executor; this lock keeps their source check/read outside an import. */
  private val importSnapshotLock = new ReentrantReadWriteLock(true)
  /** Build compilation runs apart from document requests, but must never overlap a reload/import or BSP shutdown. */
  private val buildLock = new Object
  /** Set by `close`; once true, `ensureUsable` rejects requests. */
  @volatile private var closed = false
  /** The build's modules as last imported (changed only by `importBuild`, on the session's thread). */
  @volatile private var imported: Seq[ImportedModule] = Seq.empty
  /** Compile settings of the last import's modules, by stable module id. */
  @volatile private var compileSpecs: Map[String, CompileSpec] = Map.empty
  /** Classpath jars whose sources were asked for, attached or not. */
  private var sourcesAskedFor = Set.empty[Path]
  /** Compiles current buffers for compiler diagnostics; closed with the session. */
  private val documentCompiler = new DocumentCompiler
  /** Formats whole Scala files; closed with the session. */
  private val scalafmt = new ScalafmtFormatting(host.stateDir.resolve("scalafmt"))
  /** The build's compiler problems from its last compile, by file (for files the document compiler does not cover). */
  private val buildProblems = new AtomicReference[Map[Path, Seq[CompilerDiagnostic]]](Map.empty)
  /** The failure message recorded by `markUnavailable`; `ensureUsable` rejects requests while it is present. */
  @volatile private var unavailable: Option[String] = None
  /** Counts as of the end of the last completed import, readable without waiting for an ongoing one. */
  @volatile private var lastImportedSnapshot = ProjectSession.Snapshot(0, 0)

  /** Captures the validated module graph, affected identities and source inputs for a host update. */
  private final case class ImportPlan(
    orderedModules: Seq[ImportedModule],
    previousModulesById: Map[String, ImportedModule],
    nextModulesById: Map[String, ImportedModule],
    moduleIdsToRebuild: Set[String],
    sourceTextByPath: Map[Path, String],
    classpathJarsToFetchSources: Set[Path],
    librarySourcesByJar: Map[Path, Path],
  )

  /**
   * Lists the sources currently registered in the session.
   *
   * This includes sources without an open editor tab. During an import the live registry can change; use
   * `snapshot` for counts from the last completed import. Takes no lock and does not check the session's state.
   *
   * @return the registered source paths, in unspecified order
   */
  def sourceFiles: Seq[Path] = files.keySet.toArray(Array.empty[Path]).toSeq

  /**
   * Checks whether a path belongs to a registered project source.
   *
   * Library files are excluded. The check takes the import guard's read lock, so it waits for host updates, and
   * rejects a closed or unavailable session.
   *
   * @param path source path as registered by the build
   * @return `true` if `path` is a registered project source; `false` for library files and unregistered paths
   */
  def isSource(path: Path): Boolean = {
    val readLock = importSnapshotLock.readLock()
    readLock.lock()
    try {
      ensureUsable()
      files.containsKey(path)
    } finally readLock.unlock()
  }

  /**
   * Reports whether source requests remain permitted by the import-failure guard.
   *
   * Takes no lock. Only the partial-import failure is consulted: closing the session does not change the result.
   *
   * @return `false` once a partial import has left the session unavailable; `true` otherwise
   */
  private[scalaanalyzer] def isUsable: Boolean = unavailable.isEmpty

  /**
   * Provides the recorded partial-import failure and recovery message.
   *
   * Takes no lock.
   *
   * @return the message that source requests are rejected with after a partial import; `None` while the session
   *         has no recorded failure
   */
  private[scalaanalyzer] def unavailableMessage: Option[String] = unavailable

  /**
   * Reads the last completed import's counts without waiting for ongoing updates.
   *
   * Takes no lock.
   *
   * @return the module and source counts as of the end of the last completed import; both zero before the first
   *         one completes
   */
  private[scalaanalyzer] def snapshot: ProjectSession.Snapshot = lastImportedSnapshot

  /** Rejects requests after closure or a partial import failure. */
  private def ensureUsable(): Unit = {
    if (closed) throw new IllegalStateException("the project session is closed")
    unavailable.foreach(message => throw new ProjectSession.Unavailable(message))
  }

  /** Marks the session unavailable under the caller's import guard and host write lock. */
  private def markUnavailable(error: Throwable): Unit = {
    val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
    unavailable = Some(s"the project session is unusable after a partial build import ($detail); restart the language server to recover")
  }

  /** Resolves source PSI in a usable session; callers must protect subsequent PSI access. */
  private def file(path: Path): PsiFile = {
    ensureUsable()
    Option(files.get(path)).getOrElse(throw new IllegalArgumentException(s"not a source file of the project: $path"))
  }

  /** Performs the PSI-backed source requests this session delegates to, resolving paths through `file`. */
  private val language = new ProjectLanguageOperations(host, root, documentCompiler, scalafmt, file)

  /**
   * Reads the current text of a source buffer.
   *
   * @param path registered project-source path
   * @return the in-memory text, including unsaved edits
   */
  def text(path: Path): String = language.text(path)

  /**
   * Collects completion suggestions at a source position.
   *
   * Collecting suggestions does not insert text; a chosen suggestion's handler runs through `insertCompletion`.
   *
   * @param path registered project-source path
   * @param at caret position in zero-based lines and UTF-16 columns
   * @return the suggestions in the order the host returns them, best first, with an incomplete flag; empty and
   *         complete for a source that is neither Scala nor Java
   */
  def completion(path: Path, at: LineColumn): CompletionChoices = language.completion(path, at)

  /**
   * Collects inline hints within a source range.
   *
   * @param path registered project-source path
   * @param start range start in zero-based lines and UTF-16 columns
   * @param end range end in the same coordinates
   * @return the hints whose offset lies within the range, in the order the host returns them; empty for a source
   *         that is not Scala
   */
  def inlayHints(path: Path, start: LineColumn, end: LineColumn): Seq[InlineHint] = language.inlayHints(path, start, end)

  /**
   * Finds the runnable suite and test selections of a source, each with the BSP target identity of its module.
   *
   * @param path registered project-source path
   * @return the lenses of a Scala source whose module has a BSP target identity; empty for any other source
   */
  def testLenses(path: Path): Seq[TestLens] = language.testLenses(path)

  /**
   * Looks up signatures for the call surrounding a caret.
   *
   * @param path registered project-source path
   * @param at caret position in zero-based lines and UTF-16 columns
   * @return the rendered overloads and the active one as the host reports them; `None` when the host finds no call,
   *         and for a source that is not Scala
   */
  def signatures(path: Path, at: LineColumn): Option[SignatureChoices] = language.signatures(path, at)

  /**
   * Builds successive selection expansions for each caret.
   *
   * A Scala caret with no expansion keeps its empty range. Non-Scala sources return an empty outer sequence.
   *
   * @param path registered project-source path
   * @param positions carets in zero-based lines and UTF-16 columns, in the desired result order
   * @return one chain per caret, in the order of `positions`, each of ranges smallest first
   */
  def selectionRanges(path: Path, positions: Seq[LineColumn]): Seq[Seq[LineRange]] =
    language.selectionRanges(path, positions)

  /**
   * Previews the insert handler of a chosen completion suggestion.
   *
   * The editor must first insert `lookup` in place of `prefixText`. Returned changes describe the follow-up
   * insertion; the caller applies them and reports the resulting buffer through `change`.
   *
   * @param path registered project-source path
   * @param start start of the inserted lookup text, in zero-based lines and UTF-16 columns
   * @param prefixText original typed prefix replaced by the lookup text
   * @param lookup lookup text already inserted by the editor
   * @param identity suggestion identity returned by `completion`
   * @return the changes still to apply and the caret position, in the text that applying them produces; `None` when
   *         the host reports no follow-up, and for a source that is neither Scala nor Java
   */
  def insertCompletion(path: Path, start: LineColumn, prefixText: String, lookup: String, identity: String)
      : Option[CompletionFollowUp] = language.insertCompletion(path, start, prefixText, lookup, identity)

  /**
   * Computes formatting edits for a Scala source.
   *
   * The buffer is unchanged. Whole-file requests use scalafmt when a configuration is found; range requests
   * use the IntelliJ formatter. Formatter failures propagate to the caller.
   *
   * @param path registered project-source path
   * @param range optional range in zero-based lines and UTF-16 columns; `None` selects the whole file
   * @param indentSize positive indentation size used by the IntelliJ formatter
   * @param useTabs whether the IntelliJ formatter should indent with tabs
   * @return the changes against the current buffer; empty for a source that is not Scala
   */
  def formatting(path: Path, range: Option[LineRange], indentSize: Int, useTabs: Boolean): Seq[TextChange] =
    language.formatting(path, range, indentSize, useTabs)

  /**
   * Applies an editor change to a source buffer and its PSI indexes.
   *
   * This updates in-memory state without saving to disk and can cancel running host queries.
   *
   * @param path registered project-source path
   * @param range range in zero-based lines and UTF-16 columns; `None` replaces the entire buffer
   * @param replacement text to put in place of the selected range
   */
  def change(path: Path, range: Option[LineRange], replacement: String): Unit =
    language.change(path, range, replacement)

  /**
   * Collects diagnostics for the current source.
   *
   * This may run concurrently with session operations and waits for import application. Scala 3 compiler
   * diagnostics use the current buffer when compile settings are available; other compiler diagnostics come
   * from the last build compile. Java sources use the platform's syntax and semantic analysis of the current
   * buffer, without running javac.
   *
   * @param path registered project-source path
   * @return the plugin's problems, then the compiler's; empty for a source that is neither Scala nor Java
   */
  def problems(path: Path): Seq[FileProblem] = {
    val readLock = importSnapshotLock.readLock()
    readLock.lock()
    try {
      val source = file(path)
      val spec = moduleSpec(source)
      language.problems(path, spec, buildProblems.get.getOrElse(path, Seq.empty))
    } finally readLock.unlock()
  }

  /** Finds a source's owning module compile settings while the caller holds the import guard. */
  private def moduleSpec(file: PsiFile): Option[CompileSpec] =
    host.modules.ofFile(file.getViewProvider.getVirtualFile).flatMap(m => compileSpecs.get(m.spec.stableId))

  /**
   * Renders a symbol's signature and available ScalaDoc as Markdown.
   *
   * Missing documentation can leave a signature-only result.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position in zero-based lines and UTF-16 columns
   * @return Markdown with the signature in a fenced block, followed by the documentation when it can be read; `None`
   *         when no symbol is found or the plugin has no quick information for it
   */
  def hover(path: Path, at: LineColumn): Option[String] = language.hover(path, at)

  /**
   * Describes the type under a caret or of a selected expression, as the IDE's Type Info action does.
   *
   * @param path registered project-source path
   * @param range selection in zero-based lines and UTF-16 columns; an empty range stands for the caret
   * @return the type as plain text, on several lines when the IDE adds the expected or original type; `None` when
   *         there is no type to show, and for a source that is not Scala
   */
  def typeInfo(path: Path, range: LineRange): Option[String] = language.typeInfo(path, range)

  /**
   * Locates the declaration referenced at a source position.
   *
   * Library targets are materialized as read-only source or decompiled files under the host's state directory.
   *
   * @param path registered project-source path
   * @param at reference position in zero-based lines and UTF-16 columns
   * @return the declaration's file and the position of its name; `None` when nothing resolves there or the
   *         declaration is in neither a project source nor a library file
   */
  def definition(path: Path, at: LineColumn): Option[(Path, LineColumn)] = language.definition(path, at)

  /**
   * Locates the type declarations associated with a symbol.
   *
   * Library locations use materialized source or decompiled files, as in `definition`.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position in zero-based lines and UTF-16 columns
   * @return the declaration locations, distinct and in the order the type lookup finds them; empty when the symbol
   *         has no type declaration to navigate to
   */
  def typeDefinition(path: Path, at: LineColumn): Seq[(Path, LineColumn)] = language.typeDefinition(path, at)

  /**
   * Finds a symbol's usages across project sources.
   *
   * Results follow the plugin's text-based find-usages support; usages requiring compiler indexes are unavailable.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position in zero-based lines and UTF-16 columns
   * @param includeDeclaration whether to include the target's declaration name when it belongs to a project source
   * @return the distinct spans, the declaration first; empty when the position has no usage target
   */
  def references(path: Path, at: LineColumn, includeDeclaration: Boolean): Seq[SourceSpan] =
    language.references(path, at, includeDeclaration)

  /**
   * Previews plugin actions for a source range.
   *
   * The buffer is unchanged. Unsupported previews can produce disabled actions with a reason. Organize imports
   * is included only when `only` explicitly requests its source-action category or an ancestor category.
   *
   * @param path registered project-source path
   * @param start selection start in zero-based lines and UTF-16 columns
   * @param end selection end in the same coordinates; equal to `start` for a caret request
   * @param only accepted action kinds and their descendants; empty selects contextual fixes and refactorings
   * @return each accepted action paired with its current-buffer edits; empty for a source that is not Scala
   */
  def codeActions(path: Path, start: LineColumn, end: LineColumn, only: Seq[String] = Seq.empty)
      : Seq[(CodeActionResult, Seq[TextChange])] = language.codeActions(path, start, end, only)

  /**
   * Validates a rename target and locates its name range.
   *
   * Propagates `Rename.Refused` when the position has no renameable project-source target.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position in zero-based lines and UTF-16 columns
   * @return start and end of the name to rename
   */
  def prepareRename(path: Path, at: LineColumn): LineRange = language.prepareRename(path, at)

  /**
   * Computes project-source edits for renaming a symbol.
   *
   * The host restores the original buffers before returning. The caller applies the edits and reports the
   * resulting text through `change`. Invalid targets, names or conflicts propagate as `Rename.Refused`;
   * the plugin's text-search limitations also apply to the affected references.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position in zero-based lines and UTF-16 columns
   * @param newName replacement name validated by the plugin's rename processors
   * @return the edits of each affected project-source file; files outside the project sources are left out
   */
  def rename(path: Path, at: LineColumn, newName: String): Seq[FileChanges] = language.rename(path, at, newName)

  /**
   * Locates implementations and overriders in project sources.
   *
   * The plugin may include the target itself when invoked on a reference, following its go-to-implementation rules.
   *
   * @param path registered project-source path
   * @param at symbol position in zero-based lines and UTF-16 columns
   * @return the spans of the declarations' names, distinct and in the order the search finds them
   */
  def implementations(path: Path, at: LineColumn): Seq[SourceSpan] = language.implementations(path, at)

  /**
   * Prepares the call hierarchy method or type hierarchy class at a position, without retaining PSI across requests.
   *
   * @param path registered project-source path
   * @param at position of the method or class, in zero-based lines and UTF-16 columns
   * @param types `true` for the type hierarchy's class, `false` for the call hierarchy's method
   * @return the item for the target, locatable in a project source or a navigable library; empty when the position
   *         has no target or the target cannot be located
   */
  def prepareHierarchy(path: Path, at: LineColumn, types: Boolean): Seq[HierarchyItem] =
    language.prepareHierarchy(path, at, types)

  /**
   * Resolves a call-hierarchy item and finds its call edges.
   *
   * @param path registered project-source path the request started from
   * @param target item to expand, as `prepareHierarchy` returned it
   * @param incoming `true` for the callers of the item, `false` for the methods it calls
   * @return one edge per caller or callee, with the call sites in the caller's document for incoming edges; empty
   *         when the target no longer resolves to a method
   */
  def hierarchyCalls(path: Path, target: HierarchyTarget, incoming: Boolean): Seq[HierarchyCall] =
    language.hierarchyCalls(path, target, incoming)

  /**
   * Resolves a type-hierarchy item and finds its direct supertypes or project-source subtypes.
   *
   * @param path registered project-source path the request started from
   * @param target item to expand, as `prepareHierarchy` returned it
   * @param subtypes `true` for the subclasses, `false` for the supertypes
   * @return the items found; empty when the target no longer resolves to a class
   */
  def hierarchyTypes(path: Path, target: HierarchyTarget, subtypes: Boolean): Seq[HierarchyItem] =
    language.hierarchyTypes(path, target, subtypes)

  /**
   * Searches project symbols by name.
   *
   * Uses the plugin's name matching and result limit. Library symbols are excluded; a blank query matches nothing.
   *
   * @param query symbol-name query, optionally qualified by package or enclosing type
   * @return the matches, best first
   */
  def workspaceSymbols(query: String): Seq[WorkspaceSymbolMatch] = {
    ensureUsable()
    language.workspaceSymbols(query)
  }

  /**
   * Finds a symbol's occurrences within one source file.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position in zero-based lines and UTF-16 columns
   * @return the distinct occurrences with write-access markers, the declaration first when it is in this document;
   *         empty when no named symbol is at the position
   */
  def highlights(path: Path, at: LineColumn): Seq[Occurrence] = language.highlights(path, at)

  /**
   * Builds the declaration outline shown by the plugin's structure view.
   *
   * @param path registered project-source path
   * @return the nested declarations; empty for a source that is not Scala
   */
  def outline(path: Path): Seq[OutlineSymbol] = language.outline(path)

  /**
   * Collects foldable code, comments, imports and explicit regions.
   *
   * When a range ends at a closing bracket, its last line remains visible.
   *
   * @param path registered project-source path
   * @return the distinct multiline regions; empty for a source that is not Scala
   */
  def foldingRanges(path: Path): Seq[Fold] = language.foldingRanges(path)

  /**
   * Classifies identifiers and soft keywords using the plugin's highlighting.
   *
   * Only supported, nonempty, single-line classifications are returned. LSP encoding belongs to the caller.
   *
   * @param path registered project-source path
   * @return the tokens with source positions, kinds and modifiers; empty for a source that is not Scala
   */
  def semanticTokens(path: Path): Seq[SemanticToken] = language.semanticTokens(path)

  /**
   * Synchronizes this session's modules and sources with the BSP build.
   *
   * The first import registers the imported modules and opens their sources; subsequent imports apply changes.
   * Changed modules and their transitive dependents are recreated, preserving unsaved text for sources that
   * remain in the build.
   *
   * This call blocks on BSP and filesystem I/O. Callers must serialize it with other session operations;
   * concurrent diagnostics are synchronized with the host-update phase.
   *
   * Preparation gathers module descriptions, source text and library-source attachments before changing host PSI.
   * A failure during preparation leaves the previous host snapshot usable. A failure after host mutations begin
   * leaves the session unavailable and requires restarting the language server; partial changes are not rolled
   * back. Exceptions from `progress` propagate and follow the same distinction between preparation and application.
   *
   * @param reload whether to reload the build definition before requesting its modules; pass `true` after changes
   *               to build-definition files, or `false` to refresh modules and sources without reloading it
   * @param progress callback invoked synchronously on the calling thread as each stage starts; defaults to a no-op
   */
  def importBuild(reload: Boolean, progress: String => Unit = _ => ()): Unit = buildLock.synchronized {
    ensureUsable()

    val start = System.nanoTime()

    /** Applies a prepared plan under both locks, recording mutation failures before unlocking. */
    def applyPreparedImport(preparedPlan: ImportPlan): Unit = {
      var hostMutationStarted = false
      val writeLock = importSnapshotLock.writeLock()
      writeLock.lock()
      try {
        HostLock.write {
          try applyImport(preparedPlan, progress, () => hostMutationStarted = true)
          catch {
            case error: Throwable =>
              if (hostMutationStarted) markUnavailable(error)
              throw error
          }
        }
      } finally writeLock.unlock()
    }

    if (reload) {
      progress("reloading the build definition")
      HostLog.timed("build definition reloaded")(build.reload())
    }
    progress("importing the build's modules")

    val modules = HostLog.timed("modules described by the build")(build.modules())
    val plan = planImport(modules, progress)

    applyPreparedImport(plan)

    val existingModulesRebuilt = plan.moduleIdsToRebuild
      .intersect(plan.previousModulesById.keySet)
      .intersect(plan.nextModulesById.keySet)
      .size

    HostLog.info(s"build imported in ${(System.nanoTime() - start) / 1_000_000} ms: ${plan.orderedModules.size} modules " +
      s"(${plan.nextModulesById.keySet.diff(plan.previousModulesById.keySet).size} new, $existingModulesRebuilt added again, " +
      s"${plan.previousModulesById.keySet.diff(plan.nextModulesById.keySet).size} removed), " +
      s"${plan.orderedModules.map(_.sources.size).sum} imported sources, ${files.size} in all")
  }

  /** Validates the next module graph and gathers source inputs without changing host PSI. */
  private def planImport(modules: Seq[ImportedModule], progress: String => Unit): ImportPlan = {
    val orderedModules = BspImport.dependenciesFirst(modules)
    val previousModulesById = imported.map(module => module.spec.stableId -> module).toMap
    val nextModulesById = orderedModules.map(module => module.spec.stableId -> module).toMap

    /** Includes previous dependents whose cached scopes require rebuilding with the affected modules. */
    def includeDependents(moduleIds: Set[String]): Set[String] = {
      val expanded = moduleIds ++ previousModulesById.values
        .filter(_.spec.dependsOn.exists(moduleIds))
        .map(_.spec.stableId)
      if (expanded == moduleIds) moduleIds else includeDependents(expanded)
    }

    val changedModuleIds = previousModulesById.keySet.filter { id =>
      !nextModulesById.get(id).exists(_.spec == previousModulesById(id).spec)
    }
    val previousOwnerBySource = imported.flatMap(module => module.sources.map(_ -> module.spec.stableId)).toMap
    val nextOwnerBySource = orderedModules.flatMap(module => module.sources.map(_ -> module.spec.stableId)).toMap
    val movedSourcePaths = previousOwnerBySource.keySet.intersect(nextOwnerBySource.keySet)
      .filter(path => previousOwnerBySource(path) != nextOwnerBySource(path))
    val movedModuleIds = movedSourcePaths.flatMap { path =>
      Set(previousOwnerBySource(path), nextOwnerBySource(path))
    }
    val moduleIdsToRebuild = includeDependents(changedModuleIds ++ movedModuleIds)

    val sourcePaths = orderedModules.flatMap(_.sources).distinct
    val openTextByPath = host.read {
      sourcePaths.flatMap(path => Option(files.get(path)).map(file => path -> file.getText))
    }.toMap
    val sourceTextByPath = sourcePaths.map(path => path -> openTextByPath.getOrElse(path, Files.readString(path))).toMap

    // Fetch source attachments before the PSI update, so a build-server failure cannot leave modules half replaced.
    val classpathJarsToFetchSources = orderedModules.flatMap(_.spec.classpath).toSet -- sourcesAskedFor
    val librarySourcesByJar = if (classpathJarsToFetchSources.isEmpty) Map.empty
    else {
      progress(s"attaching library sources (${classpathJarsToFetchSources.size} jars)")
      HostLog.timed(s"source jars asked for ${classpathJarsToFetchSources.size} jars") {
        build.librarySources(classpathJarsToFetchSources)
      }
    }

    ImportPlan(
      orderedModules,
      previousModulesById,
      nextModulesById,
      moduleIdsToRebuild,
      sourceTextByPath,
      classpathJarsToFetchSources,
      librarySourcesByJar,
    )
  }

  /** Applies a prepared plan under the caller's import guard and host write lock. */
  private def applyImport(plan: ImportPlan, progress: String => Unit, beforeHostMutation: () => Unit): Unit = {
    var closedSourceCount = 0
    var openedSourceCount = 0

    /** Signals mutation before unregistering and closing a source, preserving partial-failure tracking. */
    def closeTrackedSource(path: Path): Unit = Option(files.get(path)).foreach { file =>
      beforeHostMutation()
      files.remove(path)
      host.closeSource(file)
      closedSourceCount += 1
    }

    /** Opens a source with its planned buffer text and tracks the host mutation. */
    def openImportedSource(source: Path, hostModule: HostModule): Unit = {
      beforeHostMutation()
      files.put(source, host.openFile(source, plan.sourceTextByPath(source), hostModule))
      openedSourceCount += 1
    }

    /** Reports progress before registering a module, including modules without sources. */
    def addImportedModule(nextModule: ImportedModule): HostModule = {
      val spec = nextModule.spec
      val moduleId = spec.stableId

      progress(s"module ${spec.name} ($moduleId): ${spec.classpath.size} jars, ${nextModule.sources.size} sources")
      HostLog.timed(s"module ${spec.name} ($moduleId, Scala ${spec.scalaVersion}, ${spec.classpath.size} jars) added") {
        beforeHostMutation()
        host.addModule(spec)
      }
    }

    // Remove dependents first (reverse of the dependencies-first import order).
    for (previousModule <- imported.reverse if plan.moduleIdsToRebuild(previousModule.spec.stableId)) {
      previousModule.sources.foreach(closeTrackedSource)
      beforeHostMutation()
      host.removeModule(previousModule.spec.stableId)
    }

    for (nextModule <- plan.orderedModules) {
      val moduleId = nextModule.spec.stableId
      if (!plan.previousModulesById.contains(moduleId) || plan.moduleIdsToRebuild(moduleId)) {
        val hostModule = addImportedModule(nextModule)

        nextModule.sources.foreach(source => openImportedSource(source, hostModule))
      } else {
        val hostModule = host.modules.byId(moduleId).getOrElse(
          throw new IllegalStateException(s"host module $moduleId (${nextModule.spec.name}) disappeared during import"))
        val previousSources = plan.previousModulesById(moduleId).sources.toSet
        val nextSources = nextModule.sources.toSet

        (previousSources -- nextSources).foreach(closeTrackedSource)
        (nextSources -- previousSources).foreach(source => openImportedSource(source, hostModule))
      }
    }

    for ((jar, sources) <- plan.librarySourcesByJar) {
      beforeHostMutation()
      host.addLibrarySources(jar, sources)
    }
    if (plan.librarySourcesByJar.nonEmpty)
      HostLog.info(s"library sources attached to ${plan.librarySourcesByJar.size} of ${plan.classpathJarsToFetchSources.size} jars")

    imported = plan.orderedModules
    compileSpecs = plan.orderedModules.map(module => module.spec.stableId -> module.compile).toMap
    sourcesAskedFor ++= plan.classpathJarsToFetchSources
    lastImportedSnapshot = ProjectSession.Snapshot(imported.size, files.size)
    HostLog.info(s"${openedSourceCount} sources opened, ${closedSourceCount} closed, ${files.size} in all")
  }

  /**
   * Counts the modules currently recorded as imported.
   *
   * For a consistent pair of counts from one completed import, use `snapshot` instead of combining this value
   * with the live source registry during an update. Takes no lock.
   *
   * @return the number of modules in the last applied import; zero before the first import
   */
  def moduleCount: Int = imported.size

  /**
   * Describes the build's targets as the last completed import left them.
   *
   * Takes no lock: during an import it describes the previous one, so callers that run it with the imports, as the
   * LSP documents executor does, see a finished import.
   *
   * @return one description per imported module, sorted by name, then by stable identity
   * @throws IllegalStateException when the session is closed or unavailable
   */
  def buildTargets(): Seq[ProjectSession.TargetSummary] = {
    ensureUsable()

    imported
      .map { module =>
        ProjectSession.TargetSummary(
          id = module.spec.stableId,
          name = module.spec.name,
          scalaVersion = module.spec.scalaVersion,
          dependsOn = module.spec.dependsOn,
          libraries = module.spec.classpath,
          sourceFileCount = module.sources.size,
        )
      }
      .sortBy(target => (target.name, target.id))
  }

  /**
   * Compiles the build, or one target of it, and replaces the stored build diagnostics.
   *
   * This blocks on BSP and does not save editor buffers; document compilation is handled separately by `problems`.
   * Document operations can continue during compilation. Imports and closure wait for its BSP request to finish.
   * The build can report compilation errors without throwing, in which case its diagnostics are still stored. The
   * build keeps the diagnostics of the targets that were not compiled as their last compile left them; they are
   * stored, but not counted.
   *
   * @param target stable identity of the target to compile (`TargetSummary.id`, the BSP target URI); `None` compiles
   *               every target
   * @return whether the build compiled, the number of errors and warnings of the compiled targets and their
   *         dependencies (in registered sources or not), and the registered sources whose stored diagnostics changed
   *         and may need republishing
   * @throws BspImport.UnknownTarget when `target` is not a Scala target of the build
   * @throws IllegalStateException when the session is closed or unavailable
   */
  def compileBuild(target: Option[String] = None): ProjectSession.BuildCompilation = buildLock.synchronized {
    ensureUsable()

    val compiled = target match {
      case Some(id) => build.compile(id)
      case None => build.compile()
    }
    val severities = compiled.compiledProblems.map(_.severity)
    val changed = storeBuildProblems(compiled.diagnostics)

    ProjectSession.BuildCompilation(
      succeeded = compiled.succeeded,
      errors = severities.count(_ == HighlightSeverity.ERROR),
      warnings = severities.count(_ == HighlightSeverity.WARNING),
      changed = changed,
    )
  }

  /** Replaces the stored build diagnostics; returns the registered sources whose diagnostics differ from the old ones. */
  private def storeBuildProblems(compiled: Map[Path, Seq[CompilerDiagnostic]]): Set[Path] = {
    val previous = buildProblems.getAndSet(compiled)

    (previous.keySet ++ compiled.keySet)
      .filter(path => previous.get(path) != compiled.get(path) && files.containsKey(path))
  }

  /**
   * Compiles a test target and captures its environment under the BSP guard, without taking a host lock.
   *
   * This blocks on BSP like `compileBuild`, and stores the build diagnostics published so far, whether or not the
   * compilation succeeded. `changed` receives the registered sources whose stored diagnostics changed, as
   * `compileBuild` reports them, once they are stored and before a failed compilation is reported, so it also runs
   * for a failed one. It runs on the calling thread under the BSP guard and must not block. A failed compilation is
   * reported as an exception after that.
   *
   * @param target BSP target to compile and to capture the test environment of
   * @param changed receives the sources that may need their diagnostics republished
   * @return the target's test environment, captured after its successful compilation
   * @throws IllegalStateException when the target does not compile, or the session is closed or unavailable
   */
  def prepareTestRun(target: String, changed: Set[Path] => Unit): TestRun.Environment = buildLock.synchronized {
    ensureUsable()

    val compiled = build.compile(target)

    changed(storeBuildProblems(compiled.diagnostics))
    if (!compiled.succeeded) throw new IllegalStateException(s"compilation failed for $target; see compiler diagnostics")

    build.testEnvironment(target)
  }

  /**
   * Releases the session's compiler, formatter and BSP connection.
   *
   * Waits for in-flight build operations and document compilations before releasing their resources. Closing is
   * idempotent. Every resource is offered for closure even if another close fails; cleanup failures propagate with
   * suppressed errors as managed by `Using`. Callers must stop session operations before closing. Host modules,
   * source PSI and mounted libraries remain in the process-scoped host.
   */
  override def close(): Unit = buildLock.synchronized {
    if (!closed) {
      closed = true
      Using.resources(build, scalafmt, documentCompiler)((_, _, _) => ())
    }
  }
}

/** Opens workspaces and defines the session's import-status and failure values. */
object ProjectSession {
  /**
   * Captures module and source counts from one completed import for concurrent status reporting.
   *
   * @param moduleCount number of modules recorded by the import
   * @param sourceFileCount number of registered project sources when the import completed
   */
  private[scalaanalyzer] final case class Snapshot(moduleCount: Int, sourceFileCount: Int)

  /**
   * Describes one build target: the imported module's identity, compiler and dependencies, without host internals.
   *
   * @param id stable identity of the module, which is its BSP target URI
   * @param name display name, which several targets may share
   * @param scalaVersion Scala version of the target
   * @param dependsOn stable identities of the modules this one directly depends on
   * @param libraries jars on the target's classpath; the class output of other targets is not among them
   * @param sourceFileCount number of project sources the build assigns to the target
   */
  final case class TargetSummary(
    id: String,
    name: String,
    scalaVersion: String,
    dependsOn: Seq[String],
    libraries: Seq[Path],
    sourceFileCount: Int,
  )

  /**
   * Captures what compiling the build produced: its outcome, diagnostic counts and the sources to republish.
   *
   * @param succeeded whether BSP reported a successful compile
   * @param errors number of error diagnostics the compile published, in registered sources or not
   * @param warnings number of warning diagnostics the compile published, in registered sources or not
   * @param changed registered sources whose stored diagnostics changed and may need republishing
   */
  final case class BuildCompilation(succeeded: Boolean, errors: Int, warnings: Int, changed: Set[Path])

  /**
   * Rejects source requests after a partial import; recovery requires restarting the language server.
   *
   * @param message the recorded failure and recovery advice, as `unavailableMessage` reports it
   */
  private[scalaanalyzer] final class Unavailable(message: String) extends IllegalStateException(message)

  /**
   * Connects to a workspace build and opens its modules and sources in the host.
   *
   * This blocks on BSP, library indexing and source-file I/O. It mounts the running JVM's JDK and available JDK
   * sources, then performs the initial import. The returned session owns its connection, formatter and document
   * compiler and must be closed by the caller.
   *
   * Opening failures close the resources acquired so far, preserving cleanup errors as suppressed exceptions.
   * Any host mutations already performed remain in the process-scoped host.
   *
   * @param host initialized process-scoped host receiving the workspace modules and sources
   * @param root workspace directory containing the BSP connection definitions
   * @param connection optional BSP connection-file name without its `.json` suffix; required if several exist
   * @param progress callback invoked synchronously on the calling thread as each stage starts; defaults to a no-op
   * @return the session with its initial import completed; the caller owns it and must close it
   */
  def open(host: ScalaPsiHost, root: Path, connection: Option[String] = None, progress: String => Unit = _ => ()): ProjectSession = {
    progress("connecting to the build server")

    val build = HostLog.timed(s"build server of $root connected")(BspImport.connect(root, connection))
    var cleanupOwner: AutoCloseable = build

    try {
      val javaHome = Path.of(System.getProperty("java.home"))

      host.addJdk(javaHome)
      // The JDK's sources, as the IDE attaches them to the JDK.
      Some(javaHome.resolve("lib/src.zip")).filter(Files.isRegularFile(_)).foreach(host.addJdkSources)

      val session = new ProjectSession(host, build, root)

      cleanupOwner = session
      session.importBuild(reload = false, progress)
      session
    } catch {
      case error: Throwable =>
        try cleanupOwner.close()
        catch { case closeError: Throwable => if (closeError ne error) error.addSuppressed(closeError) }
        throw error
    }
  }
}
