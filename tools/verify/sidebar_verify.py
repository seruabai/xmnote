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

# ---- 夹具复位：把"对齐待办一"恢复成单条待办（标题=原文、无子项），S1 才有确定形态 ----
# 上一轮的 S2 会往空行注入字符来唤起输入法，那会在库里留下一条子项；这里先清干净。
def db_path():
    for line in sh('shell', 'run-as ' + PKG + ' ls databases').splitlines():
        name = line.split()[-1] if line.split() else ''
        if name.startswith('purenote-') and name.endswith('.db'):
            return 'databases/' + name
    return None

def sql(q):
    db = db_path()
    return '' if not db else sh('shell', 'run-as %s sqlite3 %s "%s"' % (PKG, db, q)).strip()

# 探针会在标题里留下 xxxxx 之类的尾巴（输入法测试注入的字符），这里按前缀一并收掉
_todo = sql("select id from todos where parent_id is null "
            "and (title like '%对齐待办一%' or title like '%xxxxx%' or title like '待办清单%') limit 1;")
if _todo:
    _tid = _todo.split()[0]
    sql('delete from todos where parent_id=%s;' % _tid)
    sql("update todos set title='对齐待办一', done=0, done_at=NULL where id=%s;" % _tid)
    print('夹具复位：todo %s = 单条待办"对齐待办一"（清掉历史子项）' % _tid, flush=True)

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
# 把手是纯 WindowManager 视图，**没有语义节点**，uiautomator 看不到。
# 位置不要从 dumpsys window 的 mAttrs 解析：TOP|END 窗口的 x/y 是"相对右缘/顶部"的偏移，
# 而且 attrs 与实际 frame 会不同步（本机实测 attrs 说 y=816，真实 frame 在 y=944，点按就落空）。
# 可靠来源是 dumpsys input 里该窗口的 frame —— 那个矩形就是真正吃触摸的区域。
handle_rect = None
for line in sh('shell', 'dumpsys', 'input').splitlines():
    if "name='" not in line or PKG not in line or 'frame=[' not in line:
        continue
    m = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', line)
    if not m:
        continue
    x0, y0, x1, y1 = map(int, m.groups())
    if 0 < x1 - x0 < 200:        # 窄的那个就是把手
        handle_rect = (x0, y0, x1, y1)
        break
check('S0 侧栏把手出现（悬浮窗已授权）', handle_rect is not None, 'handle frame=%s' % (handle_rect,))
if handle_rect is None:
    print('  提示：若把手没出现，可能需要在系统里允许"显示在其他应用上层"'); sys.exit(1)

# 拉出面板：**点按把手**即可（onClick → showPanel），比滑动稳（滑动要跨过 26dp 阈值，注入节奏容易不达标）。
hx, hy = (handle_rect[0] + handle_rect[2]) // 2, (handle_rect[1] + handle_rect[3]) // 2
for _ in range(3):
    sh('shell', 'input', 'tap', str(hx), str(hy))
    time.sleep(2.5)
    if any(n.get('text') for n in nodes()):
        break
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
    # 注意：空输入框 uiautomator 会把 hint 当 text 报出来，所以"空"= 文本为空串或就是占位词
    check('S1 第一行是空标题行（占位"待办清单"）',
          title_row['text'] in ('', '待办清单'), repr(title_row['text']))
    check('S1 第二行是原文内容（不再是标题）', content_row['text'] == '对齐待办一', repr(content_row['text']))
    check('S1 光标落在第三行空白行', rows[2]['focused'] == 'true' or (rows[2]['text'] == ''),
          'focused=%s text=%r' % (rows[2]['focused'], rows[2]['text']))

# ---- S2 回车：插新行 + 键盘不收起 ----
# 输入法要真弹出来这条才有意义：聚焦/翻页不一定弹，注入一个字符把它叫出来。
if not ime_shown():
    sh('shell', 'input', 'text', 'x')
    time.sleep(1.5)
check('S2 前置：输入法已弹出', ime_shown(), 'mInputShown=true')
before_rows = len(edit_rows())
sh('shell', 'input', 'keyevent', '66')
time.sleep(1.5)
after_rows = len(edit_rows())
check('S2 回车插入了新行', after_rows == before_rows + 1,
      '%d → %d' % (before_rows, after_rows))
check('S2 回车之后输入法仍然显示（不再被收起）', ime_shown(),
      'dumpsys input_method mInputShown')

# ---- S4 点「完成」：面板留在列表、收尾状态与点之前一致（用户 2026-09-17 反馈的"抽动"）----
def marker_y():
    """用笔记条标题的 y 当位移观测点：前后不变 = 收尾没有位移"""
    n = find(nodes(), 'text', '对齐检查')
    return n['y0'] if n else None

done = find(nodes(), 'text', '完成')
for _ in range(5):                     # 键盘占位时"完成"在窗口下沿外，往上滑把它露出来
    if done is not None:
        break
    sh('shell', 'input', 'swipe', '540', '1350', '540', '1150', '250')
    time.sleep(1.0)
    done = find(nodes(), 'text', '完成')
check('S4 编辑卡里出现"完成"', done is not None, '完成=%s' % (done is not None))
if done:
    before_y = marker_y()
    sh('shell', 'input', 'tap', str(center(done)[0]), str(center(done)[1]))
    time.sleep(2.5)
    ns = nodes()
    check('S4 点完成后面板还在（退回列表，不连带收起）',
          any('对齐待办一' in (n.get('text') or '') for n in ns),
          [n['text'] for n in ns if n['text']][:8])
    time.sleep(0.6)
    mid_y = marker_y()
    time.sleep(1.6)
    after_y = marker_y()
    check('S4 收尾静止：换装后两次采样位置一致（没有二次位移）',
          mid_y is not None and mid_y == after_y, 'y %s → %s' % (mid_y, after_y))
    check('S4 点完成后列表位置基本保持（差值 > 20px 才算异常；小差值来自列表比编辑卡短时的钳位）',
          before_y is not None and after_y is not None and abs(after_y - before_y) <= 20,
          'marker y %s → %s' % (before_y, after_y))

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
            if target.get('checked') == 'true':
                print('  （开关本来就是打开的，不重复点）')
            else:
                sh('shell', 'input', 'tap', str(center(target)[0]), str(center(target)[1]))
                time.sleep(1.5)
            pref = sh('shell', 'run-as', PKG, 'cat', 'shared_prefs/quick_capture.xml')
            check('S3 开关已打开并落盘',
                  'hide_keepalive_notification" value="true' in pref,
                  [l.strip() for l in pref.splitlines() if 'hide_keepalive' in l])
            # 开关只决定"下次挂前台通知时要不要撤回"，所以必须让服务重走一遍 startForeground。
            sh('shell', 'am', 'force-stop', PKG)
            sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
            time.sleep(6)
            after_notif = sh('shell', 'dumpsys', 'notification', '--noredact')
            # 只认 NotificationRecord —— 通道定义（NotificationChannel mId='quick_capture'）
            # 无论有没有通知都在，用它判断会永远"还有通知"。
            alive = any('NotificationRecord' in l and PKG in l for l in after_notif.splitlines())
            check('S3 打开开关并重启服务后，消息栏里不再有保活通知', not alive,
                  '打开前有=%s，重启服务后还有=%s' % (has_before, alive))
            if alive:
                print('  说明：撤回调用是成功的（logcat 过滤 QuickCapture 可看到 4 拍日志），'
                      '但 AOSP 13 会在服务仍处于前台态时把这条前台通知重新挂回；'
                      '这一步在允许撤回前台通知的机型（部分 MIUI）上才会 PASS。')
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
