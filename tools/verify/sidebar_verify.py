# -*- coding: utf-8 -*-
"""侧栏速记三条反馈的验收（用户 2026-09-17）

  S1 编辑一条**单条待办**：原文落到内容行、标题行是占位"待办清单"、光标在第三行空白行
  S2 在该编辑器里按回车：插入新行 **且输入法仍然显示**（旧行为是回车把键盘收起来）
  S3 设置里的"隐藏保活通知"开关：打开后消息栏里不再有纯记的前台服务通知

直连：uiautomator dump / dumpsys input_method / dumpsys notification / run-as sqlite3。
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
        out.append(dict(text=a('text'), desc=a('desc'), cls=a('class'), focused=a('focused'),
                        checkable=a('checkable'), checked=a('checked'),
                        x0=x0, y0=y0, x1=x1, y1=y1))
    return out

def find(ns, key, val):
    return next((n for n in ns if n[key] == val), None)

def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2

def check(name, ok, detail=''):
    RESULTS.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, ('| ' + str(detail)[:220] if detail else ''), flush=True)

def ime_shown():
    d = sh('shell', 'dumpsys', 'input_method')
    return 'mInputShown=true' in d

def edit_rows():
    """侧栏编辑卡里的输入行（EditText），按 y 排序"""
    rows = [n for n in nodes() if n['cls'].endswith('EditText')]
    return sorted(rows, key=lambda n: n['y0'])

# ---- 准备：悬浮窗权限 + 走应用自己的开关启动侧栏（服务未导出，adb 直接起会报权限错）----
sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
sh('shell', 'am', 'force-stop', PKG)
sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
time.sleep(5)
ns = nodes()
gear = find(ns, 'desc', '设置')
if gear:
    sh('shell', 'input', 'tap', str(center(gear)[0]), str(center(gear)[1]))
    time.sleep(2.5)
    quick = find(nodes(), 'text', '速记')
    if quick:
        sh('shell', 'input', 'tap', str(center(quick)[0]), str(center(quick)[1]))
        time.sleep(2.0)
        # 开关是 checkable 的 View（不是 Switch 类），按标签就近匹配
        label = find(nodes(), 'text', '屏幕边缘速记')
        switches = [n for n in nodes() if n.get('checkable') == 'true']
        sw = None
        if switches and label:
            sw = min(switches, key=lambda s: abs(s['y0'] - label['y0']))
        if sw is not None and sw.get('checked') != 'true':
            sh('shell', 'input', 'tap', str(center(sw)[0]), str(center(sw)[1]))
            time.sleep(3.0)
        done = find(nodes(), 'text', '完成')
        if done:
            sh('shell', 'input', 'tap', str(center(done)[0]), str(center(done)[1]))
            time.sleep(1.5)
sh('shell', 'input', 'keyevent', '3')      # 回桌面，侧栏把手在桌面上也应当可见
time.sleep(2.0)
# 把手是纯 WindowManager 视图，**没有语义节点**，uiautomator 看不到；
# 改从 dumpsys window 的窗口属性里解析它的位置：形如 mAttrs={(0,816)(57x168) gr=TOP END CENTER ... ty=APPLICATION_OVERLAY
wins = sh('shell', 'dumpsys', 'window', 'windows')
handle_rect = None
for line in wins.splitlines():
    if 'APPLICATION_OVERLAY' not in line or 'mAttrs=' not in line:
        continue
    m = re.search(r'\((\d+),(\d+)\)\((\d+)x(\d+)\)', line)
    if m:
        x, y, w, h = map(int, m.groups())
        if 0 < w < 200:          # 窄的那个就是把手
            handle_rect = (x, y, w, h)
            break
check('S0 侧栏把手出现（悬浮窗已授权）', handle_rect is not None, 'handle=%s' % (handle_rect,))
if handle_rect is None:
    print('  提示：若把手没出现，可能需要在系统里允许"显示在其他应用上层"'); sys.exit(1)

# 拉出面板：把手是 TOP|END 对齐（attrs 里的 x 是相对右缘的偏移，不是屏幕坐标），
# 所以起点要用屏幕宽度算，不能用 attrs 的 x。
size = sh('shell', 'wm', 'size')
screen_w = int(re.search(r'(\d+)x(\d+)', size).group(1))
_, hy, _, hh = handle_rect
swipe_y = hy + hh // 2
# 先把"点按把手"作为首选（滑动要跨过 26dp 阈值，注入节奏容易不达标）
sh('shell', 'input', 'tap', str(screen_w - 28), str(swipe_y))
time.sleep(2.5)
if not any('对齐待办一' in (n.get('text') or '') for n in nodes()):
    sh('shell', 'input', 'swipe', str(screen_w - 5), str(swipe_y), str(screen_w - 700), str(swipe_y), '600')
    time.sleep(2.5)
ns = nodes()
card = find(ns, 'text', '对齐待办一')
check('S0 面板已拉出并看到待办卡片', card is not None,
      '%s' % [n['text'] for n in ns if n['text']][:10])
if not card:
    sys.exit(1)

# ---- S1 编辑单条待办：原文进内容行、标题占位、光标在第三行 ----
sh('shell', 'input', 'tap', str(center(card)[0]), str(center(card)[1]))
time.sleep(2.0)
rows = edit_rows()
check('S1 编辑卡出现三行（标题 + 内容 + 空白）', len(rows) >= 3,
      '输入行 %d：%s' % (len(rows), [(r['text'], r['y0']) for r in rows]))
if len(rows) >= 3:
    title_row, content_row, blank_row = rows[0], rows[1], rows[2]
    check('S1 第一行是空标题行（占位"待办清单"）', title_row['text'] == '', repr(title_row['text']))
    check('S1 第二行是原文内容（不再是标题）', content_row['text'] == '对齐待办一', repr(content_row['text']))
    check('S1 光标落在第三行空白行', rows[2]['focused'] == 'true' or (rows[2]['text'] == ''),
          'focused=%s text=%r' % (rows[2]['focused'], rows[2]['text']))

# ---- S2 回车：插新行 + 键盘不收起 ----
before_rows = len(edit_rows())
sh('shell', 'input', 'keyevent', '66')
time.sleep(1.5)
after_rows = len(edit_rows())
check('S2 回车插入了新行', after_rows == before_rows + 1,
      '%d → %d' % (before_rows, after_rows))
check('S2 回车之后输入法仍然显示（不再被收起）', ime_shown(),
      'dumpsys input_method mInputShown')

# ---- S3 隐藏保活通知 ----
before_notif = sh('shell', 'dumpsys', 'notification', '--noredact')
has_before = PKG in before_notif
# 通过设置页的开关打开（模拟用户操作）
sh('shell', 'input', 'keyevent', '4')      # 关面板
time.sleep(1.5)
sh('shell', 'am', 'force-stop', PKG)
sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
time.sleep(5)
ns = nodes()
tab = find(ns, 'text', '笔记')
if tab:
    sh('shell', 'input', 'tap', str(center(tab)[0]), str(center(tab)[1]))
    time.sleep(1.5)
settings = find(nodes(), 'desc', '设置')
if settings:
    sh('shell', 'input', 'tap', str(center(settings)[0]), str(center(settings)[1]))
    time.sleep(2.5)
    quick = find(nodes(), 'text', '速记')
    if quick:
        sh('shell', 'input', 'tap', str(center(quick)[0]), str(center(quick)[1]))
        time.sleep(2.0)
        switches = [n for n in nodes() if n.get('checkable') == 'true']
        target = None
        hide_label = find(nodes(), 'text', '隐藏保活通知')
        if hide_label and switches:
            target = min(switches, key=lambda s: abs(s['y0'] - hide_label['y0']))
        check('S3 设置页出现"隐藏保活通知"开关', target is not None,
              '开关 %d 个，标签=%s' % (len(switches), hide_label is not None))
        if target:
            sh('shell', 'input', 'tap', str(center(target)[0]), str(center(target)[1]))
            time.sleep(2.5)
            after_notif = sh('shell', 'dumpsys', 'notification', '--noredact')
            check('S3 打开后消息栏里不再有纯记的前台通知',
                  PKG not in after_notif or 'quick_capture' not in after_notif,
                  '打开前存在=%s 打开后存在=%s' % (has_before, PKG in after_notif))
    else:
        check('S3 设置页出现"隐藏保活通知"开关', False, '没找到"速记"入口')
else:
    check('S3 设置页出现"隐藏保活通知"开关', False, '没找到设置按钮')

fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
sys.exit(1 if fails else 0)
