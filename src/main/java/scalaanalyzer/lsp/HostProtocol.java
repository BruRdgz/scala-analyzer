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

    /** The Scala version the target is compiled with, or {@code null} for a Java target. */
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
     * @param scalaVersion the Scala version the target is compiled with, or {@code null} for a Java target
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

  /**
   * The document whose tests a {@code scalaAnalyzer/testItems} request lists.
   *
   * The field is mutable so that Gson can fill it in.
   */
  public static final class TestItemsParams {
    /** The URI of the document to list; absent for every source of the project. */
    public String uri;

    /** Creates empty parameters for the JSON deserializer to fill in. */
    public TestItemsParams() {}

    /**
     * Creates the parameters of a test listing.
     *
     * @param uri the URI of the document to list, or {@code null} for the whole project
     */
    public TestItemsParams(String uri) {
      this.uri = uri;
    }
  }

  /**
   * A test suite or one of its tests, with what the {@code scalaAnalyzer.runTest} command needs to run it.
   *
   * Instances are immutable and travel as the elements of the result of the {@code scalaAnalyzer/testItems} request.
   * {@link #target}, {@link #framework}, {@link #suite} and {@link #testName} are the members of the argument that the
   * run code lens of the same range gives the command.
   */
  public static final class TestItem {
    /** The URI of the document that holds the suite or test. */
    public final String uri;

    /** The range of the suite's or test's name when it has one, else of the whole element. */
    public final Range range;

    /** The BSP target of the module that holds the suite. */
    public final String target;

    /** The test framework or runner: {@code scalatest}, {@code specs2}, {@code utest}, {@code munit}, {@code junit4} or {@code junit5}. */
    public final String framework;

    /** The qualified name of the suite; for a Java class, its binary name. */
    public final String suite;

    /** The test's name in the runner's encoding, or {@code null} for a suite. */
    public final String testName;

    /** A short name to display: the suite's simple class name, or the first line of the test's name. */
    public final String label;

    /**
     * Creates a test item.
     *
     * @param uri the URI of the document that holds the suite or test
     * @param range the range of the suite's or test's name
     * @param target the BSP target of the module that holds the suite
     * @param framework the test framework or runner
     * @param suite the qualified name of the suite
     * @param testName the test's name in the runner's encoding, or {@code null} for a suite
     * @param label the short name to display
     */
    public TestItem(
        String uri, Range range, String target, String framework, String suite, String testName, String label) {
      this.uri = uri;
      this.range = range;
      this.target = target;
      this.framework = framework;
      this.suite = suite;
      this.testName = testName;
      this.label = label;
    }
  }

  /**
   * The progress of one test of a {@code scalaAnalyzer.runTest} run.
   *
   * Instances are immutable and travel as the payload of the {@code scalaAnalyzer/testResult} notification.
   */
  public static final class TestResult {
    /** The suite of the run's selection, as the command's argument gives it. */
    public final String suite;

    /**
     * The test's name as the runner reports it. It is the {@link TestItem#testName} of the test for most runners; a
     * ScalaTest scope that groups several tests, and a parameterized JUnit method, report names that differ.
     */
    public final String testName;

    /** What happened: {@code started}, {@code passed}, {@code failed} or {@code ignored}. */
    public final String outcome;

    /** The failure, or the reason the test was ignored, when the runner gives one; otherwise {@code null}. */
    public final String message;

    /** How long the test took in milliseconds, when the runner reports it; otherwise {@code null}. */
    public final Long durationMs;

    /**
     * Creates a test progress report.
     *
     * @param suite the suite of the run's selection
     * @param testName the test's name as the runner reports it
     * @param outcome {@code started}, {@code passed}, {@code failed} or {@code ignored}
     * @param message the failure or the reason for ignoring, or {@code null}
     * @param durationMs the test's duration in milliseconds, or {@code null}
     */
    public TestResult(String suite, String testName, String outcome, String message, Long durationMs) {
      this.suite = suite;
      this.testName = testName;
      this.outcome = outcome;
      this.message = message;
      this.durationMs = durationMs;
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

    /**
     * Reports the progress of one test while a {@code scalaAnalyzer.runTest} command runs.
     *
     * Sent as the {@code scalaAnalyzer/testResult} notification, so the client sends no reply, for each test that
     * starts and for each that passes, fails or is ignored, in the order the runner reports them. A client that does
     * not handle it loses nothing: the run's output and summary reach it through the standard messages.
     *
     * @param result the test and what happened to it
     */
    @JsonNotification("scalaAnalyzer/testResult")
    void testResult(TestResult result);
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
     * Runs the host's selected inspections over the project's Scala and Java sources and returns their problems, as the
     * IDE's Inspect Code does.
     *
     * Only inspections run: no syntax errors, annotator problems or compiler diagnostics. Generated sources are
     * skipped. The request reads each source's current buffer, saved or not, and publishes
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
     * Locates what the IDE's Go to Test navigates to from a caret: the tests of the class around it, or the classes
     * that the test class around it tests.
     *
     * @param params the document and the caret position
     * @return a future containing the locations of the targets in the order the IDE ranks them, each a zero-width
     *         range at the position of the declaration's name; empty when there is none or the document is not a
     *         project source
     */
    @JsonRequest("scalaAnalyzer/testTargets")
    CompletableFuture<List<Location>> testTargets(TextDocumentPositionParams params);

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

    /**
     * Lists the test suites and tests that the run code lenses offer, for a client's test explorer.
     *
     * The items are exactly the ones the {@code Run suite} and {@code Run test} lenses carry, from the same detection,
     * over the document's current buffer. Without a document, every Scala and Java source of the project is scanned,
     * one after the other: the cost grows with the project, since the build does not tell test sources from main
     * ones, and the request is meant to be sent once and then per saved document.
     *
     * @param params the document to list, or no document for the whole project
     * @return a future containing, per source in path order, each suite followed by its tests in source order; empty
     *         when the document is not a project source or has no tests
     */
    @JsonRequest("scalaAnalyzer/testItems")
    CompletableFuture<List<TestItem>> testItems(TestItemsParams params);
  }

  /** The server as a client calls it. */
  public interface LanguageServerWithStatus extends LanguageServer, Server {}
}
