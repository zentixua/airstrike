package ua.zentix.airstrike.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticsTest {
    /** Источник летит по оси X с постоянной скоростью v (блоков/тик), в момент 0 — в точке x0. */
    private static Acoustics.Path straight(double x0, double v) {
        return new Acoustics.Path() {
            @Override
            public void at(double t, double[] out) {
                out[0] = x0 + v * t;
                out[1] = 0;
                out[2] = 0;
            }

            @Override
            public double start() {
                return -1000;
            }
        };
    }

    @Test
    void stillSourceIsHeardAfterDistanceOverSpeed() {
        // неподвижный источник в 343 блоках: звук идёт ровно 20 тиков (1 с)
        double te = Acoustics.emissionTime(straight(343, 0), 100, 0, 0, 0);
        assertEquals(100 - 343 / Acoustics.SPEED, te, 1e-6);
    }

    @Test
    void emittedSoundReachesListenerExactlyThen() {
        // ракета 11.5 блока/тик летит на слушателя: время излучения удовлетворяет |p(t) - L| = c (now - t)
        Acoustics.Path p = straight(500, -11.5);
        double now = 30;
        double te = Acoustics.emissionTime(p, now, 0, 0, 0);
        double[] at = new double[3];
        p.at(te, at);
        assertEquals(Math.abs(at[0]), Acoustics.SPEED * (now - te), 1e-3);
    }

    @Test
    void approachingSourceSoundsHigherRecedingLower() {
        double c = Acoustics.SPEED;
        // приближается со скоростью c/2: частота ×2; удаляется с c/2: ×2/3
        assertEquals(2.0, Acoustics.doppler(c / 2, 0, 0, 1, 0, 0), 1e-9);
        assertEquals(2.0 / 3.0, Acoustics.doppler(-c / 2, 0, 0, 1, 0, 0), 1e-9);
        // поперёк луча — без сдвига
        assertEquals(1.0, Acoustics.doppler(0, 0, 5, 1, 0, 0), 1e-9);
    }

    @Test
    void dopplerIsBoundedNearTheSpeedOfSound() {
        double f = Acoustics.doppler(Acoustics.SPEED * 5, 0, 0, 1, 0, 0);
        assertTrue(f <= 10.0 + 1e-9, "не выше ×10 даже у сверхзвукового источника: " + f);
    }

    @Test
    void gainFallsAsOneOverDistanceWithFloorAndCutoff() {
        assertEquals(1.0, Acoustics.gain(30, 60, 0.12, 300), 1e-9);
        assertEquals(0.5, Acoustics.gain(120, 60, 0.12, 300), 1e-9);
        assertEquals(0.12, Acoustics.gain(290, 20, 0.12, 300), 1e-9);
        assertEquals(0.0, Acoustics.gain(400, 60, 0.12, 300), 1e-9);
    }

    @Test
    void airflowIsLoudestOnTheApproach() {
        // тот же снаряд в 100 блоках: идёт на слушателя — громко, уходит — треть того
        double toward = Acoustics.airflow(100, 4, 4, 50, 4);
        double away = Acoustics.airflow(100, 4, -4, 50, 4);
        assertEquals(0.5, toward, 1e-9);
        assertEquals(0.3, away / toward, 1e-9);
    }

    @Test
    void airflowFadesWithSpeedAndDistance() {
        // у вершины дуги вдвое медленнее — вчетверо тише; дальше среза и затухания — тишина
        assertEquals(0.25, Acoustics.airflow(100, 2, 2, 50, 4) / Acoustics.airflow(100, 4, 4, 50, 4), 1e-9);
        assertEquals(0, Acoustics.airflow(ua.zentix.airstrike.strike.Hearing.AIRFLOW + ua.zentix.airstrike.strike.Hearing.FADE, 4, 4, 50, 4), 1e-12);
        assertEquals(0, Acoustics.airflow(10, 0, 0, 50, 4), 1e-12);
    }
}
