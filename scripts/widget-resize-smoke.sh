#!/usr/bin/env bash
set -euxo pipefail
package=com.luncher.home.preview3
mkdir -p smoke-output
trap 'adb logcat -d -s AndroidRuntime:E > smoke-output/android-runtime.log 2>/dev/null || true' EXIT
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true
adb install -r app/build/outputs/apk/preview/app-preview.apk
# Two disposable apps let the emulator exercise real uninstall confirmation without touching user apps.
build_tools="$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
for name in one two; do
  case "$name" in one) label='Fixture One';; two) label='Fixture Two';; esac
  cat > "smoke-output/fixture-$name.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.launcherfixture.$name">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
  <application android:label="$label" android:icon="@android:drawable/sym_def_app_icon" android:hasCode="false">
    <activity android:name="android.app.Activity" android:exported="true">
      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
    </activity>
  </application>
</manifest>
XML
  "$build_tools/aapt" package -f -M "smoke-output/fixture-$name.xml" -I "$ANDROID_HOME/platforms/android-35/android.jar" -F "smoke-output/fixture-$name-unsigned.apk"
  "$build_tools/zipalign" -f 4 "smoke-output/fixture-$name-unsigned.apk" "smoke-output/fixture-$name-aligned.apk"
  "$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --out "smoke-output/fixture-$name.apk" "smoke-output/fixture-$name-aligned.apk"
  adb install -r "smoke-output/fixture-$name.apk"
done
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
tap_text() {
  local point
  point="$(python3 - "$1" <<'PY'
import re,sys,xml.etree.ElementTree as ET
needle=sys.argv[1].casefold()
root=ET.parse('smoke-output/current.xml').getroot()
def walk(node, ancestors):
    if needle in (node.get('text','')+' '+node.get('content-desc','')).casefold():
        for a in reversed(ancestors+[node]):
            if a.get('clickable')=='true':
                nums=list(map(int,re.findall(r'\d+',a.get('bounds',''))))
                if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:
                    print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2); return True
    return any(walk(child,ancestors+[node]) for child in node)
assert walk(root,[]), 'Clickable item not found: '+needle
PY
)"
  adb shell input tap $point
  sleep 2
  dump
}
home_menu() {
  adb shell input swipe 8 245 8 245 1200
  sleep 2
  dump
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
home_menu
tap_text 'Edit Home Screen'
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

# Settings exposes an icon list with multiple selections and Android-controlled uninstall.
dump
tap_text 'Done'
home_menu
tap_text 'Apple Launcher Settings'
tap_text 'Manage & uninstall apps'
grep -qi 'Manage apps' smoke-output/current.xml
tap_text 'Search apps'
adb shell input text Fixture
adb shell input keyevent KEYCODE_BACK
sleep 2
dump
tap_text 'App info for Fixture One'
adb shell input keyevent KEYCODE_BACK
sleep 2
dump
tap_text 'Fixture One'
tap_text 'Fixture Two'
grep -q '2 selected' smoke-output/current.xml
tap_text 'Uninstall selected apps'
grep -qi 'Fixture One' smoke-output/current.xml
grep -qi 'Fixture Two' smoke-output/current.xml
tap_text 'Continue'
adb exec-out screencap -p > smoke-output/uninstall-confirmation.png
tap_text 'Cancel'
grep -q '2 selected' smoke-output/current.xml
adb shell pm path com.example.launcherfixture.one
adb shell pm path com.example.launcherfixture.two
# Confirm both prompts; each package must disappear from the emulator.
tap_text 'Uninstall selected apps'
tap_text 'Continue'
tap_text 'OK'
tap_text 'OK'
grep -q '0 selected' smoke-output/current.xml
if adb shell pm path com.example.launcherfixture.one | grep -q '^package:'; then exit 1; fi
if adb shell pm path com.example.launcherfixture.two | grep -q '^package:'; then exit 1; fi
adb logcat -d -s AndroidRuntime:E > smoke-output/android-runtime.log
if grep -q 'FATAL EXCEPTION' smoke-output/android-runtime.log; then cat smoke-output/android-runtime.log; exit 1; fi
echo 'Widget resize, removal, multi-select and uninstall checks passed'
