package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дымка по Кошмидеру, яркая точка, у которой свет сохраняется при любом угловом размере, блик и вуаль. */
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

    /** Свет неба FarView.ambient: полдень и полночь (getSkyDarken 0,2). */
    private static final double DAY = 1, NIGHT = 0.17;

    @Test
    void daylightFireballHasNoVeilButNightOneLightsDegrees() {
        // шар крылатой ракеты (11 блоков, яркость 8 против белого) за 1,5 км днём и за 2 и 8 км ночью
        double[] out = new double[5];
        double r = 11, b = 8;
        double t = Sight.transmittance(1500, Sight.CLEAR);
        Sight.light(r, Sight.adapted(b, DAY) * t, 1500, PIXEL, out);
        assertEquals(0, out[4], "днём вуали нет");
        assertTrue(out[2] > 1, "днём шар ярче неба: " + out[2]);
        assertTrue(out[3] / out[0] < 2.5, "блик днём — не шире двух с половиной ядер: " + out[3] / out[0]);
        for (double d : new double[]{2000, 8000}) {
            Sight.light(5, Sight.adapted(b, NIGHT) * Sight.transmittance(d, Sight.CLEAR), d, PIXEL, out);
            double deg = Math.toDegrees(out[4] / d);
            if (d == 2000) assertTrue(deg > 6 && deg <= Math.toDegrees(Sight.VEIL_MAX) + 1e-9, "шахед ночью за 2 км — вуаль " + deg + "°");
            else assertTrue(deg > 1 && deg < 3, "шахед ночью за 8 км — вуаль " + deg + "°");
        }
    }

    @Test
    void lightKeepsItsFluxBelowAPixel() {
        // факел реактивного снаряда (0,38 блока) за 3 км днём: ядро в 1,5 px, свет тот же, что у настоящего
        double[] out = new double[5];
        double d = 3000, seen = 13;
        Sight.light(0.38, seen, d, PIXEL, out);
        assertEquals(0.75 * PIXEL * d, out[0], 1e-9);
        assertEquals(seen * 0.38 * 0.38, out[2] * out[0] * out[0], 1e-9, "яркость ядра × его площадь — поток источника");
        assertTrue(out[2] < 1, "искра бледнее белого");
    }

    @Test
    void eyeAdaptsToNight() {
        assertEquals(1, Sight.adapted(1, DAY), 1e-12);
        double night = Sight.adapted(1, NIGHT);
        assertTrue(night > 500 && night < 3000, "ночью в " + night + " раз");
    }
}
