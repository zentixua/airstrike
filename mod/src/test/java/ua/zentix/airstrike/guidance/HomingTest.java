package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomingTest {
    private static double angle(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.normalize().dot(b.normalize())))));
    }

    /** Цель сбоку: поворот ровно на предел, в плоскости обоих направлений, к цели. */
    @Test
    void turnIsLimitedAndTowardsWant() {
        Vec3 dir = new Vec3(1, 0, 0), want = new Vec3(0, 0, 1);
        Vec3 got = Homing.turn(dir, want, 9);
        assertEquals(9, angle(dir, got), 1e-9);
        assertEquals(81, angle(got, want), 1e-9);
        assertEquals(0, got.y, 1e-12, "поворот вышел из плоскости");
        assertEquals(1, got.length(), 1e-12);
    }

    /** Цель в пределах поворота — курс прямо на неё. */
    @Test
    void smallTurnReachesWant() {
        Vec3 want = new Vec3(1, 0.1, 0).normalize();
        assertEquals(want, Homing.turn(new Vec3(1, 0, 0), want, 9));
    }

    /** Цель точно сзади — поворот через верх, у вертикального курса — через +X; не застревает. */
    @Test
    void oppositeTurnsSomewhere() {
        Vec3 got = Homing.turn(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), 9);
        assertEquals(9, angle(new Vec3(1, 0, 0), got), 1e-9);
        assertTrue(got.y > 0, got.toString());
        Vec3 up = Homing.turn(new Vec3(0, 1, 0), new Vec3(0, -1, 0), 9);
        assertEquals(9, angle(new Vec3(0, 1, 0), up), 1e-9);
        assertTrue(up.x > 0, up.toString());
    }

    /** Повтор поворотов приводит курс к цели за угол / предел тиков. */
    @Test
    void repeatedTurnsConverge() {
        Vec3 dir = new Vec3(0, 1, 0), want = new Vec3(0.3, -0.2, -1).normalize();
        double start = angle(dir, want);
        int ticks = 0;
        while (!dir.equals(want)) {
            dir = Homing.turn(dir, want, 9);
            ticks++;
            assertTrue(ticks <= Math.ceil(start / 9), "за " + ticks + " тиков курс не вышел на цель");
        }
    }

    /**
     * Быстрая ракета проходит цель насквозь за тик: в концах тика они в 5 блоках друг от друга, а посреди тика —
     * встречаются.
     */
    @Test
    void closestApproachInsideTick() {
        Vec3 a0 = new Vec3(0, 0, 0), a1 = new Vec3(10, 0, 0), b0 = new Vec3(5, 5, 0), b1 = new Vec3(5, -5, 0);
        double s = Homing.closest(a0, a1, b0, b1);
        assertEquals(0.5, s, 1e-12);
        assertEquals(0, Homing.distanceAt(a0, a1, b0, b1, s), 1e-12);
        assertTrue(a0.distanceTo(b0) > 6 && a1.distanceTo(b1) > 6);
    }

    /** Расходятся или стоят — ближе всего в начале тика; сближение после конца тика — в конце. */
    @Test
    void closestIsClampedToTick() {
        Vec3 o = Vec3.ZERO;
        assertEquals(0, Homing.closest(o, new Vec3(-1, 0, 0), new Vec3(5, 0, 0), new Vec3(6, 0, 0)), 1e-12);
        assertEquals(0, Homing.closest(o, o, new Vec3(5, 0, 0), new Vec3(5, 0, 0)), 1e-12);
        assertEquals(1, Homing.closest(o, new Vec3(1, 0, 0), new Vec3(10, 0, 0), new Vec3(10, 0, 0)), 1e-12);
    }
}
