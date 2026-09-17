# -*- coding: utf-8 -*-
"""勾选框 vs 文字中线：用 uiautomator 定窗口、用像素找真实中心（框描边中心 vs 文字墨水中心）。

用法：python tools/verify/checkbox_align_verify.py <serial> <note_id>
"""
import subprocess, re, sys, time
import numpy as np
from PIL import Image
SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
NOTE = sys.argv[2] if len(sys.argv) > 2 else '116'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
SHOT = r'E:\DSH\tmp\align-' + NOTE + '.png'
DP = 2.625

def sh(cmd, timeout=90):
    p = subprocess.run([ADB, '-s', SERIAL, 'shell', cmd], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode('utf-8', 'ignore')

def nodes():
    sh('uiautomator dump')
    xml = sh('cat /sdcard/window_dump.xml')
    out = []
    for n in re.findall(r'<node[^>]*>', xml):
        def a(k, n=n):
            m = re.search(k + '="([^"]*)"', n); return m.group(1) if m else ''
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
        if m:
            out.append(dict(cls=a('class'), text=a('text'), x0=int(m.group(1)), y0=int(m.group(2)),
                            x1=int(m.group(3)), y1=int(m.group(4))))
    return out

sh('am force-stop com.purenote.local')
sh('am start -n com.purenote.local/.MainActivity --el target_id %s --es target_kind note' % NOTE)
time.sleep(5)
ns = nodes()
open(SHOT, 'wb').write(subprocess.run([ADB, '-s', SERIAL, 'exec-out', 'screencap', '-p'], capture_output=True).stdout)
im = np.asarray(Image.open(SHOT).convert('L'), dtype=np.float32)

def ink_center(x0, x1, y0, y1, thr, min_px, drop_cursor=True):
    """在窗口里找深色行簇，返回(中心, 首行, 末行)。

    先按列剔除"光标"：光标的竖线会贯穿整行，按列统计深色像素数，太高的列直接丢掉——
    不剔除的话量到的是光标高度而不是字形墨水（上一版就是这么量错的）。
    """
    sub = im[y0:y1, x0:x1]
    dark = sub < thr
    if drop_cursor:
        col_h = dark.sum(axis=0)
        keep = col_h < max(3, int((y1 - y0) * 0.55))
        if not keep.any():
            return None
        dark = dark[:, keep]
    counts = dark.sum(axis=1)
    rows = np.where(counts >= min_px)[0]
    if len(rows) == 0:
        return None
    a, b = y0 + rows.min(), y0 + rows.max()
    return ((a + b) / 2.0, a, b)

checks = [n for n in ns if n['cls'].endswith('CheckBox')]
fields = [n for n in ns if n['cls'].endswith('EditText')]
ok = tot = 0
for c in checks:
    # 勾选框窗口不做光标剔除：那里没有光标，剔除会把方框的竖边也一起丢掉
    box = ink_center(c['x0'], c['x1'], c['y0'], c['y1'], 235, 3, drop_cursor=False)
    if box is None:
        print('  (跳过：勾选框窗口内没找到描边) window=%s' % [c['x0'], c['y0'], c['x1'], c['y1']])
        continue
    bc = box[0]
    cands = [f for f in fields if f['x0'] >= c['x1'] - 8 and f['y1'] > c['y0'] and f['y0'] < c['y1']]
    if not cands:
        print('  (跳过：没找到同一行的输入框) box y=%d..%d' % (c['y0'], c['y1']))
        continue
    f = max(cands, key=lambda f: min(f['y1'], c['y1']) - max(f['y0'], c['y0']))
    txt = ink_center(f['x0'], min(f['x0'] + 260, f['x1']), f['y0'], f['y1'], 175, 2)
    if txt is None:
        continue
    d = bc - txt[0]
    tot += 1
    good = abs(d) <= 2
    ok += 1 if good else 0
    print('%s %-22r 框 y=%d..%d 中心=%.1f | 字 y=%d..%d 中心=%.1f | 偏差=%+.1fpx (%+.1fdp)'
          % ('PASS' if good else 'FAIL', (f['text'] or '(空)')[:20], box[1], box[2], bc, txt[1], txt[2], txt[0], d, d / DP))
print('\n== 汇总: %d/%d 在 ±2px 内 ==' % (ok, tot))
