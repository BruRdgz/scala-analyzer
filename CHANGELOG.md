# Changelog

## Unreleased

- `scalaAnalyzer/buildTargets` lists the build's targets with their identity, Scala version, dependencies, library jars
  and source file count.
- `scalaAnalyzer/compile` compiles the build on request and answers whether it succeeded, with its error and warning
  counts; given a `target` identity, it compiles only that target.
- `scalaAnalyzer/reimport` reloads the build server's workspace and imports the build again on request.
- `scalaAnalyzer/typeInfo` answers what the IDE's Type Info action shows for a caret or a selected expression.
- `scalaAnalyzer.completeInsertion` with missing or non-numeric arguments now fails with `InvalidParams`.
- Semantic tokens that start at the same position keep the last one, whatever its length, in a defined order.
- After a test run's compile, the build's compiler diagnostics are republished to the open documents, also when the
  compilation fails.
- Code standardization across all packages, following [CONVENTIONS.md](CONVENTIONS.md); no other behavior change.
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
