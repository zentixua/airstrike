package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Взрывы вдали: таблица видов, столб без разрывов, клубы за гребнем. */
class FarBlastsTest {
    private static final List<FarBlasts.Look> LOOKS = List.of(FarBlasts.DRONE, FarBlasts.MISSILE, FarBlasts.ROCKET, FarBlasts.BUNKER_BREACH,
            FarBlasts.BUNKER_DEEP);

    @Test
    void columnsMatchTheirWarheads() {
        // шахед 100–200 м, крылатая ракета 300–500 м, РСЗО ниже шахеда; верх — центр верхнего клуба
        assertTop(FarBlasts.DRONE, 100, 200);
        assertTop(FarBlasts.MISSILE, 300, 500);
        assertTrue(top(FarBlasts.ROCKET) < top(FarBlasts.DRONE) && FarBlasts.ROCKET.life() < FarBlasts.DRONE.life());
        assertEquals(0, FarBlasts.BUNKER_DEEP.fireball(), "бомба под землёй — без вспышки и шара");
        assertEquals(1, FarBlasts.BUNKER_DEEP.dust(), 1e-6, "бомба под землёй — столб пыли");
        for (FarBlasts.Look k : LOOKS) {
            assertTrue(k.puffs() >= 10 && k.puffs() <= 14, "клубов на столб: " + k);
            assertTrue(k.life() >= 1200, "столб живёт минуты: " + k);
            assertTrue(k.life() > k.riseTicks(), "столб поднимается раньше, чем тает: " + k);
        }
    }

    @Test
    void columnHasNoGapsEvenAtWorstJitter() {
        for (FarBlasts.Look k : LOOKS) {
            int n = k.puffs();
            for (int i = 0; i + 1 < n; i++) {
                // соседи разнесены разбросом до предела и оба мельче всех
                double f1 = FarBlasts.fraction(i, n, -0.5), f2 = FarBlasts.fraction(i + 1, n, 0.5);
                double r1 = FarBlasts.radius(k, f1) * FarBlasts.SIZE_MIN, r2 = FarBlasts.radius(k, f2) * FarBlasts.SIZE_MIN;
                double gap = FarBlasts.height(k, f2, r2, 1) - FarBlasts.height(k, f1, r1, 1);
                assertTrue(gap < r1 + r2, k + ": клубы " + i + " и " + (i + 1) + " в " + gap + " бл при радиусах " + r1 + ", " + r2);
            }
        }
    }

    @Test
    void columnRisesFastThenSettles() {
        assertEquals(0, FarBlasts.rise(0, 600), 1e-12);
        assertEquals(0.95, FarBlasts.rise(600, 600), 0.01);
        assertTrue(FarBlasts.rise(100, 600) > 100 / 600.0, "вначале быстрее, чем равномерно");
    }

    @Test
    void puffsSinkBehindTheRidgeSmoothly() {
        assertEquals(1, FarBlasts.visible(5, 6, 0), "место открыто — видно всё, и часть у земли тоже");
        assertEquals(0, FarBlasts.visible(20, 6, 40), 1e-12, "весь клуб ниже гребня");
        assertEquals(1, FarBlasts.visible(60, 6, 40), 1e-12, "весь клуб выше гребня");
        assertEquals(0.5, FarBlasts.visible(40, 6, 40), 1e-12, "гребень посередине клуба");
        // клуб над землёй, гребень у самой земли — видно всё: по мере роста гребня клуб уходит без скачка
        assertEquals(1, FarBlasts.visible(10, 6, 1e-9), 1e-12);
        double prev = 1;
        for (double line = 0; line < 30; line += 0.5) {
            double v = FarBlasts.visible(15, 6, line);
            assertTrue(v <= prev + 1e-12 && prev - v < 0.2, "плавно на " + line + ": " + prev + " → " + v);
            prev = v;
        }
    }

    @Test
    void skyGlowsAtNightNotByDay() {
        // свет неба FarView.ambient: полдень и полночь (getSkyDarken 0,2)
        double day = 1, night = 0.17;
        for (FarBlasts.Look k : List.of(FarBlasts.DRONE, FarBlasts.MISSILE, FarBlasts.ROCKET, FarBlasts.BUNKER_BREACH)) {
            double clearDay = FarBlasts.skyGlow(k.flash(), k.fireball(), Sight.CLEAR, day);
            double clearNight = FarBlasts.skyGlow(k.flash(), k.fireball(), Sight.CLEAR, night);
            double rainNight = FarBlasts.skyGlow(k.flash(), k.fireball(), Sight.RAIN, night);
            assertTrue(clearDay < Sight.THRESHOLD, k + ": днём зарева не видно, " + clearDay);
            assertTrue(clearNight > (k == FarBlasts.ROCKET ? 3 * Sight.THRESHOLD : 0.1), k + ": ночью небо над взрывом вспыхивает, " + clearNight);
            assertTrue(rainNight > clearNight || rainNight == 1, k + ": в дождь воздух рассеивает больше, " + rainNight);
            assertTrue(rainNight <= 1, "не ярче белого");
        }
        assertTrue(FarBlasts.skyGlow(FarBlasts.MISSILE.flash(), FarBlasts.MISSILE.fireball(), Sight.CLEAR, night)
                > FarBlasts.skyGlow(FarBlasts.DRONE.flash(), FarBlasts.DRONE.fireball(), Sight.CLEAR, night), "ракета ярче шахеда");
        assertTrue(FarBlasts.skyGlow(FarBlasts.DRONE.flash(), FarBlasts.DRONE.fireball(), Sight.CLEAR, night)
                > FarBlasts.skyGlow(FarBlasts.ROCKET.flash(), FarBlasts.ROCKET.fireball(), Sight.CLEAR, night), "шахед ярче снаряда РСЗО");
    }

    private static double top(FarBlasts.Look k) {
        return FarBlasts.height(k, 1, FarBlasts.radius(k, 1), 1);
    }

    private static void assertTop(FarBlasts.Look k, double from, double to) {
        double top = top(k);
        assertTrue(top >= from && top <= to, k + ": верх столба " + top);
    }
}
