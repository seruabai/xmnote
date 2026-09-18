# -*- coding: utf-8 -*-
"""编辑器里"点到真的文字上"的公共办法。

背景（2026-09-18 在 emulator-5554 / 5556 上实测，v1.2.23 与 v1.2.24 表现一致）：
编辑器正文块列表里，uiautomator 报的 EditText bounds 比**实际绘制位置**高约 46px
（Compose 语义坐标与绘制错位；标题行、日期行、勾选框的坐标都是准的，只有正文块行偏）。
照 a11y 坐标点会点在文字上方的空白处 —— 输入法不弹、'input text' 进不去、落库为空，
看起来和"键入坏了"一模一样。实测按真实墨迹位置点，输入框立刻拿到焦点（a11y 的
focused 变 true）、键盘弹出、字符正常落库（DB 里 AAA → AAA12）：
**这是测试取点的问题，不是应用回归。**

两个坑都在这里解决掉：
  ① a11y 坐标整体偏高 ~46px —— 用截图的"墨迹行带"定位真实文字，a11y 只用来定顺序；
  ② 焦点一落到某块，编辑器会把光标滚进可视区（键盘弹出后视口变矮），随后所有
     坐标都会失效 —— 所以每次取点前先把键盘收掉、现场重新截图。
判据用 a11y 的 focused 属性（比"输入法弹没弹"准：键盘可能因为上一次编辑还挂着）。
"""
import io
import subprocess


def ime_shown(sh):
    return 'mInputShown=true' in sh('shell', 'dumpsys', 'input_method')


def hide_ime(sh):
    """键盘弹着的时候先收掉：不收的话视口变矮、内容被滚过，坐标全错位。"""
    if ime_shown(sh):
        sh('shell', 'input', 'keyevent', '4')      # 键盘在前台时 BACK 只收键盘
        return True
    return False


def _shot(adb, serial):
    return subprocess.run([adb, '-s', serial, 'exec-out', 'screencap', '-p'],
                          capture_output=True, timeout=60).stdout


def ink_bands(adb, serial, x_from=40, x_to=1040, y_from=250, y_to=2200,
              thresh=140, min_dark=2, step=4):
    """截图里"有墨迹"的横向行带 —— 真实文字的位置（上界, 下界）。"""
    from PIL import Image
    im = Image.open(io.BytesIO(_shot(adb, serial))).convert('L')
    px = im.load()
    bands, inside, start = [], False, 0
    for y in range(y_from, min(y_to, im.size[1])):
        dark = sum(1 for x in range(x_from, x_to, step) if px[x, y] < thresh)
        if dark > min_dark and not inside:
            start, inside = y, True
        elif dark <= min_dark and inside:
            bands.append((start, y - 1))
            inside = False
    if inside:
        bands.append((start, min(y_to, im.size[1]) - 1))
    return bands


def target_focused(nodes_fn, text):
    for n in nodes_fn():
        if 'EditText' in (n.get('cls') or '') and n.get('text') == text:
            return str(n.get('focused')) == 'true'
    return False


def block_rows(nodes_fn):
    """正文块行（a11y 顺序）：第一个 EditText 是标题，其余按 y 排序就是各块。"""
    eds = [n for n in nodes_fn() if 'EditText' in (n.get('cls') or '')]
    eds.sort(key=lambda n: n['y0'])
    return eds[1:] if len(eds) > 1 else []


def target_y(adb, serial, nodes_fn, text, fallback_dy=46):
    """目标块的点击 y：优先用截图墨迹行带的中心，取不到再退回 a11y + 经验偏移。"""
    rows = block_rows(nodes_fn)
    idx = next((i for i, n in enumerate(rows) if n.get('text') == text), None)
    if idx is None:
        return None
    bands = ink_bands(adb, serial)
    lo, hi = rows[0]['y0'] - 12, rows[-1]['y1'] + 110
    cand = [b for b in bands if lo <= b[0] <= hi]
    if len(cand) == len(rows):
        return (cand[idx][0] + cand[idx][1]) // 2
    return (rows[idx]['y0'] + rows[idx]['y1']) // 2 + fallback_dy


def tap_text_field(sh, adb, serial, nodes_fn, text, sleep, label=''):
    """点正文里的某个块，点到它真的拿到输入焦点为止。"""
    x = (block_rows(nodes_fn)[0]['x0'] + 40) if block_rows(nodes_fn) else 540
    for attempt in range(3):
        hide_ime(sh)
        y = target_y(adb, serial, nodes_fn, text)
        if y is None:
            print('   [tap_text_field] 找不到 %s 这一块' % text, flush=True)
            return None
        sh('shell', 'input', 'tap', str(int(x)), str(int(y)))
        sleep(1.2)
        if target_focused(nodes_fn, text):
            return y
        print('   [tap_text_field] %s 第 %d 次点 y=%d 没中，重取坐标再来'
              % (label or text, attempt + 1, y), flush=True)
    print('   [tap_text_field] %s 三次都没拿到焦点' % (label or text), flush=True)
    return None
