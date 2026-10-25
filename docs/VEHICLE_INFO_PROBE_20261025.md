# 차량 제원 값 확인 (2026-10-25, Pleos Connect 에뮬레이터 v2.1)

## 경로
- Pleos Vehicle SDK 2.3.2 `CarInfo`: VIN·CRN·capability만 제공(javap 확인). 차종·축거 없음.
- AAOS 표준 `CarPropertyManager` INFO_* (권한 `android.car.permission.CAR_INFO`, normal): 에뮬레이터 HAL에 등록돼 있고 앱에서 읽힘.
- `cmd car_service get-property-value`는 user 빌드라 막힘, `adb root` 불가 → 디버그 전용 수신기 `app/src/debug/.../InfoProbeReceiver.kt`로 읽음.
  `adb shell am broadcast -n lab.pleos.observe/.InfoProbeReceiver -a lab.pleos.observe.INFO_PROBE` → logcat 태그 `InfoProbe`.

## 에뮬레이터 값
| 속성 | 값 |
|---|---|
| INFO_MAKE | Hyundai 42dot |
| INFO_MODEL | Connect |
| INFO_MODEL_YEAR | 2024 |
| INFO_FUEL_TYPE | 10 (전기) |
| INFO_EXTERIOR_DIMENSIONS (mm) | 높이 1776, 길이 4950, 폭 2008, 미러 포함 폭 2140, **축거 2984**, 앞 윤거 1665, 뒤 윤거 1667, **최소 회전 지름 11800** |
| PERF_STEERING_ANGLE 범위(HAL 덤프) | -470 ~ 500 |

- 차종 이름이 실제 모델명이 아니라 에뮬레이터 표본값으로 보임. 실차가 진짜 값을 채우는지는 확인 못 함 → 문의 메일 후보.
- 이 값으로 계산한 조향비(참고): 회전 지름 11.8m·축거 2.984m → 앞바퀴 최대각 약 30~35°, 핸들 최대 약 470~500° → 조향비 약 14~16. 엔진 1.6.0은 실데이터에 맞춘 RATIO 20을 씀. 에뮬레이터 표본값이라 엔진 상수는 바꾸지 않음.

## 쓸 수 있는 방향(미구현)
실차에서 INFO_EXTERIOR_DIMENSIONS가 실제 값이면 축거는 바로 대입, 조향비는 (핸들 최대각 ÷ 회전 반경에서 구한 바퀴 최대각)으로 차량별 추정 가능. 실차 값 확인이 먼저.
