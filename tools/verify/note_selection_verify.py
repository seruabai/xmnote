# -*- coding: utf-8 -*-
"""笔记页多选态验收（用户 2026-09-19 的四条要求，逐条落成断言）：

1) 长按进多选 / 退出多选，页头「笔记」大字与前 3 张卡片的几何不许位移（±2px）——
   与 tools/verify/todo_selection_verify.py 同一个口径（待办页早先同款问题、同样修法）；
2) 多选态每张卡片右下角有一个 44dp 勾选框，命中区外缘贴住卡片右下角；
3) 置顶图标紧挨勾选框左侧、同一行（两者中心 y 差 ≤ 6px）；
4) 设了提醒的纸条，左下角那一格显示的是**提醒日期时间**（与库里 remind_at 对齐），不是笔记日期；
5) 左下角那一格永远单行（宽度不够就省年，而不是挤字/换行）。

用法：python tools/verify/note_selection_verify.py [serial]
只读：uiautomator dump + wm size/density（另只读查库核对提醒时间），不改任何东西。
退出码：0 全 PASS；1 有 FAIL。SKIP = 屏幕上缺数据（如没有带提醒的纸条），不算失败。
"""
import atexit, re, subprocess, sys, time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
RES, SKIPPED = [], []


def sh(cmd, timeout=90):
    p = subprocess.run([ADB, '-s', SERIAL, 'shell', cmd], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode('utf-8', 'ignore')


def check(name, ok, detail=''):
    RES.append((name, ok))
    print(('PASS' if ok else 'FAIL'), name, ('| ' + str(detail) if detail else ''), flush=True)


def skip(name, why):
    SKIPPED.append((name, why))
    print('SKIP', name, '|', why, flush=True)


def dump(retry=4):
    """uiautomator 在动画/忙的时候会 dump 失败，此时 cat 出来的是**上一次的旧文件**——
    先删再 dump，拿不到 hierarchy 就重试，否则会把上一屏当成这一屏，结论作废。"""
    for _ in range(retry):
        sh('rm -f /sdcard/window_dump.xml')
        sh('uiautomator dump')
        xml = sh('cat /sdcard/window_dump.xml')
        if '<hierarchy' in xml:
            return parse(xml)
        time.sleep(1.5)
    return []


def parse(xml):
    out = []
    for raw in re.findall(r'<node[^>]*>', xml):
        def a(k, raw=raw):
            m = re.search(k + '="([^"]*)"', raw)
            return m.group(1) if m else ''
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
        if not m:
            continue
        x0, y0, x1, y1 = map(int, m.groups())
        out.append(dict(cls=a('class'), text=a('text'), desc=a('content-desc'),
                        click=a('clickable') == 'true', checkable=a('checkable') == 'true',
                        x0=x0, y0=y0, x1=x1, y1=y1))
    return out


def find(ns, **kw):
    for n in ns:
        if all(n.get(k) == v for k, v in kw.items()):
            return n
    return None


def center(n):
    return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2


def inside(outer, inner):
    cx, cy = center(inner)
    return outer['x0'] <= cx <= outer['x1'] and outer['y0'] <= cy <= outer['y1']


def clean_screen():
    """清场：系统 ANR 弹窗（"System UI isn't responding"）和残留对话框会挡住界面，
    挡住的话量到的是弹窗不是列表（要求里点名过），所以先点掉再按两次 BACK + force-stop。"""
    for _ in range(4):
        ns = dump()
        dlg = [n for n in ns if (n['text'] or '') in ('Wait', 'Close app', '等待', '关闭应用')]
        if not dlg:
            break
        sh('input keyevent 4'); time.sleep(0.6)
        ns = dump()
        dlg = [n for n in ns if (n['text'] or '') in ('Wait', 'Close app', '等待', '关闭应用')]
        if dlg:
            sh('input tap %d %d' % center(dlg[0])); time.sleep(1.2)
    sh('input keyevent 4'); time.sleep(0.4)
    sh('input keyevent 4'); time.sleep(0.4)
    sh('am force-stop ' + PKG); time.sleep(1.0)


def open_notes():
    """起应用并等笔记页真的画出来（冷启动在模拟器上要好几秒，页面没出来就量会拿到上一屏）"""
    sh('am start -n ' + PKG + '/.MainActivity')
    for _ in range(12):
        time.sleep(2)
        ns = dump()
        if big_title(ns, '笔记') and len(cards(ns, big_title(ns, '笔记'))) >= 3:
            return ns
    return dump()


W, H = map(int, re.search(r'(\d+)x(\d+)', sh('wm size')).groups())
DPI = int(re.search(r'(\d+)', sh('wm density')).group(1))
DP = DPI / 160.0


def dp(px):
    return round(px / DP, 1)


def big_title(ns, label):
    """页头大字（同文案在底部标签栏还有一份，取靠上的那个）"""
    cands = [n for n in ns if n['text'] == label]
    return min(cands, key=lambda n: n['y0']) if cands else None


def container(ns, node):
    """包含该文本节点中心的最小可点节点 = 它所在的卡片容器"""
    cands = [n for n in ns if n['click'] and inside(n, node)]
    return min(cands, key=lambda n: (n['x1'] - n['x0']) * (n['y1'] - n['y0'])) if cands else None


def list_bottom(ns):
    """列表可视区下沿 = 底部标签栏的上沿（被裁掉的卡片不能拿来量勾选框，会短路成假 FAIL）"""
    nav = [n for n in ns if n['click'] and n['text'] in ('笔记', '待办') and n['y0'] > H * 0.6]
    return min(n['y0'] for n in nav) if nav else H


def cards(ns, title):
    """列表里的卡片：占屏宽 ≥35%、高 ≥140px 的可点容器。
    分类胶囊只有 44dp 高、底部标签栏在最下一条（>0.86H），都会在这里被排掉。"""
    if title is None:
        return []
    out, seen = [], set()
    for n in sorted([x for x in ns if (x['text'] or '').strip() and x['y0'] > title['y1']],
                    key=lambda n: n['y0']):
        if n['text'] in ('笔记', '待办'):
            continue
        c = container(ns, n)
        if not c:
            continue
        if (c['x1'] - c['x0']) < 0.35 * W or (c['y1'] - c['y0']) < 140 or c['y0'] > H * 0.86:
            continue
        key = (c['x0'], c['y0'], c['x1'], c['y1'])
        if key in seen:
            continue
        seen.add(key)
        texts = [t for t in ns if (t['text'] or '').strip() and inside(c, t)]
        out.append(dict(card=c, texts=texts,
                        title=sorted(texts, key=lambda t: t['y0'])[0]['text']))
    return out


def stamp_of(ns, card):
    """卡片左下角那一格：最下面一行里最靠左的文本节点（日期或提醒时间）"""
    row = [t for t in ns if (t['text'] or '').strip() and inside(card, t)
           and t['y0'] >= card['y1'] - int(40 * DP)]
    return min(row, key=lambda t: t['x0']) if row else None


def snapshot(ns):
    title = big_title(ns, '笔记')
    cs = cards(ns, title)[:3]
    return title, [dict(bounds=(c['card']['x0'], c['card']['y0'], c['card']['x1'], c['card']['y1']),
                          title=c['title'], texts=[t['text'] for t in c['texts']]) for c in cs]


def compare(tag, ref_title, ref_cards, ns):
    title = big_title(ns, '笔记')
    check('%s：页头「笔记」大字不位移（±2px）' % tag,
          title is not None and abs(title['y0'] - ref_title['y0']) <= 2,
          'y %s → %s' % (ref_title['y0'], title['y0'] if title else None))
    cs = cards(ns, title)
    for i, b in enumerate(ref_cards):
        if i >= len(cs):
            check('%s：第 %d 张卡片还在' % (tag, i + 1), False, '卡片没了')
            continue
        cb = cs[i]['card']
        now = (cb['x0'], cb['y0'], cb['x1'], cb['y1'])
        d = max(abs(a - c) for a, c in zip(now, b['bounds']))
        check('%s：第 %d 张卡片几何不位移（±2px）' % (tag, i + 1), d <= 2,
              '%r %s → %s 最大偏差 %dpx' % (b['title'][:10], b['bounds'], now, d))


def db_reminders():
    """只读查库：拿"哪张纸条设了提醒"当要求 4 的判据来源（列表模型里有 remind_at）"""
    dbs = [l.split()[-1] for l in sh('run-as %s ls databases' % PKG).splitlines()
           if l.strip().endswith('.db') and 'journal' not in l]
    dbs = [d for d in dbs if 'purenote-' in d] or dbs
    if not dbs:
        return None
    out = sh("run-as %s sqlite3 databases/%s 'SELECT title, remind_at, all_day FROM notes "
             "WHERE trashed = 0 AND remind_at IS NOT NULL'" % (PKG, dbs[0]))
    rows = []
    for line in out.splitlines():
        p = line.split('|')
        if len(p) == 3 and p[1].strip().isdigit():
            rows.append((p[0], int(p[1]), p[2].strip() == '1'))
    return rows


def device_time(ms):
    out = sh("date -d @%d +'%%Y|%%m|%%d|%%H|%%M'" % (ms // 1000)).strip()
    return out.split('|') if out.count('|') == 4 else None


_REPORTED = [False]


def summary_and_exit(code):
    """run_all.py 拿 '== 汇总:' 这一行判真假绿：无论怎么结束都要打印它。"""
    _REPORTED[0] = True
    fails = [n for n, ok in RES if not ok]
    print('\n== 汇总: %d/%d PASS%s ==' % (len(RES) - len(fails), len(RES),
                                         ('，%d SKIP' % len(SKIPPED)) if SKIPPED else ''))
    for n in fails:
        print('FAIL ->', n)
    for n, why in SKIPPED:
        print('SKIP ->', n, ':', why)
    sys.exit(code)


# 脚本自己崩了（异常/断言）也要留一行汇总：run_all 只认 '== 汇总:' 判真假绿，
# 没有这一行会被当成"没跑起来"，有这一行才会把失败原因列出来。
atexit.register(lambda: None if _REPORTED[0] else summary_and_exit(1))


clean_screen()
ns = open_notes()
title, before = snapshot(ns)
check('笔记页加载出页头与前 3 张卡片', title is not None and len(before) >= 3,
      'header_y=%s cards=%d' % (title['y0'] if title else None, len(before)))
if title is None or len(before) < 3:
    check('笔记页前置条件（页头 + ≥3 张卡片）', False, '界面没起来或列表为空，后面的量测无从谈起')
    summary_and_exit(1)   # 直接收尾：这一行下面，列表是空的就什么都量不了
print('  多选前：', [(b['title'][:8], b['bounds']) for b in before])

# ---- 长按第一张卡片进多选（0 距离 650ms 的滑动 = 长按）----
first = dict(zip(('x0', 'y0', 'x1', 'y1'), before[0]['bounds']))
cx, cy = center(first)
# 850ms：模拟器慢的时候 650ms 贴着长按阈值走，会偶发进不去多选（实测踩过）
sh('input swipe %d %d %d %d 850' % (cx, cy, cx, cy))
time.sleep(2.2)
mid = dump()
entered = ((find(mid, desc='退出多选') is not None and find(mid, desc='全选') is not None)
           or any((n['text'] or '').startswith('已选') for n in mid))
check('长按后进入多选（出现"已选 N 项"/"退出多选"/"全选"）',
      entered and find(mid, desc='选择笔记') is not None,
      '已选=%s exit=%s all=%s box=%s' % (any((n['text'] or '').startswith('已选') for n in mid),
                                         find(mid, desc='退出多选') is not None,
                                         find(mid, desc='全选') is not None,
                                         find(mid, desc='选择笔记') is not None))
compare('进入多选', title, before, mid)

# ---- 要求 2/3：勾选框在最右下角 + 置顶在勾选框左侧同一行 ----
mid_cards = cards(mid, big_title(mid, '笔记'))
if not mid_cards:
    check('多选态能识别到卡片', False, '')
else:
    ok_box, ok_pin, box_detail, pin_detail = True, True, [], []
    bottom = list_bottom(mid)
    checked = 0
    for c in mid_cards:
        cb = next((n for n in mid if n['desc'] == '选择笔记' and inside(c['card'], n)), None)
        if cb is None:
            ok_box = False
            box_detail.append('%s 无勾选框' % c['title'][:8])
            continue
        size = (cb['x1'] - cb['x0'], cb['y1'] - cb['y0'])
        if min(size) < 44 * DP - 4 or cb['y1'] > bottom:
            # 列表下沿那半张卡片，勾选框本身也被裁了 —— 无从测量，跳过（不计入判定）
            box_detail.append('%s 勾选框被列表下沿裁掉(%dx%dpx)，跳过' % (c['title'][:8], size[0], size[1]))
            continue
        checked += 1
        inset = (c['card']['x1'] - cb['x1'], c['card']['y1'] - cb['y1'])
        # "最右下角"按外缘判定：勾选框右下角必须压在卡片右下角上（≤4px），且整体在卡片右半边。
        # 不按"卡片下半部"判：只有一行字的短卡片整张还不到两个勾选框高，那条判据会误伤。
        right_half = cb['x0'] >= c['card']['x0'] + (c['card']['x1'] - c['card']['x0']) * 0.5
        box_detail.append('%s %dx%dpx(%.0fdp) 距卡片右下角 %d,%dpx 右半边=%s'
                          % (c['title'][:8], size[0], size[1], dp(size[0]), inset[0], inset[1], right_half))
        if max(inset) > 4 or not right_half or min(size) < 44 * DP - 4:
            ok_box = False
        pin = next((n for n in mid if n['desc'] == '已置顶' and inside(c['card'], n)), None)
        if pin is not None:
            gap = pin['x1'] - cb['x0']
            dy = abs(center(pin)[1] - center(cb)[1])
            pin_detail.append('%s 置顶 x1=%d 勾选框 x0=%d 水平间距=%dpx 中心 y 差=%dpx'
                              % (c['title'][:8], pin['x1'], cb['x0'], gap, dy))
            if gap > 2 or dy > 6:
                ok_pin = False
    check('要求 2：勾选框 44dp、落在卡片右下角（完整可见的 %d 张）' % checked, ok_box and checked > 0,
          ' / '.join(box_detail))
    check('要求 3：置顶图标紧挨勾选框左侧、同一行', ok_pin and bool(pin_detail),
          ' / '.join(pin_detail) or '本屏没有置顶纸条，无法判定')

# ---- 要求 4/5：左下角那一格 ----
stamps = [(c, stamp_of(mid, c['card'])) for c in mid_cards]
stamps = [(c, s) for c, s in stamps if s]
check('要求 5：左下角那一格永远单行（没有换行）', len(stamps) > 0
      and all((s['y1'] - s['y0']) <= 48 for _, s in stamps),
      ' / '.join('%s h=%dpx %r' % (c['title'][:8], s['y1'] - s['y0'], s['text']) for c, s in stamps))
reminders = db_reminders()
if reminders is None:
    skip('要求 4：提醒纸条显示提醒日期时间', '读不到库（run-as/sqlite3 不可用）')
elif not reminders:
    skip('要求 4：提醒纸条显示提醒日期时间', '库里没有设了提醒的纸条')
else:
    hit = False
    for c, s in stamps:
        shown = {x['text'] for x in c['texts']}
        for t, ms, all_day in reminders:
            if t and t in shown:
                hit = True
                tm = device_time(ms)
                exp = None
                if tm:
                    # 设备 date 给的是零填充（09），应用文案不带前导零（9月19日）
                    y, mo, dd, hh, mm = tm
                    exp = ('%s年%d月%d日 %s:%s' % (y, int(mo), int(dd), hh, mm),
                           '%d月%d日 %s:%s' % (int(mo), int(dd), hh, mm))
                check('要求 4：%r 左下角是提醒时间（%s），不是笔记日期' % (t[:10], s['text']),
                      exp is not None and s['text'] in exp,
                      '库 remind_at=%d → 期望 %s 或 %s，实际 %r'
                      % (ms, exp[0] if exp else '?', exp[1] if exp else '?', s['text']))
                print('    （该格 %s，带年写法 %s）' % ('带年' if '年' in s['text'] else '省年',
                                                      exp[0] if exp else '?'))
    if not hit:
        skip('要求 4：提醒纸条显示提醒日期时间', '本屏可见的纸条里没有设提醒的（库里 %d 条）' % len(reminders))

# ---- 退出多选，再量一次 ----
exit_btn = find(mid, desc='退出多选')
if exit_btn:
    sh('input tap %d %d' % center(exit_btn)); time.sleep(1.8)
    out = dump()
    check('退出多选后回到常态（勾选框消失、设置齿轮回来）',
          find(out, desc='选择笔记') is None and find(out, desc='设置') is not None,
          'box=%s gear=%s' % (find(out, desc='选择笔记') is not None,
                              find(out, desc='设置') is not None))
    compare('退出多选', title, before, out)
else:
    check('找到"退出多选"按钮', False, '')

summary_and_exit(1 if [n for n, ok in RES if not ok] else 0)
