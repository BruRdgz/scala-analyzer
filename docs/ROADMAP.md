# Roadmap

Parity with the Scala plugin in IntelliJ IDEA is the goal. Nearly every editor feature of the plugin rests on one of
a few pieces of platform infrastructure, so the milestones are ordered by those pieces: each one, once the host
provides it, unlocks a group of features.

A feature is done when an oracle over the plugin's own test data (`scala/scala-impl/testdata` in intellij-scala)
agrees with the host, and the cases that do not agree are explained in the README, as for type inference today.

## Current state — 2026-10-31

The host already provides Scala 2/3 diagnostics, navigation, project-wide text search, symbols, folding, semantic tokens,
completion, signature help, inlay hints, selection ranges, IntelliJ/scalafmt formatting, rename, organize imports, and
previewable code actions. The documented limitations remain in [README.md](../README.md#known-limitations): compiler-index
searches and Java class indexing are incomplete, some UI-dependent actions and hints are unavailable, and only a
selected set of inspections runs, per file and over the whole project (Inspect Code).

**Verification checkpoint:** before the organization work, `sbt test` passed 108 tests. After the bootstrap,
resource-lifecycle, stable-module-identity, and package changes, `sbt test` passed 112/112. The final import and
threading-documentation cleanup then passed `sbt compile "Test / compile"`; those cleanup-only edits were not followed
by another test run. `sbt dist` passed, and the built distribution completed the fixture smoke journey (diagnostics,
hover, definition request, shutdown) with exit code 0. Temporary `Host_StubIndex` generation and `javac` compilation
using the exported compile classpath passed; the checked-in adapter still contains both maintained overloads.
Beta 1 remains implemented but untagged, and Beta 2 closed without inspections.

### Ownership and lifecycle constraints

- The IntelliJ application and extension area are JVM-wide. One host is created per JVM; replacing or restarting it
  requires a process restart. Multiple-project support is outside this roadmap.
- The process root owns platform state and the host library file system. It closes file systems opened for archives or
  alternate JDKs on JVM shutdown, and leaves the running JVM's shared JDK file system open.
- Each `ProjectSession` owns its BSP client connection, scalafmt instance, and document compiler. Session close waits
  for in-flight document compilations before releasing their compiler class loaders; it does not reset the process host.
- Build target IDs identify modules and their dependencies. Display names remain for presentation and may be
  ambiguous; existing `ModuleSpec` callers without an ID continue to use the name as the default ID.

### Next milestones

1. Follow the platform/plugin upgrade procedure in [ARCHITECTURE.md](ARCHITECTURE.md#platform-and-plugin-upgrades)
   before the next dependency upgrade: use a matching platform/plugin pair, review generated adapter diffs, and run
   the adapter surface plus semantic checks for changed boundaries. The surface test is one gate, not a compatibility
   verdict on its own.
2. Inspections: decided in 0.2. A selected set runs per file and over the whole project (Inspect Code); 0.3 added
   the collection inspections that the IDE enables by default, checked by `InspectionsOracle`. Widening the set
   further is open work, judged inspection by inspection.

#### Current package ownership

- `scalaanalyzer.platform`: IntelliJ bootstrap, adapters, threading, files, and indexes; flat initially.
- `scalaanalyzer.build`: BSP, compiler, and scalafmt integration.
- `scalaanalyzer.workspace`: project-session and build-import lifecycle.
- `scalaanalyzer.language`: language operations and internal result models.
- `scalaanalyzer.lsp`: transport and request/response mapping.
- `scalaanalyzer`: root `ScalaPsiHost` facade and caller-facing `HostConfig`, `Diagnostic`, `ModuleSpec`, `HostModule`,
  `ModuleModel`, `LineColumn`, `LineRange`, and `LibraryIndexReport` types.

The package map describes the current implementation. The historical milestones below retain their oracle counts and
limitations as recorded at the time.

## Original infrastructure sequence

- **A. Read-only, one file.** Nothing new: walk the PSI, or capture what the plugin already produces.
- **B. Project-wide search.** A word index (`IdIndex`) over the sources and a host `PsiSearchHelper`, behind the
  plugin's `referencesSearch` and `definitionsScopedSearch` extensions.
- **C. Change engine.** A change runs on a non-physical copy of the file, as the platform's intention preview does;
  the difference with the original becomes a `WorkspaceEdit`. Needs `CodeStyleManager` and the formatter, which
  almost every modification of the plugin calls.
- **D. Caret-bound features.** An `Editor` object in the fixed tables (document, caret, selection, settings and lexer;
  `AdapterSurfaceTest` shows what the plugin uses of it), a completion context (`CompletionParameters`, the copy with
  a dummy identifier), and a parameter-info UI context that records what `updateUI` renders.
- **E. Inspections.** The plugin's `LocalInspectionTool`s run without UI against a fixed profile, the IDE's default.

## Done ahead of order (2026-10-29)

The change engine and the formatter (beta 1), completion, signature help, selection ranges and the request-local
`Editor` (beta 2), and inlay hints exist; their gaps are in the README's known limitations. Next, from the review of that work:

- Scala 3 fixtures in the completion and parameter info oracles; file templates (override completion); live
  templates as snippets.

## Alpha 2 — read-only features, and the gaps of alpha 1

- The memory held per edit (`SessionBenchmark --edit-file`, about 0.3 MB per edit): locate it and let it go.
  **Done:** one `EverythingGlobalScope` per project instead of one per lookup; flat over 120 edits.
- A real Scala 2 project as an acceptance test. **Done:** korolev v1.5.0 agrees with the plugin's own project test.

## Alpha 3 — project-wide search

- Word index over the sources, `PsiSearchHelper`.
- Find references, implementations, workspace symbols. Oracle: `findUsages`.
- Java class files in the short-name index (auto-import and completion need them).
- `PackageIndex`: the plugin's use scope of a `private[pkg]` member is a `PackageScope`, which needs it (the
  inheritor searchers behind completion hit it today and log an error).

## Beta 1 — changes

Implementation completed on 2026-10-31 within the preview and search limits documented in README; the release is not
tagged. At the pre-organization checkpoint on that date, the complete suite passed 108 tests and a rebuilt distribution
formatted through scalafmt over LSP stdio. Those checks predate the current organization work. Inspections remain a
separate, undecided milestone.

- `CodeStyleManager` and the formatter; formatting, range formatting. **Done:** `FormatterOracle`: 187 agree,
  0 disagree, 0 skip. Whole-file requests honor the nearest workspace `.scalafmt.conf` through the plugin's dynamic
  engine; missing formatter artifacts are downloaded from Maven Central and cached under the host's state directory.
  Range formatting keeps the plugin's IntelliJ default. `ScalafmtOracle`: 8 agree, 0 disagree, using the plugin's
  3.11.5 and 2.7.5 contracts; the suite also verifies cold-cache download, disk-cache reuse and config failures.
- The change engine. **Done:** preview copies and minimal edits, shared by formatting, completion handlers and
  code actions; internal mod-command copies use the same synchronous commit and cleanup lifecycle.
- Code actions from the plugin's intentions and the annotators' quick fixes. **Done within the preview contract:**
  intentions, annotation fixes and surrounders return versioned LSP edits. `CodeActionsOracle` replays
  `SurroundWithTest`: 48 agree, 0 disagree, 0 skip; selected intention suites: 15 agree, 0 disagree, 12 skip.
  Type-import quick fixes offer each plugin candidate separately. Remaining: global-member/conversion imports
  and broader intention-oracle coverage. `testdata/actions` covers editor key handlers, not these intentions.
- Organize imports. **Done:** `source.organizeImports` invokes the plugin optimizer on a preview copy;
  `OptimizeImportsOracle`: 53 agree, 1 disagree, 7 skip, with the limits documented in README.
- Auto-import of types and rename. **Done:** type-import candidates are separate annotation quick fixes and
  completion insertions (`CompletionOracle` auto-import: 13 agree, 0 disagree). `RenameOracle`: 112 agree,
  1 disagree, 18 skip; `FindUsagesOracle`: 74 agree, 0 disagree, 3 skip. Search and preview gaps remain in README.

## Beta 2 — completion and inspections

Closed on 2026-10-30 (`v0.1.0-b2`) without inspections: their milestone is still to decide.

- The `Editor` object.
- Selection ranges. **Done:** successive Extend Selection steps through `SelectWordUtil`, the platform's base
  handlers and the plugin's descriptor-registered handlers; the editor supplies default settings and its layered lexer.
- Completion (oracles: `completion`, `completion3`, `keywordCompletion`) and signature help (oracle:
  `parameterInfo`), with a latency budget (p95 under 150 ms on the acceptance projects). **Done**, the budget
  waived: p95 261 ms first pass, 186 ms warm on YggDrasil, the remainder being the plugin's own contributors and
  weighers, a gap to the IDE judged insignificant.
- Inspections as diagnostics, with their quick fixes as code actions. Oracle: `inspections`. **Not done**; the
  quick fixes need beta 1's code actions.

## 0.1 — release

- Distribution and CI on tags.
- A platform upgrade process, with the adapter surface test as its gate.
- Third-party license listing for the binary distribution.
- Editor extensions published.

## 0.2 — release (2026-11-09)

Toward the IDE's UI in the editor, the logic in the server and the VS Code extension thin:

- Build: compile the build or one target on request (the build task, Ctrl+Shift+B), reimport, and a Scala Build view
  of the targets with their dependencies and libraries.
- Show Type Info; Run main lenses beside the test lenses, one run at a time, cancellable from the editor.
- Inspect Code over the whole project, generated sources skipped.
- Sources follow their files on disk while they are not open in the editor.

## 0.3 — release (2026-11-11)

More of the IDE's navigation, code insight and inspections, each through the plugin's own handler:

- Go to Definition through the plugin's Go to Declaration; Go to Super and Go to Test.
- Document highlights from the plugin's usage handlers; split/join lists, Flip ',', Unwrap and Remove as code actions.
- Inlay hints with links to declarations, an accept-type edit, per-kind settings and X-Ray mode.
- Inspection Results view in VS Code; the collection inspections that the IDE enables by default, with diagnostics
  published before the inspections finish.
- Single-file Inline, postfix templates, and Implement and Override members.

## 0.3.1 — release (2026-11-11)

Fixes from an audit of the invariants the code relied on without stating or enforcing them: diagnostics cancelled by
another write are computed again, a preview counts only its own thread's logged errors, the dispatch thread stays the
event thread after a task throws, code actions check the document's stamp across their reads, progress is optional,
edits of closed documents are unversioned, a run can start behind a cancelled one, and the Inspection Results view
detaches a problem once.

## 0.3.2 — release (2026-11-12)

Memory: the plugin pin moves to the upstream fix of its per-instance cache labels (SCL-26384), and the launcher sets
G1 free ratios of 20-50% with a periodic collection after 5 idle minutes.

## 0.3.3 — release (2026-11-17)

Fixes left by the 0.3.1 audit: a writer waiting for write intent leaves the intent owner's queries alone, and a
source whose stubs do not pair with its PSI stays out of the symbol index instead of failing its edit or import.

## 0.4 — release (2026-11-18)

- AI agents over MCP: `scala-analyzer --mcp`, the same host and `ProjectSession` behind a second transport, with
  read-only tools for project status, symbol search, navigation, types, outlines, diagnostics and inspections,
  following the disk before each call.
- Extract to val and Extract method as code actions, through the plugin's Introduce Variable and Extract Method with
  the IDE's defaults, followed in VS Code by a rename of the new name.
- Decided not to do now, each for its own reason: Change Signature (a multi-file processor that needs a parameter
  table as its UI, a webview in VS Code; the plugin's suggested refactoring, which would offer it after an edit of the
  signature, supports rename only, SCL-17973), debugging (the plugin's debugger is IntelliJ's JDI UI; DAP would go
  through BSP's `debugSession/start`, which depends on the build server), worksheets (a REPL and an editor UI, none of
  which the host can reuse). Introduce Parameter changes the callers, so it waits for a multi-file extraction.

## Next — 0.4.1

- Move a class or file from the editor's file explorer (`workspace/willRenameFiles`). The plugin's move processors move
  the file in the VFS to a `PsiDirectory`, and the host's sources have no directories: the plan is the rename's
  perform-and-restore with the plugin's own steps (`collectAssociations`, `setPackageName` in place, `collectUsages`
  and `bindToElement`, `restoreAssociations`, the import optimizer).
- Extract method from the last member of a Scala 3 class body without braces fails in the plugin
  (`ScalaExtractMethodHandler.insertMethod` inserts before a next sibling there is none of; probably since SCL-25158
  made the class-body member the default scope). The host withholds the action there
  (`Extract.targetsLastIndentedMember`); `EditingTest` expects the plugin's failure, so it fails once the pin carries a fix, and the guard can go.
- Inspect Code reports "Unused expression without side effects" on the patterns of `case` clauses that follow a guard
  whose `if` ends its line (`HostBootstrap`, the application table proxy). A plugin parser bug, reported to the
  maintainers on 2026-11-17: in Scala 3 the guard's indentation region (`ExprInIndentationRegion`) takes the clause's
  `=>`, and the clauses after it parse as loose statements. Nothing to change in the host; bump the pin once it is fixed.
- Left by the MCP test drives on this repository (2026-11-17), in the host's analysis, so the LSP shows them too.
  MCP writes (rename, quick fixes) are a later decision: they would edit files that the agent edits too.
  - The Java analysis reports an error on a call of a Scala member by its bytecode name: `HostImplicitHintsPass.java`
    calls the expanded name of a qualified-private member (`...$$collectedHintTemplates`), which the plugin's Java view
    of Scala classes does not have. Probably the IDE's error too: check in IntelliJ before changing anything.
  - `diagnostics` of every source took 144 s on this repository (133 sources), near the timeout of some clients.
  - Over MCP only: a library symbol cannot be named by `symbol` (`scala.collection.immutable.List`); the search
    covers the project sources only, so an agent reaches a library API through a use of it in the project.
- Left by the 0.3.1 audit, none fixed (read from the code or reported by reviewers; check before acting):
  - A document opened before it became a source is tracked by halves: its `didChange`s are dropped until an import
    registers it from disk, the next incremental change then lands on the disk text, and since only `didOpen` adds it
    to `openDocuments`, its edits are unversioned and a watched-file change overwrites it.
  - Build problems of non-Scala-3 sources are published at the positions of the last build, against the current buffer
    (only with `compilerErrorsScala2`); `DiagnosticsClient.published` keeps the entries of removed targets.
  - Lifecycle spread over fields: `ScalaLanguageServer` (`session`, `projectRequested`, `shutdownRequested`, `ending`,
    the executors, `state`; a missing or second `initialized` hangs or opens twice) and `ProjectSession` (its status and
    `inspectableSources` answer stale counts after a partial import); `BuildConnection` has no state for a request
    that timed out on the client but goes on in the server.
  - The progress callback of an import runs under the host write lock: a client that stops reading its messages stalls
    every query.

## Java support (branch `java-support`, from 2026-11-21)

The goal is Java at the IDE's level, as for Scala, in sbt projects and in Maven and Gradle ones. It is a program of
milestones, not one release; each wave ends with the full suite and its own commit.

- Done:
  - Wave A, Java resolution and search: library packages get their directories (Java's module access check reported
    every on-demand import as "Package not found"), records their implicit members, references of Java declarations
    go through the Java find-usages handler, Java inheritors come from the Java direct inheritors searcher, and the
    short-name cache finds member classes of Java binaries.
  - Wave E, Maven and Gradle: Java-only build targets become modules without a Scala SDK; a workspace with no `.bsp/`
    and a Maven or Gradle root is imported through Bloop (the host runs the tool's Bloop install step, then speaks BSP
    over `bloop bsp`'s Unix socket, and runs the step again when a build file changes). The JDK that runs the build
    tool is `SCALA_ANALYZER_BUILD_JAVA_HOME` (VS Code: `scalaAnalyzer.buildJavaHome`).
  - Each module's Java language level comes from its javac options; parser and stub builder read the same level.
  - Wave B, read features: Extend Selection, formatting, signature help, parameter-name hints and semantic tokens.
  - Wave C so far: organize imports; rename and references on Java documents (a Java interface method renames its
    Java and Scala overriders); the Java plugin's intentions (119 of 135) and error quick fixes (from java-backend's
    JavaPlugin.xml); the IDE's default Java local inspections (432 enabled by default, from java-backend's
    descriptors, minus `Inspections.JavaExclusions`, each excluded with its reason by the audit in
    `JavaInspectionsTest`; 432 run over its corpus in about 1 s).
- Decisions:
  - Bloop for both Maven and Gradle (spike E0): Microsoft's build-server-for-gradle publishes no artifact, reports no
    compile diagnostics and has no test or run environment. Bloop answers no `workspace/reload` and has no stdio mode.
  - Java references leave out text occurrences, as the plugin's Scala options do.
  - sbt announces Scala only and never answers `buildTarget/javacOptions`: it is not asked, and the Java sources of
    sbt modules stay at the highest language level.
- Open questions from wave C:
  - Nine data-flow inspections (DataFlowIssue, ConstantExpression, PointlessBooleanExpression, ObviousNullCheck...)
    are excluded: they cache through `JavaLibraryModificationTracker`, a final project service that subscribes to the
    IDE's message bus, which the host was designed without. ConstantValue, of the same engine, loads but reports
    nothing on `int x = 1; if (x == 1)`; to look into with them.
  - NullableProblems is excluded, and data flow sees no nullability annotations: the host's NullableNotNullManager
    answers none. The real manager plus the Java plugin's annotation-package supports would change how Scala sees
    Java's `@Nullable` too, so it needs the type-inference oracle first.
  - Java error fixes that change the IDE's project model (add a library or module to a classpath) are not offered.
- Plan to finish the branch (2026-11-22), in order, each step with its tests, the full suite and a commit:
  1. Navigation on Java documents: implementations, call and type hierarchies (the host's operations are already
     on `PsiClass`/`PsiMethod`; the LSP registrations select Scala only), and Go to Super from Java.
  2. Override/Implement on Java classes: the Java plugin's `OverrideImplementUtil` through the host's preview editor,
     with a Velocity subset in `HostFileTemplates` (the Java method-body templates use `#if`).
  3. Java refactorings: Extract variable, Extract method and Inline on Java sources, through the Java plugin's
     processors with the IDE's defaults, as the Scala ones are.
  4. Wave D: `main` lenses (including Java 21+ instance mains) and JUnit 4/5 test lenses on Java sources, run over
     BSP like the Scala ones.
  5. Spike, no architecture change without a decision: what the nine data-flow inspections need of a message bus
     (one project-level `MessageBusImpl` with no subscribers but the tracker, or a replaced tracker), and why
     ConstantValue reports nothing.
  6. Release preparation of 0.5.0: README (Java, Maven, Gradle), CHANGELOG, the VS Code extension's README and
     activation, the java-playground's scenarios as the manual checklist, VSIX; merge and push wait for the user.
- After 0.5.0, candidates (each its own decision): 0.4.1's Move; debugging over BSP's `debugSession/start` (Bloop
  and sbt both start a debug adapter); test discovery for Scala and Java over BSP; Change Signature with a webview;
  MCP writes; the `intellij.projects`/`jdkForSymbolResolution`-like settings; file and live templates; Bazel; the
  0.3.1 audit's lifecycle items; diagnostics of every source in less than the 144 s of this repository.
- Candidates taken from JetBrains' own LSP extension, "Java and Kotlin by IntelliJ IDEA"
  ([docs](https://www.jetbrains.com/help/intellij-vscode/About-instance.html)), which runs the IDE itself and is
  closed source, so only its documented behavior is a reference:
  - A setting that names the projects to import (`intellij.projects`: type `maven`, `gradle` or `bazel`, and a path)
    when detection is not enough, and one for the JDK to resolve symbols against when the build gives none
    (`intellij.jdkForSymbolResolution`).
  - Per-kind toggles of the Java inlay hints (`jetbrains.java.hints.*`); the host's Java hints have no settings yet.
  - Its editor mapping as wave C's checklist: intentions and quick fixes in one lightbulb list, live templates
    through completion, file templates for new files, problem descriptions on hover.
  - Bazel projects, later.

## Later

- A persistent index, for fast startup: library jars are cached since the library cache (Unreleased); a start still
  indexes the project's sources on one thread under the import's write lock (about 2.7 s for this repository's 136
  sources), which could be read in parallel as library jars are.
- `.sbt` files (sbt's meta-build is a build target over BSP).
- Test discovery and code lenses over BSP.
- Change Signature, Introduce Parameter; worksheets; debugging (DAP). See the 0.4 decisions.

## Code quality — standardization pass (2026-11-06/07)

[CONVENTIONS.md](CONVENTIONS.md) is the standard; every package under `src/main` follows it (`platform`, `build`,
root data types, `language`, `lsp`, `workspace`, `ScalaPsiHost`). The suite passed 176/176 at the end of the pass.

**Decisions**

- The `Host_*.java` adapters stay outside the pass: 27 mechanical files of at most 43 lines, pinned by
  `AdapterSurfaceTest`, regenerated on a platform upgrade rather than read.
- `Completion` needs no pass of its own: its longest method is 45 lines after the pass.
- The tests were not standardized.
- Run `sbt clean test` before pushing a build-structure or package change: an incremental build hid a shadowed
  package once (`scalaanalyzer.munit`) that a clean CI build rejected.

**Found and fixed during the pass** (each with a test): `completeInsertion` with malformed arguments answered
`InternalError` instead of `InvalidParams`; semantic tokens with one start position and different lengths were kept
in hash order; a test run's compile stored build diagnostics without republishing them, and the next compile on
save then saw no change.

**Found, not fixed** (read from the code or reported by reviewers; check before acting)

- Indexes and files: `WordIndex.indexFile` without `removeFile` leaves stale `filesByWord` (`indexFile` could remove
  first). `LibraryFileSystem.filenameIndex` can publish a stale index when a mount lands between the snapshot and
  `byName = index`; the same jar mounted twice leaks a file system. `libraryFileOnDisk` does I/O under the object
  monitor. `HostDispatchThread` initialization calls `submit().get()` while the object initializes.
- `HostBootstrap`: `installApplication`, `installLanguageRegistries` and `installProjectServices` run 300 to 520 lines
  and their phases leak services into each other. `newPrivate` takes the first constructor of the matching arity,
  which is not deterministic with overloads. `instantiateClass` has duplicated cases. `platform` imports `language`
  at `HostBootstrap.scala:275`, the only upward dependency found.
- Build and tests: `TestRun.execute` may call `finished` twice after a `NonFatal` failure. `BspImport.connect` and
  `modules` are long. A rare timeout in `LanguageServerTest` ("a client that does not apply the server's edits gets
  suggestions without commands", seen once on 2026-11-07, passed on rerun) was not investigated.
- `ProjectLanguageOperations.prepareRename` discards the result of `renameTarget` and reads in two non-atomic blocks;
  `hierarchyElement` nests five levels. Long methods in `language/`: `CodeActions.collect` (about 80 lines),
  `Inspections.collect` (about 63), `ChangeEngine.run`; measure again before acting.
- `ProjectSession`: `applyImport` is about 69 lines and holds the host write lock and the import lock throughout (by
  design). `close()` does I/O under `buildLock`. `isUsable` ignores `closed`. `workspaceSymbols` does not take the
  import snapshot lock, unlike `problems` and `isSource`. `language` is a `val` between methods that depends on the
  initialization order of two fields above it.
- `ScalaLanguageServer`: `initialize` is about 100 lines (judged not worth splitting: linear and single use).
  `closing` is a `lazy val` with side effects. `importAgain` and `openProject` catch `Throwable`. `onRead` shares a
  plain `var asked` across threads. A `file:` URI that does not parse throws out of `sourcePath` as `InternalError`.
  The `TextDocuments` object (about 300 lines) and the conversion helpers would split naturally into a converter file.
- Public tuples left: `ScalaPsiHost.rename`, `textAttributes` and `Diagnostic.fixes`; `ProjectSession.definition`,
  `typeDefinition` and `codeActions`. `TextChange`, `FileProblem` and `CompletionFollowUp` keep `start` and `end` as
  separate fields.
- [ARCHITECTURE.md](ARCHITECTURE.md) does not exist, though this file links it; the upgrade procedure it should hold
  lives only in an old checkpoint (`d9e0340`).
