"""Opus 점검: 핸들각만으로 '자이로 확인/미확인' 탐지를 가를 특징이 있는지. 값 고르기는 BRAND(기본 HYUNDAI)만."""
import json, os, sys, math, numpy as np
sys.path.insert(0, os.path.dirname(__file__)); import lanewin as L, eval_v3 as E
BR = os.environ.get("BRAND", "HYUNDAI")
rows = []
for s in E.truth:
    if not s["model"].startswith(BR): continue
    sp, st, sig = L.load_csv(rf"{E.ROOT}\csv\{s['segment']}.csv"); T, v, th = L.resample(sp, st)
    ser = dict(np.load(rf"{E.ROOT}\series\{s['segment']}.npz")); ym = np.radians(np.interp(T, ser["yaw"][:, 0], ser["yaw"][:, 1]))
    for e in L.detect(T, v, th):
        c = E.yaw_check(T, v, ym, e["start"], e["end"]); ok = bool(c and abs(c["lat"]) >= 2.0 and c["returned"])
        a, b = e["a"], e["b"]; raw = th[a:b]
        accel = abs(v[b - 1] - v[a]) / ((b - a) * L.DT)              # 창 안 속도 변화율 m/s²
        swing = raw.max() - raw.min()                                 # 원시 핸들 진폭(°)
        rows.append(dict(ok=ok, lat=abs(e["lat"]), peak=e["peak_deg"], kph=e["kph"], score=e["score"],
                         dur=(b - a) * L.DT, accel=accel, swing=swing, strong=raw.min() <= -5 and raw.max() >= 5))
print(f"{BR}: 탐지 {len(rows)}, 확인 {sum(r['ok'] for r in rows)}")
for k in ("lat", "peak", "kph", "score", "dur", "accel", "swing"):
    a = [r[k] for r in rows if r["ok"]]; b = [r[k] for r in rows if not r["ok"]]
    q = lambda x: np.percentile(x, [25, 50, 75]).round(2).tolist() if x else []
    print(f"  {k:6s} 확인 {q(a)}  미확인 {q(b)}")
print("  strong 비율: 확인", sum(r["strong"] for r in rows if r["ok"]), "/", sum(r["ok"] for r in rows), " 미확인", sum(r["strong"] for r in rows if not r["ok"]), "/", sum(not r["ok"] for r in rows))
