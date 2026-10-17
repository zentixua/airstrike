package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Эхо от рельефа — в звук (без Minecraft и OpenAL, проверяется юнит-тестами): отражения {@link TerrainEcho} сводятся
 * в {@link #SECTORS} дорожек по сторонам света вокруг уха. Каждое отражение — копия начала записи прямого удара
 * («зерно»), со своей задержкой и громкостью; воздух на лишнем пути съедает верха (одна из {@link #CUTOFF} копий
 * зерна, пропущенных через фильтр нижних частот). Отражение между двумя сторонами делится на обе с равной мощностью
 * (cos/sin), так что оно звучит со своего направления, а не с ближней стороны.
 * <p>
 * Дорожка начинается со своего первого отражения (тишину до него не хранит) и не длиннее {@link #MAX_SECONDS}.
 * Отражения в одном окне {@link #BIN_SECONDS} складываются по мощности (некогерентно). Если сумма вышла за
 * {@link #PEAK}, дорожка ужата до него, а громкость при игре — поднята на столько же ({@link Track#gain}).
 */
public final class EchoMix {
    /** Частота дорожек: эхо далёкого удара — низы, верха съел воздух. */
    public static final int RATE = 11025;
    /** Сторон вокруг уха. */
    public static final int SECTORS = 8;
    /** Окно, в котором отражения складываются в одно, с. */
    static final double BIN_SECONDS = 0.01;
    static final double MAX_SECONDS = 30;
    /** Наибольший отсчёт дорожки (без перегруза при записи в 16 бит). */
    static final double PEAK = 0.98;
    /** Отражений на дорожку не больше (сильнейшие). */
    static final int MAX_TAPS = 600;
    /**
     * Срез верхов зерна по лишнему пути: до {@code PATH[i]} блоков — фильтр {@code CUTOFF[i]} Гц (0 — без фильтра).
     * Воздух на 2 кГц — ~9 дБ/км, на 4 кГц — ~23 дБ/км (ISO 9613-1).
     */
    static final double[] PATH = {250, 1000, 3000, Double.POSITIVE_INFINITY};
    static final double[] CUTOFF = {0, 2000, 900, 450};

    private EchoMix() {}

    /**
     * Дорожка одной стороны.
     *
     * @param azimuth откуда звучит, рад ({@code atan2(dz, dx)}, как у {@link TerrainEcho.Reflection#azimuth})
     * @param start   через сколько тиков после прямого удара начинается
     * @param pcm     отсчёты, моно 16 бит, {@link #RATE}
     * @param gain    во сколько раз громче играть (дорожка ужата до {@link #PEAK})
     */
    public record Track(double azimuth, double start, short[] pcm, float gain) {
        public double seconds() {
            return pcm.length / (double) RATE;
        }
    }

    /**
     * Всё эхо подрыва у слушателя.
     *
     * @param t60 время спада хвоста (с) — для реверберации поверх дорожек: по разбросу задержек, с весом мощности
     */
    public record Mix(List<Track> tracks, double t60, int taps) {
        public static final Mix NONE = new Mix(List.of(), 0, 0);
    }

    /** Отражения → дорожки. {@code grain} — моно, {@link #RATE}, громкость как у прямого удара. */
    public static Mix render(float[] grain, List<TerrainEcho.Reflection> reflections) {
        if (grain.length == 0 || reflections.isEmpty()) return Mix.NONE;
        float[][] filtered = new float[CUTOFF.length][];
        for (int i = 0; i < CUTOFF.length; i++) filtered[i] = CUTOFF[i] <= 0 ? grain : lowpass(grain, CUTOFF[i]);
        // окна по сторонам: [сторона][копия зерна] → (окно → мощность)
        int bins = (int) Math.ceil(MAX_SECONDS / BIN_SECONDS);
        double[][][] energy = new double[SECTORS][CUTOFF.length][];
        double width = 2 * Math.PI / SECTORS;
        double sumE = 0, sumT = 0, sumT2 = 0;
        int taps = 0;
        for (TerrainEcho.Reflection r : reflections) {
            double seconds = r.delay() / 20;
            int bin = (int) Math.round(seconds / BIN_SECONDS);
            if (bin >= bins || !(r.energy() > 0)) continue;
            int variant = variant(r.extraPath());
            double a = wrap(r.azimuth()) / width;
            int k0 = (int) Math.floor(a) % SECTORS, k1 = (k0 + 1) % SECTORS;
            double f = a - Math.floor(a);
            double c = Math.cos(f * Math.PI / 2), s = Math.sin(f * Math.PI / 2);
            add(energy, k0, variant, bins, bin, r.energy() * c * c);
            add(energy, k1, variant, bins, bin, r.energy() * s * s);
            sumE += r.energy();
            sumT += r.energy() * seconds;
            sumT2 += r.energy() * seconds * seconds;
            taps++;
        }
        if (taps == 0) return Mix.NONE;
        List<Track> tracks = new ArrayList<>();
        for (int k = 0; k < SECTORS; k++) {
            Track t = track(k * width, energy[k], filtered, bins);
            if (t != null) tracks.add(t);
        }
        double mean = sumT / sumE, spread = Math.sqrt(Math.max(0, sumT2 / sumE - mean * mean));
        // хвост гаснет за время, за которое приходит основная масса отражений
        double t60 = Math.max(1.5, Math.min(8, 1 + mean + 2 * spread));
        return new Mix(List.copyOf(tracks), t60, taps);
    }

    private static void add(double[][][] energy, int sector, int variant, int bins, int bin, double e) {
        if (e <= 0) return;
        if (energy[sector][variant] == null) energy[sector][variant] = new double[bins];
        energy[sector][variant][bin] += e;
    }

    /** Одна сторона: сильнейшие окна, сложенные копиями зерна. */
    private static Track track(double azimuth, double[][] energy, float[][] filtered, int bins) {
        // окна стороны: (копия, окно, амплитуда), не больше MAX_TAPS сильнейших
        List<double[]> taps = new ArrayList<>();
        for (int v = 0; v < energy.length; v++) {
            if (energy[v] == null) continue;
            for (int b = 0; b < bins; b++) if (energy[v][b] > 0) taps.add(new double[]{v, b, Math.sqrt(energy[v][b])});
        }
        if (taps.isEmpty()) return null;
        if (taps.size() > MAX_TAPS) {
            taps.sort((x, y) -> Double.compare(y[2], x[2]));
            taps = new ArrayList<>(taps.subList(0, MAX_TAPS));
        }
        int first = Integer.MAX_VALUE, last = 0;
        for (double[] t : taps) {
            first = Math.min(first, (int) t[1]);
            last = Math.max(last, (int) t[1]);
        }
        double binSamples = BIN_SECONDS * RATE;
        int grain = filtered[0].length;
        int length = (int) Math.min(MAX_SECONDS * RATE, Math.round((last - first) * binSamples) + grain);
        float[] out = new float[length];
        for (double[] t : taps) {
            float[] g = filtered[(int) t[0]];
            int at = (int) Math.round((t[1] - first) * binSamples);
            float amp = (float) t[2];
            int n = Math.min(g.length, length - at);
            for (int i = 0; i < n; i++) out[at + i] += amp * g[i];
        }
        float peak = 0;
        for (float x : out) peak = Math.max(peak, Math.abs(x));
        if (peak <= 0) return null;
        float scale = peak > PEAK ? (float) (PEAK / peak) : 1;
        short[] pcm = new short[length];
        for (int i = 0; i < length; i++) pcm[i] = (short) Math.round(Math.max(-1, Math.min(1, out[i] * scale)) * 32767);
        return new Track(azimuth, first * BIN_SECONDS * 20, pcm, 1 / scale);
    }

    /** Какая копия зерна у отражения с этим лишним путём (блоки). */
    static int variant(double extraPath) {
        for (int i = 0; i < PATH.length; i++) if (extraPath < PATH[i]) return i;
        return PATH.length - 1;
    }

    /** Угол в [0, 2π). */
    static double wrap(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    /** Фильтр нижних частот (два однополюсных подряд — −12 дБ на октаву). */
    static float[] lowpass(float[] x, double cutoff) {
        double a = 1 - Math.exp(-2 * Math.PI * cutoff / RATE);
        float[] y = Arrays.copyOf(x, x.length);
        for (int pass = 0; pass < 2; pass++) {
            double s = 0;
            for (int i = 0; i < y.length; i++) {
                s += a * (y[i] - s);
                y[i] = (float) s;
            }
        }
        return y;
    }

    /**
     * Зерно с высотой тона {@code pitch}, как её играет движок: секунда звука берёт {@code pitch} секунд записи (ниже —
     * длиннее), между отсчётами — по прямой.
     */
    public static float[] pitch(float[] grain, double pitch) {
        if (Math.abs(pitch - 1) < 1e-6 || grain.length == 0) return grain;
        int n = (int) Math.floor((grain.length - 1) / pitch) + 1;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double at = i * pitch;
            int a = Math.min(grain.length - 1, (int) at);
            int b = Math.min(grain.length - 1, a + 1);
            double w = at - a;
            out[i] = (float) (grain[a] * (1 - w) + grain[b] * w);
        }
        return out;
    }

    /**
     * Начало записи прямого удара → зерно: отсчёты {@code samples} (−1..1, каналы вперемежку) частоты {@code rate}
     * в моно {@link #RATE} (среднее по окну — без наложения частот), первые {@code seconds} секунд с затуханием на
     * последней трети.
     */
    public static float[] grain(float[] samples, int channels, int rate, double seconds) {
        int frames = samples.length / Math.max(1, channels);
        double ratio = rate / (double) RATE;
        int n = (int) Math.min(frames / ratio, seconds * RATE);
        float[] out = new float[Math.max(0, n)];
        for (int i = 0; i < n; i++) {
            int a = (int) Math.floor(i * ratio), b = Math.max(a + 1, (int) Math.floor((i + 1) * ratio));
            double s = 0;
            int count = 0;
            for (int j = a; j < b && j < frames; j++) {
                for (int c = 0; c < channels; c++) s += samples[j * channels + c];
                count += channels;
            }
            out[i] = count == 0 ? 0 : (float) (s / count);
        }
        int fade = n / 3;
        for (int i = 0; i < fade; i++) {
            double w = Math.cos(Math.PI / 2 * (i + 1) / fade);
            out[n - fade + i] *= (float) (w * w);
        }
        return out;
    }
}
