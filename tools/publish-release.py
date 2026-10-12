#!/usr/bin/env python3
"""Restore and verify pre-signed public release assets; never handles signing secrets."""
import hashlib, json, subprocess, sys, gzip, base64, urllib.request
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
 if 'delta' in asset:
  spec=asset['delta'];base=out/spec['base_name']
  if not base.exists():
   if not spec['base_url'].startswith('https://github.com/MetoteM/aFolderPlayer/releases/download/'):raise ValueError('Invalid base URL')
   with urllib.request.urlopen(spec['base_url'],timeout=45) as response:base.write_bytes(response.read(50_000_001))
  original=base.read_bytes()
  if len(original)>50_000_000 or hashlib.sha256(original).hexdigest()!=spec['base_sha256']:raise ValueError('Base APK mismatch')
  packed=(ROOT/spec['recipe']).read_bytes()
  if hashlib.sha256(packed).hexdigest()!=spec['recipe_sha256']:raise ValueError('Recipe integrity mismatch')
  recipe=json.loads(gzip.decompress(packed))
  if recipe['base_sha256']!=spec['base_sha256'] or recipe['target_sha256']!=asset['sha256']:raise ValueError('Recipe identity mismatch')
  restored=bytearray()
  for operation in recipe['operations']:
   if 'copy' in operation:
    offset,length=operation['copy']
    if offset<0 or length<0 or offset+length>len(original):raise ValueError('Invalid copy range')
    restored.extend(original[offset:offset+length])
   else:restored.extend(base64.b64decode(operation['literal'],validate=True))
   if len(restored)>asset['size']:raise ValueError('Restored APK too large')
  target.write_bytes(restored);base.unlink()
 if target.stat().st_size!=asset['size'] or hashlib.sha256(target.read_bytes()).hexdigest()!=asset['sha256']:raise ValueError('APK integrity mismatch')
 result=subprocess.check_output([sys.argv[2], 'verify','--print-certs',str(target)],text=True)
 if 'Signer #1 certificate SHA-256 digest: '+manifest['certificate_sha256'] not in result:raise ValueError('APK certificate mismatch')
for entry in manifest.get('models',[manifest.get('model')]):
 model=out/entry['name']
 if model.stat().st_size!=entry['size'] or hashlib.sha256(model.read_bytes()).hexdigest()!=entry['sha256']:raise ValueError('Model archive mismatch')
for name in ['catalog.json','SHA256SUMS','release-notes.md']:
 (out/name).write_bytes((release/name).read_bytes())
print('Verified release assets:', ', '.join(a['name'] for a in manifest['apks']))
