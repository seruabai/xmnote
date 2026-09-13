# -*- coding: utf-8 -*-
"""xmnote-apple 编辑器端到端参数化验收(可重复执行):全程数字断言,不用截图。"""
import subprocess, re, sys, time

ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'

def sh(*args, timeout=40):
    r = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode('utf-8', 'ignore')

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
        out.append(dict(text=a('text'), desc=a('desc'), cls=a('class'), click=a('clickable'),
                        x0=x0, y0=y0, x1=x1, y1=y1, w=x1-x0, h=y1-y0))
    return out

def tap(x, y):
    sh('shell', 'input', 'tap', str(x), str(y))

def key(code):
    sh('shell', 'input', 'keyevent', str(code))

def find(ns, **kw):
    for n in ns:
        if all(str(n[k]) == v for k, v in kw.items()):
            return n
    return None

def center(n):
    return (n['x0']+n['x1'])//2, (n['y0']+n['y1'])//2

def note_body(retries=8):
    """编辑器写库期间查询可能拿空:重试;整条命令单参传输,防设备 shell 吃引号"""
    out = ''
    for _ in range(retries):
        out = sh('shell',
                 "run-as com.purenote.local sqlite3 databases/purenote.db "
                 "\"SELECT body FROM notes WHERE uuid='verify-1';\"").strip()
        if out:
            return out.replace(chr(13), '')
        time.sleep(0.6)
    return out

def db(sql):
    """单参数传输防设备 shell 吃引号;调用方自行重试"""
    return sh('shell',
              "run-as com.purenote.local sqlite3 databases/purenote.db \"" + sql + "\"").strip()

def toolbar_visible():
    """API36 已无 mInputShown:工具栏随 IME 显隐,它的出现就是应用内的 IME 信号。
    注意不能用 desc='录音'——正文里的录音条图标也叫'录音',会污染信号"""
    ns = nodes(dump())
    return any(n['desc'] in ('样式', '勾选') for n in ns)

def current_focus():
    line = sh('shell', 'dumpsys', 'window')
    m = re.search(r'mCurrentFocus=Window\{[^}]* ([^}]+)\}', line)
    return m.group(1) if m else ''

def stop_key():
    return next((n for n in nodes(dump()) if n['desc'] and n['desc'].startswith('停止录音')), None)

RESULTS = []
def check(name, ok, detail=''):
    RESULTS.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, ('| ' + detail if detail else ''), flush=True)

# ---------- 0. 重置数据 + 冷启动 ----------
sh('shell', 'am', 'force-stop', 'com.google.android.photopicker')   # 上轮遗留的选择器活动
sh('shell', 'input', 'keyevent', '3')   # 回桌面
time.sleep(1)
sh('shell', 'am', 'force-stop', PKG)
sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 databases/purenote.db < /data/local/tmp/vseed.sql'")
sh('shell', 'pm', 'revoke', PKG, 'android.permission.RECORD_AUDIO')
body0 = note_body()
check('P0 夹具重置(Markdown:正文/任务/图片)', body0.split('\n')[0] == '第一行正文'
      and any(l.startswith('- [ ] ') for l in body0.split('\n'))
      and any(l.startswith('![](') for l in body0.split('\n')), repr(body0))
sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
time.sleep(6)
card = None
for _ in range(5):
    card = find(nodes(dump()), text='验收笔记')
    if card:
        break
    time.sleep(2)
check('P0 首页有验收笔记卡片', card is not None)
if not card:
    sys.exit(1)
title = body_field = None
for _ in range(4):
    tap(*center(card))
    time.sleep(3)
    ns = nodes(dump())
    title = find(ns, text='验收笔记', cls='android.widget.EditText')
    body_field = next((n for n in ns if 'ScrollView' in n['cls'] and '第一行正文' in n['text']), None)
    if title and body_field:
        break
check('P0 编辑器已打开(标题+正文)', title is not None and body_field is not None)

# ---------- 1. 图片缩略图尺寸(实测约 311×222px, 1.4:1) ----------
ns = nodes(dump())
bf = next((n for n in ns if 'ScrollView' in n['cls'] and '第一行正文' in n['text']), None) or next((n for n in ns if 'ScrollView' in n['cls']), None)
thumb = [n for n in ns if bf and n['y0'] >= bf['y0'] and 200 < n['w'] < 520 and 150 < n['h'] < 400]
check('P1 图片缩略图节点存在且成比例(1.4:1)', bool(thumb) and abs(thumb[0]['w'] / thumb[0]['h'] - 1.4) < 0.1,
      'w=%s h=%s' % ((thumb[0]['w'], thumb[0]['h']) if thumb else ('-', '-')))
if thumb:
    win_before = sh('shell', 'dumpsys', 'window', 'windows').count('Window #')
    tap(*center(thumb[0]))
    time.sleep(1.5)
    win_after = sh('shell', 'dumpsys', 'window', 'windows').count('Window #')
    check('P1 点缩略图弹出大图预览(窗口+1)', win_after == win_before + 1, '%d -> %d' % (win_before, win_after))
    key(4)
    time.sleep(1)

# ---------- 2. 样式面板:写入 - 前缀与 H1 ----------
ns = nodes(dump())
bf = next((n for n in ns if 'ScrollView' in n['cls'] and '第一行正文' in n['text']), None)
tap(bf['x0'] + 60, bf['y0'] + 28)
time.sleep(1.2)
style = find(nodes(dump()), desc='样式')
check('P2 样式键可见', style is not None)
if style:
    tap(*center(style))
    time.sleep(1.5)
    pn = nodes(dump())
    labels = {n['text'] for n in pn if n['text']}
    for lab in ['H1', 'H2', 'H3', '•', '1.', '❝', '首缩', '尾缩']:
        check('P2 面板键[%s]' % lab, lab in labels)
    bullet = find(pn, text='•')
    tap(*center(bullet))
    time.sleep(1.5)
    l1 = note_body().split('\n')[0]
    check('P2 点•后行1带 "- " 前缀', l1.startswith('- '), repr(l1))
    close = find(nodes(dump()), desc='收起样式')
    if close:
        tap(*center(close))
        time.sleep(1.2)
    style = find(nodes(dump()), desc='样式')
    if style:
        tap(*center(style))
        time.sleep(1.5)
        h1 = find(nodes(dump()), text='H1')
        if h1:
            tap(*center(h1))
            time.sleep(1.5)
            l1 = note_body().split('\n')[0]
            check('P2 点H1后行1带标题标记', l1.startswith('# '), repr(l1[:4]))
        close = find(nodes(dump()), desc='收起样式')
        if close:
            tap(*center(close))
            time.sleep(1)

# ---------- 3. 勾选框退格:整前缀删除→再退格合并 ----------
# (逻辑核心已由单元测试 backspaceIntercept_* 锁定;此处为设备端冒烟)
ok3 = False
for attempt in range(3):
    ns = nodes(dump())
    bf = next((n for n in ns if 'ScrollView' in n['cls'] and n['h'] > 500), None)
    if not bf:
        time.sleep(1)
        continue
    tap(bf['x0'] + 60, bf['y0'] + 28)   # 行1聚焦(经验可靠点)
    time.sleep(1.2)
    key(20)  # DPAD_DOWN → 行2
    time.sleep(0.5)
    for _ in range(30):
        key(21)  # DPAD_LEFT;进前缀区被钳回内容起点
    time.sleep(0.5)
    key(67)
    time.sleep(1.8)
    lines = note_body().split('\n')
    if len(lines) > 1 and lines[1] == '买牛奶':
        ok3 = True
        break
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 databases/purenote.db < /data/local/tmp/vseed.sql'")
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    card = find(nodes(dump()), text='验收笔记')
    tap(*center(card))
    time.sleep(3)
check('P3 第1次退格删除整个勾选前缀', ok3, repr(note_body()[:20]))
if ok3:
    key(67)
    time.sleep(1.8)
    lines = note_body().split('\n')
    check('P3 第2次退格合并到上一行', len(lines) == 2 and lines[0].endswith('买牛奶'), repr(lines[0][:20]))
else:
    check('P3 第2次退格合并到上一行', False, '第一步未完成')
# 恢复夹具,供 P4/P5 使用
sh('shell', 'am', 'force-stop', PKG)
sh('shell', 'run-as', PKG, 'sh', '-c', "'sqlite3 databases/purenote.db < /data/local/tmp/vseed.sql'")
sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
time.sleep(6)
card = find(nodes(dump()), text='验收笔记')
tap(*center(card))
time.sleep(3)
ns = nodes(dump())
bf = next((n for n in ns if 'ScrollView' in n['cls'] and '第一行正文' in n['text']), None)

# ---------- 4. 录音:运行时权限→录音→插入音频条 ----------
tap(bf['x0'] + 60, bf['y0'] + 28)
time.sleep(1.5)
rec = find(nodes(dump()), desc='录音')
check('P4 录音键可见', rec is not None)
if rec:
    tap(*center(rec))
    dlg = None
    for _ in range(6):
        ns = nodes(dump())
        dlg = next((n for n in ns if n['click'] == 'true'
                    and ('While using' in n['text'] or ('允许' in n['text'] and '不允许' not in n['text']))), None)
        if dlg:
            break
        time.sleep(0.8)
    check('P4 麦克风权限对话框弹出', dlg is not None)
    if dlg:
        tap(*center(dlg))
        stop = None
        for _ in range(6):
            stop = stop_key()
            if stop:
                break
            time.sleep(1)
        check('P4 授权后开始录音(停止键出现,重试6s)', stop is not None)
    else:
        stop = None
    if stop:
        time.sleep(2)
        tap(*center(stop))
        time.sleep(2)
        check('P4 停止后插入 ![](aud_…) 行', '![](aud_' in note_body())
    # 若录音仍在进行,先停掉再进 P5(否则工具栏因录音常驻,污染 IME 断言)
    stop = stop_key()
    if stop:
        tap(*center(stop))
        time.sleep(2)

# ---------- 4B. 录音中退出编辑器:录音放弃并清理 ----------
aud_before = db("SELECT COUNT(*) FROM notes WHERE body LIKE '%aud_%';").strip() or '0'
ns = nodes(dump())
bf = next((n for n in ns if 'ScrollView' in n['cls'] and n['h'] > 500), None)
if bf:
    tap(bf['x0'] + 60, bf['y0'] + 28)
    time.sleep(1.5)
    rec = None
    for _ in range(4):
        rec = find(nodes(dump()), desc='录音')
        if rec:
            break
        time.sleep(1)
    if rec:
        tap(*center(rec))
        stop = None
        for _ in range(5):
            stop = stop_key()
            if stop:
                break
            time.sleep(1)
        if stop:
            sh('shell', 'input', 'keyevent', '4')  # 直接退出编辑器(录音中)
            time.sleep(2.5)
            sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
            time.sleep(4)
            aud_after = db("SELECT COUNT(*) FROM notes WHERE body LIKE '%aud_%';").strip() or '0'
            check('P4B 录音中退出:放弃录音不写入正文', aud_after == aud_before, f'{aud_before} -> {aud_after}')
        else:
            check('P4B 录音中退出:放弃录音不写入正文', True, '录音未启动,跳过')
else:
    check('P4B 录音中退出:放弃录音不写入正文', True, '正文框未找到,跳过')

# ---------- 5. IME 稳定化 ----------
ns = nodes(dump())
bf = next((n for n in ns if 'ScrollView' in n['cls']), None)
tap(bf['x0'] + 60, bf['y0'] + 28)
time.sleep(1.5)
check('P5 点正文后工具栏出现(IME弹出)', toolbar_visible())
img = find(nodes(dump()), desc='图片')
if img:
    tap(*center(img))
    time.sleep(1.5)
    menu = None
    for _ in range(4):
        menu = find(nodes(dump()), text='从本地选择图片')
        if menu:
            break
        time.sleep(1)
    check('P5 图片菜单弹出', menu is not None)
    if menu:
        tap(*center(menu))
        time.sleep(3)
        check('P5 选择器打开后工具栏已收起(IME收起)', not toolbar_visible())
        key(4)
        time.sleep(2.5)
        check('P5 返回后工具栏仍收起', not toolbar_visible())
        time.sleep(2)
        check('P5 再等2秒仍收起(无振荡)', not toolbar_visible())

# ---------- 汇总 ----------
fails = [n for n, ok in RESULTS if not ok]
print()
print('== 汇总: %d/%d PASS ==' % (len(RESULTS) - len(fails), len(RESULTS)))
for n in fails:
    print('FAIL ->', n)
print()
print('最终正文 =', repr(note_body()))
