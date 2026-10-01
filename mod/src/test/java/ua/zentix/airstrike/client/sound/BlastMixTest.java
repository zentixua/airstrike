package ua.zentix.airstrike.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ракурсы взрыва по расстоянию: громкость без ступенек, переход как одна запись, стык с дальней моделью на 640 блоках. */
class BlastMixTest {
    private static final BlastMix.Profile[] PROFILES = {BlastMix.DRONE, BlastMix.MISSILE, BlastMix.ROCKET};

    /** Ракурсы — одна запись (один вариант), поэтому складываются по амплитуде; громкость суммы, LUFS. */
    private static double direct(BlastMix.Profile p, BlastMix.Layers l) {
        double a = l.near() * Math.pow(10, p.lufs() / 20) + l.mid() * Math.pow(10, BlastMix.MID_LUFS / 20)
                + l.far() * Math.pow(10, BlastMix.FAR_LUFS / 20);
        return 20 * Math.log10(a);
    }

    @Test
    void crossfadeKeepsTheLoudnessOfOneRecording() {
        for (BlastMix.Profile p : PROFILES) {
            for (double d = 0; d <= Outdoor.NEAR; d += 2.5) {
                BlastMix.Layers l = BlastMix.at(p, d);
                assertEquals(BlastMix.loudness(p, d), direct(p, l), 0.01, "громкость ракурсов на " + d + " бл");
            }
        }
    }

    @Test
    void loudnessIsFullUpCloseAndFallsThreeDecibelsPerDoubling() {
        for (BlastMix.Profile p : PROFILES) {
            assertEquals(p.lufs(), BlastMix.loudness(p, 0), 1e-9);
            assertEquals(p.lufs(), BlastMix.loudness(p, p.flat()), 1e-9);
            assertEquals(-10 * Math.log10(2), BlastMix.loudness(p, 4 * p.flat()) - BlastMix.loudness(p, 2 * p.flat()), 1e-9);
            double prev = BlastMix.loudness(p, 0);
            for (double d = 1; d <= 2 * Outdoor.NEAR; d++) {
                double l = BlastMix.loudness(p, d);
                assertTrue(l <= prev + 1e-12, "громче на " + d + " бл");
                prev = l;
            }
        }
        // крупнее заряд — не тише на том же расстоянии
        for (double d = 0; d <= Outdoor.NEAR; d += 10) {
            assertTrue(BlastMix.loudness(BlastMix.MISSILE, d) >= BlastMix.loudness(BlastMix.DRONE, d));
            assertTrue(BlastMix.loudness(BlastMix.DRONE, d) >= BlastMix.loudness(BlastMix.ROCKET, d));
        }
    }

    @Test
    void edgeOfTheNearModelIsTheFarModel() {
        for (BlastMix.Profile p : PROFILES) {
            BlastMix.Layers l = BlastMix.at(p, Outdoor.NEAR);
            assertEquals(BlastMix.v640(p), l.far(), 1e-6, "на 640 блоках — только дальний ракурс, громкость v640");
            assertEquals(0, l.near() + l.mid() + l.sub() + l.echo(), 1e-9);
            // дальше: громкость дальней модели (ночь, открытый путь, сухо) по тому же закону — ни ступеньки, ни излома
            for (double d : new double[]{Outdoor.NEAR - 30, Outdoor.NEAR - 1}) {
                Outdoor.Path path = new Outdoor.Path(2 * Outdoor.NEAR - d, 0, 2, 1.7, 0.5, 0.5, 0, 0, 0);
                double near = 20 * Math.log10(BlastMix.at(p, d).far()), far = 20 * Math.log10(Outdoor.volume(1e6, BlastMix.v640(p), path));
                double edge = 20 * Math.log10(BlastMix.v640(p));
                assertEquals(near - edge, edge - far, 0.05, "наклон с обеих сторон 640 блоков, " + d);
            }
        }
        assertEquals(0.30, BlastMix.v640(BlastMix.DRONE), 0.01);
        assertEquals(0.36, BlastMix.v640(BlastMix.MISSILE), 0.01);
        assertEquals(0.19, BlastMix.v640(BlastMix.ROCKET), 0.01);
    }

    @Test
    void layersStayWithinMinecraftVolume() {
        for (BlastMix.Profile p : PROFILES) {
            for (double d = 0; d <= Outdoor.NEAR; d += 1) {
                BlastMix.Layers l = BlastMix.at(p, d);
                for (float v : new float[]{l.near(), l.mid(), l.far(), l.sub(), l.echo()}) assertTrue(v >= 0 && v <= 1, "на " + d + " бл: " + l);
            }
        }
    }

    @Test
    void closeUpIsTheNearPerspectiveWithBodyAndEcho() {
        BlastMix.Layers l = BlastMix.at(BlastMix.DRONE, 10);
        assertEquals(1, l.near(), 1e-6);
        assertEquals(0, l.mid() + l.far(), 1e-9);
        assertEquals(1, l.sub(), 1e-6);
        // эхо вблизи на 8 дБ тише прямого звука, вдали — на 3
        assertEquals(BlastMix.NEAR_LUFS + BlastMix.ECHO_CLOSE - BlastMix.TAIL_LUFS, 20 * Math.log10(l.echo()), 1e-4);
        BlastMix.Layers open = BlastMix.at(BlastMix.DRONE, 200);
        assertEquals(BlastMix.loudness(BlastMix.DRONE, 200) + BlastMix.ECHO_FAR - BlastMix.TAIL_LUFS, 20 * Math.log10(open.echo()), 1e-4);
        assertEquals(0, open.sub(), 1e-9);
        // средняя дистанция — средний ракурс; у РСЗО его нет: свой разрыв до дальнего
        BlastMix.Layers mid = BlastMix.at(BlastMix.DRONE, 160);
        assertEquals(0, mid.near() + mid.far(), 1e-9);
        assertEquals(BlastMix.loudness(BlastMix.DRONE, 160) - BlastMix.MID_LUFS, 20 * Math.log10(mid.mid()), 1e-4);
        assertEquals(0, BlastMix.at(BlastMix.ROCKET, 100).mid(), 1e-9);
    }

    @Test
    void pitchIsTheSameForTheSameSeedAndVariesAcrossSeeds() {
        for (BlastMix.Profile p : PROFILES) {
            double min = 9, max = 0;
            for (long seed = 0; seed < 1000; seed++) {
                float a = BlastMix.pitch(p, seed);
                assertEquals(a, BlastMix.pitch(p, seed));
                min = Math.min(min, a);
                max = Math.max(max, a);
            }
            assertTrue(min >= p.pitch() * (1 - BlastMix.JITTER) - 1e-6 && max <= p.pitch() * (1 + BlastMix.JITTER) + 1e-6);
            assertTrue(max - min > p.pitch() * BlastMix.JITTER, "тон от взрыва к взрыву разный");
        }
    }
}
