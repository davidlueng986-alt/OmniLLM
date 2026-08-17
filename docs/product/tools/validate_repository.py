#!/usr/bin/env python3
from __future__ import annotations
import hashlib, re, sys
from pathlib import Path
import yaml

root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]).resolve()
issues = []

# YAML parse and duplicate-key rejection.
class UniqueLoader(yaml.SafeLoader):
    pass

def construct_mapping(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError(f'duplicate YAML key: {key}')
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping
UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping)

for p in root.rglob('*.yaml'):
    try:
        yaml.load(p.read_text(encoding='utf-8'), Loader=UniqueLoader)
    except Exception as e:
        issues.append(f'YAML {p.relative_to(root)}: {e}')

# Governed source paths declared by the compendium must exist.
comp = root / 'OmniLLM_建置前產品與架構設計總綱_產品版_繁體中文.md'
if not comp.exists():
    issues.append('missing compiled design compendium')
else:
    for rel in re.findall(r'^> \*\*來源：\*\* `([^`]+)`', comp.read_text(encoding='utf-8'), flags=re.M):
        if not (root / rel).is_file():
            issues.append(f'missing source document: {rel}')

# Basic local Markdown link path closure.
link_re = re.compile(r'(?<!!)\[[^\]]*\]\(([^)]+)\)')
for p in root.rglob('*.md'):
    text = p.read_text(encoding='utf-8')
    for raw in link_re.findall(text):
        target = raw.strip().split()[0].strip('<>')
        if not target or target.startswith(('http://', 'https://', 'mailto:', '#')):
            continue
        path_part = target.split('#', 1)[0]
        if not path_part:
            continue
        resolved = (p.parent / path_part).resolve()
        try:
            resolved.relative_to(root)
        except ValueError:
            issues.append(f'link escapes package: {p.relative_to(root)} -> {target}')
            continue
        if not resolved.exists():
            issues.append(f'broken link: {p.relative_to(root)} -> {target}')

# Manifest.
manifest = root / 'MANIFEST.sha256'
if not manifest.exists():
    issues.append('missing MANIFEST.sha256')
else:
    for line in manifest.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        digest, rel = line.split('  ', 1)
        path = root / rel
        if not path.is_file():
            issues.append(f'manifest missing file: {rel}')
            continue
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            issues.append(f'manifest mismatch: {rel}')

print(f'root={root}')
print(f'files={sum(1 for p in root.rglob("*") if p.is_file())}')
print(f'issues={len(issues)}')
for issue in issues:
    print(f'- {issue}')
sys.exit(1 if issues else 0)
