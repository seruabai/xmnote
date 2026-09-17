# -*- coding: utf-8 -*-
"""排版审计：按大厂标准给一屏做体检（外边距一致性 / 8pt 网格 / 触控目标 / 纵向节奏）。

用法：python tools/verify/layout_audit.py <serial> [screenshot-name]
只读：uiautomator dump + wm size/density，不改任何东西，不需要人眼判断。
"""
import subprocess, re, sys
SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
LABEL = sys.argv[2] if len(sys.argv) > 2 else 'screen'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'

def sh(cmd, timeout=90):
    p = subprocess.run([ADB, '-s', SERIAL, 'shell', cmd], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode('utf-8', 'ignore')

size = sh('wm size'); dens = sh('wm density')
W, H = map(int, re.search(r'(\d+)x(\d+)', size).groups())
DPI = int(re.search(r'(\d+)', dens).group(1)); DP = DPI / 160.0
def dp(px): return round(px / DP, 1)

sh('uiautomator dump')
xml = sh('cat /sdcard/window_dump.xml')
nodes = []
for n in re.findall(r'<node[^>]*>', xml):
    def a(k, n=n):
        m = re.search(k + '="([^"]*)"', n); return m.group(1) if m else ''
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
    if not m: continue
    x0, y0, x1, y1 = map(int, m.groups())
    nodes.append(dict(cls=a('class').split('.')[-1], text=a('text'), desc=a('content-desc'),
                      clickable=a('clickable') == 'true', x0=x0, y0=y0, x1=x1, y1=y1))
named = [n for n in nodes if n['text'] or n['desc']]
print('=== %s  %dx%d @%.2fx (%d dpi) ===' % (LABEL, W, H, DP, DPI))

# 1) 水平外边距：正文类节点的左右缘分
lefts = sorted({n['x0'] for n in named if n['x1'] - n['x0'] < W * 0.9})
rights = sorted({W - n['x1'] for n in named if n['x1'] - n['x0'] < W * 0.9})
print('\n[1] 左侧起点（去重，前 8 个）:', [(p, dp(p)) for p in lefts[:8]])
print('    右侧留白（去重，前 8 个）:', [(p, dp(p)) for p in rights[:8]])

# 2) 触控目标：可点节点小于 44dp 的
small = [n for n in nodes if n['clickable'] and (n['x1'] - n['x0']) < 44 * DP or (n['clickable'] and (n['y1'] - n['y0']) < 44 * DP)]
small = sorted(small, key=lambda n: (n['y1'] - n['y0']) * (n['x1'] - n['x0']))
print('\n[2] 触控目标 < 44dp 的可点节点（前 6 个）:')
for n in small[:6]:
    print('    %-12s %-20r %dx%d px = %.1fx%.1f dp' % (n['cls'], (n['text'] or n['desc'])[:18],
          n['x1'] - n['x0'], n['y1'] - n['y0'], dp(n['x1'] - n['x0']), dp(n['y1'] - n['y0'])))
if not small: print('    （无）')

# 3) 纵向节奏：同一列里相邻文本行的间隔
col = [n for n in named if n['x0'] < W * 0.5]
col.sort(key=lambda n: n['y0'])
print('\n[3] 纵向节奏（左列文本行的间隔，去重）:')
gaps = []
for a, b in zip(col, col[1:]):
    g = b['y0'] - a['y1']
    if g >= 0: gaps.append(g)
uniq = {}
for g in gaps: uniq[g] = uniq.get(g, 0) + 1
for g, c in sorted(uniq.items(), key=lambda kv: -kv[1])[:8]:
    print('    间隔 %4dpx = %5.1fdp  ×%d' % (g, dp(g), c))

# 4) 8pt 网格：主要间距是否落在 4/8 的倍数上
off_grid = [g for g in uniq if g > 0 and min(g % (4 * DP), (4 * DP) - g % (4 * DP)) > 1]
print('\n[4] 不在 4dp 网格上的间隔:', [(g, dp(g)) for g in sorted(off_grid)[:6]] or '（无）')
print('\n[5] 命名节点（前 24 个，按 y）:')
for n in sorted(named, key=lambda n: n['y0'])[:24]:
    print('    y=%-5d x=%-5d %-12s %r' % (n['y0'], n['x0'], n['cls'], (n['text'] or n['desc'])[:22]))
