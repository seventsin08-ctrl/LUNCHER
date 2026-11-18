import json, re, sys, xml.etree.ElementTree as ET
root = ET.parse('dock-smoke/current.xml').getroot()
parents = {child: node for node in root.iter() for child in node}
def bounds(n):
    value = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
    assert len(value) == 4, ET.tostring(n)
    return value
def dock_cell(label):
    return next(n for n in root.iter() if n.get('content-desc') == label)
def dock_row(label):
    node = dock_cell(label)
    while node in parents:
        node = parents[node]
        if node.get('class') == 'android.widget.LinearLayout':
            return node
    raise AssertionError('No dock parent for ' + label)
def center(n):
    x1,y1,x2,y2 = bounds(n)
    return (x1+x2)//2, (y1+y2)//2
cmd = sys.argv[1]
label = sys.argv[2] if len(sys.argv)>2 else 'Camera'
if cmd == 'dock':
    print(*bounds(dock_row(label)))
elif cmd == 'dock-app':
    print(*center(dock_cell(label)))
elif cmd == 'home-app':
    n = next(n for n in root.iter() if n.get('text') == label)
    cell = parents[n]
    x1,y1,x2,y2 = bounds(cell)
    print((x1+x2)//2, y1+25)
elif cmd == 'home-empty':
    pager = next(n for n in root.iter() if n.get('class','').endswith('ViewPager'))
    x1,y1,x2,y2 = bounds(pager)
    print(x1 + (x2-x1)*4//5, y1+38)
elif cmd == 'minus':
    cell = dock_cell(label)
    for n in cell.iter():
        if n.get('text') in ['−','-'] and n.get('clickable') == 'true':
            print(*center(n)); break
    else:
        x1,y1,x2,y2 = bounds(cell)
        print((x1+x2)//2-26, y1+4)
elif cmd == 'button':
    n = next(n for n in root.iter() if label in (n.get('text','')+' '+n.get('content-desc','')))
    for a in [n,parents.get(n)]:
        if a is not None and a.get('clickable') == 'true':
            print(*center(a)); break
    else:
        print(*center(n))
elif cmd == 'measure':
    rect = bounds(dock_row(label))
    json.dump(rect, open('dock-smoke/'+sys.argv[3]+'.json','w'))
    print('Dock bounds', rect)
else:
    raise AssertionError(cmd)
