#!/usr/bin/env python3
"""Dependency-free HTTP/STDIO contract probe, also usable against published images."""
import argparse
import concurrent.futures
import json
import http.client
import queue
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

PROTOCOL = '2025-11-25'
MODEL = ('INTERLIS 2.4;\nMODEL Smoke (de) AT "https://example.org" VERSION "2026-01-01" =\n'
         ' TOPIC Data = CLASS Item = name : MANDATORY TEXT*20; END Item; END Data; END Smoke.\n')

class Client:
    def __init__(self, url=None, command=None):
        self.url, self.session, self.counter = url, None, 0
        self.lock, self.pending, self.errors = threading.Lock(), {}, []
        if command:
            self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            def read():
                try:
                    for line in self.process.stdout:
                        message = json.loads(line)
                        if 'id' in message:
                            with self.lock:
                                waiter = self.pending.get(message['id'])
                            if waiter:
                                waiter.put(message)
                except Exception as error:
                    self.errors.append(str(error))
            self.reader = threading.Thread(target=read, daemon=True)
            self.reader.start()

    def send(self, message):
        if self.url:
            headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
                       'MCP-Protocol-Version': PROTOCOL}
            if self.session:
                headers['Mcp-Session-Id'] = self.session
            request = urllib.request.Request(self.url, data=json.dumps(message).encode(), headers=headers)
            with urllib.request.urlopen(request, timeout=90) as response:
                self.session = response.headers.get('Mcp-Session-Id', self.session)
                if 'id' not in message:
                    response.read()
                    return
                if 'text/event-stream' in response.headers.get('Content-Type', ''):
                    for line in response:
                        if line.startswith(b'data:'):
                            result = json.loads(line[5:])
                            if result.get('id') == message['id']:
                                return result
                    raise AssertionError('SSE stream closed without a matching response')
                return json.load(response)
        with self.lock:
            self.process.stdin.write(json.dumps(message) + '\n')
            self.process.stdin.flush()

    def call(self, method, params=None):
        with self.lock:
            self.counter += 1
            ident = self.counter
            waiter = self.pending[ident] = queue.Queue()
        message = {'jsonrpc': '2.0', 'id': ident, 'method': method, 'params': params or {}}
        try:
            response = self.send(message) if self.url else (self.send(message) or waiter.get(timeout=90))
            assert response['id'] == ident, response
            assert 'error' not in response, response
            assert not self.errors, self.errors
            return response['result']
        finally:
            with self.lock:
                self.pending.pop(ident, None)

    def tool(self, name, arguments):
        result = self.call('tools/call', {'name': name, 'arguments': arguments})
        assert not result.get('isError'), result
        return result.get('structuredContent') or json.loads(result['content'][0]['text'])

    def close(self):
        if self.url:
            if self.session:
                request = urllib.request.Request(self.url, method='DELETE', headers={
                    'Mcp-Session-Id': self.session, 'MCP-Protocol-Version': PROTOCOL})
                with urllib.request.urlopen(request, timeout=10) as response:
                    assert response.status in (200, 202, 204)
        else:
            self.process.stdin.close()
            try:
                code = self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=10)
                raise AssertionError('STDIO server did not stop after EOF')
            self.reader.join(timeout=2)
            assert code == 0, f'Server exited with {code}'
            assert not self.errors, self.errors


def probe(client, kind, output=None, theme='demo/standorte'):
    initialized = client.call('initialize', {'protocolVersion': PROTOCOL, 'capabilities': {},
        'clientInfo': {'name': 'mcp-contract-probe', 'version': '1'}})
    assert initialized['serverInfo']['name'] == {'interlis':'interlis-mcp', 'netl':'netl-mcp', 'suite':'mcp-suite'}[kind]
    client.send({'jsonrpc':'2.0', 'method':'notifications/initialized'})
    catalog = {'tools': client.call('tools/list')['tools'], 'resources': [], 'prompts': [], 'results': {}}
    names = [tool['name'] for tool in catalog['tools']]
    assert len(names) == len(set(names)), 'Duplicate tool names'
    calls = []
    if kind in ('interlis', 'suite'):
        assert {'validateIliModel','reviewIliModel','authorIliModel'} <= set(names)
        catalog['resources'] = client.call('resources/list')['resources']
        catalog['prompts'] = client.call('prompts/list')['prompts']
        client.call('resources/read', {'uri':'interlis://knowledge/agent-workflow'})
        client.call('prompts/get', {'name':'interlis-modeling-agent', 'arguments':{}})
        result = client.tool('validateIliModel', {'modelText':MODEL})
        assert result['valid'] is True, result
        catalog['results']['validateIliModel'] = result
        calls.append(('validateIliModel', {'modelText':MODEL}))
    if kind in ('netl', 'suite'):
        assert {'config_context','config_validate','schema_create','job_test'} <= set(names)
        save = next(tool for tool in catalog['tools'] if tool['name']=='config_save')
        assert save['inputSchema']['properties']['manifest']['properties']['formatVersion']['type']=='integer'
        result = client.tool('config_context', {'theme':theme})
        assert result['status']=='OK', result
        checked = client.tool('config_validate', {'theme':theme, 'manifest':result['manifest']})
        assert checked['status']=='VALID', checked
        catalog['results']['config_validate'] = checked
        calls.append(('config_context', {'theme':theme}))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        futures = [executor.submit(client.tool, *calls[index % len(calls)]) for index in range(24)]
        for future in futures:
            assert future.result()
    if output:
        Path(output).write_text(json.dumps(catalog, ensure_ascii=False, indent=2)+'\n')
    print(f'{kind}: {len(names)} tools; initialization, catalogs, calls and 24 parallel responses OK')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['interlis','netl','suite'], required=True)
    parser.add_argument('--url')
    parser.add_argument('--catalog')
    parser.add_argument('--theme', default='demo/standorte')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1]==['--'] else args.command
    assert bool(args.url) != bool(command), 'Specify --url or a command after --'
    client = None
    try:
        if args.url:
            deadline = time.monotonic()+90
            while True:
                try:
                    with urllib.request.urlopen(urllib.request.Request(args.url, method='GET'), timeout=2):
                        break
                except urllib.error.HTTPError:
                    break  # A transport error response proves the HTTP server is listening.
                except (OSError, http.client.HTTPException):
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(1)
        client = Client(args.url, command or None)
        probe(client, args.kind, args.catalog, args.theme)
    finally:
        if client:
            client.close()

if __name__=='__main__':
    main()
