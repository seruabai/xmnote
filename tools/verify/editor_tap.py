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
    # 同一行可能被拆成两条带（字形之间出现 1~2px 空行），不合并会让"第几行"整体错位一位
    merged = []
    for b in bands:
        if merged and b[0] - merged[-1][1] <= 4:
            merged[-1] = (merged[-1][0], b[1])
        else:
            merged.append(b)
    return merged


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
    """点正文里的某个块，点到它真的拿到输入焦点为止。

    x 取**目标块自己的**左边界 +40：列表块、有序/无序列表块和引用块的文字起点不一样
    （列表/引用前面有 28dp 标记槽），拿第一块的 x 去点别的块会点到标记槽上，点不中。
    """
    def target_x(nodes_fn, text):
        rows = block_rows(nodes_fn)
        row = next((n for n in rows if n.get('text') == text), None)
        return (row['x0'] + 40) if row else 540

    for attempt in range(3):
        hide_ime(sh)
        ns_now = nodes_fn()
        x = target_x(lambda: ns_now, text)
        row = next((n for n in block_rows(lambda: ns_now) if n.get('text') == text), None)
        if row is None:
            print('   [tap_text_field] 找不到 %s 这一块' % text, flush=True)
            return None
        # 候选 y：先试墨迹行带算出来的真实位置，再试 a11y 中心往下挪的各档。
        # 两个来源都可能出错（行带条数对不上时映射会整体错位；a11y 则整体偏高 ~46px），
        # 所以都列出来逐个试，靠 focused 校验挑中正确的那个。
        cands = []
        band_y = target_y(adb, serial, lambda: ns_now, text)
        if band_y:
            cands.append(band_y)
        center = (row['y0'] + row['y1']) // 2
        cands += [center + dy for dy in (0, 18, 36, 54, 72, 90)]
        tried = []
        for y in cands:
            if any(abs(y - t) <= 3 for t in tried):
                continue
            tried.append(y)
            sh('shell', 'input', 'tap', str(int(x)), str(int(y)))
            sleep(1.2)
            if target_focused(nodes_fn, text):
                if len(tried) > 1:
                    print('   [tap_text_field] %s 第 %d 个候选 y=%d 命中'
                          % (label or text, len(tried), y), flush=True)
                return y
        print('   [tap_text_field] %s 第 %d 轮 %d 个候选都没拿到焦点'
              % (label or text, attempt + 1, len(tried)), flush=True)
    print('   [tap_text_field] %s 都没拿到焦点' % (label or text), flush=True)
    return None
