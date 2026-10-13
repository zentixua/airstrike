package ua.zentix.replayscan.film;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пилот FPV на рельефе без мира: ровное поле, гряда, дым прежнего удара на пути прямого прохода, край чанков повтора.
 * Полёт должен облететь всё это, как пилот, который видит, и при этом снять удар.
 */
class PilotTest {
    private static final double ASPECT = 1080.0 / 1920, FOV = 80;
    private static final int A = 110, B = 290, HIT = 200;
    private static final Vec3 HIT_POS = new Vec3(0.5, 64, 0.5);

    /** Шахед с запада: 2,1 блока за тик, пикирует последние 40 тиков. */
    private static Subject shahed() {
        List<double[]> path = new ArrayList<>();
        for (int t = 40; t <= HIT; t += 2) {
            double x = 0.5 - 2.1 * (HIT - t), y = t < HIT - 40 ? 95 : 64 + 31 * (HIT - t) / 40.0;
            path.add(new double[]{t, x, y, 0.5});
        }
        return new Subject("00000000-0000-0000-0000-000000000001", 3.5, path, List.of(new Subject.Key(HIT, HIT_POS, 12)), null, 0);
    }

    private static Pilot pilot(Pilot.Terrain terrain, List<Plume> extra) {
        Subject s = shahed();
        Subject.Key main = Pilot.mainKey(s, A, B);
        assertNotNull(main);
        List<Plume> plumes = new ArrayList<>(extra);
        plumes.add(new Plume(HIT, HIT_POS, 12));
        return new Pilot(A, B, FOV, ASPECT, s, main, plumes, new Pilot.Grid(terrain, 0, 0, 400), 42);
    }

    /** Полёт по правилам дрона и проход снял удар; возвращает путь по тикам. */
    private static Pilot.Track check(Pilot p, Pilot.Flight f) {
        Pilot.Track tr = p.fly(f.controls());
        for (int t = A; t <= B; t++) {
            int i = t - tr.from();
            Vec3 pos = tr.pos()[i];
            double high = p.grid.high(pos.x, pos.z);
            assertFalse(Double.isNaN(high), f.name() + ": over chunks the client lacks at tick " + t + " " + pos);
            assertTrue(pos.y - high >= Pilot.CLEAR - 0.5, f.name() + ": " + (pos.y - high) + " blocks above ground at tick " + t);
            assertTrue(tr.vel()[i].length() <= Pilot.V_MAX + 0.1, f.name() + ": speed " + tr.vel()[i].length() + " at tick " + t);
            assertTrue(tr.acc()[i].length() <= Pilot.A_MAX * 1.5, f.name() + ": acceleration " + tr.acc()[i].length() + " at tick " + t);
            assertTrue(Math.abs(tr.roll()[i]) <= Pilot.ROLL + 1e-6);
            for (Plume pl : p.plumes) assertTrue(pl.clearance(pos, t) > 0, f.name() + ": in smoke of " + pl + " at tick " + t);
        }
        int seen = 0;
        for (int t = HIT; t <= HIT + FrameLog.KEY_CHECK; t++) {
            int i = t - tr.from();
            Vec3 k = Cameras.keyPoint(p.main, t);
            if (p.grid.seen(p.view(tr, i), k, p.main.size())) seen++;
        }
        assertTrue(seen >= FrameLog.KEY_CHECK, f.name() + ": the hit is in frame in " + seen + " of its first ticks");
        double d = tr.pos()[HIT - tr.from()].distanceTo(HIT_POS);
        assertTrue(d >= 18 && d <= 90, f.name() + ": " + d + " blocks from the hit when it goes off");
        return tr;
    }

    @Test
    void fliesLowPastTheHitOnFlatGround() {
        Pilot p = pilot((x, z) -> 64, List.of());
        List<Pilot.Flight> flights = p.plan(3);
        assertEquals(3, flights.size());
        for (Pilot.Flight f : flights) {
            Pilot.Track tr = check(p, f);
            double sum = 0;
            int n = 0;
            for (int t = A; t <= B; t += 2, n++) sum += tr.pos()[t - tr.from()].y - 64;
            assertTrue(sum / n <= Pilot.HIGH + 4, f.name() + ": " + sum / n + " blocks above the field on average — not low");
        }
        // разные: другой стороной или курсом
        assertFalse(flights.get(0).name().equals(flights.get(1).name()) && flights.get(1).name().equals(flights.get(2).name()));
    }

    @Test
    void climbsOverARidgeInsteadOfThroughIt() {
        // гряда высотой 40 блоков поперёк, в 40–48 блоках к северу от удара, и высотки к югу
        Pilot.Terrain t = (x, z) -> z >= -48 && z <= -40 ? 104 : z >= 35 && z <= 45 && Math.abs(x) < 60 ? 94 : 64;
        Pilot p = pilot(t, List.of());
        for (Pilot.Flight f : p.plan(2)) check(p, f);
    }

    @Test
    void keepsOutOfEarlierSmoke() {
        // дым удара 6 с назад в 30 блоках сбоку от места и ещё один к востоку: прямой проход прошёл бы сквозь них
        List<Plume> smoke = List.of(new Plume(HIT - 120, new Vec3(30.5, 64, 0.5), 18), new Plume(HIT - 60, new Vec3(0.5, 64, 35.5), 18),
                new Plume(HIT - 90, new Vec3(-25.5, 64, -30.5), 18));
        Pilot p = pilot((x, z) -> 64, smoke);
        for (Pilot.Flight f : p.plan(2)) {
            Pilot.Track tr = check(p, f);
            int smoky = 0, n = 0;
            for (int t = A; t <= HIT + 40; t += 2, n++) {
                Vec3 pos = tr.pos()[t - tr.from()];
                Vec3 q = p.focus(t);
                int at = t;
                if (smoke.stream().anyMatch(pl -> pl.blocks(pos, q, at))) smoky++;
            }
            assertTrue(smoky <= n / 5, f.name() + ": the hit is behind smoke in " + smoky + " of " + n + " samples");
        }
    }

    @Test
    void staysInsideTheReplayChunks() {
        // к востоку от x = 90 и к югу от z = 70 у клиента чанков нет
        Pilot.Terrain t = (x, z) -> x >= 90 || z >= 70 ? Double.NaN : 64;
        Pilot p = pilot(t, List.of());
        for (Pilot.Flight f : p.plan(2)) {
            Pilot.Track tr = check(p, f);
            int out = 0, n = 0;
            for (int s = A; s <= B; s += 2, n++) if (p.grid.lowOut(p.view(tr, s - tr.from())) > 0) out++;
            // вывод не пропускает план, где так больше 2 % кадров
            assertTrue(out <= n / 50, f.name() + ": frame bottom off chunks in " + out + " of " + n + " samples");
        }
    }

    @Test
    void sameSeedSameFlight() {
        Pilot.Flight one = pilot((x, z) -> 64, List.of()).plan(1).get(0), two = pilot((x, z) -> 64, List.of()).plan(1).get(0);
        assertEquals(one.name(), two.name());
        assertEquals(one.cost(), two.cost());
    }

    @Test
    void controlsFlyStraightAndInCircles() {
        Pilot p = pilot((x, z) -> 64, List.of());
        Vec3 at = new Vec3(10, 80, -5);
        // на запад (курс 90°) блок за тик
        Pilot.Track line = p.fly(p.controls.steady(at, 90, 1, 0, 0));
        for (int t : new int[]{p.t0, p.tp - 37, p.tp, p.tp + 50, p.t1}) {
            Vec3 want = at.add(-(t - p.tp), 0, 0);
            assertTrue(line.pos()[t - p.t0].distanceTo(want) < 1e-6, "place at " + t + ": " + line.pos()[t - p.t0]);
            assertTrue(line.vel()[t - p.t0].distanceTo(new Vec3(-1, 0, 0)) < 1e-6);
        }
        // вираж вправо 2° за тик: круг радиуса 1 / 0.0349 = 28.6 блока, за 45 тиков — четверть (с запада на север);
        // крен вправо — минус
        Pilot.Track turn = p.fly(p.controls.steady(at, 90, 1, 2, 0));
        double r = 1 / Math.toRadians(2);
        assertTrue(turn.pos()[p.tp + 45 - p.t0].distanceTo(at.add(-r, 0, -r)) < 0.3, "quarter circle: " + turn.pos()[p.tp + 45 - p.t0]);
        double far = 0;
        for (Vec3 q : turn.pos()) far = Math.max(far, q.distanceTo(at));
        assertEquals(2 * 28.65, far, 0.5, "circle diameter");
        int mid = p.tp - p.t0;
        assertTrue(turn.roll()[mid] < -10, "right turn banks right: " + turn.roll()[mid]);
    }

    @Test
    void plumeIsAColumnThatDriftsAndFades() {
        Plume pl = new Plume(100, new Vec3(0, 64, 0), 18);
        assertFalse(pl.alive(99));
        assertTrue(pl.clearance(new Vec3(0, 70, 0), 150) < 0, "inside at the base");
        assertTrue(pl.clearance(new Vec3(0, 64 + 31 + 14, 0), 300) > 0, "above the top");
        assertTrue(pl.clearance(new Vec3(40, 70, 0), 150) > 0, "beside");
        assertTrue(pl.base(500).x > 5, "drifts east with the wind");
        assertEquals(Double.POSITIVE_INFINITY, pl.clearance(new Vec3(0, 70, 0), 100 + Plume.LIFE + 1), "gone");
        // взгляд сквозь столб — закрыт; на сам удар (свой дым) — нет
        assertTrue(pl.blocks(new Vec3(-60, 70, 0), new Vec3(60, 70, 0), 200));
        assertFalse(pl.blocks(new Vec3(-60, 70, 0), new Vec3(5, 66, 0), 200));
        assertFalse(pl.blocks(new Vec3(-60, 70, 40), new Vec3(60, 70, 40), 200));
    }

    @Test
    void handsShakeAfterTheBlastReachesTheCamera() {
        Cameras.Path still = new Cameras.Path() {
            @Override
            public Cameras.Pose at(double t) {
                return new Cameras.Pose(new Vec3(0, 80, 0), 0, 10, FOV);
            }
        };
        Cameras.Path h = Hands.humanize(still, 1, List.of(new Hands.Blast(100, new Vec3(0, 64, 60), 18)), 0, 200);
        double before = 0, after = 0;
        for (int t = 80; t < 100; t++) before = Math.max(before, Math.abs(h.at(t).pitch() - 10));
        // волна до камеры (62 блока) — 3,6 тика
        for (int t = 104; t < 124; t++) after = Math.max(after, Math.abs(h.at(t).pitch() - 10));
        assertTrue(before < 1.2, "hand tremor only before the blast: " + before);
        assertTrue(after > before + 0.5, "kick after the blast: " + after + " vs " + before);
        for (int t = 0; t <= 200; t++) assertTrue(h.at(t).pos().distanceTo(new Vec3(0, 80, 0)) < 0.6, "place stays at tick " + t);
    }
}
