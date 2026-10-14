package ua.zentix.replayscan.film;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель дыма удара по коду картинки Incoming: путь частицы — как тик мода, облако шара и вал пыли «Града» там, где их
 * видели кадры проходов 13.10 (камера в 13–18 блоках от удара через 3–5 с — в дыму), столб стоит минуты и клонится по
 * ветру, свой дым цели взгляд на неё не закрывает.
 */
class PlumeTest {
    private static final Vec3 AT = new Vec3(0, 64, 0);

    @Test
    void particleMovesLikeTheModTick() {
        // клуб облака шара: скорость (0,2; 0,3; −0,1), сопротивление 0,9, всплытие 0,011 — по тику FxParticle
        double x = 0, y = 0, z = 0, vx = 0.2, vy = 0.3, vz = -0.1;
        for (int age = 1; age <= 300; age++) {
            vy += 0.011 * (0.15 + 0.85 * Math.exp(-age / 40.0));
            vx += Plume.WIND_X;
            vz += Plume.WIND_Z;
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.9;
            vy *= 0.9;
            vz *= 0.9;
            if (age == 1 || age == 40 || age == 300) {
                Plume.Motion m = Plume.BALL;
                assertEquals(x, 0.2 * m.move(age) + Plume.WIND_X * m.wind(age), 1e-9, "x at " + age);
                assertEquals(y, 0.3 * m.move(age) + m.rise(age), 1e-9, "y at " + age);
                assertEquals(z, -0.1 * m.move(age) + Plume.WIND_Z * m.wind(age), 1e-9, "z at " + age);
            }
        }
    }

    @Test
    void gradSmokeFillsTheAirBesideTheHit() {
        // проход 03:53 (13.10): камера в 13–24 блоках от «Градов» и на 4–7 выше них через 3–5 с — кадр весь в дыму; прежняя
        // модель (столб радиусом 8) видела там чистый воздух: облако шара — шире 10 блоков, вал пыли — 20 блоков по земле
        Plume grad = new Plume(100, AT, 8, Plume.Look.ROCKET);
        for (int u : new int[]{60, 100}) {
            assertTrue(grad.clearance(AT.add(8, 8, 0), 100 + u) < 0, "ball cloud 8 blocks beside, " + u + " ticks after");
            assertTrue(grad.clearance(AT.add(0, 3, -17), 100 + u) < 0, "dust surge 17 blocks beside, " + u + " ticks after");
        }
        assertTrue(grad.clearance(AT.add(45, 10, 0), 160) > 3, "45 blocks away — clear");
        assertTrue(grad.clearance(AT.add(25, 16, 0), 160) > 0, "over the dust surge — clear");
        assertFalse(grad.alive(99));
    }

    @Test
    void columnStandsForMinutesAndLeansDownwind() {
        Plume grad = new Plume(0, AT, 8, Plume.Look.ROCKET);
        int u = 1200;
        // через минуту: столб поднялся на ~80 блоков, шапку снесло по ветру (на восток и юг) на десятки блоков
        double rise = 1 - Math.exp(-3.0 * u / 2400), drift = Plume.WIND_X * Plume.DRIFT * u;
        Vec3 top = AT.add(drift, 105 * rise, Plume.WIND_Z * Plume.DRIFT * u);
        assertTrue(drift > 50, "drift " + drift);
        assertTrue(grad.clearance(top, u) < 0, "in the cap downwind");
        assertTrue(grad.clearance(AT.add(-drift, 105 * rise, -Plume.WIND_Z * Plume.DRIFT * u), u) > 0, "upwind of the cap — clear");
        assertTrue(grad.blocks(top.add(-80, 0, 0), top.add(80, 0, 0), u), "a look through the cap");
        // жизнь столба — 3000 тиков
        assertTrue(grad.alive(2999));
        assertEquals(Double.POSITIVE_INFINITY, grad.clearance(AT.add(0, 10, 0), 3001));
    }

    @Test
    void ownSmokeDoesNotHideTheHit() {
        Plume shahed = new Plume(100, AT, 12, Plume.Look.DRONE);
        Vec3 eye = AT.add(-60, 8, 0);
        assertFalse(shahed.blocks(eye, AT.add(0, 4, 0), 160), "looking at the hit itself");
        assertTrue(shahed.blocks(eye, AT.add(60, 8, 0), 160), "looking through it at something beyond");
    }

    @Test
    void lookFromThePlanRow() {
        assertSame(Plume.Look.ROCKET, Plume.of(new double[]{1, 0, 64, 0, 8, 1}).look());
        assertSame(Plume.Look.MISSILE, Plume.of(new double[]{1, 0, 64, 0, 18, 3}).look());
        assertNull(Plume.of(new double[]{1, 0, 64, 0, 120, 0}).look(), "a nuke has no blast smoke");
        assertFalse(Plume.of(new double[]{1, 0, 64, 0, 120, 0}).alive(50));
        // прежние планы без вида — по поперечнику шара
        assertSame(Plume.Look.ROCKET, Plume.of(new double[]{1, 0, 64, 0, 8}).look());
        assertSame(Plume.Look.DRONE, Plume.of(new double[]{1, 0, 64, 0}).look());
        assertSame(Plume.Look.BUNKER, Plume.of(new double[]{1, 0, 64, 0, 24}).look());
    }

    @Test
    void cylinderAndBallShapes() {
        Plume.Shape ball = new Plume.Shape(0, 70, 0, 5, 0, false, 1);
        assertEquals(-5, ball.distance(0, 70, 0), 1e-9);
        assertEquals(5, ball.distance(10, 70, 0), 1e-9);
        assertTrue(ball.crosses(new Vec3(-10, 72, 0), new Vec3(10, 72, 0)));
        assertFalse(ball.crosses(new Vec3(-10, 76, 0), new Vec3(10, 76, 0)));
        Plume.Shape disk = new Plume.Shape(0, 64, 0, 20, 70, true, 1);
        assertTrue(disk.distance(10, 66, 0) < 0, "inside");
        assertEquals(4, disk.distance(10, 74, 0), 1e-9, "above");
        assertEquals(5, disk.distance(25, 66, 0), 1e-9, "beside");
        assertTrue(disk.crosses(new Vec3(-40, 68, 5), new Vec3(40, 68, 5)), "level through");
        assertTrue(disk.crosses(new Vec3(-30, 90, 0), new Vec3(30, 60, 0)), "down through the top");
        assertFalse(disk.crosses(new Vec3(-30, 75, 0), new Vec3(30, 72, 0)), "over the top");
        assertFalse(disk.crosses(new Vec3(-40, 68, 25), new Vec3(40, 68, 25)), "beside");
    }
}
