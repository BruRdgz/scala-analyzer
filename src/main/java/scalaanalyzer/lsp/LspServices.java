package scalaanalyzer.lsp;

import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/** Keeps inherited RPC defaults on Java interfaces so Scala forwarders do not duplicate lsp4j methods. */
final class LspServices {
  /** Prevents instantiation: this class only groups the base classes below. */
  private LspServices() {}

  /**
   * Base class of the language server: inherits the lsp4j defaults of {@link LanguageServer} and
   * {@link LanguageClientAware}, and declares the host's {@code scalaAnalyzer/} requests.
   *
   * Subclasses implement what those interfaces leave abstract, including {@code status}, {@code buildTargets},
   * {@code compile}, {@code reimport}, {@code inspect} and {@code typeInfo}.
   */
  abstract static class Server implements LanguageServer, LanguageClientAware, HostProtocol.Server {}

  /**
   * Base class of the text document service: inherits the lsp4j defaults of {@link TextDocumentService}.
   *
   * Subclasses override the requests they serve.
   */
  abstract static class TextDocuments implements TextDocumentService {}

  /**
   * Base class of the workspace service: inherits the lsp4j defaults of {@link WorkspaceService}.
   *
   * Subclasses override the requests they serve.
   */
  abstract static class Workspace implements WorkspaceService {}
}
