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
