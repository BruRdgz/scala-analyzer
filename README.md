<h1 align="center">scala-analyzer</h1>

<p align="center">
  <strong>A Scala language server powered by the IntelliJ Scala plugin.</strong><br>
  Completion, diagnostics, navigation and refactoring in your LSP editor.
</p>

<p align="center">
  <a href="https://github.com/BruRdgz/scala-analyzer/actions/workflows/ci.yml"><img src="https://github.com/BruRdgz/scala-analyzer/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="https://github.com/BruRdgz/scala-analyzer/releases"><img src="https://img.shields.io/github/v/release/BruRdgz/scala-analyzer?label=release" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-blue" alt="License: Apache 2.0"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/JDK-25-orange" alt="JDK 25 required"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/Scala-2_%7C_3-red" alt="Scala 2 and 3"></a>
</p>

<p align="center">
  <a href="#getting-started">Getting started</a> ·
  <a href="#features">Features</a> ·
  <a href="#configuration">Configuration</a> ·
  <a href="#known-limitations">Limitations</a> ·
  <a href="#development">Development</a>
</p>

scala-analyzer runs the IntelliJ Scala plugin in a standalone process, without the IntelliJ application container.
It exposes editor features through the **Language Server Protocol (LSP)** and imports projects through the
**Build Server Protocol (BSP)**. A VS Code extension is included; other editors can connect over stdio.

> **Renaming from scala-psi-host:** release 0.1.0 artifacts retain the old name. The instructions below describe
> the renamed distribution. Existing users should follow the [migration notes](CHANGELOG.md#020--2026-11-09).

## Features

| Area | Capabilities |
| --- | --- |
| Diagnostics | Scala plugin and compiler diagnostics; current-buffer compilation for Scala 3; selected native inspections, also over the whole project (Inspect Code, listed by group, inspection and file in VS Code's Inspection Results view). |
| Completion | Automatic imports, insertion handlers, postfix templates (`expr.if`, `.match`, `.for`, `.var`, `.not`...) and signature help. |
| Inlay hints | Inferred types, parameter names, implicit arguments and conversions, method chains. Type names are links to their declarations, an inferred definition type can be written into the source, and `scalaAnalyzer.inlayHints.*` selects the kinds shown. |
| Navigation | Definitions (through the plugin's Go to Declaration), type definitions, references, implementations, go to super, go to test (from a class to its tests and back), call/type hierarchies and workspace symbols; library sources and decompiled classes. |
| Documentation | Markdown hover with declaration context, parameters, return values and linked references. |
| Editing | Rename, organize imports, previewable quick fixes, intentions, surround and unwrap actions; implement and override members; split/join lists and flip comma; Inline for `val`s, `def`s and type aliases; Extract to val and Extract method. |
| Formatting | Whole-file scalafmt or IntelliJ formatting; range formatting through IntelliJ. |
| Tests | Run ScalaTest, specs2 and MUnit suites/tests, and uTest suites, from code lenses; readable output and a result summary. |
| Run | Run objects with a `main` method, `App` objects and Scala 3 `@main` methods from code lenses; output and an exit summary. |
| Code structure | Semantic tokens, document highlights (including a method's exit points, companions and implicit usages), outlines, folding and selection ranges. |

See [known limitations](#known-limitations) for the supported boundaries of these features.

## Requirements

| Requirement | Support |
| --- | --- |
| Runtime | **JDK 25** for the language server. |
| Platforms | Linux x64, macOS Intel and macOS Apple Silicon. |
| Languages | Scala 2 and Scala 3; Java in mixed Scala/Java projects, and in Maven and Gradle projects with Java sources only. Java support has the [limits listed below](#known-limitations). |
| Build import | A BSP connection file under `.bsp/` (sbt), or a Maven or Gradle build imported through [Bloop](https://scalacenter.github.io/bloop/), which must be on the `PATH`. |
| Editors | The included VS Code extension, or an LSP client that launches the server over stdio. |

The server uses one workspace root per process. Your project's build can use its own required JDK.

## Getting started

### 1. Install the server and editor client

Download the **server archive**, **VSIX**, **build-info.json** and **SHA256SUMS** from the same
[GitHub release](https://github.com/BruRdgz/scala-analyzer/releases). In the download directory, verify the files:

```sh
shasum -a 256 -c SHA256SUMS
```

Extract the server archive and keep its `bin/` and `lib/` directories together. The same archive serves all supported
platforms. Install JDK 25 separately; the distribution does not bundle a JDK.

For VS Code, use **Extensions: Install from VSIX...** to install the downloaded extension. The extension requires
the server to be installed separately and does not download it automatically.

### 2. Prepare your project

For sbt, run this in your Scala project's root, using the JDK required by that build:

```sh
sbt bspConfig
```

This creates the BSP connection under `.bsp/`. The server expects one connection file.

For Maven and Gradle, install [Bloop](https://scalacenter.github.io/bloop/) so that `bloop` is on the `PATH`, and
open the project's root. The root must hold either a `pom.xml` (Maven) or a `settings.gradle`, `settings.gradle.kts`,
`build.gradle` or `build.gradle.kts` (Gradle), not both, and no `.bsp/` connection files: those take precedence. The
server imports the project the way an IDE does:

- It runs the build tool's Bloop install step in the root: `./mvnw` or `mvn` with `ch.epfl.scala:bloop-maven-plugin`'s
  `bloopInstall`, or `./gradlew` or `gradle` with an init script that applies the Bloop Gradle plugin. Your build files
  are not edited, and the wrappers are used when they are executable files in the root. This writes a `.bloop/`
  directory into the project, which you can ignore in version control, and needs network access the first time to
  fetch the plugin.
- It starts `bloop bsp` and connects to it over a Unix socket. The Bloop server keeps running after the editor closes,
  like sbt's; stop it with `bloop exit`.
- When a `pom.xml`, a Gradle script, `gradle.properties` or `gradle/libs.versions.toml` changes, it runs the install
  step again and imports the project again.

The build tool runs with the server's environment. To run it on another JDK than the server's JDK 25, which older
Gradle versions cannot run on, set `SCALA_ANALYZER_BUILD_JAVA_HOME` to that JDK's home; it becomes the tool's
`JAVA_HOME`. The VS Code setting `scalaAnalyzer.buildJavaHome` sets it, and when it is empty the extension passes the
`JAVA_HOME` that VS Code was started with.

### 3. Connect your editor

**VS Code:** add the following to your settings, adjusting both paths:

```json
{
  "scalaAnalyzer.serverPath": "/absolute/path/to/scala-analyzer/bin/scala-analyzer",
  "scalaAnalyzer.javaHome": "/absolute/path/to/jdk-25"
}
```

Open the Scala project and a Scala source file. The status bar reports import progress and server readiness.
See the [VS Code client documentation](editors/vscode/README.md) for more details.

**Other LSP editors:** configure your client to launch `bin/scala-analyzer` over stdio, with `JAVA_HOME` pointing
to JDK 25. Completion follow-up edits require `workspace/applyEdit`; caret placement also requires
`window/showDocument`.

### Use with AI agents (MCP)

With `--mcp`, the same binary speaks the [Model Context Protocol](https://modelcontextprotocol.io) over stdio instead
of LSP, so that an AI agent can ask about the project by symbol name. Register it with your MCP client as a stdio
server whose command is `bin/scala-analyzer` and whose arguments are `--mcp --root /absolute/path/to/your/project`
(`--root` defaults to the working directory), with `JAVA_HOME` pointing to JDK 25. The project needs the BSP
connection described in step 2. The server starts importing at once; the tools answer that the project is still
importing until it is ready.

| Tool | Purpose |
| --- | --- |
| `project_status` | The import state, module and source counts, the workspace root and the log file. Answers while the project imports. |
| `find_symbol` | Declarations by name, optionally qualified by package or enclosing type (`core.Greeting`, `Greeting.hello`), with file, line and column (counting from 1). |
| `definition` | Where the symbol at the target is declared, in the project or a library. |
| `references` | The usages of the symbol at the target, without its declaration; for a type named by `symbol`, also those of its companion object. |
| `implementations` | The implementations and overriders of a trait, class or method. |
| `callers`, `callees` | The calls of a method, each at its call site with the declaration that makes it; and the methods it calls, the project's first, then the libraries' by qualified name. One level. |
| `supertypes`, `subtypes` | The direct supertypes and subtypes of a class, trait or object; one level. |
| `type_of` | The type of a symbol or of an expression, as the IDE's Type Info. |
| `describe` | The signature and documentation the IDE shows on hover, in Markdown. |
| `outline` | The declarations of a file as an indented tree, with the line of each name. Takes `file` only. |
| `diagnostics` | The problems the editor shows, for a `file` or for every source, as `path:line:column  severity: message [source]`: the Scala plugin's annotator and inspections, plus the compiler's diagnostics of the current text for Scala 3 sources. Scala 2 compiler errors come only from a build compile, which the server does not run, so Scala 2 and Java sources have the plugin's own analysis only. |
| `inspect` | The IDE's Inspect Code over a Scala `file` or every Scala source (generated ones excluded): the selected inspections only, each labelled with its name. |

Every tool but `project_status`, `find_symbol`, `outline`, `diagnostics` and `inspect` names its target in one of two
ways: `symbol`, a name qualified as far as needed to be unambiguous (`core.Greeting`, `Greeting.text`; the answer lists
the candidates when it is not, and the name of a type that has a companion object means the type), or `file`, `line` and `text`, the identifier (for `type_of`, the expression) as written
on that line of a project source, where its first occurrence on the line, not inside a longer identifier, is the
target. The tools that list their
findings take an optional `limit` (default 100; 200 for `diagnostics` and `inspect`, which also take `severity`, the
least severe level reported: `error`, `warning` (default) or `information`), and show each location as
`path:line:column` and its source line. Every tool is annotated as read-only, so a client may run it without asking.

The tools only read. The server follows the files edited on disk, checked before each call: it takes the new text of
changed sources, and imports the build again when sources are created or deleted or the build definition changes.

## Configuration

### Server environment

| Variable | Purpose | Default |
| --- | --- | --- |
| `JAVA_HOME` | JDK used to run the server | `java` on `PATH` |
| `SCALA_ANALYZER_STATE` | State, logs, formatter cache, library source files and the library index cache | `$XDG_CACHE_HOME/scala-analyzer`, or `~/.cache/scala-analyzer` |
| `SCALA_ANALYZER_BUILD_JAVA_HOME` | JDK that runs Maven and Gradle for the import (set as the tool's `JAVA_HOME`) | The server's environment |
| `SCALA_ANALYZER_HEAP` | Maximum JVM heap | `2g` |
| `SCALA_ANALYZER_LOG` | Set to `debug` to log every request's timing, each file's inspection times and each library jar's cache use | Informational logging |

### Formatting and build changes

Whole-file formatting uses the nearest `.scalafmt.conf` between the source file and workspace root. The configuration
must declare a formatter version; missing dependencies are downloaded from Maven Central and cached locally.
Project exclusion filters apply. Without a config, and for range requests, the IntelliJ formatter is used.

When the client supports dynamic file watchers, changes to build definitions (sbt files, `pom.xml`, Gradle scripts,
`gradle.properties` and `gradle/libs.versions.toml`) and source-file creation or deletion trigger a reimport. Unsaved source text is preserved.

### Running tests

Test lenses compile their BSP target and launch the Scala plugin's runner in a separate JVM. The build server must
support `buildTarget/jvmTestEnvironment`; its classpath, JVM options, environment variables and working directory
are used. The executable comes from the target's BSP Java home. When BSP omits it, the host's running JVM is used
deliberately, so the server JDK must also be compatible with that project's tests.

Results and failure details appear in the editor's output channel, with a summary notification when execution ends.
One run, of tests or of a main class, is allowed at a time; cancelling the execute-command request kills its runner
(in VS Code, the cancel button of the running notification). Compilation reads saved files, not unsaved editor
buffers. MUnit runs through the JUnit 4 it depends on, with scala-analyzer's own small runner: the
plugin's needs IntelliJ's JUnit runtime.

### Running main classes

Main lenses sit on top-level objects that have a `main` method of their own or an inherited one (`App`), and on Scala 3
`@main` methods. They compile the target and launch the class in a separate JVM like test runs do, with the classpath,
JVM options, environment variables and working directory of BSP's `buildTarget/jvmRunEnvironment`, which the build
server must support. The output goes to the editor's output channel and a notification tells how the program exited.
The program gets no arguments and no standard input, and shares the single active run with the tests, so a program
that keeps running blocks the next run until it is cancelled. Worksheets and scripts have no lenses.

### Troubleshooting

In VS Code, run **scala-analyzer: Show Status** to inspect the server or **scala-analyzer: Show Log File** to open its current log.
Set `scalaAnalyzer.debugLog` to `true` before starting the server for request timing logs.

Logs are sent to the editor and written under `<state-dir>/logs/`. The latest ten log files are retained.
If a reimport fails after host updates have begun, restart the server; see the recovery limits below.

## Known limitations

Some searches, inspections and editor actions have a narrower scope than the full IntelliJ IDE. Java support is
partial, indexes are kept in memory, and each process serves one workspace.

<details>
<summary><strong>Search, editing, performance and recovery details</strong></summary>

- References and implementations use text-based searches without compiler indexes. Implicit/SAM usages can be
  missed, and references leave out text occurrences (a name in comments and strings). Rename can leave Java
  overrides unchanged. Call and type hierarchies list project
  callers and subtypes only; outgoing calls include overriding methods, as in the IDE, without call-site ranges.
  Java constructor callers can be missed.
- CI exercises ordinary source-member navigation through the packaged server and installed extension.
- JDK symbols come from the JDK running the server.
- Native inspections cover deprecation, selected redundancies, explicit unused imports, unannotated
  declarations confined to the file, and the collection inspections that the IDE enables by default.
  Wildcard/given import warnings and whole-project dead-code analysis are outside this scope. Inspect Code
  (`scalaAnalyzer/inspect`) runs only these inspections, on the Scala sources that the build does not mark as
  generated; it does not report syntax, type or compiler errors. The VS Code extension shows its result in a separate
  list and omits files whose live diagnostics are already shown; other clients receive every file's problems and
  must avoid showing them twice. Dialog-dependent actions, file/package moves and optional automatic renames are
  unavailable; code actions are limited to current-file previews.
- Unwrap actions are offered with every code-action request at the caret, in the order of the IDE's Unwrap popup,
  without its highlighting of the affected code. Change access modifier is not offered: it needs the IDE's rename UI.
- Inline (`refactor.inline`) is offered for a `val`, a `def` or a type alias that only its own file can see (a local
  or a private member), at the declaration or at a usage, as two actions: inline every usage and remove the
  declaration, or inline this usage only. As in the IDE, it is not offered where the plugin refuses (for example a
  recursive or generic method, a method used without a call, a `val` of a function type); `var`s are left out too. A
  public or `protected` member, a `private[pkg]` one and a Scala 3 top-level private one are left out even when no
  other file uses them: Inline of a member used in several files is unavailable. The IDE's choice to keep the declaration after
  inlining every usage is not offered.
- Extract to val and Extract method (`refactor.extract`) are offered for a selection. They are the plugin's Introduce
  Variable and Extract Method with the IDE's defaults, without their dialogs. Extract to val takes the first name the
  plugin suggests and the explicit type its setting asks for (as the IDE's dialog does and its in-place editor does
  not), and replaces all the occurrences of the expression or, when it has others, this one only. Extract method takes
  the scope the plugin picks, replaces the duplicates of the selection (the plugin's unit-test path does, where the
  IDE asks) and is named `extracted`, which the plugin's dialog leaves for the user to type. Neither action carries an
  edit: choosing one runs the server command `scalaAnalyzer.extract`, which computes the edit when it is chosen, sends it with `workspace/applyEdit` and answers
  with the position of the new name (or `null`); the VS Code extension then starts a rename there, and other clients
  just get the edit. Nothing is offered where the plugin's checks refuse the selection, but Extract method may still
  find no scope to extract to when it runs, and then changes nothing. Extract method is not offered in a file that
  uses the plugin's unit-test name `testMethodName`, or starts with the comments `//case class` or `//inner class`,
  nor from the last member of a Scala 3 class body without braces, where the plugin fails to insert the method.
  Introduce Type Alias for a selected type is not offered.
- Some completion contributors require IDE services that are unavailable. Live templates, postfix templates among
  them, have no snippet tab stops: the caret goes where their end is; completion followed by handler edits takes two
  undo steps.
- Inlay hints are text labels without the IDE's interactive presentation or range hints (`1 to 5`). Their settings
  are the host's, read from `workspace/configuration` (`scalaAnalyzer.inlayHints.*`) by a client that answers it; the
  IDE's hint length and per-method parameter-name exclusions are not offered. X-Ray mode is a setting
  (`scalaAnalyzer.inlayHints.xRay`, off by default; the VS Code extension's **Toggle X-Ray** flips it) rather than a
  held key, with the plugin's default X-Ray hints but not its editor changes (indent guides, method separators). In
  Scala 3 files, whose errors come from the compiler, the plugin shows type and method chain hints only in X-Ray mode,
  as in the IDE. Label parts get their
  locations and tooltips from `inlayHint/resolve`, so a client without resolve support shows plain labels, and a
  type written from a hint is the hint's text: it adds no import. Project IDE code-style
  settings are not imported, and range formatting uses the IntelliJ formatter even when scalafmt is configured.
- One workspace is served per process. Shared sources assigned to multiple BSP targets are rejected as ambiguous;
  `.sbt` build-definition files are not treated as editable Scala sources.
- Indexes are in memory. Initial imports and checks can be expensive; document requests run in order, so an import
  or slow request can delay later requests.
- A reimport failure before host updates preserves the previous snapshot. A failure after updates begin requires
  restarting the server.

</details>

## Development

### Build from source

Requires **Git, sbt and JDK 25**. From this repository's root, build the pinned Scala plugin in a sibling checkout,
then build the server distribution:

```sh
export JAVA_HOME=/absolute/path/to/jdk-25

git clone https://github.com/JetBrains/intellij-scala ../intellij-scala
git -C ../intellij-scala checkout 79602300bb391187ee6c79890082706bdf9b6ad0
(
  cd ../intellij-scala
  sbt --java-home "$JAVA_HOME" packageArtifactZip
)

sbt --java-home "$JAVA_HOME" dist
```

The output is `target/dist/`, with the launcher at `target/dist/bin/scala-analyzer`. Keep `bin/` and `lib/` together
when moving the distribution.

The IntelliJ Platform version and Scala plugin revision are pinned in [build.sbt](build.sbt). By default, the build
reads jars from `../intellij-scala/target/plugin/Scala/lib` and verifies the checkout's revision. Set
`SCALA_PLUGIN_LIB` to use another plugin build's `Scala/lib` directory. This skips the revision check; the supplied
jars must still match the host's platform dependencies.

### Run checks

Generate the fixture's BSP connection once, then run the tests and Scaladoc from the repository root:

```sh
(
  cd fixtures/bsp-project
  sbt --java-home "$JAVA_HOME" bspConfig
)
sbt --java-home "$JAVA_HOME" test 'Compile/doc'
```

The suite covers PSI semantics, module resolution, editing, BSP imports, formatting, LSP requests and the reviewed
platform-adapter surface. Additional [oracle tools](src/test/scala/scalaanalyzer/oracle) compare selected upstream
plugin fixtures; their source files document the supported inputs.

To check a built distribution over stdio with Python 3:

```sh
python3 dist/smoke-test.py \
  target/dist/bin/scala-analyzer \
  fixtures/bsp-project \
  fixtures/bsp-project/app/src/main/scala/app/Main.scala \
  6 53
```

This checks initialization, the server name, document opening, diagnostics, hover, definition and shutdown.
Positions are zero-based.

### Release verification

[CI](.github/workflows/build.yml) builds the pinned upstream plugin, runs the suite and Scaladoc checks, packages
the server and VSIX, and exercises both the extracted server and installed extension on the supported platforms.
Numeric version tags (for example, `0.1.0`) publish the exact checked artifacts to GitHub Releases.

`version.sbt` and the VSIX manifest must agree. Each release includes `build-info.json` with source/dependency
coordinates and packaged-file hashes, plus `SHA256SUMS`. The workflow does not publish to the VS Code Marketplace.

For planned work, see the [roadmap](docs/ROADMAP.md). For release history, see the [changelog](CHANGELOG.md).

## License and acknowledgements

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.

scala-analyzer builds on the [Scala plugin for IntelliJ IDEA](https://github.com/JetBrains/intellij-scala) and the
[IntelliJ Platform](https://github.com/JetBrains/intellij-community) by JetBrains. It is not affiliated with or
endorsed by JetBrains. IntelliJ and IntelliJ IDEA are trademarks of JetBrains s.r.o.
