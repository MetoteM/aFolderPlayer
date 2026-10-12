#!/usr/bin/env python3
"""Small public-byte recipe for an already signed APK; never reads signing keys."""
import base64,gzip,hashlib,json,struct,sys,zipfile
from pathlib import Path
base,target,output=map(Path,sys.argv[1:4]);old=base.read_bytes();new=target.read_bytes()
def payload(data,info):
 n,e=struct.unpack_from('<HH',data,info.header_offset+26);return info.header_offset+30+n+e
with zipfile.ZipFile(base) as z: originals={i.filename:i for i in z.infolist()}
ops=[];cursor=0
with zipfile.ZipFile(target) as z:
 for i in sorted(z.infolist(),key=lambda i:i.header_offset):
  start=payload(new,i);end=start+i.compress_size
  if start<cursor or end>len(new):raise ValueError('Invalid APK layout')
  if start>cursor:ops.append({'literal':base64.b64encode(new[cursor:start]).decode()})
  previous=originals.get(i.filename);offset=payload(old,previous) if previous else 0
  if previous and previous.compress_size==i.compress_size and old[offset:offset+i.compress_size]==new[start:end]:ops.append({'copy':[offset,i.compress_size]})
  else:ops.append({'literal':base64.b64encode(new[start:end]).decode()})
  cursor=end
if cursor<len(new):ops.append({'literal':base64.b64encode(new[cursor:]).decode()})
recipe={'base_sha256':hashlib.sha256(old).hexdigest(),'target_sha256':hashlib.sha256(new).hexdigest(),'operations':ops}
encoded=json.dumps(recipe,separators=(',',':')).encode();output.write_bytes(gzip.compress(encoded,compresslevel=9,mtime=0))
restored=b''.join(old[o['copy'][0]:sum(o['copy'])] if 'copy' in o else base64.b64decode(o['literal']) for o in ops)
assert restored==new
print('Verified recipe:',output.stat().st_size,'bytes; target:',len(new),'bytes')
