"""오운복 브랜드 자산 생성기 (마크·워드마크·락업·앱 아이콘). 사람이 정한 기하로 다시 그린 벡터다.

마크 '고리 속 한 순간' — 기준: 바깥 반지름 R=100
  고리: 바깥 100, 안쪽 63(두께 37), 틈 40°(28°~68°, 수학 좌표 반시계 기준), 양 끝은 반지름 방향으로 평평하게 자름
  점  : 틈 한가운데(48°) 반지름 86 위치, 점 반지름 16  → "되감다 멈춘 한 순간"
  비율은 ChatGPT 시안 01(2026-10-25)을 측정한 값(바깥 183.1px, 두께 67.7px, 틈 28.5°~69.5°, 점 160.5px·r29.8px)을 정리한 것.
워드마크: Pretendard Black(OFL 1.1) '오운복' 윤곽선, 부제 Pretendard Medium '오늘의 운전 복기'.
출력: docs/store/brand/*.svg, *.png, app/src/main/res/drawable/ic_launcher_foreground.xml
"""
import math, os
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
APP = OUT.parents[2] / "app" / "src" / "main" / "res" / "drawable"
FONT_DIR = Path(r"C:\data\fonts\pretendard_dl\x\public\static")
BLACK, MEDIUM = FONT_DIR / "Pretendard-Black.otf", FONT_DIR / "Pretendard-Medium.otf"
BLUE, INK, PAPER, NIGHT = "#0064FF", "#131417", "#F7F8FA", "#131417"
R_OUT, R_IN, GAP0, GAP1, DOT_R, DOT_D, DOT_A = 100.0, 63.0, 28.0, 68.0, 16.0, 86.0, 48.0

def pt(cx, cy, r, deg):  # 수학 각도(반시계) → SVG 좌표(y 아래)
    a = math.radians(deg); return cx + r * math.cos(a), cy - r * math.sin(a)

def ring_path(cx, cy, s):
    """틈 GAP0~GAP1을 뺀 고리(바깥 큰 호 + 안쪽 큰 호). s = 배율."""
    o1, o2 = pt(cx, cy, R_OUT * s, GAP1), pt(cx, cy, R_OUT * s, GAP0)
    i2, i1 = pt(cx, cy, R_IN * s, GAP0), pt(cx, cy, R_IN * s, GAP1)
    ro, ri = R_OUT * s, R_IN * s
    f = lambda p: f"{p[0]:.3f},{p[1]:.3f}"
    # 68°에서 반시계로 돌아 28°까지(320°, 큰 호) → 안쪽 28°에서 시계로 68°까지
    return (f"M{f(o1)} A{ro:.3f},{ro:.3f} 0 1,0 {f(o2)} L{f(i2)} "
            f"A{ri:.3f},{ri:.3f} 0 1,1 {f(i1)} Z")

def dot(cx, cy, s):
    x, y = pt(cx, cy, DOT_D * s, DOT_A); return x, y, DOT_R * s

def mark_svg(color_ring, color_dot, size=240, bg=None, pad=0.12):
    s = size * (1 - 2 * pad) / (2 * (DOT_D + DOT_R) if DOT_D + DOT_R > R_OUT else 2 * R_OUT)
    s = size * (1 - 2 * pad) / (2 * R_OUT)
    cx = cy = size / 2; dx, dy, dr = dot(cx, cy, s)
    bgr = f'<rect width="{size}" height="{size}" fill="{bg}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size}" height="{size}">{bgr}'
            f'<path d="{ring_path(cx, cy, s)}" fill="{color_ring}" fill-rule="evenodd"/>'
            f'<circle cx="{dx:.3f}" cy="{dy:.3f}" r="{dr:.3f}" fill="{color_dot}"/></svg>')

def text_path(font_path, text, size, x, y, tracking=0.0):
    """글자 윤곽선을 SVG path로. y = 기준선. tracking = em 비율 자간."""
    f = TTFont(font_path); gs = f.getGlyphSet(); cmap = f.getBestCmap(); upm = f["head"].unitsPerEm
    sc = size / upm; d = []; cur = x
    for ch in text:
        g = cmap.get(ord(ch))
        if g is None: cur += size * 0.3; continue
        pen = SVGPathPen(gs); gs[g].draw(TransformPen(pen, (sc, 0, 0, -sc, cur, y)))
        d.append(pen.getCommands()); cur += gs[g].width * sc + tracking * size
    return " ".join(d), cur - tracking * size - x

def lockup_svg(theme="light"):
    ink = INK if theme == "light" else "#FFFFFF"; bg = PAPER if theme == "light" else NIGHT
    W, H = 1200, 420; s = 150 / R_OUT; cx, cy = 60 + R_OUT * s, H / 2
    dx, dy, dr = dot(cx, cy, s)
    wx = cx + R_OUT * s + 70
    word, ww = text_path(BLACK, "오운복", 200, wx, 252, tracking=-0.02)
    sub, sw = text_path(MEDIUM, "오늘의 운전 복기", 52, wx + 6, 340, tracking=0.32)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
            f'<rect width="{W}" height="{H}" fill="{bg}"/>'
            f'<path d="{ring_path(cx, cy, s)}" fill="{BLUE}"/><circle cx="{dx:.2f}" cy="{dy:.2f}" r="{dr:.2f}" fill="{BLUE}"/>'
            f'<path d="{word}" fill="{ink}"/><path d="{sub}" fill="{ink}"/></svg>')

def android_foreground():
    """적응형 아이콘 전경 108dp. 마크 바깥 지름 56dp(안전 영역 66dp 안, 권장 48~66dp)."""
    s = 28 / R_OUT; cx = cy = 54.0; dx, dy, dr = dot(cx, cy, s)
    circ = f"M{dx - dr:.3f},{dy:.3f} a{dr:.3f},{dr:.3f} 0 1,0 {2 * dr:.3f},0 a{dr:.3f},{dr:.3f} 0 1,0 {-2 * dr:.3f},0 Z"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 오운복 마크 '고리 속 한 순간'(docs/store/brand/make_brand.py가 생성). 끊긴 고리 = 되감기, 틈의 점 = 몰랐던 한 순간.
     108dp 캔버스 가운데 지름 56dp(안전 영역 66dp 안). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="{ring_path(cx, cy, s)}" />
    <path android:fillColor="#FFFFFF" android:pathData="{circ}" />
</vector>
'''

# ---------- PNG (8배 해상도로 그린 뒤 축소) ----------
def draw_mark(d, cx, cy, s, ring, dotc, k=1.0):
    pts = []
    for i in range(361):  # 바깥 호: GAP1 → GAP0+360 반시계(각도 증가)
        a = GAP1 + (360 - (GAP1 - GAP0)) * i / 360; pts.append(pt(cx, cy, R_OUT * s, a))
    for i in range(361):
        a = GAP0 + 360 - (360 - (GAP1 - GAP0)) * i / 360; pts.append(pt(cx, cy, R_IN * s, a))
    d.polygon(pts, fill=ring)
    x, y, r = dot(cx, cy, s); d.ellipse([x - r, y - r, x + r, y + r], fill=dotc)

def icon_png(path, size=512, shape="square", fg="#FFFFFF", bg=BLUE):
    S = 8; N = size * S; im = Image.new("RGBA", (N, N), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    if shape == "square": d.rectangle([0, 0, N, N], fill=bg)
    elif shape == "circle": d.ellipse([0, 0, N, N], fill=bg)
    elif shape == "squircle": d.rounded_rectangle([0, 0, N, N], radius=N * 0.22, fill=bg)
    elif shape == "teardrop": d.rounded_rectangle([0, 0, N, N], radius=N * 0.5, fill=bg); d.rectangle([N / 2, N / 2, N, N], fill=bg)
    s = N * (56 / 108) / 2 / R_OUT; draw_mark(d, N / 2, N / 2, s, fg, fg)
    out = im.resize((size, size), Image.LANCZOS)
    if shape == "square": out = out.convert("RGB")
    out.save(path); return out

def lockup_png(path, theme="light", scale=1.0):
    W, H = int(1200 * scale), int(420 * scale); S = 4
    bg = PAPER if theme == "light" else NIGHT; ink = INK if theme == "light" else "#FFFFFF"
    im = Image.new("RGB", (W * S, H * S), bg); d = ImageDraw.Draw(im); k = scale * S
    s = 150 / R_OUT * k; cx, cy = (60 + 150) * k, H * S / 2
    draw_mark(d, cx, cy, s, BLUE, BLUE)
    wx = cx + 150 * k + 70 * k
    fb = ImageFont.truetype(str(BLACK), int(200 * k)); fm = ImageFont.truetype(str(MEDIUM), int(52 * k))
    x = wx
    for ch in "오운복":
        d.text((x, 252 * k), ch, font=fb, fill=ink, anchor="ls"); x += fb.getlength(ch) - 0.02 * 200 * k
    x = wx + 6 * k
    for ch in "오늘의 운전 복기":
        d.text((x, 340 * k), ch, font=fm, fill=ink, anchor="ls"); x += fm.getlength(ch) + 0.32 * 52 * k
    im.resize((W, H), Image.LANCZOS).save(path)

if __name__ == "__main__":
    (OUT / "mark_blue.svg").write_text(mark_svg(BLUE, BLUE), encoding="utf-8")
    (OUT / "mark_white_on_blue.svg").write_text(mark_svg("#FFFFFF", "#FFFFFF", bg=BLUE), encoding="utf-8")
    (OUT / "mark_mono_black.svg").write_text(mark_svg("#000000", "#000000"), encoding="utf-8")
    (OUT / "lockup_light.svg").write_text(lockup_svg("light"), encoding="utf-8")
    (OUT / "lockup_dark.svg").write_text(lockup_svg("dark"), encoding="utf-8")
    (APP / "ic_launcher_foreground.xml").write_text(android_foreground(), encoding="utf-8")
    icon_png(OUT.parent / "icon_512.png")
    for shp in ("square", "circle", "squircle", "teardrop"): icon_png(OUT / f"mask_{shp}.png", 256, shp)
    lockup_png(OUT / "lockup_light.png", "light"); lockup_png(OUT / "lockup_dark.png", "dark")
    print("ok")
