#!/usr/bin/env python3
"""Exercise NETL's actual Docker/JDBC workflow from a server container in the synthetic lab."""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--image',required=True)
p.add_argument('--kind',choices=['netl','suite'],default='netl')
p.add_argument('--workspace',type=Path,required=True)
p.add_argument('--project',default='themenintegration-lab')
p.add_argument('--socket',default='/var/run/docker.sock')
p.add_argument('--socket-gid',default=os.environ.get('DOCKER_SOCKET_GID'))
a=p.parse_args()
socket_gid=a.socket_gid or str(os.stat(a.socket).st_gid if sys.platform.startswith('linux') else 0)
workspace=a.workspace.resolve()
assert workspace == Path(__file__).resolve().parents[1]/'tests/compose/workspace', 'Only the bundled synthetic fixture is allowed'
spec=importlib.util.spec_from_file_location('smoke',Path(__file__).with_name('mcp-smoke.py'))
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)
theme='tests/container_'+uuid.uuid4().hex[:12]
folder=workspace/'themes'/theme
shutil.copytree(workspace/'themes/demo/standorte',folder)
manifest=json.loads((folder/'schemas.json').read_text())
for schema in manifest['schemas']:
    schema['baseName']='netl_ci_'+theme.split('_',1)[1]+'_'+schema['database']
    schema.pop('schemaVersion',None)
(folder/'schemas.json').write_text(json.dumps(manifest))
for path in [workspace/'.netl',workspace/'themes/tests',folder]:
    path.mkdir(parents=True,exist_ok=True)
    path.chmod(0o777)
for path in (workspace/'.netl').rglob('*'):
    if not path.is_symlink():path.chmod(0o777 if path.is_dir() else 0o666)
container=None;client=None
try:
    container=subprocess.check_output(['docker','run','--rm','-d','--network',a.project+'_default',
        '--group-add',socket_gid,'-p','127.0.0.1::8080',
        '-v',str(workspace)+':/workspace','-v',a.socket+':/var/run/docker.sock',
        '-e','NETL_WORKSPACE=/workspace','-e','NETL_HOST_WORKSPACE='+str(workspace),
        '-e','NETL_RUNTIME_MODE=container','-e','NETL_DOCKER_PROJECT='+a.project,a.image],text=True).strip()
    subprocess.run(['docker','exec',container,'docker','version'],check=True)
    subprocess.run(['docker','exec',container,'docker','inspect',a.project+'-gretl-1'],check=True,stdout=subprocess.DEVNULL)
    port=subprocess.check_output(['docker','port',container,'8080/tcp'],text=True).strip().rsplit(':',1)[1]
    url='http://127.0.0.1:'+port+'/mcp'
    kind=a.kind
    subprocess.run([sys.executable,str(Path(__file__).with_name('mcp-smoke.py')),'--kind',kind,'--url',url],check=True)
    client=smoke.Client(url=url)
    client.call('initialize',{'protocolVersion':smoke.PROTOCOL,'capabilities':{},'clientInfo':{'name':'netl-container-test','version':'1'}})
    client.send({'jsonrpc':'2.0','method':'notifications/initialized'})
    for schema in ['edit','pub']:
        result=client.tool('schema_create',{'theme':theme,'schema':schema})
        assert result['status']=='CREATED', result
        assert client.tool('schema_inspect',{'theme':theme,'schema':schema})['status']=='MATCHING'
    job={'theme':theme,'job':'edit-to-pub'}
    checked=client.tool('job_validate',job)
    assert checked['status']=='VALID', checked
    assert client.tool('job_confirm',{**job,'expectationsRevision':checked['expectationsRevision']})['status']=='CONFIRMED'
    tested=client.tool('job_test',job)
    assert tested['status']=='PASSED', tested
    # Populate only this test's explicitly created schema before executing the accepted transfer.
    names={entry['database']:entry['baseName'] for entry in manifest['schemas']}
    fixtures=(folder/'jobs/edit-to-pub/fixtures/standorte.sql').read_text().replace('${sourceSchema}', '"'+names['edit']+'"')
    subprocess.run(['docker','exec','-i',a.project+'-edit-db-1','psql','-U','netl','-d','edit','-v','ON_ERROR_STOP=1'],input=fixtures,text=True,check=True)
    planned=client.tool('job_plan',job)
    assert planned['status']=='READY', planned
    result=client.tool('job_run',{**job,'planToken':planned['planToken']})
    assert result['status']=='PASSED', result
    assert client.tool('job_run',{**job,'planToken':planned['planToken']})['code']=='INVALID_PLAN'
    with tempfile.TemporaryDirectory() as temporary:
        for entry in ['first.log.runtime.json','repeat.log.runtime.json']:
            subprocess.run(['docker','cp',container+':'+tested['runDirectory']+'/'+entry,temporary+'/'+entry],check=True)
        first=json.loads(Path(temporary+'/first.log.runtime.json').read_text())
        repeat=json.loads(Path(temporary+'/repeat.log.runtime.json').read_text())
        assert first['containerId']==repeat['containerId'] and first['runtime']==repeat['runtime']
    print(a.image+': container network, host/mounted workspace mapping, schemas, temporary JDBC roles, real jobs, daemon reuse and one-use plans verified.')
finally:
    if client:client.close()
    if container:
        subprocess.run(['docker','logs',container],check=False)
        subprocess.run(['docker','stop','--time','20',container],check=False,stdout=subprocess.DEVNULL)
    for entry in manifest['schemas']:
        name=entry['baseName']
        assert name.startswith('netl_ci_')
        subprocess.run(['docker','exec',a.project+'-'+entry['database']+'-db-1','psql','-U','netl','-d',entry['database'],'-v','ON_ERROR_STOP=1',
            '-c',f'DROP SCHEMA IF EXISTS "{name}" CASCADE; DROP ROLE IF EXISTS "{name}_read", "{name}_write";'],check=True,stdout=subprocess.DEVNULL)
        (workspace/'.netl/state'/f"{entry['database']}-{name}.json").unlink(missing_ok=True)
    shutil.rmtree(folder)
