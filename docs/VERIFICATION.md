# 2026-10-22 검증과 인계

## 실행한 검증

| 항목 | 결과 | 근거와 한계 |
| --- | --- | --- |
| 공식 문서 재확인 | 확인 | 웹 검색 및 Chrome에서 공개 API 참조의 렌더링된 시그니처를 확인. [API_EVIDENCE.md](API_EVIDENCE.md)에 정리 |
| Maven 최신 버전 | 2.3.2 확인 | Chrome에서 metadata의 latest/release 모두 2.3.2, lastUpdated=20261019002245 확인 |
| JDK·Android 도구 설치 | 확인 | Android Studio JBR 21.0.9, Kotlin compiler 2.2.20, Android SDK 36/36.1 및 Build Tools 35.0.0/36.0.0/36.1.0 파일 확인. 실행 중인 AVD의 상태는 재확인하지 않음 |
| Gradle 전체 빌드 | 실행했으나 실패 | `:logic:test :app:assembleDebug`가 wrapper 8.14.3 다운로드에서 `java.net.SocketException: Permission denied: getsockopt`. 프로젝트 구성·의존성 해석·Kotlin 앱 컴파일에 도달하지 못함 |
| JVM 단위 테스트 | 10/10 통과 | `scripts/test-logic-offline.ps1`로 로컬 compiler + JUnit 4.13.2 사용. `.local/offline-tests/junit-result.txt`: `OK (10 tests)` |
| AAOS 부분 컴파일 | 통과 | 실제 로컬 android.jar + optional/android.car.jar에 대해 Observation.kt, EvidenceLog.kt, CarSafetyClient.kt를 Kotlin 2.2.20 / JVM 17로 컴파일. `.local/aaos-check/safety.jar`. SDK 36의 deprecated 경고만 발생 |
| 매니페스트 XML | 파싱 성공 | PowerShell XML parser로 확인. APK manifest merge와 설치 결과는 별도 미검증 |
| APK 설치·실제 Pleos 콜백 | 미검증 | Claude가 빌드·설치 후 README 순서로 확인할 것 |

처음 직접 실행한 PowerShell 테스트 스크립트는 실행 정책으로 차단됐다. 시스템 정책은 변경하지 않고 자식 PowerShell에 `-ExecutionPolicy Bypass`를 지정해 재실행한 결과가 10/10이다. 부분 컴파일 중 발견한 공개 API에 없는 UX timestamp 접근은 제거했다. AAOS property 이벤트에서만 SDK가 제공하는 timestamp를 기록한다.

## 단위 테스트 범위

1. 연결·제한 비활성·P·속도 0이 모두 확인됐을 때 해제.
2. 제한/기어/속도 각각 미수신일 때 잠금.
3. 이전 값이 있어도 서비스 미연결이면 잠금.
4. 제한 활성만으로 잠금.
5. P 이외 모든 기어에서 잠금.
6. 양수·음수·극소 비영 속도에서 잠금.
7. NaN·양/음 무한대에서 잠금.
8. 잠금 본문에서 기존 값·시각·오류 문자열 제거.
9. 미지원과 미수신/오류 구별.
10. null 값 콜백도 수신 시각 기록, 값 조작 없음.

## Claude에게 남은 항목

- `:logic:test :app:assembleDebug`를 실제 네트워크 가능한 PC 환경에서 완료한다. **Claude가 빌드할 것.**
- 실제 Vehicle 2.3.2 AAR에 대한 전체 타입 검증과 manifest merge를 확인한다. MainActivity 및 PleosObservationClient 전체 컴파일은 아직 확인되지 않았다.
- README의 실제 serial 선택 후 설치, 권한 허용, 창문/독서등 좌석별 get/notify·시각·지원 범위 확인.
- Virtual Console Gear Selection → CURRENT_GEAR 연동과 실제 Vehicle Speed 입력 수단 확인.
- 플랫폼 DO 차단과 앱 자체 잠금의 결과를 구분해 기록. 안전 신호가 미수신일 때 임의 기본값으로 해제하지 않는다.
- 초기 get이 새 notify를 덮지 않는지, 앱 재진입 때 구독이 누적되지 않는지, SDK release/해제 오류가 없는지 확인.
- 조회 시 토큰/키가 정말 불필요한지 실기로 확인. 공식 조회 문서에 없는 키를 만들거나 계정에 로그인하지 않는다.

빌드 오류가 발생하면 정확한 오류 출력, 사용한 JDK/SDK 버전, 문제가 난 파일·행을 이 Codex 대화로 전달한다. 의존성을 임의로 낮추거나 DO를 선언해 차단을 우회하지 않는다.

## 파일 보존

파일 생성·수정 위치는 `D:\Projects\pleos-observe-lab` 아래로 제한했다. 다른 폴더에서는 도구·SDK·기존 캐시·위키/메모리 검색만 읽었다. wrapper JAR는 로컬 Gradle 8.14.3 공식 배포본의 내장 `gradle-wrapper.jar`를 읽어 이 프로젝트에 추출했다. SHA-256:

```text
7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172
```

Git index·브랜치·커밋은 변경하지 않았다. 기존 명세 파일은 수정하지 않았다. `_codex_log_w1.txt`와 `.omc` 추적 파일은 외부 실행기가 갱신할 수 있으므로 앱 산출물로 취급하지 않는다.
