# -*- coding: utf-8 -*-
"""主题验收：抓屏取像素，核对米黄纸主题（浅色/深色 + 侧栏）"""
import subprocess, re, sys, time, os
from PIL import Image
SERIAL=sys.argv[1] if len(sys.argv)>1 else 'emulator-5554'
ADB=r'C:/Android/sdk/platform-tools/adb.exe'; PKG='com.purenote.local'
OUT=r'E:\DSH\tmp\theme'
os.makedirs(OUT, exist_ok=True)
RES=[]
def sh(cmd, timeout=90):
    p=subprocess.run([ADB,'-s',SERIAL,'shell',cmd],capture_output=True,timeout=timeout)
    return (p.stdout+p.stderr).decode('utf-8','ignore')
def nodes():
    sh('uiautomator dump'); xml=sh('cat /sdcard/window_dump.xml')
    out=[]
    for n in re.findall(r'<node[^>]*>', xml):
        def a(k,n=n):
            m=re.search(k+'="([^"]*)"',n); return m.group(1) if m else ''
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a('bounds'))
        if not m: continue
        out.append(dict(cls=a('class'), text=a('text'), desc=a('desc'),
                        x0=int(m.group(1)), y0=int(m.group(2)), x1=int(m.group(3)), y1=int(m.group(4))))
    return out
def center(n): return (n['x0']+n['x1'])//2, (n['y0']+n['y1'])//2
def find(ns, key, val): return next((n for n in ns if n[key]==val), None)
def shot(name):
    p=os.path.join(OUT, name+'.png')
    with open(p,'wb') as f:
        f.write(subprocess.run([ADB,'-s',SERIAL,'exec-out','screencap','-p'],capture_output=True,timeout=90).stdout)
    return Image.open(p).convert('RGB')
def check(name, got, want, tol=8):
    ok = all(abs(g-w)<=tol for g,w in zip(got,want))
    RES.append((name, ok, got, want))
    print(('PASS' if ok else 'FAIL'), name, '样本=%s 期望=%s(±%d)' % (got, want, tol), flush=True)

PAPER=(0xFF,0xFA,0xF0); WHITE=(0xFF,0xFF,0xFF); ACCENT=(0xFF,0xB2,0x1D)
DARKBG=(0x17,0x14,0x0E)

print('== 浅色模式 ==')
sh('cmd uimode night no'); time.sleep(2)
sh('am force-stop '+PKG); sh('am start -n '+PKG+'/.MainActivity'); time.sleep(6)
ns=nodes()
home_bg = shot('01-home-light')
bg = home_bg.getpixel((20, 900))
print('   列表底色 =', bg)
check('浅色：首页底色 = 米黄纸 #FFFAF0', bg, PAPER)
# 卡片位置从界面树取，别猜坐标（第一次就猜到了卡片之间的空当上）
card_node = find(ns, 'text', '对齐检查') or find(ns, 'text', '反馈清单')
if card_node:
    px, py = card_node['x0'] + 25, card_node['y1'] + 35
    got = home_bg.getpixel((px, py))
    print('   卡片内像素(%d,%d) = %s' % (px, py, got))
    check('浅色：笔记卡片 = 白纸 #FFFFFF', got, WHITE, tol=12)
else:
    print('   （没找到笔记卡片节点）')

tab=find(ns,'text','待办')
if tab:
    sh('input tap %d %d' % center(tab)); time.sleep(2.5)
    todo = shot('02-todo-light')
    print('   待办底色 =', todo.getpixel((540, 420)))
    check('浅色：待办页底色 = 米黄纸', todo.getpixel((540, 420)), PAPER, tol=12)
gear=find(nodes(),'desc','设置')
if gear:
    sh('input tap %d %d' % center(gear)); time.sleep(2.5)
    st = shot('03-settings-light')
    print('   设置底色 =', st.getpixel((540, 1500)))
    check('浅色：设置页底色 = 米黄纸', st.getpixel((540, 1500)), PAPER, tol=12)

print('== 侧栏面板 ==')
sh('input keyevent 3'); time.sleep(2)
h=None
for line in sh('dumpsys input').splitlines():
    if "name='" in line and PKG in line and 'frame=[' in line:
        m=re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', line)
        if m:
            x0,y0,x1,y1=map(int,m.groups())
            if 0<x1-x0<200: h=(x0,y0,x1,y1); break
if h:
    sh('input tap %d %d' % ((h[0]+h[2])//2,(h[1]+h[3])//2)); time.sleep(3)
    pnl = shot('04-sidebar-light')
    # 面板里的一行待办卡片内部（避开文字）：从 dump 里找"对齐待办一"卡片范围
    ns3=nodes(); card=find(ns3,'text','对齐待办一')
    if card:
        y=(card['y0']+card['y1'])//2; x=card['x1']-30
        print('   面板卡片内像素 =', pnl.getpixel((x,y)))
        check('浅色：侧栏卡片 = 米黄纸', pnl.getpixel((x,y)), PAPER, tol=12)
    else:
        print('   （面板里没找到待办卡片）')

print('== 深色模式 ==')
sh('cmd uimode night yes'); time.sleep(3)
sh('am force-stop '+PKG); sh('am start -n '+PKG+'/.MainActivity'); time.sleep(6)
dk = shot('05-home-dark')
print('   深色首页底色 =', dk.getpixel((20, 900)))
check('深色：首页底色 = 暖黑 #17140E', dk.getpixel((20, 900)), DARKBG, tol=14)
sh('cmd uimode night no')

fails=[n for n,ok,_,_ in RES if not ok]
print('\n== 汇总: %d/%d PASS ==' % (len(RES)-len(fails), len(RES)))
for n in fails: print('FAIL ->', n)
print('截图目录：', OUT)
