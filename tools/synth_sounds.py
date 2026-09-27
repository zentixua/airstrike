"""Синтез звуков шахеда, крылатой ракеты, взрыва и сирены для пакета Airstrike Sounds.

  python3 tools/synth_sounds.py [папка_вывода]      (по умолчанию build/sounds)
Нужны numpy и ffmpeg. Сирена берётся из мода SnAssets (mods/snassets-*.jar инстанса, см. tools/paths.py).
Готовые .ogg копируются в resourcepack/airstrike-sounds/assets/airstrike/sounds/ (имена совпадают),
события описаны в resourcepack/airstrike-sounds/assets/airstrike/sounds.json. Сид фиксирован — результат воспроизводим.
"""
import numpy as np, subprocess, os, json, sys, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import BUILD, MODS
SR = 32000
rng = np.random.default_rng(42)
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BUILD, "sounds"); os.makedirs(OUT, exist_ok=True)
TMP = os.path.join(OUT, "_tmp"); os.makedirs(TMP, exist_ok=True)

def butter_like_lp(x, fc):
    # однополюсные каскады (достаточно для «воздушного» затухания)
    a = np.exp(-2*np.pi*fc/SR); y = np.zeros_like(x); s = 0.0
    for _ in range(2):
        y = np.empty_like(x); s = 0.0
        for i in range(len(x)): s = (1-a)*x[i] + a*s; y[i] = s
        x = y
    return y
def fft_filter(x, lo=None, hi=None, shelf=None):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1/SR); H = np.ones_like(f)
    if lo: H *= 1/np.sqrt(1+(lo/np.maximum(f,1e-3))**4)
    if hi: H *= 1/np.sqrt(1+(f/hi)**4)
    if shelf:
        for fc, gain_db, q in shelf:  # колокол
            H *= 1 + (10**(gain_db/20)-1)*np.exp(-0.5*((np.log2(np.maximum(f,1)/fc))/q)**2)
    return np.fft.irfft(X*H, len(x))
def reverb(x, t60=1.2, mix=0.3, predelay=0.02):
    n = int(SR*t60*1.2); t = np.arange(n)/SR
    ir = rng.standard_normal(n) * np.exp(-6.9*t/t60)
    ir = fft_filter(ir, hi=3000)
    ir[:int(predelay*SR)] = 0; ir /= np.sqrt((ir**2).sum())
    y = np.convolve(x, ir)[:len(x)] if len(x) < 200000 else fft_conv(x, ir)
    return (1-mix)*x + mix*y*np.std(x)/max(np.std(y),1e-9)
def fft_conv(x, h):
    n = len(x)+len(h)-1; N = 1<<(n-1).bit_length()
    return np.fft.irfft(np.fft.rfft(x,N)*np.fft.rfft(h,N), N)[:len(x)]
def norm(x, rms_db=-14, peak=0.97):
    x = x - np.mean(x)
    r = np.sqrt(np.mean(x**2)); x = x * (10**(rms_db/20)/max(r,1e-9))
    x = np.tanh(x*1.2)/np.tanh(1.2)  # мягкий лимитер
    return x * min(1.0, peak/np.max(np.abs(x)))
def write(name, x):
    raw = f"{OUT}/{name}.f32"; (x.astype(np.float32)).tofile(raw)
    ogg = f"{OUT}/{name}.ogg"
    subprocess.run(["ffmpeg","-loglevel","error","-y","-f","f32le","-ar",str(SR),"-ac","1","-i",raw,"-c:a","libvorbis","-q:a","5",ogg],check=True)
    os.remove(raw)
def grains(sig, name, n=8, glen=0.6):
    L = int(glen*SR); w = np.hanning(L)
    for i in range(n):
        o = rng.integers(0, len(sig)-L)
        write(f"{name}_{i}", sig[o:o+L]*w)

# ---------------- ШАХЕД: двухтактный «мопед» ----------------
def airstrike_engine(dur=12.0, f_rot=100.0):
    n = int(dur*SR); t = np.arange(n)/SR
    # обороты гуляют: медленный дрейф + «плавание» под нагрузкой
    fr = f_rot*(1 + 0.018*np.sin(2*np.pi*0.55*t) + 0.008*np.sin(2*np.pi*1.7*t+1) + 0.004*np.cumsum(rng.standard_normal(n))/np.sqrt(n))
    ph = np.cumsum(fr)/SR
    out = np.zeros(n)
    # вспышки в цилиндрах: 4 на оборот, неравномерные (оппозитный двухтактник)
    pulse_len = int(0.012*SR); tp = np.arange(pulse_len)/SR
    cyl_gain = [1.0, 0.82, 0.93, 0.76]
    k = 0; rev = 0
    idx_prev = -1
    firing_phase = np.floor(ph*4).astype(int)
    edges = np.nonzero(np.diff(firing_phase))[0]
    for j, e in enumerate(edges):
        c = j % 4
        e2 = e + int(rng.normal(0, 0.00012)*SR)
        if e2 < 0 or e2+pulse_len >= n: continue
        f_ex = rng.uniform(520, 700)
        p = (np.sin(2*np.pi*f_ex*tp)*np.exp(-tp/0.0028) + 0.9*rng.standard_normal(pulse_len)*np.exp(-tp/0.0012))
        out[e2:e2+pulse_len] += p*cyl_gain[c]*rng.uniform(0.85, 1.1)
    # резонанс выхлопной трубы и глушителя
    out = fft_filter(out, lo=60, hi=4200, shelf=[(104,4,0.4),(208,6,0.5),(416,7,0.4),(1250,3,0.7)])
    # винт: две лопасти, «пила» на двойной частоте оборотов
    bp = 2*ph
    prop = (2*(bp - np.floor(bp+0.5)))  # пилообразный
    prop = fft_filter(prop, lo=80, hi=2500)*0.55
    # механика: стрекот и дребезг
    mech = fft_filter(rng.standard_normal(n), lo=1500, hi=4500)*0.06*(1+0.5*np.sin(2*np.pi*ph*4))
    x = out/np.std(out) + prop/np.std(prop)*0.6 + mech/np.std(mech)*0.25
    x = np.tanh(x*1.6)  # хрип и «рашпиль»
    return fft_filter(x, lo=55, hi=5000)
eng = airstrike_engine()
near = norm(eng, -12)
mid  = norm(reverb(fft_filter(eng, hi=3200), t60=0.9, mix=0.25), -13)
far  = norm(reverb(fft_filter(eng, lo=70, hi=900), t60=1.8, mix=0.45), -13)
grains(near, "drone_near"); grains(mid, "drone_mid"); grains(far, "drone_far")
write("preview_drone", norm(eng,-12)[:int(4*SR)])

# ---------------- КРЫЛАТАЯ РАКЕТА: турбореактивный «свист» ----------------
def turbojet(dur=10.0, tone=3100.0, whine_amt=1.0, roar_amt=1.0, hiss_amt=0.4):
    n = int(dur*SR); t = np.arange(n)/SR
    drift = 1 + 0.004*np.sin(2*np.pi*0.3*t) + 0.002*np.sin(2*np.pi*2.1*t)
    ph = np.cumsum(tone*drift)/SR
    # тон вентилятора/компрессора + обертоны + «пила» лопаток
    whine = (np.sin(2*np.pi*ph) + 0.45*np.sin(2*np.pi*2*ph+0.3) + 0.2*np.sin(2*np.pi*1.5*ph) + 0.15*np.sin(2*np.pi*0.5*ph))
    whine *= 1 + 0.15*fft_filter(rng.standard_normal(n), hi=20)/0.05
    # узкополосный шум вокруг тона — тот самый «свист»
    band = fft_filter(rng.standard_normal(n), shelf=[(tone,30,0.06)], lo=tone*0.8, hi=tone*1.25)
    # рёв струи: широкополосный, максимум 200–600 Гц
    roar = fft_filter(rng.standard_normal(n), lo=40, hi=5000, shelf=[(350,10,1.2),(120,6,0.8)])
    crackle = (rng.random(n) < 0.0015) * rng.standard_normal(n)
    crackle = fft_filter(crackle, lo=800, hi=6000)
    hiss = fft_filter(rng.standard_normal(n), lo=2500, hi=9000)
    x = (whine/np.std(whine))*0.55*whine_amt + (band/np.std(band))*0.6*whine_amt + (roar/np.std(roar))*roar_amt \
        + (crackle/max(np.std(crackle),1e-9))*0.15*roar_amt + (hiss/np.std(hiss))*hiss_amt
    return np.tanh(x*0.9)
front = turbojet(whine_amt=1.25, roar_amt=0.55, hiss_amt=0.5)    # спереди — свист
rear  = turbojet(whine_amt=0.35, roar_amt=1.2, hiss_amt=0.25)    # сзади — рёв
dive  = turbojet(tone=3400, whine_amt=1.6, roar_amt=0.6, hiss_amt=0.9)  # пике: пронзительный свист
for nm, s in [("mfront", front), ("mrear", rear), ("mdive", dive)]:
    grains(norm(s, -12), f"{nm}_near", n=6)
    grains(norm(reverb(fft_filter(s, hi=4200), t60=1.0, mix=0.25), -13), f"{nm}_mid", n=6)
    grains(norm(reverb(fft_filter(s, lo=60, hi=1500), t60=2.0, mix=0.45), -13), f"{nm}_far", n=6)
write("preview_missile_front", norm(front,-12)[:int(3*SR)])

# ---------------- взрыв: удар по груди и раскат ----------------
def blast_sub(dur=5.0):
    n = int(dur*SR); t = np.arange(n)/SR
    crack = rng.standard_normal(n)*np.exp(-t/0.015)
    thump = np.sin(2*np.pi*(55*t - 12*t*t))*np.exp(-t/0.35)*1.6
    rumble = fft_filter(rng.standard_normal(n), lo=20, hi=220)*np.exp(-t/1.4)
    x = fft_filter(crack, lo=30, hi=8000)*0.8 + thump + rumble/np.std(rumble)*0.7
    return norm(x, -9, 0.95)
def blast_far(dur=8.0):
    n = int(dur*SR); t = np.arange(n)/SR
    boom = fft_filter(rng.standard_normal(n), lo=25, hi=400)*np.exp(-t/0.5)
    x = boom.copy()
    for d, g in [(0.35,0.5),(0.8,0.35),(1.4,0.28),(2.3,0.2),(3.5,0.12)]:   # эхо от рельефа и домов
        k = int(d*SR); x[k:] += g*fft_filter(boom, hi=250)[:n-k]
    x = reverb(x, t60=3.0, mix=0.5)
    return norm(x*np.minimum(1, t/0.03+0.05), -11, 0.95)
write("blast_sub", blast_sub()); write("blast_far", blast_far())

# ---------------- сирена: далёкая, моно, с эхом города ----------------
SNA = sorted(glob.glob(os.path.join(MODS, "snassets-*.jar")))[-1]
subprocess.run(["bash","-c",f"unzip -p '{SNA}' assets/snassets/sounds/signal/siren.ogg > '{TMP}/siren_src.ogg' && ffmpeg -loglevel error -y -i '{TMP}/siren_src.ogg' -ac 1 -ar {SR} -f f32le '{TMP}/siren.f32'"],check=True)
sir = np.fromfile(os.path.join(TMP, "siren.f32"), dtype=np.float32).astype(float)
sir = reverb(fft_filter(sir, lo=150, hi=2800), t60=2.6, mix=0.5, predelay=0.05)
fade = np.minimum(1, np.arange(len(sir))/(0.8*SR)) * np.minimum(1, (len(sir)-np.arange(len(sir)))/(1.5*SR))
write("siren", norm(sir*fade, -14, 0.8))
print(sorted(os.listdir(OUT))[:5], len(os.listdir(OUT)))
