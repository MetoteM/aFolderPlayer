#!/usr/bin/env python3
"""Edit reviewed release text only; refuse concurrent edits and preserve assets."""
import hashlib,json,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def gh(*args):return json.loads(subprocess.check_output(['gh','api',*args],text=True))
for item in json.loads((ROOT/'deployment/release-descriptions.json').read_text()):
    endpoint='repos/MetoteM/aFolderPlayer/releases/'+str(item['id'])
    before=gh(endpoint)
    assert before['tag_name']==item['tag'],'Release identity changed'
    if before.get('body','')==item['body'] and before['name']==item['name']:
        print(item['tag']+': already updated');continue
    assert hashlib.sha256((before.get('body') or '').encode()).hexdigest()==item['previous_body_sha256'],'Concurrent release text edit: '+item['tag']
    with tempfile.TemporaryDirectory() as directory:
        payload=Path(directory)/'body.json';payload.write_text(json.dumps({'name':item['name'],'body':item['body']},ensure_ascii=False))
        gh('--method','PATCH',endpoint,'--input',str(payload))
    after=gh(endpoint)
    assert after['body']==item['body'] and after['name']==item['name']
    assert {(a['id'],a['name'],a['size'],a.get('digest')) for a in before['assets']}=={(a['id'],a['name'],a['size'],a.get('digest')) for a in after['assets']}
    assert (before['draft'],before['prerelease'],before['tag_name'])==(after['draft'],after['prerelease'],after['tag_name'])
    print(item['tag']+': description updated; attachments preserved')
