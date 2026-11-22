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
| Diagnostics | Scala plugin and compiler diagnostics; current-buffer compilation for Scala 3; selected native inspections and, in Java files, the Java plugin's default inspections, also over the whole project (Inspect Code, listed by group, inspection and file in VS Code's Inspection Results view). |
| Completion | Automatic imports, insertion handlers, postfix templates (`expr.if`, `.match`, `.for`, `.var`, `.not`...) and signature help (also in Java files). |
| Inlay hints | Inferred types, parameter names, implicit arguments and conversions, method chains. Type names are links to their declarations, an inferred definition type can be written into the source, and `scalaAnalyzer.inlayHints.*` selects the kinds shown. Java files get the IDE's parameter-name hints, with its default options. |
| Navigation | Definitions (through the plugin's Go to Declaration), type definitions, references, implementations, go to super, go to test (from a class to its tests and back), call/type hierarchies and workspace symbols, in Scala and Java files; library sources and decompiled classes. |
| Documentation | Markdown hover with declaration context, parameters, return values and linked references. |
| Editing | Rename and references (also across Java files), organize imports (also in Java files), previewable quick fixes and intentions (Java files get the Java plugin's error and inspection fixes and intentions), surround and unwrap actions (also in Java files); implement and override members (also in Java files); split/join lists and flip comma (also in Java files); Inline for `val`s, `def`s and type aliases; Extract to val and Extract method; in Java files, Extract variable and Inline variable. |
| Formatting | Whole-file scalafmt or IntelliJ formatting; range formatting through IntelliJ. Java files always use the IntelliJ formatter. |
| Tests | Run ScalaTest, specs2 and MUnit suites/tests, uTest suites, and JUnit 4 and 5 classes and methods in Java files, from code lenses; readable output and a result summary. |
| Run | Run objects with a `main` method, `App` objects, Scala 3 `@main` methods and Java main classes from code lenses; output and an exit summary. |
| Debugging | Debug main classes and test suites or tests from code lenses or launch configurations (VS Code): breakpoints, stepping, variables and expression evaluation in Scala and Java, through Scala Center's debug adapter; attach to a JVM that already runs. |
| Code structure | Semantic tokens and selection ranges (also in Java files), document highlights (including a method's exit points, companions and implicit usages), outlines and folding. |

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
| `diagnostics` | The problems the editor shows, for a `file` or for every source, as `path:line:column  severity: message [source]`: the Scala plugin's annotator and inspections, plus the compiler's diagnostics of the current text for Scala 3 sources. Scala 2 compiler errors come only from a build compile, which the server does not run, so Scala 2 and Java sources have the plugin's own analysis only (for Java, its errors and default inspections). |
| `inspect` | The IDE's Inspect Code over a Scala or Java `file` or every Scala and Java source (generated ones excluded): the selected inspections only, each labelled with its name. |

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
Project exclusion filters apply. Without a config, for range requests and for Java files, the IntelliJ formatter is used.

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

JUnit 4 and 5 classes and test methods in Java files have lenses as the IDE's run markers (a `@Nested` class and its
methods have their own; an abstract class has none). They run through the IDE's own `JUnitStarter`, with the runner the
IDE chooses for the class (JUnit 5 for a Jupiter class, JUnit 4 for the rest; JUnit 6 classes run on the JUnit 5
runner), and the same summary as the Scala runs. JUnit 5 needs `org.junit.platform:junit-platform-launcher` on the
test classpath, which the IDE downloads when it is missing and the server does not: add it as a test dependency
(Maven: `junit-platform-launcher`, test scope; Gradle: `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`),
or the run fails with a message that names it.

### Running main classes

Main lenses sit on top-level objects that have a `main` method of their own or an inherited one (`App`), on Scala 3
`@main` methods, and on the name of each Java class the JVM can launch that has a main method, static or (Java 21+)
an instance one, as the IDE's run markers do (a static nested class runs by its binary name, `Outer$Nested`). They compile the target and launch the class in a separate JVM like test runs do, with the classpath,
JVM options, environment variables and working directory of BSP's `buildTarget/jvmRunEnvironment`, which the build
server must support. The output goes to the editor's output channel and a notification tells how the program exited.
The program gets no arguments and no standard input, and shares the single active run with the tests, so a program
that keeps running blocks the next run until it is cancelled. Worksheets and scripts have no lenses.

### Debugging

Each Run lens has a **Debug** lens beside it (Debug main, Debug suite, Debug test). It compiles the target, launches
the program or the test runner exactly as the run does, with a JDWP agent added, and connects the editor to the
debug adapter the server runs for it (Scala Center's scala-debug-adapter, as Metals uses it when its build server
cannot debug). Breakpoints, stepping, the stack, variables and expression evaluation work in Scala and Java sources,
and the library frames show their sources when a source archive is known: the server unpacks it once into
`debug-sources/` under its state directory, so that the frame is a file the editor opens (an unpacked archive no
session has used for a month is deleted). A debug session does not take the single
active run: it ends when the editor disconnects or the program exits, and the server ends the ones that are left when
it stops. The program's output goes to the editor's debug console.

In VS Code, the lenses start the `scala-analyzer` debugger ("Scala (scala-analyzer)"), which a `launch.json`
configuration can start as well. Its attributes:

| Attribute | Meaning |
| --- | --- |
| `buildTarget` | Required. The target to compile and debug, as the server names it: the workspace's file URI and the target id, such as `file:///project#app/Compile` for a main class and `file:///project#core/Test` for tests. |
| `mainClass` | The class with the main method, by its JVM name. |
| `testClass`, `framework`, `testName` | A test suite (for JUnit, the class's binary name), its framework (`junit4`, `junit5`, `munit`, `scalatest`, `specs2`, `utest`) and optionally the test, as the Run test lens names it. Use these or `mainClass`. |
| `args` | The arguments of `mainClass`. A test takes none: the runner's arguments are the selection. |
| `jvmOptions` | JVM options added after the build's, such as `-Xmx2g` or `-Dkey=value`. |
| `env` | Environment variables added to the build's; one of the same name replaces the build's. |

Without a `launch.json`, F5 in a Scala or Java file debugs what the file's Debug lens would: its main class or test,
or the one picked from a list when the file has several.

To debug a JVM that already runs, start the program with a JDWP agent, for example
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` (`JAVA_TOOL_OPTIONS` or `sbt`'s `javaOptions`
carry it too), and start an `attach` configuration. The server compiles `buildTarget` so that the frames find their
sources, and ends the session, never the program, when the editor disconnects:

```json
{ "type": "scala-analyzer", "request": "attach", "name": "Attach", "buildTarget": "file:///project#app/Compile", "hostName": "localhost", "port": 5005 }
```

`hostName` defaults to `localhost`. The program must be built from the sources the target compiles; the target's
classpath is what the debugger knows of it.

Other editors start a session with the server command `scalaAnalyzer.startDebugSession`. Its one argument is the
object of `scalaAnalyzer.runMain` (`target`, `mainClass`) or `scalaAnalyzer.runTest` (`target`, `framework`, `suite`,
and optionally `testName`) with a `kind` member, `"main"` or `"test"`, and optionally `jvmOptions` and `env` (and, for a
main class, `args`) as in `launch.json`; or `{"kind": "attach", "target", "hostName",
"port"}` for a JVM that already runs with a JDWP agent listening there (the client then sends the DAP `attach` request
with the same host and port). The request compiles the target and answers
`{"uri": "tcp://127.0.0.1:<port>"}`, the address of a Debug Adapter Protocol server that takes one client; a target
that does not compile fails it with `RequestFailed`, and an invalid argument with `InvalidParams`. The Debug lenses
carry the same object as the argument of the command `scalaAnalyzer.debug`, which the server does not execute: the
client implements it, as the VS Code extension does, by starting a debug session of its own for that selection.

The adapter downloads its expression compiler (and, for Scala 3, its TASTy decoder) for the project's Scala version the
first time a session evaluates a Scala expression that needs it, and keeps them under the state directory, so that
evaluation needs network access once per Scala version. Simple expressions (a variable, a field, a method call
without lambdas) are evaluated without it.

Breakpoints are verified by the class files: a line the program has no code for (a comment, a blank line, `package`,
`import`) is answered as not verified, and the adapter does not move it to another line. A line with a lambda stops in
the enclosing code and in the lambda, each time it runs: the adapter does not support the DAP `breakpointLocations`
request or a breakpoint's `column`, so the editor cannot offer "line" and "lambda" breakpoints separately as the IDE does.

Hot code replace: after the server compiles a target (the compile command, or the compile of a Run or Debug), every
live debug session is told which classes' files were rewritten. The adapter redefines them in the running program when
its client sends the custom DAP request `redefineClasses` (the server cannot send it for the client; the VS Code
extension sends it to each running session after a successful compile command); it answers the
redefined class names, sends a `hotcodereplace` event, and steps the thread out of a frame it had to pop. Redefining
changes method bodies only: a changed signature, field or class shape fails with an error message, and the frame of a
method that is still running (`main`) keeps the old code. Class files are compared by size and modification time, so a
recompile that wrote identical bytes is reported too.

Limitations: Java frames evaluate simple expressions only, and the source of a library is shown only when the build
gives its source archive.

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
- Java error fixes that would change the IDE's project model (add a library or a module to a module's classpath) are
  not offered: the build owns the classpath. The static-import fix finds the methods of Scala code and of Java
  sources, not of Java libraries. Some Java fixes and intentions that need the IDE's refactoring services are offered
  without a preview, as unavailable.
- A module's Java sources are read at the language level of the build's javac options (`--release`, else `-source`;
  `--enable-preview` selects the preview level). A module whose build reports none, or a version the server does not
  know, is read at the highest level the server supports, so a feature newer than the build's release goes unreported.
  sbt does not report javac options over BSP, so the Java sources of sbt modules are always read at that level.
- Native inspections of Scala sources cover deprecation, selected redundancies, explicit unused imports, unannotated
  declarations confined to the file, and the collection inspections that the IDE enables by default. Java sources
  get the Java plugin's local inspections that the IDE's default profile enables, less those that fail in the host
  (see `Inspections.JavaExclusions`), with the IDE's `@SuppressWarnings` and `//noinspection` suppression; the global
  inspections (unused declarations across files, for one) are not run. Wildcard/given import warnings and whole-project dead-code analysis are outside this scope.
  Inspect Code (`scalaAnalyzer/inspect`) runs only these inspections, on the Scala and Java sources that the build
  does not mark as generated; it does not report syntax, type or compiler errors. The VS Code extension shows its result in a separate
  list and omits files whose live diagnostics are already shown; other clients receive every file's problems and
  must avoid showing them twice. The data-flow inspections of Java sources (DataFlowIssue, ConstantValue and others)
  do not infer facts from library bytecode, as the IDE does, so they can miss what depends on them.
  Dialog-dependent actions, file/package moves and optional automatic renames are
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
- In Java files, Extract variable offers each choice of occurrences that the IDE's chooser lists (this occurrence,
  all of them), named as the IDE suggests, through the same `scalaAnalyzer.extract` command; Inline variable inlines
  every usage of a local and removes it, or the usage under the caret. Java's Extract method, Inline method and
  Inline constant are not offered: the first needs a class of the IDE's settings dialog that the server does not
  have.
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
  as in the IDE. Java files show only parameter-name hints, at the IDE's defaults: `scalaAnalyzer.inlayHints.*`
  does not apply to them. Label parts get their
  locations and tooltips from `inlayHint/resolve`, so a client without resolve support shows plain labels, and a
  type written from a hint is the hint's text: it adds no import. Project IDE code-style
  settings are not imported, and range formatting uses the IntelliJ formatter even when scalafmt is configured.
- Java signature help lists the overloads and the current parameter; unlike the IDE, it does not mark the overloads
  that do not fit the arguments, nor the deprecated ones.
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
