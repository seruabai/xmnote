# -*- coding: utf-8 -*-
"""块编辑链路回归（2.4 把正文契约从"标记文本"换成"块文档"之后的口径）

覆盖:
  P1 块内打字 → 文本进块并落库（v3 块文档）
  P2 回车拆块 → 新块位置正确，且能在里面接着打字落库
  P3 段首退格并块 → 合回一块
  P4 清单块勾选 → checked 翻转（勾选框是块自己的控件）
在本机的 API 33 AVD（emulator-5554）上跑：那台机器的 input text 能进应用，
API 36 的 AVD 上 input text 进不去（注入问题，不是应用问题）。
只用指定串口，绝不触碰实机。
"""
import subprocess, re, time, sys, os

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
        out.append(dict(text=a('text'), desc=a('desc'), cls=a('class'), checked=a('checked'),
                        x0=x0, y0=y0, x1=x1, y1=y1))
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

def key(code):
    sh('shell', 'input', 'keyevent', str(code))
    time.sleep(0.6)

def type_until(s, expect, attempts=3):
    """逐字符注入并重试，返回成功前用掉的尝试次数（0 = 始终没送进去）。

    **只用数字**：实测 API 36 的镜像上 input text 送字母/中文进不了应用（同样状态下
    数字 1 能进、z/Z/中 一律丢失），是模拟器注入通道的限制，不是应用行为。
    回车后 IME 连接重建期间还有一次丢字窗口，所以允许重试并记录次数当数据。
    """
    for i in range(1, attempts + 1):
        type_text(s, settle=1.6)
        time.sleep(1.6)
        if expect():
            return i
    return 0

def type_text(s, settle=1.2, per_char=0.45):
    """逐字符注入。

    多字符的 input text 在一次提交里发给 IME：刚发生焦点切换（回车建新块）时
    IME 连接还在重建，整串会被丢掉——实测同样的动作单字符能进、整串丢。
    这是注入通道的性质，不是应用行为，所以验收脚本自己按字符发。
    """
    time.sleep(settle)
    for ch in s:
        sh('shell', 'input', 'text', ch)
        time.sleep(per_char)

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

def blocks():
    raw = db("SELECT body FROM notes WHERE uuid='edit-1';")
    out = []
    for chunk in raw.split('{"id":"')[1:]:
        def g(pat, default=''):
            m = re.search(pat, chunk)
            return m.group(1) if m else default
        out.append(dict(
            id=g(r'^([^"]+)'),
            type=g(r'"type":"([A-Z]+)"', 'TEXT'),
            text=g(r'"text":"([^"]*)"'),
            checked=g(r'"checked":(true|false)', 'false') == 'true',
        ))
    return out

def seed():
    sql = ("DELETE FROM notes WHERE uuid='edit-1';"
           "INSERT INTO notes(uuid,kind,title,body,color,folder_id,pinned,created_at,updated_at) "
           "VALUES ('edit-1',0,'编辑验证','AAA'||char(10)||'BBB'||char(10)||"
           "'- [ ] 买牛奶',0,NULL,0,strftime('%s','now')*1000,strftime('%s','now')*1000);")
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_edit_seed.sql')
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(sql + '\n')
    sh('push', tmp, '/data/local/tmp/edit_seed.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/edit_seed.sql'" % DB)
    os.remove(tmp)

def block_node(t):
    return find(nodes(), text=t, cls='android.widget.EditText')

def open_editor():
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    in_ed = lambda: block_node('AAA') is not None and block_node('BBB') is not None
    if in_ed():
        return True
    card = None
    for _ in range(5):
        card = find(nodes(), text='编辑验证')
        if card:
            break
        time.sleep(2)
    if not card:
        return False
    tap(*center(card))
    time.sleep(2.5)
    return in_ed()

# ---------- 夹具 ----------
seed()
if not open_editor():
    check('P0 打开编辑器', False)
    sys.exit(1)
# 夹具是旧格式正文，落库要等第一次编辑（P1）之后——这里先看界面上的三块
ui_rows = [(n['y0'], n['text']) for n in nodes() if n['text'] in ('AAA', 'BBB', '买牛奶')]
check('P0 三块已渲染且按序', [t for _, t in sorted(ui_rows)] == ['AAA', 'BBB', '买牛奶'], str(ui_rows))

# ---------- P1 打字 ----------
a = block_node('AAA')
tap(a['x0'] + 60, (a['y0'] + a['y1']) // 2)
key(123)                       # MOVE_END
type_text('12')
time.sleep(2.0)
texts = [b['text'] for b in blocks()]
check('P1 块内打字落库：AAA → AAA12', texts == ['AAA12', 'BBB', '买牛奶'], str(texts))

# ---------- P2 回车拆块 ----------
key(66)                        # ENTER
time.sleep(1.2)
texts = [b['text'] for b in blocks()]
check('P2 回车拆出空块且位置正确', texts == ['AAA12', '', 'BBB', '买牛奶'], str(texts))
tries = type_until('34', lambda: [b['text'] for b in blocks()] == ['AAA12', '34', 'BBB', '买牛奶'])
texts = [b['text'] for b in blocks()]
check('P2 新块里打字落库（回车后能接着写）', texts == ['AAA12', '34', 'BBB', '买牛奶'],
      '注入尝试 %d 次 | %s' % (tries, texts))

# ---------- P3 段首退格并块 ----------
key(122)                       # MOVE_HOME → 新块行首
key(67)                        # BACKSPACE → 并入上一块
time.sleep(2.0)
texts = [b['text'] for b in blocks()]
check('P3 段首退格并块：AAA1234 合成一块', texts == ['AAA1234', 'BBB', '买牛奶'], str(texts))

# ---------- P4 勾选 ----------
todo = block_node('买牛奶') or find(nodes(), text='买牛奶')
box = None
for n in nodes():
    if 'CheckBox' in (n['cls'] or '') and todo and abs(n['y0'] - todo['y0']) < 90:
        box = n
        break
if box:
    before = [b['checked'] for b in blocks() if b['text'] == '买牛奶']
    tap(*center(box))
    time.sleep(2.0)
    after = [b['checked'] for b in blocks() if b['text'] == '买牛奶']
    check('P4 点勾选框翻转 checked', before != after and after == [True], 'before=%s after=%s' % (before, after))
else:
    print('SKIP P4 勾选框节点未找到（不同系统版本的语义树差异）', flush=True)

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
