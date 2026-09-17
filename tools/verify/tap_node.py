# -*- coding: utf-8 -*-
"""按内容描述/文本点一个节点（坐标取自界面树）。用法: tap_node.py <serial> desc|text <值> [等待秒]"""
import subprocess, re, sys, time
SERIAL = sys.argv[1]; FIELD = sys.argv[2]; VALUE = sys.argv[3]
WAIT = float(sys.argv[4]) if len(sys.argv) > 4 else 1.5
ADB = r'C:/Android/sdk/platform-tools/adb.exe'
def sh(cmd, timeout=90):
    p = subprocess.run([ADB, '-s', SERIAL, 'shell', cmd], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode('utf-8', 'ignore')
key = 'content-desc' if FIELD == 'desc' else 'text'
sh('uiautomator dump')
xml = sh('cat /sdcard/window_dump.xml')
for n in re.findall(r'<node[^>]*>', xml):
    m = re.search(key + '="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not m or not b or m.group(1) != VALUE:
        continue
    x0, y0, x1, y1 = map(int, b.groups())
    print('tap %s=%r @(%d,%d)' % (FIELD, VALUE, (x0 + x1) // 2, (y0 + y1) // 2))
    sh('input tap %d %d' % ((x0 + x1) // 2, (y0 + y1) // 2))
    time.sleep(WAIT)
    sys.exit(0)
print('未找到', FIELD, VALUE); sys.exit(1)
