# 소프트라이드 시연 영상 대본 (80초, Replay 사용)

실제 내비 경로 대신 시험 주행(Replay)을 쓴다. 이유: 이 AVD에서는 내비 차량 위치를 움직일 수 없다. `adb emu geo fix`가 만드는 NMEA 문장의 체크섬이 틀려(`*47`) 내비가 위치를 버리고(`Invalid NMEA message … checksum mismatch`), 체크섬을 맞춘 `geo nmea`도 GNSS HAL이 위치로 받아들이지 않았다. 내비는 경로 시작점에 멈춰 있어서 회전이 하나도 지나가지 않는다.

시간은 2026-10-21 리허설(릴리스 APK, `-gpu host`)에서 잰 값이다. T는 "시험 주행 시작" 시점부터 센다. 영상은 T 약 15초~150초에서 필요한 부분을 잘라 80초로 편집한다(아래 "편집" 열).

## 준비 (녹화 전)

```powershell
$env:JAVA_HOME='A:\AppStudio\AndroidDev\jdk17'; $env:ANDROID_SDK_ROOT='A:\AppStudio\AndroidDev\sdk'
A:\AppStudio\AndroidDev\sdk\emulator\emulator.exe -avd pleos_connect_21 -gpu host -no-snapshot -no-boot-anim -port 5580
adb -s emulator-5580 install -r -g apk\SoftRide_release.apk
adb -s emulator-5580 forward tcp:8088 tcp:8088
# 하트비트(별도 창, 녹화 내내)
while($true){ curl.exe -s -o NUL -X POST "http://127.0.0.1:8088/browser/session?client=demo"; Start-Sleep -Milliseconds 800 }
```

```powershell
$V='http://127.0.0.1:8088'
curl.exe -X PUT "$V/vehicle/gear?gear=Park"
curl.exe -X PUT "$V/vehicle/seat?area=rear_left&kind=occupied&value=false"
curl.exe -X PUT "$V/vehicle/seat?area=rear_right&kind=occupied&value=false"
curl.exe -X PUT "$V/vehicle/controls?speed=0&brake=0&steering=0"
curl.exe -X PUT "$V/vehicle/hvac?power=1"; curl.exe -X PUT "$V/vehicle/hvac?recirc=1"; curl.exe -X PUT "$V/vehicle/hvac?driver_temp=22"
adb -s emulator-5580 shell cmd uimode night no
adb -s emulator-5580 shell am force-stop ai.softride.app
adb -s emulator-5580 shell am start -W -n ai.softride.app/.MainActivity
```

## 장면 목록

| 편집 시간 | T | 화면 | 명령 (그 순간에 실행) | 확인할 것 |
| --- | --- | --- | --- | --- |
| 0:00–0:06 | 출발 전 | 주차, "출발 전이에요", "서윤(7세)의 자리가 비어 있어요" | (없음) | `4x_demo_00_idle_seat_empty.png` |
| 0:06–0:12 | 출발 전 | 뒷좌석에 앉음 → "뒷좌석 알림 켜짐" | `curl.exe -X PUT "$V/vehicle/seat?area=rear_right&kind=occupied&value=true"` | `4x_demo_01_seat_occupied.png` |
| 0:12–0:14 | T=0 | 시험 주행 시작, "시뮬레이션 입력" 표시. 왼쪽 클러스터도 D와 같은 속도 | `adb -s emulator-5580 shell am start -n ai.softride.app/.MainActivity --ez replay true`, 곧바로 `curl.exe -X PUT "$V/vehicle/gear?gear=Drive"`. 이후 도착까지 약 1초마다 `controls?speed=<km/h>&brake=<현재>&steering=<현재>` (속도는 `trip_demo.json` 값 × 3.6, 정수만 받는다) | 앱 속도 출처는 `car`(PERF_VEHICLE_SPEED)로 바뀌지만 값이 같아 알림 위치는 그대로 |
| 0:14–0:24 | T=18–23 | "100m 앞 우회전" → 오른쪽 깜빡이 → "곧 오른쪽" 음성, "깜빡이와 함께 알렸어요" | T=19.5: `curl.exe -X PUT "$V/vehicle/turn-signal?mode=BlinkRight"` / T=22: `curl.exe -X PUT "$V/vehicle/turn-signal?mode=Off"` | 깜빡이를 켜면 150 m 안의 같은 방향 회전 알림이 앞당겨진다(T 약 19초, 53 m 앞). 에뮬레이터가 측면 카메라 화면을 앱 왼쪽 위에 띄운다 — 깜빡이를 2.5초만 켜서 그 시간만 보인다(끄면 바로 닫힘). 회전 카드는 가리지 않는다 |
| 0:24–0:30 | T=42–47 | "곧 덜컹" (과속방지턱) | (없음) | 로그 `cue: 곧 덜컹` |
| 0:30–0:36 | T=61–66 | "곧 왼쪽" (좌회전) | (없음) | |
| 0:36–0:50 | T=74–90 | 굽은 길, 흔들림 지수가 5초마다 올라감(조금 출렁여요) | T=74부터 2초 간격: `controls?speed=47&steering=20` → `-20` → `25` → `-22` → `20` → `0`, 이어서 `brake=40`, 2초 뒤 `brake=0&steering=0` (모두 `curl.exe -X PUT "$V/vehicle/controls?speed=<현재>&brake=…&steering=…"`, 속도를 같이 보내야 0으로 떨어지지 않는다) | |
| 0:50–0:58 | T=77–83 | "바깥 공기로 바꿀게요" 음성 → 하단 공조 바 운전석 22.0° → 21.0°, 내기순환 꺼짐 | (없음) | `4x_demo_03_air_care_climate_bar.png` |
| 0:58–1:08 | T=97–106 | "400미터 앞 휴게소에서 쉬었다 갈까요?" → "“응” 하고 말하면 돼요" → 6초 뒤 "그대로 갈게요" | (없음) | AVD에는 마이크 입력이 없어 대답은 항상 시간 초과 |
| 1:08–1:20 | T=150–156 | 휴게소 도착 → 주행 리포트: 점수, 가장 흔들린 구간, 미리 알림 3 · 공조 케어 1. 클러스터 P, 0 km/h | T≈151.5: `controls?speed=0&brake=0&steering=0`, 이어서 `gear?gear=Park` | `6x_05_trip_report_parked.png` |
| (선택) | 리포트 후 | 밤 화면 | `adb -s emulator-5580 shell cmd uimode night yes` | 주행 중에 바꿔도 된다 |

## 녹화 (자동)

`docs/demo/record_demo.py`가 위 장면 목록을 그대로 실행하면서 녹화한다. 에뮬레이터를 `-gpu host`로 띄우고, 에뮬레이터 창을 가리지 않은 상태에서 실행한다. 표시 영역 좌표는 창 위치에 따라 다르다(2026-10-21: x 100, y 131, 2048×1152 = 80% 크기).

```powershell
pip install soundcard soundfile
cd docs\demo; python record_demo.py . 100 131 2048 1152
```

결과 (2026-10-21 20:08 녹화, 6단계: 주행 중 클러스터 D·속도 동기화):

| 파일 | 내용 |
| --- | --- |
| `docs/demo/raw_demo.mp4` | 편집 원본: 에뮬레이터 화면 원본 2560×1440 + PC 소리(+39 dB 증폭), 179.6초, 116 MB — git 제외 |
| `docs/demo/raw_video_gdigrab.mp4` | PC 화면 녹화 2048×1152 30 fps, 182초 (예비) — git 제외 |
| `docs/demo/raw_video_native.mp4` | 에뮬레이터 화면 원본 2560×1440 (`screenrecord`, 소리 없음, 179.6초) — git 제외 |
| `docs/demo/raw_audio_loopback.wav` | PC 출력 loopback, 48 kHz float — git 제외 |
| `docs/demo/raw_commands.log` | Virtual Console·adb 명령마다 시각과 T 값 (자막용) |
| `docs/demo/cue_times.log` | 앱 로그의 알림 시각과 문구 |
| `docs/demo/test_av.mp4` | 12초 확인용: "곧 오른쪽"이 화면과 소리로 함께 나오는 부분 |

- 소리: 에뮬레이터 소리는 PC 기본 출력으로 나온다. 다만 매우 작다(최대 −45 dBFS 정도, 차량 내비 음량 그룹 20/45). 그래서 녹음은 float로 받고 합칠 때 `volume=39dB,alimiter`로 키운다. 볼륨 키(`input keyevent KEYCODE_VOLUME_UP`)는 미디어 음량 그룹만 올라가고, `cmd car_service set-group-volume`은 듣지 않았다.
- 지연: 앱 로그의 알림 시각보다 PC 소리가 약 0.65초 늦다(효과음 기준). 편집 때 소리를 0.3~0.6초 앞당기면 화면과 더 잘 맞는다.
- 녹화 중 오른쪽 깜빡이 소리(1초 간격 딸깍)도 함께 녹음된다(T 약 19.5~22초).
- 영상 시간 = T + 10.0초 (`raw_demo.mp4`는 리플레이 시작 10초 전부터 녹화).
- 알림 시각 (7단계 녹화 2026-10-21 20:32, 에뮬레이터 시계, 리플레이 시작 20:32:18.26 기준 T): 곧 오른쪽 20:32:37.36 (T 19.1, 52 m 앞, 43 km/h, 깜빡이로 앞당김) · 곧 덜컹 20:33:01.53 (T 43.3, 10 m, 26 km/h) · 곧 왼쪽 20:33:20.53 (T 62.3, 10 m, 24 km/h) · 바깥 공기로 바꿀게요 20:33:34.81 (T 76.6) · 휴게소 제안 20:33:56.79 (T 98.5) · 주행 끝 20:34:49.87 (T 151.6).
- 효과음→음성 간격 (loopback 측정, 50 ms 단위): 곧 오른쪽 0.00초(깜빡이 소리가 나는 중), 곧 덜컹 0.05초, 곧 왼쪽 0.05초, 바깥 공기로 0.10초. 6단계 녹화에서는 곧 오른쪽만 3.1초 늦었다 — 부팅 뒤 첫 음성 합성이 느린 것이 원인이었고(깜빡이와 무관), 앱 시작·주행 시작 때 빈 문장으로 TTS를 미리 깨워 고쳤다.
