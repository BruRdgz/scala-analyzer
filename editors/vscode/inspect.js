// @ts-check
// "Inspect Code": the server's inspections over the whole project (`scalaAnalyzer/inspect`), as the IDE's action of that
// name runs them. The server publishes nothing; its answer goes to a collection of its own, which the next run replaces
// and Clear Inspection Results empties. A source that the server's live diagnostics already show is left out, for they
// contain the same inspections and would be listed twice.
const vscode = require('vscode');

/**
 * @typedef {{ uri: string, diagnostics: import('vscode-languageclient').Diagnostic[] }} InspectedFile
 * @typedef {{ filesInspected: number, files: InspectedFile[] }} InspectReport
 */

// Long enough to read a one-line summary.
const statusMessageMs = 5000;

const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`;

/**
 * Returns the two commands of the feature; their results go to a collection that the extension disposes.
 * @param {vscode.ExtensionContext} context
 * @param {import('vscode-languageclient/node').LanguageClient} client
 */
function registerInspection(context, client) {
  const results = vscode.languages.createDiagnosticCollection('scala-analyzer inspect');
  context.subscriptions.push(results);

  // Resolves to nothing when the user cancels, which the server answers as a cancelled request.
  /** @returns {Thenable<InspectReport | undefined>} */
  const request = () => vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: 'scala-analyzer: inspecting the project', cancellable: true },
    async (_progress, token) => {
      try {
        return await client.sendRequest('scalaAnalyzer/inspect', token);
      } catch (error) {
        if (token.isCancellationRequested) return undefined;
        throw error;
      }
    },
  );

  async function inspect() {
    let report;
    try {
      report = await request();
    } catch (error) {
      vscode.window.showErrorMessage(`scala-analyzer: inspection failed: ${error.message}`);
      return;
    }
    if (!report) return;

    const converter = client.protocol2CodeConverter;
    results.clear();
    for (const { uri, diagnostics } of report.files) {
      const file = converter.asUri(uri);
      if (!client.diagnostics?.get(file)?.length) results.set(file, await converter.asDiagnostics(diagnostics));
    }

    const problems = report.files.reduce((count, { diagnostics }) => count + diagnostics.length, 0);
    vscode.window.setStatusBarMessage(
      `$(search) Inspected ${plural(report.filesInspected, 'file')}: ${plural(problems, 'problem')}`, statusMessageMs);
    if (problems > 0) await vscode.commands.executeCommand('workbench.actions.view.problems');
  }

  return { inspect, clear: () => results.clear() };
}

module.exports = { registerInspection };
