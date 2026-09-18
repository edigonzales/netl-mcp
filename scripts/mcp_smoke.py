#!/usr/bin/env python3
"""Exercise the actual STDIO server and compare all four tools with the CLI.
--create explicitly allows creating the configured demo schemas.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import threading

parser = argparse.ArgumentParser()
parser.add_argument('workspace', type=Path)
parser.add_argument('--create', action='store_true')
args = parser.parse_args()
workspace = args.workspace.resolve()
netl = Path(__file__).resolve().parents[1] / 'bin/netl'
command = [str(netl), '--workspace', str(workspace)]
messages = queue.Queue()
proc = subprocess.Popen(command + ['mcp'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, text=True, bufsize=1)
errors = []
def read_stdout():
    try:
        for line in proc.stdout:
            messages.put(json.loads(line))
    except Exception as exc:
        messages.put(exc)
    finally:
        messages.put(EOFError('MCP stdout closed'))
def read_stderr():
    errors.extend(proc.stderr)
threading.Thread(target=read_stdout, daemon=True).start()
threading.Thread(target=read_stderr, daemon=True).start()
sequence = 0

def send(method, params, notification=False):
    global sequence
    sequence += 1
    request = {'jsonrpc': '2.0', 'method': method, 'params': params}
    if not notification:
        request['id'] = sequence
    proc.stdin.write(json.dumps(request) + '\n')
    proc.stdin.flush()
    if notification:
        return
    while True:
        response = messages.get(timeout=180)
        if isinstance(response, Exception):
            raise response
        if response.get('id') == sequence:
            assert 'error' not in response, response
            return response['result']

def tool(operation, schema=None):
    arguments = {'theme': 'demo/standorte'}
    if schema:
        arguments['schema'] = schema
    result = send('tools/call', {'name': 'schema_' + operation, 'arguments': arguments})
    assert not result.get('isError'), result
    if result.get('structuredContent'):
        return result['structuredContent']
    return json.loads(next(c['text'] for c in result['content'] if c['type'] == 'text'))

def cli(operation, schema=None):
    call = subprocess.run(command + ['schema', operation, 'demo/standorte'] +
                          ([schema] if schema else []) + ['--json'], capture_output=True, text=True, timeout=180)
    assert call.returncode in (0, 1), call.stderr
    return json.loads(call.stdout)

try:
    send('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
                       'clientInfo': {'name': 'netl-smoke', 'version': '1'}})
    send('notifications/initialized', {}, notification=True)
    tools = send('tools/list', {})['tools']
    assert {t['name'] for t in tools} == {'schema_list', 'schema_plan', 'schema_create', 'schema_inspect'}
    assert tool('list') == cli('list')
    # OpenCode may issue independent inspections concurrently. Both response IDs
    # must be returned; a sequential-only smoke test misses transport races.
    for batch in range(10):
        pending = {900 + batch * 2, 901 + batch * 2}
        for request_id, schema in zip(sorted(pending), ('edit', 'pub')):
            proc.stdin.write(json.dumps({'jsonrpc':'2.0','id':request_id,'method':'tools/call',
                'params':{'name':'schema_inspect','arguments':{'theme':'demo/standorte','schema':schema}}}) + '\n')
        proc.stdin.flush()
        while pending:
            response = messages.get(timeout=15)
            if isinstance(response, Exception):
                raise response
            if response.get('id') in pending:
                assert 'error' not in response, response
                pending.remove(response['id'])
    for schema in ('edit', 'pub'):
        assert tool('plan', schema) == cli('plan', schema)
        assert tool('inspect', schema) == cli('inspect', schema)
        if args.create:
            created = tool('create', schema)
            assert created['status'] in ('CREATED', 'ALREADY_PRESENT'), created
            again = cli('create', schema)
            assert again['status'] == 'ALREADY_PRESENT', again
            assert created['inspection'] == again['inspection']
            assert tool('inspect', schema) == cli('inspect', schema)
    invalid = send('tools/call', {'name':'schema_plan', 'arguments':{'theme':'../escape', 'schema':'edit'}})
    payload = invalid.get('structuredContent') or json.loads(invalid['content'][0]['text'])
    assert payload['status'] == 'ERROR' and payload['code'] == 'INVALID_CONFIG', payload
    print('PASS: STDIO initialization, exact tool set, CLI parity, traversal rejection' +
          (', schema creation and repeat' if args.create else ''))
finally:
    proc.stdin.close()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)
    if errors:
        print('MCP stderr:', ''.join(errors)[:4000])
