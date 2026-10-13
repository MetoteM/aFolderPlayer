#!/usr/bin/env python3
"""Prepare a durable, content-addressed upload plan. No credentials or signing keys.

prepare VERSION: hashes every tracked release file; saves the plan in the release
folder, so the next Git checkpoint carries the recovery information.
status VERSION: compares planned Git blob SHAs against GitHub; missing blobs alone
need uploading. Every successfully staged batch must be committed to the staging
branch before continuing. A missing local session is not a reason to rebuild.
"""
import argparse, hashlib, json, subprocess, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def blob_sha(data):
    return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()

def prepare(version):
    folder=ROOT/'deployment/releases'/version
    rows=[]
    for file in sorted(folder.iterdir()):
        if not file.is_file() or file.name=='upload-plan.json': continue
        data=file.read_bytes()
        rows.append(dict(path=str(file.relative_to(ROOT)),size=len(data),git_blob=blob_sha(data),sha256=hashlib.sha256(data).hexdigest()))
    if not rows: raise ValueError('No prepared release files')
    manifest=json.loads((folder/'manifest.json').read_text())
    plan=dict(version=version,source_commit=manifest['source_commit'],staging_branch='release/'+version+'-staging',files=rows)
    temporary=folder/'upload-plan.json.tmp'
    temporary.write_text(json.dumps(plan,indent=2)+'\n');temporary.replace(folder/'upload-plan.json')
    print('Saved upload plan:',len(rows),'files')

def status(version):
    plan=json.loads((ROOT/'deployment/releases'/version/'upload-plan.json').read_text())
    for row in plan['files']:
        request=urllib.request.Request('https://api.github.com/repos/MetoteM/aFolderPlayer/git/blobs/'+row['git_blob'],method='HEAD',headers={'User-Agent':'AFP-release-checkpoint'})
        try:
            with urllib.request.urlopen(request,timeout=20) as response: present=response.status==200
        except urllib.error.HTTPError as error:
            if error.code!=404: raise
            present=False
        print(('present' if present else 'UPLOAD'),row['path'])

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['prepare','status']);parser.add_argument('version');args=parser.parse_args()
    if not args.version or any(c not in '0123456789.' for c in args.version): parser.error('Expected numeric version')
    (prepare if args.command=='prepare' else status)(args.version)
