# -*- coding: utf-8 -*-
"""待办页两条反馈的验收（用户 2026-09-17）

  T1 左滑露删除键时，勾选栏直接消失（不再被卡片拖着走）
  T2 长按进多选：退出多选的 X 在"待办"大字**上方**一行；右上角由设置齿轮换成**全选**
  T3 退出多选后：全选消失、设置齿轮回来

全程直连：run-as sqlite3 造夹具 / uiautomator dump 读界面，不用截图。
"""
import subprocess, re, sys, time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
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
        out.append(dict(text=a('text'), desc=a('desc'), cls=a('class'), checkable=a('checkable'),
                        x0=x0, y0=y0, x1=x1, y1=y1))
    return out

def find(ns, key, val):
    return next((n for n in ns if n[key] == val), None)

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

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

TITLES = ['滑动手感验证', '第二条待办']
def seed():
    now = int(time.time() * 1000)
    stmt = "DELETE FROM todos WHERE title IN ('%s');" % "','".join(TITLES)
    for i, t in enumerate(TITLES):
        stmt += ("INSERT INTO todos(uuid,parent_id,title,done,done_at,due_at,remind_at,sort_index,created_at,updated_at) "
                 "VALUES ('fb-todo-%d',NULL,'%s',0,NULL,NULL,NULL,%d,%d,%d);" % (i, t, i, now, now))
    open('E:/DSH/tmp/todo_seed.sql', 'w', encoding='utf-8').write(stmt + '\n')
    sh('push', 'E:/DSH/tmp/todo_seed.sql', '/data/local/tmp/todo_seed.sql')
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 %s < /data/local/tmp/todo_seed.sql'" % DB)

def open_todo_tab():
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    tab = None
    for _ in range(5):
        ns = nodes()
        tab = find(ns, 'text', '待办')
        if tab:
            break
        time.sleep(1.5)
    if not tab:
        return False
    sh('shell', 'input', 'tap', str(center(tab)[0]), str(center(tab)[1]))
    time.sleep(2.5)
    return find(nodes(), 'text', TITLES[0]) is not None

seed()
check('T0 打开待办页并看到夹具', open_todo_tab(), '没看到夹具待办')
ns = nodes()
row = find(ns, 'text', TITLES[0])
if row is None:
    sys.exit(1)
card_boxes = [n for n in ns if n.get('checkable') == 'true' and abs((n['y0'] + n['y1']) // 2 - (row['y0'] + row['y1']) // 2) < 90]
check('T1 左滑前卡片上有勾选框', bool(card_boxes), '可勾选节点 %d 个' % len(card_boxes))

# ---- T1 左滑：勾选栏直接消失 ----
cy = (row['y0'] + row['y1']) // 2
sh('shell', 'input', 'swipe', '900', str(cy), '600', str(cy), '400')
time.sleep(1.2)
ns2 = nodes()
row2 = find(ns2, 'text', TITLES[0])
boxes2 = [n for n in ns2 if n.get('checkable') == 'true' and row2 and abs((n['y0'] + n['y1']) // 2 - (row2['y0'] + row2['y1']) // 2) < 90]
check('T1 左滑后该卡片的勾选栏消失', row2 is not None and not boxes2,
      '卡片仍在=%s 同行勾选框=%d' % (row2 is not None, len(boxes2)))
dele = find(ns2, 'desc', '删除')
check('T1 左滑后露出删除键', dele is not None, str(dele and (dele['x0'], dele['y0'])))

# 收回卡片，避免影响后续
sh('shell', 'input', 'tap', str(center(row2 or row)[0]), str(cy))
time.sleep(1.2)

# ---- T2 长按进多选 ----
ns = nodes()
row = find(ns, 'text', TITLES[0])
# 长按进多选在这台机器上偶发不中（机器忙时输入被吞）：同一次会话里复现过一次 3/7、
# 紧接着重跑就 10/10。所以点两次 + 拉长等待，别让环境噪声把发版门判红。
for _attempt in range(2):
    sh('shell', 'input', 'swipe', str((row['x0'] + row['x1']) // 2), str((row['y0'] + row['y1']) // 2),
       str((row['x0'] + row['x1']) // 2 + 6), str((row['y0'] + row['y1']) // 2), '900')
    time.sleep(2.2)
    ns3 = nodes()
    exit_btn = find(ns3, 'desc', '退出多选')
    select_all = find(ns3, 'desc', '全选') or find(ns3, 'desc', '取消全选')
    if exit_btn is not None or select_all is not None:
        break
    row = find(nodes(), 'text', TITLES[0]) or row
title = find(ns3, 'text', '待办')
gear = find(ns3, 'desc', '设置')
check('T2 长按进入多选（出现退出键与全选）', exit_btn is not None and select_all is not None,
      '退出=%s 全选=%s' % (exit_btn is not None, select_all is not None))
if exit_btn and title:
    check('T2 退出键在"待办"大字上方', exit_btn['y1'] <= title['y0'],
          '退出键 y=%d..%d，大字 y=%d' % (exit_btn['y0'], exit_btn['y1'], title['y0']))
if select_all:
    screen_w = max(n['x1'] for n in ns3)
    check('T2 全选在右上角', (select_all['x0'] + select_all['x1']) / 2 > screen_w * 0.6,
          '全选 x=%d..%d 屏宽=%d' % (select_all['x0'], select_all['x1'], screen_w))
check('T2 多选时设置齿轮被全选替换', gear is None, '齿轮=%s' % gear)

# ---- T3 退出多选 ----
if exit_btn:
    sh('shell', 'input', 'tap', str(center(exit_btn)[0]), str(center(exit_btn)[1]))
    time.sleep(1.8)
    ns4 = nodes()
    gear_back = find(ns4, 'desc', '设置')
    still_sel = find(ns4, 'desc', '全选') or find(ns4, 'desc', '取消全选')
    exit_gone = find(ns4, 'desc', '退出多选')
    check('T3 退出多选后设置齿轮回来', gear_back is not None, str(gear_back and (gear_back['x0'], gear_back['y0'])))
    check('T3 退出多选后全选与退出键消失', still_sel is None and exit_gone is None,
          '全选=%s 退出=%s' % (still_sel is not None, exit_gone is not None))
else:
    check('T3 退出多选后设置齿轮回来', False, '没进多选')

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
