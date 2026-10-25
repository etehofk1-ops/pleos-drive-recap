# 8주차 · 제출 저장소 준비 · 1.0.0-rc1(12)

2026-10-23. 소스/설정/문서/검사 도구를 준비했다. Git 저장소 생성·commit·push·심사 계정 초대, 실제 키 생성, Gradle release/APK/AVD 실행은 수행하지 않았다. APK는 아직 없다. 기존 새 영상/영상 제작 자료와 개발 기록은 보존했다.

## 근거

- [공식 지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission): GitHub 소스+APK, `apk/`, `팀명_앱이름_release.apk`, 실행 가능한 release, README 네 항목과 비공개 심사 계정 접근 조건을 웹에서 재확인했다. 작품 소개의 개인 식별정보 제외는 이번 사용자 지시로 적용했다.
- [Android 서명](https://developer.android.com/studio/publish/app-signing), [네트워크 설정](https://developer.android.com/privacy-and-security/security-config): 로컬 서명 속성과 도메인별 cleartext 예외의 근거다. 실제 AGP 실행/리소스 병합 성공 증거와는 구분한다.
- [7b 실제 AVD 부록](WEEK7B_DEMO_FIX.md): 최종 D 카드4/애매함0, 감속21.4m/s² 경고 표시. 이후 release 검증을 대신하지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `.gitignore` | 서명/자격 파일·개발 문서 보관 폴더·원본 영상/모션·새 로그/명세 제외 |
| `app/build.gradle.kts` | 12/1.0.0-rc1, release signingConfig/누락 검증 task, minify/shrink=false·debuggable=false, 심사용 relay 기본값 |
| `app/src/main/res/xml/network_security_config.xml` | release 포함10.0.2.2만 HTTP 예외, base는 차단 |
| `logic/src/main/kotlin/lab/pleos/observe/logic/recap/ProxyEndpoint.kt` | origin 정규화/HTTPS 또는 정확한 HTTP 호스트 검증 |
| `logic/src/test/kotlin/lab/pleos/observe/logic/recap/ProxyEndpointTest.kt` | 기본값/정규화/우회 호스트/인증정보·경로·범위 거부5개 |
| `app/src/main/java/lab/pleos/observe/AiProxyClient.kt` | 검증한 서버 주소 적용/기존 연결 취소 |
| `app/src/main/java/lab/pleos/observe/MainActivity.kt` | release에도 AI 서버 설정, 저장/오류 표시·주행/일시중지 때 대화상자 닫기, 주소 변경 시 기존 분류 무효화 |
| `app/src/main/java/lab/pleos/observe/RecapProvider.kt` | private IPC 서버 설정 명령과 주소 반환 |
| `app/src/main/java/lab/pleos/observe/RecapRepository.kt` | 소유 프로세스에서 주소 저장/복원·요약 서버 전환 |
| `app/src/main/java/lab/pleos/observe/RecapSummaryCoordinator.kt` | 변경 시 pending 티켓/문장 캐시 무효화, 저장한 주소 초기화 |
| `README.md` | 제출용 한국어 네 필수 항목·키 생성/빌드/설치/시연·저장/한계·제외 범위 |
| `ai-proxy/README.md`, `docs/AI_CONTRACT.md` | 개인 홈 경로 제거, release 로컬 relay/설정 정책 반영. HTTP JSON 스키마 유지 |
| `scripts/check-submission.ps1` | 범위/개인정보/키/커밋 메시지/README/APK·서명·버전 검사 |
| `scripts/test-submission.mjs` | 격리 소스/모의 도구/누출 방지/네트워크 정책10개 |
| `apk/.gitkeep` | APK 폴더만 준비, 실제 APK 미복사 |
| `docs/WORK-CHECKLIST.md`, 이 문서 | 구현·검증·남은 실제 확인 구분 |
| 루트 `_codex_log*`, `_codex_report*`, `SPEC_*` → `docs/dev-notes/` |37개 원본 보존 이동.36개 SHA256 일치, 현재 쓰는 로그1개는 공유 잠금으로 해시 미검증 |

이동 근거는 `.local/w8-archive-hashes-corrected.csv`다. 현재 쓰는 로그는 위치만 옮겼고 내용/작성 프로세스를 변경하지 않았다. Git의 추적된 루트 파일은 삭제처럼 표시될 수 있지만 실제 보존본은 gitignore한 `docs/dev-notes/`에 있다. 인덱스/이력을 수정하지 않았다. `docs/WEEK*.md`는 현재 위치를 유지하고 README 끝의 제출 제외 범위로 명시했다.

## 서명과 release 경계

`.local/release.properties`에서 storeFile/storePassword/keyAlias/keyPassword를 읽고 release config에만 연결한다. 파일/항목/키 파일이 없으면 `validateReleaseSigning`이 설명 가능한 오류를 낸다. `preReleaseBuild`와 `validateSigningRelease`가 이를 선행한다. debug/logic 작업은 서명 파일 없이 사용할 수 있게 했다. 실제 누락 오류·AGP signing task 실행은 Claude 확인이 남았다.

서명키/속성 파일은 이번에 생성하지 않았다. README의 keytool 명령은 비밀번호를 프롬프트로 받고 개인 식별정보가 없는 DN을 사용한다. 저장 재료/인증서 내용을 소스에 넣지 않는다. Debug와 release 서명 차이로 기존 debug 앱과 충돌할 수 있으므로 보존할 기록이 있으면 별도 AVD를 쓴다. 자동 삭제/언인스톨은 수행하지 않았다.

Debug 전용 속도 미수신 시연과 원시 감속 표시는 기존 BuildConfig.DEBUG 게이트를 유지한다. 서비스도 release에서 demo extra를 무시한다. 심사용 예외로 debug/release 모두10.0.2.2만 HTTP를 허용하며 다른 주소는 HTTPS origin으로 제한한다. 키 입력 UI는 없다. 주소 저장 시 UI와 서비스의 pending AI/캐시를 무효화해 이전 서버 응답이 새 결과로 적용되지 않게 했다. 키는 여전히 중계 프로세스에만 있다.

## 검사 결과

- JVM **196/196**: 기존191 + URL 정책5. `.local/w8-jvm-tests.txt`.
- 신규 제출 검사 **10/10**: `.local/w8-submission-tests.txt`. 모의 APK 도구 응답으로 분기만 시험했다. 실제 APK나 서명을 만들었다는 증거가 아니다.
- 앱+logic 직접 Kotlin 타입 검사 **Debug/Release 두 BuildConfig 모두 통과**. `.local/w8-debug-types.txt`, `.local/w8-release-types.txt`. 기존 deprecated 경고가 남는다. Gradle DSL/리소스/manifest/서명/APK 검증과 구분한다.
- 기존 Node 전체 실행은62개 중60 통과/2 실패였다(신규 stderr 누출 검사 추가 전9개 포함). 실패는 변경하지 않은 D 회귀2개다. `scripts/recap-replay.js`, D JSON, `scripts/test-recap-replay.mjs`가 HEAD와 동일함을 `git diff --quiet`로 확인했다. 서버27개는 통과했다.
- 실패 이유: Claude의 최종 실측 후 D 신호 대기 구간이 브레이크0으로 바뀌었으나 기존 Node 단언은 브레이크10의61개/250ms 간격을 기대한다. 단언이나 실제 시연 JSON을 이번 범위에서 변경하지 않았다. [7b 부록](WEEK7B_DEMO_FIX.md)에 실측 이유가 있다. 실제 제출 전 기대/모의 시험의 정합성 정리는 별도 확인이 필요하다.

### 개인정보·이력 경계

현재 제출 대상 텍스트84개에서 작업 파일의 실명/전화/이메일/키/개인 홈 경로 패턴은 검출되지 않았다. **현재 Git 커밋 메시지에는 공동작성자 이메일17곳이 남아 있어 기본 검사 실패를 유지한다.** 원문 주소는 보고하지 않는다. 작성자 메타데이터/과거 파일 내용은 자동 검사 범위가 아니며 추가 검토 대상이다. 과거 이력을 재작성하지 않았고 현재 저장소를 그대로 게시해도 된다고 판단하지 않는다.

기존 `.git`과 README 제외 경로를 넣지 않은 소스 검토본을 `.local/w8-source-review-*`에 만들었다. 이는 **폴더 복사본이며 Git 저장소가 아니다**. 파일 해시를 대조했고84개 텍스트의 source-only 검사는0건이다. 경로는 `.local/w8-source-review-path.txt`, 결과는 `.local/w8-source-review-check.txt`다. 이 검토본도 APK/실제 release 실행은 확인 전이다. 실제 제출 시에는 검토한 소스만 사용하고 기존 개발 Git 이력을 가져가지 않는다.

무표식 실명은 일반 정규식만으로 완전히 판별할 수 없다. `.local/submission-names.txt`를 로컬에서 만들어 `-NameListFile`로 대조할 수 있으며, 화면/영상/인증서/권한과 함께 사람이 검토한다. 키/매칭 본문·도구 stderr는 검사 로그에 출력하지 않는다. 제출 제외 자료는 삭제하지 않았다.

## Claude 순서

1. README 빌드 절의 keytool로 `.local/release.keystore`를 만들고 `.local/release.properties`를 작성한다. 개인 식별정보 없는 DN, 실제 팀명은 제출 담당자가 정한다. 키/비밀번호를 대화나 로그에 붙이지 않는다.
2. JBR/SDK를 설정하고 아래 release 빌드를 실행한다. 실패 시 예전 APK를 복사하지 않는다. 최초에는 서명 파일 없는 release의 명확한 오류와 debug/logic 비의존도 별도로 확인한다.

```powershell
.\gradlew.bat :logic:test :app:assembleRelease --no-daemon --no-configuration-cache --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
```

3. aapt2로 package=lab.pleos.observe, versionCode12/versionName1.0.0-rc1·비디버그를 확인하고 apksigner verify로 실제 서명을 확인한다. 생성물을 `apk/실제팀명_오늘운전복기_release.apk`로 복사한다. README에는 공백을 뺀 앱 이름을 명시했다.
4. 소스 검토본은 서명 파일 없이 만들어져 있으므로 그 안에서 빌드하려면 해당 루트의 `.local`에 키/설정을 별도로 준비해야 한다. 보통 원래 작업 폴더에서 검증한 APK를 검토한 제출 소스의 `apk/`에 복사한다. 최종 소스가 이후 변경됐다면 다시 대조한다. 저장소 생성/push는 이번 승인 범위가 아니다.
5. 최종 대상에 `scripts/check-submission.ps1` 실행. 현재 개발 Git에서 이메일17건을 무시하지 않는다. 기존 이력이 없는 검토 소스에 APK를 넣은 뒤 최종 검사를 수행하고, 기존 개발 `.git`은 가져가지 않는다. `-SourceOnly`는 APK를 검사하지 않는다.
6. 별도/호환 AVD에 release 설치. Debug 시연 토글/원시 감속 숫자 숨김, AI 서버 설정의 저장/재실행, 잘못된 HTTP 주소 거부, 기본 로컬 서버와 Jev/문장/캐시/서버 중지 폴백을 확인한다. 주소 변경 중 옛 문장이 되살아나면 실패다. D 잠금/P 복귀/기록/삭제도 확인한다.
7. 현재 D의 기대/Node 단언2개는 승인된 실제 시연 기준과 맞춰 정리한다. release의 실제 감속이21.4처럼 나와도 숫자를 조작하지 않고 경고 표시를 확인한다. 최종 README의 APK/검증 전 문구와 팀명도 실제 결과로 갱신한다. 발표/소개 화면과 커밋 메시지를 포함해 익명화/접근 권한을 확인한 뒤 게시 여부를 판단한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-submission.ps1 -SourceOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-submission.ps1
# 실제 검토 대상의 경로를 지정할 때
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-submission.ps1 -Root <검토한소스루트> -NameListFile .local/submission-names.txt
```

최종 검사는 APK가 없으면 실패해야 정상이다. 현재 source-only와 최종 실패를 제출 완료로 보고하지 않는다. 자동 검사 성공도 실제 release AVD 실행이나 심사상 백그라운드 허용의 증거는 아니다.
