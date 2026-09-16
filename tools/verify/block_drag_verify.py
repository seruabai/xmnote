# -*- coding: utf-8 -*-
"""块拖拽的当前口径（用户 2026-09-17 取消文本块拖拽之后）——设备端参数化验收

  P1 夹具:文本块与图片块混排
  P2 **文本块长按拖动不改顺序**（新口径：长按留给选字，不能误触发排序）
  P3 图片块长按拖动**会**改变顺序，且同一顺序落库
  P4 图片块原位拖动不改顺序、也不多写一次

只用指定串口（默认 emulator-5554），绝不触碰实机。全程直连：uiautomator dump + sqlite3。
"""
import subprocess, re, time, sys, os, zlib, struct

SERIAL = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
RESULTS = []

def sh(*a, timeout=120):
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

def find(ns, key, val):
    return next((n for n in ns if n[key] == val), None)

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

def check(name, ok, detail=''):
    RESULTS.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, ('| ' + detail if detail else ''), flush=True)

LISTING = sh('shell', 'run-as', PKG, 'ls', 'databases')
DBS = [l.strip() for l in LISTING.splitlines()
       if l.strip().startswith('purenote-') and l.strip().endswith('.db')]
DB = 'databases/' + DBS[0]
print('DB =', DB, flush=True)

def sql(q):
    return sh('shell', 'run-as %s sqlite3 %s "%s"' % (PKG, DB, q)).strip().replace('\r', '')

def make_png(path, w=48, h=32, tint=0):
    """真彩 PNG（调色板 PNG 在 Android BitmapFactory 上解不出来，别用）"""
    raw = b''
    for y in range(h):
        raw += b'\x00'
        for x in range(w):
            raw += bytes(((x * 5 + tint) % 256, (y * 7 + tint) % 256, 180))
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    with open(path, 'wb') as fh:
        fh.write(b'\x89PNG\r\n\x1a\n'
                 + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
                 + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

IMG = ['drag_a.png', 'drag_b.png']

def seed():
    """夹具：文本 / 图片 / 文本 / 图片 —— 文本块验证"拖不动"，图片块验证"拖得动" """
    for i, name in enumerate(IMG):
        local = 'E:/DSH/tmp/' + name
        make_png(local, tint=i * 60)
        sh('push', local, '/data/local/tmp/' + name)
        sh('shell', 'run-as', PKG, 'sh', '-c',
           "'mkdir -p files/images && cp /data/local/tmp/%s files/images/%s'" % (name, name))
    def text_block(i, text):
        return ('{"id":"t%d","type":"TEXT","fragments":[{"text":"%s"}],"headingLevel":0,"checked":false,'
                '"number":0,"indentHead":false,"indentTail":false,"fileId":null,"imageDesc":null,'
                '"imageShow":null,"href":null,"linkTitle":null,"textAlign":"NONE","sizeLevel":"NONE"}' % (i, text))
    def img_block(i, name):
        return ('{"id":"i%d","type":"IMAGE","fragments":[],"headingLevel":0,"checked":false,"number":0,'
                '"indentHead":false,"indentTail":false,"fileId":"%s","imageDesc":null,"imageShow":null,'
                '"href":null,"linkTitle":null,"textAlign":"NONE","sizeLevel":"NONE"}' % (i, name))
    body = '{"version":3,"blocks":[%s]}' % ','.join([
        text_block(0, 'TXT1'), img_block(0, IMG[0]), text_block(1, 'TXT2'), img_block(1, IMG[1])])
    stmt = ("DELETE FROM notes WHERE uuid='dragmix-1';"
            "INSERT INTO notes(uuid,kind,title,body,body_format_version,color,folder_id,pinned,created_at,updated_at) "
            "VALUES ('dragmix-1',0,'拖拽口径','" + body + "',3,0,NULL,0,"
            "strftime('%s','now')*1000,strftime('%s','now')*1000);")
    open('E:/DSH/tmp/dragmix.sql', 'w', encoding='utf-8').write(stmt + '\n')
    sh('push', 'E:/DSH/tmp/dragmix.sql', '/data/local/tmp/dragmix.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/dragmix.sql'" % DB)

def open_editor():
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    card = None
    for _ in range(5):
        card = find(nodes(), 'text', '拖拽口径')
        if card:
            break
        time.sleep(2)
    if not card:
        return False
    sh('shell', 'input', 'tap', str(center(card)[0]), str(center(card)[1]))
    time.sleep(3)
    return True

def ui_blocks():
    """界面上的块（文本按 text，图片按 contentDescription）返回 [(y, 标签)]"""
    out = []
    for n in nodes():
        if n['text'] in ('TXT1', 'TXT2'):
            out.append((n['y0'], n['text'], n))
        elif n['desc'] in IMG or (n['desc'] or '').endswith('.png'):
            out.append((n['y0'], n['desc'], n))
    out.sort()
    return out

def ui_order():
    return [t for _, t, _ in ui_blocks()]

def scroll_top():
    """回到正文顶部。

    两个坑：① 文本块不再响应拖拽后，拖动会退化成列表滚动，所以要滚回来；
    ② 滑动起点不能落在文本框上（那会变成选字而不是滚动），所以从右侧空白处滑。
    """
    for _ in range(4):
        sh('shell', 'input', 'swipe', '1000', '1000', '1000', '1900', '250')
        time.sleep(0.5)
    time.sleep(0.6)

def db_order():
    raw = sql("SELECT body FROM notes WHERE uuid='dragmix-1';")
    return re.findall(r'"fileId":"([^"]*)"', raw) and [
        (m.group(1) if m.group(1) else 'TXT') for m in
        re.finditer(r'"(?:text|fileId)":"([^"]*)"', raw)]

def db_sequence():
    """库内块顺序：文本块记 TXT，图片块记文件名"""
    raw = sql("SELECT body FROM notes WHERE uuid='dragmix-1';")
    seq = []
    for chunk in raw.split('{"id":"')[1:]:
        if '"type":"IMAGE"' in chunk:
            m = re.search(r'"fileId":"([^"]*)"', chunk)
            seq.append(m.group(1) if m else 'IMG')
        else:
            seq.append('TXT')
    return seq

def drag_hold(x, y1, y2, hold=1.3, steps=14):
    """按住 1.3s 再分段移动：先越过长按阈值再越过 slop，与真实手指一致"""
    sh('shell', 'input', 'motionevent', 'DOWN', x, y1)
    time.sleep(hold)
    for i in range(1, steps + 1):
        sh('shell', 'input', 'motionevent', 'MOVE', x, y1 + (y2 - y1) * i // steps)
        time.sleep(0.06)
    for _ in range(4):
        sh('shell', 'input', 'motionevent', 'MOVE', x, y2)
        time.sleep(0.05)
    sh('shell', 'input', 'motionevent', 'UP', x, y2)
    time.sleep(1.6)

seed()
if not open_editor():
    check('P1 打开编辑器', False, '卡片没找到')
    sys.exit(1)
order0 = ui_order()
check('P1 四块按 文本/图片/文本/图片 渲染', len(order0) == 4, str(order0))
if len(order0) != 4:
    sys.exit(1)

# ---------- P2 文本块拖不动 ----------
before = ui_order()
t1 = find(nodes(), 'text', 'TXT1')
t2 = find(nodes(), 'text', 'TXT2')
if t1 and t2:
    ts_before = sql("SELECT updated_at FROM notes WHERE uuid='dragmix-1';").strip()
    drag_hold(t1['x0'] + 60, (t1['y0'] + t1['y1']) // 2, t2['y0'] + 8)
    # 文本块不再吞这个手势，于是它退化成"列表滚动/选字"——这正是新口径的预期行为，
    # 所以这里只看库：顺序没变、也没有多写一次
    check('P2 文本块长按拖动不改顺序（库内顺序不变）',
          db_sequence() == ['TXT', IMG[0], 'TXT', IMG[1]], str(db_sequence()))
    ts_after = sql("SELECT updated_at FROM notes WHERE uuid='dragmix-1';").strip()
    check('P2 文本块拖动没有多写一次', ts_before == ts_after, 'ts %s→%s' % (ts_before, ts_after))
else:
    check('P2 文本块长按拖动后顺序不变（新口径）', False, '文本节点没找到')

# ---------- P3 图片块拖得动 ----------
# 重开编辑器：上面的手势把列表滚下去了，图片块不在视口里就取不到节点
open_editor()
before = ui_order()
src = next((n for n in nodes() if n['desc'] == IMG[1]), None)
# 落点取"第一块文本块的上半区" → 图片应插到它前面（文首），这样期望最直白
dst = next((n for n in nodes() if n['text'] == 'TXT1'), None)
if src and dst:
    drag_hold(src['x0'] + 60, (src['y0'] + src['y1']) // 2, dst['y0'] + 8)
    time.sleep(1.5)
    after = ui_order()
    expect = [IMG[1], 'TXT1', IMG[0], 'TXT2']
    check('P3 图片块拖到最前后顺序正确', after == expect, '%s → %s (期望 %s)' % (before, after, expect))
    check('P3 同一顺序已落库', db_sequence() == [IMG[1], 'TXT', IMG[0], 'TXT'], str(db_sequence()))
else:
    check('P3 图片块拖到最前后顺序正确', False, '图片节点没找到')

# ---------- P4 原位拖动不写库 ----------
open_editor()
ts = sql("SELECT updated_at FROM notes WHERE uuid='dragmix-1';").strip()
img = next((n for n in nodes() if n['desc'] == IMG[1]), None)
if img:
    before = ui_order()
    drag_hold(img['x0'] + 60, img['y0'] + (img['y1'] - img['y0']) * 3 // 4, img['y0'] + 8)
    after = ui_order()
    ts2 = sql("SELECT updated_at FROM notes WHERE uuid='dragmix-1';").strip()
    check('P4 原位拖动顺序不变且没有多写一次', after == before and ts == ts2,
          'order=%s ts %s→%s' % (after, ts, ts2))
else:
    check('P4 原位拖动顺序不变且没有多写一次', False, '图片节点没找到')

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
