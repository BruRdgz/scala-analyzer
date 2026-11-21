# Changelog

## Unreleased

- A build import indexes the library jars of a module in parallel, one thread per processor, so the server is ready
  sooner: on this repository (226 jars) the import's library indexing went from about 19 s to 8 s, and the time to
  ready from 25 s to 13 s.
- The server keeps what reading a library jar produced (the stub trees of its decompiled classes and the plugin's
  persistent file attributes) in `library-cache/` under its state directory, one file per jar, so a later start
  indexes that jar without decompiling it. On this repository the library indexing of a start with the cache went
  from about 9 s to under 3 s, and the time to ready from about 15 s to 8.5 s; the cache takes 20 MB for 229 jars.
  A changed jar, plugin or server gets a cache file of its own, and files unused for 30 days are deleted.
- The log shows how long a build import took to open each module's sources. With `SCALA_ANALYZER_LOG=debug` it also
  shows each file's inspection time with its five slowest inspections, and what the library cache held for each jar.
- The launcher sends the JVM's own warnings and log to standard error, so they can no longer corrupt the LSP or MCP
  stream on standard output.
- Java sources that compile no longer get false errors on on-demand imports of library and JDK packages
  (`import java.util.*;`: "Package not found") or on the implicit constructor and accessors of records.
- References of a Java class or method find its Java and Scala usages; they found nothing.
  As for Scala declarations, text occurrences in comments and strings are left out.
- Implementations and subtypes of a Java class or interface include its Java inheritors.
- Auto-import and class-name completion find the member classes of Java libraries and of the JDK (`Map.Entry`).
- Maven and Gradle projects open without a BSP connection file, through [Bloop](https://scalacenter.github.io/bloop/)
  on the `PATH`: the server runs the build tool's Bloop install step (`./mvnw` or `mvn`, `./gradlew` or `gradle`,
  without editing the build files), connects to `bloop bsp` over a Unix socket, and runs the step again when a
  `pom.xml` or a Gradle file changes. `SCALA_ANALYZER_BUILD_JAVA_HOME`, or the VS Code setting
  `scalaAnalyzer.buildJavaHome`, chooses the JDK the build tool runs on; by default the extension passes the
  `JAVA_HOME` VS Code was started with, since older Gradle versions cannot run on JDK 25.
- Build targets with Java sources only become modules, without a Scala SDK; the build target view shows them as Java.
  A build server that cannot reload (Bloop) is no longer asked to.
- The VS Code extension also starts for Java files and in Maven and Gradle workspaces.
- Java sources are parsed and checked at their module's language level, from the build's javac options (`--release`,
  else `-source`; `--enable-preview` selects the preview level), as in the IDE: a record in a module released for
  Java 11 is reported. A build that reports no javac options, as sbt does over BSP, keeps the highest level.
- Java files have Extend Selection ranges, with the Java plugin's selectioners, and IntelliJ formatting of the whole
  file or a range, with the request's indentation.
- Java files have signature help, from the Java plugin's parameter info (each overload's signature, the active one and
  the parameter at the caret), and the IDE's parameter-name hints at its defaults (`name:` before a literal argument,
  none where the argument is named like the parameter).
- Java files have semantic tokens from the Java plugin's name highlighting: classes, interfaces, enums, annotations,
  type parameters, methods and their declarations, fields (final ones read-only), parameters and locals, with the
  same token types as the Scala names of the same kind.
- Java files have Organize imports (the platform's Java import optimizer, with the default Java style: unused imports
  go, and an on-demand import serving fewer than five classes becomes single-class imports), and rename and
  references are offered on Java documents: renaming a Java interface method renames its Java and Scala overriders
  and every call.
- Java files have the Java plugin's intentions in the lightbulb (`refactor.rewrite`), read from its descriptors: 119
  of its 135 Java intentions, previewed on a copy as the Scala ones are. Those that need a dialog, a popup, an editor
  template or an inline refactoring, or that edit something other than the file, are left out.
- Scala parameter-name hints follow the IDE's default exclusion list, which the Scala filter shares with Java: calls
  such as setters (`*.set*(*)`), `Math` methods or `println` no longer show parameter names.

## 0.4.0 — 2026-11-18

AI agents can ask the project questions over MCP, and the editor gains Extract to val and Extract method.

- `scala-analyzer --mcp [--root DIR]` serves the Model Context Protocol over stdio, for any MCP client: project status,
  symbol search, definitions, references, implementations, callers and callees, supertypes and subtypes, types,
  hover documentation, outlines, and diagnostics and inspections of a file or of the whole project. The tools are
  read-only, take a symbol name or a file, line and identifier, and follow the files on disk before each call. See the
  README, "Use with AI agents (MCP)".
- Extract to val and Extract method are code actions (`refactor.extract`) on a selection, as in the IDE's Introduce
  Variable and Extract Method, with their defaults. Extract to val replaces every occurrence of the expression, or only
  the selected one when there are others.
- The VS Code extension starts a rename of the new name after an extraction, as the IDE's in-place rename does.

## 0.3.3 — 2026-11-17

Two fixes for rare failures that a restart used to be the only way out of.

- A rename no longer fails when the host invalidates its caches while the rename prepares its plan. A write waiting
  for its turn cancelled the queries of the rename that held the turn, although it could not write before the
  rename ended.
- A source whose declarations the index cannot match to its syntax tree is left out of the declaration index, with a
  warning in the log. Before, the edit stopped halfway, leaving the word and import indexes on the old text, and
  during a build import it made the session unusable until the server restarted. In Scala sources this cannot
  happen while typing; in Java sources it takes a platform bug.

## 0.3.2 — 2026-11-12

Less memory for the server.

- The Scala plugin is at a revision whose caches no longer build label strings for every cache instance
  (SCL-26384). After a session over this repository, the server holds about 850 MB of live objects instead of
  1370 MB, and its requests took about 10% less time.
- The launcher keeps the committed heap near what the server uses, and lets it shrink after 5 idle minutes.
  Before, the committed heap grew to the maximum (2 GB by default) during a session and stayed there while the
  editor was idle.
- `scalaAnalyzer/status` reports the committed heap next to the used and maximum heap.

## 0.3.1 — 2026-11-11

Fixes of invariants that the code relied on without stating or enforcing them, found by an audit of the code base.

- A document's diagnostics are computed again when a write that is not its own cancels them: another document's edit,
  an import or a settings change. Before, the document kept no new diagnostics, or only those published before its
  inspections, until its next edit.
- A code action is no longer disabled, and Organize imports no longer fails, because another request or the
  diagnostics logged an error while the action was previewed: only the errors logged by the preview's own thread count.
- The host's dispatch thread stays the platform's event thread after a task queued on it throws, and the failure is
  logged. Before, the thread that replaced it was not the event thread, so the plugin's event-thread checks failed.
- Code actions are cancelled when their document changes between the reads that compute them, instead of converting
  ranges of one version of the text against another, or failing with an internal error.
- Opening and importing the project go on without progress when the client does not acknowledge the progress token in
  time or a progress report cannot be sent. Before, the project failed to open.
- The edits of a document the client has closed (the other files of a rename, an action resolved for it) are
  unversioned. They carried the version the document had before it was closed, which the client numbers anew when it
  opens the document again.
- A test or main run can start while a cancelled one is still ending, for instance in its compile: it waits for it
  instead of being refused with "a run is already in progress".
- VS Code: applying a fix from the Inspection Results view no longer removes the file's other problems from the view
  while they stay in the Problems panel.

## 0.3.0 — 2026-11-11

- Go to Definition asks the plugin's Go to Declaration for Scala sources, so it also covers case-class construction
  calls (to the case class), companions, `end` markers, `given` imports, `this(...)` calls, for-comprehensions and
  package objects, and answers several locations when the plugin finds several; other sources keep resolving the
  reference directly.
- `scalaAnalyzer/superElements` answers the locations that the IDE's Go to Super navigates to from a caret: the
  superclasses of the type around it, or the members that the member around it overrides or implements. The VS Code
  extension adds **Go to Super**, which opens the declaration or peeks several; it has no default key.
- `scalaAnalyzer/testTargets` answers the locations that the IDE's Go to Test navigates to from a caret: the tests of
  the class around it, or the classes that the test class around it tests, ranked by name proximity as the IDE ranks
  them. The host installs the plugin's ScalaTest, specs2 and MUnit detectors as the platform's test frameworks (uTest
  suites are not recognized: their detection needs library versions the host lacks) and the Java and Scala test
  finders. The VS Code extension adds **Go to Test**, which opens the location or peeks several; it has no default key.
- Document highlights use the plugin's usage handlers, as the IDE does: on `def`, `return`, `val`, `case`, `match`,
  `if`, `try`, `for` and `=>` they mark the exit points or results, on a class-like keyword its companion's, on an
  `end` marker the definition, on constructor calls and case-class references the class and its constructor, and on an
  implicit the places that use it. Elsewhere they mark the symbol's occurrences, as before.
- Code actions offer the platform's **Put arguments/parameters/tuple elements/type arguments on separate lines** and
  **on one line** (split/join list), and **Flip ','**; the host registers the plugin's six split/join contexts for them.
- Code actions offer the plugin's **Unwrap** and **Remove** actions (`if`, `else`, `while`, `for`, `try`, `catch`,
  `finally`, `match`, `case`, braces and others) at the caret, previewed on a copy like the intentions.
- **Remove unnecessary parentheses** is offered: it needed an inspection profile, which the host has had since 0.2.
  The host does not run the inspection, so the intention is available whenever the parentheses are redundant.
- Inlay hints carry what the plugin attaches to their parts: a type name in a hint is a link to its declaration (library
  declarations open their materialized source) and shows its signature on hover. The server resolves both lazily
  (`inlayHint/resolve`), because locating a library declaration writes its source file. The hint that shows an inferred
  `val`, `var` or `def` type has the edit that writes it, which VS Code applies on a double click.
- The inlay hint kinds follow the client's `scalaAnalyzer.inlayHints.*` settings (method result types, member and local
  variable types, obvious types, parameter names, method chains, implicits), read with `workspace/configuration` once the
  client is ready and again after `workspace/didChangeConfiguration`; the server then sends `workspace/inlayHint/refresh`.
  The defaults are the IDE's, except implicit hints, which stay on.
- X-Ray mode is the setting `scalaAnalyzer.inlayHints.xRay` (off by default), applied and refreshed like the other
  inlay hint settings. It shows what the plugin shows in X-Ray mode with the IDE's default X-Ray options: the types
  of all `def`s, `val`s and `var`s without one (obvious ones too), of lambda parameters, placeholders and pattern
  variables, parameter names, by-name arguments, `.apply` calls and method chains, and implicit hints even when they
  are off. Scala 3 files, whose errors come from the compiler, get type and method chain hints only in X-Ray mode, as
  in the IDE. Range hints and the editor's indent guides and method separators stay out. The VS Code extension adds
  **Toggle X-Ray** (Ctrl+Alt+X in Scala editors, Cmd+Alt+X on macOS), which flips the setting in the workspace
  settings when they hold it and in the user settings otherwise.
- `scalaAnalyzer/inspect` also answers `inspections`: the display name and group path (such as `Scala`, `General`) of
  each inspection that reported a problem, read from the plugin's registrations and keyed by the `code` of its
  diagnostics. Unused imports are described by the unused-declaration registration, whose name and group they share.
- The VS Code extension lists the results of **Inspect Code** in an **Inspection Results** view in the bottom panel,
  as the IDE's tool window does: group, inspection, file, then problem, with counts. Selecting a problem opens it;
  its buttons apply the problem's quick fix or its suppression, and the view's title bar reruns, clears and collapses.
- Code actions for an inspection problem list, as their `diagnostics`, the client's diagnostics that report it (same
  range, code and message). They include the plugin's suppression actions (`//noinspection <id>` for the statement,
  definition, class or file), previewed like any fix; unused imports are suppressed under `ScalaUnusedImport`. A request
  that carries inspection diagnostics gets their fixes even before the file's diagnostics were computed.
- Inline (`refactor.inline` code actions) for `val`s, `def`s and type aliases that only their own file can see: at the
  declaration or at a usage, **Inline all and remove the declaration**; at a usage of a declaration with other usages,
  also **Inline this occurrence**. They run the plugin's inline processors on a preview copy and are not offered
  where the plugin's handlers refuse, or for members other files can see. The server advertises the `refactor.inline`
  kind.
- Postfix templates are completion items after `expr.`: the plugin's `if`, `else`, `match`, `for`, `var`, `field`,
  `not`, `par`, `cast`, `try`, `null`, `notnull`, `nn`, `println`, `sout`, `prtln`, `return`, `throw`, `assert`,
  `while`, `dowhile`, `Option`, `Seq` and `List`, those that apply to the expression before the dot, shown as snippets
  with their example. Choosing one replaces `expr.key` with the template's expansion and puts the caret where the IDE
  does; where several expressions qualify, the innermost is taken, and a name the IDE asks for is its suggestion.
  Clients that do not apply the server's edits do not get them.
- Code actions offer the plugin's **Implement members**: on a class or trait with abstract members it implements them
  all, previewed on a copy with the plugin's ordering, placement, type annotations and `???` bodies. It is a quick fix
  when the problem's fix is Implement members, a `refactor.rewrite` action otherwise. The preview runs on the host's
  dispatch thread, so a client that resolves code actions (`codeAction/resolve` with `edit`, as VS Code does) gets the
  action without its edit, and the server computes it when the action is chosen, for the document version it was
  offered for; other clients get it previewed, unless the request's `only` excludes its kind. The host now answers the
  plugin's file template manager for the two member body templates.
- A query on the host's dispatch thread observes the cancellation of the request that waits for it, so a client can
  cancel the previews and rename lookups that run there.
- `scalaAnalyzer/overridableMembers` lists the members the IDE's Override Members offers for the class at a position
  (`id`, `signature`, `owner`), and the `scalaAnalyzer.overrideMembers` command overrides the selected ones, taking the
  document's URI, the position, the identifiers and the document version. It applies a versioned edit, is cancelled when
  the document has changed, and fails with `InvalidParams` for an identifier the class does not offer. The VS Code
  extension adds **Override Members…**, a multi-select list followed by the command; it has no default key.
- The plugin's collection inspections that the IDE enables by default (54, such as `filter(...).headOption` to
  `find(...)`, `map(...).flatten` to `flatMap(...)` or `size == 0` to `isEmpty`) run with the other inspections, in
  the editor and in Inspect Code, each with its fix. `InspectionsOracle` replays the plugin's tests of them: 467 agree,
  0 disagree, 107 skip (the five inspections off by default, 13 tests whose hint the oracle cannot evaluate, 3 that
  need Scala 2.10).
- A Scala source's diagnostics come in two publications when its inspections have to run, as the IDE highlights in
  passes: first the syntax, annotator and compiler problems, with the inspection problems of the previous computation
  where the edits since moved them; then all of them, with the inspections' current problems. The inspections of a
  large file can take seconds after each edit (some 5.5 s for a 1900-line file of a real project, against 0.65 s
  without the collection inspections), and before, its compiler errors waited for them.
- Go to Definition no longer fails when the plugin answers a synthetic member with no position in its file; such a
  target has no location, as in the call and type hierarchies.

## 0.2.0 — 2026-11-09

- Sources changed on disk while they are not open in the editor (a checkout, a formatter) take their new text, and a
  source closed without saving returns to its file; before, both kept stale text until the next import.
- Java sources with a call that throws a checked exception get their diagnostics again, with the unhandled exception
  reported; the host lacked an extension point that the check reads.
- **Insert gap with concatenation** is offered inside string literals: its preview moves the caret along the line.
- Code lenses run main classes (objects with a `main` method, `App` objects and Scala 3 `@main` methods) through the
  new `scalaAnalyzer.runMain` command: it compiles the target, launches the class with BSP's run environment and logs
  its output. A test run and a main run share the one active run; the VS Code extension shows a cancellable
  notification while either runs.
- `scalaAnalyzer/buildTargets` lists the build's targets with their identity, Scala version, dependencies, library jars
  and source file count.
- `scalaAnalyzer/compile` compiles the build on request and answers whether it succeeded, with its error and warning
  counts; given a `target` identity, it compiles only that target and counts the problems of that target and its
  dependencies. An unknown target fails with `InvalidParams`.
- `scalaAnalyzer/reimport` reloads the build server's workspace and imports the build again on request.
- `scalaAnalyzer/inspect` runs the selected native inspections over every Scala source of the build, as the IDE's Inspect
  Code does, and answers the number of sources inspected and the problems of those that have some. It reads the
  sources' current buffers, skips generated sources (as the build reports them) and Java sources, publishes nothing,
  is shown as progress and stops when the client cancels it. The VS Code extension adds **Inspect Code** and
  **Clear Inspection Results**; it lists the problems of the files that the live diagnostics do not already show.
- `scalaAnalyzer/typeInfo` answers what the IDE's Type Info action shows for a caret or a selected expression.
- `scalaAnalyzer.completeInsertion` with missing or non-numeric arguments now fails with `InvalidParams`.
- Semantic tokens that start at the same position keep the last one, whatever its length, in a defined order.
- After a test run's compile, the build's compiler diagnostics are republished to the open documents, also when the
  compilation fails.
- Code standardization across all packages, following [CONVENTIONS.md](docs/CONVENTIONS.md); no other behavior change.
- Rename the project to `scala-analyzer`, including the launcher, release archives, VS Code extension,
  Scala/Java package (`scalaanalyzer`), JVM properties and custom protocol identifiers.
- Migration from 0.1.0: install the new `brurdgz.scala-analyzer` extension and remove the old one; update
  `scalaPsiHost.*` settings and commands to `scalaAnalyzer.*`, launcher paths to `bin/scala-analyzer`, and
  `SCALA_PSI_HOST_*` environment variables to `SCALA_ANALYZER_*`. Update any explicit formatter ID too.
  The default cache directory is now `scala-analyzer` under `$XDG_CACHE_HOME` or `~/.cache`; set
  `SCALA_ANALYZER_STATE` to the previous directory to reuse it. Direct JVM clients must use
  `scalaanalyzer.*` properties/packages, and custom LSP clients must use `scalaAnalyzer/state`,
  `scalaAnalyzer/status` and `scalaAnalyzer.completeInsertion`. Old identifiers are not aliases.

## 0.1.0 — 2026-11-01

First stable release for the documented Scala 2/3 and mixed Java workspace scope.

- Native Java completion, resolution/type diagnostics, outlines and source Javadoc hover.
- Selected Scala inspections for deprecated symbols, redundancies, explicit unused imports and local/private
  unused declarations, with suppression comments and previewed native quick fixes.
- Structured Markdown hover: declaration context, parameters/return/throws sections, grouped references,
  tables, lists and fenced examples.
- Safer attached-source handling for unsupported file types, plus in-memory file gists for Java documentation.
- Cancellable reads, coalesced background diagnostics and version guards for editor changes.
- CI validates the packaged server and installed VSIX on Linux x64 and macOS Intel/Apple Silicon. Numeric
  tags publish checked archives, VSIX, SHA-256 checksums and build metadata to GitHub Releases.

## Development changes before 0.1.0

The changes milestone: rename, previewable code actions, organize imports and configured scalafmt formatting.
The implementation is bounded by the documented preview and search limits.

- Rename through the plugin's processors, including companions and related Scala members. Source text and indexes
  are restored before versioned edits return to the editor. Invalid names and conflicts fail the request. File moves
  and optional automatic renames remain outside this operation; Java inheritor search remains a known gap.
- Code actions from plugin intentions, annotation quick fixes and Scala 2/3 surrounders. Preview and mod-command
  copies share the change engine's document commits and cleanup. Type-import fixes offer each qualified candidate
  separately; actions requiring dialogs, other files or unsupported previews are unavailable. Global-member/conversion imports remain outside that milestone.
- `source.organizeImports` runs the plugin optimizer on a preview copy and returns versioned edits. Source actions
  are computed only when requested and can be combined with contextual quick fixes.
- Whole-file formatting honors the nearest workspace `.scalafmt.conf` and its declared version, including exclusion
  filters and relative configuration includes. Formatter dependencies are downloaded from Maven Central only when
  absent from the host's disk cache. Invalid configs fail visibly. Without a config, and for range requests, the
  IntelliJ formatter remains in use.
- Verification: 108 passing tests with a fresh state/cache directory; configured formatting through the rebuilt
  distribution over stdio. Oracles: rename 112/1/18, code actions 63/0/12, organize imports 53/1/7, IntelliJ formatter
  187/0/0 and selected scalafmt contracts 8/0/0 (agree/disagree/skip). Remaining disagreements and replay limits are
  documented in README.

## 0.1.0-b2 — 2026-10-30

Beta 2: completion, signature help, selection ranges, formatting and inlay hints from the Scala plugin itself; the
alpha 2 gaps closed (no memory held per edit; korolev agrees with the plugin's own project test). Inspections as
diagnostics and Scala 3 oracle fixtures are not part of it.

- Faster completion: a suggestion whose weigher fails no longer makes the others be weighed again, and index
  lookups filter postings by file without navigating PSI. First pass p95 300 to 261 ms, p99 988 to 428 ms.
- Selection ranges from the platform's and Scala plugin's word-selection handlers: each parent replays the next
  Extend Selection step, with the plugin's layered lexer and default editor settings. The selection oracle agrees
  on 38 cases; one ScalaDoc fixture expects an extra step before the final newline.
- Inlay hints from the plugin's hints pass: definition types, parameter names, implicit and `using` arguments,
  conversions, method chains and type arguments, within the requested range. Uses the plugin's settings; range
  hints require the IDE's concrete editor and are disabled. The distribution includes a small platform slice rather
  than the full `ide-impl` jar.
- Go to definition and type definition into libraries: their attached sources (the build's source jars, the JDK's
  `src.zip`), or their decompiled class files.
- Go to type definition, as the IDE's "go to type declaration".
- Document highlights: a symbol's occurrences in the file, as reads or writes.
- Document symbols, from the plugin's structure view.
- Folding ranges, from the plugin's folding builder (with `//region` and `<editor-fold>` regions).
- Semantic tokens: identifiers and soft keywords colored as the IDE colors them.
- Build changes: the build is imported again after a change to its definition, or when source files are created or
  deleted.
- Observability: a log (sent to the editor and written to a file) of startup, import, slow requests and the
  platform's warnings and errors; progress while the project opens; an error message when it cannot; the server's
  state (`scalaPsiHost/state`) and a status report (`scalaPsiHost/status`). The VS Code extension shows the state in
  the status bar and has *Show Status* and *Show Log File* commands.
- Memory: the platform's reparse copies of edited files are no longer kept, and lookups share one project-wide
  scope instead of piling up a new one each in the plugin's caches: the heap no longer grows with edits.
- Resolution as the IDE's scopes: a module sees its own resolved classpath (not the replaced library versions of the
  modules it depends on), a library's classes resolve in the modules that have the library, synthetic files keep
  their declaration's scope, and packages that sources declare do not leak other modules' subpackages. On korolev the
  host agrees with the plugin's project test.
- Lifecycle: the server ends with its client (its input ending, or its process exiting when the server can see it)
  and leaves the build's sbt server running for its other clients.
- Formatting and completion's insert handlers no longer stop the other requests: they change a copy of the file
  under a read, as the IDE's intention preview does.

## 0.1.0-a1 — 2026-10-28

First public alpha: a language server for sbt (or any BSP) builds, Scala 2 and 3, with Java sources in mixed
projects, built on the Scala plugin for IntelliJ IDEA (intellij-scala `491569803c`, platform 263.7307.44).

- Diagnostics: the plugin's highlighting; for Scala 3 by default, the module's own compiler on the document's
  current text.
- Hover: the signature of the symbol, or of the declaration, under the cursor.
- Go to definition, for declarations in the project's sources.
- A minimal VS Code extension (`editors/vscode`), and a distribution with a launcher script (`sbt dist`).
