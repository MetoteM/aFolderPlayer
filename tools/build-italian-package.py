#!/usr/bin/env python3
"""Build a deterministic, audited Italian → English → Russian ZIP."""
import hashlib,json,gzip,re,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def digest(data):return hashlib.sha256(data).hexdigest()
source=Path(sys.argv[1]);english=Path(sys.argv[2]);output=Path(sys.argv[3])
data={'it-en/tokenizer.json':gzip.decompress((ROOT/'deployment/italian-v1/tokenizer.json.gz').read_bytes())}
for name in ['encoder.onnx','decoder.onnx']:data['it-en/'+name]=(source/name).read_bytes()
with zipfile.ZipFile(english) as z:
 for name in ['encoder.onnx','decoder.onnx','tokenizer.json']:data['en-ru/'+name]=z.read(name)
 data['LICENSE.txt']=b'Italian to English: Apache License 2.0.\nSee NOTICE.txt for authors, source and modifications.\n\nEnglish to Russian: Apache License 2.0 follows.\n\n'+z.read('LICENSE.txt')
registry=(ROOT/'translator/src/main/java/dev/alex/folderplayer/ModelPackages.kt').read_text()
expected=dict(re.findall(r'"(it-en/[^\"]+)"\s+to\s+"([0-9a-f]{64})"',registry))
weights=dict(re.findall(r'"(encoder.onnx|decoder.onnx)"\s+to\s+"([0-9a-f]{64})"',registry));expected.update({'en-ru/'+k:v for k,v in weights.items()})
expected['en-ru/tokenizer.json']='ab59614552269971b70f0bd5e966fe5adacf472cf9e08baf6841d41def76da35'
if len(expected)!=6:raise ValueError('Incomplete audited registry')
for name,h in expected.items():
 if digest(data[name])!=h:raise ValueError('Wrong model bytes: '+name)
data['NOTICE.txt']=(ROOT/'deployment/italian-v1/NOTICE.txt').read_bytes()
data['manifest.json']=json.dumps({'model':'opus-it-ru-v1','source':'it','target':'ru','intermediate':'en','revision':'fd0b89b9c052adc1f2f64152f555aa17353728be','files':expected},sort_keys=True,separators=(',',':')).encode()
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_STORED) as z:
 for name,value in sorted(data.items()):
  info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.create_system=3;info.external_attr=0o100644<<16;info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,value,compresslevel=9)
entry=json.loads((ROOT/'deployment/italian-v1/catalog-entry.json').read_text())
if entry['size']!=output.stat().st_size or entry['sha256']!=digest(output.read_bytes()):raise ValueError('Italian catalogue differs from reproducible package')
print(digest(output.read_bytes()))
