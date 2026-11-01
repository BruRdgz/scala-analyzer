// Install the packaged VSIX into an isolated VS Code, then exercise it through the editor API.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { downloadAndUnzipVSCode, resolveCliArgsFromVSCodeExecutablePath, runTests } = require('@vscode/test-electron');

async function main() {
  const [vsix, server, workspace] = process.argv.slice(2).map(value => path.resolve(value));
  if (!vsix || !server || !workspace) throw new Error('usage: npm run test:installed -- <vsix> <server> <workspace>');
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'scala-psi-host-vscode-'));
  try {
    const userData = path.join(profile, 'user');
    const extensions = path.join(profile, 'extensions');
    fs.mkdirSync(path.join(userData, 'User'), { recursive: true });
    fs.writeFileSync(path.join(userData, 'User/settings.json'), JSON.stringify({
      'scalaPsiHost.serverPath': server,
      'scalaPsiHost.javaHome': process.env.JAVA_HOME,
      'security.workspace.trust.enabled': false,
      'extensions.autoUpdate': false,
      'extensions.autoCheckUpdates': false,
      'update.mode': 'none',
      'telemetry.telemetryLevel': 'off',
    }));
    const executable = await downloadAndUnzipVSCode('1.106.0');
    const [cli, ...args] = resolveCliArgsFromVSCodeExecutablePath(executable);
    const isolation = ['--user-data-dir', userData, '--extensions-dir', extensions, '--no-sandbox'];
    for (const extension of ['scala-lang.scala', vsix]) {
      const result = spawnSync(cli, [...args, ...isolation, '--install-extension', extension, '--force'], { stdio: 'inherit' });
      if (result.error) throw result.error;
      if (result.status !== 0) throw new Error(`VSIX installation failed: ${extension} (${result.status})`);
    }
    await runTests({
      vscodeExecutablePath: executable,
      extensionDevelopmentPath: path.join(__dirname, 'driver'),
      extensionTestsPath: path.join(__dirname, 'suite.js'),
      extensionTestsEnv: { SCALA_PSI_HOST_STATE: path.join(profile, 'server-state') },
      launchArgs: [workspace, ...isolation, '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes'],
    });
  } finally {
    const logs = path.join(profile, 'server-state/logs');
    if (fs.existsSync(logs)) fs.cpSync(logs, path.resolve(__dirname, '../../../target/vscode-test-logs'), { recursive: true });
    fs.rmSync(profile, { recursive: true, force: true });
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
