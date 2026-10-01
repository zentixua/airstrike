package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Взрывы вдали: таблица видов, столб без разрывов, клубы за гребнем. */
class FarBlastsTest {
    private static final List<FarBlasts.Look> LOOKS = List.of(FarBlasts.DRONE, FarBlasts.MISSILE, FarBlasts.ROCKET, FarBlasts.BUNKER_BREACH,
            FarBlasts.BUNKER_DEEP);

    /** Верх облака через 2 минуты по Чёрчу (1969): 92,6·W^¼ м, W — кг ТНТ. */
    private static double church(double kg) {
        return 92.6 * Math.pow(kg, 0.25);
    }

    @Test
    void columnsMatchTheirWarheads() {
        // ТНТ-эквивалент: снаряд РСЗО ~6,4 кг, шахед ~30, крылатая ракета ~300; верх — верх верхнего клуба
        assertCloud(FarBlasts.ROCKET, church(6.4));
        assertCloud(FarBlasts.DRONE, church(30));
        assertCloud(FarBlasts.MISSILE, church(300));
        assertTrue(cloud(FarBlasts.BUNKER_BREACH) < cloud(FarBlasts.MISSILE), "прорвавшаяся бомба — ниже наземного взрыва");
        assertEquals(0, FarBlasts.BUNKER_DEEP.fireball(), "бомба под землёй — без вспышки и шара");
        assertEquals(1, FarBlasts.BUNKER_DEEP.dust(), 1e-6, "бомба под землёй — столб пыли");
        for (FarBlasts.Look k : LOOKS) {
            assertTrue(k.puffs() >= 10 && k.puffs() <= 14, "клубов на столб: " + k);
            assertTrue(k.life() >= 1200, "столб живёт минуты: " + k);
            assertTrue(k.life() > k.riseTicks(), "столб поднимается раньше, чем тает: " + k);
        }
    }

    @Test
    void fireballsMatchTheirCharges() {
        // радиус шара 1,6–1,8·W^⅓, светит 0,2·W^0,35 с (±40 %: вспышка шахеда — как у ближней картинки)
        double[][] charges = {{6.4, 0}, {30, 1}, {300, 2}};
        FarBlasts.Look[] looks = {FarBlasts.ROCKET, FarBlasts.DRONE, FarBlasts.MISSILE};
        for (double[] c : charges) {
            FarBlasts.Look k = looks[(int) c[1]];
            double cube = Math.cbrt(c[0]);
            assertTrue(k.fireball() >= 1.5 * cube && k.fireball() <= 1.9 * cube, k + ": шар " + k.fireball() + " при W^⅓ " + cube);
            double ticks = 20 * 0.2 * Math.pow(c[0], 0.35);
            assertTrue(Math.abs(k.ballTicks() - ticks) <= 0.4 * ticks, k + ": светит " + k.ballTicks() + " т, по замерам " + ticks);
        }
    }

    @Test
    void ballOutshinesDaySkyForAboutHalfItsLife() {
        // днём (яркость против неба > 1 после воздуха 1,5 км) — меньше половины жизни; ночью — вся
        double t = Sight.transmittance(1500, Sight.CLEAR);
        double day = 0;
        for (double u = 0; u < 1; u += 0.001) if (FarBlasts.ball(u) * t > 1) day += 0.001;
        assertTrue(day > 0.3 && day < 0.55, "днём виден " + day + " жизни");
        assertTrue(Sight.adapted(FarBlasts.ball(0.85), 0.17) * Sight.transmittance(4000, Sight.CLEAR) > 1, "ночью — почти до конца");
    }

    @Test
    void flashHoldsThenFades() {
        FarBlasts.Look k = FarBlasts.MISSILE;
        assertEquals(k.flash(), FarBlasts.flash(k, 0), 1e-12);
        assertTrue(FarBlasts.flash(k, 1) > 0.9 * k.flash(), "держится — глаз складывает свет 50–100 мс");
        assertEquals(0, FarBlasts.flash(k, k.flashTicks()), 1e-12);
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
    void cloudsGlowAtNightNotByDay() {
        // свет неба FarView.ambient: полдень, сумерки и полночь (getSkyDarken 0,2); облака в 130 блоках над местом
        double day = 1, dusk = 0.5, night = 0.17, h = 130;
        for (FarBlasts.Look k : List.of(FarBlasts.DRONE, FarBlasts.MISSILE, FarBlasts.ROCKET, FarBlasts.BUNKER_BREACH)) {
            double b = FarBlasts.ball(0);
            assertTrue(FarBlasts.SKY_PEAK * FarBlasts.cloudGlow(b, k.fireball(), h, day) < Sight.THRESHOLD, k + ": днём зарева не видно");
            assertTrue(FarBlasts.cloudGlow(b, k.fireball(), h, night) > 0.5, k + ": ночью низ облаков над взрывом вспыхивает");
            assertTrue(FarBlasts.cloudGlow(k.flash(), k.fireball(), h, night) <= 1, "не ярче белого");
        }
        assertTrue(FarBlasts.cloudGlow(8, FarBlasts.MISSILE.fireball(), h, dusk) > FarBlasts.cloudGlow(8, FarBlasts.DRONE.fireball(), h, dusk),
                "ракета ярче шахеда");
        assertTrue(FarBlasts.cloudGlow(8, FarBlasts.DRONE.fireball(), h, dusk) > FarBlasts.cloudGlow(8, FarBlasts.ROCKET.fireball(), h, dusk),
                "шахед ярче снаряда РСЗО");
    }

    /** Верх облака: верх верхнего клуба поднявшегося столба. */
    private static double cloud(FarBlasts.Look k) {
        double r = FarBlasts.radius(k, 1);
        return FarBlasts.height(k, 1, r, 1) + r;
    }

    private static void assertCloud(FarBlasts.Look k, double church) {
        double top = cloud(k);
        assertTrue(Math.abs(top - church) <= 0.15 * church, k + ": верх облака " + top + ", по Чёрчу " + church);
    }
}
