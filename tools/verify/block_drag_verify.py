# -*- coding: utf-8 -*-
"""块级长按拖拽排序——设备端参数化验收(可重复执行,全程数字断言,不靠肉眼)

覆盖:
  P0 夹具:一条三块笔记(AAA/BBB/CCC)
  P1 三块各自成输入框,顺序与正文一致
  P2 长按第三块拖到最前:界面顺序变为 CCC/AAA/BBB
  P3 落库是 v3 块文档,且库内块顺序与界面一致
  P4 在同一块内部拖一下(落点=原位):顺序不变,也不多写一次

拖拽注入用 input draganddrop:它是单一进程的连贯触摸流(先长按 0.5s 再移动)。
逐条 input motionevent 每次都是新进程,设备端不会把它们当成同一次手势,推不出拖拽。
只使用 emulator-5554,绝不触碰实机。
"""
import subprocess, re, time, sys, os

SERIAL = 'emulator-5554'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
SHOT_DIR = r'E:/DSH/tmp/blockdrag'
RESULTS = []

def sh(*a, timeout=90):
    return subprocess.run([ADB, '-s', SERIAL] + [str(x) for x in a],
                          capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore')

def dump():
    sh('shell', 'uiautomator', 'dump')
    return sh('shell', 'cat', '/sdcard/window_dump.xml')

def nodes(xml=None):
    xml = dump() if xml is None else xml
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
                        x0=x0, y0=y0, x1=x1, y1=y1, w=x1-x0, h=y1-y0))
    return out

def find(ns, **kw):
    for n in ns:
        if all(str(n[k]) == v for k, v in kw.items()):
            return n
    return None

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

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
    # 整条命令当单个参数传:分开传的话设备 shell 会按空格拆开,读回来是空串
    return sh('shell', 'run-as %s sqlite3 %s "%s"' % (PKG, DB, sql)).strip().replace('\r', '')

def seed():
    sql = ("DELETE FROM notes WHERE uuid='drag-1';"
           "INSERT INTO notes(uuid,kind,title,body,color,folder_id,pinned,created_at,updated_at) "
           "VALUES ('drag-1',0,'拖拽验证','AAA'||char(10)||'BBB'||char(10)||'CCC',0,NULL,0,"
           "strftime('%s','now')*1000,strftime('%s','now')*1000);")
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_drag_seed.sql')
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(sql + '\n')
    sh('push', tmp, '/data/local/tmp/drag_seed.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/drag_seed.sql'" % DB)
    os.remove(tmp)

def block_node(t):
    return find(nodes(), text=t, cls='android.widget.EditText')

def order_in_ui():
    got = [(n['y0'], n['text']) for n in nodes() if n['text'] in ('AAA', 'BBB', 'CCC')]
    return [t for _, t in sorted(got)]

def in_editor():
    return len([n for n in nodes() if n['text'] in ('AAA', 'BBB', 'CCC')]) >= 3

def open_editor():
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    if in_editor():
        return True
    card = None
    for _ in range(5):
        card = find(nodes(), text='拖拽验证')
        if card:
            break
        time.sleep(2)
    if not card:
        return False
    sh('shell', 'input', 'tap', str(center(card)[0]), str(center(card)[1]))
    time.sleep(3)
    return in_editor()

def shot(name):
    os.makedirs(SHOT_DIR, exist_ok=True)
    with open(SHOT_DIR + '/' + name, 'wb') as f:
        f.write(subprocess.run([ADB, '-s', SERIAL, 'exec-out', 'screencap', '-p'],
                               capture_output=True, timeout=60).stdout)

def drag(x1, y1, x2, y2, duration=5000, mid_shot=None, mid_at=2.4):
    p = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'input', 'draganddrop',
                          str(x1), str(y1), str(x2), str(y2), str(duration)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if mid_shot:
        time.sleep(mid_at)
        shot(mid_shot)
    p.wait()
    time.sleep(1.8)

def drag_above(src_text, dst_text, mid_shot=None, attempts=3):
    """把 src 拖到 dst 的上半区(= dst 之前),最多试 attempts 次"""
    for i in range(attempts):
        src, dst = block_node(src_text), block_node(dst_text)
        if not src or not dst:
            return None
        x = src['x0'] + 60
        y_from = (src['y0'] + src['y1']) // 2
        y_to = dst['y0'] + max(6, dst['h'] // 5)
        drag(x, y_from, x, y_to, mid_shot=mid_shot if i == 0 else None)
        got = order_in_ui()
        if got[:1] == [src_text]:
            return got
    return order_in_ui()

# ---------- P0 夹具 ----------
seed()
body0 = db("SELECT body FROM notes WHERE uuid='drag-1';")
check('P0 夹具写入三行 Markdown', body0.splitlines() == ['AAA', 'BBB', 'CCC'], repr(body0[:40]))
if not open_editor():
    check('P0 打开编辑器', False)
    sys.exit(1)
check('P1 三个块各自成输入框且按序排列', order_in_ui() == ['AAA', 'BBB', 'CCC'], str(order_in_ui()))

a, c = block_node('AAA'), block_node('CCC')
if not a or not c:
    check('P1 找得到首尾块节点', False)
    sys.exit(1)

# ---------- P2 长按拖到最前 ----------
after = drag_above('CCC', 'AAA', mid_shot='drag-mid.png')
check('P2 第三块拖到最前后:C 在 A 之上', after == ['CCC', 'AAA', 'BBB'], str(after))

# ---------- P3 落库顺序 ----------
time.sleep(2)
ver = db("SELECT body_format_version FROM notes WHERE uuid='drag-1';").strip()
body = db("SELECT body FROM notes WHERE uuid='drag-1';")
ids = re.findall(r'"text":"([A-Z]{3})"', body)
check('P3 落库是 v3 块文档', ver == '3', 'version=' + ver)
check('P3 库内块顺序与界面一致', ids == ['CCC', 'AAA', 'BBB'], str(ids))

# ---------- P4 同一块内拖动 = 原位,不写库 ----------
before_ts = db("SELECT updated_at FROM notes WHERE uuid='drag-1';").strip()
moved = order_in_ui()
if moved == ['CCC', 'AAA', 'BBB']:
    n = block_node('CCC')
    if n:
        x = n['x0'] + 60
        drag(x, n['y0'] + n['h'] * 3 // 4, x, n['y0'] + max(4, n['h'] // 6))
        same = order_in_ui() == ['CCC', 'AAA', 'BBB']
        after_ts = db("SELECT updated_at FROM notes WHERE uuid='drag-1';").strip()
        check('P4 原位拖动顺序不变且没有多写一次', same and after_ts == before_ts,
              'order=%s ts %s->%s' % (order_in_ui(), before_ts, after_ts))
    else:
        check('P4 原位拖动顺序不变且没有多写一次', False, '节点缺失')
else:
    check('P4 原位拖动顺序不变且没有多写一次', False, '前置顺序不对:' + str(moved))

shot('drag-final.png')
print()
print('=== 应用侧拖拽日志 ===')
print(sh('logcat', '-d', '-s', 'BlockDrag:D').strip())
fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
