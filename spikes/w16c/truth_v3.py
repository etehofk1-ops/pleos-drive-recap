"""정답 v3: 측정 요레이트(ESP12)에 끝점 기준선 창 기하를 적용한다. 핸들각은 쓰지 않는다.
- 사건: 요레이트로 잰 옆 이동 2.5~5.5m, 방향 최대 ≥1.5°, 복귀, 끝점 요레이트 차 ≤ 0.012 rad/s, 두 봉우리.
- 주의: 탐지기와 같은 기하 정의를 쓰므로 '같은 모양'에 편향된 정답이다. 센서는 독립(자이로 vs 핸들각)이다.
- 정답 밖 탐지 검증: 탐지 창마다 요레이트로 옆 이동을 다시 재서 ≥2.0m면 '물리적으로 확인'으로 센다.
출력: C:\data\commaCarSegments\truth_v3.json
"""
import json, os, sys, numpy as np
sys.path.insert(0, os.path.dirname(__file__)); import lanewin as L
sys.path.insert(0, r"C:\data\tools"); from ccs_truth_v2 import blinker_state
ROOT = r"C:\data\commaCarSegments"
old = {s["segment"]: s for s in json.load(open(ROOT + r"\truth_v2.json", encoding="utf-8"))}
L.PEAK_DEG, L.LAT_MIN, L.LAT_MAX = 1.5, 2.5, 5.5
out = []
for seg, s in old.items():
    ser = dict(np.load(rf"{ROOT}\series\{seg}.npz")); yaw, whl, sas = ser["yaw"], ser["whl"], ser["sas"]
    t0 = max(yaw[0, 0], whl[0, 0], sas[0, 0]); t1 = min(yaw[-1, 0], whl[-1, 0], sas[-1, 0])
    T = np.arange(t0, t1, L.DT); v = np.interp(T, whl[:, 0], whl[:, 1])
    y = np.radians(np.interp(T, yaw[:, 0], yaw[:, 1])); th = np.zeros_like(T)
    ev = L.detect(T, v, th, yaw=y, strong_ok=False)
    bt, bon = blinker_state(ser["lamp"]); sig = [(t, "ON" if o else "OFF") for t, o in zip(bt, bon)]
    events = []
    for e in ev:
        a, b = e["start"], e["end"]
        before = bool(((bt >= a - 3) & (bt < a + 0.3) & bon).any()); during = bool(((bt >= a + 0.3) & (bt <= b) & bon).any())
        events.append({"start_ms": int(a * 1000), "end_ms": int(b * 1000), "lat_m": round(e["lat"], 2), "kph": round(e["kph"]),
                       "peak_deg": round(e["peak_deg"], 1), "blinker": "before" if before else "late" if during else "none", "clean": True, "reasons": []})
    out.append({**{k: s[k] for k in ("segment", "model", "duration_s", "moving_s")}, "truth": events})
json.dump(out, open(ROOT + r"\truth_v3.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
allev = [e for s in out for e in s["truth"]]
import collections
print("v3 사건", len(allev), "깜빡이", collections.Counter(e["blinker"] for e in allev), "속도대", collections.Counter("≤60" if e["kph"]<60 else "60~100" if e["kph"]<100 else "≥100" for e in allev))
print("브랜드", collections.Counter(s["model"].split("_")[0] for s in out for e in s["truth"]))
