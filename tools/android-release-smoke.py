#!/usr/bin/env python3
"""Exercise real signed player APK clean install, upgrade and UI on an emulator."""
import argparse,hashlib,json,pathlib,re,subprocess,sys,time,urllib.request,xml.etree.ElementTree as ET
PACKAGE='dev.alex.folderplayer'
OUT=pathlib.Path('smoke-output');OUT.mkdir(exist_ok=True)
parser=argparse.ArgumentParser()
parser.add_argument('apk')
parser.add_argument('--old-url',default='https://github.com/MetoteM/aFolderPlayer/releases/download/v0.7.9/aFolderPlayer_0.7.9_patch1.apk')
parser.add_argument('--old-sha256',default='88aa2cae10ae38e233d5266c0cb6ca9c84f2fd5134ab278a7afbaee0c5335e59')
parser.add_argument('--old-version',default='0.7.9_patch1')
parser.add_argument('--version',default='0.8.0')
parser.add_argument('--code',type=int,default=36)
args=parser.parse_args()
assert args.old_url.startswith('https://github.com/MetoteM/aFolderPlayer/releases/download/')
APK=pathlib.Path(args.apk).resolve()
OLD=OUT/'previous.apk'
NAME='AFP_'+args.version.replace('.','')+'_smoke'

def adb(*args):
 return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=45)

def ui():
 for attempt in range(12):
  try:
   adb('shell','uiautomator','dump','/sdcard/afp-smoke.xml')
   raw=adb('shell','cat','/sdcard/afp-smoke.xml')
   (OUT/'last-ui.xml').write_text(raw)
   return ET.fromstring(raw)
  except (subprocess.SubprocessError,ET.ParseError):time.sleep(1)
 raise RuntimeError('Cannot read Android UI')

def tap(value):
 for attempt in range(8):
  for n in ui().iter('node'):
   if value in (n.get('text'),n.get('content-desc')):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
  time.sleep(1)
 raise AssertionError('UI action missing: '+value)

def launch():
 adb('shell','svc','power','stayon','true')
 adb('shell','input','keyevent','224')
 adb('shell','wm','dismiss-keyguard')
 adb('shell','settings','put','system','screen_off_timeout','1800000')
 adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity')
 assert adb('shell','pidof',PACKAGE).strip()
 tap('Плейлисты')

def create():
 tap('＋ Создать плейлист')
 edit=next(n for n in ui().iter('node') if n.get('class')=='android.widget.EditText')
 bounds=list(map(int,re.findall(r'\d+',edit.get('bounds'))))
 adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
 adb('shell','input','text',NAME);tap('Создать')

def assert_saved():
 assert any(n.get('text')==NAME for n in ui().iter('node')),'Playlist lost after upgrade/recreation'
 tap(NAME)
 adb('shell','settings','put','system','accelerometer_rotation','0')
 adb('shell','settings','put','system','user_rotation','1')
 time.sleep(1);ui()
 adb('shell','settings','put','system','user_rotation','0')
 time.sleep(1);tap('Плейлисты')
 assert any(n.get('text')==NAME for n in ui().iter('node')),'Playlist lost after rotation'
 tap(NAME)

try:
 adb('logcat','-c')
 data=urllib.request.urlopen(args.old_url,timeout=45).read()
 assert hashlib.sha256(data).hexdigest()==args.old_sha256
 OLD.write_bytes(data)
 assert 'Success' in adb('install',str(OLD))
 launch();create();print('Previous signed app and test playlist prepared',flush=True);adb('shell','am','force-stop',PACKAGE)
 assert 'Success' in adb('install','-r',str(APK))
 launch();assert_saved();print('Signed upgrade preserves playlist across rotation',flush=True)
 assert ('versionCode='+str(args.code)) in adb('shell','dumpsys','package',PACKAGE)
 adb('shell','am','force-stop',PACKAGE)
 assert 'Success' in adb('uninstall',PACKAGE)
 assert 'Success' in adb('install',str(APK))
 launch();assert not any(n.get('text')==NAME for n in ui().iter('node')),'Clean install retained removed data'
 create();tap('Плейлисты');assert_saved();print('Clean signed installation and rotation passed',flush=True)
 log=adb('logcat','-d','-v','brief','-s','AndroidRuntime');(OUT/'AndroidRuntime.log').write_text(log)
 assert not ('FATAL EXCEPTION' in log and PACKAGE in log),'Application crash in smoke run'
 (OUT/'verification.json').write_text(json.dumps({'result':'passed','clean_install':True,'upgrade_from':args.old_version,'to':args.version,'version_code':args.code,'playlist_preserved':True,'rotation':True,'scope':'emulator UI/installation; ARM64 audio, models and battery require separate device measurement'},indent=2)+'\n')
 print('Signed APK upgrade, clean install and UI smoke checks passed')
finally:
 try:
  (OUT/'AndroidRuntime.log').write_text(adb('logcat','-d','-v','brief','-s','AndroidRuntime'))
  (OUT/'activity.txt').write_text(adb('shell','dumpsys','activity','activities'))
  (OUT/'screen.png').write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=30))
 except Exception:pass
 OLD.unlink(missing_ok=True)
