#!/usr/bin/env bash
set -euxo pipefail
package=com.luncher.home.preview3
mkdir -p smoke-output
trap 'adb logcat -d -s AndroidRuntime:E > smoke-output/android-runtime.log 2>/dev/null || true' EXIT
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 5
adb shell am force-stop "$package"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > smoke-output/activities.txt
python3 - <<'PY'
import json,re,xml.etree.ElementTree as ET
raw=open('smoke-output/activities.txt').read()
components=[]
for match in re.finditer(r'(?m)^\s*([A-Za-z][\w.]+)/([\w.$]+)\s*$',raw):
    pkg,cls=match.groups()
    if pkg=='com.luncher.home.preview3': continue
    if cls.startswith('.'): cls=pkg+cls
    elif '.' not in cls: cls=pkg+'.'+cls
    key=pkg+'/'+cls
    if key not in components: components.append(key)
assert len(components)>=4, raw
members=components[:3]
open('smoke-output/members.json','w').write(json.dumps(members))
root=ET.Element('map')
for key in ['PHOTO','ICONS','CALENDAR','BATTERY','MUSIC']:
    ET.SubElement(root,'boolean',name='s_w_'+key,value='false')
ET.SubElement(root,'string',name='dock').text=''
ET.SubElement(root,'string',name='home_folders').text=json.dumps({'smoke':{'name':'Test Folder','apps':members}})
folder='com.luncher.home.preview3/com.luncher.home.preview3.folder.smoke'
ET.SubElement(root,'string',name='s_order').text='|'.join([folder,'__luncher_empty_cell__',components[3]])
ET.ElementTree(root).write('smoke-output/preferences.xml',encoding='utf-8',xml_declaration=True)
PY
adb push smoke-output/preferences.xml /data/local/tmp/launcher-folder-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/launcher-folder-prefs.xml
adb shell "run-as $package sh -c 'mkdir -p shared_prefs; cat /data/local/tmp/launcher-folder-prefs.xml > shared_prefs/luncher.xml'"
adb logcat -c
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 8
dump() {
  adb shell uiautomator dump /sdcard/launcher-ui.xml >/dev/null
  adb exec-out cat /sdcard/launcher-ui.xml > smoke-output/current.xml
}
folder_point() {
  python3 - <<'PY'
import re,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current.xml').getroot()
def walk(node, ancestors):
    if 'Test Folder' in (node.get('text','')+' '+node.get('content-desc','')):
        for a in reversed(ancestors+[node]):
            if a.get('clickable')=='true':
                x1,y1,x2,y2=map(int,re.findall(r'\d+',a.get('bounds','')))
                print((x1+x2)//2,(y1+y2)//2,x2-x1); return True
    return any(walk(c,ancestors+[node]) for c in node)
assert walk(root,[]), 'Folder not visible'
PY
}
member_point() {
  python3 - <<'PY'
import re,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current.xml').getroot()
for node in root.iter():
    if node.get('long-clickable')=='true' and node.get('content-desc'):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
        print((x1+x2)//2,(y1+y2)//2); break
else: raise SystemExit('No draggable folder member')
PY
}
read_prefs() { adb shell "run-as $package cat shared_prefs/luncher.xml" > smoke-output/current-prefs.xml; }
assert_members() {
  python3 - "$1" <<'PY'
import json,sys,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current-prefs.xml').getroot()
prefs={x.get('name'):x.text or '' for x in root}
groups=json.loads(prefs.get('home_folders','{}'))
initial=json.load(open('smoke-output/members.json'))
actual=groups.get('smoke',{}).get('apps',[])
if sys.argv[1]=='removed':
    assert actual==initial[1:], (actual,initial)
    order=prefs.get('s_order','').split('|')
    assert len(order)>1 and order[1]==initial[0], (order,initial)
else:
    assert set(actual)==set(initial) and len(actual)==3, (actual,initial)
print('Folder state:',sys.argv[1],actual)
PY
}
dump
read -r fx fy cell_width <<< "$(folder_point)"
dx=$((fx + cell_width))
adb shell input tap "$fx" "$fy"
sleep 2
dump
read -r sx sy <<< "$(member_point)"
adb exec-out screencap -p > smoke-output/folder-before.png
# A slow swipe holds within touch slop long enough to trigger long-press.
adb shell input swipe "$sx" "$sy" "$dx" "$fy" 20000
sleep 3
dump
adb exec-out screencap -p > smoke-output/dragged-out.png
if grep -q 'content-desc="Moving ' smoke-output/current.xml; then echo 'Floating drag image remained'; exit 1; fi
read_prefs
assert_members removed
# The existing home-screen drag should add the same app back into the folder.
adb shell input swipe "$dx" "$fy" "$fx" "$fy" 12000
sleep 3
dump
adb exec-out screencap -p > smoke-output/dragged-back.png
if grep -q 'content-desc="Moving ' smoke-output/current.xml; then echo 'Floating drag image remained'; exit 1; fi
read_prefs
assert_members restored
adb shell input swipe 270 350 50 350 350
sleep 2
dump
if grep -q 'content-desc="Moving ' smoke-output/current.xml; then echo 'Floating drag image remained after page switch'; exit 1; fi
adb logcat -d -s AndroidRuntime:E > smoke-output/android-runtime.log
if grep -q 'FATAL EXCEPTION' smoke-output/android-runtime.log; then cat smoke-output/android-runtime.log; exit 1; fi
echo 'Folder drag-out and drag-in checks passed'
