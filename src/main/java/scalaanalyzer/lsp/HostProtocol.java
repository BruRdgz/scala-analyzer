package scalaanalyzer.lsp;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Defines host-specific state notifications and requests alongside LSP.
 *
 * Clients may implement these extensions to display import state and diagnostics about the server itself, and to
 * list the build's targets, compile or import the build, inspect the project's code, ask for a type or for the
 * supers of a declaration on request and list the members a class can override.
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
   * One build target of the imported build, as the server knows it.
   *
   * Instances are immutable and travel as the elements of the result of the {@code scalaAnalyzer/buildTargets}
   * request.
   */
  public static final class BuildTarget {
    /** The target's stable identity, which {@link CompileParams#target} and {@link #dependsOn} refer to. */
    public final String id;

    /** The target's display name, which several targets may share. */
    public final String name;

    /** The Scala version the target is compiled with. */
    public final String scalaVersion;

    /** The identities of the targets this one directly depends on, in the build's order. */
    public final List<String> dependsOn;

    /** The absolute paths of the jar files on the target's classpath, without the output of other targets. */
    public final List<String> libraries;

    /** The number of project sources that belong to the target. */
    public final int sourceFileCount;

    /**
     * Creates a build target description.
     *
     * @param id the target's stable identity
     * @param name the target's display name
     * @param scalaVersion the Scala version the target is compiled with
     * @param dependsOn identities of the targets this one directly depends on
     * @param libraries absolute paths of the jar files on the target's classpath
     * @param sourceFileCount number of project sources that belong to the target
     */
    public BuildTarget(
        String id, String name, String scalaVersion, List<String> dependsOn, List<String> libraries,
        int sourceFileCount) {
      this.id = id;
      this.name = name;
      this.scalaVersion = scalaVersion;
      this.dependsOn = dependsOn;
      this.libraries = libraries;
      this.sourceFileCount = sourceFileCount;
    }
  }

  /**
   * The target a {@code scalaAnalyzer/compile} request compiles.
   *
   * The field is mutable so that Gson can fill it in.
   */
  public static final class CompileParams {
    /** The identity of the target to compile, as {@link BuildTarget#id} reports it; absent for the whole build. */
    public String target;

    /** Creates empty parameters for the JSON deserializer to fill in. */
    public CompileParams() {}

    /**
     * Creates the parameters of a compile request.
     *
     * @param target the identity of the target to compile, or {@code null} for the whole build
     */
    public CompileParams(String target) {
      this.target = target;
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

    /** The number of error diagnostics of the compiled targets and their dependencies, in project sources or not. */
    public final int errors;

    /** The number of warning diagnostics of the compiled targets and their dependencies, in project sources or not. */
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

  /**
   * How the IDE presents one inspection in its inspection results.
   *
   * Instances are immutable and travel in the {@code scalaAnalyzer/inspect} result.
   */
  public static final class InspectionDescription {
    /** The inspection id; the {@code code} of each of the inspection's diagnostics. */
    public final String id;

    /** The inspection's name, such as {@code Redundant block}. */
    public final String displayName;

    /** The groups that contain the inspection, outermost first, such as {@code Scala}, {@code General}. */
    public final List<String> groupPath;

    /**
     * Creates an inspection description.
     *
     * @param id the inspection id that its diagnostics carry as their code
     * @param displayName the inspection's name
     * @param groupPath the groups that contain the inspection, outermost first
     */
    public InspectionDescription(String id, String displayName, List<String> groupPath) {
      this.id = id;
      this.displayName = displayName;
      this.groupPath = groupPath;
    }
  }

  /**
   * A member that Override Members offers for a class, as the IDE's chooser shows it.
   *
   * Instances are immutable and travel as the elements of the result of the {@code scalaAnalyzer/overridableMembers}
   * request.
   */
  public static final class OverridableMember {
    /** The member's opaque key, which the {@code scalaAnalyzer.overrideMembers} command takes back. */
    public final String id;

    /** The member as the chooser shows it, such as {@code foo(x: Int): String}. */
    public final String signature;

    /** The qualified name of the class or trait that declares the member. */
    public final String owner;

    /**
     * Creates a member description.
     *
     * @param id the member's opaque key
     * @param signature the member as the chooser shows it
     * @param owner the qualified name of the class or trait that declares the member
     */
    public OverridableMember(String id, String signature, String owner) {
      this.id = id;
      this.signature = signature;
      this.owner = owner;
    }
  }

  /**
   * The outcome of a project-wide inspection.
   *
   * Instances are immutable and travel as the result of the {@code scalaAnalyzer/inspect} request.
   */
  public static final class InspectReport {
    /** The number of sources the inspection covered, with or without problems. */
    public final int filesInspected;

    /** The inspection problems by source, in path order; only the sources that have some, and never a version. */
    public final List<PublishDiagnosticsParams> files;

    /** The description of each inspection that reported a problem, keyed by the code of its diagnostics; none repeats. */
    public final List<InspectionDescription> inspections;

    /**
     * Creates an inspection result.
     *
     * @param filesInspected number of sources the inspection covered
     * @param files the problems of the sources that have some
     * @param inspections the description of each inspection that reported a problem
     */
    public InspectReport(int filesInspected, List<PublishDiagnosticsParams> files, List<InspectionDescription> inspections) {
      this.filesInspected = filesInspected;
      this.files = files;
      this.inspections = inspections;
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
     * Lists the targets of the imported build.
     *
     * The request waits for the import in progress, if any, and describes the build as that import left it.
     *
     * @return a future containing the targets sorted by name, then by identity; it fails if the project is not open
     */
    @JsonRequest("scalaAnalyzer/buildTargets")
    CompletableFuture<List<BuildTarget>> buildTargets();

    /**
     * Compiles the build, or one target of it, and republishes the diagnostics of the sources whose build problems
     * changed.
     *
     * The compilation runs apart from document requests. A build that does not compile still answers: its errors are
     * counted in the report. The request fails if the project is not open, the target is not one of the build's, or
     * the compilation cannot run.
     *
     * @param params the target to compile; a request without parameters, or without a target, compiles the whole
     *               build
     * @return a future containing whether the build compiled and how many errors and warnings it reported
     */
    @JsonRequest("scalaAnalyzer/compile")
    CompletableFuture<CompileReport> compile(CompileParams params);

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
     * Runs the host's selected inspections over the project's Scala sources and returns their problems, as the IDE's
     * Inspect Code does.
     *
     * Only inspections run: no syntax errors, annotator problems or compiler diagnostics. Generated sources are
     * skipped, and so are Java sources. The request reads each source's current buffer, saved or not, and publishes
     * nothing: the client decides how to show the result. It runs apart from document requests, is shown as progress
     * and ends early, answering a cancellation error, when the client cancels it.
     *
     * @return a future containing the number of sources inspected, the problems found and the name and group of each
     *         inspection that found some; it fails if the project is not open
     */
    @JsonRequest("scalaAnalyzer/inspect")
    CompletableFuture<InspectReport> inspect();

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

    /**
     * Locates what the IDE's Go to Super navigates to from a caret: the superclasses of the type around it, or the
     * members that the member around it overrides or implements.
     *
     * @param params the document and the caret position
     * @return a future containing the locations of the supers, each a zero-width range at the position of the
     *         declaration's name; empty when there is none or the document is not a project source
     */
    @JsonRequest("scalaAnalyzer/superElements")
    CompletableFuture<List<Location>> superElements(TextDocumentPositionParams params);

    /**
     * Lists the members that the IDE's Override Members action offers for the class, object or trait at a position.
     *
     * The members come in the chooser's order, grouped by owner. The {@code scalaAnalyzer.overrideMembers} command
     * overrides the ones the client selects.
     *
     * @param params the document and the caret position in the class
     * @return a future containing the members; empty when the position is not in a class, object or trait or the
     *         document is not a project source
     */
    @JsonRequest("scalaAnalyzer/overridableMembers")
    CompletableFuture<List<OverridableMember>> overridableMembers(TextDocumentPositionParams params);
  }

  /** The server as a client calls it. */
  public interface LanguageServerWithStatus extends LanguageServer, Server {}
}
