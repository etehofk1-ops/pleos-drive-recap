"""W17b: 차선 변경 '조작 시작(onset)' 후보 정의를 정답(truth_v3, 자이로 요레이트 기하)과 비교한다.

- 탐지는 spikes/w16c/lanewin.py detect() 그대로(엔진 DelayedLaneWindow와 같은 기하). 카드의 startMs == lanewin 'start'.
- 후보 onset은 앱이 쓸 수 있는 정보만 쓴다: 선택된 창 [a,b)의 핸들각/속도, 창 앞 기록(링 버퍼가 창 시작 7.5초 전까지 가짐).
- 정답 시작: truth_v3 사건의 start_ms (자이로 잔차 최대의 20%를 처음 넘는 곳). 자이로 5%/10% 잣대(정답 자신의 창 위)도 민감도로 본다.
- 매칭: eval_v3와 같은 ±1.5초 겹침. 한 창에 정답이 둘이면 겹침이 큰 쪽.

사용: python onset_eval.py [engine_jsonl] [--rows]
  engine_jsonl = RealDataBatchTest 출력(lead_ms로 깜빡이 켠 시각을 얻는다). 기본 out/batch_master.jsonl
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "w16c"))
import lanewin as L

ROOT = r"C:\data\commaCarSegments"
truth = json.load(open(ROOT + r"\truth_v3.json", encoding="utf-8"))
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
ENGINE = ARGS[0] if ARGS else os.path.join(HERE, "..", "..", "out", "batch_master.jsonl")
SHOW_ROWS = "--rows" in sys.argv
eng = {j["segment"]: j for j in map(json.loads, open(ENGINE, encoding="utf-8"))}

FRACS = (0.2, 0.15, 0.1, 0.07, 0.05, 0.03)
ANGLES = (0.3, 0.5)
LOOK_D = (0.3, 0.5, 0.8)      # 되짚기 임계(도): 창 앞 기준선에서 벗어난 정도
LOOK_W = (3.0, 5.0, 7.0)      # 기준선(중앙값)을 잡는 창 앞 길이(초). 링 버퍼 한계 7.5초
HOLD = 3                      # 조용한 표본이 연속 이만큼이면 되짚기 멈춤(0.3초)
LOOK_CAP_S = 8.0              # 되짚기 최대 길이


def first_cross(x, thr):
    idx = np.flatnonzero(np.abs(x) > thr)
    return int(idx[0]) if len(idx) else 0


def first_cross_run(x, thr, run):
    hit = np.abs(x) > thr
    for i in range(len(x) - run + 1):
        if hit[i:i + run].all(): return i
    return first_cross(x, thr)


def look_back(th, a, o, W, d, hold=HOLD, cap=LOOK_CAP_S):
    """o(창 안 인덱스)에서 거꾸로, 창 앞 W초 중앙값 기준선에서 d도 넘게 벗어난 표본이 이어지는 곳까지 되짚는다."""
    w = int(round(W / L.DT)); lo = max(0, a - w)
    base = float(np.median(th[lo:a])) if a - lo >= 5 else float(th[max(0, a - L.EDGE):a].mean())
    floor = max(lo, o - int(cap / L.DT)); last = o; quiet = 0; j = o - 1
    while j >= floor:
        if abs(th[j] - base) > d: last = j; quiet = 0
        else:
            quiet += 1
            if quiet >= hold: break
        j -= 1
    return last


def ext_back(yaw, a, b, r, on, K, f, hold):
    """창 앞 K초 지점 0.5초 평균을 조용한 기준 Q로 삼고(끝점 기준선과 같은 발상), 창 시작에서 거꾸로
    |yaw-Q| > f*최대(잔차)인 표본이 이어지는 만큼 onset을 앞당긴다. 조용한 표본이 hold개 이어지면 멈춘다."""
    k = int(round(K / L.DT))
    if a - k - L.EDGE < 0: return on
    Q = yaw[a - k - L.EDGE:a - k].mean()
    dev = np.abs(yaw[a - k:a] - Q)
    peak = max(np.abs(r).max(), dev.max())
    thr = f * peak; last = on - a  # 창 안 상대 인덱스(음수면 창 앞)
    quiet = 0
    for j in range(k - 1, -1, -1):
        if dev[j] > thr: last = j - k; quiet = 0
        else:
            quiet += 1
            if quiet >= hold: break
    return a + last


def chain_back(yaw, a, r, on, K, opp, walk, hold=5):
    """창이 '두 번째 봉우리 한가운데'에서 시작한 경우를 잡는 되짚기. 창 앞 K초 안에 창 첫 봉우리와 반대 부호로
    peak*opp 이상 벗어난 구간이 있으면, 창 시작에서 거꾸로 그 반대 방향 편차가 peak*walk를 넘는 표본이 이어지는 곳까지 당긴다."""
    k = int(round(K / L.DT))
    if a - k < 0: return on
    peak = np.abs(r).max()
    s1 = np.sign(r[first_cross(r, 0.5 * peak)])
    y0 = yaw[a - L.EDGE:a].mean()
    opp_dev = -s1 * (yaw[a - k:a] - y0)           # 반대 방향 편차(양수)
    if opp_dev.max() < opp * peak: return on
    last = on - a; quiet = 0
    for j in range(k - 1, -1, -1):
        if opp_dev[j] > walk * peak: last = j - k; quiet = 0
        else:
            quiet += 1
            if quiet >= hold: break
    return a + last


def candidates(T, v, th, e):
    """창 e의 후보 onset 시각(초) 딕셔너리."""
    a, b = e["a"], e["b"]; n = b - a
    yaw = v * np.radians(th) / (L.RATIO * L.WHEELBASE)
    y0 = yaw[a - L.EDGE:a].mean(); y1 = yaw[b:b + L.EDGE].mean()
    r = yaw[a:b] - np.linspace(y0, y1, n)
    base = th[a - L.EDGE:a].mean(); dth = th[a:b] - base
    c = {}
    for f in FRACS: c[f"resid{int(round(f*100)):02d}"] = T[a + first_cross(r, f * np.abs(r).max())]
    for f in (0.1, 0.05):   # 연속 3표본(0.3초) 넘어야 인정 — 잡음 한 점에 걸리지 않게
        c[f"resid{int(round(f*100)):02d}p3"] = T[a + first_cross_run(r, f * np.abs(r).max(), 3)]
    for d in ANGLES: c[f"angle{d}"] = T[a + first_cross(dth, d)]
    c["win_start"] = T[a]
    on20 = a + first_cross(r, 0.2 * np.abs(r).max())
    for W in LOOK_W:
        for d in LOOK_D: c[f"look W{W:g} d{d}"] = T[look_back(th, a, on20, W, d)]
    for K in (3.0, 4.0, 5.0, 6.0):
        for f in (0.3, 0.5, 0.7):
            c[f"ext K{K:g} f{f} h5"] = T[ext_back(yaw, a, b, r, on20, K, f, 5)]
    on05 = a + first_cross(r, 0.05 * np.abs(r).max())
    for K in (2.0, 3.0, 4.0):
        for opp in (0.5, 0.7, 1.0):
            for walk in (0.25,):
                c[f"chain K{K:g} o{opp} w{walk}"] = T[chain_back(yaw, a, r, on05, K, opp, walk)]
    return c


def sim_main():
    """합성 사건(lanewin.sim_episode: 조향이 정확히 t0에서 시작하는 반사인 차선 변경)에서 후보 onset - t0 오차."""
    import random
    n = int(os.environ.get("N", 100)); rnd = random.Random(20261026)
    noise = os.environ.get("NOISE", "default")
    print(f"# 합성 시뮬 n={n}/속도 NOISE={noise}. 오차 = 후보 onset - 참 조향 시작(t0), 초와 m(속도 곱). 매칭 못 한 에피소드는 따로 센다.")
    for kph in (40, 60, 80, 100, 120):
        errs = {}; miss = 0
        for _ in range(n):
            sp, st, sig, t0, end_ev = L.sim_episode("LANE_BEFORE", kph, rnd)
            T, v, th = L.resample(sp, st); ev = [e for e in L.detect(T, v, th) if e["start"] < end_ev + 1.5 and e["end"] > t0 - 1.5]
            if not ev: miss += 1; continue
            e = ev[0]
            for k, tv in candidates(T, v, th, e).items(): errs.setdefault(k, []).append(tv - t0)
        print(f"\n## {kph} km/h 매칭 {n-miss}/{n}")
        for k, x in errs.items():
            x = np.array(x); m = x * kph / 3.6
            print(f"  {k:18s} 중앙 {np.median(x):+.2f}s 평균 {x.mean():+.2f}s |오차| 중앙 {np.median(abs(x)):.2f}s p90 {np.percentile(abs(x),90):.2f}s | m: 중앙 {np.median(m):+5.1f} 평균 {m.mean():+5.1f} |m|p90 {np.percentile(abs(m),90):5.1f}")


if "--sim" in sys.argv:
    sim_main(); sys.exit(0)


def odo(T, v, t0, t1):
    """t0~t1 사이 속도 적분(m). 10ms 보간 — 0.1초 격자에 걸려 생기는 몇 m 오차를 피한다."""
    if not (t1 > t0): return float("nan")
    t = np.arange(t0, t1, 0.01); return float(np.trapezoid(np.interp(t, T, v), t))


def truth_variants(T, v, th, ym, s):
    """정답 사건마다 자이로 잔차 f% 시작(정답 자신의 창 위). truth_v3.py와 같은 탐지 설정."""
    save = (L.PEAK_DEG, L.LAT_MIN, L.LAT_MAX); L.PEAK_DEG, L.LAT_MIN, L.LAT_MAX = 1.5, 2.5, 5.5
    ser = dict(np.load(rf"{ROOT}\series\{s['segment']}.npz")); yaw, whl = ser["yaw"], ser["whl"]
    t0 = max(yaw[0, 0], whl[0, 0], ser["sas"][0, 0]); t1 = min(yaw[-1, 0], whl[-1, 0], ser["sas"][-1, 0])
    TT = np.arange(t0, t1, L.DT); vv = np.interp(TT, whl[:, 0], whl[:, 1]); y = np.radians(np.interp(TT, yaw[:, 0], yaw[:, 1]))
    evs = L.detect(TT, vv, np.zeros_like(TT), yaw=y, strong_ok=False); L.PEAK_DEG, L.LAT_MIN, L.LAT_MAX = save
    out = {}
    for t in s["truth"]:
        near = [q for q in evs if abs(q["start"] * 1000 - t["start_ms"]) <= 150]
        if not near: continue   # truth_v3.json가 lanewin 현재 기본값과 달라 재현이 안 되는 사건 → 민감도 정답 없음
        e = near[0]; a, b = e["a"], e["b"]; n = b - a
        y0 = y[a - L.EDGE:a].mean(); y1 = y[b:b + L.EDGE].mean(); rg = y[a:b] - np.linspace(y0, y1, n)
        out[t["start_ms"]] = {f"gyro{int(f*100):02d}": TT[a + first_cross(rg, f * np.abs(rg).max())] for f in (0.2, 0.1, 0.05)}
    return out


rows = []
for s in truth:
    sp, st, sig = L.load_csv(rf"{ROOT}\csv\{s['segment']}.csv"); T, v, th = L.resample(sp, st)
    ev = L.detect(T, v, th)
    cards = [c for c in eng.get(s["segment"], {}).get("cards", []) if c["kind"].startswith("LANE_")]
    tv = truth_variants(T, v, th, None, s)
    for e in ev:
        card = next((c for c in cards if abs(c["start_ms"] - e["start"] * 1000) <= 150), None)
        best = None; bo = 0
        for t in s["truth"]:
            ov = min(e["end"], t["end_ms"] / 1000 + 1.5) - max(e["start"], t["start_ms"] / 1000 - 1.5)
            if e["start"] * 1000 < t["end_ms"] + 1500 and e["end"] * 1000 > t["start_ms"] - 1500 and ov > bo: best, bo = t, ov
        on_s = (card["start_ms"] - card["lead_ms"]) / 1000 if card and card.get("lead_ms") is not None else None
        rows.append(dict(seg=s["segment"], start=e["start"], end=e["end"], kph=e["kph"], card=card, truth=best,
                         tv=tv.get(best["start_ms"]) if best else None, cand=candidates(T, v, th, e), on_s=on_s, T=T, v=v,
                         mps=float(v[e["a"]:e["b"]].mean()), wstart=T[e["a"]]))

print(f"탐지 창 {len(rows)}개, 엔진 카드와 대조 불일치 {sum(1 for r in rows if r['card'] is None)}개")
matched = [r for r in rows if r["truth"] is not None and r["card"] is not None]
print(f"자이로 5/10% 민감도 정답을 못 만든 매칭 창 {sum(1 for r in matched if r['tv'] is None)}개 (그 정답만 제외)")
sigm = [r for r in matched if r["on_s"] is not None]
print(f"정답과 매칭된 창 {len(matched)}개, 그중 사전 깜빡이(lead 있음) {len(sigm)}개")
keys = list(matched[0]["cand"].keys())


def summarize(key, subset, tsel):
    err = np.array([r["cand"][key] - tsel(r) for r in subset]); mt = np.array([abs(r["cand"][key] - tsel(r)) * r["mps"] for r in subset])
    sg = np.array([(r["cand"][key] - tsel(r)) * r["mps"] for r in subset]); ae = np.abs(err)
    return (f"{key:14s} 부호평균 {err.mean():+.2f}s 중앙 {np.median(err):+.2f}s |오차| 평균 {ae.mean():.2f}s 중앙 {np.median(ae):.2f}s "
            f"p90 {np.percentile(ae,90):.2f}s 최대 {ae.max():.1f}s | ±0.5s 안 {np.mean(ae<=0.5)*100:3.0f}% | |m| 평균 {mt.mean():5.1f} 중앙 {np.median(mt):5.1f} p90 {np.percentile(mt,90):5.1f} 부호평균 {sg.mean():+5.1f}")


ROWKEYS = ("resid20", "resid05", "win_start", "look W5 d0.8", "ext K4 f0.5 h5", "ext K5 f0.5 h5", "ext K6 f0.5 h5")
TS = {"truth_v3(자이로20%)": lambda r: r["truth"]["start_ms"] / 1000,
      "자이로10%": lambda r: r["tv"]["gyro10"], "자이로5%": lambda r: r["tv"]["gyro05"]}
INW = keys if "--all" in sys.argv else [k for k in keys if not (k.startswith("look") or k.startswith("ext") or k.startswith("chain"))]   # 창 안 정보만 쓰는 후보(--all이면 되짚기 후보도)
trim = [r for r in matched if abs(r["cand"]["resid20"] - r["truth"]["start_ms"] / 1000) <= 2.0]
print(f"분절 불일치 창(|resid20-truth|>2s) {len(matched)-len(trim)}개 — '정상 창'(n={len(trim)})에서 따로 본다")
for title, subset in (("매칭된 모든 창", matched), ("정상 창(분절 불일치 제외)", trim), ("사전 깜빡이 카드만", sigm)):
    for tn, ts in TS.items():
        sub = subset if tn.startswith("truth") else [r for r in subset if r["tv"] is not None]
        print(f"\n## {title} n={len(sub)} — 정답 {tn}")
        for k in INW: print("  " + summarize(k, sub, ts))

print("\n## 되짚기 후보가 resid20에서 얼마나 움직였나(truth_v3 기준): 움직인 창 수 / 오차 줄어든(>0.3s) / 늘어난(>0.3s)")
for k in keys:
    if not (k.startswith("ext") or k.startswith("look") or k.startswith("chain")): continue
    ref = "resid05" if k.startswith("chain") else "resid20"   # chain은 resid05 위에 얹은 것이라 그 대비
    ch = [r for r in matched if abs(r["cand"][k] - r["cand"][ref]) > 0.05]
    tt = lambda r: r["truth"]["start_ms"] / 1000
    better = sum(1 for r in ch if abs(r["cand"][ref] - tt(r)) - abs(r["cand"][k] - tt(r)) > 0.3)
    worse = sum(1 for r in ch if abs(r["cand"][k] - tt(r)) - abs(r["cand"][ref] - tt(r)) > 0.3)
    print(f"  {k:18s} 움직임 {len(ch):2d}  개선 {better:2d}  악화 {worse:2d}")
if SHOW_ROWS:
    print("\n## 창별 오차(초) — truth_v3 기준, 정렬: resid20 오차")
    for r in sorted(matched, key=lambda r: r["cand"]["resid20"] - r["truth"]["start_ms"] / 1000):
        t = r["truth"]["start_ms"] / 1000
        print(f"{r['seg'][-28:]:28s} {r['kph']:4.0f}km/h truth={t:6.1f} wstart={r['wstart']:6.1f} " + " ".join(f"{k.replace('look ','L')}={r['cand'][k]-t:+5.1f}" for k in ROWKEYS))

print("\n## 켬 거리(m) — 사전 깜빡이 카드. 카드=엔진(현행), 정답=truth_v3 시작까지 적분")
sg = [r for r in rows if r["on_s"] is not None]
for r in sg:
    t = r["truth"]["start_ms"] / 1000 if r["truth"] else float("nan")
    d = lambda tt: odo(r["T"], r["v"], r["on_s"], tt)
    print(f"{r['seg'][-28:]:28s} on={r['on_s']:6.1f} start={r['start']:6.1f} truth={t:6.1f} {r['kph']:3.0f}km/h 카드={r['card']['lead_m']:6.1f} 정답={d(t):6.1f} | " +
          " ".join(f"{k.replace('look ','L')}={d(r['cand'][k]):5.1f}" for k in ROWKEYS))
