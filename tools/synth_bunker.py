# Звуки бетонобойной бомбы (GBU-57-подобной) и бомбардировщика B-2.
"""Синтез звуков бетонобойной бомбы и B-2 (bb_*) для пакета Airstrike Sounds.
  python3 tools/synth_bunker.py [папка_вывода]   (по умолчанию build/sounds); нужны numpy и ffmpeg.
  Готовые .ogg → resourcepack/airstrike-sounds/assets/airstrike/sounds/.
"""
import numpy as np, subprocess, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import BUILD
SR = 32000; rng = np.random.default_rng(57)
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BUILD, "sounds"); os.makedirs(OUT, exist_ok=True)
def F(x, lo=None, hi=None, bells=()):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1/SR); H = np.ones_like(f)
    if lo: H *= 1/np.sqrt(1+(lo/np.maximum(f,1e-3))**4)
    if hi: H *= 1/np.sqrt(1+(f/hi)**4)
    for fc, db, q in bells: H *= 1 + (10**(db/20)-1)*np.exp(-0.5*((np.log2(np.maximum(f,1)/fc))/q)**2)
    return np.fft.irfft(X*H, len(x))
def conv(x, h):
    n = len(x)+len(h)-1; N = 1<<(n-1).bit_length()
    return np.fft.irfft(np.fft.rfft(x,N)*np.fft.rfft(h,N), N)[:len(x)]
def rev(x, t60=1.5, mix=0.35, pre=0.02, hi=3000):
    n = int(SR*t60*1.3); t = np.arange(n)/SR
    ir = F(rng.standard_normal(n)*np.exp(-6.9*t/t60), hi=hi); ir[:int(pre*SR)] = 0; ir /= np.sqrt((ir**2).sum())
    y = conv(x, ir); return (1-mix)*x + mix*y*np.std(x)/max(np.std(y),1e-9)
def norm(x, rms_db=-12, peak=0.82):
    x = x-np.mean(x); x *= 10**(rms_db/20)/max(np.sqrt(np.mean(x**2)),1e-9)
    x = np.tanh(x*1.2)/np.tanh(1.2); return x*min(1.0, peak/np.max(np.abs(x)))
def pad(x, sec): return np.concatenate([x, np.zeros(int(sec*SR))])
def write(name, x):
    raw = f"{OUT}/{name}.f32"; x.astype(np.float32).tofile(raw)
    subprocess.run(["ffmpeg","-loglevel","error","-y","-f","f32le","-ar",str(SR),"-ac","1","-i",raw,"-c:a","libvorbis","-q:a","5",f"{OUT}/{name}.ogg"],check=True); os.remove(raw)
def grains(sig, name, n=6, glen=0.6):
    L = int(glen*SR); w = np.hanning(L)
    for i in range(n):
        o = rng.integers(0, len(sig)-L); write(f"{name}_{i}", sig[o:o+L]*w)
def env_noise(n, rate, depth):   # медленная «турбулентная» модуляция
    m = F(rng.standard_normal(n), hi=rate); m /= np.max(np.abs(m)); return 1 + depth*m

# --- B-2 на высоте: глухой раскатистый гул с «дыханием» атмосферы ---
n = int(10*SR)
roar = F(rng.standard_normal(n), lo=30, hi=2200, bells=[(90,8,0.9),(250,5,1.0)])
whine = np.sin(2*np.pi*np.cumsum(1450*(1+0.003*np.sin(2*np.pi*0.2*np.arange(n)/SR)))/SR)
jet = roar/np.std(roar)*env_noise(n, 1.5, 0.35) + 0.12*whine
grains(norm(rev(F(jet, hi=1600), t60=2.5, mix=0.4), -12), "bb_jet_mid")
grains(norm(rev(F(jet, lo=25, hi=500), t60=3.5, mix=0.55), -12), "bb_jet_far")

# --- падающая бомба: рвущийся воздух, шипящий рёв с тоном обтекания ---
n = int(8*SR); t = np.arange(n)/SR
tear = F(rng.standard_normal(n), lo=300, hi=9000, bells=[(1100,9,0.35),(2600,6,0.5)])
tone = np.sin(2*np.pi*np.cumsum(880*(1+0.01*np.sin(2*np.pi*3.1*t)))/SR) + 0.4*np.sin(2*np.pi*np.cumsum(1760*(1+0.01*np.sin(2*np.pi*3.1*t)))/SR)
rumble = F(rng.standard_normal(n), lo=40, hi=400)
fall = tear/np.std(tear)*env_noise(n, 8, 0.4) + 0.35*tone + 0.6*rumble/np.std(rumble)
grains(norm(fall, -11), "bb_fall_near")
grains(norm(rev(F(fall, hi=3500), t60=1.0, mix=0.3), -12), "bb_fall_mid")
grains(norm(rev(F(fall, lo=60, hi=1200), t60=2.0, mix=0.45), -12), "bb_fall_far")

# --- звуковой удар (N-волна): два хлёстких щелчка ---
n = int(2.5*SR); t = np.arange(n)/SR; x = np.zeros(n)
for t0 in (0.02, 0.13):
    k = int(t0*SR); L = int(0.004*SR)
    x[k:k+L] += np.linspace(1, -1, L)
x = F(x, lo=20, hi=9000)
x = x + F(rng.standard_normal(n), lo=30, hi=300)*np.exp(-t/0.6)*0.25
write("bb_crack", norm(rev(x, t60=1.8, mix=0.35), -10))

# --- удар о грунт: тупой удар по земле, хруст породы ---
n = int(3.5*SR); t = np.arange(n)/SR
thump = np.sin(2*np.pi*(48*t - 8*t*t))*np.exp(-t/0.25)*1.8
crunch = F(rng.standard_normal(n), lo=200, hi=2800)*np.exp(-t/0.35)*(1+0.8*(rng.random(n)<0.004))
earth = F(rng.standard_normal(n), lo=25, hi=200)*np.exp(-t/0.9)
write("bb_impact", norm(rev(thump + 0.7*crunch/np.std(crunch) + 0.8*earth/np.std(earth), t60=1.6, mix=0.3), -11))

# --- бурение породы под землёй: скрежет и глухие толчки ---
n = int(8*SR); t = np.arange(n)/SR
grind = F(rng.standard_normal(n), lo=60, hi=1400, bells=[(180,6,0.8),(420,4,0.6)])
knocks = np.zeros(n); idx = np.nonzero(rng.random(n) < 22/SR)[0]
for k in idx:
    L = min(int(0.05*SR), n-k); knocks[k:k+L] += np.sin(2*np.pi*70*np.arange(L)/SR)*np.exp(-np.arange(L)/(0.012*SR))*rng.uniform(0.5,1.2)
drill = grind/np.std(grind)*env_noise(n, 6, 0.5) + 1.3*knocks/np.std(knocks)
grains(norm(F(drill, hi=1200), -11), "bb_drill", n=4, glen=0.8)

# --- подземный толчок: почти инфразвук, дребезг ---
n = int(6*SR); t = np.arange(n)/SR
q = F(rng.standard_normal(n), lo=15, hi=90)*np.minimum(1, t/0.15)*np.exp(-t/1.8)
rattle = F(rng.standard_normal(n), lo=900, hi=4000)*(rng.random(n)<0.02)*np.exp(-t/1.2)
write("bb_quake", norm(q/np.std(q) + 0.04*rattle/max(np.std(rattle),1e-9), -9))

# --- глухой подземный взрыв для тех, кто на поверхности ---
n = int(7*SR); t = np.arange(n)/SR
boom = F(rng.standard_normal(n), lo=18, hi=180)*np.exp(-t/0.7)*np.minimum(1, t/0.05)
sub = np.sin(2*np.pi*(36*t - 3*t*t))*np.exp(-t/0.9)
write("bb_deep", norm(rev(boom/np.std(boom) + 1.2*sub, t60=3.2, mix=0.45, hi=400), -9))

# --- выброс газов из скважины: рёв струи ---
n = int(4*SR); t = np.arange(n)/SR
vent = F(rng.standard_normal(n), lo=50, hi=1800, bells=[(250,7,1.0)])*env_noise(n, 12, 0.5)*np.exp(-t/1.6)*np.minimum(1, t/0.08)
write("bb_vent", norm(rev(vent, t60=1.2, mix=0.25), -10))

# --- взрыв в замкнутой пещере: жёстко, с долгим каменным эхом ---
n = int(7*SR); t = np.arange(n)/SR
crack = F(rng.standard_normal(n), lo=40, hi=9000)*np.exp(-t/0.02)
th = np.sin(2*np.pi*(60*t - 10*t*t))*np.exp(-t/0.4)*1.6
debris = F(rng.standard_normal(n), lo=500, hi=6000)*np.exp(-t/1.5)*(rng.random(n)<0.01)
x = crack + th + 0.5*F(rng.standard_normal(n), lo=25, hi=250)*np.exp(-t/1.2) + 0.4*debris
write("bb_cave", norm(rev(x, t60=3.8, mix=0.6, pre=0.03, hi=2500), -9))
print("ok", len(os.listdir(OUT)))
