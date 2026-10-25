"""시나리오 H = G + 1.7.0 '깜빡이 켜 둔 채 주행' 구간.

E3(늦게 켬) 차선 변경이 끝난 뒤 깜빡이를 끄지 않고 시속 60km로 20초 더 달리게 한다(카드 기준 15초 이상, 30km/h 이상).
E1(먼저 켬)은 G 그대로라 켬 거리 약 33m(2초 × 60km/h)로 '충분히 일찍' 문구가 나온다.
사용: python scripts/make-recap-scenario-h.py  → scripts/recap-scenario-h.json
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
OFF_AT, HOLD_MS = 27_500, 20_000  # G에서 E3 뒤 깜빡이 끔 시각, 켜 둔 채 더 달릴 시간
SHIFT = HOLD_MS + 2_000

g = json.loads((HERE / "recap-scenario-g.json").read_text(encoding="utf-8"))
steps = []
for s in g["steps"]:
    if s["atMs"] < OFF_AT:
        steps.append(s)
        continue
    if s["atMs"] == OFF_AT:
        # 깜빡이를 끄지 않고 그대로 달린다. 속도는 5초마다 다시 써서 유지한다.
        for t in range(OFF_AT, OFF_AT + SHIFT, 5_000):
            steps.append({"atMs": t, "controls": {"speedKph": 60},
                          "caption": "[E7] 깜빡이 켜 둔 채 주행", "navi": s.get("navi")} if s.get("navi") is not None
                         else {"atMs": t, "controls": {"speedKph": 60}, "caption": "[E7] 깜빡이 켜 둔 채 주행"})
    steps.append({**s, "atMs": s["atMs"] + SHIFT})

h = {**g, "name": "H — G + 깜빡이 켜 둔 채 주행(1.7.0)",
     "note": "G에 E7(차선 변경 뒤 깜빡이를 20초 켜 둔 채 60km/h)을 끼웠다. 1.7.0 켬 거리(E1)와 켜 둔 채 주행 카드 확인용.",
     "expected": g["expected"] + ["BLINKER_LEFT_ON 약 20초"], "steps": steps}
(HERE / "recap-scenario-h.json").write_text(json.dumps(h, ensure_ascii=False, indent=1), encoding="utf-8")
print("steps", len(steps), "end", steps[-1]["atMs"])
