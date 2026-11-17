#!/usr/bin/env bash
set -euxo pipefail
package=com.luncher.home.preview3
mkdir -p editbar-smoke
trap 'adb logcat -d -s AndroidRuntime:E > editbar-smoke/android-runtime.log 2>/dev/null || true' EXIT
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell cmd package set-home-activity "$package/com.luncher.home.MainActivity"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 5
adb shell am force-stop "$package"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > editbar-smoke/activities.txt
python3 - <<'PY'
import re,xml.etree.ElementTree as ET
raw=open('editbar-smoke/activities.txt').read()
components=[]
for match in re.finditer(r'(?m)^\s*([A-Za-z][\w.]+)/([\w.$]+)\s*$',raw):
    pkg,cls=match.groups()
    if pkg=='com.luncher.home.preview3': continue
    if cls.startswith('.'): cls=pkg+cls
    elif '.' not in cls: cls=pkg+'.'+cls
    key=pkg+'/'+cls
    if key not in components: components.append(key)
chrome=next(x for x in components if x.startswith('com.android.chrome/'))
root=ET.Element('map')
for key in ['PHOTO','ICONS','CALENDAR','BATTERY','MUSIC']:
    ET.SubElement(root,'boolean',name='s_w_'+key,value='false')
ET.SubElement(root,'boolean',name='s_anim',value='false')
ET.SubElement(root,'int',name='home_page_count',value='3')
ET.SubElement(root,'string',name='dock').text=''
ET.SubElement(root,'string',name='s_order').text=chrome
hidden=ET.SubElement(root,'set',name='s_hidden_apps')
for key in components:
    if key!=chrome: ET.SubElement(hidden,'string').text=key
ET.ElementTree(root).write('editbar-smoke/preferences.xml',encoding='utf-8',xml_declaration=True)
PY
adb push editbar-smoke/preferences.xml /data/local/tmp/editbar-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/editbar-prefs.xml
adb shell "run-as $package sh -c 'mkdir -p shared_prefs; cat /data/local/tmp/editbar-prefs.xml > shared_prefs/luncher.xml'"
adb logcat -c
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 8
dump() {
  adb shell uiautomator dump /sdcard/editbar-ui.xml >/dev/null
  adb exec-out cat /sdcard/editbar-ui.xml > editbar-smoke/current.xml
}
tap_text() {
  local point
  point="$(python3 - "$1" <<'PY'
import re,sys,xml.etree.ElementTree as ET
needle=sys.argv[1].casefold()
root=ET.parse('editbar-smoke/current.xml').getroot()
def walk(node,ancestors):
    if needle in (node.get('text','')+' '+node.get('content-desc','')).casefold():
        for a in reversed(ancestors+[node]):
            if a.get('clickable')=='true':
                xy=list(map(int,re.findall(r'\d+',a.get('bounds',''))))
                if len(xy)==4 and xy[2]>xy[0] and xy[3]>xy[1]:
                    print((xy[0]+xy[2])//2,(xy[1]+xy[3])//2); return True
        xy=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
        if len(xy)==4 and xy[2]>xy[0] and xy[3]>xy[1]:
            print((xy[0]+xy[2])//2,(xy[1]+xy[3])//2); return True
    return any(walk(child,ancestors+[node]) for child in node)
assert walk(root,[]), 'Item not found: '+needle
PY
)"
  adb shell input tap $point
  sleep 2
  dump
}
read_prefs() { adb shell "run-as $package cat shared_prefs/luncher.xml" > editbar-smoke/current-prefs.xml; }
assert_page_count() {
  python3 - "$1" <<'PY'
import sys,xml.etree.ElementTree as ET
root=ET.parse('editbar-smoke/current-prefs.xml').getroot()
node=root.find("int[@name='home_page_count']")
assert node is not None and int(node.get('value'))==int(sys.argv[1]), ET.tostring(root)
print('Saved page count:',node.get('value'))
PY
}
dump
cp editbar-smoke/current.xml editbar-smoke/before.xml
grep -q 'text="Chrome"' editbar-smoke/current.xml
! grep -q 'make Apple Launcher your Home app' editbar-smoke/current.xml
adb shell input swipe 8 245 8 245 1200
sleep 2
dump
tap_text 'Edit Home Screen'
cp editbar-smoke/current.xml editbar-smoke/editing.xml
python3 - <<'PY'
import re,xml.etree.ElementTree as ET
def parse(name): return ET.parse('editbar-smoke/'+name+'.xml').getroot()
def bounds(node): return tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))
def one(root,pred): return next(n for n in root.iter() if pred(n))
before,after=parse('before'),parse('editing')
pager=lambda r: one(r,lambda n: n.get('class','').endswith('ViewPager'))
chrome=lambda r: one(r,lambda n: n.get('text')=='Chrome')
assert bounds(pager(before))==bounds(pager(after)), (bounds(pager(before)),bounds(pager(after)))
assert bounds(chrome(before))==bounds(chrome(after)), (bounds(chrome(before)),bounds(chrome(after)))
bottom=bounds(pager(after))[3]
for label in ['Manage home pages','Done','＋']:
    button=one(after,lambda n: n.get('text')==label or n.get('content-desc')==label)
    assert bounds(button)[1]>=bottom, (label,bounds(button),bounds(pager(after)))
print('Apps and pager stay in place; edit controls sit below the pager')
PY
tap_text '＋'
grep -q 'Add widgets' editbar-smoke/current.xml
tap_text 'CANCEL'
tap_text 'Manage home pages'
grep -q 'Home pages' editbar-smoke/current.xml
tap_text 'Add Page'
read_prefs
assert_page_count 4
tap_text 'Manage home pages'
tap_text 'Remove This Page'
tap_text 'Remove page'
read_prefs
assert_page_count 3
tap_text 'Done'
grep -q 'text="Search"' editbar-smoke/current.xml
! grep -q 'Manage home pages' editbar-smoke/current.xml
adb shell am force-stop "$package"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 5
read_prefs
assert_page_count 3
echo 'Edit layout, widget menu, page actions and persistence passed'
