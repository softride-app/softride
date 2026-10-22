import numpy as np, soundfile as sf, datetime
D=r"A:\1\공모전_2026하반기\현대차앱\softride\docs\demo\take2"
d,sr=sf.read(D+r"\raw_audio_loopback.wav"); x=np.abs(d).max(axis=1)
w=480; n=len(x)//w; e=np.sqrt((x[:n*w].reshape(n,w)**2).mean(axis=1))
on=e>2e-5   # ~ -94 dBFS before boost; floor is 6e-6 median
segs=[];i=0
while i<n:
    if on[i]:
        j=i; g=0
        while j<n and g<10:
            g = 0 if on[j] else g+1; j+=1
        j-=g; segs.append((i*0.01,j*0.01,e[i:j].max())); i=j+1
    else: i+=1
cues={"right":29.47,"bump":52.95,"left":72.18,"air":86.38,"rest":108.40,"gaze":123.56}
print("all sound segments (audio time s, len, peak rms):")
for a,b,m in segs: 
    tag=[k for k,t in cues.items() if t+1.0<=a<=t+4.0]
    print("%.2f-%.2f len %.2f rms %.4f %s"%(a,b,b-a,m,tag[0] if tag else "OUTSIDE"))
