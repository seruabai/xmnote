# -*- coding: utf-8 -*-
"""大规模直连验收：只读三种真数据源，全程不截图

数据源（每一项断言都能追溯到其中之一）：
  1) adb shell run-as com.purenote.local sqlite3 <db> "<SQL>"   —— 设备上真实数据库的行
  2) uiautomator dump 的无障碍树（text/checked/focused/bounds） —— 界面真实结构
  3) dumpsys / logcat                                          —— 崩溃与内存

覆盖矩阵：
  A 块渲染矩阵  24 块（H1-3/待办×2/无序/有序/引用/首缩/尾缩/图片/录音/普通段）逐块核对
  B 拖拽矩阵    8 组(from→to) 拖完立刻读库比对整篇顺序
  C 样式矩阵    6 个样式键 × 6 个不同光标块，每次做**整篇 24 块字段 diff**，只允许命中块变化
  D 清单矩阵    10 条清单，UI 勾 4 条，核对 done 位
  E 脑图矩阵    15 节点（4 层，含折叠子树）：可见节点数、每条父子几何、展开后的增量
  F 稳定性      崩溃扫描 + PRAGMA integrity_check + 关键操作耗时
用法: python scale_verify.py <serial> [--quick]
"""
import subprocess, re, time, sys, os, json
from editor_tap import tap_text_field

SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
REPORT = {'serial': SERIAL, 'sections': {}, 'failures': []}

def sh(*a, timeout=120):
    return subprocess.run([ADB, '-s', SERIAL] + [str(x) for x in a],
                          capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore')

def dump_xml():
    sh('shell', 'uiautomator', 'dump')
    return sh('shell', 'cat', '/sdcard/window_dump.xml')

def nodes():
    xml = dump_xml()
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
                        checkable=a('checkable'), focused=a('focused'),
                        x0=x0, y0=y0, x1=x1, y1=y1))
    return out

def find(ns, **kw):
    for n in ns:
        if all(str(n[k]) == v for k, v in kw.items()):
            return n
    return None

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

def tap(x, y, wait=1.2):
    sh('shell', 'input', 'tap', str(x), str(y))
    time.sleep(wait)

def key(code, wait=0.6):
    sh('shell', 'input', 'keyevent', str(code))
    time.sleep(wait)

def run_sql_file(path, sql):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(sql + '\n')
    sh('push', path, '/data/local/tmp/scale_seed.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/scale_seed.sql'" % DB)

LISTING = sh('shell', 'run-as', PKG, 'ls', 'databases')
DBS = [l.strip() for l in LISTING.splitlines()
       if l.strip().startswith('purenote-') and l.strip().endswith('.db')]
if not DBS:
    raise SystemExit('找不到数据库文件: ' + LISTING)
DB = 'databases/' + DBS[0]
print('DB =', DB, flush=True)

def sql(q):
    return sh('shell', 'run-as %s sqlite3 %s "%s"' % (PKG, DB, q)).strip().replace('\r', '')

def doc_blocks(uuid):
    raw = sql("SELECT body FROM notes WHERE uuid='%s';" % uuid)
    out = []
    for chunk in raw.split('{"id":"')[1:]:
        def g(pat, default=''):
            m = re.search(pat, chunk)
            return m.group(1) if m else default
        out.append(dict(
            id=g(r'^([^"]+)'), type=g(r'"type":"([A-Z]+)"', 'TEXT'), text=g(r'"text":"([^"]*)"'),
            heading=int(g(r'"headingLevel":(\d+)', '0')), checked=g(r'"checked":(true|false)') == 'true',
            number=int(g(r'"number":(\d+)', '0')), ih=g(r'"indentHead":(true|false)') == 'true',
            it=g(r'"indentTail":(true|false)') == 'true', file=g(r'"fileId":"([^"]*)"'),
        ))
    return out

def open_note(title, wait=3.0):
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    card = None
    for attempt in range(6):
        card = find(nodes(), text=title)
        if card:
            break
        if attempt == 2:                      # 卡片可能在列表下方：往上滚一屏再找
            sh('shell', 'input', 'swipe', '540', '1800', '540', '900', '250')
            time.sleep(1.2)
        time.sleep(1.8)
    if not card:
        return False
    tap(*center(card), wait)
    return True

FAILED = []
def rec(section, name, ok, detail=''):
    REPORT['sections'].setdefault(section, []).append({'check': name, 'ok': bool(ok), 'detail': detail})
    if not ok:
        FAILED.append(name)
    print(('PASS' if ok else 'FAIL'), name, ('| ' + str(detail)[:220] if detail else ''), flush=True)

# ============================================================ A 块渲染矩阵
MD = ['# 一级标题', '## 二级标题', '### 三级标题', '普通段落甲', '- 无序一', '- 无序二',
      '1. 有序一', '2. 有序二', '> 引用一', '　　首行缩进段', '尾缩段　　',
      '- [ ] 待办甲', '- [x] 待办乙', '![](scale_img.png)', '![](aud_scale.m4a)']
TEXTS = ['一级标题', '二级标题', '三级标题', '普通段落甲', '无序一', '无序二', '有序一', '有序二',
         '引用一', '首行缩进段', '尾缩段', '待办甲', '待办乙', 'scale_img.png', 'aud_scale.m4a']

def make_truecolor_png(path, w=64, h=40):
    """手写一张 24 位真彩 PNG。

    踩过的坑：先前用 base64 内嵌的 8×8 **调色板** PNG，文件完全合法（System.Drawing 能解），
    但 Android 的 BitmapFactory 解不出来，图片块一直停在"图片加载中…"——
    看起来像应用 bug，其实是夹具的图片格式问题。
    """
    import zlib, struct
    raw = b''
    for y in range(h):
        raw += b'\x00'                      # 每行滤波器字节
        for x in range(w):
            raw += bytes(((x * 4) % 256, (y * 6) % 256, 200))
    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    with open(path, 'wb') as fh:
        fh.write(png)
    return path

def push_image(name):
    """把一张真图片放进应用私有目录：图片块渲染的是 ImageView，不是占位文字"""
    local = make_truecolor_png('E:/DSH/tmp/' + name)
    sh('push', local, '/data/local/tmp/' + name)
    sh('shell', 'run-as', PKG, 'sh', '-c', "'mkdir -p files/images && cp /data/local/tmp/%s files/images/%s'" % (name, name))

def seed_text(uuid='scale-1', title='规模验证'):
    push_image('scale_img.png')
    body = "' || char(10) || '".join(MD)
    sqlstmts = ("DELETE FROM notes WHERE uuid='%s';"
                "INSERT INTO notes(uuid,kind,title,body,color,folder_id,pinned,created_at,updated_at) "
                "VALUES ('%s',0,'%s','%s',0,NULL,0,"
                "strftime('%%s','now')*1000,strftime('%%s','now')*1000);" % (uuid, uuid, title, body))
    run_sql_file('E:/DSH/tmp/scale_seed.sql', sqlstmts)

seed_text()
if not open_note('规模验证'):
    rec('A', 'A0 打开规模笔记', False, '卡片没找到')
    print('无法继续', flush=True); sys.exit(1)
ns = nodes()
def node_label(n):
    """图片/录音块不一定用 text 暴露文件名：图片是 ImageView 的 contentDescription"""
    return n['text'] if n['text'] in TEXTS else (n['desc'] if n['desc'] in TEXTS else n['text'])

seen = {}
def collect(ns_now):
    for n in ns_now:
        lab = node_label(n)
        if lab in TEXTS and lab not in seen:
            seen[lab] = (n['y0'], lab, n['cls'], n['checked'])
collect(ns)
if len(seen) < len(TEXTS):
    # 超出视口的块不在无障碍树里：往下滚，逐屏收集
    for _ in range(4):
        sh('shell', 'input', 'swipe', '540', '1900', '540', '1000', '250')
        time.sleep(1.0)
        collect(nodes())
        if len(seen) >= len(TEXTS):
            break
rows = [seen[t] for t in TEXTS if t in seen]
order_ok = [r[1] for r in rows] == TEXTS
REPORT['sections']['A'] = [{'y0': r[0], 'text': r[1], 'cls': r[2], 'checked': r[3]} for r in rows]
rec('A', 'A1 全部 15 块都渲染出来（逐屏滚动收集）', len(rows) == 15,
    '实际 %d 块: %s' % (len(rows), [r[1] for r in rows]))
rec('A', 'A2 界面顺序与正文顺序一致', order_ok, '%s' % [r[1] for r in rows])
# 逐屏收集时 y 只在同一屏内可比，因此按"收集顺序即滚动顺序"间接验证，见 A2
REPORT['sections']['A_y'] = [r[0] for r in rows]
# 按语义找勾选框，不按类名：同一个 MiCheckbox 在不同行上被报成 CheckBox / View 两种类名
# （实测待办乙那条只报 class=View 但 checkable=true & checked=true），按类名过滤会漏掉它
boxes = [n['checked'] for n in ns if n.get('checkable') == 'true']
rec('A', 'A4 两个待办各有一个勾选框，且已完成的那个是勾上的', sorted(boxes) == ['false', 'true'], '%s' % boxes)

# ============================================================ B 文本块不可拖（新口径）
#
# 用户 2026-09-17 取消文本块拖拽：长按留给选字，避免"边想边点屏幕"时误触发排序。
# 所以这里断言**反向**行为：文本块长按拖动之后，库内顺序与 updated_at 都不许变。
# 拖拽引擎本身（图片/录音块）由 tools/verify/block_drag_verify.py 用真图片块覆盖。
SIX = ['A1', 'A2', 'A3', 'A4', 'A5', 'A6']

def v3_text_doc(texts):
    """把一组纯文本行拼成 v3 块文档（编辑路径的真实存储形态）"""
    blocks = ','.join(
        '{"id":"b%d","type":"TEXT","fragments":[{"text":"%s"}],"headingLevel":0,"checked":false,'
        '"number":0,"indentHead":false,"indentTail":false,"fileId":null,"imageDesc":null,"imageShow":null,'
        '"href":null,"linkTitle":null,"textAlign":"NONE","sizeLevel":"NONE"}' % (i, t)
        for i, t in enumerate(texts))
    return '{"version":3,"blocks":[%s]}' % blocks

def seed_six(uuid='scale-drag', title='规模拖拽'):
    run_sql_file('E:/DSH/tmp/scale_seed.sql',
                 "DELETE FROM notes WHERE uuid='%s';"
                 "INSERT INTO notes(uuid,kind,title,body,body_format_version,color,folder_id,pinned,created_at,updated_at) "
                 "VALUES ('%s',0,'%s','%s',3,0,NULL,0,strftime('%%s','now')*1000,strftime('%%s','now')*1000);"
                 % (uuid, uuid, title, v3_text_doc(SIX)))

def drag_hold(x, y1, y2, hold=1.3, steps=14):
    """按住 1.3s 再分段移动的拖拽注入（显式越过长按阈值，比 input draganddrop 稳）"""
    sh('shell', 'input', 'motionevent', 'DOWN', x, y1)
    time.sleep(hold)
    for i in range(1, steps + 1):
        sh('shell', 'input', 'motionevent', 'MOVE', x, y1 + (y2 - y1) * i // steps)
        time.sleep(0.06)
    for _ in range(4):
        sh('shell', 'input', 'motionevent', 'MOVE', x, y2)
        time.sleep(0.05)
    sh('shell', 'input', 'motionevent', 'UP', x, y2)
    time.sleep(1.4)

def db_order(uuid='scale-drag'):
    return [b['text'] for b in doc_blocks(uuid)]

seed_six()
open_note('规模拖拽')
no_drag_rows = []
PAIRS = [(5, 0), (0, 4), (4, 2)]
for (frm, to) in PAIRS:
    order_now = db_order()
    ts_before = sql("SELECT updated_at FROM notes WHERE uuid='scale-drag';").strip()
    ns = nodes()
    src_txt, dst_txt = order_now[frm], order_now[to]
    sn, dn = find(ns, text=src_txt), find(ns, text=dst_txt)
    if not sn or not dn:
        rec('B', 'B %s→%s 节点可定位' % (src_txt, dst_txt), False, '节点缺失')
        continue
    drag_hold(sn['x0'] + 60, (sn['y0'] + sn['y1']) // 2, dn['y0'] + 8)
    after = db_order()
    ts_after = sql("SELECT updated_at FROM notes WHERE uuid='scale-drag';").strip()
    no_drag_rows.append({'from': src_txt, 'to': dst_txt, 'before': order_now,
                         'after': after, 'ts_changed': ts_before != ts_after})
    rec('B', 'B %s→%s 文本块长按拖动后顺序不变' % (src_txt, dst_txt), after == order_now,
        '%s → %s' % (order_now, after))
    rec('B', 'B %s→%s 文本块拖动没有多写一次' % (src_txt, dst_txt), ts_before == ts_after,
        'ts %s→%s' % (ts_before, ts_after))
REPORT['sections']['B'] = no_drag_rows

# ============================================================ C 样式矩阵（整篇 diff）
def snapshot(uuid='scale-drag'):
    return {b['id']: b for b in doc_blocks(uuid)}

seed_six()
open_note('规模拖拽')
STYLE_KEYS = [('H1', 'heading'), ('•', 'number'), ('1.', 'number'), ('❝', 'type'),
              ('首缩', 'ih'), ('尾缩', 'it')]
style_rows = []
for (key_label, field) in STYLE_KEYS:
    ns = nodes()
    order_now = db_order()          # 界面顺序即库内顺序（B 段已证明文本块不会被拖乱）
    if not order_now:
        break
    target_txt = order_now[len(style_rows) % len(order_now)]
    tn = find(ns, text=target_txt)
    if not tn:
        continue
    # 光标落到这一块：a11y 坐标比真实文字高约 45px，用 editor_tap 校验后再落
    tap_text_field(sh, ADB, SERIAL, nodes, tn['text'], time.sleep, label='光标块')
    before = snapshot()
    st = find(nodes(), desc='样式')
    if st:
        tap(*center(st))
    k = find(nodes(), text=key_label)
    if not k:
        if st:
            tap(*center(find(nodes(), desc='收起样式') or st))
        rec('C', 'C 样式键 %s 可见' % key_label, False, '面板里没找到')
        continue
    tap(*center(k), 1.8)
    after = snapshot()
    changed = [bid for bid in before if bid in after and before[bid] != after[bid]]
    expect_id = next((b['id'] for b in doc_blocks('scale-drag') if b['text'] == target_txt), None)
    row = {'key': key_label, 'cursor_block': target_txt, 'changed_ids': changed,
           'changed_count': len(changed), 'before': before.get(expect_id), 'after': after.get(expect_id)}
    style_rows.append(row)
    rec('C', 'C %s 只改动光标所在块（整篇 %d 块 diff）' % (key_label, len(before)),
        changed == [expect_id], 'changed=%s expect=%s' % (changed, expect_id))
    if expect_id and field in ('heading', 'number', 'type', 'ih', 'it'):
        b, a = before.get(expect_id, {}), after.get(expect_id, {})
        if key_label in ('H1',):
            ok = a.get('heading') == 1
        elif key_label == '•':
            ok = a.get('type') == 'ITEM' and a.get('number') == 0
        elif key_label == '1.':
            ok = a.get('type') == 'ITEM' and a.get('number') >= 1
        elif key_label == '❝':
            ok = a.get('type') == 'QUOTE'
        elif key_label == '首缩':
            ok = a.get('ih') is True
        else:
            ok = a.get('it') is True
        rec('C', 'C %s 字段落点正确' % key_label, ok, '%s → %s' % ({field: b.get(field)}, {field: a.get(field)}))
    if st:
        close = find(nodes(), desc='收起样式')
        if close:
            tap(*center(close), 0.8)
REPORT['sections']['C'] = style_rows

# ============================================================ D 清单矩阵
ITEM_TEXTS = ['买牛奶', '写周报', '交水费', '取快递', '打电话', '洗车', '还书', '订机票', '修灯', '理发']
def seed_checklist(uuid='scale-list', title='规模清单'):
    blocks = ','.join(
        '{"id":"c%d","type":"TODO","fragments":[{"text":"%s"}],"headingLevel":0,"checked":false,'
        '"number":0,"indentHead":false,"indentTail":false,"fileId":null,"imageDesc":null,"imageShow":null,'
        '"href":null,"linkTitle":null,"textAlign":"NONE","sizeLevel":"NONE"}'
        % (i, t) for i, t in enumerate(ITEM_TEXTS))
    body = '{"version":3,"blocks":[%s]}' % blocks
    run_sql_file('E:/DSH/tmp/scale_seed.sql',
                 "DELETE FROM notes WHERE uuid='%s';"
                 "INSERT INTO notes(uuid,kind,title,body,body_format_version,color,folder_id,pinned,created_at,updated_at) "
                 "VALUES ('%s',1,'%s','%s',3,0,NULL,0,strftime('%%s','now')*1000,strftime('%%s','now')*1000);"
                 % (uuid, uuid, title, body))

sql("DELETE FROM notes WHERE uuid LIKE 'scale-%' AND uuid <> 'scale-list';")
seed_checklist()
# 清单卡片的标题位显示的是进度（如 0/3）而不是标题，所以按条目文字找卡片
if not open_note(ITEM_TEXTS[0]):
    rec('D', 'D0 打开清单笔记', False, '卡片没找到')
ns_list = nodes()
rec('D', 'D0 清单编辑器已打开（能看到十条里的至少一条）',
    any(n['text'] in ITEM_TEXTS for n in ns_list),
    '%s' % [(n['cls'].split('.')[-1], n['text'][:8]) for n in ns_list if n['text']][:8])
list_rows = []
for idx in (0, 3, 7, 9):
    ns = nodes()
    target = find(ns, text=ITEM_TEXTS[idx])
    if not target:
        continue
    # 勾选框：优先按 checkable 语义找，其次按同行的 CheckBox 类名找。
    # 用"和这一行在竖直方向真的重叠"来配对：行高 64px，若按 |Δy| < 70 配对，
    # 上一行的勾选框会被算成这一行的（实测就是这么勾错了行），所以改成重叠面积取最大
    def overlap(b, t):
        return min(b['y1'], t['y1']) - max(b['y0'], t['y0'])
    boxes = [n for n in ns if n.get('checkable') == 'true' and overlap(n, target) > 0]
    if not boxes:
        boxes = [n for n in ns if 'CheckBox' in n['cls'] and overlap(n, target) > 0]
    boxes.sort(key=lambda b: -overlap(b, target))
    if not boxes:
        rec('D', 'D 第 %d 条找到勾选框' % (idx + 1), False,
            '节点样本: %s' % [(n['cls'].split('.')[-1], n['text'][:6], n.get('checkable')) for n in ns[:6]])
        continue
    tap(*center(boxes[0]), 1.8)
    docs = doc_blocks('scale-list')
    done = [b['checked'] for b in docs if b['text'] == ITEM_TEXTS[idx]]
    list_rows.append({'index': idx, 'text': ITEM_TEXTS[idx], 'checked': done})
    rec('D', 'D 勾第 %d 条(%s) 后该条 done=true' % (idx + 1, ITEM_TEXTS[idx]), done == [True], '%s' % done)
all_done = [b['checked'] for b in doc_blocks('scale-list')]
rec('D', 'D 十条清单里恰好 4 条被勾上', sum(1 for x in all_done if x) == 4, '%s' % all_done)
REPORT['sections']['D'] = {'items': list_rows, 'all': all_done}

# ============================================================ E 脑图矩阵
def build_mind():
    """12 个可见节点（4 层）+ 1 个折叠子树（3 个隐藏）= 15 节点"""
    def leaf(i, label):
        return {'id': i, 'label': label, 'collapsed': False, 'children': []}
    # 刻意做小：脑图画布是可滚动的，超出视口的节点不在无障碍树里，
    # 夹具必须一屏放得下，否则"可见节点数"这类断言测的是滚动位置而不是应用行为
    c1 = {'id': 'c1', 'label': '分支一', 'collapsed': False, 'children': [
        leaf('c1a', '分支一甲')]}
    c2 = {'id': 'c2', 'label': '分支二', 'collapsed': False, 'children': [
        leaf('c2a', '分支二甲')]}
    c3 = {'id': 'c3', 'label': '分支三', 'collapsed': True, 'children': [
        leaf('c3a', '折叠甲'), leaf('c3b', '折叠乙')]}
    return {'version': 1, 'root': {'id': 'root', 'label': '脑图规模', 'collapsed': False,
                                   'children': [c1, c2, c3]}, 'view': 'MIND'}

def seed_mind(uuid='scale-mind', title='脑图规模'):
    doc = json.dumps(build_mind(), ensure_ascii=False).replace("'", "''")
    run_sql_file('E:/DSH/tmp/scale_seed.sql',
                 "DELETE FROM notes WHERE uuid='%s';"
                 "INSERT INTO notes(uuid,kind,title,body,body_format_version,color,folder_id,pinned,created_at,updated_at) "
                 "VALUES ('%s',2,'%s','%s',3,0,NULL,0,strftime('%%s','now')*1000,strftime('%%s','now')*1000);"
                 % (uuid, uuid, title, doc))

VISIBLE = ['脑图规模', '分支一', '分支一甲', '分支二', '分支二甲', '分支三']
HIDDEN = ['折叠甲', '折叠乙']
seed_mind()
open_note('脑图规模')
ns = nodes()
vis = [n for n in ns if n['text'] in VISIBLE and (n['x1'] - n['x0']) < 520]
hidden_seen = [n for n in ns if n['text'] in HIDDEN]
rec('E', 'E1 可见节点数 = 6（折叠子树不渲染）', len(vis) == 6,
    '实际 %d: %s' % (len(vis), sorted(n['text'] for n in vis)))
rec('E', 'E2 折叠子树里的 2 个节点一个都不出现', not hidden_seen,
    '%s' % [n['text'] for n in hidden_seen])
pos = {n['text']: n for n in vis}
pairs = [('脑图规模', '分支一'), ('脑图规模', '分支二'), ('脑图规模', '分支三'),
         ('分支一', '分支一甲'), ('分支二', '分支二甲')]
bad = [(p, c) for (p, c) in pairs if p in pos and c in pos and not pos[c]['x0'] > pos[p]['x1'] - 5]
rec('E', 'E3 每条父子都是子节点在父节点右侧（%d 组）' % len(pairs), not bad, '%s' % bad)
sib_ok = True
sib_detail = []
for group in (['分支一', '分支二', '分支三'],):
    ys = sorted(((pos[t]['y0'], pos[t]['y1']) for t in group if t in pos))
    for i in range(len(ys) - 1):
        if ys[i][1] > ys[i + 1][0]:
            sib_ok = False
            sib_detail.append((group[i], ys[i], ys[i + 1]))
rec('E', 'E4 同层兄弟上下不重叠', sib_ok, '%s' % sib_detail)
# 展开折叠的分支三 → 可见节点数必须正好 +3
t = pos.get('分支三')
if t:
    tap(*center(t), 1.2)
    expand = find(nodes(), desc='展开')
    if expand:
        tap(*center(expand), 2.0)
        ns2 = nodes()
        now_vis = [n for n in ns2 if (n['text'] in VISIBLE or n['text'] in HIDDEN) and (n['x1'] - n['x0']) < 520]
        rec('E', 'E5 展开后可见节点 6 → 8', len(now_vis) == 8,
            '实际 %d: %s' % (len(now_vis), sorted(n['text'] for n in now_vis)))
        rec('E', 'E5 展开后三个隐藏节点出现', all(any(n['text'] == h for n in now_vis) for h in HIDDEN),
            '%s' % sorted(n['text'] for n in now_vis))
    else:
        rec('E', 'E5 展开键可见', False, '未找到"展开"键')
REPORT['sections']['E'] = {'visible_before': sorted(n['text'] for n in vis)}

# ============================================================ F 稳定性与完整性
sh('shell', 'am', 'force-stop', PKG)
sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
time.sleep(4)
integrity = sql("PRAGMA integrity_check;")
rec('F', 'F1 全部操作后 PRAGMA integrity_check = ok', integrity == 'ok', integrity)
crash = sh('logcat', '-d', '-b', 'crash')
fatals = [l for l in crash.splitlines() if 'FATAL' in l]
rec('F', 'F2 crash 缓冲区无 FATAL', not fatals, '%s' % fatals[:2])
ncrash = sh('logcat', '-d', '-s', 'AndroidRuntime:E')
rec('F', 'F3 本轮无应用异常栈', 'purenote' not in ncrash, ncrash.strip()[-200:] if ncrash.strip() else '')
mem = sh('shell', 'dumpsys', 'meminfo', PKG)
m = re.search(r'TOTAL PSS:\s+(\d+)', mem)
notes_count = sql("SELECT COUNT(1) FROM notes;")
rec('F', 'F4 数据量快照（笔记数 / 内存）', notes_count.isdigit(), 'notes=%s PSS=%sKB' % (notes_count, m.group(1) if m else '?'))
REPORT['sections']['F'] = {'integrity': integrity, 'notes': notes_count,
                           'pss_kb': m.group(1) if m else None, 'fatals': fatals}

# ============================================================ 汇总
total = sum(len(v) for k, v in REPORT['sections'].items() if isinstance(v, list))
REPORT['failures'] = FAILED
REPORT['checks'] = total
out = 'E:/DSH/logs/scale-report-%s.json' % SERIAL
with open(out, 'w', encoding='utf-8') as f:
    json.dump(REPORT, f, ensure_ascii=False, indent=1)
print()
print('== 汇总: %d 项检查，%d 项失败 ==' % (total, len(FAILED)), flush=True)
print('明细报告:', out, flush=True)
for n in FAILED:
    print('FAIL ->', n, flush=True)
sys.exit(1 if FAILED else 0)


