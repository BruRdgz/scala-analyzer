# Scala (scala-psi-host)

VS Code client for [scala-psi-host](https://github.com/BruRdgz/scala-psi-host).

Install the VSIX and the server archive from the same GitHub release. Extract the server, install JDK 25,
and set `scalaPsiHost.serverPath` to its `bin/scala-psi-host` launcher. Set `scalaPsiHost.javaHome` if JDK 25
is not the Java used by VS Code's environment.

Open a Scala workspace with a BSP connection (`sbt bspConfig` for sbt). The extension starts the server
for Scala files and also handles Java files in that workspace. Use **Scala: Show Status** and
**Scala: Show Log File** to inspect the server.

The server provides diagnostics, completion, Markdown hover, navigation, formatting, symbols and previewed
code actions. Supported platforms for the packaged server are Linux x64 and macOS Intel/Apple Silicon.
The server currently serves one workspace per process. See the repository README for feature boundaries.
