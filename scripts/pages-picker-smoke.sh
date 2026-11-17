#!/usr/bin/env bash
set -euxo pipefail
package=com.luncher.home.preview3
mkdir -p page-smoke
trap 'adb logcat -d -s AndroidRuntime:E > page-smoke/android-runtime.log 2>/dev/null || true' EXIT
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 5
adb shell am force-stop "$package"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > page-smoke/activities.txt
python3 - <<'PY'
import json,re,xml.etree.ElementTree as ET
raw=open('page-smoke/activities.txt').read()
components=[]
for m in re.finditer(r'(?m)^\s*([A-Za-z][\w.]+)/([\w.$]+)\s*$',raw):
    pkg,cls=m.groups()
    if pkg=='com.luncher.home.preview3': continue
    if cls.startswith('.'): cls=pkg+cls
    elif '.' not in cls: cls=pkg+'.'+cls
    key=pkg+'/'+cls
    if key not in components: components.append(key)
def from_pkg(name):
    return next(x for x in components if x.split('/')[0]==name)
camera=from_pkg('com.android.camera2')
chrome=from_pkg('com.android.chrome')
settings=from_pkg('com.android.settings')
other=[]
for key in components:
    if key.split('/')[0] not in {x.split('/')[0] for x in [camera,chrome,settings]+other}:
        other.append(key)
assert len(other)>=3, components
membersA=[camera,other[0]]
membersB=[settings,other[1]]
outsider=other[2]
open('page-smoke/keys.json','w').write(json.dumps({'a':membersA,'b':membersB,'chrome':chrome,'outsider':outsider}))
root=ET.Element('map')
for key in ['PHOTO','ICONS','CALENDAR','BATTERY','MUSIC']:
    ET.SubElement(root,'boolean',name='s_w_'+key,value='false')
ET.SubElement(root,'boolean',name='s_anim',value='false')
ET.SubElement(root,'int',name='home_page_count',value='3')
ET.SubElement(root,'string',name='dock').text=''
ET.SubElement(root,'string',name='home_folders').text=json.dumps({'smokeA':{'name':'Folder A','apps':membersA},'smokeB':{'name':'Folder B','apps':membersB}})
keep=set(membersA+membersB+[chrome,outsider])
hidden=ET.SubElement(root,'set',name='s_hidden_apps')
for key in components:
    if key not in keep: ET.SubElement(hidden,'string').text=key
prefix='com.luncher.home.preview3/com.luncher.home.preview3.folder.'
order=[prefix+'smokeA',chrome]+['__luncher_empty_cell__']*14+[prefix+'smokeB',outsider]
ET.SubElement(root,'string',name='s_order').text='|'.join(order)
ET.SubElement(root,'string',name='additional_widgets').text=json.dumps({'extra_test':{'type':'QUOTE','page':1,'col':1,'row':1,'w':2,'h':2}})
ET.SubElement(root,'string',name='quote_extra_test').text='PAGE TEST'
ET.ElementTree(root).write('page-smoke/preferences.xml',encoding='utf-8',xml_declaration=True)
PY
adb push page-smoke/preferences.xml /data/local/tmp/page-smoke-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/page-smoke-prefs.xml
adb shell "run-as $package sh -c 'mkdir -p shared_prefs; cat /data/local/tmp/page-smoke-prefs.xml > shared_prefs/luncher.xml'"
adb logcat -c
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 8
dump() {
  adb shell uiautomator dump /sdcard/page-ui.xml >/dev/null
  adb exec-out cat /sdcard/page-ui.xml > page-smoke/current.xml
}
tap_text() {
  local point
  point="$(python3 - "$1" <<'PY'
import re,sys,xml.etree.ElementTree as ET
needle=sys.argv[1].casefold()
root=ET.parse('page-smoke/current.xml').getroot()
def walk(node, ancestors):
    if needle in (node.get('text','')+' '+node.get('content-desc','')).casefold():
        for a in reversed(ancestors+[node]):
            if a.get('clickable')=='true':
                nums=list(map(int,re.findall(r'\d+',a.get('bounds',''))))
                if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:
                    print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2); return True
        # Android list-dialog rows handle taps in their parent ListView.
        nums=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
        if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:
            print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2); return True
    return any(walk(c,ancestors+[node]) for c in node)
assert walk(root,[]), 'Clickable item not found: '+needle
PY
)"
  adb shell input tap $point
  sleep 2
  dump
}
tap_desc_exact() {
  local point
  point="$(python3 - "$1" <<'PY'
import re,sys,xml.etree.ElementTree as ET
root=ET.parse('page-smoke/current.xml').getroot()
for n in root.iter():
    if n.get('content-desc')==sys.argv[1] and n.get('clickable')=='true':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds','')))
        print((x1+x2)//2,(y1+y2)//2); break
else: raise SystemExit('Missing app tile: '+sys.argv[1])
PY
)"
  adb shell input tap $point
  sleep 2
  dump
}
set_search() {
  local point
  point="$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
root=ET.parse('page-smoke/current.xml').getroot()
for n in root.iter():
    if n.get('class')=='android.widget.EditText':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds','')))
        print((x1+x2)//2,(y1+y2)//2); break
else: raise SystemExit('Search field not found')
PY
)"
  adb shell input tap $point
  sleep 1
  adb shell input keyevent KEYCODE_MOVE_END
  adb shell input keyevent $(printf '67 %.0s' {1..30})
  adb shell input text "$1"
  adb shell input keyevent KEYCODE_BACK
  sleep 2
  dump
}
assert_no_app_tile() {
  python3 - "$1" <<'PY'
import sys,xml.etree.ElementTree as ET
root=ET.parse('page-smoke/current.xml').getroot()
assert not any(n.get('content-desc')==sys.argv[1] and n.get('clickable')=='true' for n in root.iter()),sys.argv[1]
print('Grouped app hidden from picker:',sys.argv[1])
PY
}
home_menu() {
  adb shell input swipe 8 245 8 245 1200
  sleep 2
  dump
}
read_prefs() { adb shell "run-as $package cat shared_prefs/luncher.xml" > page-smoke/current-prefs.xml; }
assert_page_count() {
  python3 - "$1" <<'PY'
import sys,xml.etree.ElementTree as ET
root=ET.parse('page-smoke/current-prefs.xml').getroot()
node=root.find("int[@name='home_page_count']")
assert node is not None and int(node.get('value'))==int(sys.argv[1]),ET.tostring(root)
print('Saved page count:',node.get('value'))
PY
}
dump
tap_text 'Folder A'
tap_text 'Add apps to folder'
set_search Camera
assert_no_app_tile Camera
set_search Settings
assert_no_app_tile Settings
set_search Chrome
tap_desc_exact Chrome
assert_no_app_tile Chrome
read_prefs
python3 - <<'PY'
import json,xml.etree.ElementTree as ET
root=ET.parse('page-smoke/current-prefs.xml').getroot()
prefs={x.get('name'):x.text or '' for x in root}
groups=json.loads(prefs['home_folders']); keys=json.load(open('page-smoke/keys.json'))
assert groups['smokeA']['apps']==keys['a']+[keys['chrome']],groups
assert groups['smokeB']['apps']==keys['b'],groups
print('Added app only to its selected folder')
PY
tap_text 'Done'
grep -qi Chrome page-smoke/current.xml
adb shell input keyevent KEYCODE_BACK
sleep 2

# Place Folder B and one ungrouped app on page 2 for the page-removal test.
read_prefs
python3 - <<'PY'
import json,xml.etree.ElementTree as ET
p='page-smoke/current-prefs.xml'; root=ET.parse(p).getroot(); keys=json.load(open('page-smoke/keys.json'))
prefix='com.luncher.home.preview3/com.luncher.home.preview3.folder.'
order=[prefix+'smokeA']+['__luncher_empty_cell__']*15+[prefix+'smokeB',keys['outsider']]
root.find("string[@name='s_order']").text='|'.join(order)
ET.ElementTree(root).write('page-smoke/rearranged.xml',encoding='utf-8',xml_declaration=True)
PY
adb shell am force-stop "$package"
adb push page-smoke/rearranged.xml /data/local/tmp/page-smoke-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/page-smoke-prefs.xml
adb shell "run-as $package sh -c 'cat /data/local/tmp/page-smoke-prefs.xml > shared_prefs/luncher.xml'"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 8
home_menu
tap_text 'Manage Pages'
tap_text 'Add Page'
read_prefs
assert_page_count 4
# An empty page must still exist after the launcher restarts.
adb shell am force-stop "$package"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 8
read_prefs
assert_page_count 4
for i in 1 2 3; do adb shell input swipe 270 350 50 350 350; sleep 2; done
home_menu
tap_text 'Manage Pages'
tap_text 'Remove This Page'
tap_text 'Remove page'
read_prefs
assert_page_count 3
# Move back to the populated page with the quote widget and Folder B.
adb shell input swipe 50 350 270 350 350
sleep 2
dump
grep -qi 'PAGE TEST' page-smoke/current.xml
grep -qi 'Folder B' page-smoke/current.xml
home_menu
tap_text 'Manage Pages'
tap_text 'Remove This Page'
tap_text 'Remove page'
read_prefs
assert_page_count 2
python3 - <<'PY'
import json,xml.etree.ElementTree as ET
root=ET.parse('page-smoke/current-prefs.xml').getroot()
prefs={x.get('name'):x.text or '' for x in root}
widgets=json.loads(prefs['additional_widgets']); groups=json.loads(prefs['home_folders'])
keys=json.load(open('page-smoke/keys.json')); order=prefs['s_order'].split('|')
assert widgets['extra_test']['page']==1,widgets
assert order.count('com.luncher.home.preview3/com.luncher.home.preview3.folder.smokeB')==1,order
assert order.count(keys['outsider'])==1,order
assert groups['smokeA']['apps']==keys['a']+[keys['chrome']] and groups['smokeB']['apps']==keys['b'],groups
print('Populated page removed with widget, folder, and app preserved')
PY
adb shell am force-stop "$package"
adb shell am start -W -n "$package/com.luncher.home.MainActivity"
sleep 8
read_prefs
assert_page_count 2
adb logcat -d -s AndroidRuntime:E > page-smoke/android-runtime.log
if grep -q 'FATAL EXCEPTION' page-smoke/android-runtime.log; then cat page-smoke/android-runtime.log; exit 1; fi
echo 'Page management and grouped app filtering checks passed'
