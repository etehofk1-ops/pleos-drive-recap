"""W16c 스파이크 — 끝점 기준선 지연 창 차선 변경 탐지 (Kotlin 이식 전 알고리즘 검증용).

핵심 아이디어
- 도로 성분/핸들 영점을 이동평균으로 빼지 않는다(이웃 사건이 섞여 들어옴).
- 후보 창 [a,b)마다 양 끝 0.5초 평균을 잇는 직선을 기준선으로 쓴다. 참 차선 변경은 양 끝이 '직진'이라
  기준선이 곧 도로+영점이고, 이웃 사건은 창 밖이라 영향이 없다.
- 두 봉우리(반대 부호) 모양, 방향 복귀, 옆 이동 2~6m, 곡선 전이(끝점 요레이트 차) 거절, 점수 기반 비최대 억제.

사용:  python lanewin.py regress | sim | ccs
"""
import csv, json, math, sys, os, random
import numpy as np

RATIO, WHEELBASE = float(os.environ.get("RATIO", 20.0)), 2.7   # 유효 조향비: 실데이터 차종별 추정 13.9~27.4의 중앙값(20.6)
DT = 0.1
EDGE = 5                      # 0.5초 끝점 평균
WIN_S = np.arange(2.5, 8.01, 0.5)   # 창 = 사건 본체. 기준선 끝점은 창 바깥 0.5초
STEP = 2                      # 창 시작 0.2초 간격
PEAK_DEG = float(os.environ.get("PEAK", 1.8))   # 물리: 고속도로 차선 변경 방향 최대 1.9~3.5°, 손 잡음 1.2~1.7°
RETURN_DEG, RETURN_FRAC = 1.5, 0.4
LAT_MIN, LAT_MAX = float(os.environ.get("LATMIN", 2.5)), 6.0
CURVE_DELTA = 0.012           # rad/s, 끝점 요레이트 차(곡선 진입·이탈)
LOBE_MIN_RATIO = 0.3          # 작은 봉우리/큰 봉우리 면적비
LOBE_PAIR_FRAC = 0.8          # 두 봉우리가 전체 |면적|에서 차지하는 비율
MIN_MEAN_KPH, MIN_KPH = 30, 25
WARMUP_S = 3.0
STRONG_DEG = 5.0              # 원시 핸들각 양쪽 ±5° 이상이면 비대칭 복귀(0.8·최대)와 작은 둘째 봉우리 허용
STRONG_RETURN_FRAC, STRONG_LOBE_RATIO, STRONG_LAT_MIN = 0.8, 0.12, 2.0  # 큰 왕복 조향은 조향비 불확실성(14~27)을 감안해 옆 이동 하한 2.0m
CORR_MIN = float(os.environ.get("CORR", 0.7))   # 현대 데이터 격자: 0.6→0.7은 재현 -1건에 미확인 -3건, 0.8은 재현 반토막


def load_csv(path):
    sp, st, sig = [], [], []
    for r in csv.DictReader(open(path, encoding="utf-8")):
        t = int(r["t_ms"]) / 1000
        if r["type"] == "Speed": sp.append((t, float(r["value"])))
        elif r["type"] == "Steering": st.append((t, float(r["value"])))
        elif r["type"] == "Signal": sig.append((t, r["value"]))
    return np.array(sp), np.array(st), sig


def resample(sp, st):
    t0 = max(sp[0, 0], st[0, 0]); t1 = min(sp[-1, 0], st[-1, 0])
    T = np.arange(t0, t1, DT)
    v = np.interp(T, sp[:, 0], sp[:, 1])
    idx = np.searchsorted(st[:, 0], T, side="right") - 1          # 핸들각 ON_CHANGE: 계단 유지(엔진과 동일)
    th = st[np.clip(idx, 0, len(st) - 1), 1]
    return T, v, th


def detect(T, v, th, yaw=None, strong_ok=True, ratio=None):
    """th: 핸들각(도). yaw를 주면 측정 요레이트(rad/s)로 같은 기하를 검사한다(정답 생성용)."""
    if yaw is None: yaw = v * np.radians(th) / ((ratio or RATIO) * WHEELBASE)
    N = len(T); cands = []
    for L in WIN_S:
        n = int(round(L / DT))
        for a in range(EDGE, N - n - EDGE, STEP):
            b = a + n
            if T[a] < T[0] + WARMUP_S: continue
            vv = v[a:b]
            if vv.mean() < MIN_MEAN_KPH / 3.6 or vv.min() < MIN_KPH / 3.6: continue
            y0 = yaw[a - EDGE:a].mean(); y1 = yaw[b:b + EDGE].mean()   # 창 바깥 양쪽 0.5초가 기준선
            if abs(y1 - y0) > CURVE_DELTA: continue
            r = yaw[a:b] - np.linspace(y0, y1, n)
            psi = np.cumsum(r) * DT; peak = np.abs(psi).max()
            if math.degrees(peak) < PEAK_DEG: continue
            strong = strong_ok and th[a:b].min() <= -STRONG_DEG and th[a:b].max() >= STRONG_DEG
            if abs(psi[-1]) > max(math.radians(RETURN_DEG), (STRONG_RETURN_FRAC if strong else RETURN_FRAC) * peak): continue
            lat = np.cumsum(vv * np.sin(psi)) * DT
            if not ((STRONG_LAT_MIN if strong else LAT_MIN) <= abs(lat[-1]) <= LAT_MAX): continue
            # 두 봉우리 검사(0.5초 평활 뒤 부호 구간 면적)
            k = np.ones(EDGE) / EDGE; rs = np.convolve(r, k, mode="same")
            lobes = []; s0 = 0
            for i in range(1, n + 1):
                if i == n or np.sign(rs[i]) != np.sign(rs[s0]):
                    area = rs[s0:i].sum() * DT
                    if abs(area) > 0: lobes.append(area)
                    s0 = i
            if len(lobes) < 2: continue
            top = sorted(lobes, key=abs, reverse=True)
            if np.sign(top[0]) == np.sign(top[1]): continue
            if abs(top[1]) < (STRONG_LOBE_RATIO if strong else LOBE_MIN_RATIO) * abs(top[0]): continue
            if (abs(top[0]) + abs(top[1])) < LOBE_PAIR_FRAC * sum(abs(x) for x in lobes): continue
            # 점수: 한 주기 사인과의 상관 × 복귀 정도
            tmpl = np.sin(2 * np.pi * np.arange(n) / n)
            corr = abs(np.dot(r, tmpl)) / (np.linalg.norm(r) * np.linalg.norm(tmpl) + 1e-12)
            if corr < CORR_MIN: continue
            score = corr * (1 - abs(psi[-1]) / peak)
            # 시작 시각: |r|가 최대의 20%를 처음 넘는 곳
            thr = 0.2 * np.abs(r).max(); on = int(np.argmax(np.abs(r) > thr))
            cands.append(dict(a=a, b=b, start=T[a + on], end=T[b - 1], score=score, lat=float(lat[-1]),
                              peak_deg=math.degrees(peak), kph=float(vv.mean() * 3.6)))
    cands.sort(key=lambda c: -c["score"]); out = []
    for c in cands:
        if any(min(c["b"], o["b"]) - max(c["a"], o["a"]) > 0 for o in out): continue
        out.append(c)
    out.sort(key=lambda c: c["a"])
    return out


def blinker(sig, start, end):
    """sig: [(t, LEFT/RIGHT/OFF)]. 시작 3초 전~+0.3초 안에 켜져 있으면 before, 진행 중 켜지면 late, 아니면 none."""
    state = "OFF"; before = False; late = False; cur = "OFF"
    for t, s in sig:
        if t <= start - 3: cur = s; continue
        break
    on_before = cur != "OFF"
    for t, s in sig:
        if start - 3 <= t < start + 0.3 and s != "OFF": on_before = True
        if start + 0.3 <= t <= end and s != "OFF": late = True
    return "before" if on_before else "late" if late else "none"


def regress():
    base = r"D:\Projects\pleos-observe-lab\logic\src\test\resources\replay"
    for name in ["avd_g_run1.csv", "avd_g_run2.csv", "avd_g_realistic_2laps.csv", "comma2k19_example1.csv"]:
        sp, st, sig = load_csv(os.path.join(base, name)); T, v, th = resample(sp, st)
        ev = detect(T, v, th)
        print(f"== {name}: {len(ev)} lane events")
        for e in ev:
            print(f"   {e['start']:6.1f}-{e['end']:6.1f}s  {blinker(sig, e['start'], e['end']):6s}  lat={e['lat']:+.2f}m peak={e['peak_deg']:.1f}° "
                  f"{e['kph']:.0f}km/h score={e['score']:.2f}")


# ---- 시뮬(Kotlin RealisticDrivingSimTest의 파이썬 이식, 난수열은 다름) ----
def sim_episode(truth, kph, rnd, asym=True):
    ratio = 13.5 + rnd.random() * 3.0; wheelbase = 2.6 + rnd.random() * 0.35
    understeer = 0.0003 + rnd.random() * 0.0004
    # 실데이터(commaCarSegments 현대·기아) 핸들 잔차(2s 이동평균 제거) σ: ≤60km/h 1.25°, 60~100 0.51°, ≥100 0.28°(중앙),
    # 10~90%는 그 0.4~1.9배. AR(1) τ0.8의 2s 잔차는 σ의 0.6배라 σ = 잔차/0.6. 시뮬 기본값(0.3~0.8, 속도 무관)과 다르다.
    if os.environ.get("NOISE") == "ccs":
        med = 1.25 if kph < 60 else 0.51 if kph < 100 else 0.28
        noise_sd = med / 0.6 * (0.4 + rnd.random() * 1.5)
    else:
        noise_sd = 0.3 + rnd.random() * 0.5
    crown = (rnd.random() - 0.5) * 1.0
    def wheel_deg(a, v): return ratio * (wheelbase * a / (v * v) + understeer * a) * 180 / math.pi
    v0 = kph / 3.6; t0 = 12.0 + rnd.random() * 2; direction = 1 if rnd.random() < .5 else -1
    Tl = 3.5 + rnd.random() * 3; W = 3.3 + rnd.random() * .3; A = 2 * math.pi * W / Tl ** 2
    asymR = 0.75 + rnd.random() * 0.58 if asym else 1.0
    curveR = 250 + rnd.random() * 950; hold = 5 + rnd.random() * 15; ramp = 2.5
    end_ev = {"CURVE": t0 + 2 * ramp + hold, "STRAIGHT": t0 + 60}.get(truth, t0 + Tl)
    end = end_ev + 8
    if truth == "LANE_BEFORE": s_on, s_off = t0 - (0.5 + rnd.random() * 2.5), t0 + Tl * (0.6 + rnd.random() * .5)
    elif truth == "LANE_LATE": s_on, s_off = t0 + 0.3 + rnd.random() * 1.2, t0 + Tl + .5
    else: s_on = s_off = None
    dt = 0.05; phi = math.exp(-dt / 0.8); noise = 0.0; t = 0.55
    ts, ths, vs = [], [], []
    while t <= end:
        if truth.startswith("LANE"):
            t1 = Tl / 2 * asymR; t2 = Tl / 2 / asymR; e = t - t0
            resid = A * (t1 - t2) * 2 / math.pi
            if e < 0: a = 0
            elif e < t1: a = direction * A * math.sin(math.pi * e / t1)
            elif e < t1 + t2: a = -direction * A * math.sin(math.pi * (e - t1) / t2)
            elif e < t1 + t2 + 2.5: a = -direction * resid / 2.5
            else: a = 0
        elif truth == "CURVE":
            ss = direction * v0 * v0 / curveR
            a = 0 if (t < t0 or t > end_ev) else ss * (t - t0) / ramp if t < t0 + ramp else ss * (end_ev - t) / ramp if t > end_ev - ramp else ss
        else: a = 0
        noise = phi * noise + math.sqrt(1 - phi * phi) * noise_sd * rnd.uniform(-1.73, 1.73)
        ang = round((wheel_deg(a, max(v0, 1)) + noise + crown) * 10) / 10
        ts.append(t); ths.append(ang); vs.append(max(0, v0 + rnd.uniform(-.04, .04)))
        t += dt
    sig = []
    if s_on is not None: sig = [(0, "OFF"), (s_on, "LEFT" if direction > 0 else "RIGHT"), (s_off, "OFF")]
    else: sig = [(0, "OFF")]
    sp = np.array(list(zip(ts, vs))); st = np.array(list(zip(ts, ths)))
    return sp, st, sig, t0, end_ev


def sim(n=100, seed=20261025):
    rnd = random.Random(seed)
    print(f"{'모양':12s} " + " ".join(f"{k:>7d}" for k in (40, 60, 80, 100, 120)))
    for truth in ["LANE_BEFORE", "LANE_NONE", "LANE_LATE", "CURVE", "STRAIGHT"]:
        row = []
        for kph in (40, 60, 80, 100, 120):
            hit = 0; exact = 0
            for _ in range(n):
                sp, st, sig, t0, end_ev = sim_episode(truth, kph, rnd)
                T, v, th = resample(sp, st); ev = detect(T, v, th)
                if truth.startswith("LANE"):
                    m = [e for e in ev if e["start"] < end_ev + 1.5 and e["end"] > t0 - 1.5]
                    if m:
                        hit += 1
                        want = {"LANE_BEFORE": "before", "LANE_NONE": "none", "LANE_LATE": "late"}[truth]
                        exact += blinker(sig, m[0]["start"], m[0]["end"]) == want
                else:
                    hit += bool(ev)
            row.append(f"{hit*100/n:5.1f}%" + (f"({exact*100/n:.0f})" if truth.startswith("LANE") else "    "))
        print(f"{truth:12s} " + " ".join(f"{x:>7s}" for x in row))
    print("LANE 행: 탐지율(깜빡이 시점까지 일치율). CURVE/STRAIGHT 행: 오탐률(낮아야 함).")


def ccs():
    sys.path.insert(0, r"C:\data\tools"); import ccs_eval_v2 as E
    det = {}
    for s in E.truth:
        sp, st, sig = load_csv(rf"{E.ROOT}\csv\{s['segment']}.csv"); T, v, th = resample(sp, st)
        det[s["segment"]] = [(int(e["start"] * 1000), int(e["end"] * 1000)) for e in detect(T, v, th)]
    E.score(det, "w16c 끝점 기준선 창")


if __name__ == "__main__":
    {"regress": regress, "sim": sim, "ccs": ccs}[sys.argv[1]]()
