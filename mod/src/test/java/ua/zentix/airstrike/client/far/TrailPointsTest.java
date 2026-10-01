package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.ClientWeaponSpec;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarTrail;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Общий круговой буфер точек шлейфов: тают своим сроком, полный буфер затирает старые, отбой снимает шлейф сразу. */
class TrailPointsTest {
    private static final FarTrail SHORT = new FarTrail(2, 40, 1, 2, 0xFFFFFF, 0xFFFFFF, 1, 1, 0.5f, 1);
    private static final FarTrail LONG = new FarTrail(4, 1000, 1, 2, 0xFFFFFF, 0xFFFFFF, 1, 1, 0.5f, 1);

    @Test
    void pointsMeltByTheirOwnLifetime() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 100, SHORT, 1, TrailPoints.NONE);
        long b = p.add(1, 0, 0, 100, LONG, 2, TrailPoints.NONE);
        long c = p.add(2, 0, 0, 101, SHORT, 1, a);
        p.expire(139);
        assertTrue(p.holds(a) && p.holds(c), "короткий ещё жив");
        p.expire(140);
        assertFalse(p.holds(a), "растаял с хвоста");
        // за долгим шлейфом короткий растаял, но с хвоста его не убрать — его отсеет кадр по возрасту
        assertTrue(p.holds(b) && p.holds(c));
        assertTrue(SHORT.expired(141 - p.birth[TrailPoints.index(c)]));
        p.expire(1100);
        assertTrue(p.isEmpty());
    }

    @Test
    void fullBufferOverwritesOldestAndBreaksLinksToIt() {
        TrailPoints p = new TrailPoints();
        long prev = TrailPoints.NONE, firstLink = TrailPoints.NONE;
        for (int i = 0; i < TrailPoints.CAPACITY + 10; i++) {
            prev = p.add(i, 0, 0, i, LONG, 1, prev);
            if (i == 10) firstLink = prev;
        }
        assertEquals(10, p.first(), "затёрты десять самых старых");
        assertFalse(p.holds(9));
        assertTrue(p.holds(10));
        // точка 10 ссылается на затёртую 9: отрезка к ней нет
        assertFalse(p.holds(p.prev[TrailPoints.index(firstLink)]));
        assertEquals(TrailPoints.CAPACITY + 9, p.prev[TrailPoints.index(prev)] + 1);
    }

    @Test
    void cancelledFlightTrailVanishesAtOnce() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 0, LONG, 7, TrailPoints.NONE);
        long b = p.add(0, 0, 0, 0, LONG, 8, TrailPoints.NONE);
        p.kill(7);
        assertNull(p.style[TrailPoints.index(a)]);
        assertNotNull(p.style[TrailPoints.index(b)], "чужой шлейф остаётся");
        p.expire(1);
        assertFalse(p.holds(a));
        assertTrue(p.holds(b));
    }

    @Test
    void wholeRocketPackFitsQuarterOfBuffer() {
        // пакет «Града» — 40 снарядов: точка раз в шаг, пока горит двигатель, и точка выгорания
        FarTrail trail = ClientWeaponSpec.of(WeaponType.ROCKET).airframe(false).far().stage(FlightPhase.BOOST).trail();
        assertNotNull(trail);
        int pack = LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS;
        int perRocket = RocketEntity.BURN_TICKS / trail.step() + 2;
        assertTrue(pack * perRocket <= TrailPoints.CAPACITY / 4, pack * perRocket + " точек");
    }
}
