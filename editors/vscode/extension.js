// @ts-check
// Starts the scala-analyzer language server for Scala workspaces, including their Java sources.
// The workspace needs a BSP connection file (.bsp/, e.g. from `sbt bspConfig`).
//
// The server logs to its output channel (and to a log file), reports opening the project as progress, and its state
// as `scalaAnalyzer/state` notifications, shown in the status bar; `scalaAnalyzer/status` answers what it is doing.
// The status bar menu and the commands call the server's requests (compile, reimport, inspect, type info, go to super) and show the result;
// compiling is also the workspace build task (Ctrl+Shift+B). The Scala Build view lists the build's targets (buildTargets.js);
// Inspect Code lists the inspections' problems of the whole project (inspect.js). The Testing view lists the project's
// tests and runs them (tests.js).
const vscode = require('vscode');
const { ExecuteCommandRequest, LanguageClient, State } = require('vscode-languageclient/node');
const { registerBuildTargets } = require('./buildTargets');
const { registerInspection } = require('./inspect');
const { registerTests } = require('./tests');

/** @type {LanguageClient} */
let client;

/** @type {vscode.TextEditorDecorationType} */
let typeHint;

const icons = { starting: '$(loading~spin)', importing: '$(sync~spin)', ready: '$(check)', failed: '$(error)', stopped: '$(circle-slash)' };

const menuItems = [
  { label: '$(tools) Compile build', command: 'scalaAnalyzer.compile' },
  { label: '$(sync) Reimport build', command: 'scalaAnalyzer.reimport' },
  { label: '$(search) Inspect code', command: 'scalaAnalyzer.inspect' },
  { label: '$(debug-restart) Restart server', command: 'scalaAnalyzer.restart' },
  { label: '$(info) Show status', command: 'scalaAnalyzer.showStatus' },
  { label: '$(output) Show log file', command: 'scalaAnalyzer.showLog' },
];

// Long enough to read a one-line compile summary.
const statusMessageMs = 5000;

const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`;

// Commands that send requests do nothing but warn while the server is not running.
const needsServer = handler => async (...args) => {
  if (client.isRunning()) return handler(...args);
  vscode.window.showWarningMessage('scala-analyzer: the server is not running');
};

// `onReady` runs whenever the server reports the build imported, which it does after every import and reimport.
function createStatusBar(context, onReady) {
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 0);
  status.command = 'scalaAnalyzer.menu';
  const show = (state, message) => {
    status.text = `${icons[state] || ''} Scala`;
    status.tooltip = `scala-analyzer: ${state}${message ? ` — ${message}` : ''} (click for the menu)`;
    status.show();
  };
  show('starting', 'starting the server');
  context.subscriptions.push(status);
  client.onNotification('scalaAnalyzer/state', ({ state, message }) => {
    show(state, message);
    if (state === 'ready') onReady();
  });
  client.onDidChangeState(({ newState }) => {
    if (newState === State.Stopped) show('stopped', 'the server is not running');
  });
}

async function showTypeInfo() {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return undefined;
  const converter = client.code2ProtocolConverter;
  let text;
  try {
    text = await client.sendRequest('scalaAnalyzer/typeInfo', {
      textDocument: converter.asTextDocumentIdentifier(editor.document),
      range: converter.asRange(editor.selection),
    });
  } catch (error) {
    vscode.window.showErrorMessage(`scala-analyzer: type info failed: ${error.message}`);
    return undefined;
  }
  // Shown at the end of the line, as IntelliJ shows its hint by the caret; the next move or edit clears it.
  const end = editor.document.lineAt(editor.selection.end.line).range.end;
  const label = text ? text.split('\n').join(' · ') : 'no type here';
  editor.setDecorations(typeHint, [{ range: new vscode.Range(end, end), renderOptions: { after: { contentText: label } } }]);
  return text;
}

// Peeks the locations that the server answers `request` with for the caret, as IntelliJ lists the targets of a
// navigation action; a single one is opened directly. `action` names the command in an error, `none` is shown when
// the server has no location.
async function peekFromCaret(request, action, none) {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return;

  const position = editor.selection.active;
  let locations;
  try {
    locations = await client.sendRequest(request, {
      textDocument: client.code2ProtocolConverter.asTextDocumentIdentifier(editor.document),
      position: client.code2ProtocolConverter.asPosition(position),
    });
  } catch (error) {
    vscode.window.showErrorMessage(`scala-analyzer: ${action} failed: ${error.message}`);
    return;
  }

  if (locations.length === 0) {
    vscode.window.setStatusBarMessage(`$(info) ${none}`, statusMessageMs);
    return;
  }
  const targets = locations.map(target => client.protocol2CodeConverter.asLocation(target));
  await vscode.commands.executeCommand('editor.action.goToLocations', editor.document.uri, position, targets, 'gotoAndPeek');
}

// IntelliJ's Go to Super (Ctrl+U): the declarations that the caret's type or member extends, overrides or implements.
const goToSuper = () => peekFromCaret('scalaAnalyzer/superElements', 'go to super', 'No super declaration here');

// IntelliJ's Go to Test (Ctrl+Shift+T): the tests of the caret's class, or the classes that its test class tests.
const goToTest = () => peekFromCaret('scalaAnalyzer/testTargets', 'go to test', 'No test or tested class here');

// Lists the members the class at the caret can override as IntelliJ's Override Members (Ctrl+O) does, and overrides the
// ones picked. The server edits the document version the list was made for, so an edit made meanwhile cancels the
// override. The server's own command, `scalaAnalyzer.overrideMembers`, is registered by the language client; this one
// asks first.
async function chooseMembersToOverride() {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return;

  const converter = client.code2ProtocolConverter;
  const uri = converter.asUri(editor.document.uri);
  const position = converter.asPosition(editor.selection.active);
  const version = editor.document.version;
  let members;
  try {
    members = await client.sendRequest('scalaAnalyzer/overridableMembers', {
      textDocument: converter.asTextDocumentIdentifier(editor.document),
      position,
    });
  } catch (error) {
    vscode.window.showErrorMessage(`scala-analyzer: listing the members to override failed: ${error.message}`);
    return;
  }

  if (members.length === 0) {
    vscode.window.setStatusBarMessage('$(info) No members to override here', statusMessageMs);
    return;
  }
  const items = members.map(({ id, signature, owner }) => ({ label: signature, description: owner, id }));
  const picked = await vscode.window.showQuickPick(items, {
    canPickMany: true,
    matchOnDescription: true,
    placeHolder: 'Members to override',
  });
  if (!picked || picked.length === 0) return;

  try {
    const ids = picked.map(item => item.id);
    await vscode.commands.executeCommand('scalaAnalyzer.overrideMembers', uri, position, ids, version);
  } catch (error) {
    vscode.window.showErrorMessage(`scala-analyzer: override members failed: ${error.message}`);
  }
}

function registerTypeHint(context) {
  typeHint = vscode.window.createTextEditorDecorationType({
    after: { margin: '0 0 0 2em', color: new vscode.ThemeColor('editorCodeLens.foreground'), fontStyle: 'italic' },
  });
  const clear = editor => editor.setDecorations(typeHint, []);
  context.subscriptions.push(
    typeHint,
    vscode.window.onDidChangeTextEditorSelection(({ textEditor }) => clear(textEditor)),
    vscode.workspace.onDidChangeTextDocument(({ document }) => {
      vscode.window.visibleTextEditors.filter(editor => editor.document === document).forEach(clear);
    }),
  );
}

async function reimport() {
  try {
    await client.sendRequest('scalaAnalyzer/reimport');
  } catch (error) {
    vscode.window.showErrorMessage(`scala-analyzer: reimport failed: ${error.message}`);
  }
}

async function compile() {
  const [task] = await vscode.tasks.fetchTasks({ type: 'scala-analyzer' });
  await vscode.tasks.executeTask(task);
}

// The commands of the run lenses: the server ends the run when its request is cancelled.
const runCommands = new Set(['scalaAnalyzer.runTest', 'scalaAnalyzer.runMain']);

// The running debug sessions of this extension's type: a successful compile asks each to redefine the classes the
// compile rewrote, which the server tells their adapters (hot code replace).
/** @type {Set<vscode.DebugSession>} */
const debugSessions = new Set();

// The debugger type of the extension. The Debug lenses run the client command `scalaAnalyzer.debug` (see `debug`), which
// the server does not list or execute.
const debugType = 'scala-analyzer';

// The client command of the Debug lenses.
const debugLensCommand = 'scalaAnalyzer.debug';

// The command that asks the server for a debug session, which answers with the address of its DAP server.
const startDebugSession = 'scalaAnalyzer.startDebugSession';

// The command of the extractions (Extract to val, Extract method): the server sends the edit and answers with the
// position of the name it introduced, once the edit is applied.
const extractCommand = 'scalaAnalyzer.extract';

/**
 * Starts a rename of the name an extraction introduced, as IntelliJ's in-place rename does after it.
 * @param {string} uri the document the extraction was made in
 * @param {import('vscode-languageclient/node').Position | null} position where the name is in the edited document, or
 *   null when nothing was extracted
 */
async function renameExtracted(uri, position) {
  const editor = vscode.window.activeTextEditor;
  if (!position || !editor || client.code2ProtocolConverter.asUri(editor.document.uri) !== uri) return;

  const at = client.protocol2CodeConverter.asPosition(position);
  editor.selection = new vscode.Selection(at, at);
  await vscode.commands.executeCommand('editor.action.rename');
}

/**
 * Sends a run command under a progress notification whose cancel button cancels the request, which the language client
 * does not offer for commands, and renames what an extraction introduced. The other commands go the client's way.
 * @type {NonNullable<import('vscode-languageclient/node').Middleware['executeCommand']>}
 */
async function executeCommand(command, args, next) {
  if (command === extractCommand) {
    const position = await next(command, args);
    await renameExtracted(args[0], position);
    return position;
  }
  if (!runCommands.has(command)) return next(command, args);
  const subject = args[0]?.mainClass ?? args[0]?.suite ?? command;
  const options = { location: vscode.ProgressLocation.Notification, title: `scala-analyzer: running ${subject}`, cancellable: true };
  return vscode.window.withProgress(options, (_progress, token) =>
    client.sendRequest(ExecuteCommandRequest.type, { command, arguments: args }, token)
      .then(undefined, error => client.handleFailedRequest(ExecuteCommandRequest.type, token, error, undefined)));
}

/**
 * The selection of a Debug lens: the members of the server's `scalaAnalyzer.startDebugSession`.
 * @typedef {{kind: 'main' | 'test', target: string, mainClass?: string, framework?: string, suite?: string, testName?: string}} DebugSelection
 */

/**
 * The launch configuration of the extension's debugger for the selection of a Debug lens.
 * @param {DebugSelection} selection the lens's argument
 * @returns {vscode.DebugConfiguration}
 */
function launchConfiguration({ kind, target, mainClass, framework, suite, testName }) {
  const configuration = kind === 'main'
    ? { name: `Debug ${mainClass}`, mainClass }
    : { name: `Debug ${suite}${testName ? `: ${testName.split('\n')[0]}` : ''}`, testClass: suite, framework, testName };
  return { type: debugType, request: 'launch', buildTarget: target, ...configuration };
}

/**
 * Starts a debug session for the selection of a Debug lens.
 * @param {DebugSelection} selection the lens's argument
 */
async function debug(selection) {
  const editor = vscode.window.activeTextEditor;
  const folder = editor && vscode.workspace.getWorkspaceFolder(editor.document.uri);
  await vscode.debug.startDebugging(folder, launchConfiguration(selection));
}

/**
 * The launch configuration of F5 when no launch.json names one: that of the active editor's Debug lens, or of the lens
 * the user picks when the file has several, as the IDE's Debug action offers the run configurations of the file.
 * @returns {Promise<vscode.DebugConfiguration | undefined>} undefined when there is nothing to debug or the pick was
 *   dismissed, which ends the start quietly
 */
async function lensConfiguration() {
  const editor = vscode.window.activeTextEditor;
  /** @type {vscode.CodeLens[]} */
  const lenses = editor ? await vscode.commands.executeCommand('vscode.executeCodeLensProvider', editor.document.uri) : [];
  /** @type {vscode.DebugConfiguration[]} */
  const configurations = lenses
    .filter(lens => lens.command?.command === debugLensCommand)
    .map(lens => launchConfiguration(lens.command.arguments[0]));
  if (configurations.length === 0) {
    vscode.window.showInformationMessage('scala-analyzer: the active file has no main class or test to debug');
    return undefined;
  }
  if (configurations.length === 1) return configurations[0];
  const picked = await vscode.window.showQuickPick(
    configurations.map(configuration => ({ label: configuration.name, configuration })),
    { placeHolder: 'What to debug' });
  return picked?.configuration;
}

/**
 * The server's debug request for a configuration: a running JVM to attach to for an `attach` configuration; otherwise a
 * main class, or a test selection when it has a `testClass`, with the configuration's arguments, JVM options and
 * variables.
 * @param {vscode.DebugConfiguration} configuration the session's configuration
 */
function debugRequest({ request, buildTarget, hostName, port, mainClass, testClass, framework, testName, args, jvmOptions, env }) {
  if (request === 'attach') return { kind: 'attach', target: buildTarget, hostName, port: Number(port) };
  if (mainClass) return { kind: 'main', target: buildTarget, mainClass, args, jvmOptions, env };
  if (testClass) {
    // The runner's arguments are the selection: a test has no arguments of its own.
    if (args) throw new Error('args applies to a mainClass; a testClass is debugged with the runner\'s arguments');
    return { kind: 'test', target: buildTarget, framework, suite: testClass, testName, jvmOptions, env };
  }
  throw new Error('a scala-analyzer launch configuration needs a mainClass or a testClass');
}

/**
 * Fills in what a configuration leaves out. F5 without a launch.json resolves an empty configuration, which becomes the
 * active file's Debug lens; VS Code sends the other configurations as written, without the defaults of the schema in
 * package.json, and the debug adapter needs the host to attach to.
 * @type {vscode.DebugConfigurationProvider}
 */
const debugConfigurationProvider = {
  resolveDebugConfiguration(_folder, configuration) {
    if (!configuration.type && !configuration.request) return lensConfiguration();
    return configuration.request === 'attach' ? { hostName: 'localhost', ...configuration } : configuration;
  },
};

/**
 * Starts the debuggee in the server, or has it attach to the running one; it compiles the target first, and the editor
 * connects to the session's DAP server.
 * @type {vscode.DebugAdapterDescriptorFactory}
 */
const debugAdapterFactory = {
  async createDebugAdapterDescriptor(session) {
    if (!client.isRunning()) throw new Error('the server is not running');
    const { uri } = await client.sendRequest(ExecuteCommandRequest.type, {
      command: startDebugSession,
      arguments: [debugRequest(session.configuration)],
    });
    const { hostname, port } = new URL(uri);
    return new vscode.DebugAdapterServer(Number(port), hostname);
  },
};

// Flips `scalaAnalyzer.inlayHints.xRay`, where the user set it: in the workspace settings when they hold a value, else
// in the user settings. The server reads the setting again when it changes and asks for the hints.
async function toggleXRay() {
  const config = vscode.workspace.getConfiguration('scalaAnalyzer');
  const target = config.inspect('inlayHints.xRay')?.workspaceValue === undefined
    ? vscode.ConfigurationTarget.Global
    : vscode.ConfigurationTarget.Workspace;
  const enabled = !config.get('inlayHints.xRay');
  await config.update('inlayHints.xRay', enabled, target);
  vscode.window.setStatusBarMessage(`scala-analyzer: X-Ray ${enabled ? 'on' : 'off'}`, statusMessageMs);
}

function registerCommands(context) {
  const inspection = registerInspection(context, client);
  const register = (name, handler) => context.subscriptions.push(vscode.commands.registerCommand(`scalaAnalyzer.${name}`, handler));
  register('menu', async () => {
    const pick = await vscode.window.showQuickPick(menuItems, { placeHolder: 'scala-analyzer' });
    if (pick) await vscode.commands.executeCommand(pick.command);
  });
  register('compile', compile);
  register('compileTarget', needsServer(node => compileAndReport(node.id)));
  register('reimport', needsServer(reimport));
  register('inspect', needsServer(inspection.inspect));
  register('clearInspection', inspection.clear);
  register('restart', () => client.restart());
  register('typeInfo', needsServer(showTypeInfo));
  register('goToSuper', needsServer(goToSuper));
  register('goToTest', needsServer(goToTest));
  register('chooseMembersToOverride', needsServer(chooseMembersToOverride));
  register('toggleXRay', toggleXRay);
  register('debug', needsServer(debug));
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory(debugType, debugAdapterFactory),
    vscode.debug.registerDebugConfigurationProvider(debugType, debugConfigurationProvider),
    vscode.debug.onDidStartDebugSession(session => {
      if (session.type === debugType) debugSessions.add(session);
    }),
    vscode.debug.onDidTerminateDebugSession(session => debugSessions.delete(session)));
  register('showStatus', needsServer(async () => {
    const report = await client.sendRequest('scalaAnalyzer/status');
    client.outputChannel.appendLine(`status: ${JSON.stringify(report, null, 2)}`);
    client.outputChannel.show(true);
  }));
  register('showLog', needsServer(async () => {
    const { logFile } = await client.sendRequest('scalaAnalyzer/status');
    if (logFile) await vscode.window.showTextDocument(vscode.Uri.file(logFile), { preview: false });
    else vscode.window.showWarningMessage('scala-analyzer: the server writes no log file.');
  }));
}

// Compiles the whole build, or the target with the given id, and shows the outcome briefly in the status bar. A failed
// compile opens the Problems panel, where the server publishes the diagnostics. `onOutcome` gets the summary line and
// the exit code first, for the task terminal.
/**
 * @param {string | undefined} target
 * @param {(line: string, exitCode: number) => void} [onOutcome]
 */
async function compileAndReport(target, onOutcome = () => {}) {
  const outcome = (line, exitCode) => {
    vscode.window.setStatusBarMessage(`${exitCode === 0 ? '$(check)' : '$(error)'} ${line}`, statusMessageMs);
    onOutcome(line, exitCode);
  };
  try {
    const request = target
      ? client.sendRequest('scalaAnalyzer/compile', { target })
      : client.sendRequest('scalaAnalyzer/compile');
    const { succeeded, errors, warnings } = await request;
    outcome(`${succeeded ? 'Compiled' : 'Compilation failed'}: ${plural(errors, 'error')}, ${plural(warnings, 'warning')}`, succeeded ? 0 : 1);
    if (!succeeded) await vscode.commands.executeCommand('workbench.actions.view.problems');
    else await redefineClasses();
  } catch (error) {
    outcome(error.message, 1);
    vscode.window.showErrorMessage(`scala-analyzer: compile failed: ${error.message}`);
  }
}

// Asks each running debug session to replace the classes that the last compile rewrote, with the adapter's custom
// request; a session that cannot (its program does not support redefinition, or it is not stopped where it can) says
// why in the output channel.
async function redefineClasses() {
  await Promise.all([...debugSessions].map(async session => {
    try {
      await session.customRequest('redefineClasses');
    } catch (error) {
      client.outputChannel.appendLine(`hot code replace in ${session.name}: ${error.message}`);
    }
  }));
}

// The terminal only relays the server's compile result and stays hidden (see compileTask).
function compileTerminal() {
  const written = new vscode.EventEmitter();
  const closed = new vscode.EventEmitter();
  const finish = (line, exitCode) => {
    written.fire(`${line}\r\n`);
    closed.fire(exitCode);
  };
  const run = () => compileAndReport(undefined, finish);
  return { onDidWrite: written.event, onDidClose: closed.event, open: run, close: () => {} };
}

function compileTask(definition) {
  const task = new vscode.Task(definition, vscode.TaskScope.Workspace, 'compile', 'scala-analyzer',
    new vscode.CustomExecution(async () => compileTerminal()), []);
  task.group = vscode.TaskGroup.Build;
  // A reused task terminal comes back into view despite `reveal`; closing it when done keeps the panel as it was.
  task.presentationOptions = { reveal: vscode.TaskRevealKind.Never, close: true };
  return task;
}

function registerBuildTask(context) {
  context.subscriptions.push(vscode.tasks.registerTaskProvider('scala-analyzer', {
    provideTasks: () => [compileTask({ type: 'scala-analyzer', task: 'compile' })],
    resolveTask: task => (task.definition.task === 'compile' ? compileTask(task.definition) : undefined),
  }));
}

async function activate(context) {
  const config = vscode.workspace.getConfiguration('scalaAnalyzer');
  const serverPath = config.get('serverPath');
  if (!serverPath) {
    vscode.window.showErrorMessage('scala-analyzer: set "scalaAnalyzer.serverPath" to bin/scala-analyzer of a built distribution.');
    return;
  }
  const javaHome = config.get('javaHome');
  // The editor's own JAVA_HOME, before the override below: the default JDK for Maven and Gradle, which the server
  // (JDK 25) may be too new to run. Always passed, empty when VS Code had none, so the build tool never inherits the
  // override and finds `java` on the PATH as it would outside the editor.
  const buildJavaHome = config.get('buildJavaHome') || process.env.JAVA_HOME || '';
  const env = {
    ...process.env,
    ...(javaHome ? { JAVA_HOME: javaHome } : {}),
    SCALA_ANALYZER_BUILD_JAVA_HOME: buildJavaHome,
    ...(config.get('debugLog') ? { SCALA_ANALYZER_LOG: 'debug' } : {}),
  };
  client = new LanguageClient(
    'scalaAnalyzer',
    'scala-analyzer',
    { command: serverPath, options: { env } },
    {
      documentSelector: [{ scheme: 'file', language: 'scala' }, { scheme: 'file', language: 'java' }],
      middleware: { executeCommand },
    },
  );

  await vscode.commands.executeCommand('setContext', 'scalaAnalyzer.active', true);
  const refreshTargets = registerBuildTargets(context, client);
  const tests = registerTests(context, client, launchConfiguration);
  createStatusBar(context, () => {
    refreshTargets();
    tests.refresh();
  });
  registerCommands(context);
  registerTypeHint(context);
  registerBuildTask(context);
  context.subscriptions.push(client);
  await client.start();
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };
