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
cat > smoke-output/preferences.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <boolean name="s_w_ICONS" value="false" />
  <boolean name="s_w_CALENDAR" value="false" />
  <boolean name="s_w_BATTERY" value="false" />
  <boolean name="s_w_MUSIC" value="false" />
  <boolean name="s_anim" value="false" />
</map>
XML
adb push smoke-output/preferences.xml /data/local/tmp/resize-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/resize-prefs.xml
adb shell "run-as $package sh -c 'mkdir -p shared_prefs; cat /data/local/tmp/resize-prefs.xml > shared_prefs/luncher.xml'"
adb logcat -c
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 7
dump() {
  adb shell uiautomator dump /sdcard/launcher-ui.xml >/dev/null
  adb exec-out cat /sdcard/launcher-ui.xml > smoke-output/current.xml
}
photo_point() {
  python3 - <<'PY'
import re,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current.xml').getroot()
for parent in root.iter():
    if any(child.get('text')=='Photo' for child in parent):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',parent.get('bounds','')))
        print((x1+x2)//2,(y1+y2)//2,(x2-x1)//2)
        break
else: raise SystemExit('Photo widget not found')
PY
}
handle_point() {
  python3 - <<'PY'
import re,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current.xml').getroot()
matches=[n for n in root.iter() if 'Resize widget.' in n.get('content-desc','')]
assert matches, 'Resize handle not visible'
n=min(matches,key=lambda n:tuple(int(x) for x in re.findall(r'\d+',n.get('bounds',''))[:2])[::-1])
x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds','')))
print((x1+x2)//2,(y1+y2)//2)
PY
}
read_prefs() { adb shell "run-as $package cat shared_prefs/luncher.xml" > smoke-output/current-prefs.xml; }
assert_size() {
  python3 - "$1" "$2" <<'PY'
import json,sys,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current-prefs.xml').getroot()
prefs={x.get('name'):x.text or '' for x in root}
pos=json.loads(prefs.get('widget_positions','{}'))['0:PHOTO:p1']
assert (pos['w'],pos['h'])==(int(sys.argv[1]),int(sys.argv[2])),pos
print('Saved photo size:',pos['w'],pos['h'])
PY
}
enter_edit() {
  dump
  read -r px py cell <<< "$(photo_point)"
  adb shell input swipe "$px" "$py" "$px" "$py" 1200
  sleep 3
  dump
  handle_point >/dev/null
}
enter_edit
read -r hx hy <<< "$(handle_point)"
adb shell input swipe "$hx" "$hy" "$((hx+cell))" "$hy" 1500
sleep 3
adb logcat -d -s ResizeTrace:I > smoke-output/resize-trace.txt
cat smoke-output/resize-trace.txt
dump
adb exec-out screencap -p > smoke-output/after-drag.png
read_prefs
assert_size 3 2
adb exec-out screencap -p > smoke-output/expanded.png
adb shell am force-stop "$package"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 7
read_prefs
assert_size 3 2
enter_edit
read -r hx hy <<< "$(handle_point)"
adb shell input tap "$hx" "$hy"
sleep 2
dump
read -r sx sy <<< "$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current.xml').getroot()
for n in root.iter():
    if n.get('text')=='1 × 1':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds','')))
        print((x1+x2)//2,(y1+y2)//2); break
else: raise SystemExit('Small size option not found')
PY
)"
adb shell input tap "$sx" "$sy"
sleep 3
read_prefs
assert_size 1 1
dump
adb exec-out screencap -p > smoke-output/shrunk.png
read -r mx my <<< "$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current.xml').getroot()
matches=[n for n in root.iter() if n.get('text')=='−']
assert matches, 'Remove controls not visible'
n=min(matches,key=lambda n:tuple(int(x) for x in re.findall(r'\d+',n.get('bounds',''))[:2])[::-1])
x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds','')))
print((x1+x2)//2,(y1+y2)//2)
PY
)"
adb shell input tap "$mx" "$my"
sleep 3
read_prefs
python3 - <<'PY'
import xml.etree.ElementTree as ET
root=ET.parse('smoke-output/current-prefs.xml').getroot()
hidden=root.find("set[@name='s_hidden_widgets']")
assert hidden is not None and any(x.text=='0:PHOTO:p1' for x in hidden),ET.tostring(root)
print('Resized photo widget was removed')
PY
adb logcat -d -s AndroidRuntime:E > smoke-output/android-runtime.log
if grep -q 'FATAL EXCEPTION' smoke-output/android-runtime.log; then cat smoke-output/android-runtime.log; exit 1; fi
echo 'Widget drag, persistence, size menu and removal checks passed'
