# scala-psi-host

A Scala language server powered by the IntelliJ Scala plugin, running in a standalone process without the IntelliJ
application container. It provides editor features through the Language Server Protocol (LSP) and imports Scala 2
and Scala 3 projects through the Build Server Protocol (BSP).

Release 0.1.0 supports Linux x64 and macOS Intel/Apple Silicon with JDK 25. Integration tests use sbt builds;
mixed Scala/Java projects have partial Java support.

## Features

- Diagnostics from the Scala plugin and compiler, including current-buffer compilation for Scala 3.
- Hover documentation, definitions, type definitions, references and implementations.
- Completion with automatic imports and insertion handlers, signature help and inlay hints.
- Rename, organize imports, previewable quick fixes, intentions and surround actions.
- Whole-file and range formatting with scalafmt or the IntelliJ formatter.
- Document highlights, outlines, folding, selection ranges and semantic tokens.
- Workspace symbol search and module-aware resolution.
- Navigation into library sources or decompiled class files.

## Install

Download the server archive and VSIX from [GitHub Releases](https://github.com/BruRdgz/scala-psi-host/releases).
Verify `SHA256SUMS`, extract the archive, and keep its `bin/` and `lib/` directories together. Install JDK 25
and configure the editor as described below. The archive is shared by Linux and macOS; releases are tested on
Linux x64, macOS Intel and macOS Apple Silicon. The VSIX is installed separately; it does not bundle a JDK
or download a server automatically.

## Build

Requires Git, sbt and **JDK 25** for both the build and the server.

The IntelliJ Platform version and Scala plugin revision are pinned in [build.sbt](build.sbt). From the repository
root, build the plugin in a sibling checkout, then build the host:

```sh
export JAVA_HOME=/absolute/path/to/jdk-25

git clone https://github.com/JetBrains/intellij-scala ../intellij-scala
git -C ../intellij-scala checkout 491569803c6c3c2f93a0bda51490425fda237fc0
(
  cd ../intellij-scala
  sbt --java-home "$JAVA_HOME" packageArtifactZip
)

sbt --java-home "$JAVA_HOME" dist
```

The distribution is written to `target/dist/`. Its launcher is `target/dist/bin/scala-psi-host`; keep the `bin/` and
`lib/` directories together when moving it.

By default, the build reads plugin jars from `../intellij-scala/target/plugin/Scala/lib` and verifies the checkout's
revision. Set `SCALA_PLUGIN_LIB` to use another plugin build's `Scala/lib` directory. This skips the revision check;
the supplied jars must still match the host's platform dependencies.

## Usage

Your Scala project needs a BSP connection file under `.bsp/`. For sbt, run this from the project's root using the
JDK required by that build:

```sh
sbt bspConfig
```

Configure your editor's LSP client to launch the distribution's `bin/scala-psi-host` over stdio, with `JAVA_HOME`
pointing to JDK 25. The server currently uses one workspace root and expects one BSP connection file.

A VS Code client is included in [editors/vscode](editors/vscode). Its configuration is:

```json
{
  "scalaPsiHost.serverPath": "/absolute/path/to/scala-psi-host/target/dist/bin/scala-psi-host",
  "scalaPsiHost.javaHome": "/absolute/path/to/jdk-25"
}
```

The extension reports import progress and server readiness in the status bar. Its **Show Status** and **Show Log
File** commands provide troubleshooting information. Set `scalaPsiHost.debugLog` to `true` before starting the
server for request timing logs.

Completion follow-up edits require client support for `workspace/applyEdit`; caret placement also requires
`window/showDocument`.

## Configuration

| Environment variable | Purpose | Default |
| --- | --- | --- |
| `JAVA_HOME` | JDK used to run the server | `java` on `PATH` |
| `SCALA_PSI_HOST_STATE` | State, logs, formatter cache and library source files | `$XDG_CACHE_HOME/scala-psi-host`, or `~/.cache/scala-psi-host` |
| `SCALA_PSI_HOST_HEAP` | Maximum JVM heap | `2g` |
| `SCALA_PSI_HOST_LOG` | Set to `debug` to log every request's timing | Informational logging |

Logs are sent to the editor and written under `<state-dir>/logs/`. The latest ten log files are retained.

Whole-file formatting uses the nearest `.scalafmt.conf` between the source file and workspace root. The configuration
must declare a formatter version; missing dependencies are downloaded from Maven Central and cached locally.
Project exclusion filters apply. Without a config, and for range requests, the IntelliJ formatter is used.

When the client supports dynamic file watchers, changes to sbt build definitions and source-file creation or deletion
trigger a reimport. Unsaved source text is preserved.

## Known limitations

- References and implementations use text-based searches without compiler indexes. Implicit/SAM usages and Java
  inheritors can be missed. Rename can leave Java overrides unchanged.
- Java binary short-name lookup is incomplete, and JDK symbols come
  from the JDK running the server.
- Native inspections cover deprecation, selected redundancies, explicit unused imports and unannotated
  declarations confined to the file. Wildcard/given import warnings and whole-project dead-code analysis are
  outside this scope. Dialog-dependent actions, file/package moves and optional automatic renames are unavailable;
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

## Development

Generate the fixture's BSP connection once, then run the tests from the repository root:

```sh
(
  cd fixtures/bsp-project
  sbt --java-home "$JAVA_HOME" bspConfig
)
sbt --java-home "$JAVA_HOME" test
```

The suite covers PSI semantics, module resolution, editing, BSP imports, formatting, LSP requests and the reviewed
platform-adapter surface. Additional tools in [src/test/scala/scalapsihost/oracle](src/test/scala/scalapsihost/oracle)
compare selected upstream plugin fixtures; their source files document the supported inputs.

To check a built distribution over stdio with Python 3:

```sh
python3 dist/smoke-test.py \
  target/dist/bin/scala-psi-host \
  fixtures/bsp-project \
  fixtures/bsp-project/app/src/main/scala/app/Main.scala \
  6 53
```

This checks initialization, document opening, diagnostics, hover, definition and shutdown. Positions are zero-based.

CI builds the pinned upstream plugin, runs the suite and Scaladoc checks, packages the server/VSIX, and exercises
both the extracted server and installed extension on the supported platforms. Numeric version tags (for example,
`0.1.0`) publish the exact checked artifacts to GitHub Releases. `version.sbt` and the VSIX manifest must agree.
`build-info.json` records the source/dependency coordinates and packaged-file hashes. No Marketplace publication
is performed by this workflow.

See [ROADMAP.md](ROADMAP.md) for planned work and [CHANGELOG.md](CHANGELOG.md) for release history.

## License

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.

scala-psi-host builds on the Scala plugin for IntelliJ IDEA and the IntelliJ Platform by JetBrains. It is not
affiliated with or endorsed by JetBrains. IntelliJ and IntelliJ IDEA are trademarks of JetBrains s.r.o.
