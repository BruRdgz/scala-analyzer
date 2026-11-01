#!/usr/bin/env python3
"""Read release coordinates, validate versions, and package the distribution that CI tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def coordinates():
    version = re.fullmatch(r'ThisBuild / version := "([0-9]+\.[0-9]+\.[0-9]+)"\s*',
                           (ROOT / 'version.sbt').read_text()).group(1)
    build = (ROOT / 'build.sbt').read_text()
    platform = re.search(r'^val platformVersion = "([^"]+)"$', build, re.M).group(1)
    plugin = re.search(r'^val scalaPluginRevision = "([0-9a-f]{40})"$', build, re.M).group(1)
    extension = json.loads((ROOT / 'editors/vscode/package.json').read_text())
    if extension['version'] != version:
        raise ValueError('server and VSIX versions differ')
    if os.environ.get('GITHUB_REF_TYPE') == 'tag' and os.environ['GITHUB_REF_NAME'] != version:
        raise ValueError('release tag must equal the version in version.sbt and package.json')
    return dict(version=version, platform=platform, plugin=plugin)


def package():
    info = coordinates()
    distribution = ROOT / 'target/dist'
    with zipfile.ZipFile(distribution / 'lib/scala-psi-host.jar') as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode()
        if f'Implementation-Version: {info["version"]}\r\n' not in manifest:
            raise ValueError('distribution jar has the wrong release version')
    output = ROOT / 'target/release'
    output.mkdir(parents=True, exist_ok=True)
    name = f'scala-psi-host-{info["version"]}'
    with tarfile.open(output / f'{name}.tar.gz', 'w:gz') as archive:
        archive.add(distribution, arcname=name)
    vsix = output / f'{name}.vsix'
    with zipfile.ZipFile(vsix) as archive:
        extension = json.loads(archive.read('extension/package.json'))
        if extension['version'] != info['version']:
            raise ValueError('packaged VSIX has the wrong version')
        archive.getinfo('extension/extension.js')
        archive.getinfo('extension/node_modules/vscode-languageclient/lib/node/main.js')
    info['commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    info['java'] = subprocess.run(['java', '-version'], check=True, capture_output=True, text=True).stderr.strip()
    info['run'] = '/'.join([os.environ.get('GITHUB_SERVER_URL', 'https://github.com'),
                            os.environ.get('GITHUB_REPOSITORY', 'local'), 'actions/runs',
                            os.environ.get('GITHUB_RUN_ID', 'local')])
    info['distribution_files'] = {
        str(path.relative_to(distribution)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(distribution.rglob('*')) if path.is_file()
    }
    (output / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')
    assets = [output / f'{name}.tar.gz', vsix, output / 'build-info.json']
    (output / 'SHA256SUMS').write_text(''.join(
        f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n' for path in assets))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['config', 'package'])
    args = parser.parse_args()
    if args.command == 'config':
        for key, value in coordinates().items():
            print(f'{key}={value}')
    else:
        package()
