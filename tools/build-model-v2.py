#!/usr/bin/env python3
"""Rebuild the pinned v2 package using audited weights and the corrected tokenizer."""
import gzip, hashlib, json, sys, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
HASHES = {
 'encoder.onnx':'97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53',
 'decoder.onnx':'7e4e602c31130176b086cd4bf31d6d08d44a6a367617e910870e0ea3e5408574',
 'tokenizer.json':'ab59614552269971b70f0bd5e966fe5adacf472cf9e08baf6841d41def76da35',
 'LICENSE.txt':'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'}
with zipfile.ZipFile(sys.argv[1]) as old:
 data = {name: old.read(name) for name in HASHES if name != 'tokenizer.json'}
data['tokenizer.json'] = gzip.decompress((ROOT/'deployment/model-v2/tokenizer.json.gz').read_bytes())
for name, expected in HASHES.items():
 if hashlib.sha256(data[name]).hexdigest() != expected: raise ValueError('Wrong pinned model file: '+name)
data['NOTICE.txt']=(ROOT/'deployment/model-v2/NOTICE.txt').read_bytes()
data['manifest.json']=json.dumps({'model':'opus-en-ru-v2','files':HASHES},sort_keys=True,separators=(',',':')).encode()
with zipfile.ZipFile(sys.argv[2], 'w', compression=zipfile.ZIP_STORED) as output:
 for name in ['encoder.onnx','decoder.onnx','tokenizer.json','LICENSE.txt','NOTICE.txt','manifest.json']:
  info=zipfile.ZipInfo(name, (1980,1,1,0,0,0));info.create_system=3;info.external_attr=0o100644 << 16
  output.writestr(info, data[name])
print(hashlib.sha256(Path(sys.argv[2]).read_bytes()).hexdigest())
