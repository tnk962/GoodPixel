"""Build GoodPixel user manual PDF with reportlab and Arial Unicode.
"""
from pathlib import Path
import re
from html import escape
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Image, KeepTogether
from reportlab.lib.pagesizes import A4

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'docs/20261006_goodpixel-user-manual.md'
OUT_DIR = ROOT / 'output/pdf'
OUT_DIR.mkdir(parents=True, exist_ok=True)
OUT = OUT_DIR / '20261006_goodpixel-user-manual.pdf'

pdfmetrics.registerFont(TTFont('JP', '/Library/Fonts/Arial Unicode.ttf'))
pdfmetrics.registerFontFamily('JP', normal='JP', bold='JP', italic='JP', boldItalic='JP')

INK = colors.HexColor('#203347')
ACCENT = colors.HexColor('#1A73E8') # Google Blue

styles = {
    'body': ParagraphStyle('body', fontName='JP', fontSize=9.5, leading=15, wordWrap='CJK', textColor=INK, spaceAfter=6),
    'bullet': ParagraphStyle('bullet', fontName='JP', fontSize=9.5, leading=15, wordWrap='CJK', textColor=INK, spaceAfter=4, leftIndent=12),
    'h1': ParagraphStyle('h1', fontName='JP', fontSize=24, leading=34, textColor=INK, spaceAfter=14),
    'h2': ParagraphStyle('h2', fontName='JP', fontSize=16, leading=24, textColor=ACCENT, spaceBefore=14, spaceAfter=10, keepWithNext=True),
    'h3': ParagraphStyle('h3', fontName='JP', fontSize=11.5, leading=18, textColor=ACCENT, spaceBefore=10, spaceAfter=5, keepWithNext=True),
    'caption': ParagraphStyle('caption', fontName='JP', fontSize=8, leading=12, wordWrap='CJK', textColor=INK, alignment=TA_CENTER, spaceAfter=8),
    'cell': ParagraphStyle('cell', fontName='JP', fontSize=8, leading=12, wordWrap='CJK', textColor=INK),
    'cell_header': ParagraphStyle('cell_header', fontName='JP', fontSize=8.5, leading=13, wordWrap='CJK', textColor=colors.white),
}

EMOJI_FALLBACK = {
    '▶': '▶',
    '⏸': '[停止]',
    '⏯': '[再生/停止]',
    '⏭': '[次へ]',
    '📶': '[Wi-Fi]',
    '🛜': '[Wi-Fi]',
    'ᛒ': '[BT]',
    '🔊': '[音量]',
    '🔇': '[消音]',
    '🔕': '[サイレント]',
    '🔄': '[回転]',
    '📹': '[録画]',
    '🎥': '[録画]',
    '✂️': '[切抜]',
    '✂': '[切抜]',
    '📸': '[スクショ]',
    '🔦': '[ライト]',
    '📜': '[ログ]',
    '⚙️': '[設定]',
    '⚙': '[設定]',
    '🔒': '[ロック]',
    '⚏': '[分割]',
    '⏻': '[電源]',
    '🗑️': '[ゴミ箱]',
    '🗑': '[ゴミ箱]',
    '🔔': '[通知]',
    '📦': '[更新]',
    '⚡': '[ツール]',
    '☀️': '[輝度]',
    '✕': '✕',
    '✓': '✓',
}

font_obj = TTFont('JP', '/Library/Fonts/Arial Unicode.ttf')
supported_glyphs = font_obj.face.charToGlyph

def sanitize_text(s):
    for em, rep in EMOJI_FALLBACK.items():
        s = s.replace(em, rep)
    # Filter out any character not in Arial Unicode to prevent tofu boxes
    out = []
    for ch in s:
        if ord(ch) <= 127 or ord(ch) in supported_glyphs:
            out.append(ch)
    return ''.join(out)

def rich(s):
    s = sanitize_text(s)
    s = escape(s)
    s = re.sub(r'\[([^\]]+)\]\((https?://[^)]+)\)', r'<link href="\2" color="#1A73E8">\1</link>', s)
    s = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<b>\1</b>', s) # internal links
    s = re.sub(r'`([^`]+)`', r'\1', s)
    return re.sub(r'\*\*([^*]+)\*\*', r'<b>\1</b>', s)

def p(s, style='body'):
    return Paragraph(rich(s), styles[style])

def footer(c, doc):
    c.setStrokeColor(colors.HexColor('#DCE5EC'))
    c.line(42, 36, A4[0]-42, 36)
    c.setFont('JP', 8)
    c.setFillColor(INK)
    c.drawString(42, 22, 'GoodPixel 操作マニュアル | v1.3.0 / Build 4')
    c.drawRightString(A4[0]-42, 22, f'{doc.page}')

story = []
lines = SOURCE.read_text().splitlines()
i = 0
in_toc = False

while i < len(lines):
    line = lines[i].strip()
    
    if not line:
        i += 1
        continue
        
    if line.startswith('*図') and line.endswith('*'):
        # Caption already processed or handled with image
        i += 1
        continue
        
    if line.startswith('[PDF版をダウンロード]'):
        i += 1
        continue
        
    if line.startswith('---'):
        story.append(Spacer(1, 8))
        i += 1
        continue
        
    if line.startswith('# '):
        story.append(p(line[2:], 'h1'))
        i += 1
        continue
        
    if line.startswith('## 目次'):
        in_toc = True
        story.append(p('目次', 'h2'))
        i += 1
        continue
        
    if in_toc:
        if line.startswith('## '):
            in_toc = False
        else:
            story.append(p(line, 'bullet'))
            i += 1
            continue
            
    if line.startswith('## '):
        story.append(p(line[3:], 'h2'))
        i += 1
        continue
        
    if line.startswith('### '):
        story.append(p(line[4:], 'h3'))
        i += 1
        continue
        
    if line.startswith('![図'):
        m = re.match(r'!\[([^\]]+)\]\(([^)]+)\)', line)
        if m:
            caption, rel_path = m.group(1), m.group(2)
            img_path = ROOT / 'docs' / rel_path
            if img_path.exists():
                # Fit image nicely into A4 width (max width 450pt)
                try:
                    import subprocess
                    info = subprocess.run(['sips', '-g', 'pixelWidth', '-g', 'pixelHeight', str(img_path)], capture_output=True, text=True)
                    pw = int(re.search(r'pixelWidth: (\d+)', info.stdout).group(1))
                    ph = int(re.search(r'pixelHeight: (\d+)', info.stdout).group(1))
                    max_w = 420
                    max_h = 240
                    ratio = min(max_w / pw, max_h / ph)
                    w = pw * ratio
                    h = ph * ratio
                    img = Image(str(img_path), width=w, height=h)
                    story.append(KeepTogether([
                        img,
                        Spacer(1, 4),
                        Paragraph(rich(caption), styles['caption'])
                    ]))
                except Exception as e:
                    print(f"Error sizing image {img_path}: {e}")
        i += 1
        continue

    # Tables
    if line.startswith('|') and '|' in line[1:]:
        table_lines = [line]
        i += 1
        while i < len(lines) and lines[i].strip().startswith('|'):
            table_lines.append(lines[i].strip())
            i += 1
            
        header = [c.strip() for c in table_lines[0].split('|')[1:-1]]
        data = [[Paragraph(rich(c), styles['cell_header']) for c in header]]
        
        for tl in table_lines[2:]: # skip separator
            cols = [c.strip() for c in tl.split('|')[1:-1]]
            row = [Paragraph(rich(c), styles['cell']) for c in cols]
            data.append(row)
            
        col_count = len(header)
        table_width = A4[0] - 84
        col_w = table_width / col_count
        
        # Adjust column widths for specific tables
        col_widths = [col_w] * col_count
        if col_count == 3:
            col_widths = [table_width * 0.25, table_width * 0.25, table_width * 0.5]
            
        t = Table(data, colWidths=col_widths)
        t.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), ACCENT),
            ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
            ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor('#DCE5EC')),
            ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#F8F9FA')]),
            ('TOPPADDING', (0, 0), (-1, -1), 5),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 5),
        ]))
        story.append(Spacer(1, 6))
        story.append(t)
        story.append(Spacer(1, 8))
        continue

    # Warning / Alert blockquotes
    if line.startswith('> [!WARNING]') or line.startswith('> [!IMPORTANT]'):
        alert_lines = []
        i += 1
        while i < len(lines):
            l = lines[i].strip()
            if l.startswith('>'):
                content = l[1:].strip()
                alert_lines.append(content)
                i += 1
            elif not l:
                # Check if blockquote continues on next non-empty line
                next_non_empty = i + 1
                while next_non_empty < len(lines) and not lines[next_non_empty].strip():
                    next_non_empty += 1
                if next_non_empty < len(lines) and lines[next_non_empty].strip().startswith('>'):
                    alert_lines.append('')
                    i += 1
                else:
                    break
            else:
                break

        flowables = []
        for al in alert_lines:
            if not al:
                flowables.append(Spacer(1, 3))
            elif al.startswith('### '):
                flowables.append(Paragraph(rich(al[4:]), ParagraphStyle('alert_title', fontName='JP', fontSize=10.5, leading=15, textColor=colors.HexColor('#B45309'), spaceAfter=4)))
            elif al.startswith('• ') or al.startswith('- '):
                flowables.append(Paragraph(rich('• ' + al[2:]), ParagraphStyle('alert_bullet', fontName='JP', fontSize=8.5, leading=13, wordWrap='CJK', textColor=INK, spaceAfter=2, leftIndent=8)))
            elif re.match(r'^\d+\.\s+', al):
                flowables.append(Paragraph(rich(al), ParagraphStyle('alert_num', fontName='JP', fontSize=8.5, leading=13, wordWrap='CJK', textColor=INK, spaceAfter=2, leftIndent=8)))
            else:
                flowables.append(Paragraph(rich(al), ParagraphStyle('alert_body', fontName='JP', fontSize=8.5, leading=13, wordWrap='CJK', textColor=INK, spaceAfter=3)))

        table_width = A4[0] - 84
        alert_table = Table([[flowables]], colWidths=[table_width])
        alert_table.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#FFFBEB')),
            ('LINEBEFORE', (0, 0), (0, -1), 4, colors.HexColor('#F59E0B')),
            ('BOX', (0, 0), (-1, -1), 0.5, colors.HexColor('#FCD34D')),
            ('TOPPADDING', (0, 0), (-1, -1), 8),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 8),
            ('LEFTPADDING', (0, 0), (-1, -1), 12),
            ('RIGHTPADDING', (0, 0), (-1, -1), 12),
        ]))
        story.append(Spacer(1, 6))
        story.append(alert_table)
        story.append(Spacer(1, 8))
        continue

    # Normal Blockquotes
    if line.startswith('> '):
        story.append(p(line[2:], 'body'))
        i += 1
        continue
        
    # Bullet points
    if line.startswith('- ') or line.startswith('* '):
        story.append(p('• ' + line[2:], 'bullet'))
        i += 1
        continue

    # Numbered lists
    m_num = re.match(r'^(\d+)\.\s+(.*)', line)
    if m_num:
        story.append(p(f"{m_num.group(1)}. {m_num.group(2)}", 'bullet'))
        i += 1
        continue

    # Normal paragraph
    story.append(p(line, 'body'))
    i += 1

doc = SimpleDocTemplate(
    str(OUT),
    pagesize=A4,
    leftMargin=42,
    rightMargin=42,
    topMargin=42,
    bottomMargin=48
)

doc.build(story, onFirstPage=footer, onLaterPages=footer)
print(f"Manual PDF successfully generated at: {OUT}")
