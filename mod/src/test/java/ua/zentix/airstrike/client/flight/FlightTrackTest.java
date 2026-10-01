package ua.zentix.airstrike.client.flight;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.sound.Acoustics;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Путь снаряда по пакетам сервера: между пакетами — по прямой, разрыв — заново, без данных — молчать. */
class FlightTrackTest {
    private static final UUID ID = UUID.randomUUID();

    private static S2C.FarFlight at(double x, int phaseAge) {
        return at(x, 11.5, phaseAge);
    }

    private static S2C.FarFlight at(double x, double vx, int phaseAge) {
        return new S2C.FarFlight(ID, WeaponType.MISSILE.id(), false, false, true, new Vec3(x, 80, 0), new Vec3(vx, 0, 0), 0, 0,
                FlightPhase.CRUISE.ordinal(), phaseAge, new Vec3(500, 80, 0));
    }

    private static double x(FlightTrack t, double time) {
        double[] p = new double[3];
        t.at(time, p);
        return p[0];
    }

    @Test
    void ticksBetweenPacketsFollowStraightLine() {
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
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
    void velocityIsWhatServerReportedNotUnevenArrival() {
        // пакет тика сервера 11 опоздал на тик клиента: положение стоит, потом прыгает на два шага — скорость ровная
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(11, at(0, 100));
        t.record(12, at(23, 102));
        for (double time = 10; time <= 12; time += 0.25) assertEquals(11.5, t.velocity(time).x, 1e-9);
    }

    @Test
    void velocityBetweenTicksIsInterpolated() {
        FlightTrack t = new FlightTrack(ID, WeaponType.ROCKET, false);
        t.record(10, at(0, 4, 100));
        t.record(12, at(9, 5, 102));
        assertEquals(4.5, t.velocity(11).x, 1e-9);
        assertEquals(4.75, t.velocity(11.5).x, 1e-9);
        assertEquals(5, t.velocity(20).x, 1e-9, "за краем истории — последняя известная");
    }

    @Test
    void laterPacketOfTheSameTickWins() {
        // сеть прислала два пакета в один тик: верен последний
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.record(12, at(25, 104));
        assertEquals(25, x(t, 12), 1e-9);
        assertEquals(12.5, x(t, 11), 1e-9);
    }

    @Test
    void longGapStartsHistoryOver() {
        // снаряд ушёл из слуха и вернулся: между этими точками он не летел по прямой
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(40, at(300, 130));
        assertEquals(40, t.start(), 1e-9);
        assertEquals(300, x(t, 30), 1e-9);
    }

    @Test
    void silentWhenNothingIsKnownForThatMoment() {
        // по пакетам — ждём следующего (период и тик на неровную доставку), дальше слушать нечего
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        assertTrue(t.covers(12 + S2C.FarFlights.PERIOD + 2));
        assertFalse(t.covers(12 + S2C.FarFlights.PERIOD + 2.5));
    }

    @Test
    void emissionTimeFollowsPacketPath() {
        // ракета 11.5 блока/тик по пакетам на слушателя в начале координат: |p(te)| = c·(now − te)
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        for (int k = 0; k <= 40; k += S2C.FarFlights.PERIOD) t.record(k, at(600 - 11.5 * k, k));
        double now = 40;
        double te = Acoustics.emissionTime(t, now, 0, 80, 0);
        assertEquals(Acoustics.SPEED * (now - te), Math.abs(x(t, te)), 1e-4);
    }

    @Test
    void predictionRunsAheadByLastVelocityButNotPastTheAim() {
        // следующий пакет ещё в пути: снаряд вдали идёт дальше по скорости, но сквозь цель (x = 500) не летит
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        double[] p = new double[3];
        assertTrue(t.predict(13.5, p));
        assertEquals(23 + 11.5 * 1.5, p[0], 1e-9);
        t.record(14, at(490, 104));
        assertTrue(t.predict(16, p));
        assertEquals(500, p[0], 1e-9);
        assertFalse(t.predict(14 + S2C.FarFlights.PERIOD + 3, p), "дальше срока пакета данных нет");
    }

    @Test
    void blastEndsTheFlightWhenItHappened() {
        // последний пакет о дальней ракете дошёл за 6 тиков до пакета взрыва (дольше ожидания следующего пакета): звук
        // тянется до самого взрыва по скорости, а не стихает раньше — иначе перед взрывом пауза тишины
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.record(14, at(46, 104));
        assertFalse(t.covers(19), "без пакета взрыва — данных нет");
        t.impact(20);
        assertTrue(t.covers(19.5));
        assertTrue(t.covers(20));
        assertFalse(t.covers(20.5), "после взрыва мотора нет");
        assertEquals(46 + 11.5 * 6, x(t, 20), 1e-9, "до взрыва — по скорости последней записи");
    }

    @Test
    void lateBlastDoesNotStretchAnOldPath() {
        // пакетов давно нет (ушёл из слуха, сервер встал): взрыв не тянет звук дальше разрыва, после которого путь — заново
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.impact(60);
        assertTrue(t.covers(20));
        assertFalse(t.covers(20.5));
        // конец полёта по тишине (взрыва не было) — сразу после последней записи
        FlightTrack s = new FlightTrack(ID, WeaponType.MISSILE, false);
        s.record(10, at(0, 100));
        s.die(60);
        assertTrue(s.covers(11));
        assertFalse(s.covers(11.5));
    }

    @Test
    void trailIsDrawnOnlyOverServerSamples() {
        // точки по пакетам — для шлейфа вдали; сущность у клиента рисует свой шлейф частицами
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        assertTrue(t.fromServer(11));
        assertTrue(t.fromServer(12));
        assertFalse(t.fromServer(13));
        assertFalse(t.fromServer(9));
    }
}
