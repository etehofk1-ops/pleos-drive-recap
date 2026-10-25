"""W17b: 창이 조작 한복판에서 시작했는지(분절 어긋남)를 창 안 지표로 가를 수 있는지 본다.
지표: s0=창 첫 0.5초 이동/최대 잔차, wide1.5=창 앞 1.5초 움직임 폭/최대 잔차, e0=끝 쪽 대칭, r0=첫 0.5초 최대 잔차 비율.
결론: 정상 창도 같은 범위(s0 0.02~0.94)라 갈리지 않는다. 사용: python window_start_features.py
"""
import os, sys, json, numpy as np
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "w16c"))
import lanewin as L
R = r"C:\data\commaCarSegments"
truth = json.load(open(R + r"\truth_v3.json", encoding="utf-8"))
rows = []
for s in truth:
    sp, st, sig = L.load_csv(rf"{R}\csv\{s['segment']}.csv"); T, v, th = L.resample(sp, st)
    yaw = v * np.radians(th) / (L.RATIO * L.WHEELBASE)
    for e in L.detect(T, v, th):
        best = None
        for t in s["truth"]:
            if e["start"] * 1000 < t["end_ms"] + 1500 and e["end"] * 1000 > t["start_ms"] - 1500: best = t
        a, b = e["a"], e["b"]; n = b - a
        y0 = yaw[a - 5:a].mean(); y1 = yaw[b:b + 5].mean(); r = yaw[a:b] - np.linspace(y0, y1, n)
        peak_r = np.abs(r).max()
        # 창 시작 쪽 이동량: 창 앞 0.5초 평균 -> 창 첫 0.5초 평균
        s0 = abs(yaw[a:a + 5].mean() - y0) / peak_r
        # 앞 1.5초 안에서의 움직임 폭(최대-최소) / peak
        wide = (yaw[a - 15:a].max() - yaw[a - 15:a].min()) / peak_r if a >= 15 else float("nan")
        # 끝점 쪽도(대칭 지표): 창 끝 0.5초 -> 끝점 평균
        e0 = abs(yaw[b - 5:b].mean() - y1) / peak_r
        # 첫 0.5초 안의 |r| 최대 / peak
        r0 = np.abs(r[:5]).max() / peak_r
        err = (e["start"] - best["start_ms"] / 1000) if best else float("nan")
        rows.append((err, s["segment"][-26:], e["start"], T[a], s0, wide, e0, r0, e["kph"]))
rows.sort(key=lambda x: (np.isnan(x[0]), -abs(x[0]) if not np.isnan(x[0]) else 0))
for r in rows:
    print(f"err={r[0]:+5.1f} {r[1]:26s} start={r[2]:6.1f} wstart={r[3]:6.1f} s0={r[4]:.2f} wide1.5={r[5]:.2f} e0={r[6]:.2f} r0={r[7]:.2f} {r[8]:.0f}km/h")
