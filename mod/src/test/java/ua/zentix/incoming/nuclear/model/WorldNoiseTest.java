package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldNoiseTest {
    /** Поле в объёме — тоже около N(0, 1): разброс 3D-шума в пределах трети от плоского (для пятен гари хватает). */
    @Test
    void volumeRoughlyNormalised() {
        WorldNoise n = new WorldNoise(12345L, 1);
        double sum = 0, sq = 0;
        int count = 0;
        for (int x = -6000; x < 6000; x += 53) {
            for (int y = -64; y < 320; y += 29) {
                for (int z = -6000; z < 6000; z += 59) {
                    double v = n.at(x, y, z, 6);
                    sum += v;
                    sq += v * v;
                    count++;
                }
            }
        }
        double mean = sum / count, std = Math.sqrt(sq / count - mean * mean);
        assertEquals(0, mean, 0.1, "среднее");
        assertEquals(1, std, 0.35, "разброс");
    }

    /** Поле приведено к N(0, 1): среднее и разброс по большому участку, хвосты — как у нормальной величины. */
    @Test
    void normalised() {
        WorldNoise n = new WorldNoise(12345L, 1);
        double sum = 0, sq = 0;
        int count = 0, beyond1 = 0;
        for (int x = -20000; x < 20000; x += 37) {
            for (int z = -20000; z < 20000; z += 41) {
                double v = n.at(x, z, 0);
                sum += v;
                sq += v * v;
                if (Math.abs(v) > 1) beyond1++;
                count++;
            }
        }
        double mean = sum / count, std = Math.sqrt(sq / count - mean * mean);
        assertEquals(0, mean, 0.05, "среднее");
        assertEquals(1, std, 0.05, "разброс");
        // у N(0, 1) за пределами ±1 — 31.7 %
        assertEquals(0.317, beyond1 / (double) count, 0.06, "хвосты");
    }

    /** Соседние места почти одинаковы (пятна), далёкие — независимы; другое зерно и другой слой — другое поле. */
    @Test
    void patchesAndIndependence() {
        WorldNoise a = new WorldNoise(1L, 1), b = new WorldNoise(2L, 1);
        double near = 0, far = 0, seeds = 0, layers = 0;
        int count = 0;
        for (int i = 0; i < 4000; i++) {
            double x = i * 997.0 % 50000, z = i * 613.0 % 50000;
            double v = a.at(x, z, 0);
            near += v * a.at(x + 1, z, 0);
            far += v * a.at(x + 400, z + 300, 0);
            seeds += v * b.at(x, z, 0);
            layers += v * a.at(x, z, 1);
            count++;
        }
        assertTrue(near / count > 0.9, "соседи " + near / count);
        assertTrue(Math.abs(far / count) < 0.1, "далёкие " + far / count);
        assertTrue(Math.abs(seeds / count) < 0.1, "зёрна " + seeds / count);
        assertTrue(Math.abs(layers / count) < 0.1, "слои " + layers / count);
    }

    /** Масштаб модели: при 0.5 блока на метр те же пятна вдвое меньше в блоках. */
    @Test
    void scaleInBlocks() {
        WorldNoise m = new WorldNoise(5L, 1), half = new WorldNoise(5L, 0.5);
        assertEquals(m.at(100, 200, 0), half.at(50, 100, 0), 1e-9);
    }
}
