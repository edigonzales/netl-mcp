#!/usr/bin/env python3
"""Exercise STDIO and CLI parity. --create creates/recreates only isolated synthetic test schemas."""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import threading
import shutil
import uuid

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
suffix = uuid.uuid4().hex[:12]
test_theme = 'tests/mcp_' + suffix
test_dir = workspace / 'themes' / test_theme
test_dir.mkdir(parents=True)
shutil.copytree(workspace / 'themes/demo/standorte/modelle', test_dir / 'modelle')
shutil.copyfile(workspace / 'themes/demo/standorte/grants.sql', test_dir / 'grants.sql')
manifest = json.loads((workspace / 'themes/demo/standorte/schemas.json').read_text())
def physical_name(entry):
    return entry['baseName'] + '_v' + str(entry['schemaVersion'])

for entry in manifest['schemas']:
    entry['baseName'] = 'netl_mcp_' + suffix + '_' + entry['database']
draft = test_dir / 'draft.json'
draft.write_text(json.dumps(manifest))
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

def invoke(name, arguments):
    result = send('tools/call', {'name': name, 'arguments': arguments})
    assert not result.get('isError'), result
    return result.get('structuredContent') or json.loads(next(c['text'] for c in result['content'] if c['type'] == 'text'))

def config_cli(operation, *extra):
    result = subprocess.run(command + ['config', operation, test_theme, *extra, '--json'],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode in (0, 1), result.stderr
    return json.loads(result.stdout)

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
    assert {t['name'] for t in tools} == {'schema_list', 'schema_plan', 'schema_create', 'schema_inspect', 'schema_recreate', 'schema_drop_previous', 'config_context', 'config_validate', 'config_save',
        'job_context', 'job_validate', 'job_confirm', 'job_test', 'job_plan', 'job_run', 'job_status', 'job_write_transform', 'job_write_test'}
    validate_schema = next(t for t in tools if t['name'] == 'config_validate')['inputSchema']['properties']['manifest']
    assert validate_schema['properties']['formatVersion']['type'] == 'integer'
    assert validate_schema['properties']['schemas']['items']['properties']['overrides']['properties']['nameByTopic']['type'] == 'boolean'
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
    context = invoke('config_context', {'theme': test_theme})
    assert context == config_cli('context')
    assert context['revision'] == 'ABSENT'
    invalid_manifest = {**manifest, 'formatVersion': '1'}
    # Typed argument errors are rejected by the MCP SDK before the service is called.
    rejected = send('tools/call', {'name': 'config_validate',
                    'arguments': {'theme': test_theme, 'manifest': invalid_manifest}})
    assert rejected.get('isError') is True and 'formatVersion' in str(rejected), rejected
    assert not (test_dir / 'schemas.json').exists()
    assert invoke('config_validate', {'theme': test_theme, 'manifest': manifest}) == config_cli('validate', str(draft))
    saved = invoke('config_save', {'theme': test_theme, 'manifest': manifest, 'expectedRevision': 'ABSENT'})
    assert saved['status'] == 'SAVED', saved
    assert config_cli('save', str(draft), 'ABSENT')['code'] == 'CONFIG_CONFLICT'
    assert invoke('config_context', {'theme': test_theme}) == config_cli('context')
    revision = saved['revision']
    saved_cli = config_cli('save', str(draft), revision)
    assert saved_cli['status'] == 'SAVED' and saved_cli['revision'] == revision
    if args.create:
        for entry in manifest['schemas']:
            target = {'theme': test_theme, 'schema': entry['ident']}
            created = invoke('schema_create', target)
            assert created['status'] == 'CREATED', created
            again = subprocess.run(command + ['schema', 'create', test_theme, entry['ident'], '--json'],
                                   capture_output=True, text=True, timeout=180)
            assert json.loads(again.stdout)['status'] == 'ALREADY_PRESENT'
            planned = invoke('schema_plan', {**target, 'operation': 'recreate'})
            assert planned['status'] == 'READY', planned
            if entry['ident'] == 'edit':
                recreated = invoke('schema_recreate', {**target, 'planToken': planned['planToken']})
            else:
                call = subprocess.run(command + ['schema', 'recreate', test_theme, entry['ident'],
                                      planned['planToken'], '--json'], capture_output=True, text=True, timeout=180)
                recreated = json.loads(call.stdout)
            assert recreated['status'] == 'RECREATED', recreated
            assert recreated['inspection']['status'] == 'MATCHING'
            assert invoke('schema_recreate', {**target, 'planToken': planned['planToken']})['code'] == 'INVALID_PLAN'
        manifest['schemas'][0]['schemaVersion'] = 2
        revision = invoke('config_context', {'theme': test_theme})['revision']
        assert invoke('config_save', {'theme': test_theme, 'manifest': manifest, 'expectedRevision': revision})['status'] == 'SAVED'
        target = {'theme': test_theme, 'schema': 'edit'}
        assert invoke('schema_create', target)['status'] == 'CREATED'
        planned = invoke('schema_plan', {**target, 'operation': 'drop-previous'})
        assert planned['status'] == 'READY', planned
        deleted = invoke('schema_drop_previous', {**target, 'planToken': planned['planToken']})
        assert deleted['status'] == 'DELETED', deleted
        assert invoke('schema_inspect', target)['status'] == 'MATCHING'
    invalid = send('tools/call', {'name':'schema_plan', 'arguments':{'theme':'../escape', 'schema':'edit'}})
    payload = invalid.get('structuredContent') or json.loads(invalid['content'][0]['text'])
    assert payload['status'] == 'ERROR' and payload['code'] == 'INVALID_CONFIG', payload
    print('PASS: STDIO, typed manifest schema, configuration validation/save, CLI parity, traversal rejection' +
          (', isolated schema creation/recreation, version switch, previous-version deletion, one-use tokens' if args.create else ''))
finally:
    if args.create:
        for entry in manifest['schemas']:
            for version in range(1, entry['schemaVersion'] + 1):
                db, name = entry['database'], entry['baseName'] + '_v' + str(version)
                assert entry['baseName'] == 'netl_mcp_' + suffix + '_' + db
                subprocess.run(['docker', 'compose', 'exec', '-T', db + '-db', 'psql', '-U', 'netl', '-d', db,
                                '-v', 'ON_ERROR_STOP=1', '-c', 'DROP SCHEMA IF EXISTS "' + name + '" CASCADE; DROP ROLE IF EXISTS "' + name + '_read", "' + name + '_write"'],
                               cwd=workspace, capture_output=True, check=True, timeout=20)
                (workspace / '.netl/state' / (db + '-' + name + '.json')).unlink(missing_ok=True)
    shutil.rmtree(test_dir)
    proc.stdin.close()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)
    if errors:
        print('MCP stderr:', ''.join(errors)[:4000])
