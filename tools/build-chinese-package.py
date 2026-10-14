#!/usr/bin/env python3
"""Build a deterministic, audited Chinese → English → Russian ZIP."""
import hashlib,json,gzip,re,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def digest(data):return hashlib.sha256(data).hexdigest()
source=Path(sys.argv[1]);english=Path(sys.argv[2]);output=Path(sys.argv[3])
data={'zh-en/tokenizer.json':gzip.decompress((ROOT/'deployment/chinese-v1/tokenizer.json.gz').read_bytes())}
for name in ['encoder.onnx','decoder.onnx']:data['zh-en/'+name]=(source/name).read_bytes()
with zipfile.ZipFile(english) as z:
 for name in ['encoder.onnx','decoder.onnx','tokenizer.json']:data['en-ru/'+name]=z.read(name)
 data['LICENSE.txt']=b'Chinese to English: CC BY 4.0. Full license: https://creativecommons.org/licenses/by/4.0/legalcode.en\nSee NOTICE.txt for authors, source and modifications.\n\nEnglish to Russian: Apache License 2.0 follows.\n\n'+z.read('LICENSE.txt')
registry=(ROOT/'translator/src/main/java/dev/alex/folderplayer/ModelPackages.kt').read_text()
expected=dict(re.findall(r'"(zh-en/[^\"]+)"\s+to\s+"([0-9a-f]{64})"',registry))
weights=dict(re.findall(r'"(encoder.onnx|decoder.onnx)"\s+to\s+"([0-9a-f]{64})"',registry));expected.update({'en-ru/'+k:v for k,v in weights.items()})
expected['en-ru/tokenizer.json']='ab59614552269971b70f0bd5e966fe5adacf472cf9e08baf6841d41def76da35'
if len(expected)!=6:raise ValueError('Incomplete audited registry')
for name,h in expected.items():
 if digest(data[name])!=h:raise ValueError('Wrong model bytes: '+name)
data['NOTICE.txt']=(ROOT/'deployment/chinese-v1/NOTICE.txt').read_bytes()
data['manifest.json']=json.dumps({'model':'opus-zh-ru-v1','source':'zh','target':'ru','intermediate':'en','revision':'39d480d52a9ea3065a1f117adfe4dbc55de10e6f','files':expected},sort_keys=True,separators=(',',':')).encode()
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_STORED) as z:
 for name,value in sorted(data.items()):
  info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.create_system=3;info.external_attr=0o100644<<16;z.writestr(info,value)
print(digest(output.read_bytes()))
