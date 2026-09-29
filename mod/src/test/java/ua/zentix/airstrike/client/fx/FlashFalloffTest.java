package ua.zentix.airstrike.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashFalloffTest {
    private static final double MISSILE_NEAR = 3 * BlastEffects.Missile.R, MISSILE_RANGE = 400;
    private static final double DRONE_NEAR = 3 * BlastEffects.Drone.R, DRONE_RANGE = 240;

    /** Вблизи, лицом к взрыву — как было (0.85), за преградой — как было (0.3). */
    @Test
    void closeBlastKeepsItsStrength() {
        assertEquals(0.85f, FlashFalloff.strength(10, MISSILE_NEAR, MISSILE_RANGE, 1, true), 0.01f);
        assertEquals(0.30f, FlashFalloff.strength(10, MISSILE_NEAR, MISSILE_RANGE, 1, false), 0.01f);
        assertEquals(0.85f, FlashFalloff.strength(0, DRONE_NEAR, DRONE_RANGE, 1, true), 0.01f);
    }

    /** Залп в 200 блоках (план barrage трейлера) не белит экран: была заливка 0.43 от ракеты и 0.14 от шахеда. */
    @Test
    void farSalvoDoesNotWashOut() {
        assertTrue(FlashFalloff.strength(200, MISSILE_NEAR, MISSILE_RANGE, 1, true) <= 0.12f);
        assertTrue(FlashFalloff.strength(200, DRONE_NEAR, DRONE_RANGE, 1, true) <= 0.05f);
    }

    /** Спадает с расстоянием монотонно и доходит до нуля к краю без скачка. */
    @Test
    void monotonicAndContinuousAtRange() {
        float prev = Float.MAX_VALUE;
        for (double d = 0; d <= MISSILE_RANGE + 10; d += 1) {
            float s = FlashFalloff.strength(d, MISSILE_NEAR, MISSILE_RANGE, 1, true);
            assertTrue(s <= prev + 1e-6f, "d=" + d);
            prev = s;
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
