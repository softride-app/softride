# 소프트라이드 (SoftRide)

> **심사용 3단계 실행** (Pleos Connect 에뮬레이터)
>
> 1. APK 설치: `adb install apk\SoftRide_release.apk` 후 앱 목록에서 소프트라이드를 연다. 처음 열 때 묻는 권한은 모두 허용한다.
> 2. 뒷좌석 착석 (필수): Virtual Console에서 **뒷좌석 오른쪽**(rear right)을 "앉음"으로 바꾼다. 기본 탑승자 서윤이 이 자리에 앉아야 알림이 켜진다. 앉지 않으면 화면에 "서윤이 뒷좌석 오른쪽에 앉으면 켜져요"가 보이고 알림은 쉰다.
> 3. 주차(P) 상태에서 화면의 **시험 주행 시작**을 누른다. 약 2분 30초 동안 저장된 주행이 재생된다: 회전·과속방지턱 알림, 창밖 시선 안내, 공조 케어, 휴게소 제안, 도착 후 주행 리포트.
>    휴게소 제안은 누적 흔들림이 35 이상일 때만 나온다. 재생 74~92초 사이에 Virtual Console로 조향을 ±20° 정도 몇 번 바꾸면 확인할 수 있다(시연 영상과 같은 조작, `docs/demo_script.md`).

뒷좌석 아이의 차멀미를 줄이는 Pleos Connect 차량 앱. 회전·덜컹 구간을 1.5초 전에 소리로 미리 알리고, 흔들림을 물 한 컵의 수면으로 보여 준다.

## 실행 방법

```powershell
$env:JAVA_HOME='<JDK 17 경로>'
$env:ANDROID_SDK_ROOT='<Android SDK 경로>'
.\gradlew.bat assembleDebug assembleRelease --no-daemon "-Pandroid.overridePathCheck=true"
adb -s emulator-5580 install -r -g apk\SoftRide_release.apk
adb -s emulator-5580 forward tcp:8088 tcp:8088
# 뒷좌석 오른쪽 착석 (필수: 기본 탑승자 서윤의 자리. 앉지 않으면 알림이 꺼져 있다)
curl.exe -X PUT "http://127.0.0.1:8088/vehicle/seat?area=rear_right&kind=occupied&value=true"
adb -s emulator-5580 shell am start -n ai.softride.app/.MainActivity
# 시험 주행(Replay) 시작: 화면의 "시험 주행 시작" 버튼(주차 중에만) 또는
adb -s emulator-5580 shell am start -n ai.softride.app/.MainActivity --ez replay true
```

- 경로에 한글이 있으면 `-Pandroid.overridePathCheck=true`가 필요하다(`gradle.properties`에도 넣어 둠).
- 단위 테스트(`testDebugUnitTest`)는 한글 경로에서 Gradle이 테스트 클래스를 못 찾는다(`ClassNotFoundException`). `subst S: <softride 경로>` 후 `S:\`에서 실행하면 통과한다(6개 테스트).
- 릴리스 서명 키는 `keystore\`에 있고 git에 올리지 않는다(`keystore\README.txt`에 비밀번호).
- 릴리스는 R8 축소(`isMinifyEnabled`·`isShrinkResources`)를 켠다. Pleos SDK·Gson은 AIDL/리플렉션을 쓰므로 `app/proguard-rules.pro`에서 통째로 보존한다. 릴리스 APK 39.8 MB → 21.2 MB.
- 시작 시 권한 요청: `CAR_SPEED`, 알림, `RECORD_AUDIO`(휴식 제안 대답), `pleos.car.permission.FUSED_LOCATION`. `install -g`면 미리 허용된다.

### 시험 주행(Replay) 이야기 (`assets/trip_demo.json`, 약 2분 30초)

내비가 실제로 주는 것만 담았다: 다음 회전(TBT) 하나와 주의 구간. 화면에는 항상 다음 회전 하나만 나온다.

| 시각 | 일어나는 일 |
| --- | --- |
| 0–8초 | 출발, 43 km/h까지 가속 |
| 약 19초 | "곧 오른쪽" (우회전 1.5초 전) |
| 약 43초 | "곧 덜컹" (과속방지턱) |
| 약 62초 | "곧 왼쪽" (좌회전) |
| 74–99초 | 굽은 길(진행 방향이 ±27° 흔들림). 이때 Virtual Console로 조향·브레이크를 바꾸면 흔들림이 더 오른다 |
| 약 76초 | 공조 케어: "바깥 공기로 바꿀게요" → 내기순환 해제, 운전석 −1 ℃, 바람 +1단 (이미 바깥 공기면 온도·바람만 말하고 바꾼다) |
| 약 98초 | "1킬로미터 앞 휴게소에서 쉬었다 갈까요?" → 6초 동안 음성 대답을 듣는다(STT) |
| 99–132초 | 곧은 고속도로(97 km/h). 15초 넘게 곧게 가고 회전 안내가 400 m 안에 없으면 "창밖 먼 곳을 봐요"(약 113초) |
| 약 150초 | 가평휴게소 도착(기어 P) → 주행 리포트 |

화면 왼쪽 위에 "시뮬레이션 입력"이 Replay 내내 표시된다. 다시 하려면 리포트의 "다시 시험 주행".

## 테스트 환경

| 항목 | 값 |
| --- | --- |
| AVD | `pleos_connect_21` (Pleos Connect v2.1.0, API 34, x86_64, 2560×1440, 160 dpi, RAM 4GB, cold boot) |
| 부팅 | `emulator -avd pleos_connect_21 -gpu host -no-snapshot -no-boot-anim -port 5580` (`swiftshader_indirect`는 내비 지도에서 에뮬레이터가 죽는다, 3단계 참고) |
| 앱 영역 | 주행 뷰 옆 1682×1210 px (x 853–2535, y 90–1300). 화면은 이 영역 기준으로 배치함 |
| Virtual Console | `adb forward tcp:8088 tcp:8088`, 1초마다 `POST /browser/session?client=<id>` 하트비트 필요 |

시연용 Virtual Console 설정 예:

```powershell
$V='http://127.0.0.1:8088'
curl.exe -X PUT "$V/vehicle/hvac?power=1"; curl.exe -X PUT "$V/vehicle/hvac?recirc=1"; curl.exe -X PUT "$V/vehicle/hvac?fan_speed=2"
curl.exe -X PUT "$V/vehicle/seat?area=rear_right&kind=occupied&value=true"    # 서윤 자리(뒷좌석 오른쪽) 착석 → 멀미 예보 켜짐
curl.exe -X PUT "$V/vehicle/controls?speed=0&accelerator=0&brake=60&steering=25" # 흔들림 지수 올리기
curl.exe -X PUT "$V/vehicle/turn-signal?mode=BlinkRight"                        # 같은 방향이면 알림 확인/앞당김
curl.exe -X PUT "$V/vehicle/gear?gear=Drive"; curl.exe -X PUT "$V/vehicle/controls?speed=30"  # 주행 중 화면
```

## APK

`apk\SoftRide_release.apk` (release, 새 키로 서명, minSdk 28 / targetSdk 35, applicationId `ai.softride.app`)

블라인드 심사 규정에 따라 APK 파일명에서 성명을 뺐습니다.

## 구조

- `signals/VehicleSignals.kt` — 입력 인터페이스. 구현 두 개:
  - `RealSignals` — 실제 차량: `LiveVehicle` + NaviHelper TBT·주의 구간.
  - `ReplaySignals` — 시연용 `assets/trip_demo.json`(시각별 속도·진행 방향, TBT 목록, 주의 구간). 브레이크·조향·방향지시등·기어·뒷좌석 착석은 Replay 중에도 실제 차량(Virtual Console) 값이 들어오고, 실제 속도가 5.4 km/h를 넘으면 기록 속도 대신 쓴다. Replay 중에는 화면에 "시뮬레이션 입력" 표시.
- `engine/SwayMeter.kt` — 흔들림 지수 0–100. 좌우 가속(속도×진행 방향 변화율, 또는 속도²×tan(조향각)/축거 중 큰 값) + 앞뒤 가속(속도 변화) + 브레이크, 좌우에 가중. 0.1–0.5 Hz 대역을 강조. 1초 표본의 최근 5분 이동 평균.
- `engine/CuePlanner.kt` — 멀미 예보 시점: 도달 시간 = 남은 거리 ÷ 현재 속도, 1.5초 전 알림. 같은 방향 방향지시등은 이미 한 알림을 확인하고, 아직 안 한 알림은 회전까지 4초 이하·150 m 미만일 때만 앞당긴다. 말하는 안내(회전 알림, 창밖 안내, 공조 케어, 휴게소 제안)는 서로 8초 이상 떨어뜨린다: 회전 알림이 아닌 안내는 앞으로 8초 안에 회전 알림이 있으면 기다린다(`quiet`).
- `engine/WindowGaze.kt` — 창밖 시선 안내: 최근 1분 안에 10초 흔들림이 50 이상이었고, 진행 방향이 15초 넘게 ±5° 안이고(곧은 길), 400 m 안에 회전 안내(TBT)가 없으면 "창밖 먼 곳을 봐요". 10분에 한 번까지, 뒷좌석이 비었거나 미리 알림을 끄면 하지 않는다.
- `engine/Speaker.kt` — 0.3초 효과음 + 짧은 음성("곧 오른쪽", "곧 왼쪽", "곧 유턴", "곧 덜컹"). `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, 포커스를 받지 못하면 건너뜀(깜빡이 소리는 막지 않음). 효과음 0.15초 지점에서 `speak()`를 불러 음성이 효과음 끝 0~0.1초 안에 이어진다. 부팅 뒤 첫 합성이 1~3초 늦어서, TTS 준비 직후와 주행 시작 때 빈 문장(" ")으로 미리 깨운다(소리 없음). 알림 목소리: Pleos TTS에는 높낮이·빠르기 설정이 없어 `setSpeakerType`으로 화자를 바꾼다 — 다정한 = seoyoon, 또렷한 = haeun, 차분한 = eunwoo(Pleos 음성 서비스에 들어 있는 한국어 화자). 에뮬레이터에서 "곧 오른쪽"을 재 보니 평균 음높이 192 / 258 / 128 Hz, 길이 0.75 / 0.61 / 0.73초로 셋이 확실히 다르다. Pleos TTS를 못 쓸 때만 Android TTS로 대신하고, 그때는 높낮이·빠르기(1.0/1.0, 1.15/1.15, 0.85/0.85)로 구분한다.
- `engine/AirCare.kt` — 공조 케어(요청 모델): 허용 범위(바깥 공기, −1 ℃, 바람 +1단) 안에서, `checkHvac*Capabilities`가 돌려준 영역에만, 음성 안내 후 실행, 주행당 1회, 운전자가 먼저 바꾸면 하지 않음. 실제로 바뀌는 것만 말한다(내기순환이 이미 꺼져 있으면 "바깥 공기로 바꿀게요"라고 하지 않고, 바꿀 것이 없으면 아무 말도 하지 않음). 승인 모드: `CONTROL_CAR_CLIMATE` 권한이 없거나 쓸 수 있는 공조 영역이 없으면 `setHvac*`를 부르지 않고 "창문 쪽 공기를 바꾸면 편해져요"라고 말한 뒤 화면에 "공조 케어 안내만 했어요"를 띄운다. 리포트의 "공조 케어"는 `setHvac*` 성공 콜백이 하나라도 온 뒤에만 센다.
- `engine/Engine.kt` — 10 Hz 루프, 운전자 화면 수치는 5초 단위 계단 갱신, 주행 기록·리포트, 휴게소 제안(누적 흔들림이 높고 휴게소/졸음쉼터 TBT가 3 km 안에 있으면 1회).
- `engine/Listener.kt` — 휴식 제안 대답. Pleos SpeechToText(ON_DEVICE)를 제안 음성이 끝난 직후에만 `request()`, 6초 안에 "응/네/예/그래/좋아"면 음성으로 확인하고 리포트 "휴식"에 센다. 대답이 없거나 STT를 못 쓰면 아무것도 하지 않는다(주행 중 버튼 없음).
- `engine/Settings.kt` — 탑승자 프로필(이름·나이·자리·멀미 민감도·목소리)과 공조 케어 허용 범위. 차 안(SharedPreferences)에만 저장.
- `signals/LiveVehicle.kt` 착석 — Vehicle 2.3.2는 뒷좌석 착석 값을 반대로 준다(아래 4단계). 상수 `SDK_OCCUPIED = false` 하나로 뒤집고, SDK 원래 값을 `seat ROW_2_* sdk=…`로 로그에 남긴다. SDK가 고쳐지면 이 상수만 `true`로 바꾸면 된다(실차·새 SDK에서 로그로 확인).
- `signals/LiveVehicle.kt` 속도 — `PERF_VEHICLE_SPEED`가 5초 넘게 안 오면 FusedLocation 위치 변화(두 점 거리 ÷ 시간, `SpeedMath`)로 속도를 계산한다. 로그 `trip start (... speed via car|fused)`.
- `CueService.kt` — 포그라운드 서비스. 화면이 가려져도 알림·음성이 계속 동작.
- `ui/` — Compose 화면: 운전자 화면, 주차 중 주행 리포트, 탑승자 설정(기어 P가 아니면 "운전 중에는 보기만 할 수 있어요"로 잠금). 시스템 주야간 설정(`isSystemInDarkTheme`)을 따라 낮/밤 색이 바뀐다. 디자인 콘셉트는 "물 한 컵": 흔들림 지수를 왼쪽의 물 높이와 물결로 보여 주고, 주행 중 화면은 움직이지 않으며 숫자만 5초마다 바뀐다. 글꼴 Paperlogy(큰 글자)·Pretendard(작은 글자), 둘 다 SIL OFL 1.1(`docs/Paperlogy-OFL.txt`).

## 사용한 SDK API

| SDK | API | 쓰는 곳 |
| --- | --- | --- |
| Vehicle 2.3.2 | `Vehicle(context)`, `initialize()`, `release()` | `LiveVehicle` |
| Vehicle – Brake | `getCurrentBrakePedalStatus`, `registerBrakePedalStatus` | `LiveVehicle` → 흔들림 지수 |
| Vehicle – SteeringWheel | `getCurrentSteeringWheelAngle`, `registerSteeringWheelAngle` | `LiveVehicle` → 흔들림 지수 |
| Vehicle – TurnSignal | `getCurrentTurnSignalState`, `registerTurnSignalState` | `LiveVehicle` → 알림 확인/앞당김 |
| Vehicle – Gear | `getCurrentGearSelectionState`, `registerGearSelection` | `LiveVehicle` → 주행 시작/끝(주차 시 리포트) |
| Vehicle – Seat | `getCurrentSeatOccupancy`, `registerSeatOccupancy` (`ROW_2_LEFT/RIGHT`) | 뒷좌석 착석 시 멀미 예보 자동 시작 |
| Vehicle – HVAC | `checkHvacRecirculationCapabilities`, `checkHvacTemperatureCapabilities`, `checkHvacFanSpeedCapabilities`, `getHvacRecirculationState`, `getHvacTemperature`, `getHvacFanSpeed`, `setHvacRecirculation`, `setHvacTemperature`, `setHvacFanSpeed` | `AirCare` |
| NaviHelper 3.0.5 | `initialize(onConnected, onFailed)`, `addListener`, `getTBTInfo`, `getDestinationInfo`, `onTBTInfo`, `onCautionPointInfo`, `onDestinationInfo`, `onRouteStarted`, `onNavigationStatus`, `requestRoute`(점검용) | `RealSignals` |
| TextToSpeech 3.3.1 | `TextToSpeech(context, Mode.ON_DEVICE)`, `initialize`, `setLocale(KOREA)`, `setAudioAttributes`, `setSpeakerType`(알림 목소리), `speak`, `addEventListener` | `Speaker` (초기화 실패 시 Android 기본 TTS로 대체) |
| SpeechToText 3.3.1 | `SpeechToText(context, Mode.ON_DEVICE)`, `initialize`, `setLocale(KOREA)`, `addListener`, `request`, `stop`, `release` | `Listener` |
| FusedLocation 1.1.3 | `initialize`, `isSupported`, `registerFusedLocationCallback`, `unregisterFusedLocationCallback`, `release` | `LiveVehicle` 속도 대체·진행 방향 |
| AAOS | `CarPropertyManager` `PERF_VEHICLE_SPEED` | `LiveVehicle` 속도 |

## 에뮬레이터에서 확인한 것 (2026-10-21)

최종 화면은 `docs/screens/8x_*.png`(8단계 시연 녹화)와 `5x_08~10`(탑승자 설정)이다. 아래 단계별 기록의 화면은 `docs/screens/archive/`로 옮겼다.

- 운전자 화면, Replay 중 우회전 1.5초 전 "곧 오른쪽" 음성(Pleos TTS 온디바이스, 화자 seoyoon), Virtual Console 조향 25°·브레이크 60%로 흔들림 지수 8 → 32, 공조 케어 실행 후 하단 공조 바 운전석 22.0° → 21.0°, 내기순환 아이콘 해제, Virtual Console 상태 `recirc_on=false`, 풍량 3 → 4. 화면: `docs/screens/archive/0*.png`.
- 주행 제한: 매니페스트에 `distractionOptimized=true`를 선언한 사이드로드 APK는 기어 D·30 km/h에서도 가려지지 않았다(`archive/07_drive_restriction_D_30kmh.png`).
- 공조 영역: 내기순환은 1·2열 전체 영역, 온도는 `ROW_1_LEFT`, 풍량은 `ROW_1_LEFT+ROW_1_RIGHT`로 응답. 뒷좌석 단독 온도·풍량 영역은 에뮬레이터에 없다.

2단계 확인 (화면 `docs/screens/archive/2x_*.png`):

- R8 릴리스: 처음 빌드는 실행 즉시 `IllegalStateException: CompositionLocal LocalLifecycleOwner not present`로 죽었다. 원인은 `lifecycle-runtime-compose` 2.9가 Compose UI 1.6의 `LocalLifecycleOwner`를 리플렉션으로 찾는데 R8이 이름을 바꾼 것. 그 의존성을 빼고 `collectAsState`로 바꿔 해결. Pleos SDK(Vehicle·NaviHelper·TTS·STT·FusedLocation)는 릴리스에서 정상 초기화되고 시험 주행 전체(알림 3회, 공조 케어, 휴식 제안, 리포트)가 끝까지 돌았다.
- STT: `SpeechToText` 초기화는 성공(`stt ready`)하고 `request()`도 오류가 없지만 인식이 시작되지 않아 6초 뒤 "대답이 없었어요"로 끝난다. 에뮬레이터 로그에 `Voice is not capturing` — AVD에 마이크 입력이 없다. 실차/마이크 연결 시험은 남음.
- 주야간: `adb shell cmd uimode night yes`로 시스템을 밤으로 바꾸면 앱이 밤 색으로 바뀐다. 주행 중 전환도 문제없었다(`archive/2x_03_driver_night_replay.png`).
- 실제 내비 경로: `requestRoute`(가평역)를 보내자 기본 내비가 경로를 잡고 `onRouteStarted`(75 km, 5460초)·`onNavigationStatus ROUTE_DRIVE`가 왔으며, D·30 km/h에서 실제 TBT로 "곧 오른쪽" 알림이 한 번 났다. 그런데 그때 내비 화면은 80 m 앞 좌회전이었다(`archive/2x_11_navi_probe.png`). TBT 목록 원문을 남기도록 로그를 늘렸지만, 에뮬레이터 재부팅 뒤 두 번째 시도에서는 요청이 내비로 넘어갔는데(`requestToNaviProvider(RequestRoute)`) 경로가 시작되지 않아 확인하지 못했다. `onCautionPointInfo`는 두 번 다 오지 않았다.
3단계 확인 (실제 내비 경로, 화면 `docs/screens/archive/3x_*.png`):

- 회전 방향 변환: `navi-data` 3.0.4 `constants/TurnType.kt` 기준으로 `EventKind.of(TurnType)`를 점검했다. 이름이 `_R`인 종류는 모두 오른쪽, `_L`은 모두 왼쪽, `_C`(정면)·직진·도착·회전교차로 12시/진출은 알림 없음, 회전교차로 2·3·4시 = 오른쪽, 8·9·10시 = 왼쪽, 6시 = 유턴, 휴게소·졸음쉼터 = 휴식. 단위 테스트 `turnTypeMapsToTheSideTheCarTurns`. 지하차도·고가 진입/진출 좌우와 회전교차로는 이번에 새로 알림 대상에 넣었다.
- 실제 내비가 준 원문: `TURN_LEFT 80m '기업지원허브 방면' | TURN_LEFT 390m '성남시청 방면' | TURN_KEEP_R 1000m '대왕판교로'`(목록 29개, 가까운 순). 내비 화면 "80m 좌회전"과 같다. 2단계의 "곧 오른쪽"이 어느 항목에서 나왔는지는 그때 로그가 없어 확인하지 못했다. 지금은 TBT 목록의 앞부분이 바뀔 때마다 원문(이름·순번·값·거리·설명·변환 결과)을, 알림마다 `cue for <id> <종류> '<설명>' at <거리>m`을 남긴다.
- 차를 경로 위로 움직이는 방법: Virtual Console 속도는 위치를 움직이지 않는다(내비 거리 그대로). 내비 앱 설정에 모의 주행이 없고 NaviHelper에도 모의 주행 API가 없다(`SIMULATION_GUIDING`은 상태값으로만 있음). 남은 방법은 `adb emu geo fix`를 경로 좌표를 따라 1초마다 보내는 것(경로 좌표는 OSRM 같은 외부 경로로 만들고, 내비가 재탐색하며 따라오게 함).
- 막힌 점: 이 PC에서 `-gpu swiftshader_indirect`로 실행하면, 경로 안내가 시작된 뒤 내비 지도가 그려질 때 에뮬레이터(qemu-system-x86_64.exe)가 `0xc0000005`로 죽는다. 3번 재현(18:58, 19:15, 19:18, 경로 시작 후 25초~2분). 그래서 3번 연속 회전 검증과 `onCautionPointInfo` 확인은 하지 못했다.
- 나란히 보기: 홈 화면은 주행 뷰(왼쪽 853 px)와 앱 영역(오른쪽 1682 px) 두 칸을 TaskView로 띄운다. 내비가 앱 영역에 있을 때 오른쪽 가장자리 핸들을 눌러도 끌어도 화면이 나뉘지 않았다. NaviHelper의 `updateOccupiedRegion`은 `@suppress` 내부 API라 쓰지 않았다.

4단계 확인 (`-gpu host`, 화면 `docs/screens/archive/4x_*.png`, 시연 대본 `docs/demo_script.md`):

- `-gpu host`에서는 경로 안내 중 4분 넘게 에뮬레이터가 죽지 않았고 지도도 그려졌다. 3D 사용률은 최대 12%.
- 회전 방향 버그 원인: `onTBTInfo` 목록은 가까운 순이 아니다. 원문 `TURN_LEFT 80m | TURN_LEFT 390m | TURN_KEEP_R 1000m | TURN_RIGHT 70m …`에서 앱이 거리가 가장 짧은 4번째 항목(우회전 70 m)을 다음 회전으로 골라 "곧 오른쪽"이라고 했다. 이제 목록의 첫 항목만 다음 회전으로 쓴다(`RealSignals.onTBTInfo`).
- 뒷좌석 착석 반전: Vehicle 2.3.2는 SEAT_OCCUPANCY를 `value == 1`이면 true로 넘긴다. 그런데 AAOS 값은 1 = 비어 있음, 2 = 앉음이다. 그래서 Virtual Console에서 앉히면 SDK는 false를 준다. 앱은 SDK의 false를 착석으로 읽는다(`LiveVehicle.occupied`). 이 반전 때문에 실제 모드와 Replay에서 알림이 꺼져 있었다. 이제 Replay도 실제 착석값을 따른다(`trip_demo.json` `row2Occupied=false`).
- 깜빡이 확인: 방향지시등 소리 때문에 `isMusicActive`가 true가 되어, 깜빡이로 앞당긴 알림이 묵음 처리되었다. 이 검사를 빼고 오디오 포커스 요청 결과만으로 판단한다.
- 실제 내비 위에서 차를 움직이기: 안 된다. 에뮬레이터 `geo fix`의 NMEA 체크섬이 늘 `*47`이라 내비(`GpsLocationSource`)가 `checksum mismatch`로 버린다. 체크섬을 맞춘 `geo nmea`(GGA·RMC)도 GNSS HAL이 위치로 반영하지 않았다. 내비 위치는 경로 시작점(127.094681, 37.4127212)에 머물고 회전이 지나가지 않아, 3번 연속 회전 검증과 `onCautionPointInfo` 확인은 하지 못했다.
- 시연 소리: 처음에는 PC 출력 loopback에 안 잡히는 것처럼 보였는데, 실제로는 아주 작게(약 −45 dBFS) 나오고 있었다. 5단계에서 float로 녹음한 뒤 키워서 해결(`docs/demo_script.md` "녹화").

5단계 확인:

- 테스트 위치 제공자: `appops set --uid 2000 MOCK_LOCATION allow` 뒤 `cmd location providers add-test-provider gps`, `set-test-provider-location`이 동작해 시스템 위치는 바뀐다(`gps provider [mock]`). 하지만 내비는 NMEA 문장만 읽어서 위치가 그대로다. 실제 경로 위 주행 시연은 이 AVD에서 하지 못한다.
- STT "응" 경로: 사람이 PC 마이크에 말해야 해서 시험하지 않았다(에뮬레이터는 `-allow-host-audio`로 실행 가능).
- 시연 원본 녹화: `docs/demo/raw_demo.mp4`(182초, 소리 포함), 화면 `docs/screens/archive/5x_*.png`.

실제 경로 시연 절차(남은 일, 위 문제를 푼 뒤):

1. `-gpu host`로 에뮬레이터 실행(지도 렌더링 때문에 필요할 수 있음, GPU 사용 승인 필요) → Virtual Console 하트비트, 뒷좌석 착석 `seat?area=rear_right&kind=occupied&value=true`.
2. `adb emu geo fix <출발 경도> <위도>` → `am start -n ai.softride.app/.MainActivity` → 실행 중인 앱에 `--ez navi_probe true`(가평역 경로 요청).
3. 기어 D, 속도 36 km/h, 경로 좌표를 10 m 간격으로 1초마다 `geo fix`.
4. `adb logcat -s SoftRide`에서 `navi onTBTInfo …`(내비 원문)와 `cue for …`/`cue: 곧 …`(앱 알림)을 맞춰 본다.

- 공조 바: 앱이 운전석 온도를 21.0 → 20.0으로 바꾼 기록이 있는데 하단 공조 바는 21.0°로 남은 경우가 있었다(1단계에서도 한 번 관찰).
