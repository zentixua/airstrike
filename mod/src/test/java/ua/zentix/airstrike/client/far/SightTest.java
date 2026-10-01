package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дымка по Кошмидеру и яркая точка, у которой свет сохраняется при любом угловом размере. */
class SightTest {
    private static final double PIXEL = Math.toRadians(70) / 1080;

    @Test
    void contrastFallsToEyeThresholdAtVisibilityRange() {
        assertEquals(Sight.THRESHOLD, Sight.transmittance(Sight.CLEAR, Sight.CLEAR), 1e-4);
        assertEquals(Sight.CLEAR, Sight.range(0, 0), 1e-6);
        assertEquals(Sight.RAIN, Sight.range(1, 0), 1e-6);
        assertEquals(Sight.THUNDER, Sight.range(1, 1), 1e-6);
        assertTrue(Sight.range(0.5f, 0) < Sight.CLEAR && Sight.range(0.5f, 0) > Sight.RAIN, "морось — между ясно и ливнем");
    }

    @Test
    void largeDimPointIsDrawnAsItIs() {
        double[] out = new double[2];
        Sight.point(5, 0.6, 1, 100, PIXEL, out);
        assertEquals(5, out[0], 1e-9);
        assertEquals(0.6, out[1], 1e-9);
    }

    @Test
    void tinyPointKeepsItsLight() {
        // шахед (2,5 м) за 6 км — треть пикселя: точка в полтора пикселя, бледнее во столько же раз, свет тот же
        double[] out = new double[2];
        double d = 6000, r = 1.25;
        Sight.point(r, 1, 1, d, PIXEL, out);
        assertEquals(0.75 * PIXEL * d, out[0], 1e-9);
        assertEquals((r / d) * (r / d), out[1] * (out[0] / d) * (out[0] / d), 1e-15, "свет точки сохраняется");
        assertTrue(out[1] < 0.1);
    }

    @Test
    void brightPointSpreadsIntoGlareInsteadOfExceedingWhite() {
        double[] out = new double[2];
        Sight.point(5, 40, 0.5, 3000, PIXEL, out);
        assertEquals(1, out[1], 1e-12);
        double r = 5 / 3000.0;
        assertEquals(40 * 0.5 * r * r, (out[0] / 3000) * (out[0] / 3000), 1e-12, "свет блика — свет источника");
    }
}
