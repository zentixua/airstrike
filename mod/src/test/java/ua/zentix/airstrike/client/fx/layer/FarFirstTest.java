package ua.zentix.airstrike.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarFirstTest {
    /** От дальних к ближним, каждый номер один раз, равные — в порядке записи (кадр не мигает от перестановок). */
    @Test
    void farFirstAndStable() {
        Random r = new Random(1945);
        FxQuads.FarFirst sort = new FxQuads.FarFirst();
        for (int n : new int[]{0, 1, 2, 7, 1000, 40000}) {
            float[] keys = new float[n];
            for (int i = 0; i < n; i++) {
                // и рядом с камерой, и за десятки километров; часть — одинаковые
                keys[i] = r.nextInt(5) == 0 ? 100 : (float) (Math.pow(10, r.nextDouble() * 5) - 1);
            }
            int[] order = sort.sort(keys, n);
            boolean[] seen = new boolean[n];
            for (int i = 0; i < n; i++) {
                assertTrue(!seen[order[i]], "повтор " + order[i]);
                seen[order[i]] = true;
                if (i == 0) continue;
                float a = keys[order[i - 1]], b = keys[order[i]];
                assertTrue(a >= b, n + ": " + a + " перед " + b);
                if (a == b) assertTrue(order[i - 1] < order[i], "равные не по порядку записи");
            }
        }
    }

    @Test
    void zeroAndTinyDistances() {
        float[] keys = {0, 1e-6f, 0, 3, Float.MIN_VALUE};
        int[] order = new FxQuads.FarFirst().sort(keys, keys.length);
        assertEquals(3, order[0]);
        assertEquals(1, order[1]);
        assertEquals(4, order[2]);
        assertEquals(0, order[3]);
        assertEquals(2, order[4]);
    }
}
