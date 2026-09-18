# -*- coding: utf-8 -*-
"""工具栏"直接操作光标所在块"——设备端参数化验收(可重复执行,全程数字断言)

覆盖(块文档落库后逐项核对,断言的是**哪一块**被改了,不只是"改成功了"):
  P0 夹具:三块 AAA/BBB/CCC
  P1 光标落在 BBB 上按 H1 → 只有 BBB 的 headingLevel=1,AAA/CCC 不动
  P2 再按 • → BBB 变无序列表(number=0);按 1. → 变有序(number=1)
  P3 按 ❝ → BBB 变引用;按 勾选 → BBB 变待办
  P4 按 首缩 → BBB 的 indentHead=true
  P5 样式改完文字不变(BBB 仍是 BBB),说明样式不再靠改标记文本实现
串口见下方 SERIAL(默认 emulator-5554,可用第一个参数覆盖),绝不触碰实机。
"""
import subprocess, re, time, sys, os, json
from editor_tap import tap_text_field

# 串口可覆盖：工具栏的显隐跟随 WindowInsets.isImeVisible，API 33 的 AVD 上 IME 虽在却报不可见
# （既有行为，与本批改动无关），编辑器/工具栏验收固定在 API 36 的 AVD 上跑
SERIAL = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
RESULTS = []

def sh(*a, timeout=90):
    return subprocess.run([ADB, '-s', SERIAL] + [str(x) for x in a],
                          capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore')

def dump():
    sh('shell', 'uiautomator', 'dump')
    return sh('shell', 'cat', '/sdcard/window_dump.xml')

def nodes():
    xml = dump()
    out = []
    for n in re.findall(r'<node[^>]*>', xml):
        def a(k, n=n):
            m = re.search(k + '="([^"]*)"', n)
            return m.group(1) if m else ''
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
        if not m:
            continue
        x0, y0, x1, y1 = map(int, m.groups())
        out.append(dict(text=a('text'), desc=a('desc'), cls=a('class'), focused=a('focused'),
                        x0=x0, y0=y0, x1=x1, y1=y1, w=x1-x0, h=y1-y0))
    return out

def find(ns, **kw):
    for n in ns:
        if all(str(n[k]) == v for k, v in kw.items()):
            return n
    return None

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

def tap(x, y):
    sh('shell', 'input', 'tap', str(x), str(y))
    time.sleep(1.2)

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

def seed():
    sql = ("DELETE FROM notes WHERE uuid='style-1';"
           "INSERT INTO notes(uuid,kind,title,body,color,folder_id,pinned,created_at,updated_at) "
           "VALUES ('style-1',0,'样式验证','AAA'||char(10)||'BBB'||char(10)||'CCC',0,NULL,0,"
           "strftime('%s','now')*1000,strftime('%s','now')*1000);")
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_style_seed.sql')
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(sql + '\n')
    sh('push', tmp, '/data/local/tmp/style_seed.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/style_seed.sql'" % DB)
    os.remove(tmp)

def stored_doc():
    """把库里的 v3 块文档拆成逐块字段（id/type/text/headingLevel/checked/number/indentHead/indentTail）"""
    raw = db("SELECT body FROM notes WHERE uuid='style-1';")
    out = []
    for chunk in raw.split('{"id":"')[1:]:
        def g(pat, default=''):
            m = re.search(pat, chunk)
            return m.group(1) if m else default
        out.append(dict(
            id=g(r'^([^"]+)'),
            type=g(r'"type":"([A-Z]+)"', 'TEXT'),
            text=g(r'"text":"([^"]*)"'),
            heading=int(g(r'"headingLevel":(\d+)', '0')),
            checked=g(r'"checked":(true|false)', 'false') == 'true',
            number=int(g(r'"number":(\d+)', '0')),
            indent_head=g(r'"indentHead":(true|false)', 'false') == 'true',
            indent_tail=g(r'"indentTail":(true|false)', 'false') == 'true',
        ))
    return out

def doc_by_text(t):
    for b in stored_doc():
        if b['text'] == t:
            return b
    return None

def dismiss_system_dialogs():
    """系统级弹窗（权限/ANR）会顶在最前面让 dump 里看不到应用节点：先收掉再继续"""
    for _ in range(3):
        ns = nodes()
        hit = None
        for label in ('允许', 'Allow', 'Wait', '等待', '确定', 'OK'):
            hit = find(ns, text=label)
            if hit:
                break
        if hit is None:
            return
        tap(*center(hit))
        time.sleep(1.5)

def open_editor():
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    dismiss_system_dialogs()
    in_ed = lambda: len([n for n in nodes() if n['text'] in ('AAA', 'BBB', 'CCC') and 'EditText' in n['cls']]) >= 3
    if in_ed():
        return True
    card = None
    for _ in range(5):
        card = find(nodes(), text='样式验证')
        if card:
            break
        time.sleep(2)
    if not card:
        return False
    tap(*center(card))
    time.sleep(2.5)
    return in_ed()

def block_node(t):
    return find(nodes(), text=t, cls='android.widget.EditText')

def ensure_style_panel(open_panel: bool) -> bool:
    """样式面板与主工具栏互斥：切样式键前先开面板，切勾选键前先关面板"""
    is_open = find(nodes(), text='H1') is not None
    if open_panel == is_open:
        return True
    key = find(nodes(), desc='样式') if open_panel else find(nodes(), desc='收起样式')
    if not key:
        return False
    tap(*center(key))
    time.sleep(0.9)
    return True

def open_style_panel():
    return ensure_style_panel(True)

def tap_key(text=None, desc=None, panel=None):
    if panel is not None:
        ensure_style_panel(panel)
    n = find(nodes(), text=text) if text else find(nodes(), desc=desc)
    if not n:
        return False
    tap(*center(n))
    time.sleep(1.6)     # 等去抖保存(500ms)落库
    return True

# ---------- 夹具 ----------
seed()
check('P0 夹具写入三块', db("SELECT body FROM notes WHERE uuid='style-1';").splitlines() == ['AAA', 'BBB', 'CCC'])
if not open_editor():
    check('P0 打开编辑器', False)
    sys.exit(1)
b = block_node('BBB')
if not b:
    check('P0 找得到 BBB 块节点', False)
    sys.exit(1)

# 光标落在 BBB 上（a11y 坐标比真实文字高约 45px，用 editor_tap 校验后再落光标）
tap_text_field(sh, ADB, SERIAL, nodes, 'BBB', time.sleep, label='BBB')
if not open_style_panel():
    check('P0 样式面板打开', False)
    sys.exit(1)

# ---------- P1 H1 ----------
tap_key(text='H1', panel=True)
doc = stored_doc()
bbb = doc_by_text('BBB')
check('P1 H1 落在光标块 BBB 上', bbb is not None and bbb['heading'] == 1, str(bbb))
check('P1 其它块未被误改', [x['heading'] for x in doc if x['text'] in ('AAA', 'CCC')] == [0, 0],
      str([(x['text'], x['heading']) for x in doc]))

# ---------- P2 无序 → 有序 ----------
tap_key(text='•', panel=True)
bbb = doc_by_text('BBB')
check('P2 • 让 BBB 变无序列表', bbb is not None and bbb['type'] == 'ITEM' and bbb['number'] == 0, str(bbb))
tap_key(text='1.', panel=True)
bbb = doc_by_text('BBB')
check('P2 1. 让它变有序列表(number=1)', bbb is not None and bbb['type'] == 'ITEM' and bbb['number'] == 1, str(bbb))

# ---------- P3 引用 → 待办 ----------
tap_key(text='❝', panel=True)
bbb = doc_by_text('BBB')
check('P3 ❝ 让它变引用块', bbb is not None and bbb['type'] == 'QUOTE', str(bbb))
# 勾选键在主工具栏上：样式面板打开时它被面板替换掉了，先收起面板再点
tap_key(desc='勾选', panel=False)
bbb = doc_by_text('BBB')
check('P3 勾选让它变待办块', bbb is not None and bbb['type'] == 'TODO', str(bbb))
if not (bbb and bbb['type'] == 'TODO'):
    ensure_style_panel(False)
    tap_key(desc='勾选')
    bbb = doc_by_text('BBB')
    check('P3 重试后勾选变待办块', bbb is not None and bbb['type'] == 'TODO', str(bbb))

# ---------- P4 首缩 ----------
tap_key(text='首缩', panel=True)
bbb = doc_by_text('BBB')
check('P4 首缩打开 indentHead', bbb is not None and bbb['indent_head'], str(bbb))

# ---------- P5 文字不变 ----------
check('P5 样式改完文字仍是 BBB(不再靠改标记文本)', bbb is not None and bbb['text'] == 'BBB', str(bbb))
check('P5 三块顺序与文字未被扰动', [x['text'] for x in stored_doc()] == ['AAA', 'BBB', 'CCC'],
      str([x['text'] for x in stored_doc()]))

# 打字/回车拆块/退格并块不在这里验：本机(API 36 AVD)的 input text 进不到应用，
# 那几条在 API 33 上用 tools/verify/block_edit_verify.py 验（同一份代码）。

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
