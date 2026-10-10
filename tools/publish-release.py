#!/usr/bin/env python3
"""Restore and verify pre-signed public release assets; never handles signing secrets."""
import hashlib, json, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
manifest=json.loads((ROOT/'deployment/releases/0.7.0/manifest.json').read_text())
out=Path(sys.argv[1]);out.mkdir(parents=True, exist_ok=True)
for asset in manifest['apks']:
 target=out/asset['name']
 with target.open('wb') as f:
  for part in asset['parts']:f.write((ROOT/part).read_bytes())
 if target.stat().st_size!=asset['size'] or hashlib.sha256(target.read_bytes()).hexdigest()!=asset['sha256']:raise ValueError('APK integrity mismatch')
 result=subprocess.check_output([sys.argv[2], 'verify','--print-certs',str(target)],text=True)
 if 'Signer #1 certificate SHA-256 digest: '+manifest['certificate_sha256'] not in result:raise ValueError('APK certificate mismatch')
model=out/manifest['model']['name']
if model.stat().st_size!=manifest['model']['size'] or hashlib.sha256(model.read_bytes()).hexdigest()!=manifest['model']['sha256']:raise ValueError('Model archive mismatch')
for name in ['catalog.json','SHA256SUMS','release-notes.md']:
 (out/name).write_bytes((ROOT/'deployment/releases/0.7.0'/name).read_bytes())
print('Verified release assets:', ', '.join(a['name'] for a in manifest['apks']))
