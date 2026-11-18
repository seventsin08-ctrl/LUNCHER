#!/usr/bin/env bash
set -euxo pipefail
package=com.luncher.home.preview3
mkdir -p dock-smoke
trap 'adb logcat -d -s AndroidRuntime:E > dock-smoke/android-runtime.log 2>/dev/null || true' EXIT
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell cmd package set-home-activity "$package/com.luncher.home.MainActivity"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 5
adb shell am force-stop "$package"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > dock-smoke/activities.txt
python3 - <<'PY'
import json,re,xml.etree.ElementTree as ET
raw=open('dock-smoke/activities.txt').read()
components=[]
for match in re.finditer(r'(?m)^\s*([A-Za-z][\w.]+)/([\w.$]+)\s*$',raw):
    pkg,cls=match.groups()
    if pkg=='com.luncher.home.preview3': continue
    if cls.startswith('.'): cls=pkg+cls
    elif '.' not in cls: cls=pkg+'.'+cls
    key=pkg+'/'+cls
    if key not in components: components.append(key)
def app(package): return next(x for x in components if x.startswith(package+'/'))
camera=app('com.android.camera2')
chrome=app('com.android.chrome')
settings=app('com.android.settings')
keys={'camera':camera,'chrome':chrome,'settings':settings}
json.dump(keys,open('dock-smoke/keys.json','w'))
root=ET.Element('map')
for key in ['PHOTO','ICONS','CALENDAR','BATTERY','MUSIC']:
    ET.SubElement(root,'boolean',name='s_w_'+key,value='false')
ET.SubElement(root,'boolean',name='s_anim',value='true')
ET.SubElement(root,'string',name='dock').text=camera
ET.SubElement(root,'string',name='s_order').text='|'.join([chrome,settings,'__luncher_empty_cell__'])
hidden=ET.SubElement(root,'set',name='s_hidden_apps')
for key in components:
    if key not in keys.values(): ET.SubElement(hidden,'string').text=key
ET.ElementTree(root).write('dock-smoke/preferences.xml',encoding='utf-8',xml_declaration=True)
PY
adb push dock-smoke/preferences.xml /data/local/tmp/dock-smoke-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/dock-smoke-prefs.xml
adb shell "run-as $package sh -c 'mkdir -p shared_prefs; cat /data/local/tmp/dock-smoke-prefs.xml > shared_prefs/luncher.xml'"
adb logcat -c
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 8
dump() {
  adb shell uiautomator dump /sdcard/dock-ui.xml >/dev/null
  adb exec-out cat /sdcard/dock-ui.xml > dock-smoke/current.xml
}
read_prefs() { adb shell "run-as $package cat shared_prefs/luncher.xml" > dock-smoke/current-prefs.xml; }
check_dock() {
  python3 - "$@" <<'PY'
import json,sys,xml.etree.ElementTree as ET
keys=json.load(open('dock-smoke/keys.json'))
root=ET.parse('dock-smoke/current-prefs.xml').getroot()
value=root.find("string[@name='dock']")
actual=[] if value is None else (value.text or '').split('|')
actual=[x for x in actual if x]
expected=[keys[x] for x in sys.argv[1:]]
assert actual==expected, (actual,expected)
print('Dock order:',sys.argv[1:])
PY
}
dump
python3 scripts/dock-ui.py measure Camera one
adb exec-out screencap -p > dock-smoke/one.png
read -r sx sy <<< "$(python3 scripts/dock-ui.py home-app Chrome)"
read -r x1 y1 x2 y2 <<< "$(python3 scripts/dock-ui.py dock Camera)"
# Drop just outside the existing dock: the generous drop area should accept it.
adb shell input swipe "$sx" "$sy" "$((x2+20))" "$(((y1+y2)/2))" 45000
sleep 3
dump
read_prefs
check_dock camera chrome
python3 scripts/dock-ui.py measure Camera two
adb exec-out screencap -p > dock-smoke/two.png
read -r sx sy <<< "$(python3 scripts/dock-ui.py home-app Settings)"
read -r x1 y1 x2 y2 <<< "$(python3 scripts/dock-ui.py dock Camera)"
adb shell input swipe "$sx" "$sy" "$((x2+20))" "$(((y1+y2)/2))" 45000
sleep 3
dump
read_prefs
check_dock camera chrome settings
python3 scripts/dock-ui.py measure Camera three
adb exec-out screencap -p > dock-smoke/three.png
python3 - <<'PY'
import json
rects=[json.load(open('dock-smoke/'+x+'.json')) for x in ['one','two','three']]
widths=[r[2]-r[0] for r in rects]
centers=[(r[0]+r[2])/2 for r in rects]
assert widths[0] < widths[1] < widths[2] <= 300, widths
assert max(centers)-min(centers) <= 2, centers
assert max(r[3]-r[1] for r in rects)==min(r[3]-r[1] for r in rects),rects
print('Dock grew smoothly around its center:',widths,centers)
PY
# Remove one icon with its edit badge, then drag another back to the home screen.
read -r mx my <<< "$(python3 scripts/dock-ui.py minus Settings)"
adb shell input tap "$mx" "$my"
sleep 3
dump
read_prefs
check_dock camera chrome
python3 scripts/dock-ui.py measure Camera reduced
read -r sx sy <<< "$(python3 scripts/dock-ui.py dock-app Chrome)"
adb shell input swipe "$sx" "$sy" 250 62 10000
sleep 3
dump
read_prefs
check_dock camera
python3 scripts/dock-ui.py measure Camera single_again
! grep -q 'Moving Chrome' dock-smoke/current.xml
read -r mx my <<< "$(python3 scripts/dock-ui.py minus Camera)"
adb shell input tap "$mx" "$my"
sleep 3
dump
read_prefs
check_dock
grep -q 'Drag apps here' dock-smoke/current.xml
# An empty dock remains an obvious, usable drop target.
read -r sx sy <<< "$(python3 scripts/dock-ui.py home-app Settings)"
adb shell input swipe "$sx" "$sy" 160 565 10000
sleep 3
dump
read_prefs
check_dock settings
python3 scripts/dock-ui.py measure Settings restored
python3 - <<'PY'
import json
one,reduced,again,restored=[json.load(open('dock-smoke/'+x+'.json')) for x in ['one','reduced','single_again','restored']]
width=lambda r:r[2]-r[0]
assert width(again)==width(one)==width(restored),(one,again,restored)
assert width(reduced)>width(one),(one,reduced)
print('Dock shrank after removal and worked again when empty')
PY
adb shell am force-stop "$package"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 6
dump
read_prefs
check_dock settings
python3 scripts/dock-ui.py measure Settings restarted
adb logcat -d -s AndroidRuntime:E > dock-smoke/android-runtime.log
if grep -q 'FATAL EXCEPTION' dock-smoke/android-runtime.log; then cat dock-smoke/android-runtime.log; exit 1; fi
echo 'Dock sizing, centering, drag, removal and persistence passed'
