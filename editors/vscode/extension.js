// @ts-check
// Starts the scala-analyzer language server for Scala workspaces, including their Java sources.
// The workspace needs a BSP connection file (.bsp/, e.g. from `sbt bspConfig`).
//
// The server logs to its output channel (and to a log file), reports opening the project as progress, and its state
// as `scalaAnalyzer/state` notifications, shown in the status bar; `scalaAnalyzer/status` answers what it is doing.
// The status bar menu and the commands call the server's requests (compile, reimport, type info) and show the result;
// compiling is also the workspace build task (Ctrl+Shift+B). The Scala Build view lists the build's targets (buildTargets.js).
const vscode = require('vscode');
const { ExecuteCommandRequest, LanguageClient, State } = require('vscode-languageclient/node');
const { registerBuildTargets } = require('./buildTargets');

/** @type {LanguageClient} */
let client;

/** @type {vscode.TextEditorDecorationType} */
let typeHint;

const icons = { starting: '$(loading~spin)', importing: '$(sync~spin)', ready: '$(check)', failed: '$(error)', stopped: '$(circle-slash)' };

const menuItems = [
  { label: '$(tools) Compile build', command: 'scalaAnalyzer.compile' },
  { label: '$(sync) Reimport build', command: 'scalaAnalyzer.reimport' },
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

/**
 * Sends a run command under a progress notification whose cancel button cancels the request, which the language client
 * does not offer for commands. The other commands go the client's way.
 * @type {NonNullable<import('vscode-languageclient/node').Middleware['executeCommand']>}
 */
function executeCommand(command, args, next) {
  if (!runCommands.has(command)) return next(command, args);
  const subject = args[0]?.mainClass ?? args[0]?.suite ?? command;
  const options = { location: vscode.ProgressLocation.Notification, title: `scala-analyzer: running ${subject}`, cancellable: true };
  return vscode.window.withProgress(options, (_progress, token) =>
    client.sendRequest(ExecuteCommandRequest.type, { command, arguments: args }, token)
      .then(undefined, error => client.handleFailedRequest(ExecuteCommandRequest.type, token, error, undefined)));
}

function registerCommands(context) {
  const register = (name, handler) => context.subscriptions.push(vscode.commands.registerCommand(`scalaAnalyzer.${name}`, handler));
  register('menu', async () => {
    const pick = await vscode.window.showQuickPick(menuItems, { placeHolder: 'scala-analyzer' });
    if (pick) await vscode.commands.executeCommand(pick.command);
  });
  register('compile', compile);
  register('compileTarget', needsServer(node => compileAndReport(node.id)));
  register('reimport', needsServer(reimport));
  register('restart', () => client.restart());
  register('typeInfo', needsServer(showTypeInfo));
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
  } catch (error) {
    outcome(error.message, 1);
    vscode.window.showErrorMessage(`scala-analyzer: compile failed: ${error.message}`);
  }
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
  const env = {
    ...process.env,
    ...(javaHome ? { JAVA_HOME: javaHome } : {}),
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
  createStatusBar(context, registerBuildTargets(context, client));
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
