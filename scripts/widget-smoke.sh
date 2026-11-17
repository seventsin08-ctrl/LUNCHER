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
python3 - <<'PY'
import json, xml.etree.ElementTree as ET
root=ET.Element('map')
for key in ['PHOTO','ICONS','CALENDAR','BATTERY','MUSIC']:
    ET.SubElement(root,'boolean',name='s_w_'+key,value='false')
widgets={
 'extra_world':{'type':'WORLD_CLOCK','page':0,'col':0,'row':0,'w':4,'h':2},
 'extra_time':{'type':'DIGITAL_CLOCK','page':0,'col':0,'row':2,'w':2,'h':2},
 'extra_quote':{'type':'QUOTE','page':0,'col':2,'row':2,'w':2,'h':2}
}
ET.SubElement(root,'string',name='additional_widgets').text=json.dumps(widgets)
ET.ElementTree(root).write('smoke-output/preferences.xml',encoding='utf-8',xml_declaration=True)
PY
adb push smoke-output/preferences.xml /data/local/tmp/launcher-smoke-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/launcher-smoke-prefs.xml
adb shell "run-as $package sh -c 'mkdir -p shared_prefs; cat /data/local/tmp/launcher-smoke-prefs.xml > shared_prefs/luncher.xml'"
adb logcat -c
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 7
adb exec-out screencap -p > smoke-output/home.png
dump() {
  adb shell uiautomator dump /sdcard/launcher-ui.xml >/dev/null
  adb exec-out cat /sdcard/launcher-ui.xml > smoke-output/current.xml
}
point_for() {
  python3 - "$1" <<'PY'
import re,sys,xml.etree.ElementTree as ET
needle=sys.argv[1].casefold()
root=ET.parse('smoke-output/current.xml').getroot()
def walk(node, ancestors):
    value=(node.get('text','')+' '+node.get('content-desc','')).casefold()
    if needle in value:
        for a in reversed(ancestors+[node]):
            if a.get('clickable')=='true':
                nums=list(map(int,re.findall(r'\d+',a.get('bounds',''))))
                if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:
                    print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2); return True
    return any(walk(c,ancestors+[node]) for c in node)
if not walk(root,[]):
    raise SystemExit('Could not find clickable item: '+needle)
PY
}
tap_item() {
  local point
  point="$(point_for "$1")"
  echo "Tapping $1 at $point"
  adb shell input tap $point
  sleep 2
  dump
}
dump
grep -qi 'WORLD CLOCK' smoke-output/current.xml
grep -qi 'One day at a time' smoke-output/current.xml
tap_item 'WORLD CLOCK'
cp smoke-output/current.xml smoke-output/after-world.xml
adb exec-out screencap -p > smoke-output/after-world.png
grep -qi 'Choose up to four cities' smoke-output/current.xml
adb exec-out screencap -p > smoke-output/world-clock-dialog.png
tap_item 'Save'
tap_item 'One day at a time'
grep -qi 'Edit quote' smoke-output/current.xml
adb exec-out screencap -p > smoke-output/quote-dialog.png
adb shell input keyevent KEYCODE_BACK
adb shell "run-as $package am start -W -n $package/com.luncher.home.SettingsActivity"
sleep 3
dump
grep -qi 'Theme Presets' smoke-output/current.xml
grep -qi 'Sage' smoke-output/current.xml
adb exec-out screencap -p > smoke-output/settings.png
adb logcat -d -s AndroidRuntime:E > smoke-output/android-runtime.log
if grep -q 'FATAL EXCEPTION' smoke-output/android-runtime.log; then
  cat smoke-output/android-runtime.log
  exit 1
fi
echo 'Widget and theme smoke checks passed'
