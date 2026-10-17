package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шум пелены: плитка без швов, каналы равномерны, форма клубов в среднем — 1; синий шум без сгустков. */
class PallNoiseTest {
    private static final int N = 32;
    private static final byte[] VOLUME = VolumeNoise.generate(N, 0x9A11L);

    private static double at(int x, int y, int z, int channel) {
        return (VOLUME[2 * ((Math.floorMod(z, N) * N + Math.floorMod(y, N)) * N + Math.floorMod(x, N)) + channel] & 255) / 255.0;
    }

    /** Через край плитки соседние вокселы различаются не больше, чем внутри: повтор текстуры шва не даёт. */
    @Test
    void tileHasNoSeams() {
        for (int c = 0; c < 2; c++) {
            double inside = 0, seam = 0;
            for (int z = 0; z < N; z++) {
                for (int y = 0; y < N; y++) {
                    inside += Math.abs(at(N / 2, y, z, c) - at(N / 2 - 1, y, z, c));
                    seam += Math.abs(at(0, y, z, c) - at(N - 1, y, z, c));
                }
            }
            assertTrue(seam < 1.6 * inside + 1e-9, "канал " + c + ": шов " + seam + " против " + inside);
        }
    }

    /** Каналы — по рангу: равномерны, среднее — половина. */
    @Test
    void channelsAreUniform() {
        for (int c = 0; c < 2; c++) {
            int[] bins = new int[8];
            double sum = 0;
            for (int i = 0; i < N * N * N; i++) {
                double v = (VOLUME[2 * i + c] & 255) / 256.0;
                sum += v;
                bins[(int) (v * 8)]++;
            }
            assertEquals(0.5, sum / (N * N * N), 0.01);
            for (int b : bins) assertEquals(N * N * N / 8.0, b, N * N * N / 8.0 * 0.02);
        }
    }

    /**
     * Форма клубов в среднем по плитке — 1 при любой сплошности (густота пелены в среднем — густота поля), край — клочьями
     * с просветами, середина — почти сплошная.
     */
    @Test
    void shapeKeepsMeanDensity() {
        for (double fill = 0; fill <= 1; fill += 0.25) {
            double sum = 0, empty = 0;
            int n = N * N * N;
            for (int i = 0; i < n; i++) {
                int j = (int) (((long) i * 7919 + 12345) % n);
                double s = VolumeNoise.shape((VOLUME[2 * i] & 255) / 255.0, (VOLUME[2 * j + 1] & 255) / 255.0, fill);
                sum += s;
                if (s < 0.05) empty++;
            }
            assertEquals(1, sum / n, 0.04, "сплошность " + fill);
            if (fill == 0) assertTrue(empty / n > 0.45, "край без просветов");
            if (fill == 1) assertTrue(empty / n < 0.2, "середина в дырах");
        }
    }

    /**
     * Мелкие клубы ({@link VolumeNoise#detail}) в среднем — 1, и каскад «крупные × мелкие» (мелкие — плитка вчетверо
     * мельче, как {@code clumpsAt} в {@code fx_pall.fsh}) — тоже: густота, а значит и видимость, — поля пелены.
     */
    @Test
    void detailKeepsMeanDensity() {
        int n = N * N * N;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += VolumeNoise.detail((VOLUME[2 * i] & 255) / 255.0);
        assertEquals(1, sum / n, 0.01);
        double cascade = 0;
        for (int i = 0; i < n; i++) cascade += density(i % N + 0.5, i / N % N + 0.5, i / (N * N) + 0.5, 1, true);
        assertEquals(1, cascade / n, 0.04);
    }

    /**
     * Видимость в самой густой пыли (по таблице — 40 блоков): прямые лучи через клубы пелены (плитка 256 блоков у 15 кт,
     * сплошная пыль) пропускают в среднем больше, чем ровная пыль той же средней густоты, — сквозь просветы видно дальше,
     * до 70–100 блоков. Мелкие клубы внутри крупных её почти не меняют (меньше чем на десятую): они мельче луча
     * и по нему усредняются.
     */
    @Test
    void denseDustKeepsItsVisibility() {
        double coarse = visibility(false), cascade = visibility(true);
        assertTrue(coarse > 60 && coarse < 100, "крупные клубы: видимость " + coarse);
        assertEquals(coarse, cascade, 0.1 * coarse, "с мелкими клубами");
    }

    /** Видимость (блоки) по среднему пропусканию лучей по 40 блоков в самой густой пыли: 3,912 · 40 / −ln⟨T⟩. */
    private static double visibility(boolean fine) {
        double sigma = 3.912 / 40, tile = 256, length = 40;
        java.util.Random random = new java.util.Random(7);
        double sum = 0;
        int rays = 3000, steps = 60;
        for (int r = 0; r < rays; r++) {
            double x = random.nextDouble() * N, y = random.nextDouble() * N, z = random.nextDouble() * N;
            double a = random.nextDouble() * 2 * Math.PI, b = Math.acos(2 * random.nextDouble() - 1);
            double dx = Math.sin(b) * Math.cos(a), dy = Math.cos(b), dz = Math.sin(b) * Math.sin(a);
            double tau = 0, ds = length / steps, voxel = N / tile;
            for (int k = 0; k < steps; k++) {
                double t = (k + 0.5) * ds * voxel;
                tau += sigma * density(x + dx * t, y + dy * t, z + dz * t, 1, fine) * ds;
            }
            sum += Math.exp(-tau);
        }
        return length * 3.912 / -Math.log(sum / rays);
    }

    /** Густота каскада в точке плитки (в вокселях): крупные клубы с краями втрое чаще и мелкие — вчетверо. */
    private static double density(double x, double y, double z, double fill, boolean fine) {
        double shape = VolumeNoise.shape(trilinear(x, y, z, 0), trilinear(3.1 * x + 11.8, 3.1 * y + 3.5, 3.1 * z + 22.7, 1), fill);
        return fine ? shape * VolumeNoise.detail(trilinear(4 * x + 17, 4 * y + 9.3, 4 * z + 5.4, 0)) : shape;
    }

    private static double trilinear(double x, double y, double z, int c) {
        x -= 0.5;
        y -= 0.5;
        z -= 0.5;
        int i = (int) Math.floor(x), j = (int) Math.floor(y), k = (int) Math.floor(z);
        double fx = x - i, fy = y - j, fz = z - k, v = 0;
        for (int d = 0; d < 8; d++) {
            int a = d & 1, b = d >> 1 & 1, e = d >> 2 & 1;
            v += at(i + a, j + b, k + e, c) * (a == 1 ? fx : 1 - fx) * (b == 1 ? fy : 1 - fy) * (e == 1 ? fz : 1 - fz);
        }
        return v;
    }

    /** Синий шум: каждое значение одинаково часто, средние по блокам 4×4 — ровнее, чем у белого шума, втрое. */
    @Test
    void blueNoiseIsEvenAndUnclumped() {
        int n = BlueNoise.SIZE;
        byte[] b = BlueNoise.generate(n, 0xB1E5L);
        int[] count = new int[256];
        for (byte v : b) count[v & 255]++;
        for (int c : count) assertEquals(n * n / 256, c);
        double var = 0;
        int blocks = 0;
        for (int by = 0; by < n / 4; by++) {
            for (int bx = 0; bx < n / 4; bx++) {
                double m = 0;
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) m += b[(by * 4 + y) * n + bx * 4 + x] & 255;
                m /= 16;
                var += (m - 127.5) * (m - 127.5);
                blocks++;
            }
        }
        double white = 255 / Math.sqrt(12) / 4;
        assertTrue(Math.sqrt(var / blocks) < white / 2, "разброс " + Math.sqrt(var / blocks) + " против белого " + white);
    }
}
