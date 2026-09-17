# -*- coding: utf-8 -*-
"""待办页多选态布局验收：长按进入多选时，既有内容**不许位移**。

直连断言：uiautomator dump 里「待办」大字与第一张卡片（及其标题）的 y 坐标，
在多选前后必须一致（±2px）。终端可见，不需要人眼判断。
"""
import subprocess, re, sys, time
SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'; PKG = 'com.purenote.local'
RES = []
def sh(cmd, timeout=90):
    p = subprocess.run([ADB, '-s', SERIAL, 'shell', cmd], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode('utf-8', 'ignore')
def nodes():
    sh('uiautomator dump'); xml = sh('cat /sdcard/window_dump.xml')
    out = []
    for n in re.findall(r'<node[^>]*>', xml):
        def a(k, n=n):
            m = re.search(k + '="([^"]*)"', n); return m.group(1) if m else ''
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
        if m:
            out.append(dict(cls=a('class'), text=a('text'), desc=a('desc'),
                            x0=int(m.group(1)), y0=int(m.group(2)), x1=int(m.group(3)), y1=int(m.group(4))))
    return out
def find(ns, **kw):
    for n in ns:
        if all(n.get(k) == v for k, v in kw.items()):
            return n
    return None
def center(n): return (n['x0'] + n['x1']) // 2, (n['y0'] + n['y1']) // 2
def check(name, ok, detail=''):
    RES.append((name, ok)); print(('PASS' if ok else 'FAIL'), name, ('| ' + str(detail) if detail else ''), flush=True)

# 打开待办页
sh('am force-stop ' + PKG); sh('am start -n ' + PKG + '/.MainActivity'); time.sleep(6)
tab = find(nodes(), text='待办')
if tab:
    sh('input tap %d %d' % center(tab)); time.sleep(2.0)
ns = nodes()
title = find(ns, text='待办')
cards = [n for n in ns if n['cls'].endswith('TextView') and n['text'] and n['text'] not in ('待办',) and n['y0'] > (title['y0'] if title else 0) + 60]
first = cards[0] if cards else None
check('进入待办页并看到第一张卡片', first is not None, 'title=%s first=%s' % (title['y0'] if title else None, first['text'] if first else None))
if not title or not first:
    sys.exit(1)
before = dict(title_y=title['y0'], title_h=title['y1'] - title['y0'], card_y=first['y0'], card_title=first['text'])
print('  多选前：', before)

# 长按第一张卡片（0 距离 650ms 的滑动 = 长按）
cx, cy = center(first)
sh('input swipe %d %d %d %d 650' % (cx, cy, cx, cy))
time.sleep(1.6)
ns2 = nodes()
title2 = find(ns2, text='待办')
card2 = find(ns2, text=before['card_title'])
check('长按后进入多选（出现"退出多选"/"全选"）',
      find(ns2, desc='退出多选') is not None and find(ns2, desc='全选') is not None,
      'exit=%s all=%s' % (find(ns2, desc='退出多选') is not None, find(ns2, desc='全选') is not None))
check('进入多选后「待办」大字不位移（±2px）',
      title2 and abs(title2['y0'] - before['title_y']) <= 2,
      'y %s → %s' % (before['title_y'], title2['y0'] if title2 else None))
check('进入多选后第一张卡片不位移（±2px）',
      card2 and abs(card2['y0'] - before['card_y']) <= 2,
      'y %s → %s' % (before['card_y'], card2['y0'] if card2 else None))

# 退出多选再比一次
exit_btn = find(ns2, desc='退出多选')
if exit_btn:
    sh('input tap %d %d' % center(exit_btn)); time.sleep(1.6)
    ns3 = nodes()
    title3 = find(ns3, text='待办'); card3 = find(ns3, text=before['card_title'])
    check('退出多选后「待办」大字回到原位（±2px）',
          title3 and abs(title3['y0'] - before['title_y']) <= 2,
          'y %s → %s' % (before['title_y'], title3['y0'] if title3 else None))
    check('退出多选后第一张卡片回到原位（±2px）',
          card3 and abs(card3['y0'] - before['card_y']) <= 2,
          'y %s → %s' % (before['card_y'], card3['y0'] if card3 else None))

fails = [n for n, ok in RES if not ok]
print('\n== 汇总: %d/%d PASS ==' % (len(RES) - len(fails), len(RES)))
for n in fails: print('FAIL ->', n)
sys.exit(1 if fails else 0)
