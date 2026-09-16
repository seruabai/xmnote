# -*- coding: utf-8 -*-
"""一键跑完全部设备验收，并按机型出报告。

    python tools/verify/run_all.py <serial>

为什么要有它：验收要换机型重复跑（小米 12S → 后续其它机型）。每个脚本自己都支持
串口参数，这里统一编排、收集汇总行、把机型信息与结果一起落到 logs/device-report-*.json，
方便横向对比"这次是哪台、哪几条没过"。

脚本清单（全部只走直连：run-as sqlite3 / uiautomator dump / dumpsys / logcat，不用截图）：
    block_toolbar_verify.py  样式键是否作用在光标所在块（含 API<35 的工具栏显隐）
    block_edit_verify.py     块内打字 / 回车拆块 / 段首退格并块 / 勾选框
    block_drag_verify.py     拖拽口径：文本块不可拖、图片块可拖
    mind_verify.py           脑图：几何 / 加子级 / 换父 / 折叠 / 大纲 / 新建入口
    scale_verify.py          大规模矩阵（A-F 六段）
"""
import subprocess, re, sys, os, json, time

ADB = r'C:/Android/sdk/platform-tools/adb.exe'
HERE = os.path.dirname(os.path.abspath(__file__))
PKG = 'com.purenote.local'

SUITES = [
    ('block_toolbar_verify.py', '工具栏·光标块'),
    ('block_edit_verify.py', '块编辑链路'),
    ('block_drag_verify.py', '拖拽口径'),
    ('mind_verify.py', '脑图'),
    ('scale_verify.py', '规模矩阵'),
    ('feedback_verify.py', '反馈回归'),
]

def sh(serial, *a, timeout=120):
    return subprocess.run([ADB, '-s', serial] + [str(x) for x in a],
                          capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore').strip()

def device_facts(serial):
    return {
        'serial': serial,
        'model': sh(serial, 'shell', 'getprop', 'ro.product.model'),
        'brand': sh(serial, 'shell', 'getprop', 'ro.product.brand'),
        'release': sh(serial, 'shell', 'getprop', 'ro.build.version.release'),
        'sdk': sh(serial, 'shell', 'getprop', 'ro.build.version.sdk'),
        'miui': sh(serial, 'shell', 'getprop', 'ro.miui.ui.version.name'),
        'size': sh(serial, 'shell', 'wm', 'size').split(':')[-1].strip(),
        'density': sh(serial, 'shell', 'wm', 'density').split(':')[-1].strip(),
    }

def main():
    if len(sys.argv) < 2:
        print('用法: python run_all.py <serial>'); sys.exit(2)
    serial = sys.argv[1]
    only = sys.argv[2:] or None
    facts = device_facts(serial)
    print('设备: %s %s / Android %s (API %s) / %s / %s @ %s' % (
        facts['brand'], facts['model'], facts['release'], facts['sdk'],
        facts['miui'] or '-', facts['size'], facts['density']), flush=True)
    installed = PKG in sh(serial, 'shell', 'pm', 'list', 'packages')
    print('已安装 %s: %s' % (PKG, installed), flush=True)
    if not installed:
        print('未安装：先 adb -s %s install -r -t app-debug.apk' % serial, flush=True)
        sys.exit(2)

    report = {'device': facts, 'suites': [], 'startedAt': int(time.time() * 1000)}
    for script, label in SUITES:
        if only and script not in only:
            continue
        path = os.path.join(HERE, script)
        t0 = time.time()
        print('\n=== %s (%s) ===' % (label, script), flush=True)
        r = subprocess.run([sys.executable, path, serial], capture_output=True, timeout=2400)
        out = r.stdout.decode('utf-8', 'ignore')
        err = r.stderr.decode('utf-8', 'ignore')
        tail = [l for l in out.splitlines() if l.startswith(('PASS', 'FAIL', 'SKIP'))]
        summary = next((l for l in out.splitlines() if l.startswith('== 汇总')), '(无汇总行)')
        print(summary, flush=True)
        for l in tail:
            if l.startswith('FAIL'):
                print('   ', l, flush=True)
        if err.strip():
            print('    stderr:', err.strip()[-400:], flush=True)
        report['suites'].append({
            'script': script, 'label': label, 'exit': r.returncode,
            'summary': summary, 'seconds': round(time.time() - t0, 1),
            'failures': [l for l in tail if l.startswith('FAIL')],
            'checks': len(tail),
        })
    report['finishedAt'] = int(time.time() * 1000)

    name = 'device-report-%s-%s' % (facts['model'] or 'unknown', serial)
    out_path = 'E:/DSH/logs/%s.json' % name
    with open(out_path, 'w', encoding='utf-8') as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)
    print('\n===== 总览（%s %s / Android %s）=====' % (facts['brand'], facts['model'], facts['release']))
    total_fail = 0
    for s in report['suites']:
        total_fail += len(s['failures'])
        print('  %-12s %-28s %s' % (s['label'], s['summary'], '%.0fs' % s['seconds']))
    print('  失败合计: %d' % total_fail)
    print('  机型报告:', out_path)
    sys.exit(1 if total_fail else 0)

if __name__ == '__main__':
    main()
