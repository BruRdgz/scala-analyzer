const assert = require('node:assert/strict');
const path = require('node:path');
const vscode = require('vscode');

// A first BSP import downloads/starts sbt; use the same ten-minute budget as the stdio smoke test.
const timeoutMs = 10 * 60 * 1000;

async function journey() {
  const extension = vscode.extensions.getExtension('brurdgz.scala-analyzer');
  assert(extension, 'the packaged extension must be installed');
  await extension.activate();
  assert(extension.isActive, 'the installed extension must activate');
  const root = vscode.workspace.workspaceFolders[0].uri.fsPath;
  const uri = vscode.Uri.file(path.join(root, 'app/src/main/scala/app/Main.scala'));
  const document = await vscode.workspace.openTextDocument(uri);
  await vscode.window.showTextDocument(document);
  const line = document.getText().split('\n').findIndex(text => text.includes('Greeting("world")'));
  const position = new vscode.Position(line, document.lineAt(line).text.lastIndexOf('.text') + 2);
  const hover = await vscode.commands.executeCommand('vscode.executeHoverProvider', uri, position);
  assert(hover.some(item => item.contents.some(content => (content.value || '').includes('def text: String'))),
    'the installed VSIX must return the server hover');
  const definitions = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', uri, position);
  assert(definitions.some(item => (item.uri || item.targetUri).fsPath.endsWith('Greeting.scala')),
    'the installed VSIX must navigate to the source declaration');
}

exports.run = async () => {
  let timer;
  try {
    await Promise.race([
      journey(),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('VSIX journey timed out')), timeoutMs); }),
    ]);
  } finally {
    clearTimeout(timer);
  }
};
