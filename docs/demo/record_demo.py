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

# Replay speed profile (m/s) so the cluster shows the same speed as SoftRide during Replay.
import json
TRIP = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main",
                                   "assets", "trip_demo.json"), encoding="utf-8"))["samples"]
def replay_kmh(t):
    if t <= TRIP[0]["t"]: return TRIP[0]["speed"] * 3.6
    for a, b in zip(TRIP, TRIP[1:]):
        if b["t"] >= t:
            f = (t - a["t"]) / (b["t"] - a["t"])
            return (a["speed"] + (b["speed"] - a["speed"]) * f) * 3.6
    return 0.0
END = TRIP[-1]["t"]
ctl = {"brake": 0, "steering": 0}
ctl_lock = threading.Lock()
driving = False
last_logged = [None]

def push(log_it=False):
    """Send replay speed (looked 0.5 s ahead, the step lasts 0.5 s) with the current brake/steering in one call."""
    kmh = round(replay_kmh(time.time() - T0 + 0.25))  # the console accepts whole km/h only
    q = f"/vehicle/controls?speed={kmh}&brake={ctl['brake']}&steering={ctl['steering']}"
    if log_it or last_logged[0] is None or abs(kmh - last_logged[0]) >= 5:
        stamp(f"VC PUT {q}"); last_logged[0] = kmh
    urllib.request.urlopen(urllib.request.Request(V + q, method="PUT"), timeout=5).read()

def speed_loop():
    while driving:
        try:
            with ctl_lock: push()
        except Exception as e:
            stamp(f"speed push failed: {e}")
        time.sleep(0.5)

def control(**kw):
    with ctl_lock:
        ctl.update(kw); push(log_it=True)

# heartbeat for the Virtual Console
subprocess.run(ADB + ["forward", "tcp:8088", "tcp:8088"], capture_output=True)
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

time.sleep(5)
vc("/vehicle/seat?area=rear_right&kind=occupied&value=true")
time.sleep(4)
time.sleep(1)
T0 = time.time()
adb("shell", "am", "start", "-n", "ai.softride.app/.MainActivity", "--ez", "replay", "true")
vc("/vehicle/gear?gear=Drive")
driving = True
threading.Thread(target=speed_loop, daemon=True).start()
# short blink: the signal confirms the right-turn cue; a short blink keeps the side-camera view up only briefly
at(19.5); vc("/vehicle/turn-signal?mode=BlinkRight")
at(21.0); shot("6x_01_driving_cluster_D.png")
at(22.0); vc("/vehicle/turn-signal?mode=Off")
at(25.5); shot("6x_02_after_right_cue.png")
at(74)
for st in (20, -20, 25, -22, 20, 0):
    control(steering=st, brake=0); time.sleep(2)
control(brake=40); time.sleep(2); control(brake=0, steering=0)
at(89); shot("6x_03_air_care_climate_bar.png")
at(101); shot("6x_04_rest_prompt_listening.png")
at(END - 0.5)
driving = False
time.sleep(0.6)
vc("/vehicle/controls?speed=0&brake=0&steering=0")
vc("/vehicle/gear?gear=Park")
at(156); shot("6x_05_trip_report_parked.png")
at(160); adb("shell", "cmd", "uimode", "night", "yes")
at(165); shot("6x_06_trip_report_night.png")
at(166); adb("shell", "cmd", "uimode", "night", "no")
stamp("end of scripted run")

vid.wait(); ta.join(); sr.wait()
stop = True
d = audio["d"]
sf.write(os.path.join(OUT, "raw_audio_loopback.wav"), d, 48000, subtype="FLOAT")
peak = float(np.abs(d).max())
stamp(f"audio saved, peak {peak:.4f}; audio started {audio['t']:.3f} (gdigrab {vid_t - audio['t']:+.3f}s, screenrecord {sr_t - audio['t']:+.3f}s after audio)")
subprocess.run(ADB + ["pull", "/sdcard/raw_demo_native.mp4", os.path.join(OUT, "raw_video_native.mp4")], capture_output=True)
# edit source: native 2560x1440 video + loopback audio (boosted, aligned to the screenrecord start)
subprocess.run([FF, "-y", "-v", "error", "-i", os.path.join(OUT, "raw_video_native.mp4"),
                "-ss", f"{max(0.0, sr_t - audio['t']):.3f}", "-i", os.path.join(OUT, "raw_audio_loopback.wav"),
                "-map", "0:v", "-map", "1:a", "-filter:a", "volume=39dB,alimiter=limit=0.9",
                "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", os.path.join(OUT, "raw_demo.mp4")])
cues = subprocess.run(ADB + ["logcat", "-d", "-s", "SoftRide"], capture_output=True, text=True, encoding="utf-8").stdout
with open(os.path.join(OUT, "cue_times.log"), "w", encoding="utf-8") as f:
    f.write(f"# replay start (T=0) at {datetime.datetime.fromtimestamp(T0):%H:%M:%S.%f}; audio start {datetime.datetime.fromtimestamp(audio['t']):%H:%M:%S.%f}\n")
    for line in cues.splitlines():
        if any(k in line for k in ("cue", "air care", "trip", "stt", "seat")): f.write(line + "\n")
print("done")
