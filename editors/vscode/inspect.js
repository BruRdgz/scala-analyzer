// @ts-check
// "Inspect Code": the server's inspections over the whole project (`scalaAnalyzer/inspect`), as the IDE's action of that
// name runs them. The server publishes nothing; its answer goes to the Inspection Results view (inspectionTree.js) and to
// a diagnostics collection of its own, which the next run replaces and Clear Inspection Results empties. A source that
// the server's live diagnostics already show is left out of the collection, for they contain the same inspections and
// would be listed twice. From the view, a problem's fix and its suppression are the code actions the server offers for
// it: those whose diagnostics include the problem.
const vscode = require('vscode');
const { InspectionResults, codeOf } = require('./inspectionTree');

/**
 * @typedef {{ uri: string, diagnostics: import('vscode-languageclient').Diagnostic[] }} InspectedFile
 * @typedef {{ filesInspected: number, files: InspectedFile[], inspections: import('./inspectionTree').InspectionDescription[] }} InspectReport
 * @typedef {import('./inspectionTree').Node} Node
 */

// Long enough to read a one-line summary.
const statusMessageMs = 5000;

const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`;

// The plugin titles its suppression fixes "Suppress for statement", "Suppress for class" and so on.
const isSuppression = (/** @type {vscode.CodeAction} */ action) => action.title.startsWith('Suppress');

/**
 * The quick fixes that the server offers for a problem and that can be applied: those that suppress it, or the others.
 * The server names the problem an action is about by its diagnostic: the same range and code.
 * @param {Node} problem
 * @param {boolean} suppressing
 */
async function offeredFor(problem, suppressing) {
  /** @type {vscode.CodeAction[]} */
  const actions = await vscode.commands.executeCommand(
    'vscode.executeCodeActionProvider', problem.uri, problem.range, vscode.CodeActionKind.QuickFix.value);
  const isAbout = (/** @type {vscode.Diagnostic} */ diagnostic) =>
    diagnostic.range.isEqual(problem.range) && codeOf(diagnostic) === codeOf(problem.diagnostic);
  return actions.filter(action =>
    !action.disabled && action.edit && isSuppression(action) === suppressing && action.diagnostics?.some(isAbout));
}

/** @param {vscode.CodeAction[]} actions */
async function choose(actions) {
  if (actions.length === 1) return actions[0];
  const pick = await vscode.window.showQuickPick(actions.map(action => ({ label: action.title, action })));
  return pick?.action;
}

/**
 * Returns the two commands of the feature; their results go to a collection and a view that the extension disposes.
 * @param {vscode.ExtensionContext} context
 * @param {import('vscode-languageclient/node').LanguageClient} client
 */
function registerInspection(context, client) {
  const collection = vscode.languages.createDiagnosticCollection('scala-analyzer inspect');
  // The collection follows the view: a problem that moved or left is shown so in the Problems panel too.
  const results = new InspectionResults((uri, remaining) => {
    if (collection.has(uri)) collection.set(uri, remaining);
  });
  const view = vscode.window.createTreeView('scalaAnalyzer.inspectionResults', { treeDataProvider: results, showCollapseAll: true });
  context.subscriptions.push(collection, results.changed, view, vscode.workspace.onDidChangeTextDocument(event => results.followEdit(event)));

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
    const files = [];
    for (const { uri, diagnostics } of report.files) {
      files.push({ uri: converter.asUri(uri), diagnostics: await converter.asDiagnostics(diagnostics) });
    }
    collection.clear();
    for (const { uri, diagnostics } of files) {
      if (!client.diagnostics?.get(uri)?.length) collection.set(uri, diagnostics);
    }
    results.replace(files, report.inspections);

    const problems = files.reduce((count, { diagnostics }) => count + diagnostics.length, 0);
    view.message = problems === 0 ? `No problems found in ${plural(report.filesInspected, 'file')}.` : undefined;
    vscode.window.setStatusBarMessage(
      `$(search) Inspected ${plural(report.filesInspected, 'file')}: ${plural(problems, 'problem')}`, statusMessageMs);
    if (problems > 0) await vscode.commands.executeCommand('scalaAnalyzer.inspectionResults.focus');
  }

  /**
   * Applies the server's fix or suppression for a problem, asking which when it offers several, and drops the problem.
   * @param {Node} problem
   * @param {boolean} suppressing
   */
  async function resolve(problem, suppressing) {
    const actions = await offeredFor(problem, suppressing);
    if (actions.length === 0) {
      vscode.window.showInformationMessage(`scala-analyzer: the server offers no ${suppressing ? 'suppression' : 'fix'} for this problem.`);
      return;
    }
    const action = await choose(actions);
    if (action?.edit && await vscode.workspace.applyEdit(action.edit)) results.remove(problem);
  }

  context.subscriptions.push(
    vscode.commands.registerCommand('scalaAnalyzer.revealInspectionProblem', (/** @type {Node} */ problem) =>
      vscode.window.showTextDocument(problem.uri, { selection: problem.range, preview: true, preserveFocus: true })),
    vscode.commands.registerCommand('scalaAnalyzer.applyInspectionFix', (/** @type {Node} */ problem) => resolve(problem, false)),
    vscode.commands.registerCommand('scalaAnalyzer.suppressInspection', (/** @type {Node} */ problem) => resolve(problem, true)),
  );

  return {
    inspect,
    clear: () => {
      collection.clear();
      results.clear();
      view.message = undefined;
    },
  };
}

module.exports = { registerInspection };
