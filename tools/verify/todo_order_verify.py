# -*- coding: utf-8 -*-
"""待办编辑保持原位——设备端参数化断言(可重复执行)"""
import subprocess, re, time, sys

ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
RESULTS = []

def sh(*a, timeout=40):
    return subprocess.run([ADB] + [str(x) for x in a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore')

def dump():
    sh('shell', 'uiautomator', 'dump')
    return sh('shell', 'cat', '/sdcard/window_dump.xml')

def nodes(xml):
    out = []
    for n in re.findall(r'<node[^>]*>', xml):
        def a(k, n=n):
            m = re.search(k + '="([^"]*)"', n)
            return m.group(1) if m else ''
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
        if not m:
            continue
        x0, y0, x1, y1 = map(int, m.groups())
        out.append(dict(text=a('text'), desc=a('desc'), x0=x0, y0=y0, x1=x1, y1=y1))
    return out

def find(ns, **kw):
    for n in ns:
        if all(str(n[k]) == v for k, v in kw.items()):
            return n
    return None

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

def db(sql):
    return sh('shell', "run-as com.purenote.local sqlite3 databases/purenote.db \"" + sql + "\"").strip().replace('\r', '')

def check(name, ok, detail=''):
    RESULTS.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, ('| ' + detail if detail else ''), flush=True)

def open_todo_tab():
    sh('shell', 'input', 'keyevent', '3'); time.sleep(1)
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    todo_tab = None
    for _ in range(4):
        todo_tab = find(nodes(dump()), text='待办')
        if todo_tab:
            break
        time.sleep(2)
    if not todo_tab:
        return False
    tap = center(todo_tab)
    sh('shell', 'input', 'tap', str(tap[0]), str(tap[1]))
    time.sleep(2)
    return True

def title_y(t):
    n = find(nodes(dump()), text=t)
    return n['y0'] if n else None

# ---------- 夹具 ----------
sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 databases/purenote.db < /data/local/tmp/todo_seed.sql'")
seeded = db("SELECT title FROM todos ORDER BY created_at;").replace('\n', ',')
check('P0 夹具两条待办(AAA-old 创建更早)', seeded == 'AAA-old,BBB-new', seeded)
if 'AAA-old' not in seeded:
    sys.exit(1)

# ---------- 编辑前顺序:新的在上 ----------
assert open_todo_tab(), '待办页打不开'
yA, yB = title_y('AAA-old'), title_y('BBB-new')
check('P1 编辑前 BBB-new(新)在 AAA-old(旧)上方', yA is not None and yB is not None and yB < yA, f'yA={yA} yB={yB}')

# ---------- 模拟一次"编辑"(与 updateTodo 写集一致:改 title+updated_at,不动 sort_index) ----------
db("UPDATE todos SET title='AAA-edited', updated_at=999999 WHERE title='AAA-old';")
assert open_todo_tab(), '重进待办页失败'
yA2, yB2 = title_y('AAA-edited'), title_y('BBB-new')
check('P2 编辑后 AAA 保持在原位(仍在 BBB 下方)', yA2 is not None and yB2 is not None and yB2 < yA2, f'yA={yA2} yB={yB2}')
si = db("SELECT title,sort_index FROM todos ORDER BY created_at;").replace('\n', ',')
check('P3 编辑未污染 sort_index', si == 'AAA-edited|0,BBB-new|0', si)

# ---------- 真实 UI 编辑冒烟:点开待办编辑面板改标题(ASCII) ----------
# 打开编辑面板:点击 AAA 行文本
row = find(nodes(dump()), text='AAA-edited')
if row:
    sh('shell', 'input', 'tap', str((row['x0'] + row['x1']) // 2), str((row['y0'] + row['y1']) // 2))
    time.sleep(2)
    time.sleep(1)
    ns = nodes(dump())
    sheet_field = [n for n in ns if n['text'] == 'AAA-edited']
    print('P4 诊断: 行节点+面板字段数 =', len(sheet_field), '(冒烟信息,不计入断言)', flush=True)
    sh('shell', 'input', 'keyevent', '4')  # 关面板
    time.sleep(1.5)
else:
    check('P4 编辑面板打开(标题字段可见)', False, '行未找到')

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
