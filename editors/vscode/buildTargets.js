// @ts-check
// The "Scala Build" view in the Explorer: the build's targets with their dependencies, libraries and source files, as the
// server imported them (`scalaAnalyzer/buildTargets`). The view only presents that answer. It is fetched when the view is
// first shown and again on every refresh, which the extension triggers whenever the server reports the build imported.
const path = require('path');
const vscode = require('vscode');

/**
 * @typedef {{ id: string, name: string, scalaVersion: string, dependsOn: string[], libraries: string[], sourceFileCount: number }} BuildTarget
 * @typedef {{ roots: vscode.TreeItem[], children: Map<vscode.TreeItem, vscode.TreeItem[]> }} Snapshot
 */

const collapsed = vscode.TreeItemCollapsibleState.Collapsed;

const item = (label, iconName, props = {}) =>
  Object.assign(new vscode.TreeItem(label), { iconPath: new vscode.ThemeIcon(iconName), ...props });

/** @param {BuildTarget[]} targets @returns {Snapshot} */
function snapshotOf(targets) {
  /** @type {Map<vscode.TreeItem, vscode.TreeItem[]>} */
  const children = new Map();
  const names = new Map(targets.map(({ id, name }) => [id, name]));

  const group = (label, iconName, members) => {
    const node = item(label, iconName, { collapsibleState: collapsed });
    children.set(node, members);
    return node;
  };
  const dependency = id => item(names.get(id) ?? id, 'package', {
    command: { command: 'scalaAnalyzer.revealTarget', title: 'Reveal Target', arguments: [id] },
  });
  const library = jar => item(path.basename(jar), 'file-binary', { description: path.basename(path.dirname(jar)), tooltip: jar });

  const roots = targets.map(({ id, name, scalaVersion, dependsOn, libraries, sourceFileCount }) => {
    const root = item(name, 'package', { id, description: `Scala ${scalaVersion}`, tooltip: id, collapsibleState: collapsed, contextValue: 'target' });
    const parts = [];
    if (dependsOn.length > 0) parts.push(group('Depends on', 'references', dependsOn.map(dependency)));
    if (libraries.length > 0) parts.push(group(`Libraries (${libraries.length})`, 'library', libraries.map(library)));
    parts.push(item(sourceFileCount === 1 ? '1 source file' : `${sourceFileCount} source files`, 'file-code'));
    children.set(root, parts);
    return root;
  });
  return { roots, children };
}

/**
 * Returns the function that refetches the targets.
 * @param {vscode.ExtensionContext} context
 * @param {import('vscode-languageclient/node').LanguageClient} client
 */
function registerBuildTargets(context, client) {
  /** @type {Promise<Snapshot> | undefined} */
  let snapshot;
  const changed = new vscode.EventEmitter();

  // A failed fetch leaves the tree empty and only logs: it is retried on the next refresh, and an error toast on each
  // one (for instance while the server still starts) would only be noise.
  async function fetchTargets() {
    try {
      return await client.sendRequest('scalaAnalyzer/buildTargets');
    } catch (error) {
      client.outputChannel.appendLine(`scala-analyzer: build targets failed: ${error.message}`);
      return [];
    }
  }
  const current = () => (snapshot ??= fetchTargets().then(snapshotOf));

  const treeView = vscode.window.createTreeView('scalaAnalyzer.buildTargets', {
    treeDataProvider: {
      onDidChangeTreeData: changed.event,
      getTreeItem: node => node,
      getChildren: async node => {
        const { roots, children } = await current();
        return node ? children.get(node) ?? [] : roots;
      },
      // `reveal` asks for the parents, and only the targets, which are roots, are revealed.
      getParent: () => undefined,
    },
  });
  context.subscriptions.push(changed, treeView, vscode.commands.registerCommand('scalaAnalyzer.revealTarget', async id => {
    const { roots } = await current();
    const root = roots.find(target => target.id === id);
    if (root) await treeView.reveal(root, { select: true, focus: true });
  }));

  return () => {
    snapshot = undefined;
    changed.fire(undefined);
  };
}

module.exports = { registerBuildTargets };
