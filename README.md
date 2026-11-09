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
| Completion | Automatic imports, insertion handlers, signature help and inlay hints. |
| Navigation | Definitions, type definitions, references, implementations, call/type hierarchies and workspace symbols; library sources and decompiled classes. |
| Documentation | Markdown hover with declaration context, parameters, return values and linked references. |
| Editing | Rename, organize imports, previewable quick fixes, intentions and surround actions. |
| Formatting | Whole-file scalafmt or IntelliJ formatting; range formatting through IntelliJ. |
| Tests | Run ScalaTest, specs2 and MUnit suites/tests, and uTest suites, from code lenses; readable output and a result summary. |
| Run | Run objects with a `main` method, `App` objects and Scala 3 `@main` methods from code lenses; output and an exit summary. |
| Code structure | Semantic tokens, document highlights, outlines, folding and selection ranges. |

See [known limitations](#known-limitations) for the supported boundaries of these features.

## Requirements

| Requirement | Support |
| --- | --- |
| Runtime | **JDK 25** for the language server. |
| Platforms | Linux x64, macOS Intel and macOS Apple Silicon. |
| Languages | Scala 2 and Scala 3; partial Java support in mixed Scala/Java projects. |
| Build import | A BSP connection file under `.bsp/`; integration tests use sbt builds. |
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

## Configuration

### Server environment

| Variable | Purpose | Default |
| --- | --- | --- |
| `JAVA_HOME` | JDK used to run the server | `java` on `PATH` |
| `SCALA_ANALYZER_STATE` | State, logs, formatter cache and library source files | `$XDG_CACHE_HOME/scala-analyzer`, or `~/.cache/scala-analyzer` |
| `SCALA_ANALYZER_HEAP` | Maximum JVM heap | `2g` |
| `SCALA_ANALYZER_LOG` | Set to `debug` to log every request's timing | Informational logging |

### Formatting and build changes

Whole-file formatting uses the nearest `.scalafmt.conf` between the source file and workspace root. The configuration
must declare a formatter version; missing dependencies are downloaded from Maven Central and cached locally.
Project exclusion filters apply. Without a config, and for range requests, the IntelliJ formatter is used.

When the client supports dynamic file watchers, changes to sbt build definitions and source-file creation or deletion
trigger a reimport. Unsaved source text is preserved.

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

Some searches, inspections and editor actions have a narrower scope than the full IntelliJ IDE. Mixed Java support
is partial, indexes are kept in memory, and each process serves one workspace.

<details>
<summary><strong>Search, editing, performance and recovery details</strong></summary>

- References and implementations use text-based searches without compiler indexes. Implicit/SAM usages and Java
  inheritors can be missed. Rename can leave Java overrides unchanged. Call and type hierarchies list project
  callers and subtypes only; outgoing calls include overriding methods, as in the IDE, without call-site ranges.
  Java constructor callers can be missed.
- Definition navigation can be absent on case-class construction calls; CI exercises ordinary source-member
  navigation through the packaged server and installed extension.
- Java binary short-name lookup is incomplete, and JDK symbols come
  from the JDK running the server.
- Native inspections cover deprecation, selected redundancies, explicit unused imports and unannotated
  declarations confined to the file. Wildcard/given import warnings and whole-project dead-code analysis are
  outside this scope. Inspect Code (`scalaAnalyzer/inspect`) runs only these inspections, on the Scala sources that
  the build does not mark as generated; it does not report syntax, type or compiler errors. The VS Code extension
  shows its result in a separate list and omits files whose live diagnostics are already shown; other clients receive
  every file's problems and must avoid showing them twice. Dialog-dependent actions, file/package moves and optional automatic renames are unavailable;
  code actions are limited to current-file previews.
- Some completion contributors require IDE services that are unavailable. Live templates have no snippet tab stops;
  completion followed by handler edits takes two undo steps.
- Inlay hints are text labels without the IDE's interactive presentation or range hints. Project IDE code-style
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
git -C ../intellij-scala checkout d2b6e2aed33eaa02ab4b0c4a0db7fe5f3db1202f
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

For planned work, see the [roadmap](ROADMAP.md). For release history, see the [changelog](CHANGELOG.md).

## License and acknowledgements

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.

scala-analyzer builds on the [Scala plugin for IntelliJ IDEA](https://github.com/JetBrains/intellij-scala) and the
[IntelliJ Platform](https://github.com/JetBrains/intellij-community) by JetBrains. It is not affiliated with or
endorsed by JetBrains. IntelliJ and IntelliJ IDEA are trademarks of JetBrains s.r.o.
