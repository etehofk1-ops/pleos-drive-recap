"""Opus 점검: 탐지기와 기하 정의를 공유하지 않는 정답으로 재현율을 다시 잰다.

정답 = '깜빡이 의도 사건': 깜빡이 램프가 켜진 구간(0.8초 유지로 점멸 메움) 중
  - 켜짐 1~15초, 켜진 동안 평균 속도 ≥ 40km/h, [켬−3, 끔+6] 최저 속도 ≥ 30km/h
  - 회전·램프 아님: [켬−3, 끔+8]에서 요레이트 적분 방향 변화(선형 추세 제거 전, 중앙값만 제거) 최대−최소 < 20°
  - 구간 경계 4초 안 제외
검출 = 보이는 차선 변경 카드가 [켬−3, 끔+6]과 겹침.
보조: 같은 구간에서 자이로로 잰 옆 이동 최대 |y| ≥ 2m인지('자이로로도 차선 이동이 보이는 깜빡이 사건').
"""
import json, os, sys, math, collections, numpy as np
sys.path.insert(0, r"C:\data\tools"); from ccs_truth_v2 import blinker_state
ROOT = r"C:\data\commaCarSegments"
LANE = {"LANE_SIGNALLED", "LANE_NO_SIGNAL", "LANE_LATE_SIGNAL", "LANE_CHANGE"}
meta = {s["segment"]: s for s in json.load(open(ROOT + r"\truth_v2.json", encoding="utf-8"))}

def intent_events(seg):
    ser = dict(np.load(rf"{ROOT}\series\{seg}.npz")); yaw, whl = ser["yaw"], ser["whl"]
    bt, bon = blinker_state(ser["lamp"])
    T = np.arange(max(yaw[0, 0], whl[0, 0]), min(yaw[-1, 0], whl[-1, 0]), 0.05)
    v = np.interp(T, whl[:, 0], whl[:, 1]); y = np.radians(np.interp(T, yaw[:, 0], yaw[:, 1]))
    y = y - np.median(y[v > 8]) if (v > 8).sum() > 100 else y
    runs = []; on = None
    for t, b in zip(bt, bon):
        if b and on is None: on = t
        if not b and on is not None: runs.append((on, t)); on = None
    out = []
    for a, b in runs:
        if not (1.0 <= b - a <= 15) or a < T[0] + 4 or b > T[-1] - 8: continue
        m_on = (T >= a) & (T <= b); m = (T >= a - 3) & (T <= b + 6); mt = (T >= a - 3) & (T <= b + 8)
        if v[m_on].mean() < 40 / 3.6 or v[m].min() < 30 / 3.6: continue
        psi = np.cumsum(y[mt]) * 0.05
        if math.degrees(psi.max() - psi.min()) >= 20: continue
        # 자이로 옆 이동(창 [a-1, b+6], 끝점 기준선)
        mm = (T >= a - 1) & (T <= b + 6); yy = y[mm]; n = len(yy)
        base = np.linspace(yy[:10].mean(), yy[-10:].mean(), n); ps = np.cumsum(yy - base) * 0.05
        lat = np.cumsum(v[mm] * np.sin(ps)) * 0.05
        out.append(dict(on=a, off=b, kph=float(v[m_on].mean() * 3.6), gyro_lat=float(np.abs(lat).max())))
    return out

def score(path, label):
    eng = {j["segment"]: j for j in map(json.loads, open(path, encoding="utf-8"))}
    tot = collections.Counter(); hit = collections.Counter()
    for seg, s in meta.items():
        br = s["model"].split("_")[0]
        cards = [c for c in eng.get(seg, {}).get("cards", []) if c["visible"] and c["kind"] in LANE]
        for e in intent_events(seg):
            g = "자이로 옆이동≥2m" if e["gyro_lat"] >= 2 else "자이로 옆이동<2m"
            ok = any(c["start_ms"] / 1000 < e["off"] + 6 and c["end_ms"] / 1000 > e["on"] - 3 for c in cards)
            for k in ((br, "전체"), (br, g)): tot[k] += 1; hit[k] += ok
    print(f"# {label}")
    for k in sorted(tot): print(f"  {k[0]:8s} {k[1]:14s}: {hit[k]}/{tot[k]}")

if __name__ == "__main__":
    for p, l in [(ROOT + r"\engine_v152.jsonl", "1.5.2"), (ROOT + r"\engine_v160.jsonl", "1.6.0")]: score(p, l)
