# -*- coding: utf-8 -*-
"""字体对齐设备像素断言:占位符行盒高度必须等于字段 textStyle 的 lineHeight。
纪律:占位符与字段共用完整样式 ⇒ 光标/文字/占位三者等高(构造性对齐)。"""
import subprocess, re, time

ADB = r'C:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.purenote.local'
D = 2.625  # 420dpi
RESULTS = []

def sh(*a, timeout=40):
    return subprocess.run([ADB] + [str(x) for x in a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'ignore')

def dump():
    sh('shell', 'uiautomator', 'dump')
    return sh('shell', 'cat', '/sdcard/window_dump.xml')

def text_h(xml, t):
    m = re.search(r'text="' + re.escape(t) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        # 属性顺序兜底
        for n in re.findall(r'<node[^>]*>', xml):
            if ('text="' + t + '"') in n:
                m2 = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if m2:
                    x0, y0, x1, y1 = map(int, m2.groups())
                    return y1 - y0
        return None
    return int(m.group(4)) - int(m.group(2))

def check(name, got, want, tol):
    ok = got is not None and abs(got - want) <= tol
    RESULTS.append(ok)
    print(('PASS' if ok else 'FAIL'), name, f'| 实测{got}px 期望{want}px(±{tol})', flush=True)

# ---- 1. 首页搜索框占位:17sp/24sp → 63px ----
sh('shell', 'input', 'keyevent', '3'); time.sleep(1)
sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity'); time.sleep(5)
sh('shell', 'input', 'tap', '810', '2215'); time.sleep(1.5)   # 确保在笔记标签
sh('shell', 'input', 'tap', '270', '2215'); time.sleep(2)
check('搜索框占位行盒=24sp(63px)', text_h(dump(), '搜索笔记'), round(24 * D), 8)

# ---- 2. 编辑器正文占位:18sp/28sp → 73.5px(打开验收笔记?不,需空正文:新建) ----
# 新建笔记:点 FAB
fab = None
for _ in range(3):
    xml = dump()
    m = re.search(r'content-desc="添加"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        fab = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
        break
    time.sleep(1)
if fab:
    sh('shell', 'input', 'tap', str((fab[0]+fab[2])//2), str((fab[1]+fab[3])//2))
    time.sleep(2.5)
    check('编辑器标题占位行盒=38sp(标题31/38→100px)', text_h(dump(), '标题'), round(38 * D), 14)
    check('编辑器正文占位行盒=28sp(74px)', text_h(dump(), '开始书写或'), round(28 * D), 8)
    # ---- 3. 清单占位:切到清单?新建笔记 kind=TEXT,无法直接切;改用既有清单类待办面板跳过 ----
    sh('shell', 'input', 'keyevent', '4')  # 不保存退出
    time.sleep(1.5)

fails = RESULTS.count(False)
print()
print(f'== 字体对齐: {len(RESULTS)-fails}/{len(RESULTS)} PASS ==')
