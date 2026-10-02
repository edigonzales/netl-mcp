#!/usr/bin/env python3
"""Resolve a published module and its POM transitives from a fresh external Maven consumer."""
import argparse
import json
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repository', default='https://jars.interlis.guru/snapshots/')
parser.add_argument('--artifact', choices=['interlis-mcp-module','netl-mcp-module'], required=True)
parser.add_argument('--version', required=True)
args=parser.parse_args()
repository=Path(args.repository).resolve().as_uri() if '://' not in args.repository else args.repository
with tempfile.TemporaryDirectory(prefix='mcp-consumer-') as folder:
    root=Path(folder)
    package='ch.so.agi.mcp' if args.artifact.startswith('interlis') else 'ch.so.agi.netl'
    configuration='InterlisMcpModuleConfiguration' if args.artifact.startswith('interlis') else 'NetlMcpModuleConfiguration'
    source=root/'src/main/java/Consumer.java'; source.parent.mkdir(parents=True)
    source.write_text(f'import {package}.{configuration};\nclass Consumer {{ Class<?> module = {configuration}.class; }}\n')
    (root/'settings.xml').write_text('<settings/>')
    (root/'pom.xml').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>external</groupId><artifactId>consumer</artifactId><version>1</version>
<properties><maven.compiler.release>21</maven.compiler.release></properties>
<repositories><repository><id>modules</id><url>{escape(repository)}</url></repository>
<repository><id>interlis-mirror</id><url>https://jars.interlis.guru/mirror</url></repository></repositories>
<dependencyManagement><dependencies>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>4.1.1</version><type>pom</type><scope>import</scope></dependency>
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-bom</artifactId><version>2.0.1</version><type>pom</type><scope>import</scope></dependency>
<dependency><groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-bom</artifactId><version>2.0.1</version><type>pom</type><scope>import</scope></dependency>
<dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId><version>1.0.1</version></dependency>
</dependencies></dependencyManagement>
<dependencies><dependency><groupId>ch.so.agi</groupId><artifactId>{args.artifact}</artifactId><version>{args.version}</version></dependency></dependencies>
<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.8.1</version></plugin></plugins></build></project>''')
    command=[shutil.which('mvn') or str(Path.home()/'.sdkman/candidates/maven/current/bin/mvn'),
             '-B','-ntp','-s',str(root/'settings.xml'),f'-Dmaven.repo.local={root}/cache']
    subprocess.run(command+['compile','dependency:copy-dependencies',f'-DoutputDirectory={root}/dependencies'],cwd=root,check=True)
    for classifier in ['sources','javadoc']:
        subprocess.run(command+['dependency:get',f'-Dartifact=ch.so.agi:{args.artifact}:{args.version}:jar:{classifier}',
            '-Dtransitive=false'],cwd=root,check=True)
    module=next((root/'dependencies').glob(args.artifact+'-*.jar'))
    with zipfile.ZipFile(module) as archive:
        entries=archive.namelist()
        assert not any(name.startswith(('BOOT-INF/','org/springframework/')) for name in entries), 'Embedded runtime dependencies'
        assert not any(name.endswith(('/Application.class','/application.properties','/logback-spring.xml')) for name in entries)
        classes=[name for name in entries if name.endswith('.class')]
        assert classes
        for name in classes:
            assert struct.unpack('>H',archive.read(name)[6:8])[0]==65, f'Not Java 21 bytecode: {name}'
        metadata=json.loads(archive.read(f'META-INF/mcp/{args.artifact}.json'))
        assert metadata['version']==args.version
        resolved={path.name for path in (root/'dependencies').glob('*.jar')}
        drift={}
        for coordinate,version in metadata['dependencies'].items():
            artifact=coordinate.split(':',1)[1]
            if artifact+'-'+version+'.jar' not in resolved:
                drift[coordinate]={'expected':version,'resolved':[file for file in resolved if file.startswith(artifact+'-')]}
        assert not drift, f'POM version drift: {drift}'
        assert package.replace('.','/')+'/'+configuration+'.class' in entries
    assert any((root/'cache/ch/so/agi'/args.artifact/args.version).glob('*.pom'))
    print(f'{args.artifact}: plain Java-21 JAR, POM transitives, sources and Javadoc verified without local substitution.')
