package ua.zentix.airstrike.guidance;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полоса рельефа над путём ({@link Corridor}): ни одна колонка в полосе не пропущена; набор высоты до дальних колонок. */
class CorridorTest {
    private static final double HALF_WIDTH = 1.5;

    /**
     * На случайных ломаных спрошена каждая колонка, середина которой ближе полуширины к одному из отрезков (поперёк него:
     * полоса — без скруглений на концах, позади начала пути колонки снаряду не нужны).
     */
    @Test
    void asksEveryColumnInBand() {
        SplittableRandom r = new SplittableRandom(7);
        for (int run = 0; run < 500; run++) {
            int points = 1 + r.nextInt(5);
            double[] track = new double[2 * points];
            double x = r.nextDouble(-50, 50), z = r.nextDouble(-50, 50);
            for (int i = 0; i < points; i++) {
                track[2 * i] = x;
                track[2 * i + 1] = z;
                x += r.nextDouble(-30, 30);
                z += r.nextDouble(-30, 30);
            }
            Set<Long> asked = new HashSet<>();
            Corridor.highest(track, HALF_WIDTH, Corridor.Climb.NONE, (cx, cz) -> {
                asked.add(key(cx, cz));
                return 0;
            });
            for (int cx = -120; cx <= 120; cx++) {
                for (int cz = -120; cz <= 120; cz++) {
                    if (distance(track, cx + 0.5, cz + 0.5) <= HALF_WIDTH) {
                        assertTrue(asked.contains(key(cx, cz)), "колонка " + cx + " " + cz + " не спрошена, путь " + java.util.Arrays.toString(track));
                    }
                }
            }
        }
    }

    /** Тонкая колонка в полосе — её высота, а не соседей. */
    @Test
    void thinColumnCounts() {
        double[] track = {0.3, 0, 0.3, 256};
        for (int at = 0; at < 256; at += 7) {
            int mz = at;
            double h = Corridor.highest(track, HALF_WIDTH, Corridor.Climb.NONE, (x, z) -> x == 1 && z == mz ? 104 : 64);
            assertEquals(104, h, "колонка в " + at);
        }
    }

    /** Колонка дальше пути реакции учитывается за вычетом набора до неё, ближняя — целиком. */
    @Test
    void farColumnLessClimb() {
        double[] track = {0.5, 0, 0.5, 200};
        Corridor.Climb climb = new Corridor.Climb(10, 0.1);
        double far = Corridor.highest(track, HALF_WIDTH, climb, (x, z) -> x == 0 && z == 100 ? 150 : 0);
        assertEquals(150 - 0.1 * (100.5 - 10), far, 1e-9);
        double near = Corridor.highest(track, HALF_WIDTH, climb, (x, z) -> x == 0 && z == 5 ? 150 : 0);
        assertEquals(150, near, 1e-9);
        // путь — вдоль ломаной: на втором отрезке он считается от начала первого
        double[] bent = {0.5, 0, 0.5, 50, 50.5, 50};
        double corner = Corridor.highest(bent, HALF_WIDTH, climb, (x, z) -> x == 30 && z == 49 ? 150 : 0);
        assertEquals(150 - 0.1 * (50 + 30 - 10), corner, 1e-9);
    }

    /** Одна точка — квадрат вокруг неё. */
    @Test
    void pointIsSquare() {
        double h = Corridor.highest(new double[] {10.5, 10.5}, HALF_WIDTH, Corridor.Climb.NONE, (x, z) -> x == 10 && z == 11 ? 90 : 64);
        assertEquals(90, h);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Расстояние от точки поперёк до ближайшего отрезка ломаной, на который она проецируется; одна точка — по квадрату. */
    private static double distance(double[] track, double px, double pz) {
        if (track.length == 2) return Math.max(Math.abs(px - track[0]), Math.abs(pz - track[1]));
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 3 < track.length; i += 2) {
            double ax = track[i], az = track[i + 1], dx = track[i + 2] - ax, dz = track[i + 3] - az;
            double t = ((px - ax) * dx + (pz - az) * dz) / Math.max(1e-12, dx * dx + dz * dz);
            if (t >= 0 && t <= 1) best = Math.min(best, Math.hypot(px - ax - t * dx, pz - az - t * dz));
        }
        return best;
    }
}
