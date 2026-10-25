"""오운복 복기 화면 UI 시안 3종(운동 앱처럼 담백하게) — HTML로 그려 실제 Pleos 화면 틀에 끼운다.

같은 예시 하루(34분 주행, 순간 7개)를 A 요약 / B 타임라인 / C 한 장씩 세 방식으로 보여 준다.
색·글꼴은 Pleos 토큰(배경 #F7F8FA, 카드 #FFF, 글자 #131417, 정보 파랑 #0064FF)과 Asta Sans.
사용: python make_concepts.py  → A.png, B.png, C.png(앱 영역만), *_frame.png(실제 화면 틀), sheet.png
"""
import subprocess
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
FONT = Path(r"D:\Projects\pleos-observe-lab\motion\demo-v6\assets\fonts").as_uri()
FRAME = Path(r"D:\Projects\pleos-observe-lab\.local\release_check\v160\release_icon.png")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
W, H, X0, Y0 = 1706, 1209, 854, 91  # 앱 영역(실측)

GOOD, MISS, LATE, LEFT, BRAKE = "#0064FF", "#E5482C", "#E89A00", "#8A5CF6", "#131417"
MOMENTS = [  # 분(출발 기준), 종류, 색, 제목, 근거
    (2, "잘한 순간", GOOD, "깜빡이 켜고 차선 변경", "켜고 45m 뒤 진입 · 58 km/h"),
    (5, "놓친 순간", MISS, "깜빡이 없이 차선 변경", "62 km/h · 핸들 5.4초"),
    (9, "잘한 순간", GOOD, "안내 따라 우회전", "교차로 40m 전 깜빡이"),
    (13, "늦게 켬", LATE, "핸들이 먼저 돌았어요", "깜빡이 1.2초 늦게 · 47 km/h"),
    (17, "켜 둔 채", LEFT, "깜빡이 켜 둔 채 주행", "22초 · 약 310m"),
    (21, "급제동", BRAKE, "급제동", "38 → 12 km/h · 7.9 m/s²"),
    (30, "잘한 순간", GOOD, "깜빡이 켜고 차선 변경", "켜고 35m 뒤 진입 · 71 km/h"),
]
TOTAL_MIN = 34
SPEED = [0, 18, 34, 52, 58, 61, 62, 55, 40, 22, 12, 30, 44, 47, 50, 66, 70, 71, 68, 52, 38, 12, 20, 35,
         48, 60, 68, 72, 74, 72, 71, 60, 42, 20, 0]

CSS = f"""
@font-face {{ font-family: Asta; src: url("{FONT}/AstaSansOTF-Regular.otf"); font-weight: 400; }}
@font-face {{ font-family: Asta; src: url("{FONT}/AstaSansOTF-Bold.otf"); font-weight: 700; }}
@font-face {{ font-family: Asta; src: url("{FONT}/AstaSansOTF-ExtraBold.otf"); font-weight: 800; }}
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
html, body {{ width: {W}px; height: {H}px; overflow: hidden; background: #F7F8FA; color: #131417; font-family: Asta, sans-serif; }}
.app {{ position: absolute; inset: 0; padding: 34px 44px; }}
.t1 {{ font-size: 40px; font-weight: 800; letter-spacing: -0.01em; }}
.sub {{ font-size: 26px; color: rgba(19,20,23,.64); margin-top: 8px; }}
.card {{ background: #fff; border-radius: 24px; }}
.cap {{ font-size: 24px; color: rgba(19,20,23,.64); }}
.num {{ font-weight: 800; letter-spacing: -0.02em; }}
.chip {{ display: inline-block; padding: 8px 18px; border-radius: 999px; background: rgba(19,20,23,.05); font-size: 24px; color: rgba(19,20,23,.84); margin-right: 10px; }}
.btn {{ flex: 1; height: 96px; border-radius: 16px; background: rgba(19,20,23,.05); font-size: 30px; font-weight: 700;
        display: flex; align-items: center; justify-content: center; color: rgba(19,20,23,.84); }}
.dot {{ display: inline-block; width: 16px; height: 16px; border-radius: 50%; }}
.up {{ color: {GOOD}; font-weight: 700; }}
"""


def page(body):
    return f"<!doctype html><html lang='ko'><head><meta charset='utf-8'><style>{CSS}</style></head><body><div class='app'>{body}</div></body></html>"


def header(right=""):
    return f"""<div style="display:flex;align-items:flex-end;justify-content:space-between">
      <div><div class="t1">오운복</div><div class="sub">10월 25일 토요일 · 주행 34분 · 18.4 km</div></div>{right}</div>"""


def lead_bar(meters, ref=30, width=700):
    """깜빡이 켬 → 진입 거리 막대. 권장 거리(30m) 눈금을 같이 그린다."""
    scale = width / 80
    return f"""<svg width="{width + 40}" height="120" viewBox="0 0 {width + 40} 120">
      <line x1="20" y1="60" x2="{20 + width}" y2="60" stroke="rgba(19,20,23,.10)" stroke-width="10" stroke-linecap="round"/>
      <line x1="20" y1="60" x2="{20 + meters * scale}" y2="60" stroke="{GOOD}" stroke-width="10" stroke-linecap="round"/>
      <line x1="{20 + ref * scale}" y1="38" x2="{20 + ref * scale}" y2="82" stroke="rgba(19,20,23,.4)" stroke-width="3" stroke-dasharray="6 5"/>
      <text x="{20 + ref * scale}" y="108" font-size="22" text-anchor="middle" fill="rgba(19,20,23,.64)" font-family="Asta">권장 {ref}m</text>
      <circle cx="20" cy="60" r="16" fill="#fff" stroke="{GOOD}" stroke-width="6"/>
      <text x="20" y="24" font-size="22" text-anchor="start" fill="rgba(19,20,23,.84)" font-family="Asta">깜빡이 켬</text>
      <circle cx="{20 + meters * scale}" cy="60" r="16" fill="{GOOD}"/>
      <text x="{20 + meters * scale}" y="24" font-size="22" text-anchor="middle" fill="#131417" font-family="Asta" font-weight="700">차선 진입 · {meters}m</text>
    </svg>"""


def timeline(width, height, selected=None, labels=False):
    """주행 속도 곡선 + 순간 점. 속도는 회색 면, 순간은 종류 색 점."""
    pts = [(i / TOTAL_MIN * width, height - 24 - v / 80 * (height - 70)) for i, v in enumerate(SPEED)]
    area = f"M0,{height - 24} " + " ".join(f"L{x:.1f},{y:.1f}" for x, y in pts) + f" L{width},{height - 24} Z"
    dots = ""
    for i, (m, _, c, *_r) in enumerate(MOMENTS):
        x = m / TOTAL_MIN * width
        y = pts[m][1]
        r = 18 if selected == i else 12
        ring = f'<circle cx="{x:.1f}" cy="{y:.1f}" r="30" fill="{c}" opacity=".14"/>' if selected == i else ""
        dots += f'{ring}<line x1="{x:.1f}" y1="{y:.1f}" x2="{x:.1f}" y2="{height - 24}" stroke="{c}" stroke-width="2" opacity=".35"/>' \
                f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r}" fill="{c}" stroke="#fff" stroke-width="4"/>'
        if labels:
            dots += f'<text x="{x:.1f}" y="{y - 30:.1f}" font-size="22" text-anchor="middle" fill="rgba(19,20,23,.84)" font-family="Asta" font-weight="700">{i + 1}</text>'
    ticks = "".join(f'<text x="{m / TOTAL_MIN * width:.1f}" y="{height}" font-size="20" text-anchor="middle" fill="rgba(19,20,23,.5)" font-family="Asta">{t}</text>'
                    for m, t in ((0, "08:10"), (10, "08:20"), (20, "08:30"), (30, "08:40")))
    return f"""<svg width="{width}" height="{height}" viewBox="0 0 {width} {height}" style="overflow:visible">
      <path d="{area}" fill="rgba(19,20,23,.06)"/>
      <polyline points="{' '.join(f'{x:.1f},{y:.1f}' for x, y in pts)}" fill="none" stroke="rgba(19,20,23,.25)" stroke-width="3"/>
      {dots}{ticks}</svg>"""


def ring(size, good=3, total=7):
    """오운복 마크(끊긴 고리)를 닮은 진행 고리: 잘한 순간 비율만큼 파랗게."""
    import math
    r, cx = size / 2 - 22, size / 2
    gap = 40  # 위쪽 틈(도)
    def arc(a0, a1, color, w=26):
        p0 = (cx + r * math.sin(math.radians(a0)), cx - r * math.cos(math.radians(a0)))
        p1 = (cx + r * math.sin(math.radians(a1)), cx - r * math.cos(math.radians(a1)))
        large = 1 if a1 - a0 > 180 else 0
        return f'<path d="M{p0[0]:.1f},{p0[1]:.1f} A{r},{r} 0 {large},1 {p1[0]:.1f},{p1[1]:.1f}" fill="none" stroke="{color}" stroke-width="{w}" stroke-linecap="round"/>'
    span = 360 - gap
    end = gap / 2 + span * good / total
    return f"""<svg width="{size}" height="{size}">{arc(gap / 2, 360 - gap / 2, 'rgba(19,20,23,.08)')}{arc(gap / 2, end, GOOD)}
      <circle cx="{cx}" cy="{cx - r}" r="11" fill="{GOOD}"/></svg>"""


def concept_a():
    tiles = [("깜빡이 켬 거리", "40", "m", "평균", "지난주보다 <span class='up'>12m 일찍</span>"),
             ("놓친 순간", "1", "번", "", "지난주 평균 <span class='up'>2.4번 → 1번</span>"),
             ("급제동", "1", "번", "", "지난주와 같아요")]
    tile_html = "".join(f"""<div class="card" style="flex:1;padding:30px 32px">
        <div class="cap">{t}</div>
        <div style="margin-top:14px"><span class="num" style="font-size:72px">{v}</span><span style="font-size:32px;font-weight:700;margin-left:4px">{u}</span>
        <span class="cap" style="margin-left:10px">{s}</span></div>
        <div style="font-size:24px;margin-top:10px;color:rgba(19,20,23,.84)">{d}</div></div>""" for t, v, u, s, d in tiles)
    rows = "".join(f"""<div style="display:flex;align-items:center;padding:20px 8px;border-top:2px solid rgba(19,20,23,.06)">
        <span class="cap" style="width:96px">08:{10 + m:02d}</span><span class="dot" style="background:{c};margin-right:18px"></span>
        <span style="font-size:28px;font-weight:700;width:420px">{title}</span><span class="cap">{ev}</span>
        <span style="margin-left:auto;font-size:24px;color:{c};font-weight:700">{k}</span></div>""" for m, k, c, title, ev in MOMENTS[:5])
    body = header() + f"""
    <div style="display:flex;gap:28px;margin-top:30px">
      <div class="card" style="width:500px;padding:30px;display:flex;flex-direction:column;align-items:center">
        <div class="cap" style="align-self:flex-start">오늘의 순간</div>
        <div style="position:relative;margin-top:6px">{ring(360)}
          <div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center">
            <div><span class="num" style="font-size:110px">3</span><span style="font-size:40px;font-weight:700;color:rgba(19,20,23,.5)"> / 7</span></div>
            <div style="font-size:28px;font-weight:700;margin-top:-6px">잘한 순간</div></div></div>
        <div style="margin-top:14px;font-size:24px;color:rgba(19,20,23,.84);text-align:center">놓침 1 · 늦게 켬 1 · 켜 둔 채 1 · 급제동 1</div>
      </div>
      <div style="flex:1;display:flex;flex-direction:column;gap:28px">
        <div style="display:flex;gap:28px">{tile_html}</div>
        <div class="card" style="flex:1;padding:24px 32px">
          <div class="cap" style="margin-bottom:10px">주행 흐름 · 속도와 순간</div>{timeline(1040, 220)}</div>
      </div></div>
    <div class="card" style="margin-top:28px;padding:10px 32px 4px">{rows}</div>"""
    return page(body)


def concept_b():
    sel = 1
    m, k, c, title, ev = MOMENTS[sel]
    body = header(f"<div><span class='chip'>잘한 순간 3</span><span class='chip'>놓침 1</span><span class='chip'>늦게 켬 1</span><span class='chip'>켜 둔 채 1</span><span class='chip'>급제동 1</span></div>") + f"""
    <div class="card" style="margin-top:30px;padding:40px 44px 30px">
      <div class="cap" style="margin-bottom:46px">주행 흐름 · 점을 누르면 그 순간이 펼쳐져요</div>{timeline(1530, 300, selected=sel, labels=True)}
    </div>
    <div style="display:flex;gap:28px;margin-top:28px">
      <div class="card" style="flex:1.25;padding:36px 40px">
        <div style="font-size:26px;font-weight:700;color:{c}">2 · {k} · 08:{10 + m:02d}</div>
        <div style="font-size:46px;font-weight:800;margin-top:12px">{title}</div>
        <svg width="760" height="190" style="margin-top:24px">
          <line x1="30" y1="120" x2="730" y2="120" stroke="rgba(19,20,23,.10)" stroke-width="8"/>
          <path d="M60,150 C240,150 300,60 480,60 L720,60" fill="none" stroke="#131417" stroke-width="6" stroke-linecap="round"/>
          <line x1="30" y1="90" x2="730" y2="90" stroke="rgba(19,20,23,.12)" stroke-width="3" stroke-dasharray="14 12"/>
          <text x="60" y="186" font-size="22" fill="rgba(19,20,23,.64)" font-family="Asta">핸들 돌기 시작</text>
          <text x="560" y="40" font-size="22" fill="rgba(19,20,23,.64)" font-family="Asta">옆 차로 도착</text>
          <rect x="250" y="8" width="210" height="44" rx="22" fill="{MISS}" opacity=".12"/>
          <text x="355" y="38" font-size="22" text-anchor="middle" fill="{MISS}" font-family="Asta" font-weight="700">깜빡이 없음</text>
        </svg>
      </div>
      <div class="card" style="flex:1;padding:36px 40px;display:flex;flex-direction:column">
        <div class="cap">근거</div>
        <div style="margin-top:16px"><span class="chip">속도 62 km/h</span><span class="chip">핸들 5.4초</span></div>
        <div style="margin-top:12px"><span class="chip">앞뒤 3초 깜빡이 없음</span></div>
        <div class="cap" style="margin-top:28px">이 판단이 맞나요?</div>
        <div style="display:flex;gap:16px;margin-top:auto"><div class="btn">맞아요</div><div class="btn">아니에요</div></div>
      </div></div>"""
    return page(body)


def concept_c():
    segs = "".join(f'<div style="flex:1;height:8px;border-radius:4px;background:{"#131417" if i == 0 else "rgba(19,20,23,.12)"}"></div>' for i in range(7))
    side = "".join(f"""<div style="display:flex;justify-content:space-between;padding:18px 0;border-top:2px solid rgba(19,20,23,.06);font-size:28px">
        <span><span class="dot" style="background:{c};margin-right:14px"></span>{k}</span><span class="num">{n}</span></div>"""
                   for k, c, n in (("잘한 순간", GOOD, 3), ("놓친 순간", MISS, 1), ("늦게 켬", LATE, 1), ("켜 둔 채", LEFT, 1), ("급제동", BRAKE, 1)))
    body = header() + f"""
    <div style="display:flex;gap:10px;margin-top:28px">{segs}</div>
    <div style="display:flex;gap:28px;margin-top:28px">
      <div class="card" style="flex:1;padding:50px 56px;height:900px;display:flex;flex-direction:column">
        <div style="font-size:28px;font-weight:700;color:{GOOD}">잘한 순간 · 1 / 7 · 08:12</div>
        <div style="font-size:60px;font-weight:800;margin-top:18px;line-height:1.25">깜빡이를 켜고<br>45m 뒤에 차선을 바꿨어요</div>
        <div style="margin-top:56px">{lead_bar(45, width=900)}</div>
        <div style="margin-top:40px"><span class="chip">속도 58 km/h</span><span class="chip">핸들 4.6초</span><span class="chip">지난주 평균 28m</span></div>
        <div class="cap" style="margin-top:30px;margin-bottom:4px">오늘 주행 중 위치</div>{timeline(1050, 130, selected=0)}
        <div style="display:flex;gap:16px;margin-top:auto;align-items:center">
          <div class="btn" style="flex:0 0 96px">‹</div><div class="btn">맞아요</div><div class="btn">아니에요</div><div class="btn" style="flex:0 0 96px">›</div></div>
      </div>
      <div style="width:420px;display:flex;flex-direction:column;gap:28px">
        <div class="card" style="padding:30px 32px">
          <div class="cap">오늘 한눈에</div>{side}</div>
        <div class="card" style="padding:30px 32px;flex:1">
          <div class="cap">오늘의 한마디 · AI</div>
          <div style="font-size:27px;line-height:1.55;margin-top:14px;color:rgba(19,20,23,.84)">차선을 바꿀 때 깜빡이를 지난주보다 일찍 켰어요. 다만 8시 27분쯤 깜빡이를 켠 채 22초를 달린 순간이 있었어요.</div>
        </div></div></div>"""
    return page(body)


def render(name, html):
    src = HERE / f"{name}.html"
    src.write_text(html, encoding="utf-8")
    out = HERE / f"{name}.png"
    out.unlink(missing_ok=True)
    # 켜져 있는 Edge와 프로필을 나누지 않으면 헤드리스가 기존 창에 붙어 파일을 안 남긴다
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                    f"--user-data-dir={HERE / ('_edge_profile_' + name)}", f"--window-size={W},{H}", "--virtual-time-budget=3000",
                    f"--screenshot={out.as_posix()}", src.as_uri()],
                   check=True, capture_output=True)
    import time  # msedge.exe 런처가 먼저 끝나고 실제 저장은 자식 프로세스가 조금 뒤에 한다
    for _ in range(60):
        if out.exists() and out.stat().st_size > 0: break
        time.sleep(0.5)
    time.sleep(1)
    frame = Image.open(FRAME).convert("RGB")
    frame.paste(Image.open(out).convert("RGB").crop((0, 0, W, H)), (X0, Y0))
    frame.save(HERE / f"{name}_frame.png")
    return HERE / f"{name}_frame.png"


if __name__ == "__main__":
    frames = [render(n, f()) for n, f in (("A", concept_a), ("B", concept_b), ("C", concept_c))]
    ims = [Image.open(p).resize((1280, 720)) for p in frames]
    sheet = Image.new("RGB", (1280, 720 * 3 + 40), "white")
    for i, im in enumerate(ims): sheet.paste(im, (0, i * 740))
    sheet.save(HERE / "sheet.png")
    print("ok", [p.name for p in frames])
