package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Путь снаряда по пакетам сервера: между пакетами — по прямой, разрыв — заново, без данных — молчать. */
class SourceTrackTest {
    private static final UUID ID = UUID.randomUUID();

    private static S2C.HeardFlight at(double x, int phaseAge) {
        return new S2C.HeardFlight(ID, WeaponType.MISSILE.id(), false, false, new Vec3(x, 80, 0), 0, 0,
                FlightPhase.CRUISE.ordinal(), phaseAge, 500);
    }

    private static double x(SourceTrack t, double time) {
        double[] p = new double[3];
        t.at(time, p);
        return p[0];
    }

    @Test
    void ticksBetweenPacketsFollowStraightLine() {
        SourceTrack t = new SourceTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.record(14, at(46, 104));
        assertEquals(11.5, x(t, 11), 1e-9);
        assertEquals(34.5, x(t, 13), 1e-9);
        assertEquals(11.5, t.velocity(12.5).x, 1e-9, "скорость ровная — Доплер без ступенек");
        assertEquals(103, t.phaseAge(13), 1e-9);
        assertEquals(10, t.start(), 1e-9);
    }

    @Test
    void laterPacketOfTheSameTickWins() {
        // сеть прислала два пакета в один тик: верен последний
        SourceTrack t = new SourceTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.record(12, at(25, 104));
        assertEquals(25, x(t, 12), 1e-9);
        assertEquals(12.5, x(t, 11), 1e-9);
    }

    @Test
    void longGapStartsHistoryOver() {
        // снаряд ушёл из слуха и вернулся: между этими точками он не летел по прямой
        SourceTrack t = new SourceTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(40, at(300, 130));
        assertEquals(40, t.start(), 1e-9);
        assertEquals(300, x(t, 30), 1e-9);
    }

    @Test
    void silentWhenNothingIsKnownForThatMoment() {
        // по пакетам — ждём следующего (период и тик на неровную доставку), дальше слушать нечего
        SourceTrack t = new SourceTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        assertTrue(t.covers(12 + S2C.Heard.PERIOD + 2));
        assertFalse(t.covers(12 + S2C.Heard.PERIOD + 2.5));
    }

    @Test
    void emissionTimeFollowsPacketPath() {
        // ракета 11.5 блока/тик по пакетам на слушателя в начале координат: |p(te)| = c·(now − te)
        SourceTrack t = new SourceTrack(ID, WeaponType.MISSILE, false);
        for (int k = 0; k <= 40; k += S2C.Heard.PERIOD) t.record(k, at(600 - 11.5 * k, k));
        double now = 40;
        double te = Acoustics.emissionTime(t, now, 0, 80, 0);
        assertEquals(Acoustics.SPEED * (now - te), Math.abs(x(t, te)), 1e-4);
    }
}
