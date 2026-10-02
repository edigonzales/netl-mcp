#!/usr/bin/env python3
import json
import sys
from pathlib import Path
manifest=json.loads(Path(sys.argv[1]).read_text())
platforms={(entry['platform']['os'],entry['platform']['architecture']) for entry in manifest['manifests']}
assert {('linux','amd64'),('linux','arm64')} <= platforms, platforms
print('Verified linux/amd64 and linux/arm64 manifest.')
