package scalaanalyzer.lsp;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Defines host-specific state notifications and requests alongside LSP.
 *
 * Clients may implement these extensions to display import state and diagnostics about the server itself, and to
 * compile or import the build and ask for a type on request.
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

  /**
   * The outcome of a build compilation.
   *
   * Instances are immutable and travel as the result of the {@code scalaAnalyzer/compile} request.
   */
  public static final class CompileReport {
    /** Whether the build server reported the compilation as successful. */
    public final boolean succeeded;

    /** The number of error diagnostics the compilation published, in project sources or not. */
    public final int errors;

    /** The number of warning diagnostics the compilation published, in project sources or not. */
    public final int warnings;

    /**
     * Creates a compilation result.
     *
     * @param succeeded whether the build server reported the compilation as successful
     * @param errors number of error diagnostics the compilation published
     * @param warnings number of warning diagnostics the compilation published
     */
    public CompileReport(boolean succeeded, int errors, int warnings) {
      this.succeeded = succeeded;
      this.errors = errors;
      this.warnings = warnings;
    }
  }

  /**
   * The document and selection a {@code scalaAnalyzer/typeInfo} request asks about.
   *
   * The fields are mutable so that Gson can fill them in.
   */
  public static final class TypeInfoParams {
    /** The document to describe a type in. */
    public TextDocumentIdentifier textDocument;

    /** The selected text; an empty range stands for the caret position. */
    public Range range;

    /** Creates empty parameters for the JSON deserializer to fill in. */
    public TypeInfoParams() {}

    /**
     * Creates the parameters of a type request.
     *
     * @param textDocument the document to describe a type in
     * @param range the selected text; an empty range stands for the caret position
     */
    public TypeInfoParams(TextDocumentIdentifier textDocument, Range range) {
      this.textDocument = textDocument;
      this.range = range;
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

    /**
     * Compiles the build and republishes the diagnostics of the sources whose build problems changed.
     *
     * The compilation runs apart from document requests. A build that does not compile still answers: its errors are
     * counted in the report. The request fails if the project is not open or the compilation cannot run.
     *
     * @return a future containing whether the build compiled and how many errors and warnings it reported
     */
    @JsonRequest("scalaAnalyzer/compile")
    CompletableFuture<CompileReport> compile();

    /**
     * Reloads the build server's workspace and imports the build again, as when the build definition changed.
     *
     * The server reports {@code importing} while it works and {@code ready} afterwards, and then refreshes the
     * diagnostics of the open documents.
     *
     * @return a future that completes with {@code null} when the import ends, or fails with the import's error
     */
    @JsonRequest("scalaAnalyzer/reimport")
    CompletableFuture<Void> reimport();

    /**
     * Describes the type under a caret or of a selected expression, as the IDE's Type Info action does.
     *
     * @param params the document and the selected range; an empty range stands for the caret position
     * @return a future containing the type as plain text, on several lines when the IDE adds the simplified,
     *         original or expected type; {@code null} when there is no type there or the document is not a project
     *         source
     */
    @JsonRequest("scalaAnalyzer/typeInfo")
    CompletableFuture<String> typeInfo(TypeInfoParams params);
  }

  /** The server as a client calls it. */
  public interface LanguageServerWithStatus extends LanguageServer, Server {}
}
