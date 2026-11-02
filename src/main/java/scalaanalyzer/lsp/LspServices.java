package scalaanalyzer.lsp;

import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/** Keeps inherited RPC defaults on Java interfaces so Scala forwarders do not duplicate lsp4j methods. */
final class LspServices {
  private LspServices() {}

  abstract static class Server implements LanguageServer, LanguageClientAware, HostProtocol.Server {}

  abstract static class TextDocuments implements TextDocumentService {}

  abstract static class Workspace implements WorkspaceService {}
}
