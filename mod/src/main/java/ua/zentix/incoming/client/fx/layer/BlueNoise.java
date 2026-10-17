package ua.zentix.incoming.client.fx.layer;

import java.util.Random;

/**
 * Синий шум для сдвига шагов луча пелены ({@link PallPass}): у соседних пикселей сдвиги разные и ровно распределены,
 * без сгустков, как у белого шума, — после накопления по кадрам зерно уходит быстрее, а то, что осталось, — мелкое и
 * без пятен. Пустота-и-скопление Улични (1993) на торе: у каждого пикселя — свой ранг 0..N−1 (равномерно), бóльшие
 * ранги ставятся в самые большие пустоты.
 */
public final class BlueNoise {
    /** Сторона плитки. */
    public static final int SIZE = 64;
    /** Ширина гауссова «отталкивания», пикселей (Улични — 1,5). */
    private static final double SIGMA = 1.5;

    private BlueNoise() {}

    /** Плитка {@code size}×{@code size}: байт на пиксель, ранг × 256 / size² (каждое значение — одинаково часто). */
    public static byte[] generate(int size, long seed) {
        int n = size * size;
        double[] kernel = new double[n];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int dx = Math.min(x, size - x), dy = Math.min(y, size - y);
                kernel[y * size + x] = Math.exp(-(dx * dx + dy * dy) / (2 * SIGMA * SIGMA));
            }
        }
        boolean[] on = new boolean[n];
        double[] energy = new double[n];
        Random random = new Random(seed);
        int ones = 0;
        // начальный узор: десятая часть пикселей — случайно, потом скопления переносятся в пустоты, пока узор не устоится
        while (ones < n / 10) {
            int p = random.nextInt(n);
            if (on[p]) continue;
            on[p] = true;
            ones += 1;
            splat(energy, kernel, size, p, 1);
        }
        for (int guard = 0; guard < 4 * n; guard++) {
            int cluster = extreme(energy, on, true, true);
            on[cluster] = false;
            splat(energy, kernel, size, cluster, -1);
            int voidAt = extreme(energy, on, false, false);
            on[voidAt] = true;
            splat(energy, kernel, size, voidAt, 1);
            if (voidAt == cluster) break;
        }
        int[] rank = new int[n];
        // ранги начального узора: снимать самые тесные скопления, ранг — сколько осталось
        boolean[] work = on.clone();
        double[] e = energy.clone();
        for (int r = ones - 1; r >= 0; r--) {
            int cluster = extreme(e, work, true, true);
            work[cluster] = false;
            splat(e, kernel, size, cluster, -1);
            rank[cluster] = r;
        }
        // дальше — в самые большие пустоты (самая тесная пустота среди нулей — то же, что наибольшая пустота среди единиц)
        for (int r = ones; r < n; r++) {
            int voidAt = extreme(energy, on, false, false);
            on[voidAt] = true;
            splat(energy, kernel, size, voidAt, 1);
            rank[voidAt] = r;
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((long) rank[i] * 256 / n);
        return out;
    }

    /** Прибавить ядро с центром в p (со знаком) ко всей карте энергии, на торе. */
    private static void splat(double[] energy, double[] kernel, int size, int p, int sign) {
        int px = p % size, py = p / size;
        for (int y = 0; y < size; y++) {
            int ky = Math.floorMod(y - py, size) * size;
            for (int x = 0; x < size; x++) energy[y * size + x] += sign * kernel[ky + Math.floorMod(x - px, size)];
        }
    }

    /** Пиксель с наибольшей (max) или наименьшей энергией среди тех, у кого {@code on[i] == state}. */
    private static int extreme(double[] energy, boolean[] on, boolean state, boolean max) {
        int best = -1;
        for (int i = 0; i < energy.length; i++) {
            if (on[i] != state) continue;
            if (best < 0 || (max ? energy[i] > energy[best] : energy[i] < energy[best])) best = i;
        }
        return best;
    }
}
