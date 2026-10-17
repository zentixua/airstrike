package ua.zentix.incoming.client.fx.layer;

import java.util.Random;

/**
 * Плитка трёхмерного шума без швов для объёма пелены ({@link PallPass}, шейдер {@code pall.glsl}): канал R — клубы
 * (Perlin-Worley, как облака Horizon Zero Dawn у Шнайдера, 2015: fBm Перлина, «выеденный» fBm Ворлей — круглые
 * клубы с промежутками, а не размытые пятна), канал G — мелкие края (fBm Ворлей втрое чаще), канал B — клубы гриба
 * ({@code mushLobes} в {@code fx_pall}): тот fBm Ворлей, что выедает Перлина в R, — обращённый F1, горб у каждой точки
 * ячейки. Край, сдвинутый горбом, — круглый купол (край на высоте h над точкой ячейки там, где h = a·(c − √(ρ² + h²)),
 * — параболоид), крупные купола — по 16 вокселей, на них мельче. Перлин в R таких куполов не даёт: его пятна
 * вытянуты, а градиент у октав одинаков, и тень по клубам — рябь мелких. Решётки Перлина и ячейки Ворлей повторяются
 * с периодом плитки — текстура с повтором не даёт швов. Свой код, а не FastNoiseLite: тот не умеет периодический шум.
 * <p>
 * Чистая функция зерна: плитка одна и та же у всех, считается один раз в фоне ({@link PallPass}).
 */
public final class VolumeNoise {
    /** Сторона плитки, вокселей. */
    public static final int SIZE = 64;
    /** Каналов на воксель (R, G, B). */
    public static final int CHANNELS = 3;
    /** Ячеек Перлина и Ворлей на плитку у первой октавы. */
    private static final int BASE_CELLS = 4;
    private VolumeNoise() {}

    /** Плитка {@code size}³: по {@link #CHANNELS} байта на воксель (R, G, B), x меняется быстрее всего, потом y, потом z. */
    public static byte[] generate(int size, long seed) {
        Random random = new Random(seed);
        int[] perm = new int[512];
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        Cells[] worley = new Cells[5];
        for (int o = 0; o < worley.length; o++) worley[o] = new Cells(BASE_CELLS << o, random.nextLong());
        byte[] out = new byte[size * size * size * CHANNELS];
        double[] r = new double[size * size * size], g = new double[r.length], b = new double[r.length];
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double u = (double) x / size, v = (double) y / size, w = (double) z / size;
                    // fBm Перлина: 4, 8, 16 ячеек на плитку; −1..1 → 0..1
                    double perlin = 0, amp = 1, norm = 0;
                    for (int o = 0; o < 3; o++) {
                        int cells = BASE_CELLS << o;
                        perlin += amp * perlin(u * cells, v * cells, w * cells, cells, perm);
                        norm += amp;
                        amp *= 0.5;
                    }
                    perlin = clamp01(0.5 + 0.5 * perlin / norm * 1.6);
                    // fBm Ворлей (обращённый F1): 4, 8, 16 ячеек — «выедает» Перлина
                    double wfbm = 0.625 * worley[0].f1(u, v, w) + 0.25 * worley[1].f1(u, v, w) + 0.125 * worley[2].f1(u, v, w);
                    double pw = clamp01(remap(perlin, wfbm - 1, 1));
                    // мелкие края: Ворлей 16, 32, 64 ячейки
                    double detail = 0.625 * worley[2].f1(u, v, w) + 0.25 * worley[3].f1(u, v, w) + 0.125 * worley[4].f1(u, v, w);
                    int i = (z * size + y) * size + x;
                    r[i] = pw;
                    g[i] = detail;
                    b[i] = wfbm;
                }
            }
        }
        // каналы — по рангу, равномерно 0..1: форма клубов ({@link #shape}) не зависит от того, как легли октавы
        equalize(r, out, 0);
        equalize(g, out, 1);
        equalize(b, out, 2);
        return out;
    }

    /** Значения → их ранг 0..255 (равномерно; порядок, а значит и узор клубов, тот же), в байты канала {@code channel}. */
    private static void equalize(double[] values, byte[] out, int channel) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
        for (int k = 0; k < order.length; k++) out[CHANNELS * order[k] + channel] = (byte) ((long) k * 256 / order.length);
    }

    /**
     * Форма клубов — множитель густоты по шуму в точке: {@code r}, {@code g} — каналы плитки 0..1 (клубы и мелкие края
     * в другой точке, втрое чаще), {@code fill} — насколько пелена здесь сплошная (0 — край, редкая; 1 — у центра,
     * густая): край — клочьями (55 % пусто), середина — сплошная, густота плавно от просветов (15 %) до вдвое гуще
     * средней. Не ступенькой «есть — нет»: в густой пелене тень соседнего клуба ({@code fx_pall}) и свет по ней —
     * только из разной густоты, а у формы, где почти везде одно значение, всё ровное. Мера {@code r − 0,3·(1 − g)}
     * равномерна на 0..0,7 (каналы равномерны и независимы), окно сглаживания — в ней (у {@code fill} больше 0,89 —
     * на 0,05 ниже, среднее там меньше на 10⁻⁴), поэтому среднее — 0,3 + 0,225·fill: усиление его обращает, и в среднем
     * по шуму густота — густота поля ({@code PallField}; сверено тестом). Точная копия {@code pallShape} из
     * {@code pall.glsl}.
     */
    public static double shape(double r, double g, double fill) {
        return smoothstep(0.4 - 0.45 * fill, 0.7, r - 0.3 * (1 - g)) / (0.3 + 0.225 * fill);
    }

    /**
     * Мелкие клубы — множитель густоты внутри крупных ({@code pallDetail} в {@code pall.glsl} — то же): {@code r} — канал
     * клубов плитки, взятой вчетверо мельче. От 0,3 до 1,7, в среднем по равномерному каналу — ровно 1 (сглаженный порог
     * в середине 0..1): каскад «крупные × мелкие» не меняет средней густоты поля, а значит и видимости в пелене.
     */
    public static double detail(double r) {
        return 0.3 + 1.4 * smoothstep(0.2, 0.8, r);
    }

    /** Градиентный шум Перлина с периодом {@code period} ячеек, −1..1. */
    static double perlin(double x, double y, double z, int period, int[] perm) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        double u = fade(xf), v = fade(yf), w = fade(zf);
        double sum = 0;
        for (int c = 0; c < 8; c++) {
            int dx = c & 1, dy = c >> 1 & 1, dz = c >> 2 & 1;
            int h = perm[perm[perm[Math.floorMod(xi + dx, period)] + Math.floorMod(yi + dy, period)] + Math.floorMod(zi + dz, period)];
            double g = grad(h, xf - dx, yf - dy, zf - dz);
            sum += g * (dx == 1 ? u : 1 - u) * (dy == 1 ? v : 1 - v) * (dz == 1 ? w : 1 - w);
        }
        return sum;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** Один из 12 градиентов к рёбрам куба (Перлин, 2002). */
    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** Ячейки Ворлей: по точке в каждой, с периодом плитки. */
    private static final class Cells {
        final int n;
        final double[] px, py, pz;

        Cells(int n, long seed) {
            this.n = n;
            Random r = new Random(seed);
            px = new double[n * n * n];
            py = new double[n * n * n];
            pz = new double[n * n * n];
            for (int i = 0; i < px.length; i++) {
                px[i] = r.nextDouble();
                py[i] = r.nextDouble();
                pz[i] = r.nextDouble();
            }
        }

        /** Обращённое расстояние до ближайшей точки, 0..1 (1 — в точке, 0 — на ячейку и дальше), u, v, w — доли плитки. */
        double f1(double u, double v, double w) {
            double x = u * n, y = v * n, z = w * n;
            int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
            double best = 9;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int cx = xi + dx, cy = yi + dy, cz = zi + dz;
                        int i = (Math.floorMod(cz, n) * n + Math.floorMod(cy, n)) * n + Math.floorMod(cx, n);
                        double ex = cx + px[i] - x, ey = cy + py[i] - y, ez = cz + pz[i] - z;
                        best = Math.min(best, ex * ex + ey * ey + ez * ez);
                    }
                }
            }
            return clamp01(1 - Math.sqrt(best));
        }
    }

    private static double remap(double x, double lo, double hi) {
        return (x - lo) / (hi - lo);
    }

    private static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    static double smoothstep(double e0, double e1, double x) {
        double t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }
}
