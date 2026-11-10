# Scala (scala-analyzer)

VS Code client for [scala-analyzer](https://github.com/BruRdgz/scala-analyzer).

Install the VSIX and the server archive from the same GitHub release. Extract the server, install JDK 25,
and set `scalaAnalyzer.serverPath` to its `bin/scala-analyzer` launcher. Set `scalaAnalyzer.javaHome` if JDK 25
is not the Java used by VS Code's environment.

Open a Scala workspace with a BSP connection (`sbt bspConfig` for sbt). The extension starts the server
for Scala files and also handles Java files in that workspace.

Click the **Scala** status bar item for a menu, or run the commands from the palette (category `scala-analyzer`):
**Compile Build**, **Reimport Build**, **Inspect Code**, **Clear Inspection Results**, **Restart Server**,
**Show Type Info**, **Go to Super**, **Override Members…**, **Show Status** and **Show Log File**.
Compiling is also the workspace build task, so **Ctrl+Shift+B** runs it (or add
`{ "type": "scala-analyzer", "task": "compile" }` to `tasks.json`). Its outcome shows briefly in the status bar; a
failed compile opens the Problems panel with the diagnostics.
**Show Type Info** shows the type of the selection or the expression at the cursor at the end of its line, until the
next move or edit; it is in the editor context menu for Scala files. It has no default key (IntelliJ's Ctrl+Shift+P opens VS Code's command palette); bind one in
`keybindings.json`: `{ "key": "<your key>", "command": "scalaAnalyzer.typeInfo", "when": "editorLangId == scala" }`.
**Go to Super** opens the declaration that the type or member at the cursor extends, overrides or implements, and peeks
all of them when there are several; it is in the editor context menu for Scala files. It has no default key (IntelliJ's
Ctrl+U is taken in VS Code); bind one the same way, with the command `scalaAnalyzer.goToSuper`.
**Override Members…** lists the members the class at the cursor can override, as IntelliJ's Override Members (Ctrl+O)
does, and overrides the ones you pick; it is in the editor context menu for Scala files. It has no default key; bind one
with the command `scalaAnalyzer.chooseMembersToOverride`. Implementing the abstract members is a code action (the light
bulb, **Implement members**).

**Inspect Code** runs the server's inspections over every Scala source of the build (not the generated ones) and lists
their problems, with a cancellable progress notification. The results are a snapshot: the next run replaces them and
**Clear Inspection Results** removes them.

The **Inspection Results** view in the bottom panel lists them as the IDE's Inspect Code window does: groups (such as
Scala › General), inspections, files, then problems by line, each node with its count. Selecting a problem opens it in
the editor. The buttons of a problem apply the server's quick fix, or its suppression (the plugin's `//noinspection`
comment, for the statement, the definition, the class or the file); each asks which when the server offers several for
that problem. The problem then leaves the view. The title bar reruns Inspect Code, clears the results and collapses
the tree. The listed problems follow the edits you make to their files, and one whose code you edit leaves the view. The
same problems are also in the Problems panel, except those of a file whose live diagnostics are already shown (opened,
or touched by a compile), so that none is listed twice there.

The **Scala Build** view in the Explorer lists the build's targets with their Scala version, the targets they depend
on, their libraries and their source file count. It refreshes whenever the build is (re)imported; its title bar
compiles, reimports or inspects the build, and the button on a target compiles just that target.

The server provides diagnostics, completion, Markdown hover, navigation, formatting, symbols and previewed
code actions. Supported platforms for the packaged server are Linux x64 and macOS Intel/Apple Silicon.
The server currently serves one workspace per process. See the repository README for feature boundaries.
