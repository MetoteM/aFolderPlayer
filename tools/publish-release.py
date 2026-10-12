#!/usr/bin/env python3
"""Restore and verify pre-signed public release assets; never handles signing secrets."""
import hashlib, json, subprocess, sys, gzip
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
version=sys.argv[3] if len(sys.argv)>3 else '0.7.0'
release=ROOT/'deployment/releases'/version
manifest=json.loads((release/'manifest.json').read_text())
out=Path(sys.argv[1]);out.mkdir(parents=True, exist_ok=True)
for asset in manifest['apks']:
 target=out/asset['name']
 if 'parts' in asset:
  data=b''.join((ROOT/part).read_bytes() for part in asset['parts'])
  if asset.get('compression')=='gzip':
   if hashlib.sha256(data).hexdigest()!=asset['compressed_sha256']:raise ValueError('Compressed APK integrity mismatch')
   data=gzip.decompress(data)
  target.write_bytes(data)
 if target.stat().st_size!=asset['size'] or hashlib.sha256(target.read_bytes()).hexdigest()!=asset['sha256']:raise ValueError('APK integrity mismatch')
 result=subprocess.check_output([sys.argv[2], 'verify','--print-certs',str(target)],text=True)
 if 'Signer #1 certificate SHA-256 digest: '+manifest['certificate_sha256'] not in result:raise ValueError('APK certificate mismatch')
for entry in manifest.get('models',[manifest.get('model')]):
 model=out/entry['name']
 if model.stat().st_size!=entry['size'] or hashlib.sha256(model.read_bytes()).hexdigest()!=entry['sha256']:raise ValueError('Model archive mismatch')
for name in ['catalog.json','SHA256SUMS','release-notes.md']:
 (out/name).write_bytes((release/name).read_bytes())
print('Verified release assets:', ', '.join(a['name'] for a in manifest['apks']))
