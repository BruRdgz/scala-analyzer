// @ts-check
// The Testing view: the project's test suites and tests as the server lists them (`scalaAnalyzer/testItems`), run through
// the server's `scalaAnalyzer.runTest` command, whose per-test progress arrives as `scalaAnalyzer/testResult`
// notifications. The tree is fetched when the server reports the build imported, and again for a document when it is saved.
const vscode = require('vscode');
const { ExecuteCommandRequest } = require('vscode-languageclient/node');

/**
 * What the server needs to run an item: the members of the `scalaAnalyzer.runTest` argument.
 * @typedef {{ target: string, framework: string, suite: string, testName?: string }} Selection
 */

/**
 * An element of the server's `scalaAnalyzer/testItems` answer; a suite has no `testName`.
 * @typedef {Selection & { uri: string, range: import('vscode-languageclient/node').Range, label: string }} ServerTestItem
 */

/**
 * A server notification `scalaAnalyzer/testResult`.
 * @typedef {{ suite: string, testName: string, outcome: 'started' | 'passed' | 'failed' | 'ignored', message?: string, durationMs?: number }} TestResult
 */

// Long enough that a burst of saves (format on save, save all) asks the server once per document.
const saveDebounceMs = 500;

// The first line of a test's name, as the server labels its items; a ScalaTest scope's name has one line per test.
const firstLine = name => name.split('\n')[0];

// The build target's name from its identity (`file:///project#core/Test`), which the tree groups by.
const targetLabel = target => target.slice(target.lastIndexOf('#') + 1);

/**
 * Creates the controller of the Testing view.
 * @param {vscode.ExtensionContext} context
 * @param {import('vscode-languageclient/node').LanguageClient} client
 * @param {(selection: {kind: 'test'} & Selection) => vscode.DebugConfiguration} launchConfiguration the launch
 *   configuration of a test selection, as the Debug lenses start it
 * @returns {{ refresh: () => void }} refetches every item; the extension calls it whenever the server reports the build imported
 */
function registerTests(context, client, launchConfiguration) {
  const controller = vscode.tests.createTestController('scala-analyzer', 'Scala (scala-analyzer)');
  /** The selection of every item of the tree: a target has none. @type {WeakMap<vscode.TestItem, Selection>} */
  const selections = new WeakMap();
  /** The test run whose request is in flight; the server allows one, so results belong to it. @type {{ suite: vscode.TestItem, run: vscode.TestRun } | undefined} */
  let active;
  /** The document refreshes that wait for their debounce, by document URI. @type {Map<string, NodeJS.Timeout>} */
  const pending = new Map();
  let generation = 0;

  /**
   * The item for a server entry, created apart from the tree.
   * @param {ServerTestItem} entry
   */
  function createItem(entry) {
    const id = [entry.target, entry.suite, entry.testName].filter(part => part !== undefined).join('|');
    const item = controller.createTestItem(id, entry.label, client.protocol2CodeConverter.asUri(entry.uri));
    item.range = client.protocol2CodeConverter.asRange(entry.range);
    selections.set(item, entry);
    return item;
  }

  /**
   * The item of a build target among `parents`, created and added when missing.
   * @param {vscode.TestItemCollection} parents
   * @param {string} target
   */
  function targetItem(parents, target) {
    const existing = parents.get(target);
    if (existing) return existing;
    const created = controller.createTestItem(target, targetLabel(target));
    parents.add(created);
    return created;
  }

  /**
   * Adds the entries to the tree: each suite under its target, each test under its suite. The server lists a suite
   * before its tests.
   * @param {ServerTestItem[]} entries
   */
  function addEntries(entries) {
    for (const entry of entries) {
      const target = targetItem(controller.items, entry.target);
      if (entry.testName === undefined) {
        target.children.add(createItem(entry));
      } else {
        target.children.get(`${entry.target}|${entry.suite}`)?.children.add(createItem(entry));
      }
    }
  }

  /**
   * Fetches the entries of one document, or of the whole project.
   * @param {vscode.Uri} [uri]
   * @returns {Promise<ServerTestItem[] | undefined>} undefined when the request failed, which is logged: the tree
   *   keeps what it had, and the next refresh tries again
   */
  async function fetchEntries(uri) {
    try {
      return await client.sendRequest('scalaAnalyzer/testItems', uri ? { uri: client.code2ProtocolConverter.asUri(uri) } : {});
    } catch (error) {
      client.outputChannel.appendLine(`scala-analyzer: test items failed: ${error.message}`);
      return undefined;
    }
  }

  async function refreshAll() {
    const mine = ++generation;
    const entries = await fetchEntries();
    // A newer refresh asked after this one; its answer is the one to show.
    if (!entries || mine !== generation) return;
    controller.items.replace([]);
    addEntries(entries);
  }

  /** @param {vscode.Uri} uri */
  async function refreshDocument(uri) {
    const entries = await fetchEntries(uri);
    if (!entries) return;
    const file = uri.toString();
    controller.items.forEach(target => {
      target.children.forEach(suite => {
        if (suite.uri?.toString() === file) target.children.delete(suite.id);
      });
    });
    addEntries(entries);
    controller.items.forEach(target => {
      if (target.children.size === 0) controller.items.delete(target.id);
    });
  }

  /** @param {vscode.TextDocument} document */
  function onSaved(document) {
    if (document.languageId !== 'scala' && document.languageId !== 'java') return;
    const key = document.uri.toString();
    clearTimeout(pending.get(key));
    pending.set(key, setTimeout(() => {
      pending.delete(key);
      refreshDocument(document.uri);
    }, saveDebounceMs));
  }

  /**
   * The items a request runs, each a suite or a test: a target stands for its suites, and a test of a suite that is
   * itself run is part of that suite's run.
   * @param {vscode.TestRunRequest} request
   * @returns {vscode.TestItem[]}
   */
  function runnableItems(request) {
    /** @type {vscode.TestItem[]} */
    const included = [];
    (request.include ?? gather(controller.items)).forEach(item => included.push(item));
    const excluded = new Set((request.exclude ?? []).map(item => item.id));
    const expanded = included.flatMap(item => (selections.has(item) ? [item] : gather(item.children)));
    const suites = new Set(expanded.filter(item => selections.get(item)?.testName === undefined).map(item => item.id));
    return expanded.filter(item => !excluded.has(item.id) && !(item.parent && suites.has(item.parent.id)));
  }

  /**
   * @param {vscode.TestItemCollection} collection
   * @returns {vscode.TestItem[]}
   */
  function gather(collection) {
    /** @type {vscode.TestItem[]} */
    const items = [];
    collection.forEach(item => items.push(item));
    return items;
  }

  /**
   * Shows one test's progress in the run. A test the tree does not know, such as one a ScalaTest scope or a
   * parameterized method reports, is added under the suite that runs.
   * @param {vscode.TestRun} run
   * @param {vscode.TestItem} suite the item whose run reported it
   * @param {TestResult} result
   */
  function show(run, suite, result) {
    const parent = suite.parent && selections.get(suite)?.testName !== undefined ? suite.parent : suite;
    const selection = selections.get(parent);
    if (!selection) return;
    const id = `${selection.target}|${selection.suite}|${result.testName}`;
    let test = parent.children.get(id);
    if (!test) {
      test = controller.createTestItem(id, firstLine(result.testName), parent.uri);
      parent.children.add(test);
    }

    if (result.outcome === 'started') run.started(test);
    else if (result.outcome === 'passed') run.passed(test, result.durationMs);
    else if (result.outcome === 'ignored') run.skipped(test);
    else {
      const message = new vscode.TestMessage(result.message ?? 'the test failed');
      if (test.uri && test.range) message.location = new vscode.Location(test.uri, test.range);
      run.failed(test, message, result.durationMs);
    }
  }

  /**
   * Runs one suite or test through the server and shows its results as they arrive, ending quietly on cancellation.
   * @param {vscode.TestRun} run
   * @param {vscode.TestItem} item
   * @param {vscode.CancellationToken} token
   */
  async function runItem(run, item, token) {
    const selection = selections.get(item);
    if (!selection || token.isCancellationRequested) return;
    let reported = false;
    active = { suite: item, run };
    run.started(item);
    const listener = client.onNotification('scalaAnalyzer/testResult', /** @param {TestResult} result */ result => {
      reported = true;
      show(run, item, result);
    });

    try {
      const { target, framework, suite, testName } = selection;
      await client.sendRequest(ExecuteCommandRequest.type, {
        command: 'scalaAnalyzer.runTest',
        arguments: [{ target, framework, suite, ...(testName === undefined ? {} : { testName }) }],
      }, token);
      if (!reported) run.errored(item, new vscode.TestMessage('the run reported no test results; see the scala-analyzer output'));
    } catch (error) {
      if (!token.isCancellationRequested) run.errored(item, new vscode.TestMessage(error.message));
    } finally {
      listener.dispose();
      active = undefined;
    }
  }

  /** @type {(request: vscode.TestRunRequest, token: vscode.CancellationToken) => Promise<void>} */
  async function run(request, token) {
    const testRun = controller.createTestRun(request);
    try {
      const items = runnableItems(request);
      items.forEach(item => testRun.enqueued(item));
      // One after the other: the server has one active run.
      for (const item of items) await runItem(testRun, item, token);
    } finally {
      testRun.end();
    }
  }

  /**
   * Debugs the first requested item with the extension's debugger, as the Debug lenses do. A debug session reports no
   * per-test results: the item's outcome shows in the debug console, and the run only lasts as long as the session.
   * @type {(request: vscode.TestRunRequest, token: vscode.CancellationToken) => Promise<void>}
   */
  async function debug(request, token) {
    const testRun = controller.createTestRun(request);
    try {
      const [item] = runnableItems(request);
      const selection = item && selections.get(item);
      if (!selection) return;
      const configuration = launchConfiguration({ kind: 'test', ...selection });
      const folder = item.uri && vscode.workspace.getWorkspaceFolder(item.uri);
      /** @type {vscode.DebugSession | undefined} */
      let session;
      const ended = new Promise(resolve => {
        const subscriptions = [
          vscode.debug.onDidStartDebugSession(started => {
            if (started.configuration.name === configuration.name) session = started;
          }),
          vscode.debug.onDidTerminateDebugSession(terminated => {
            if (terminated === session) {
              subscriptions.forEach(subscription => subscription.dispose());
              resolve(undefined);
            }
          }),
          token.onCancellationRequested(() => {
            if (session) vscode.debug.stopDebugging(session);
            resolve(undefined);
          }),
        ];
      });
      testRun.started(item);
      if (await vscode.debug.startDebugging(folder, configuration)) await ended;
      else testRun.errored(item, new vscode.TestMessage('the debug session did not start'));
    } finally {
      testRun.end();
    }
  }

  controller.refreshHandler = refreshAll;
  controller.createRunProfile('Run', vscode.TestRunProfileKind.Run, run, true);
  controller.createRunProfile('Debug', vscode.TestRunProfileKind.Debug, debug, false);
  context.subscriptions.push(
    controller,
    vscode.workspace.onDidSaveTextDocument(onSaved),
    { dispose: () => pending.forEach(timer => clearTimeout(timer)) },
  );

  return { refresh: () => { refreshAll(); } };
}

module.exports = { registerTests };
