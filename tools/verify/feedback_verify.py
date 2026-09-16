# -*- coding: utf-8 -*-
"""本轮四条反馈的验收（用户 2026-09-17，小米 12S 反馈）

  F1 保存状态悬浮：点勾选框后内容不许位移；状态文字若出现，必须在右下角且不占正文流
  F2 清单里回车 = 新开一条勾选栏（而不是同一条目内两行）
  F3 清单笔记也能呼出五键工具栏；"勾选"键 = 再加一条勾选栏
  F4 宫格首页只滚一页：在右半屏滑一次，左半屏的卡片必须一起动

全程直连：run-as sqlite3 / uiautomator dump，不用截图。串口取自第一个参数。
"""
import subprocess, re, sys, time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
RESULTS = []

def sh(*a, timeout=120):
    return subprocess.run([ADB, '-s', SERIAL] + [str(x) for x in a],
                          capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore')

def dump_nodes():
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
                        checkable=a('checkable'), x0=x0, y0=y0, x1=x1, y1=y1))
    return out

def find(ns, key, val):
    return next((n for n in ns if n[key] == val), None)

def check(name, ok, detail=''):
    RESULTS.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, ('| ' + str(detail)[:220] if detail else ''), flush=True)

LISTING = sh('shell', 'run-as', PKG, 'ls', 'databases')
DBS = [l.strip() for l in LISTING.splitlines()
       if l.strip().startswith('purenote-') and l.strip().endswith('.db')]
DB = 'databases/' + DBS[0]
print('DB =', DB, flush=True)

def sql(q):
    return sh('shell', 'run-as %s sqlite3 %s "%s"' % (PKG, DB, q)).strip().replace('\r', '')

ITEM_TEXTS = ['买牛奶', '交水费', '取快递']
def seed_checklist(uuid='fb-list', title='反馈清单'):
    blocks = ','.join(
        '{"id":"c%d","type":"TODO","fragments":[{"text":"%s"}],"headingLevel":0,"checked":false,'
        '"number":0,"indentHead":false,"indentTail":false,"fileId":null,"imageDesc":null,"imageShow":null,'
        '"href":null,"linkTitle":null,"textAlign":"NONE","sizeLevel":"NONE"}' % (i, t)
        for i, t in enumerate(ITEM_TEXTS))
    body = '{"version":3,"blocks":[%s]}' % blocks
    stmt = ("DELETE FROM notes WHERE uuid='%s';"
            "INSERT INTO notes(uuid,kind,title,body,body_format_version,color,folder_id,pinned,created_at,updated_at) "
            "VALUES ('%s',1,'%s','%s',3,0,NULL,0,strftime('%%s','now')*1000,strftime('%%s','now')*1000);"
            % (uuid, uuid, title, body))
    open('E:/DSH/tmp/fb_seed.sql', 'w', encoding='utf-8').write(stmt + '\n')
    sh('push', 'E:/DSH/tmp/fb_seed.sql', '/data/local/tmp/fb_seed.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/fb_seed.sql'" % DB)

def items_in_db(uuid='fb-list'):
    raw = sql("SELECT body FROM notes WHERE uuid='%s';" % uuid)
    return re.findall(r'"text":"([^"]*)"', raw)

def open_app():
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)

def tap_card(label):
    for _ in range(5):
        c = find(dump_nodes(), 'text', label)
        if c:
            sh('shell', 'input', 'tap', str((c['x0'] + c['x1']) // 2), str((c['y0'] + c['y1']) // 2))
            time.sleep(2.5)
            return True
        time.sleep(1.5)
    return False

def item_nodes(ns=None):
    ns = ns if ns is not None else dump_nodes()
    out = []
    for n in ns:
        if n['cls'].endswith('EditText') and n['text'] in ITEM_TEXTS + ['', '1', '12', '123']:
            out.append(n)
    return out

# =============================== F1 + F2 + F3（同一条清单笔记）
seed_checklist()
open_app()
# 清单卡片标题位显示的是进度（如 0/3），所以按条目文字点开
check('F0 打开清单笔记', tap_card(ITEM_TEXTS[0]), '卡片没找到')
ns = dump_nodes()
row = find(ns, 'text', ITEM_TEXTS[0])
check('F0 清单条目渲染', row is not None, '%s' % [(n['cls'].split('.')[-1], n['text'][:8]) for n in ns if n['text']][:8])
if row is None:
    sys.exit(1)

# F3 工具栏：点进条目 → 五键应出现
sh('shell', 'input', 'tap', str(row['x0'] + 60), str((row['y0'] + row['y1']) // 2))
time.sleep(1.5)
ns = dump_nodes()
descs = {n['desc'] for n in ns if n['desc']}
keys = ['录音', '图片', '手写', '勾选', '样式']
check('F3 清单笔记里五键工具栏可见', all(k in descs for k in keys), '缺: %s' % [k for k in keys if k not in descs])

# F1 保存状态悬浮：点勾选框，立刻 dump，比较条目位置
before_y = {t: find(ns, 'text', t)['y0'] for t in ITEM_TEXTS if find(ns, 'text', t)}
box = [n for n in ns if n.get('checkable') == 'true']
if not box:
    box = [n for n in ns if n['cls'].endswith('CheckBox')]
if box:
    sh('shell', 'input', 'tap', str((box[0]['x0'] + box[0]['x1']) // 2), str((box[0]['y0'] + box[0]['y1']) // 2))
    ns2 = dump_nodes()          # 立刻取，抢在"待保存/保存中"还在的窗口里
    status = next((n for n in ns2 if n['text'] in ('待保存', '保存中…')), None)
    after_y = {t: find(ns2, 'text', t)['y0'] for t in before_y if find(ns2, 'text', t)}
    check('F1 点勾选框后条目位置不变（内容不再被顶动）', before_y == after_y,
          '前 %s 后 %s' % (before_y, after_y))
    if status:
        screen_w = max(n['x1'] for n in ns2)
        screen_h = max(n['y1'] for n in ns2)
        bottom_right = status['x1'] > screen_w * 0.6 and status['y0'] > screen_h * 0.45
        check('F1 状态文字悬浮在右下角', bottom_right,
              'status=%s 屏幕=%dx%d' % (status, screen_w, screen_h))
    else:
        check('F1 状态文字悬浮在右下角', True, '（本次 dump 没抢到状态窗口，仅核对不位移）')
else:
    check('F1 点勾选框后条目位置不变（内容不再被顶动）', False, '没找到勾选框节点')

# F2 回车 = 新开一条勾选栏
row = find(dump_nodes(), 'text', ITEM_TEXTS[0])
if row:
    sh('shell', 'input', 'tap', str(row['x0'] + 60), str((row['y0'] + row['y1']) // 2))
    time.sleep(1.2)
    sh('shell', 'input', 'keyevent', '123')     # MOVE_END
    sh('shell', 'input', 'keyevent', '66')      # ENTER
    time.sleep(1.2)
    sh('shell', 'input', 'text', '9')
    time.sleep(2.2)
    got = items_in_db()
    # 光标在"买牛奶"行尾 → 回车后应变成两条：买牛奶 / 9（新条目未勾选、光标跟过去）
    check('F2 回车后新增一条勾选栏（不是同一栏里两行）',
          got == ['买牛奶', '9', '交水费', '取快递'], str(got))
    ns3 = dump_nodes()
    boxes = [n for n in ns3 if n.get('checkable') == 'true']
    check('F2 界面上勾选框数量随之增加', len(boxes) >= 4, '可勾选节点 %d 个' % len(boxes))
else:
    check('F2 回车后新增一条勾选栏（不是同一栏里两行）', False, '条目节点没找到')

# 勾选键再加一条
ns_key = dump_nodes()
key = find(ns_key, 'desc', '勾选')
if key:
    def ui_rows():
        return [n for n in dump_nodes() if n['cls'].endswith('EditText') and n['text'] != '反馈清单']
    before_rows = ui_rows()
    sh('shell', 'input', 'tap', str((key['x0'] + key['x1']) // 2), str((key['y0'] + key['y1']) // 2))
    time.sleep(2.0)
    after_rows = ui_rows()
    # 断言看界面：新加的是空勾选栏，"不存空条目"是既有保存规则，库里不增属正常
    check('F3 清单里"勾选"键 = 再加一条勾选栏',
          len(after_rows) == len(before_rows) + 1,
          '界面条目 %d → %d' % (len(before_rows), len(after_rows)))
    check('F3 新加的是空勾选栏（未勾选、无文字）',
          len(after_rows) > 0 and after_rows[-1]['text'] == '',
          str([r['text'] for r in after_rows]))
else:
    check('F3 清单里"勾选"键 = 再加一条勾选栏', False, '勾选键没找到')

# =============================== F4 宫格单页滚动
sh('shell', 'input', 'keyevent', '4')
time.sleep(1.5)
open_app()
ns = dump_nodes()
# 只要真正的笔记卡片：页头（大标题/搜索框/分类芯片）都在 y<800，别把它们当成卡片
cards = [n for n in ns if n['text'] and n['cls'].endswith('TextView') and n['y0'] > 700]
left = [c for c in cards if (c['x0'] + c['x1']) // 2 < 540]
right = [c for c in cards if (c['x0'] + c['x1']) // 2 > 540]
check('F4 宫格首页有左右两列卡片', bool(left) and bool(right),
      '左 %d 张 右 %d 张' % (len(left), len(right)))
if left and right:
    # 选视口中间偏下的卡片：滑动后它们仍在 y>820 的窗口里，能直接比位移
    def pick(cands):
        mid = [c for c in cands if 950 <= c['y0'] <= 1500]
        return (mid or cands)[0]
    lcard, rcard = pick(left), pick(right)
    ltext, rtext, ly, ry = lcard['text'], rcard['text'], lcard['y0'], rcard['y0']
    # 小幅度滑动（300px）：两张卡片都应留在视口里，这样能直接比位移量
    # 慢速滑动，避免变成 fling 把卡片甩出视口（那样就比不到位移了）
    sh('shell', 'input', 'swipe', '810', '1900', '810', '1600', '1000')   # 只在右半屏滑
    time.sleep(1.5)
    ns = dump_nodes()
    # 回来后**不加窗口过滤**：卡片被滚到 y<700 也是同一张卡，不能因为出了过滤窗就当它消失
    l2 = find(ns, 'text', ltext)
    r2 = find(ns, 'text', rtext)
    dl = (l2['y0'] - ly) if l2 else None
    dr = (r2['y0'] - ry) if r2 else None
    moved_left = dl is not None and dl < -100
    moved_right = dr is not None and dr < -100
    check('F4 只滑右半屏，左半屏也跟着滚（单页滚动）', moved_left and moved_right,
          '%s: %s→%s (Δ%s) | %s: %s→%s (Δ%s)' % (
              ltext, ly, l2 and l2['y0'], dl, rtext, ry, r2 and r2['y0'], dr))

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
