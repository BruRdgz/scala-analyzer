// @ts-check
// The "Inspection Results" view: the problems of one Inspect Code run as the IDE's tool window lists them, by group,
// inspection and file (groups and inspections as the server names them: `scalaAnalyzer/inspect`). The tree is a snapshot
// of that run. It follows the edits the user makes to the listed files, so that a problem still points at its code, and
// drops a problem whose code was edited or whose fix was applied.
const path = require('path');
const vscode = require('vscode');

/**
 * @typedef {{ id: string, displayName: string, groupPath: string[] }} InspectionDescription
 * @typedef {{ uri: vscode.Uri, diagnostics: vscode.Diagnostic[] }} InspectedFile
 * @typedef {{
 *   kind: 'group' | 'inspection' | 'file' | 'problem', id: string, label: string, parent?: Node, children: Node[],
 *   uri?: vscode.Uri, diagnostic?: vscode.Diagnostic, range?: vscode.Range,
 * }} Node
 */

const { Expanded, Collapsed, None } = vscode.TreeItemCollapsibleState;

/** The Problems panel's colors for a problem's severity; hints are shown as information. */
const severityIcons = [
  new vscode.ThemeIcon('error', new vscode.ThemeColor('problemsErrorIcon.foreground')),
  new vscode.ThemeIcon('warning', new vscode.ThemeColor('problemsWarningIcon.foreground')),
  new vscode.ThemeIcon('info', new vscode.ThemeColor('problemsInfoIcon.foreground')),
  new vscode.ThemeIcon('info', new vscode.ThemeColor('problemsInfoIcon.foreground')),
];

const codeOf = (/** @type {vscode.Diagnostic} */ diagnostic) =>
  String(typeof diagnostic.code === 'object' ? diagnostic.code.value : diagnostic.code);

/** The problems below a node. @returns {Node[]} */
const problemsOf = node => (node.kind === 'problem' ? [node] : node.children.flatMap(problemsOf));

/** The most severe level among the problems below a node: `vscode.DiagnosticSeverity` orders errors first. */
const worstSeverity = node => Math.min(...problemsOf(node).map(problem => problem.diagnostic?.severity ?? vscode.DiagnosticSeverity.Hint));

const byLabel = (a, b) => a.label.localeCompare(b.label);
const byPosition = (a, b) => a.range.start.compareTo(b.range.start);

/**
 * Builds the tree of a run: group nodes, then inspection nodes, then file nodes, then problem nodes by position.
 * Inspections that share a display name and group share a node, as the IDE lists unused imports with unused declarations.
 * @param {InspectedFile[]} files
 * @param {InspectionDescription[]} inspections
 * @returns {Node[]}
 */
function buildTree(files, inspections) {
  const described = new Map(inspections.map(description => [description.id, description]));
  /** @type {Node[]} */
  const roots = [];

  /** Returns the child of `parent` with this kind and label, adding it when it is not there yet. */
  const childOf = (parent, kind, label, props = {}) => {
    const siblings = parent ? parent.children : roots;
    let node = siblings.find(sibling => sibling.kind === kind && sibling.label === label);
    if (!node) {
      node = { kind, label, id: `${parent?.id ?? ''}/${kind}:${label}`, parent, children: [], ...props };
      siblings.push(node);
    }
    return node;
  };

  for (const { uri, diagnostics } of files) {
    diagnostics.forEach((diagnostic, index) => {
      const code = codeOf(diagnostic);
      const { displayName, groupPath } = described.get(code) ?? { displayName: code, groupPath: [] };
      const group = groupPath.reduce((parent, name) => childOf(parent, 'group', name), undefined);
      const inspection = childOf(group, 'inspection', displayName);
      const file = childOf(inspection, 'file', path.basename(uri.fsPath), { uri });
      file.children.push({ kind: 'problem', id: `${file.id}/${index}`, label: diagnostic.message.split('\n')[0], parent: file,
        children: [], uri, diagnostic, range: diagnostic.range });
    });
  }

  const sort = nodes => {
    nodes.sort(nodes[0]?.kind === 'problem' ? byPosition : byLabel);
    nodes.forEach(node => sort(node.children));
  };
  sort(roots);
  return roots;
}

/**
 * Where a position goes when `change` replaces the text before it, which must end at or before the position.
 * @param {vscode.Position} position
 * @param {vscode.TextDocumentContentChangeEvent} change
 */
function movePast(position, change) {
  const lines = change.text.split(/\r\n|\r|\n/);
  const { start, end } = change.range;
  const line = position.line - end.line + start.line + lines.length - 1;
  if (position.line > end.line) return new vscode.Position(line, position.character);
  // On the line where the change ends, the text after it follows the change's last line.
  const lastLineStart = lines.length === 1 ? start.character : 0;
  return new vscode.Position(line, lastLineStart + lines[lines.length - 1].length + position.character - end.character);
}

/**
 * Where a range goes after a change, or nothing when the change touches its text.
 * @param {vscode.Range} range
 * @param {vscode.TextDocumentContentChangeEvent} change
 */
function moveAcross(range, change) {
  if (range.end.isBeforeOrEqual(change.range.start)) return range;
  if (change.range.end.isBeforeOrEqual(range.start)) {
    return new vscode.Range(movePast(range.start, change), movePast(range.end, change));
  }
  return undefined;
}

/** @implements {vscode.TreeDataProvider<Node>} */
class InspectionResults {
  /**
   * @param {(uri: vscode.Uri, diagnostics: vscode.Diagnostic[]) => void} onFileChanged called with the problems that
   *   remain listed in a file, where its problems moved or one left
   */
  constructor(onFileChanged) {
    this.onFileChanged = onFileChanged;
    this.changed = new vscode.EventEmitter();
    this.onDidChangeTreeData = this.changed.event;
    /** @type {Node[]} */
    this.roots = [];
    /** The problem nodes by file URI, for the edits of the listed files. @type {Map<string, Node[]>} */
    this.problemsByFile = new Map();
  }

  /**
   * Shows the results of a run in place of the previous ones.
   * @param {InspectedFile[]} files
   * @param {InspectionDescription[]} inspections
   */
  replace(files, inspections) {
    this.roots = buildTree(files, inspections);
    this.problemsByFile = new Map();
    for (const problem of this.roots.flatMap(problemsOf)) {
      const key = String(problem.uri);
      this.problemsByFile.set(key, [...(this.problemsByFile.get(key) ?? []), problem]);
    }
    this.changed.fire(undefined);
  }

  clear() {
    this.replace([], []);
  }

  /**
   * Removes a problem, and the nodes above it that are left without problems.
   * @param {Node} problem
   */
  remove(problem) {
    this.detach(problem);
    this.changed.fire(undefined);
    this.reportFile(problem.uri);
  }

  /** Tells the owner of the diagnostics what a file still lists, at the problems' present ranges. */
  reportFile(uri) {
    const remaining = (this.problemsByFile.get(String(uri)) ?? []).map(({ diagnostic, range }) =>
      Object.assign(new vscode.Diagnostic(range, diagnostic.message, diagnostic.severity),
        { code: diagnostic.code, source: diagnostic.source, tags: diagnostic.tags }));
    this.onFileChanged(uri, remaining);
  }

  /** @param {Node} problem */
  detach(problem) {
    let top = problem;
    while (top.parent && top.parent.children.length === 1) top = top.parent;
    const siblings = top.parent ? top.parent.children : this.roots;
    const index = siblings.indexOf(top);
    if (index < 0) return;
    siblings.splice(index, 1);
    const key = String(problem.uri);
    this.problemsByFile.set(key, (this.problemsByFile.get(key) ?? []).filter(listed => listed !== problem));
  }

  /**
   * Keeps the listed problems of an edited file on their code; those whose code the edit touched are removed.
   * The changes of one event are in descending order, so each range is still in the coordinates they describe.
   * @param {vscode.TextDocumentChangeEvent} event
   */
  followEdit({ document, contentChanges }) {
    const problems = this.problemsByFile.get(String(document.uri)) ?? [];
    if (contentChanges.length === 0 || problems.length === 0) return;
    for (const problem of problems) {
      const range = contentChanges.reduce((current, change) => current && moveAcross(current, change), problem.range);
      if (range) problem.range = range;
      else this.detach(problem);
    }
    this.changed.fire(undefined);
    this.reportFile(document.uri);
  }

  /** @param {Node} node */
  getTreeItem(node) {
    const count = problemsOf(node).length;
    if (node.kind === 'problem') {
      const { start } = node.range;
      const item = new vscode.TreeItem(node.label, None);
      item.id = node.id;
      item.contextValue = 'problem';
      item.description = `${start.line + 1}:${start.character + 1}`;
      item.tooltip = `${node.diagnostic.message}\n${codeOf(node.diagnostic)}`;
      item.iconPath = severityIcons[node.diagnostic.severity];
      item.command = { command: 'scalaAnalyzer.revealInspectionProblem', title: 'Show Problem', arguments: [node] };
      return item;
    }
    const item = new vscode.TreeItem(node.uri ?? node.label, node.kind === 'file' ? Collapsed : Expanded);
    item.id = node.id;
    if (node.kind === 'file') {
      const directory = vscode.workspace.asRelativePath(path.dirname(node.uri.fsPath), false);
      item.description = `${count} · ${directory}`;
      item.iconPath = vscode.ThemeIcon.File;
    } else {
      item.description = String(count);
      item.iconPath = node.kind === 'group' ? new vscode.ThemeIcon('folder') : severityIcons[worstSeverity(node)];
    }
    return item;
  }

  /** @param {Node} [node] */
  getChildren(node) {
    return node ? node.children : this.roots;
  }
}

module.exports = { InspectionResults, codeOf };
