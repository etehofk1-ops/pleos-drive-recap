"""정답 v2로 채점. 사용:
  python ccs_eval_v2.py engine <engine.jsonl> [라벨]       — 판정 엔진 카드(보이는 차선 변경 카드)
  python ccs_eval_v2.py window <ratio> <lat_min> <lat_max> <peak_deg> <hp_s>  — 핸들 슬라이딩 창(지연 허용)
현대(HYUNDAI_*)=값 고르기용, 기아(KIA_*)=검증용으로 따로 센다.
재현율: clean 사건 중 겹치는 탐지(±1.5초)가 있는 비율. 오탐: 어떤 정답 사건(clean·애매)과도 안 겹치는 탐지, 주행(25km/h↑) 1시간당.
"""
import csv, json, math, sys, collections
import numpy as np
ROOT = r"C:\data\commaCarSegments"
truth = json.load(open(ROOT + r"\truth_v2.json", encoding="utf-8"))
LANE = {"LANE_SIGNALLED", "LANE_NO_SIGNAL", "LANE_LATE_SIGNAL", "LANE_CHANGE"}

def window_detect(path, ratio, lat_min, lat_max, peak_deg, hp):
    sp, st = [], []
    for r in csv.DictReader(open(path, encoding="utf-8")):
        if r["type"] == "Speed": sp.append((int(r["t_ms"]) / 1000, float(r["value"])))
        elif r["type"] == "Steering": st.append((int(r["t_ms"]) / 1000, float(r["value"])))
    sp, st = np.array(sp), np.array(st)
    T = np.arange(max(sp[0, 0], st[0, 0]) + 0.5, min(sp[-1, 0], st[-1, 0]) - 0.5, 0.1)
    v = np.interp(T, sp[:, 0], sp[:, 1]); th = np.radians(np.interp(T, st[:, 0], st[:, 1]))
    yaw = v * th / (ratio * 2.7)
    k = int(hp / 0.1); y = yaw - np.convolve(yaw, np.ones(k) / k, mode="same")
    c = []
    for win in np.arange(3.0, 8.01, 0.5):
        n = int(win / 0.1)
        for i in range(0, len(T) - n, 2):
            vv = v[i:i + n]
            if vv.mean() < 30 / 3.6 or vv.min() < 25 / 3.6: continue
            psi = np.cumsum(y[i:i + n]) * 0.1; peak = np.abs(psi).max()
            lat = np.cumsum(vv * np.sin(psi)) * 0.1
            if math.degrees(peak) >= peak_deg and abs(psi[-1]) <= max(math.radians(1.5), 0.4 * peak) and lat_min <= abs(lat[-1]) <= lat_max:
                c.append([T[i], T[i] + win])
    c.sort(); m = []
    for x in c:
        if m and x[0] <= m[-1][1]: m[-1][1] = max(m[-1][1], x[1])
        else: m.append(x)
    return [(int(a * 1000), int(b * 1000)) for a, b in m]

def score(detections, label):
    res = {}
    for split in ("HYUNDAI", "KIA"):
        hit = collections.Counter(); tot = collections.Counter(); fp = 0; fp_amb = 0; hours = 0.0
        for s in truth:
            if not s["model"].startswith(split): continue
            d = detections.get(s["segment"], []); hours += s["moving_s"] / 3600
            used = set()
            for ev in s["truth"]:
                ov = [j for j, (a, b) in enumerate(d) if a < ev["end_ms"] + 1500 and b > ev["start_ms"] - 1500]
                used.update(ov)
                if not ev["clean"]: continue
                g = "깜빡이" if ev["blinker"] != "none" else "무깜빡이"
                band = "≤60" if ev["kph"] < 60 else "60~100" if ev["kph"] < 100 else "≥100"
                tot[(g, band)] += 1; hit[(g, band)] += bool(ov)
            amb = [(e["start_ms"], e["end_ms"]) for e in s["truth"] if not e["clean"]]
            for j, (a, b) in enumerate(d):
                if j in used: continue
                fp += 1
        res[split] = (hit, tot, fp, hours)
    print(f"# {label}")
    for split, (hit, tot, fp, hours) in res.items():
        H = sum(hit.values()); N = sum(tot.values())
        hb = sum(v for k, v in hit.items() if k[0] == "깜빡이"); nb = sum(v for k, v in tot.items() if k[0] == "깜빡이")
        print(f"  {split}: clean 재현 {H}/{N} (깜빡이 확인 {hb}/{nb}) | 정답 밖 탐지 {fp}건 / 주행 {hours*60:.0f}분 = 시간당 {fp/max(hours,1e-9):.1f}")
        print("     " + ", ".join(f"{k[0]} {k[1]}: {hit[k]}/{tot[k]}" for k in sorted(tot)))

if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "engine":
        eng = {j["segment"]: j for j in map(json.loads, open(sys.argv[2], encoding="utf-8"))}
        det = {seg: [(c["start_ms"], c["end_ms"]) for c in j["cards"] if c["visible"] and c["kind"] in LANE] for seg, j in eng.items()}
        score(det, sys.argv[3] if len(sys.argv) > 3 else "engine")
    else:
        ratio, lmin, lmax, peak, hp = map(float, sys.argv[2:7])
        det = {s["segment"]: window_detect(rf"{ROOT}\csv\{s['segment']}.csv", ratio, lmin, lmax, peak, hp) for s in truth}
        score(det, f"window ratio={ratio} lat={lmin}~{lmax} peak={peak} hp={hp}")
