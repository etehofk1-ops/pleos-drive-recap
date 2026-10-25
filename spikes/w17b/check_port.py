"""W17b: Kotlin 이식 검증 — 실데이터 하네스 JSONL의 onset_ms(Kotlin)가 파이썬 참조(resid10)와 같은가.
사용: python check_port.py after.jsonl   (RealDataBatchTest 출력, onset_ms 열 필요)
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "w16c"))
import lanewin as L

ROOT = r"C:\data\commaCarSegments"
rows = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8")]
n = same = 0; bad = []
for j in rows:
    cards = [c for c in j["cards"] if c.get("onset_ms") is not None]
    if not cards: continue
    sp, st, sig = L.load_csv(rf"{ROOT}\csv\{j['segment']}.csv"); T, v, th = L.resample(sp, st)
    yaw = v * np.radians(th) / (L.RATIO * L.WHEELBASE)
    for e in L.detect(T, v, th):
        c = next((c for c in cards if abs(c["start_ms"] - e["start"] * 1000) <= 150), None)
        if c is None: continue
        a, b = e["a"], e["b"]; m = b - a
        y0 = yaw[a - L.EDGE:a].mean(); y1 = yaw[b:b + L.EDGE].mean(); r = yaw[a:b] - np.linspace(y0, y1, m)
        k = int(np.argmax(np.abs(r) > 0.10 * np.abs(r).max())); py = int(round(T[a + k] * 1000))
        n += 1
        if abs(py - c["onset_ms"]) <= 1: same += 1
        else: bad.append((j["segment"][-26:], c["start_ms"], c["onset_ms"], py))
print(f"파이썬 참조와 비교한 창 카드 {n}장 중 onset 일치 {same}장 (±1ms)")
for b in bad[:10]: print("  불일치", b)
