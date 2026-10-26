#!/usr/bin/env python3
"""Preserve third-party copyright/license blocks in the APK's offline notices."""
import re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
blocks=[]
seen=set()
for path in sorted((root/'src/main/jni/vendor/atari800').rglob('*')):
    if path.suffix not in ('.c','.h') or not path.is_file(): continue
    raw=path.read_bytes()
    try:text=raw.decode('utf-8')
    except UnicodeDecodeError:text=raw.decode('latin-1')
    for block in re.findall(r'/\*.*?\*/',text[:12000],re.S):
        if re.search(r'copyright|permission is hereby|redistribution and use|copying and distribution',block,re.I):
            block="\n".join(line.rstrip() for line in block.splitlines())
            if block not in seen:
                seen.add(block);blocks.append(str(path.relative_to(root))+'\n'+block)
notice=(root/'THIRD_PARTY.md').read_text()+'\n\n'+(root/'COPYING').read_text()+'\n\n'+'\n\n'.join(blocks)
(root/'src/main/assets/licenses.txt').write_text(notice)
print(f'License notices: {len(blocks)} preserved blocks')
