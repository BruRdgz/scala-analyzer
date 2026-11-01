#!/usr/bin/env python3
"""Exercise an extracted server over stdio: initialize, diagnostics, hover, definition, and shutdown."""
import argparse
import json
import pathlib
import subprocess
import sys
import threading
import time
from urllib.parse import unquote, urlparse

# A first BSP import can download and launch the build server.
TIMEOUT_SECONDS = 600


def exercise(process, args):
    stdin, stdout = process.stdin, process.stdout
    messages = []
    failures = []
    closed = False
    condition = threading.Condition()

    def read():
        nonlocal closed
        try:
            while True:
                headers = {}
                while True:
                    header = stdout.readline()
                    if not header:
                        return
                    if header in (b'\r\n', b'\n'):
                        break
                    name, value = header.decode().split(':', 1)
                    headers[name.lower()] = value.strip()
                length = int(headers['content-length'])
                body = stdout.read(length)
                if len(body) != length:
                    raise RuntimeError('truncated LSP response')
                message = json.loads(body)
                with condition:
                    messages.append(message)
                    condition.notify_all()
        except Exception as failure:
            with condition:
                failures.append(failure)
        finally:
            with condition:
                closed = True
                condition.notify_all()

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    next_id = 0

    def send(method, params, request=True):
        nonlocal next_id
        message = dict(jsonrpc='2.0', method=method, params=params)
        if request:
            next_id += 1
            message['id'] = next_id
        data = json.dumps(message).encode()
        stdin.write(b'Content-Length: %d\r\n\r\n' % len(data) + data)
        stdin.flush()
        return message.get('id')

    def wait_for(matches, description):
        deadline = time.monotonic() + TIMEOUT_SECONDS
        with condition:
            while True:
                for index, message in enumerate(messages):
                    if matches(message):
                        return messages.pop(index)
                if failures:
                    raise RuntimeError('invalid server transport') from failures[0]
                if closed or process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError(f'no {description}; server exit code: {process.poll()}')
                condition.wait(timeout=1)

    def request(method, params):
        request_id = send(method, params)
        response = wait_for(lambda message: message.get('id') == request_id, method)
        if 'error' in response:
            raise RuntimeError(f'{method} failed: {response["error"]}')
        return response['result']

    project = args.project.resolve()
    source = args.source.resolve()
    uri = source.as_uri()
    document = dict(uri=uri)
    position = dict(line=args.line, character=args.column)
    initialized = request('initialize', dict(processId=None, rootUri=project.as_uri(), capabilities={},
        workspaceFolders=[dict(uri=project.as_uri(), name=project.name)]))
    for capability in ('hoverProvider', 'definitionProvider'):
        if not initialized['capabilities'].get(capability):
            raise RuntimeError(f'missing capability: {capability}')
    if args.expected_version and initialized['serverInfo']['version'] != args.expected_version:
        raise RuntimeError(f'wrong packaged version: {initialized["serverInfo"]}')
    send('initialized', {}, request=False)
    send('textDocument/didOpen', dict(textDocument=dict(uri=uri, languageId='scala', version=1, text=source.read_text())), request=False)
    published = wait_for(lambda message: message.get('method') == 'textDocument/publishDiagnostics'
                         and message['params']['uri'] == uri, 'diagnostics')
    diagnostics = published['params']['diagnostics']
    errors = [problem for problem in diagnostics if problem.get('severity') == 1]
    if errors:
        raise RuntimeError(f'correct fixture has errors: {errors}')
    hover = request('textDocument/hover', dict(textDocument=document, position=position))
    if not hover or not hover.get('contents') or not hover['contents'].get('value', '').strip():
        raise RuntimeError(f'empty hover at {position}')
    definitions = request('textDocument/definition', dict(textDocument=document, position=position))
    if not definitions:
        raise RuntimeError(f'empty definition at {position}')
    for definition in definitions:
        location = urlparse(definition['uri'])
        if location.scheme != 'file' or not pathlib.Path(unquote(location.path)).is_file():
            raise RuntimeError(f'definition target does not exist: {definition}')
    print(json.dumps(dict(server=initialized['serverInfo'], diagnostics=diagnostics,
                          hover=hover, definitions=definitions), indent=2))
    request('shutdown', None)
    send('exit', None, request=False)
    stdin.close()
    if process.wait(timeout=60) != 0:
        raise RuntimeError(f'server exited with {process.returncode}')
    reader.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('server')
    parser.add_argument('project', type=pathlib.Path)
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('line', type=int)
    parser.add_argument('column', type=int)
    parser.add_argument('--expected-version')
    args = parser.parse_args()
    process = subprocess.Popen([str(pathlib.Path(args.server).resolve())], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    try:
        exercise(process, args)
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if not process.stdin.closed:
            process.stdin.close()
        process.stdout.close()


if __name__ == '__main__':
    main()
