#!/usr/bin/env python3
"""Check pinned model reuse without a network download or archive duplication."""
import contextlib, hashlib, io, json, pathlib, runpy, sys, tempfile, unittest
from unittest.mock import patch

class SharedModelTests(unittest.TestCase):
 def exercise(self, url=None, digest=None):
  with tempfile.TemporaryDirectory() as temporary:
   root=pathlib.Path(temporary);tools=root/'tools';tools.mkdir()
   script=tools/'publish-release.py';script.write_text(pathlib.Path(__file__).with_name('publish-release.py').read_text())
   release=root/'deployment/releases/test';release.mkdir(parents=True);out=root/'output';out.mkdir()
   data=b'public APK fixture';(out/'player.apk').write_bytes(data)
   model={'name':'AFP_English_Russian_v2.zip','size':123,'sha256':'a'*64,'source_url':url or 'https://github.com/MetoteM/aFolderPlayer/releases/download/v0.8.0/AFP_English_Russian_v2.zip'}
   manifest={'certificate_sha256':'b'*64,'apks':[{'name':'player.apk','size':len(data),'sha256':hashlib.sha256(data).hexdigest()}],'models':[model]}
   (release/'manifest.json').write_text(json.dumps(manifest))
   for name in ['catalog.json','SHA256SUMS','release-notes.md']:(release/name).write_text('public fixture')
   calls=[]
   def fetch(address,timeout):
    calls.append(address)
    self.assertEqual('https://api.github.com/repos/MetoteM/aFolderPlayer/releases/tags/v0.8.0',address)
    return io.BytesIO(json.dumps({'draft':False,'prerelease':False,'assets':[{'name':model['name'],'size':123,'digest':digest or 'sha256:'+'a'*64}]}).encode())
   with patch.object(sys,'argv',[str(script),str(out),'apksigner','test']),patch('subprocess.check_output',return_value='Signer #1 certificate SHA-256 digest: '+'b'*64),patch('urllib.request.urlopen',side_effect=fetch),contextlib.redirect_stdout(io.StringIO()):
    runpy.run_path(str(script),run_name='__main__')
   self.assertEqual(1,len(calls))
   self.assertFalse((out/model['name']).exists())
 def test_reuses_metadata_without_model_archive_download(self):self.exercise()
 def test_rejects_changed_model_digest(self):
  with self.assertRaisesRegex(ValueError,'Shared model archive mismatch'):self.exercise(digest='sha256:'+'c'*64)
 def test_rejects_another_repository(self):
  with self.assertRaisesRegex(ValueError,'Invalid shared model URL'):self.exercise(url='https://github.com/Other/aFolderPlayer/releases/download/v0.8.0/AFP_English_Russian_v2.zip')

if __name__=='__main__':unittest.main()
