# -*- coding: utf-8 -*-
"""脑图（思维笔记）设备端验收——可重复执行，全程数字断言

覆盖:
  P0 夹具:一条 MIND 笔记(中心主题 + 两个子节点),卡片显示大纲投影
  P1 打开后导图渲染出全部节点,且几何关系正确(子节点在父节点右侧、兄弟上下不重叠)
  P2 选中子节点 → 加子级 → 库里该节点多出一个子节点
  P3 长按拖动"子二"到"子一"上 → 库里变成子一的子节点
  P4 拖到自己的子孙上(会造成环) → 树原样不动
  P5 折叠该节点 → 库内 collapsed=true,且它的子节点从界面上消失
  P6 大纲视图列出全部节点(折叠行不展开子节点)
  P7 首页"新建脑图"入口 → 新建的笔记 kind=2 落库
串口见 SERIAL(默认 emulator-5554,可用第一个参数覆盖),绝不触碰实机。
"""
import subprocess, re, time, sys, os, json

SERIAL = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
RESULTS = []

def sh(*a, timeout=90):
    return subprocess.run([ADB, '-s', SERIAL] + [str(x) for x in a],
                          capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore')

def nodes():
    sh('shell', 'uiautomator', 'dump')
    xml = sh('shell', 'cat', '/sdcard/window_dump.xml')
    out = []
    for n in re.findall(r'<node[^>]*>', xml):
        def a(k, n=n):
            m = re.search(k + '="([^"]*)"', n)
            return m.group(1) if m else ''
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
        if not m:
            continue
        x0, y0, x1, y1 = map(int, m.groups())
        out.append(dict(text=a('text'), desc=a('desc'), cls=a('class'),
                        x0=x0, y0=y0, x1=x1, y1=y1))
    return out

def find(ns, **kw):
    for n in ns:
        if all(str(n[k]) == v for k, v in kw.items()):
            return n
    return None

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

def tap(x, y, wait=1.4):
    sh('shell', 'input', 'tap', str(x), str(y))
    time.sleep(wait)

def check(name, ok, detail=''):
    RESULTS.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, ('| ' + detail if detail else ''), flush=True)

def resolve_db():
    listing = sh('shell', 'run-as', PKG, 'ls', 'databases')
    cands = [l.strip() for l in listing.splitlines()
             if l.strip().startswith('purenote-') and l.strip().endswith('.db')]
    if not cands:
        raise SystemExit('找不到数据库文件: ' + listing)
    return 'databases/' + cands[0]

DB = resolve_db()
print('DB =', DB, flush=True)

def db(sql):
    return sh('shell', 'run-as %s sqlite3 %s "%s"' % (PKG, DB, sql)).strip().replace('\r', '')

def stored_mind(uuid='mind-1'):
    raw = db("SELECT body FROM notes WHERE uuid='%s';" % uuid)
    try:
        return json.loads(raw) if raw.startswith('{') else None
    except Exception:
        return None

def seed():
    doc = {
        "version": 1,
        "root": {
            "id": "root", "label": "中心主题", "collapsed": False,
            "children": [
                {"id": "c1", "label": "子一", "collapsed": False, "children": [
                    {"id": "g1", "label": "孙一", "collapsed": False, "children": []},
                ]},
                {"id": "c2", "label": "子二", "collapsed": False, "children": []},
            ],
        },
        "view": "MIND",
    }
    body = json.dumps(doc, ensure_ascii=False).replace("'", "''")
    sql = ("DELETE FROM notes WHERE uuid='mind-1';"
           "INSERT INTO notes(uuid,kind,title,body,body_format_version,color,folder_id,pinned,created_at,updated_at) "
           "VALUES ('mind-1',2,'中心主题','%s',3,0,NULL,0,"
           "strftime('%%s','now')*1000,strftime('%%s','now')*1000);" % body)
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_mind_seed.sql')
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(sql + '\n')
    sh('push', tmp, '/data/local/tmp/mind_seed.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/mind_seed.sql'" % DB)
    os.remove(tmp)

def label_node(t):
    """取画布里的节点：页眉标题是整行宽的 Text，节点只有 label 那么宽"""
    for n in nodes():
        if n['text'] == t and (n['x1'] - n['x0']) < 500:
            return n
    return None

def open_home():
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)

def open_fixture():
    open_home()
    card = None
    for _ in range(5):
        card = find(nodes(), text='中心主题')
        if card:
            break
        time.sleep(2)
    if not card:
        return False
    tap(*center(card))
    time.sleep(2.5)
    return label_node('中心主题') is not None

# ---------- P0 ----------
seed()
check('P0 夹具写入 MIND 笔记(kind=2)', (stored_mind() or {}).get('root', {}).get('label') == '中心主题',
      str(db("SELECT kind,title FROM notes WHERE uuid='mind-1';")))
open_home()
card = None
for _ in range(5):
    card = find(nodes(), text='中心主题')
    if card:
        break
    time.sleep(2)
check('P0 首页卡片显示脑图', card is not None)
if not card:
    sys.exit(1)
tap(*center(card))
time.sleep(2.5)

# ---------- P1 渲染与几何 ----------
root_n, c1, c2, g1 = (label_node('中心主题'), label_node('子一'), label_node('子二'), label_node('孙一'))
check('P1 四个节点都渲染出来', all([root_n, c1, c2, g1]),
      str([n['text'] for n in nodes() if n['text'] in ('中心主题', '子一', '子二', '孙一')]))
if all([root_n, c1, c2, g1]):
    check('P1 子节点在父节点右侧', c1['x0'] > root_n['x1'] - 5 and c2['x0'] > root_n['x1'] - 5,
          'root.right=%s c1.x0=%s c2.x0=%s' % (root_n['x1'], c1['x0'], c2['x0']))
    check('P1 兄弟节点上下不重叠', c1['y1'] <= c2['y0'] or c2['y1'] <= c1['y0'],
          'c1=%s..%s c2=%s..%s' % (c1['y0'], c1['y1'], c2['y0'], c2['y1']))
    check('P1 孙节点在子一右侧', g1['x0'] > c1['x1'] - 5, 'c1.right=%s g1.x0=%s' % (c1['x1'], g1['x0']))

# ---------- P2 选中子二 → 加子级 ----------
if c2:
    tap(*center(c2))
    add = find(nodes(), desc='加子级')
    check('P2 选中后出现节点操作栏', add is not None)
    if add:
        tap(*center(add))
        time.sleep(2.0)
        doc = stored_mind() or {}
        kids = [c for c in (doc.get('root', {}).get('children') or []) if c['id'] == 'c2'][0]['children']
        check('P2 子二多出一个子节点', len(kids) == 1, str(kids))
else:
    check('P2 选中后出现节点操作栏', False, '子二节点没找到')

# ---------- P3 拖动换父：把"子二"拖到"子一"上 ----------
c2n, c1n = label_node('子二'), label_node('子一')
if c2n and c1n:
    sh('shell', 'input', 'draganddrop',
       str((c2n['x0'] + c2n['x1']) // 2), str((c2n['y0'] + c2n['y1']) // 2),
       str((c1n['x0'] + c1n['x1']) // 2), str((c1n['y0'] + c1n['y1']) // 2), '3000')
    time.sleep(2.5)
    doc = stored_mind() or {}
    tops = doc.get('root', {}).get('children') or []
    c1s = [c for c in tops if c['id'] == 'c1']
    nested = bool(c1s) and any(c['id'] == 'c2' for c in (c1s[0].get('children') or []))
    check('P3 拖到"子一"上：子二成为它的子节点', nested, str(tops))
    check('P3 根下不再直接挂着子二', not any(c['id'] == 'c2' for c in tops), str([c['id'] for c in tops]))
    # 换父后新父应自动展开，否则用户看不到结果（feature 里 move 的既定语义）
    check('P3 换父后子二仍可见', label_node('子二') is not None,
          str([n['text'] for n in nodes() if n['text'] in ('子一', '子二', '孙一')]))
else:
    check('P3 拖到"子一"上：子二成为它的子节点', False, '拖拽前节点没找到')

# ---------- P4 无效换父：拖到自己的子孙上，树必须原样不动 ----------
before_tree = json.dumps(stored_mind(), sort_keys=True, ensure_ascii=False)
g1n, c1n = label_node('孙一'), label_node('子一')
if g1n and c1n:
    sh('shell', 'input', 'draganddrop',
       str((c1n['x0'] + c1n['x1']) // 2), str((c1n['y0'] + c1n['y1']) // 2),
       str((g1n['x0'] + g1n['x1']) // 2), str((g1n['y0'] + g1n['y1']) // 2), '3000')
    time.sleep(2.5)
    after_tree = json.dumps(stored_mind(), sort_keys=True, ensure_ascii=False)
    check('P4 拖到自己的子孙上不成环、树不变', before_tree == after_tree,
          'before!=after' if before_tree != after_tree else 'unchanged')
else:
    check('P4 拖到自己的子孙上不成环、树不变', False, '节点没找到（折叠/布局原因）')

# ---------- P5 折叠子一 ----------
# 注意：坐标必须重新取——换父之后布局变了，P1 时抓的节点位置已经作废
c1 = label_node('子一')
if c1:
    tap(*center(c1))
    fold = find(nodes(), desc='折叠') or find(nodes(), desc='展开')
    if fold:
        tap(*center(fold))
        time.sleep(2.0)
        doc = stored_mind() or {}
        c1s = [c for c in (doc.get('root', {}).get('children') or []) if c['id'] == 'c1'][0]
        check('P5 折叠状态落库', c1s.get('collapsed') is True, str(c1s.get('collapsed')))
        check('P5 折叠后子节点从导图消失', label_node('孙一') is None and label_node('子二') is None,
              str([n['text'] for n in nodes() if n['text'] in ('孙一', '子一', '子二')]))
    else:
        check('P5 折叠状态落库', False, '折叠键没找到')
else:
    check('P5 折叠状态落库', False, '子一节点没找到')

# ---------- P6 大纲视图 ----------
outline = find(nodes(), text='大纲')
if outline:
    tap(*center(outline))
    time.sleep(1.5)
    have = {n['text'] for n in nodes()}
    check('P6 大纲列出根与子节点', {'中心主题', '子一'} <= have, str(sorted(have)))
    check('P6 折叠的子一不展开孙节点与子二', '孙一' not in have and '子二' not in have, str(sorted(have)))
    back = find(nodes(), text='导图')
    if back:
        tap(*center(back))
        time.sleep(1.0)
else:
    check('P6 大纲列出根与子节点', False, '大纲键没找到')

# ---------- P7 首页新建脑图 ----------
sh('shell', 'input', 'keyevent', '4')      # 返回：编辑器会先落库再退
time.sleep(2.0)
sh('shell', 'am', 'force-stop', PKG)
open_home()
new_mind = find(nodes(), desc='新建脑图')
check('P5 首页有"新建脑图"入口', new_mind is not None)
if new_mind:
    before = db("SELECT COUNT(1) FROM notes;").strip()
    tap(*center(new_mind))
    time.sleep(2.5)
    check('P5 进入脑图编辑器(导图/大纲可见)', find(nodes(), text='大纲') is not None,
          str(sorted({n['text'] for n in nodes() if n['text']})))
    # 空草稿不落库是既有规则：建一个子节点并写上字，才会真正创建这条笔记
    root_node = label_node('（空）')
    if root_node:
        tap(*center(root_node))
        add = find(nodes(), desc='加子级')
        if add:
            tap(*center(add))
            time.sleep(1.0)
            edit = find(nodes(), desc='改文字')
            if edit:
                tap(*center(edit))
                time.sleep(1.2)
                field = find(nodes(), cls='android.widget.EditText')
                if field:
                    tap(*center(field))
                    sh('shell', 'input', 'text', 'NEWNODE')
                    time.sleep(0.8)
                ok_btn = find(nodes(), text='确定')
                if ok_btn:
                    tap(*center(ok_btn))
                    time.sleep(2.5)
    after = int(db("SELECT COUNT(1) FROM notes;").strip() or 0)
    kinds = db("SELECT id,kind,title FROM notes ORDER BY id DESC LIMIT 1;").strip()
    check('P5 新建的脑图落库且 kind=2', after == int(before or 0) + 1 and kinds.split('|')[1:2] == ['2'],
          'before=%s after=%s new=%s' % (before, after, kinds))

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
