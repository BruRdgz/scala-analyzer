package ch.epfl.scala.debugadapter.internal

import ch.epfl.scala.debugadapter.{DebugConfig, DebugToolsResolver, Debuggee, Logger}

import java.net.Socket

import scala.concurrent.ExecutionContext

/**
 * Starts a scala-debug-adapter session on a socket the host chose, which the adapter's public API does not offer: its
 * `DebugServer` listens on every network interface (`DebugServer.Address` has no other), and `DebugSession` is private
 * to the adapter's package. This object lives in that package for that reason only; it does what
 * `DebugServer.connect` does once a client is accepted (scala-debug-adapter 4.2.13, pinned in build.sbt).
 */
object HostDebugSession {
  /**
   * Starts the session of one client on `socket`.
   *
   * @return closes the session, which ends a launched program; closing is idempotent
   */
  def start(socket: Socket, debuggee: Debuggee, resolver: DebugToolsResolver, logger: Logger)(using
      ExecutionContext): AutoCloseable = {
    val session = DebugSession(socket, debuggee, resolver, logger, DebugConfig.default)
    session.start()
    () => session.close()
  }
}
