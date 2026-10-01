package ua.zentix.airstrike.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.ClientWeaponSpec;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarLook;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarTrail;
import ua.zentix.airstrike.client.ClientWeaponSpec.Flame;
import ua.zentix.airstrike.client.flight.FlightTrack;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Снаряд вдали: тень корпуса и след при любой дальности, факел днём и ночью, точки шлейфа по фазам и пакетам. */
class FarFlightViewTest {
    private static final double PIXEL = Math.toRadians(70) / 1080;
    private static final UUID ID = UUID.randomUUID();

    private static FarLook far(WeaponType weapon) {
        return ClientWeaponSpec.of(weapon).airframe(false).far();
    }

    @Test
    void bodyIsTrueSizeAndCoversTheSameSky() {
        double[] out = new double[2];
        for (WeaponType w : WeaponType.values()) {
            FarLook look = far(w);
            double r = Math.sqrt(look.area() / Math.PI);
            for (double d : new double[]{50, 300, 1000, 4000, 8000}) {
                FarFlightView.body(look.area(), d, PIXEL, out);
                assertEquals(Math.max(r, 0.5 * Sight.MIN_PIXELS * PIXEL * d), out[0], 1e-9, w + " на " + d + ": настоящий размер или пятно");
                assertEquals(r * r, out[1] * out[0] * out[0], 1e-9, w + " на " + d + ": закрыто то же небо");
            }
        }
    }

    @Test
    void droneFadesWithDistanceLikeACamera() {
        // силуэт шахеда (размах 2,5 м) — круг 1,07 м: за 1,5 км — 1,3 px, пятно в 1,5 px чуть бледнее; за 4 км — бледная точка
        double[] out = new double[2];
        FarFlightView.body(far(WeaponType.DRONE).area(), 1500, PIXEL, out);
        assertEquals(0.5 * Sight.MIN_PIXELS * PIXEL * 1500, out[0], 1e-9);
        assertTrue(out[1] > 0.6 && out[1] < 0.8, "за 1,5 км: " + out[1]);
        FarFlightView.body(far(WeaponType.DRONE).area(), 4000, PIXEL, out);
        assertTrue(out[1] > 0.05 && out[1] < 0.15, "за 4 км: " + out[1]);
        // B-2 (размах 52 м) за 8 км — больше пятна: настоящего размера и сплошной
        FarFlightView.body(far(WeaponType.BUNKER).area(), 8000, PIXEL, out);
        assertEquals(1, out[1], 1e-12);
    }

    @Test
    void thinTrailKeepsItsCoverage() {
        double[] out = new double[2];
        FarFlightView.ribbon(4, 100, PIXEL, out);
        assertEquals(2 * FarSprites.RIBBON, out[0], 1e-9, "вблизи — своей ширины");
        assertEquals(1, out[1], 1e-12);
        FarFlightView.ribbon(1, 5000, PIXEL, out);
        double drawn = out[0] / FarSprites.RIBBON;
        assertEquals(0.75 * PIXEL * 5000, drawn, 1e-9, "уже пикселя — полтора пикселя");
        assertEquals(1, 2 * drawn * out[1], 1e-9, "и бледнее во столько же раз");
    }

    @Test
    void rocketMotorIsSparkByDayAndGlareAtNight() {
        Flame flame = far(WeaponType.ROCKET).stage(FlightPhase.BOOST).flame();
        assertNotNull(flame);
        double d = 2000, t = Sight.transmittance(d, Sight.CLEAR);
        double[] day = new double[5], night = new double[5];
        Sight.light(flame.radius(), Sight.adapted(flame.brightness(), 1) * t, d, PIXEL, day);
        Sight.light(flame.radius(), Sight.adapted(flame.brightness(), 0.17) * t, d, PIXEL, night);
        assertTrue(day[2] < 1 && day[4] == 0, "днём — искра без вуали: " + day[2]);
        assertTrue(night[2] > 1 && night[4] / d > 0.01, "ночью — блик и вуаль: " + night[2] + ", " + Math.toDegrees(night[4] / d) + "°");
        // ночью видно и за 8 км
        double far = 8000;
        Sight.light(flame.radius(), Sight.adapted(flame.brightness(), 0.17) * Sight.transmittance(far, Sight.CLEAR), far, PIXEL, night);
        assertTrue(night[2] > 1, "за 8 км ночью: " + night[2]);
    }

    @Test
    void cruiseMissileEngineBarelyGlowsAtNight() {
        // выхлоп ТРД — не пламя: ночью искра, без вуали
        Flame flame = far(WeaponType.MISSILE).stage(FlightPhase.CRUISE).flame();
        assertNotNull(flame);
        double d = 2000, t = Sight.transmittance(d, Sight.CLEAR);
        double[] out = new double[5];
        Sight.light(flame.radius(), Sight.adapted(flame.brightness(), 0.17) * t, d, PIXEL, out);
        assertEquals(0, out[4], "без вуали");
    }

    // ---------------------------------------------------------------- точки шлейфа по пути

    private static S2C.FarFlight packet(WeaponType weapon, long tick, FlightPhase phase) {
        return new S2C.FarFlight(ID, weapon.id(), false, false, true, new Vec3(tick * 3.0, 80 + tick, 0), new Vec3(3, 1, 0), 0, 0,
                phase.ordinal(), 0, new Vec3(9000, 80, 0));
    }

    /** Путь по пакетам раз в 2 тика: фаза — по тикам. */
    private static FlightTrack track(WeaponType weapon, long from, long to, java.util.function.LongFunction<FlightPhase> phase) {
        FlightTrack t = new FlightTrack(ID, weapon, false);
        for (long tick = from; tick <= to; tick += 2) t.record(tick, packet(weapon, tick, phase.apply(tick)));
        return t;
    }

    private record Point(long serial, long tick, FarTrail style, long prev, double x) {}

    private static List<Point> points(TrailPoints p) {
        List<Point> out = new ArrayList<>();
        for (long s = p.first(); s < p.end(); s++) {
            int i = TrailPoints.index(s);
            out.add(new Point(s, p.birth[i], p.style[i], p.prev[i], p.x[i]));
        }
        return out;
    }

    @Test
    void rocketTrailRunsWhileMotorBurnsAndEndsAtBurnout() {
        FarLook look = far(WeaponType.ROCKET);
        FarTrail trail = look.stage(FlightPhase.BOOST).trail();
        FlightTrack t = track(WeaponType.ROCKET, 10, 90, tick -> tick <= 50 ? FlightPhase.BOOST : FlightPhase.CRUISE);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 90, p);
        List<Point> pts = points(p);
        // каждые 2 тика с 10 по 50, и точка выгорания в тике 51 (он уже в фазе следующего пакета)
        assertEquals(22, pts.size());
        for (int i = 0; i < pts.size(); i++) {
            Point pt = pts.get(i);
            assertSame(trail, pt.style);
            assertEquals(i == 21 ? 51 : 10 + 2 * i, pt.tick);
            assertEquals(pt.tick * 3.0, pt.x, 1e-9, "место — по пути");
            assertEquals(i == 0 ? TrailPoints.NONE : pts.get(i - 1).serial, pt.prev, "одна лента");
        }
    }

    @Test
    void boosterTrailHandsOverToEngineExhaust() {
        FarLook look = far(WeaponType.DRONE);
        FlightTrack t = track(WeaponType.DRONE, 0, 40, tick -> tick <= 20 ? FlightPhase.BOOST : FlightPhase.CRUISE);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 40, p);
        List<Point> pts = points(p);
        FarTrail booster = look.stage(FlightPhase.BOOST).trail(), exhaust = look.stage(FlightPhase.CRUISE).trail();
        int switched = 0;
        for (int i = 1; i < pts.size(); i++) {
            Point a = pts.get(i - 1), b = pts.get(i);
            if (a.style == booster && b.style == exhaust) {
                switched++;
                assertEquals(a.tick, b.tick, "лента ускорителя кончается там, где начинается выхлоп");
                assertEquals(TrailPoints.NONE, b.prev, "это другая лента");
            }
        }
        assertEquals(1, switched);
        assertSame(exhaust, pts.getLast().style);
    }

    @Test
    void samplingInStepsGivesTheSameTrail() {
        FlightTrack t = track(WeaponType.NUKE, 0, 200, tick -> tick < 40 ? FlightPhase.IGNITION : FlightPhase.BOOST);
        TrailPoints once = new TrailPoints(), steps = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 200, once);
        FarFlightView.Far f = new FarFlightView.Far(t, 1);
        for (long h = 0; h <= 200; h += 3) FarFlightView.sample(f, h, steps);
        FarFlightView.sample(f, 200, steps);
        assertEquals(points(once), points(steps));
    }
}
