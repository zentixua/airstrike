package ua.zentix.airstrike.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashFalloffTest {
    private static final double MISSILE_NEAR = BlastEffects.Missile.FLASH_NEAR, MISSILE_RANGE = BlastEffects.Missile.FLASH_RANGE;
    private static final double DRONE_NEAR = BlastEffects.Drone.FLASH_NEAR, DRONE_RANGE = BlastEffects.Drone.FLASH_RANGE;

    /** Вблизи, лицом к взрыву — как было (0.85), за преградой — как было (0.3). */
    @Test
    void closeBlastKeepsItsStrength() {
        assertEquals(0.85f, FlashFalloff.strength(10, MISSILE_NEAR, MISSILE_RANGE, 1, true), 0.01f);
        assertEquals(0.30f, FlashFalloff.strength(10, MISSILE_NEAR, MISSILE_RANGE, 1, false), 0.01f);
        assertEquals(0.85f, FlashFalloff.strength(0, DRONE_NEAR, DRONE_RANGE, 1, true), 0.01f);
    }

    /** Вплотную взрыв слепит, куда ни смотри: сбоку и за спиной сила прежняя (было 0.84 у шахеда в 3 блоках). */
    @Test
    void blastAtHandBlindsWhateverTheView() {
        assertEquals(0.85f * 0.8f + 0.85f * 0.2f * 0.5f, FlashFalloff.strength(3, DRONE_NEAR, DRONE_RANGE, -1, true), 0.01f);
        assertTrue(FlashFalloff.strength(3, DRONE_NEAR, DRONE_RANGE, -1, true) >= 0.75f);
        float prev = 0;
        for (double d = DRONE_NEAR; d >= 0; d -= 0.5) {
            float s = FlashFalloff.strength(d, DRONE_NEAR, DRONE_RANGE, 0, true);
            assertTrue(s >= prev - 1e-6f, "d=" + d);
            prev = s;
        }
    }

    /** Залп в 200 блоках (план barrage трейлера) не белит экран: была заливка 0.43 от ракеты и 0.14 от шахеда. */
    @Test
    void farSalvoDoesNotWashOut() {
        assertTrue(FlashFalloff.strength(200, MISSILE_NEAR, MISSILE_RANGE, 1, true) <= 0.12f);
        assertTrue(FlashFalloff.strength(200, DRONE_NEAR, DRONE_RANGE, 1, true) <= 0.05f);
    }

    /** Днём шар освещает окрестность на проценты от солнца — экран едва светлеет; в сумерках и ночью — вспышка. */
    @Test
    void daylightTamesTheScreenFlash() {
        assertTrue(FlashFalloff.daylight(1) <= 0.1f, "днём " + FlashFalloff.daylight(1));
        assertEquals(1f, FlashFalloff.daylight(0.5), 1e-6f, "сумерки");
        assertEquals(1f, FlashFalloff.daylight(0.17), 1e-6f, "ночь");
        float prev = 0;
        for (double a = 1; a >= 0.12; a -= 0.01) {
            float k = FlashFalloff.daylight(a);
            assertTrue(k >= prev, "свет неба " + a);
            prev = k;
        }
    }

    /** Спадает с расстоянием монотонно и доходит до нуля к краю без скачка. */
    @Test
    void monotonicAndContinuousAtRange() {
        for (double cos : new double[] {1, 0, -1}) {
            for (boolean visible : new boolean[] {true, false}) {
                float prev = Float.MAX_VALUE;
                for (double d = 0; d <= MISSILE_RANGE + 10; d += 1) {
                    float s = FlashFalloff.strength(d, MISSILE_NEAR, MISSILE_RANGE, cos, visible);
                    assertTrue(s <= prev + 1e-6f, "d=" + d + " cos=" + cos + " visible=" + visible);
                    prev = s;
                }
            }
        }
        assertTrue(FlashFalloff.strength(MISSILE_RANGE - 1, MISSILE_NEAR, MISSILE_RANGE, 1, true) < 0.001f);
        assertEquals(0f, FlashFalloff.strength(MISSILE_RANGE, MISSILE_NEAR, MISSILE_RANGE, 1, true));
    }

    /** Слепит взрыв в поле зрения; за спиной — только отсвет, за преградой — ещё слабее. */
    @Test
    void glareNeedsTheBlastInView() {
        float front = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, 1, true);
        float side = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, Math.cos(Math.toRadians(45)), true);
        float back = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, -1, true);
        float hidden = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, 1, false);
        assertTrue(front > side && side > back, front + " " + side + " " + back);
        assertEquals(FlashFalloff.AMBIENT, back / front, 1e-4f);
        assertTrue(hidden <= back);
    }
}
