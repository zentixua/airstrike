package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragilityTest {
    @Test
    void cdfAndInverseAgree() {
        assertEquals(0.5, Fragility.cdf(0), 1e-7);
        assertEquals(0.841345, Fragility.cdf(1), 1e-6);
        assertEquals(0.022750, Fragility.cdf(-2), 1e-6);
        for (double u = 0.001; u < 1; u += 0.01) assertEquals(u, Fragility.cdf(Fragility.inverse(u)), 1e-6, "u = " + u);
        assertEquals(-3.090232, Fragility.inverse(0.001), 1e-4);
    }

    @Test
    void medianIsHalf() {
        assertEquals(0.5, Fragility.probability(8, 8, 0.3), 1e-7);
        assertTrue(Fragility.probability(4, 8, 0.3) < 0.02);
        assertTrue(Fragility.probability(16, 8, 0.3) > 0.98);
        assertEquals(0, Fragility.probability(0, 8, 0.3));
    }

    /** Порог со случайностью места z ~ N(0, 1) даёт ту же долю, что и кривая. */
    @Test
    void hitMatchesProbability() {
        Random r = new Random(1);
        int n = 200_000, hits = 0;
        for (int i = 0; i < n; i++) if (Fragility.hit(10, 15, 0.35, r.nextGaussian())) hits++;
        assertEquals(Fragility.probability(10, 15, 0.35), hits / (double) n, 0.005);
        assertFalse(Fragility.hit(0, 15, 0.35, 10));
    }

    /** Хвост усечён: далеко за медианой последствия нет при любой случайности места. */
    @Test
    void tailTruncated() {
        assertFalse(Fragility.hit(3, 15, 0.35, 6));
        assertTrue(Fragility.hit(15 * Math.exp(-0.35 * Fragility.TAIL) * 1.001, 15, 0.35, 6));
    }

    /** «Возможно» — ровно «случится хоть при какой-то случайности места». */
    @Test
    void possibleBoundsHit() {
        for (double x = 0.5; x < 40; x *= 1.07) {
            boolean any = false;
            for (double z = -4; z <= 4; z += 0.05) any |= Fragility.hit(x, 15, 0.35, z);
            assertEquals(any, Fragility.possible(x, 15, 0.35), "x = " + x);
        }
        assertFalse(Fragility.possible(0, 15, 0.35));
    }
}
