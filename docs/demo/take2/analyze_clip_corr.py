import json, subprocess, numpy as np, soundfile as sf, datetime, sys, os
R=r"A:\1\공모전_2026하반기\현대차앱\softride"; D=R+r"\docs\demo\take2"; speed=sys.argv[1]
idx=json.load(open(R+r"\app\src\main\assets\voice\index.json",encoding="utf-8"))
d,sr=sf.read(D+r"\raw_audio_loopback.wav"); x=np.abs(d).max(axis=1)
lines=[l for l in open(D+r"\cue_times.log",encoding="utf-8") if "cue:" in l]
a0=[l for l in open(D+r"\cue_times.log",encoding="utf-8") if "audio start" in l][0].split("audio start ")[1].strip()
base=datetime.datetime.strptime("2026-10-22 "+a0,"%Y-%m-%d %H:%M:%S.%f")
F=r"C:\Program Files\PicShorts\bin\ffmpeg.exe"
def env(a,w=480): n=len(a)//w; return np.sqrt((a[:n*w].reshape(n,w)**2).mean(axis=1))
floor=np.median(env(x))
print("noise floor(median 10ms rms) %.6f, global peak %.4f"%(floor,x.max()))
for l in lines:
    ts=l.split()[1]; ph=l.split("cue: ")[1].split(" via")[0]
    t=(datetime.datetime.strptime("2026-10-22 "+ts,"%Y-%m-%d %H:%M:%S.%f")-base).total_seconds()
    f=R+r"\app\src\main\assets\voice\%s\%s"%(speed,idx[ph])
    raw=subprocess.run([F,"-v","quiet","-i",f,"-f","f32le","-ac","1","-ar","48000","-"],capture_output=True).stdout
    c=np.frombuffer(raw,dtype=np.float32); dur=len(c)/48000
    earcon = ph not in ("1킬로미터 앞 휴게소에서 쉬었다 갈까요?",)
    st=t+(0.3 if earcon else 0)   # clip start in audio time
    ref=env(np.abs(c))
    best=(-2,0)
    for lag in np.arange(0.0,1.6,0.01):
        a=int((st+lag)*100); seg=env(x)[a:a+len(ref)]
        if len(seg)==len(ref) and seg.std()>0:
            r=np.corrcoef(seg,ref)[0,1]
            if r>best[0]: best=(r,lag)
    st=st+best[1]; t=st-(0.3 if earcon else 0)
    # window before/after
    w0=int((t-0.5)*sr); w1=int((t+(0.3 if earcon else 0)+dur+0.5)*sr)
    e=env(x[w0:w1],480)
    # residual: loopback minus best-scaled clip env outside the clip span
    pre=env(x[int((t-0.5)*sr):int(t*sr)]); post=env(x[int((st+dur)*sr):int((st+dur+0.5)*sr)])
    print("%-24s cue t=%.2fs(audio) dur=%.2fs corr=%.3f lag=%+.2fs | win peak %.4f | pre-chime max rms %.5f post-clip max rms %.5f | active sessions above floor: pre %d/%d post %d/%d (10ms bins>5x floor)"%(ph,t,dur,best[0],best[1],x[w0:w1].max(),pre.max(),post.max(),(pre>5*floor).sum(),len(pre),(post>5*floor).sum(),len(post)))



