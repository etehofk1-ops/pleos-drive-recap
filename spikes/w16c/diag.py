"""진단: (1) run1/run2에서 놓친 사건 주변 창이 어느 조건에서 떨어지는지 (2) 시뮬 직진 오탐 vs 차선 변경의 점수·모양 분포."""
import sys, math, random, os
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import lanewin as L

def window_stats(T, v, th, a, b):
    yaw = v * np.radians(th) / (L.RATIO * L.WHEELBASE); n = b - a
    y0 = yaw[a:a + L.EDGE].mean(); y1 = yaw[b - L.EDGE:b].mean()
    r = yaw[a:b] - np.linspace(y0, y1, n); psi = np.cumsum(r) * L.DT; peak = np.abs(psi).max()
    lat = np.cumsum(v[a:b] * np.sin(psi)) * L.DT
    rs = np.convolve(r, np.ones(L.EDGE) / L.EDGE, mode="same")
    lobes = []; s0 = 0
    for i in range(1, n + 1):
        if i == n or np.sign(rs[i]) != np.sign(rs[s0]):
            ar = rs[s0:i].sum() * L.DT
            if abs(ar) > 0: lobes.append(ar)
            s0 = i
    top = sorted(lobes, key=abs, reverse=True)
    tmpl = np.sin(2 * np.pi * np.arange(n) / n)
    corr = abs(np.dot(r, tmpl)) / (np.linalg.norm(r) * np.linalg.norm(tmpl) + 1e-12)
    smooth = np.linalg.norm(rs) / (np.linalg.norm(r) + 1e-12)
    return dict(dy=abs(y1 - y0), peak=math.degrees(peak), end=math.degrees(abs(psi[-1])), lat=lat[-1],
                lobes=len(lobes), ratio=(abs(top[1]) / abs(top[0]) if len(top) > 1 else 0),
                pair=((abs(top[0]) + abs(top[1])) / sum(abs(x) for x in lobes) if len(top) > 1 else 0),
                corr=corr, smooth=smooth)

def missed():
    base = r"D:\Projects\pleos-observe-lab\logic\src\test\resources\replay"
    for name, ranges in [("avd_g_run1.csv", [(21, 29)]), ("avd_g_run2.csv", [(13, 22), (29, 37)])]:
        sp, st, sig = L.load_csv(os.path.join(base, name)); T, v, th = L.resample(sp, st)
        print("==", name, [(t, a) for t, a in st if abs(a) >= 3 and ranges[0][0] <= t <= ranges[-1][1]][:30])
        for lo, hi in ranges:
            best = None
            for Ls in L.WIN_S:
                n = int(round(Ls / L.DT))
                for a in range(int((lo - T[0]) / L.DT), int((hi - T[0]) / L.DT) - n, 2):
                    s = window_stats(T, v, th, a, a + n); s["t"] = (round(T[a], 1), round(T[a + n - 1], 1))
                    key = (abs(s["lat"]) >= 1.5, s["peak"], )
                    if best is None or (abs(s["lat"]), s["corr"]) > (abs(best["lat"]), best["corr"]): best = s
            print("  best by lat:", {k: (round(x, 3) if isinstance(x, float) else x) for k, x in best.items()})

def dist():
    rnd = random.Random(7)
    for truth, kph in [("STRAIGHT", 100), ("STRAIGHT", 120), ("CURVE", 100), ("LANE_NONE", 100), ("LANE_NONE", 60), ("LANE_NONE", 40)]:
        rows = []
        for _ in range(60):
            sp, st, sig, t0, end_ev = L.sim_episode(truth, kph, rnd); T, v, th = L.resample(sp, st)
            for e in L.detect(T, v, th):
                s = window_stats(T, v, th, e["a"], e["b"]); rows.append(s)
        if not rows: print(truth, kph, "no detections"); continue
        q = lambda k: np.percentile([r[k] for r in rows], [10, 50, 90]).round(2).tolist()
        print(f"{truth:10s} {kph:3d}: n={len(rows):3d} corr{q('corr')} smooth{q('smooth')} |lat|{np.percentile([abs(r['lat']) for r in rows],[10,50,90]).round(2).tolist()} peak{q('peak')} ratio{q('ratio')} pair{q('pair')}")



def ccsmiss():
    """실데이터에서 놓친 clean 사건마다 가장 그럴듯한 창의 조건값을 뽑아 첫 실패 조건을 센다."""
    import collections
    sys.path.insert(0, r"C:\data\tools"); import ccs_eval_v2 as E
    fails = collections.Counter(); rows = []
    for s in E.truth:
        sp, st, sig = L.load_csv(rf"{E.ROOT}\csv\{s['segment']}.csv"); T, v, th = L.resample(sp, st)
        ev = L.detect(T, v, th)
        for t in s["truth"]:
            if not t["clean"]: continue
            if any(e["start"] * 1000 < t["end_ms"] + 1500 and e["end"] * 1000 > t["start_ms"] - 1500 for e in ev): continue
            lo, hi = t["start_ms"] / 1000 - 1.5, t["end_ms"] / 1000 + 1.5; best = None
            for Ls in L.WIN_S:
                n = int(round(Ls / L.DT))
                for a in range(max(L.EDGE, int((lo - T[0]) / L.DT)), min(len(T) - n - L.EDGE, int((hi - T[0]) / L.DT) - n), 2):
                    yaw = v * np.radians(th) / (L.RATIO * L.WHEELBASE)
                    y0 = yaw[a - L.EDGE:a].mean(); y1 = yaw[a + n:a + n + L.EDGE].mean()
                    r = yaw[a:a + n] - np.linspace(y0, y1, n); psi = np.cumsum(r) * L.DT; peak = np.abs(psi).max()
                    lat = np.cumsum(v[a:a + n] * np.sin(psi)) * L.DT
                    tmpl = np.sin(2 * np.pi * np.arange(n) / n)
                    corr = abs(np.dot(r, tmpl)) / (np.linalg.norm(r) * np.linalg.norm(tmpl) + 1e-12)
                    # 조건별 통과 수를 점수로 삼아 '가장 아깝게 떨어진' 창을 고른다
                    checks = [("dy", abs(y1 - y0) <= L.CURVE_DELTA), ("peak", math.degrees(peak) >= L.PEAK_DEG),
                              ("return", abs(psi[-1]) <= max(math.radians(L.RETURN_DEG), 0.4 * peak)),
                              ("lat", L.LAT_MIN <= abs(lat[-1]) <= L.LAT_MAX), ("corr", corr >= L.CORR_MIN),
                              ("speed", v[a:a + n].min() >= L.MIN_KPH / 3.6)]
                    npass = sum(ok for _, ok in checks)
                    if best is None or npass > best[0]: best = (npass, [k for k, ok in checks if not ok], dict(dy=round(abs(y1 - y0), 3), peak=round(math.degrees(peak), 1), end=round(math.degrees(abs(psi[-1])), 1), lat=round(float(lat[-1]), 2), corr=round(corr, 2), L=Ls))
            if best is None: fails["no-window"] += 1; continue
            for k in best[1]: fails[k] += 1
            rows.append((s["model"][:14], t["kph"], t["blinker"], best[1], best[2]))
    for r in rows: print(r)
    print("실패 조건 집계(놓친 사건 기준, 한 사건이 여러 조건에 걸릴 수 있음):", dict(fails))

if __name__ == "__main__":
    {"missed": missed, "dist": dist, "ccsmiss": ccsmiss}[sys.argv[1]]()
