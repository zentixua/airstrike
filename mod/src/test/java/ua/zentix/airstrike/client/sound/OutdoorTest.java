package ua.zentix.airstrike.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Далёкий взрыв под открытым небом по ISO 9613-2: кромка, земля, зона тени днём, дождь, громкость в игре. */
class OutdoorTest {
    /** Докуда слышно и громкость ближней модели на её краю: шахед, крылатая ракета, РСЗО, бомба. */
    private static final double[][] KINDS = {{20_000, 0.35}, {40_000, 0.35}, {15_000, 0.2}, {30_000, 1}};

    /** Источник на 2 м, ухо на 1,7 м, одна земля у обоих, сухо. */
    private static Outdoor.Path path(double d, double z, double g, double day) {
        return new Outdoor.Path(d, z, 2, 1.7, g, g, day, 0, 0);
    }

    @Test
    void openPathHasNoBarrierAndGrazingEdgeGivesFiveDecibels() {
        assertEquals(0, Outdoor.barrier(0, Outdoor.LOW));
        assertEquals(0, Outdoor.barrier(0, Outdoor.HIGH));
        // кромка вровень с лучом (z → 0): 10·lg 3 в обеих полосах
        assertEquals(10 * Math.log10(3), Outdoor.barrier(1e-9, Outdoor.LOW), 1e-6);
        assertEquals(10 * Math.log10(3), Outdoor.barrier(1e-9, Outdoor.HIGH), 1e-6);
    }

    @Test
    void barrierGrowsWithPathDifferenceFasterForHighs() {
        // 0,1 м разности хода: низам (λ 5,4 м) — ≈5,3 дБ, верхам (λ 0,17 м) — ≈11,7 дБ
        assertEquals(10 * Math.log10(3 + 20 * 0.1 / (343.0 / 63)), Outdoor.barrier(0.1, Outdoor.LOW), 1e-9);
        assertEquals(10 * Math.log10(3 + 20 * 0.1 / (343.0 / 2000)), Outdoor.barrier(0.1, Outdoor.HIGH), 1e-9);
        assertEquals(5.27, Outdoor.barrier(0.1, Outdoor.LOW), 0.01);
        assertEquals(11.66, Outdoor.barrier(0.1, Outdoor.HIGH), 0.01);
        // большой холм (10 м): верха упираются в предел одной кромки, низы — нет
        assertEquals(20, Outdoor.barrier(10, Outdoor.HIGH), 1e-9);
        assertEquals(15.99, Outdoor.barrier(10, Outdoor.LOW), 0.01);
        assertEquals(20, Outdoor.barrier(1000, Outdoor.LOW), 1e-9);
    }

    @Test
    void middleGroundCountsOnlyBeyondThirtyHeights() {
        assertEquals(0, Outdoor.q(100, 2, 1.7));
        assertEquals(0, Outdoor.q(111, 2, 1.7), 1e-12);
        assertEquals(0.9, Outdoor.q(1110, 2, 1.7), 1e-12);
    }

    @Test
    void hardGroundReflectsHighsPorousGroundAbsorbsThem() {
        Outdoor.Path hard = path(2000, 0, 0, 0), soft = path(2000, 0, 1, 0);
        double q = Outdoor.q(2000, 2, 1.7);
        // 63 Гц: земля у источника и у уха −1,5 дБ, середина −3q — от пористости не зависит
        assertEquals(-3 - 3 * q, Outdoor.ground(false, hard), 1e-12);
        assertEquals(-3 - 3 * q, Outdoor.ground(false, soft), 1e-12);
        // 2 кГц: твёрдая отражает так же, рыхлая — нет
        assertEquals(-3 - 3 * q, Outdoor.ground(true, hard), 1e-12);
        assertEquals(0, Outdoor.ground(true, soft), 1e-12);
        assertEquals(1, Outdoor.highs(hard), 1e-6);
        assertEquals(Math.pow(10, -(3 + 3 * q) / 20), Outdoor.highs(soft), 1e-6);
        // громкость — по низам: земля решает только, насколько раскат глухой
        assertEquals(Outdoor.volume(20_000, 0.35, hard), Outdoor.volume(20_000, 0.35, soft), 1e-7);
    }

    @Test
    void daytimeShadowZoneNightInversion() {
        assertEquals(1, Outdoor.day(1), 1e-12);
        assertEquals(0, Outdoor.day(0), 1e-12);
        assertEquals(0, Outdoor.day(-1), 1e-12);
        assertEquals(0, Outdoor.shadow(true, path(4000, 0, 0.5, 0)));
        assertEquals(20, Outdoor.shadow(true, path(4000, 0, 0.5, 1)), 1e-9);
        assertEquals(10, Outdoor.shadow(false, path(4000, 0, 0.5, 1)), 1e-9);
        assertEquals(10, Outdoor.shadow(false, path(9000, 0, 0.5, 1)), 1e-9);
        assertEquals(0, Outdoor.shadow(true, path(1000, 0, 0.5, 1)), 1e-9);
        // ближе километра день и ночь звучат одинаково, дальше днём тише и глуше
        assertEquals(Outdoor.volume(20_000, 0.35, path(900, 0, 1, 0)), Outdoor.volume(20_000, 0.35, path(900, 0, 1, 1)), 1e-7);
        assertTrue(Outdoor.volume(20_000, 0.35, path(3000, 0, 1, 1)) < Outdoor.volume(20_000, 0.35, path(3000, 0, 1, 0)));
        assertTrue(Outdoor.highs(path(3000, 0, 1, 1)) < Outdoor.highs(path(3000, 0, 1, 0)));
    }

    @Test
    void volumeContinuesTheNearModelAtItsEdge() {
        for (double[] k : KINDS) {
            for (double g : new double[]{0, 0.5, 1}) {
                assertEquals(k[1], Outdoor.volume(k[0], k[1], path(Outdoor.NEAR, 0, g, 0)), 1e-6, "ровно громкость ближней модели на 640 блоках");
                assertEquals(k[1], Outdoor.volume(k[0], k[1], path(Outdoor.NEAR + 0.5, 0, g, 1)), 1e-3, "и днём — тени ближе километра нет");
            }
        }
    }

    @Test
    void volumeFallsMonotonicallyWithDistanceAndDiesBeyondRange() {
        for (double[] k : KINDS) {
            for (double day : new double[]{0, 1}) {
                for (double z : new double[]{0, 2}) {
                    for (double g : new double[]{0, 1}) {
                        float prev = Outdoor.volume(k[0], k[1], path(Outdoor.NEAR, z, g, day));
                        for (double d = Outdoor.NEAR + 25; d < 2 * k[0]; d += 25) {
                            float v = Outdoor.volume(k[0], k[1], path(d, z, g, day));
                            assertTrue(v <= prev + 1e-7, "громче на " + d + " бл: " + v + " > " + prev);
                            prev = v;
                        }
                    }
                }
            }
            assertEquals(0, Outdoor.volume(k[0], k[1], path(k[0] * 1.01, 0, 0.5, 0)), "дальше R0 — тишина");
            assertTrue(Outdoor.volume(k[0], k[1], path(k[0] / 2, 0, 0.5, 0)) > 0, "в половине R0 ночью — слышно");
        }
    }

    @Test
    void hillQuietensAndMufflesTheRumble() {
        // кромка в полуметре над лучом (z ≈ 0,5 м на 3 км): низы её почти обходят, верха — нет
        Outdoor.Path open = path(3000, 0, 0.5, 0), hill = path(3000, 0.5, 0.5, 0);
        assertTrue(Outdoor.volume(20_000, 0.35, hill) < Outdoor.volume(20_000, 0.35, open));
        assertTrue(Outdoor.highs(hill) < 0.5 * Outdoor.highs(open));
        // кромка заменяет эффект земли: на низах Dz − Agr
        assertEquals(Outdoor.barrier(0.5, Outdoor.LOW) - Outdoor.ground(false, hill), Outdoor.obstacle(false, hill), 1e-12);
    }

    @Test
    void rainMasksTheRumbleButKeepsItsTimbre() {
        Outdoor.Path dry = path(3000, 0, 0.5, 0);
        Outdoor.Path rain = new Outdoor.Path(3000, 0, 2, 1.7, 0.5, 0.5, 0, 1, 0);
        Outdoor.Path storm = new Outdoor.Path(3000, 0, 2, 1.7, 0.5, 0.5, 0, 1, 1);
        assertEquals(3, Outdoor.margin(20_000, dry) - Outdoor.margin(20_000, rain), 1e-9);
        assertEquals(6, Outdoor.margin(20_000, dry) - Outdoor.margin(20_000, storm), 1e-9);
        assertTrue(Outdoor.volume(20_000, 0.35, storm) < Outdoor.volume(20_000, 0.35, rain));
        assertTrue(Outdoor.volume(20_000, 0.35, rain) < Outdoor.volume(20_000, 0.35, dry));
        assertEquals(Outdoor.highs(dry), Outdoor.highs(storm), 1e-7);
    }
}
