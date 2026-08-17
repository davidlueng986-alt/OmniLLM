#!/usr/bin/env python3
from __future__ import annotations
import re, sys, unicodedata
from pathlib import Path
root = Path(sys.argv[1] if len(sys.argv)>1 else Path(__file__).resolve().parents[1]).resolve()
issues=[]
def slug(s):
    s=re.sub(r'[`*_~]', '', s.strip().lower())
    s=re.sub(r'[^\w\-\u4e00-\u9fff ]+', '', s)
    return re.sub(r'[\s\-]+','-',s).strip('-')
for p in root.rglob('*.md'):
    txt=p.read_text(encoding='utf-8')
    heads={slug(m.group(2)) for m in re.finditer(r'(?m)^(#{1,6})\s+(.+?)\s*$',txt)}
    for target in re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+#[^)]+)\)',txt):
        raw=target.strip().split()[0].strip('<>')
        path_part, frag=raw.split('#',1)
        if raw.startswith(('http://','https://')): continue
        q=(p.parent/path_part).resolve() if path_part else p
        if not q.exists(): continue
        qheads={slug(m.group(2)) for m in re.finditer(r'(?m)^(#{1,6})\s+(.+?)\s*$',q.read_text(encoding='utf-8'))}
        if frag and frag not in qheads:
            issues.append(f'{p.relative_to(root)} -> {raw}')
print(f'anchorIssues={len(issues)}')
for i in issues: print('- '+i)
sys.exit(1 if issues else 0)
