"""W17b: master와 w17b 하네스 출력(RealDataBatchTest JSONL)을 카드 단위로 비교한다.
- 증명: 종류별 개수, 카드마다 (start_ms, end_ms, kind, candidate, visible, lat_m, peak_rad, timing) 가 똑같은가.
- 바뀐 것: lead_m / lead_ms 분포(전·후), 사전 깜빡이 카드별 표.
사용: python compare_batches.py before.jsonl after.jsonl
"""
import collections, json, sys
import numpy as np

before, after = ([json.loads(l) for l in open(p, encoding="utf-8")] for p in sys.argv[1:3])
SAME = ("start_ms", "end_ms", "kind", "candidate", "visible", "lat_m", "peak_rad", "timing", "min_mps", "max_mps", "blinker_ms", "blinker_m")
assert [s["segment"] for s in before] == [s["segment"] for s in after], "세그먼트 목록이 다르다"
cb, ca = collections.Counter(), collections.Counter(); diffs = []; pairs = []
for sb, sa in zip(before, after):
    assert len(sb["cards"]) == len(sa["cards"]), (sb["segment"], len(sb["cards"]), len(sa["cards"]))
    for x, y in zip(sb["cards"], sa["cards"]):
        cb[x["kind"]] += 1; ca[y["kind"]] += 1
        for k in SAME:
            if x.get(k) != y.get(k): diffs.append((sb["segment"], k, x.get(k), y.get(k)))
        if x["lead_m"] is not None or y["lead_m"] is not None: pairs.append((sb["segment"], x, y))
print("카드 종류별 개수 before:", dict(sorted(cb.items()))); print("카드 종류별 개수 after :", dict(sorted(ca.items())))
print("종류 개수 동일:", cb == ca, "| 카드 필드(start/end/kind/candidate/visible/lat/peak/timing/속도/B) 불일치:", len(diffs))
for d in diffs[:10]: print("  불일치", d)
print(f"\n리드가 붙은 카드 {len(pairs)}장 (before 기준 붙은 것 {sum(1 for _,x,_ in pairs if x['lead_m'] is not None)}, after {sum(1 for _,_,y in pairs if y['lead_m'] is not None)})")
print(f"{'세그먼트':34s} {'종류':16s} {'start':>7s} {'onset':>7s} {'lead_ms 전→후':>16s} {'lead_m 전→후':>16s}")
for seg, x, y in pairs:
    on = y.get("onset_ms")
    print(f"{seg[-34:]:34s} {y['kind']:16s} {x['start_ms']/1000:7.1f} {(on/1000 if on is not None else float('nan')):7.1f} {str(x['lead_ms'])+'→'+str(y['lead_ms']):>16s} {str(round(x['lead_m'],1) if x['lead_m'] is not None else None)+'→'+str(round(y['lead_m'],1) if y['lead_m'] is not None else None):>16s}")


def dist(label, vals):
    v = np.array([q for q in vals if q is not None])
    if not len(v): return
    print(f"{label:34s} n={len(v):2d} 최소 {v.min():5.1f} 중앙 {np.median(v):5.1f} 평균 {v.mean():5.1f} p90 {np.percentile(v,90):5.1f} 최대 {v.max():5.1f} | <30m {int((v<30).sum())} <100m {int((v<100).sum())}")


print()
for name, sel in (("LANE_SIGNALLED", lambda y: y["kind"] == "LANE_SIGNALLED"), ("사전 깜빡이 전체(창 카드)", lambda y: y.get("onset_ms") is not None),
                  ("저속 회전 후보(TURN, 창 아님)", lambda y: y["kind"] == "TURN"), ("전체", lambda y: True)):
    sub = [(x, y) for _, x, y in pairs if sel(y)]
    dist(name + " 전", [x["lead_m"] for x, _ in sub]); dist(name + " 후", [y["lead_m"] for _, y in sub])
ch = [(x["lead_m"], y["lead_m"]) for _, x, y in pairs if x["lead_m"] is not None and y["lead_m"] is not None]
dm = np.array([b - a for a, b in ch]); print(f"\n카드별 변화 후-전(m): 중앙 {np.median(dm):+.1f} 평균 {dm.mean():+.1f} 최소 {dm.min():+.1f} 최대 {dm.max():+.1f}, 짧아진 카드 {(dm < -0.05).sum()}/{len(dm)}, 같음 {(abs(dm) <= 0.05).sum()}, 길어짐 {(dm > 0.05).sum()}")
