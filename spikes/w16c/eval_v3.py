"""정답 v3로 핸들각 탐지기 채점 + 탐지마다 요레이트로 물리 확인.
사용: python eval_v3.py [PEAK] [LATMIN] [CORR]
- 재현율: v3 사건(요레이트 기하) 중 ±1.5초 겹치는 탐지 비율. 브랜드/속도대/깜빡이별.
- 확인율: 탐지 창에서 측정 요레이트로 다시 잰 |옆 이동| ≥ 2.0m이고 방향 복귀 → '물리 확인'. 아니면 '미확인'(오탐 의심).
"""
import json, os, sys, collections, math, numpy as np
sys.path.insert(0, os.path.dirname(__file__)); import lanewin as L
ROOT = r"C:\data\commaCarSegments"
if len(sys.argv) > 1: L.PEAK_DEG = float(sys.argv[1])
if len(sys.argv) > 2: L.LAT_MIN = float(sys.argv[2])
if len(sys.argv) > 3: L.CORR_MIN = float(sys.argv[3])
truth = json.load(open(ROOT + r"\truth_v3.json", encoding="utf-8"))
# PERVEH=1: 차종별 조향비(정답 v2 때 요레이트로 추정한 ratio_est의 차종 중앙값) — 앱이 차종을 알 때의 상한선
import statistics
_est = json.load(open(ROOT + r"\truth.json", encoding="utf-8"))
MODEL_RATIO = {m: statistics.median([s["ratio_est"] for s in _est if s["model"] == m and s["ratio_est"]]) for m in {s["model"] for s in _est}}
PERVEH = os.environ.get("PERVEH") == "1"
STRONG_LAT = float(os.environ.get("STRONG", 3.5))  # '깜빡이 없이 차선 변경' 경고 표시 후보 하한(강한 근거)

def yaw_check(T, v, ymeas, a_s, b_s):
    a = int(np.searchsorted(T, a_s)); b = int(np.searchsorted(T, b_s)); n = b - a
    if a < L.EDGE or b + L.EDGE > len(T) or n < 10: return None
    y0 = ymeas[a - L.EDGE:a].mean(); y1 = ymeas[b:b + L.EDGE].mean()
    r = ymeas[a:b] - np.linspace(y0, y1, n); psi = np.cumsum(r) * L.DT; peak = np.abs(psi).max()
    lat = np.cumsum(v[a:b] * np.sin(psi)) * L.DT
    return dict(lat=float(lat[-1]), peak=math.degrees(peak), returned=abs(psi[-1]) <= max(math.radians(1.5), 0.5 * peak))

res = {}
BRAND = os.environ.get("BRAND")  # 값 고르기 단계: BRAND=HYUNDAI만 보고 기아는 마지막에 한 번
for s in truth:
    br = s["model"].split("_")[0]
    if BRAND and br != BRAND: continue
    R = res.setdefault(br, dict(hit=collections.Counter(), tot=collections.Counter(), det=0, conf=0, unconf=[], hours=0.0))
    R["hours"] += s["moving_s"] / 3600
    sp, st, sig = L.load_csv(rf"{ROOT}\csv\{s['segment']}.csv"); T, v, th = L.resample(sp, st)
    ser = dict(np.load(rf"{ROOT}\series\{s['segment']}.npz")); ym = np.radians(np.interp(T, ser["yaw"][:, 0], ser["yaw"][:, 1]))
    ev = L.detect(T, v, th, ratio=MODEL_RATIO.get(s["model"]) if PERVEH else None)
    for t in s["truth"]:
        band = "≤60" if t["kph"] < 60 else "60~100" if t["kph"] < 100 else "≥100"; g = "깜빡이" if t["blinker"] != "none" else "무깜빡이"
        R["tot"][(g, band)] += 1
        if any(e["start"] * 1000 < t["end_ms"] + 1500 and e["end"] * 1000 > t["start_ms"] - 1500 for e in ev): R["hit"][(g, band)] += 1
    for e in ev:
        R["det"] += 1; c = yaw_check(T, v, ym, e["start"], e["end"])
        strong = abs(e["lat"]) >= STRONG_LAT
        R.setdefault("sdet", 0); R.setdefault("sconf", 0)
        if strong: R["sdet"] += 1
        if c and abs(c["lat"]) >= 2.0 and c["returned"]:
            R["conf"] += 1
            if strong: R["sconf"] += 1
        else: R["unconf"].append((s["segment"][:28], round(e["start"], 1), round(e["kph"]), round(e["lat"], 1), c and round(c["lat"], 1), c and round(c["peak"], 1)))
print(f"# v3 채점  PEAK={L.PEAK_DEG} LATMIN={L.LAT_MIN} CORR={L.CORR_MIN} RATIO={'차종별 '+str({k[:12]:round(v,1) for k,v in MODEL_RATIO.items()}) if PERVEH else L.RATIO}")
for br, R in res.items():
    H, N = sum(R["hit"].values()), sum(R["tot"].values()); hb = sum(v for k, v in R["hit"].items() if k[0] == "깜빡이"); nb = sum(v for k, v in R["tot"].items() if k[0] == "깜빡이")
    print(f"  {br}: 재현 {H}/{N} ({H*100//max(N,1)}%), 깜빡이 확인 사건 {hb}/{nb} | 탐지 {R['det']}건 중 요레이트 확인 {R['conf']} ({R['conf']*100//max(R['det'],1)}%), 미확인 {len(R['unconf'])}건 = 시간당 {len(R['unconf'])/R['hours']:.1f} (주행 {R['hours']*60:.0f}분)")
    print("     " + ", ".join(f"{k[0]} {k[1]}: {R['hit'][k]}/{R['tot'][k]}" for k in sorted(R["tot"])))
    print(f"     강한 근거(|옆|≥{STRONG_LAT}m) 탐지 {R.get('sdet',0)}건 중 확인 {R.get('sconf',0)}, 미확인 {R.get('sdet',0)-R.get('sconf',0)}건 = 시간당 {(R.get('sdet',0)-R.get('sconf',0))/R['hours']:.1f}")
    for u in R["unconf"][:3]: print("     미확인:", u)


def score_engine(path, label="engine"):
    """RealDataBatchTest JSONL(보이는 차선 변경 카드)을 같은 v3 정답·요레이트 확인으로 채점한다."""
    LANE = {"LANE_SIGNALLED", "LANE_NO_SIGNAL", "LANE_LATE_SIGNAL", "LANE_CHANGE"}
    eng = {j["segment"]: j for j in map(json.loads, open(path, encoding="utf-8"))}
    out = {}
    for s in truth:
        br = s["model"].split("_")[0]; R = out.setdefault(br, dict(hit=0, tot=0, det=0, conf=0, nosig=0, nosig_conf=0, hours=0.0))
        R["hours"] += s["moving_s"] / 3600
        cards = [c for c in eng.get(s["segment"], {}).get("cards", []) if c["visible"] and c["kind"] in LANE]
        sp, st, sig = L.load_csv(rf"{ROOT}\csv\{s['segment']}.csv"); T, v, th = L.resample(sp, st)
        ser = dict(np.load(rf"{ROOT}\series\{s['segment']}.npz")); ym = np.radians(np.interp(T, ser["yaw"][:, 0], ser["yaw"][:, 1]))
        for t in s["truth"]:
            R["tot"] += 1
            if any(c["start_ms"] < t["end_ms"] + 1500 and c["end_ms"] > t["start_ms"] - 1500 for c in cards): R["hit"] += 1
        for c in cards:
            R["det"] += 1; k = yaw_check(T, v, ym, c["start_ms"] / 1000, c["end_ms"] / 1000); ok = bool(k and abs(k["lat"]) >= 2.0 and k["returned"])
            R["conf"] += ok
            if c["kind"] == "LANE_NO_SIGNAL": R["nosig"] += 1; R["nosig_conf"] += ok
    print(f"# 엔진 채점 {label}")
    for br, R in out.items():
        print(f"  {br}: 재현 {R['hit']}/{R['tot']} | 보이는 차선 카드 {R['det']}건, 요레이트 확인 {R['conf']}, 미확인 시간당 {(R['det']-R['conf'])/R['hours']:.1f} | 깜빡이 없음 카드 {R['nosig']}건 중 확인 {R['nosig_conf']}")


if __name__ == "__main__" and os.environ.get("ENGINE"):
    score_engine(os.environ["ENGINE"], os.environ.get("LABEL", "engine"))
