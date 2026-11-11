package scalaanalyzer.lsp

import org.eclipse.lsp4j.launch.LSPLauncher

import java.io.{IOException, OutputStream}
import java.nio.ByteBuffer
import java.nio.channels.{Channels, Pipe}
import java.util.concurrent.{ExecutionException, Executors}

/** Connects a client to a server in the same JVM, as an editor connects (the server's own messages included). */
object LocalConnection {
  /**
   * Returns the server as `client` sees it.
   *
   * The streams are NIO pipes, not `java.io.Piped*Stream`: those fail the reader once the thread that last wrote
   * has died ("Write end dead"), and the server writes from executor threads that end on shutdown, so the client
   * stopped reading before the shutdown answer arrived. The server writes through [[OwnThreadOutput]].
   */
  def connect(client: HostProtocol.Client, implementation: ScalaLanguageServer): HostProtocol.LanguageServerWithStatus = {
    val toServer = Pipe.open()
    val toClient = Pipe.open()
    val serverSide = new LSPLauncher.Builder[HostProtocol.Client]().setLocalService(implementation)
      .setRemoteInterface(classOf[HostProtocol.Client])
      .setInput(Channels.newInputStream(toServer.source)).setOutput(new OwnThreadOutput(toClient.sink)).create()
    implementation.connect(serverSide.getRemoteProxy)
    serverSide.startListening()
    val clientSide = new LSPLauncher.Builder[HostProtocol.LanguageServerWithStatus]().setLocalService(client)
      .setRemoteInterface(classOf[HostProtocol.LanguageServerWithStatus])
      .setInput(Channels.newInputStream(toClient.source)).setOutput(Channels.newOutputStream(toServer.sink)).create()
    clientSide.startListening()
    clientSide.getRemoteProxy
  }

  /**
   * Writes to `sink` from a thread of its own, as a process writes its standard output. The server's shutdown
   * interrupts its executor threads, and a thread interrupted while it writes to a pipe closes the pipe, so the
   * shutdown answer was lost when it came during a publication; an interrupted standard output stays open.
   */
  private final class OwnThreadOutput(sink: Pipe.SinkChannel) extends OutputStream {
    private val writer = Executors.newSingleThreadExecutor { task =>
      val thread = new Thread(task, "local-connection-writer")
      thread.setDaemon(true)
      thread
    }

    override def write(byte: Int): Unit = write(Array(byte.toByte), 0, 1)

    /** Waits for the write without giving way to an interrupt, which is kept for the caller to see afterwards. */
    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
      val written = writer.submit(new Runnable {
        override def run(): Unit = {
          val buffer = ByteBuffer.wrap(bytes, offset, length)
          while (buffer.hasRemaining) sink.write(buffer)
        }
      })
      var interrupted = false
      var done = false
      try {
        while (!done)
          try { written.get(); done = true }
          catch { case _: InterruptedException => interrupted = true }
      } catch {
        case failure: ExecutionException => throw new IOException(failure.getCause)
      } finally if (interrupted) Thread.currentThread().interrupt()
    }

    override def close(): Unit = {
      writer.shutdown()
      sink.close()
    }
  }
}
