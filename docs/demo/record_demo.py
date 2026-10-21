"""Record the SoftRide demo run (docs/demo_script.md) on the Pleos AVD.

Video: ffmpeg gdigrab of the emulator display area (no window chrome) at 30 fps, plus adb screenrecord
(native 2560x1440, no audio) as a clean backup. Audio: WASAPI loopback of the default PC output (float32).
Every Virtual Console / adb command is logged with a timestamp to raw_commands.log.
Usage: python record_demo.py <out_dir> <x> <y> <w> <h>   (display area in desktop pixels)
"""
import subprocess, sys, threading, time, datetime, os, urllib.request
import numpy as np, soundcard as sc, soundfile as sf

OUT, X, Y, W, H = sys.argv[1], *map(int, sys.argv[2:6])
FF = r"C:\Program Files\PicShorts\bin\ffmpeg.exe"
ADB = ["adb", "-s", "emulator-5580"]
V = "http://127.0.0.1:8088"
SHOTS = os.path.join(OUT, "..", "screens")
log = open(os.path.join(OUT, "raw_commands.log"), "w", encoding="utf-8")
T0 = None

def stamp(what):
    now = time.time()
    t = f"{now - T0:7.2f}s" if T0 else "   pre  "
    line = f"{datetime.datetime.fromtimestamp(now):%H:%M:%S.%f}"[:-3] + f"  T={t}  {what}"
    log.write(line + "\n"); log.flush(); print(line)

def vc(q):
    stamp(f"VC PUT {q}")
    urllib.request.urlopen(urllib.request.Request(V + q, method="PUT"), timeout=5).read()

def adb(*a):
    stamp("adb " + " ".join(a))
    subprocess.run(ADB + list(a), capture_output=True)

def shot(name):
    with open(os.path.join(SHOTS, name), "wb") as f:
        f.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
    stamp(f"screenshot {name}")

def at(sec):
    while time.time() - T0 < sec: time.sleep(0.05)

# heartbeat for the Virtual Console
stop = False
def beat():
    while not stop:
        try: urllib.request.urlopen(urllib.request.Request(V + "/browser/session?client=demo", method="POST"), timeout=2).read()
        except Exception: pass
        time.sleep(0.8)
threading.Thread(target=beat, daemon=True).start()
time.sleep(1.5)

# reset state (not recorded)
for q in ["/vehicle/gear?gear=Park", "/vehicle/seat?area=rear_left&kind=occupied&value=false",
          "/vehicle/seat?area=rear_right&kind=occupied&value=false", "/vehicle/controls?speed=0&brake=0&steering=0",
          "/vehicle/turn-signal?mode=Off", "/vehicle/hvac?power=1", "/vehicle/hvac?recirc=1", "/vehicle/hvac?driver_temp=22"]:
    vc(q)
adb("shell", "cmd", "uimode", "night", "no")
adb("shell", "am", "force-stop", "ai.softride.app")
adb("logcat", "-c")
adb("shell", "am", "start", "-W", "-n", "ai.softride.app/.MainActivity")
time.sleep(3)

# start recorders
DUR = 182
spk = sc.default_speaker()
mic = sc.get_microphone(id=str(spk.name), include_loopback=True)
audio = {}
def rec_audio():
    with mic.recorder(samplerate=48000, channels=2) as r:
        audio["t"] = time.time()
        audio["d"] = r.record(numframes=48000 * DUR)
ta = threading.Thread(target=rec_audio); ta.start()
vid = subprocess.Popen([FF, "-y", "-hide_banner", "-loglevel", "error", "-f", "gdigrab", "-framerate", "30",
                        "-offset_x", str(X), "-offset_y", str(Y), "-video_size", f"{W}x{H}", "-draw_mouse", "0",
                        "-i", "desktop", "-t", str(DUR), "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                        "-pix_fmt", "yuv420p", os.path.join(OUT, "raw_video_gdigrab.mp4")])
vid_t = time.time()
sr = subprocess.Popen(ADB + ["shell", "screenrecord", "--time-limit", "180", "--bit-rate", "12000000", "/sdcard/raw_demo_native.mp4"])
sr_t = time.time()
stamp(f"recorders started: gdigrab {vid_t:.3f}, screenrecord {sr_t:.3f}")

time.sleep(3); shot("5x_01_idle_seat_empty.png")
time.sleep(2); vc("/vehicle/seat?area=rear_right&kind=occupied&value=true")
time.sleep(3); shot("5x_02_seat_occupied.png")
time.sleep(1)
T0 = time.time()
adb("shell", "am", "start", "-n", "ai.softride.app/.MainActivity", "--ez", "replay", "true")
at(21); vc("/vehicle/turn-signal?mode=BlinkRight")
at(25.5); shot("5x_03_right_cue_signal.png")
at(28); vc("/vehicle/turn-signal?mode=Off")
at(74)
for st in (20, -20, 25, -22, 20, 0):
    vc(f"/vehicle/controls?steering={st}&brake=0"); time.sleep(2)
vc("/vehicle/controls?brake=40"); time.sleep(2); vc("/vehicle/controls?brake=0&steering=0")
at(84); shot("5x_04_air_care_climate_bar.png")
at(101); shot("5x_05_rest_prompt_listening.png")
at(156); shot("5x_06_trip_report_3_cues.png")
at(160); adb("shell", "cmd", "uimode", "night", "yes")
at(165); shot("5x_07_trip_report_night.png")
at(166); adb("shell", "cmd", "uimode", "night", "no")
stamp("end of scripted run")

vid.wait(); ta.join(); sr.wait()
stop = True
d = audio["d"]
sf.write(os.path.join(OUT, "raw_audio_loopback.wav"), d, 48000, subtype="FLOAT")
peak = float(np.abs(d).max())
stamp(f"audio saved, peak {peak:.4f}; audio started {audio['t']:.3f} (gdigrab {vid_t - audio['t']:+.3f}s, screenrecord {sr_t - audio['t']:+.3f}s after audio)")
subprocess.run(ADB + ["pull", "/sdcard/raw_demo_native.mp4", os.path.join(OUT, "raw_video_native.mp4")], capture_output=True)
cues = subprocess.run(ADB + ["logcat", "-d", "-s", "SoftRide"], capture_output=True, text=True, encoding="utf-8").stdout
with open(os.path.join(OUT, "cue_times.log"), "w", encoding="utf-8") as f:
    f.write(f"# replay start (T=0) at {datetime.datetime.fromtimestamp(T0):%H:%M:%S.%f}; audio start {datetime.datetime.fromtimestamp(audio['t']):%H:%M:%S.%f}\n")
    for line in cues.splitlines():
        if any(k in line for k in ("cue", "air care", "trip", "stt", "seat")): f.write(line + "\n")
print("done")
