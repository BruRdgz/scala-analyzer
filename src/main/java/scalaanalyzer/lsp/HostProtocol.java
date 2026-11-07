package scalaanalyzer.lsp;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Defines host-specific state notifications and status requests alongside LSP.
 *
 * Clients may implement these extensions to display import state and diagnostics about the server itself.
 */
public final class HostProtocol {
  /** Prevents instantiation: this class only groups the protocol types. */
  private HostProtocol() {}

  /**
   * The server's lifecycle state and a human-readable description of its current work.
   *
   * Instances are immutable and travel as the payload of the {@code scalaAnalyzer/state} notification.
   */
  public static final class ServerState {
    /** The lifecycle state: {@code starting}, {@code importing}, {@code ready} or {@code failed}. */
    public final String state;

    /** The human-readable activity or failure description that goes with {@link #state}. */
    public final String message;

    /**
     * Creates a lifecycle notification payload.
     *
     * @param state {@code starting}, {@code importing}, {@code ready} or {@code failed}
     * @param message human-readable activity or failure description
     */
    public ServerState(String state, String message) {
      this.state = state;
      this.message = message;
    }
  }

  /** What the server sends a client that knows its messages. */
  public interface Client extends LanguageClient {
    /**
     * Reports a server lifecycle transition.
     *
     * Sent as the {@code scalaAnalyzer/state} notification, so the client sends no reply.
     *
     * @param state current lifecycle state and activity description
     */
    @JsonNotification("scalaAnalyzer/state")
    void state(ServerState state);
  }

  /** What the server answers beyond LSP. */
  public interface Server {
    /**
     * Captures server state, project counts, memory, request latencies and logged problems.
     *
     * The request answers without waiting for project import to finish.
     *
     * @return a future containing the host's status fields
     */
    @JsonRequest("scalaAnalyzer/status")
    CompletableFuture<Map<String, Object>> status();
  }

  /** The server as a client calls it. */
  public interface LanguageServerWithStatus extends LanguageServer, Server {}
}
