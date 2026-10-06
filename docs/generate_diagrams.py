"""Generate diagram images for GoodPixel user manual.
"""
from pathlib import Path
import subprocess
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / 'docs/20261006_manual-images'
TEMP = ROOT / 'tmp/pdfs'
TARGET.mkdir(parents=True, exist_ok=True)
TEMP.mkdir(parents=True, exist_ok=True)

pdfmetrics.registerFont(TTFont('JP', '/Library/Fonts/Arial Unicode.ttf'))

INK = colors.HexColor('#203347')
ACCENT = colors.HexColor('#1A73E8') # Google Blue
GREEN = colors.HexColor('#1E8E3E')
BG = colors.HexColor('#F8F9FA')
WHITE = colors.white

def make_gesture_diagram():
    path = TEMP / 'gesture-map.pdf'
    c = canvas.Canvas(str(path), pagesize=(1000, 500))
    c.setFillColor(BG); c.rect(0, 0, 1000, 500, fill=1, stroke=0)
    
    # Title
    c.setFont('JP', 20); c.setFillColor(INK)
    c.drawString(40, 455, 'GoodPixel 画面端ジェスチャー割り当てマップ（左右共通）')
    
    # Device frame in center
    dev_x, dev_y, dev_w, dev_h = 360, 40, 280, 390
    c.setStrokeColor(colors.HexColor('#BDC1C6')); c.setLineWidth(3)
    c.setFillColor(colors.HexColor('#202124'))
    c.roundRect(dev_x, dev_y, dev_w, dev_h, 24, fill=1, stroke=1)
    
    # Screen area
    c.setFillColor(colors.HexColor('#303134'))
    c.roundRect(dev_x+10, dev_y+15, dev_w-20, dev_h-30, 16, fill=1, stroke=0)
    
    # Handles (Blue on edges)
    c.setFillColor(ACCENT)
    c.roundRect(dev_x+10, dev_y+130, 10, 140, 4, fill=1, stroke=0) # Left handle
    c.roundRect(dev_x+dev_w-20, dev_y+130, 10, 140, 4, fill=1, stroke=0) # Right handle
    
    # Edge tab (Upper right)
    c.setFillColor(colors.HexColor('#FFFFFF'))
    c.roundRect(dev_x+dev_w-18, dev_y+290, 8, 45, 3, fill=1, stroke=0)
    
    # Labels inside device
    c.setFont('JP', 12); c.setFillColor(WHITE)
    c.drawCentredString(dev_x + dev_w/2, dev_y + 200, 'Pixel 11 Pro 画面')
    c.setFont('JP', 9); c.setFillColor(colors.HexColor('#9AA0A6'))
    c.drawCentredString(dev_x + dev_w/2, dev_y + 175, '両端の青い領域がタッチハンドル')
    c.drawCentredString(dev_x + dev_w/2, dev_y + 155, '（通常時は完全透明）')
    
    # Left actions
    c.setFont('JP', 13); c.setFillColor(INK)
    actions = [
        ('↗ 斜め上スワイプ', '全画面スクリーンショット', dev_y + 300),
        ('→ 水平スワイプ', '戻る (Back)', dev_y + 225),
        ('↘ 斜め下スワイプ', 'スマート選択 (SmartCapture)', dev_y + 150),
        ('長押し + 水平', 'クイックツール (16タイル)', dev_y + 75),
    ]
    for trig, act, y in actions:
        # Left side
        c.drawString(40, y + 10, trig)
        c.setFont('JP', 11); c.setFillColor(ACCENT)
        c.drawString(40, y - 8, act)
        c.setFont('JP', 13); c.setFillColor(INK)
        # Arrow to handle
        c.setStrokeColor(ACCENT); c.setLineWidth(1.5)
        c.line(260, y + 2, dev_x + 8, dev_y + 200)

    # Right actions
    right_actions = [
        ('↖ 斜め上スワイプ', '全画面スクリーンショット', dev_y + 300),
        ('← 水平スワイプ', '戻る (Back)', dev_y + 225),
        ('↙ 斜め下スワイプ', 'スマート選択 (SmartCapture)', dev_y + 150),
        ('長押し + 斜め下', '画面自動回転 ON/OFF', dev_y + 75),
    ]
    for trig, act, y in right_actions:
        c.drawString(700, y + 10, trig)
        c.setFont('JP', 11); c.setFillColor(ACCENT)
        c.drawString(700, y - 8, act)
        c.setFont('JP', 13); c.setFillColor(INK)
        c.setStrokeColor(ACCENT); c.setLineWidth(1.5)
        c.line(dev_x + dev_w - 8, dev_y + 200, 680, y + 2)
        
    c.save()
    subprocess.run(['pdftoppm', '-singlefile', '-scale-to', '1200', '-png', str(path), str(TARGET / '06_gesture_map')], check=True)

def make_navbar_diagram():
    path = TEMP / 'navbar-mask.pdf'
    c = canvas.Canvas(str(path), pagesize=(1000, 420))
    c.setFillColor(BG); c.rect(0, 0, 1000, 420, fill=1, stroke=0)
    
    c.setFont('JP', 20); c.setFillColor(INK)
    c.drawString(40, 375, '画面下のバー（切り替えピル）非表示の仕組み')
    
    # Left: Normal Pixel
    lx = 100; ly = 50; w = 340; h = 280
    c.setFillColor(colors.HexColor('#202124')); c.roundRect(lx, ly, w, h, 14, fill=1, stroke=0)
    c.setFillColor(WHITE); c.setFont('JP', 14); c.drawCentredString(lx + w/2, ly + h - 35, '通常時のPixel')
    c.setFont('JP', 11); c.setFillColor(colors.HexColor('#BDC1C6'))
    c.drawCentredString(lx + w/2, ly + h - 60, 'アプリ画面の下に白い横棒が常時表示')
    # White pill
    c.setFillColor(WHITE); c.roundRect(lx + w/2 - 50, ly + 25, 100, 6, 3, fill=1, stroke=0)
    c.setFont('JP', 10); c.setFillColor(colors.HexColor('#EA4335'))
    c.drawCentredString(lx + w/2, ly + 45, '▲ 目障りな白いバー（切り替えピル）')

    # Right: GoodPixel Masked
    rx = 560; ry = 50
    c.setFillColor(colors.HexColor('#202124')); c.roundRect(rx, ry, w, h, 14, fill=1, stroke=0)
    c.setFillColor(WHITE); c.setFont('JP', 14); c.drawCentredString(rx + w/2, ry + h - 35, 'GoodPixel 適用時 (NavStar風)')
    c.setFont('JP', 11); c.setFillColor(colors.HexColor('#BDC1C6'))
    c.drawCentredString(rx + w/2, ry + h - 60, '最前面透過マスクで白いバーのみ消去')
    # Black mask
    c.setFillColor(colors.HexColor('#121212')); c.rect(rx + 5, ry + 5, w - 10, 45, fill=1, stroke=0)
    c.setFont('JP', 11); c.setFillColor(GREEN)
    c.drawCentredString(rx + w/2, ry + 25, '✓ バーが消えて全画面スッキリ！')
    
    # Swipe arrow on right
    c.setStrokeColor(ACCENT); c.setLineWidth(3)
    c.line(rx + w/2, ry + 10, rx + w/2, ry + 70)
    c.setFont('JP', 10); c.setFillColor(ACCENT)
    c.drawCentredString(rx + w/2 + 75, ry + 40, '↑ タッチ完全透過（操作性100%維持）')
    
    c.save()
    subprocess.run(['pdftoppm', '-singlefile', '-scale-to', '1200', '-png', str(path), str(TARGET / '07_navbar_mask')], check=True)

make_gesture_diagram()
make_navbar_diagram()
print("Diagrams generated successfully!")
