import re

xml = open(r'C:/Users/sun/AppData/Local/Temp/ns.xml', encoding='utf-8').read()
D = 2.625
W = 1080 / D  # 411.43dp

nodes = []
for n in re.findall(r'<node[^>]*>', xml):
    def a(k):
        m = re.search(k + r'="([^"]*)"', n)
        return m.group(1) if m else ''
    b = a('bounds')
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    if not m: continue
    x0, y0, x1, y1 = map(int, m.groups())
    nodes.append(dict(text=a('text'), desc=a('desc'), clickable=a('clickable'), cls=a('class'),
                      x0=x0, y0=y0, x1=x1, y1=y1, w=x1-x0, h=y1-y0))

def dp(px): return round(px / D, 2)

fails = []
def check(name, got, want, tol=0.6):
    ok = abs(got - want) <= tol
    if not ok: fails.append(name)
    print(f"{'PASS' if ok else 'FAIL'}  {name}: got {got}dp, want {want}dp (tol {tol})")

# 标题与内容同一栅格
t_title = [t for t in nodes if t['text'] == '笔记' and t['y0'] < 500]
check('大标题左缘=16', dp(min(t['x0'] for t in t_title)), 16.0, 0.8)

# 搜索框 pill:取其内部 EditText,左缘-17(行内边距)-27(图标)-12(间隙)=pill左缘
edit = [t for t in nodes if t['cls'].endswith('EditText')]
if edit:
    pill_left = dp(edit[0]['x0']) - 17 - 27 - 12
    check('搜索pill左缘=16', pill_left, 16.0, 0.8)

# 分类条首个 chip 容器
chips = sorted([t for t in nodes if t['clickable'] == 'true' and 230 < dp(t['y0']) < 260], key=lambda t: t['x0'])
if chips:
    # chip 视觉宽 = 容器宽-0(touch 48dp 只纵向扩展?横向按需) — 直接取容器左缘即可(横向无扩展时=视觉)
    check('分类条左缘=16', dp(chips[0]['x0']), 16.0, 0.8)

# 卡片(列表可点击大容器,排除底栏区域 y0<800)
cards = [t for t in nodes if t['clickable'] == 'true' and dp(t['w']) > 100 and dp(t['h']) > 60 and 280 < dp(t['y0']) < 800]
if cards:
    check('卡片左缘=16', dp(min(c['x0'] for c in cards)), 16.0, 0.8)
    check('卡片右缘=395.4(屏宽-16)', dp(max(c['x1'] for c in cards)), W - 16, 0.8)

# 顶栏图标视觉右缘 = min(屏宽, 触控容器右缘) - (48-32)/2
gear = [t for t in nodes if t['clickable'] == 'true' and dp(t['y0']) < 100 and dp(t['x1']) > 380]
if gear:
    visual_right = min(1080, max(g['x1'] for g in gear)) / D - 8
    check('顶栏图标视觉右缘=395.4', visual_right, W - 16, 1.0)

# 瀑布流列间隙
lcol = [c for c in cards if dp(c['x0']) < W/2]
rcol = [c for c in cards if dp(c['x0']) > W/2]
if lcol and rcol:
    gap = dp(min(c['x0'] for c in rcol) - max(c['x1'] for c in lcol))
    check('瀑布流列间隙=10', gap, 10.0, 0.8)

print()
print('FAILS:', len(fails), fails if fails else '- 全部通过')
