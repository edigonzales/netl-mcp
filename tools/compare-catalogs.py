#!/usr/bin/env python3
"""Compare the suite's complete MCP tool/resource/prompt contracts with both servers."""
import json
import sys
from pathlib import Path

def keyed(items, key):
    result = {item[key]:item for item in items}
    assert len(result)==len(items), f'Duplicate {key}'
    return result

single = [json.loads(Path(path).read_text()) for path in sys.argv[1:3]]
suite = json.loads(Path(sys.argv[3]).read_text())
for field, key in [('tools','name'),('resources','uri'),('prompts','name')]:
    expected = {}
    for catalog in single:
        addition = keyed(catalog[field], key)
        assert not expected.keys() & addition.keys(), f'Duplicate {field} across modules'
        expected.update(addition)
    actual = keyed(suite[field], key)
    assert expected.keys()==actual.keys(), f'{field} differ: {expected.keys() ^ actual.keys()}'
    for name, contract in expected.items():
        assert contract==actual[name], f'{field} contract changed: {name}'
for catalog in single:
    for tool, result in catalog['results'].items():
        assert result==suite['results'][tool], f'Representative result changed: {tool}'
print('Suite catalogs, schemas and representative results equal the union of both servers.')
