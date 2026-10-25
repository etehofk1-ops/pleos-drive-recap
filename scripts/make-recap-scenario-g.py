"""시연 영상 v6용 시나리오 G: 차선 변경 3종(D) + 모의 내비 우회전·유턴(F) + 급제동(D)을 한 주행으로.
demo-navi-feed.ps1 형식. caption 앞의 [E1]~[E6]·[..] 태그는 HUD 동기화용."""
import json, pathlib

steps = []
def s(at, controls, caption, navi=None, wait=None):
    x = {"atMs": at, "controls": controls, "caption": caption}
    if navi is not None: x["navi"] = navi
    if wait is not None: x["waitForSpeedKph"] = wait
    steps.append(x)

OFF = {"guiding": False, "action": None, "distanceMeters": None}
def nav(action, m): return {"guiding": True, "action": action, "distanceMeters": m}

s(0, {"gear": "P", "speedKph": 0, "steering": 0, "brake": 0, "turnSignal": "Off"}, "[P] 출발 전 정차", OFF)
s(2000, {"gear": "D", "speedKph": 60, "steering": 0, "brake": 0}, "[GO] 출발 · 시속 60km",
  nav("TURN_STRAIGHT", 1200), {"min": 58, "max": 62, "timeoutMs": 30000, "holdMs": 1000})
s(4500, {"speedKph": 60}, "[..] 순항")
# E1: 깜빡이 먼저 → 차선 변경
s(6000, {"speedKph": 60, "turnSignal": "Blink left"}, "[E1] 깜빡이 먼저 켬")
# 시속 60km 실제 차선 변경 크기(핸들 최대 7°, 4.9초, 자전거 모델 옆 이동 약 3.1~3.7m)
for i, a in enumerate([3, 5, 7, 7, 6, 4, 1, -2, -5, -6, -6, -5, -3, -1, 0]):
    s(8000 + i * 350, {"speedKph": 60, "steering": a}, "[E1] 차선 변경")
s(11600, {"speedKph": 60, "turnSignal": "Off"}, "[..] 깜빡이 끔(되돌아오는 중 자동 해제 — 다음 차선 변경 3.5초 전)")
# E2: 깜빡이 없이 차선 변경
for i, a in enumerate([-3, -5, -7, -7, -5, -3, 0, 3, 5, 6, 6, 5, 3, 1, 0]):
    s(15000 + i * 350, {"speedKph": 60, "steering": a}, "[E2] 깜빡이 없이 차선 변경")
# E3: 핸들 먼저, 깜빡이 나중
s(22000, {"speedKph": 60, "steering": 3}, "[E3] 핸들 먼저")
s(22350, {"speedKph": 60, "steering": 6}, "[E3] 핸들 먼저")
s(22600, {"speedKph": 60, "turnSignal": "Blink left"}, "[E3] 깜빡이 늦게 켬")
for i, a in enumerate([7, 7, 5, 2, -1, -4, -6, -6, -5, -4, -2, -1, 0]):
    s(22700 + i * 350, {"speedKph": 60, "steering": a}, "[E3] 차선 변경")
s(27500, {"speedKph": 60, "turnSignal": "Off"}, "[..] 깜빡이 끔")
# 완만한 감속 60→12 (1km/h·250ms, 브레이크 없음)
for k, v in enumerate(range(59, 11, -1)):
    s(28000 + k * 250, {"speedKph": v, "steering": 0, "brake": 0}, "[DEC] 완만한 감속")
t = 28000 + 48 * 250  # 40000
# E4: 내비 우회전 안내 + 깜빡이 + 우회전
s(t + 2000, {"speedKph": 12}, "[E4] 내비: 200m 앞 우회전", nav("TURN_RIGHT", 200))
s(t + 4000, {"speedKph": 12, "turnSignal": "Blink right"}, "[E4] 50m 앞 · 깜빡이 켬", nav("TURN_RIGHT", 50))
for i, a in enumerate([20, 50, 80, 90, 90, 90, 90, 70, 40, 10, 0]):
    s(t + 6000 + i * 400, {"speedKph": 12, "steering": a}, "[E4] 우회전")
s(t + 12000, {"speedKph": 12, "steering": 0, "turnSignal": "Off"}, "[..] 우회전 마침", nav("TURN_STRAIGHT", 600))
# E5: 내비 유턴 안내 + 깜빡이 없이 유턴
s(t + 16000, {"speedKph": 12, "steering": 0}, "[E5] 내비: 200m 앞 유턴", nav("TURN_UTURN", 200))
s(t + 18000, {"speedKph": 12, "steering": 0}, "[E5] 50m 앞 · 깜빡이 없음", nav("TURN_UTURN", 50))
for i, a in enumerate([30, 65, 100, 120, 120, 120, 120, 90, 50, 15, 0]):
    s(t + 20000 + i * 400, {"speedKph": 12, "steering": a}, "[E5] 유턴")
s(t + 26000, {"speedKph": 12, "steering": 0}, "[..] 유턴 마침", nav("TURN_STRAIGHT", 900))
# E6: 다시 60 → 급제동
s(t + 28000, {"speedKph": 60, "steering": 0, "brake": 0}, "[GO] 다시 시속 60km", None,
  {"min": 58, "max": 62, "timeoutMs": 30000, "holdMs": 1000})
s(t + 31000, {"speedKph": 60}, "[..] 순항")
b = t + 33000
s(b, {"speedKph": 60, "steering": 0, "brake": 90}, "[E6] 급제동")
for i, v in enumerate(range(57, 14, -3)):
    s(b + 100 * (i + 1), {"speedKph": v, "steering": 0, "brake": 90}, "[E6] 급제동")
b2 = b + 100 * 15  # 15km/h
for i, v in enumerate(range(14, -1, -1)):
    w = {"min": 0, "max": 0.1, "timeoutMs": 20000, "holdMs": 400} if v == 0 else None
    s(b2 + 250 * (i + 1), {"speedKph": v, "steering": 0, "brake": 90}, "[E6] 정지까지 감속", None, w)
e = b2 + 250 * 15
s(e + 1500, {"speedKph": 0, "steering": 0, "brake": 0}, "[..] 브레이크 뗌 · 안내 종료", OFF)
s(e + 3500, {"gear": "P", "speedKph": 0, "steering": 0, "brake": 0, "turnSignal": "Off"}, "[PARK] 정차 · P")

steps.sort(key=lambda x: x["atMs"])
scenario = {
    "name": "G · v6 시연 영상(차선 3종 + 모의 내비 회전 2종 + 급제동)",
    "runner": "demo-navi-feed.ps1",
    "requiresDemoNavi": True,
    "note": "시연 영상 v6 녹화용. D의 차선·급제동 구간과 F의 모의 내비 회전 구간을 이어 붙임. 모의 내비 = 시연용 입력.",
    "expected": ["LANE_SIGNALLED 좋아요", "LANE_NO_SIGNAL", "LANE_LATE_SIGNAL", "TURN_SIGNALLED 우회전 내비 확인(모의)",
                 "TURN_NO_SIGNAL 유턴 내비 확인(모의)", "HARD_BRAKE"],
    "steps": steps,
}
out = pathlib.Path(__file__).parent / "recap-scenario-g-v6.json"
out.write_text(json.dumps(scenario, ensure_ascii=False, indent=1), encoding="utf-8")
print(out, len(steps), "steps, nominal", steps[-1]["atMs"] / 1000, "s")
