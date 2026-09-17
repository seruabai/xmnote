# -*- coding: utf-8 -*-
"""打开首页第一张笔记卡片（供排版审计用），坐标全部来自界面树，不猜。"""
import subprocess, re, sys, time
SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
def sh(cmd, timeout=90):
    p = subprocess.run([ADB, '-s', SERIAL, 'shell', cmd], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode('utf-8', 'ignore')
sh('am force-stop com.purenote.local')
sh('am start -n com.purenote.local/.MainActivity')
time.sleep(6)
sh('uiautomator dump')
xml = sh('cat /sdcard/window_dump.xml')
best = None
for n in re.findall(r'<node[^>]*>', xml):
    def a(k, n=n):
        m = re.search(k + '="([^"]*)"', n); return m.group(1) if m else ''
    if not a('text'): continue
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
    if not m: continue
    x0, y0, x1, y1 = map(int, m.groups())
    if y0 > 780 and x0 < 500 and a('class').endswith('TextView'):
        best = (a('text'), (x0 + x1) // 2, (y0 + y1) // 2); break
print('点开卡片:', best[0][:16] if best else None)
if best:
    sh('input tap %d %d' % (best[1], best[2]))
    time.sleep(3)
    sh('uiautomator dump')
    x2 = sh('cat /sdcard/window_dump.xml')
    print('编辑器已打开 =', '返回' in x2)
