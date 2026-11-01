// Starts the scala-psi-host language server for Scala workspaces, including their Java sources.
// The workspace needs a BSP connection file (.bsp/, e.g. from `sbt bspConfig`).
//
// The server logs to its output channel (and to a log file), reports opening the project as progress, and its state
// as `scalaPsiHost/state` notifications, shown in the status bar; `scalaPsiHost/status` answers what it is doing.
const vscode = require('vscode');
const { LanguageClient, State } = require('vscode-languageclient/node');

let client;

const icons = { starting: '$(loading~spin)', importing: '$(sync~spin)', ready: '$(check)', failed: '$(error)', stopped: '$(circle-slash)' };

async function activate(context) {
  const config = vscode.workspace.getConfiguration('scalaPsiHost');
  const serverPath = config.get('serverPath');
  if (!serverPath) {
    vscode.window.showErrorMessage('scala-psi-host: set "scalaPsiHost.serverPath" to bin/scala-psi-host of a built distribution.');
    return;
  }
  const javaHome = config.get('javaHome');
  const env = {
    ...process.env,
    ...(javaHome ? { JAVA_HOME: javaHome } : {}),
    ...(config.get('debugLog') ? { SCALA_PSI_HOST_LOG: 'debug' } : {}),
  };
  client = new LanguageClient(
    'scalaPsiHost',
    'scala-psi-host',
    { command: serverPath, options: { env } },
    { documentSelector: [{ scheme: 'file', language: 'scala' }, { scheme: 'file', language: 'java' }] },
  );

  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 0);
  status.command = 'scalaPsiHost.showStatus';
  const show = (state, message) => {
    status.text = `${icons[state] || ''} Scala`;
    status.tooltip = `scala-psi-host: ${state}${message ? ` — ${message}` : ''} (click for the status)`;
    status.show();
  };
  show('starting', 'starting the server');
  context.subscriptions.push(status);
  client.onNotification('scalaPsiHost/state', ({ state, message }) => show(state, message));
  client.onDidChangeState(({ newState }) => {
    if (newState === State.Stopped) show('stopped', 'the server is not running');
  });

  context.subscriptions.push(
    vscode.commands.registerCommand('scalaPsiHost.showStatus', async () => {
      const report = await client.sendRequest('scalaPsiHost/status');
      client.outputChannel.appendLine(`status: ${JSON.stringify(report, null, 2)}`);
      client.outputChannel.show(true);
    }),
    vscode.commands.registerCommand('scalaPsiHost.showLog', async () => {
      const { logFile } = await client.sendRequest('scalaPsiHost/status');
      if (logFile) await vscode.window.showTextDocument(vscode.Uri.file(logFile), { preview: false });
      else vscode.window.showWarningMessage('scala-psi-host: the server writes no log file.');
    }),
  );
  context.subscriptions.push(client);
  await client.start();
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };
