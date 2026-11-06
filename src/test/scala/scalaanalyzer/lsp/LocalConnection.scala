package scalaanalyzer.lsp

import org.eclipse.lsp4j.launch.LSPLauncher

import java.nio.channels.{Channels, Pipe}

/** Connects a client to a server in the same JVM, as an editor connects (the server's own messages included). */
object LocalConnection {
  /**
   * Returns the server as `client` sees it.
   *
   * The streams are NIO pipes, not `java.io.Piped*Stream`: those fail the reader once the thread that last wrote
   * has died ("Write end dead"), and the server writes from executor threads that end on shutdown, so the client
   * stopped reading before the shutdown answer arrived.
   */
  def connect(client: HostProtocol.Client, implementation: ScalaLanguageServer): HostProtocol.LanguageServerWithStatus = {
    val toServer = Pipe.open()
    val toClient = Pipe.open()
    val serverSide = new LSPLauncher.Builder[HostProtocol.Client]().setLocalService(implementation)
      .setRemoteInterface(classOf[HostProtocol.Client])
      .setInput(Channels.newInputStream(toServer.source)).setOutput(Channels.newOutputStream(toClient.sink)).create()
    implementation.connect(serverSide.getRemoteProxy)
    serverSide.startListening()
    val clientSide = new LSPLauncher.Builder[HostProtocol.LanguageServerWithStatus]().setLocalService(client)
      .setRemoteInterface(classOf[HostProtocol.LanguageServerWithStatus])
      .setInput(Channels.newInputStream(toClient.source)).setOutput(Channels.newOutputStream(toServer.sink)).create()
    clientSide.startListening()
    clientSide.getRemoteProxy
  }
}
