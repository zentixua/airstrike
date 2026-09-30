package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontProfileTest {
    private static final double SCALE = 0.3;
    private static final ArrivalTable MODEL = ArrivalTable.of(15, 20000);
    /** Два радиуса шара 15 кт в блоках (≈ 2 × 180 м × 0.3). */
    private static final double FROM = 108;
    private static final FrontProfile P = FrontProfile.of(MODEL, SCALE, FROM, 3000);

    private static double model(double blocks) {
        return MODEL.arrivalSeconds(blocks / SCALE) * 20 * SCALE;
    }

    /** У эпицентра — как у модели: вспышка, шар и первый удар не меняются. */
    @Test
    void nearGroundZeroFollowsModel() {
        for (double b = 1; b <= FROM; b += 7) assertEquals(model(b), P.arrivalTicks(b), Math.max(0.05, model(b) * 0.01), "на " + b);
    }

    /** Дальше не быстрее модели, монотонно, без скачков скорости, и к {@link FrontProfile#SLOW_TO} — 3 блока за тик. */
    @Test
    void slowsDownSmoothlyToMinSpeed() {
        double prev = 0, prevSpeed = Double.NaN;
        for (double b = 1; b <= 2500; b += 1) {
            double t = P.arrivalTicks(b);
            assertTrue(t > prev, "время не растёт на " + b);
            assertTrue(t >= model(b) - 1e-6, "быстрее модели на " + b);
            double speed = 1 / (t - prev);
            if (b > FROM + 2 && !Double.isNaN(prevSpeed)) assertTrue(speed <= prevSpeed * 1.05 + 0.05, "скорость скачет на " + b);
            if (b >= FrontProfile.SLOW_TO + 5) assertEquals(FrontProfile.MIN_SPEED, speed, 0.05, "на " + b);
            prev = t;
            prevSpeed = speed;
        }
    }

    /** Прорисовка в 8 чанков (128 блоков) за краем замедления проходится за 2 с и дольше, а не мгновенно. */
    @Test
    void wallIsVisibleApproaching() {
        double far = 800;
        assertTrue(P.arrivalTicks(far) - P.arrivalTicks(far - 128) >= 40);
    }

    @Test
    void radiusIsInverseOfArrival() {
        for (double b = 0; b <= 5000; b += 37) assertEquals(b, P.radiusAt(P.arrivalTicks(b)), 1e-3 + b * 1e-6);
        assertEquals(0, P.radiusAt(0), 0);
    }
}
