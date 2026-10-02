#!/usr/bin/env python3
"""Test the same container image over both transports and verify the canonical JAR."""
import argparse
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--kind',choices=['interlis','netl','suite'],required=True)
p.add_argument('--image',required=True)
p.add_argument('--workspace')
p.add_argument('--catalog')
p.add_argument('--jar')
a=p.parse_args()
mount=[]
if a.workspace:
    mount=['-v',str(Path(a.workspace).resolve())+':/workspace:ro','-e','NETL_WORKSPACE=/workspace']
with tempfile.TemporaryDirectory(prefix='mcp-image-') as temp:
    container=subprocess.check_output(['docker','run','-d','--rm','-p','127.0.0.1::8080',*mount,a.image],text=True).strip()
    try:
        if a.jar:
            subprocess.run(['docker','cp',container+':/application/application.jar',temp+'/app.jar'],check=True)
            assert hashlib.sha256(Path(temp+'/app.jar').read_bytes()).digest()==hashlib.sha256(Path(a.jar).read_bytes()).digest(), 'Image JAR differs from the tested artifact'
        port=subprocess.check_output(['docker','port',container,'8080/tcp'],text=True).strip().rsplit(':',1)[1]
        command=[sys.executable,str(Path(__file__).with_name('mcp-smoke.py')),'--kind',a.kind,'--url','http://127.0.0.1:'+port+'/mcp']
        if a.catalog:command+=['--catalog',a.catalog]
        subprocess.run(command,check=True)
    except BaseException:
        subprocess.run(['docker','logs',container],check=False)
        raise
    finally:
        subprocess.run(['docker','stop','--time','20',container],check=True,stdout=subprocess.DEVNULL)
    subprocess.run([sys.executable,str(Path(__file__).with_name('mcp-smoke.py')),'--kind',a.kind,'--',
        'docker','run','--rm','-i','-e','SPRING_PROFILES_ACTIVE=stdio',*mount,a.image],check=True)
    if a.kind=='interlis':
        subprocess.run([sys.executable,str(Path(__file__).with_name('test-mcp-stdio.py')),
            'docker','run','--rm','-i','-e','SPRING_PROFILES_ACTIVE=stdio',a.image],check=True)
print(a.image+': canonical JAR, HTTP and STDIO verified')
