package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Подробность пелены по времени на видеокарте: ступени, бюджет, спуск сразу, подъём с запасом и не выше настройки. */
class PallDetailTest {
    private static final long MS = 1_000_000L;
    private static final int LOW = PallPass.LADDER.length - 1;

    /** Кадры по 16 мс с одним временем на видеокарте, {@code frames} штук; часы — в {@code clock[0]}. */
    private static void frames(PallPass.Detail d, double gpuMs, int frames, long[] clock) {
        for (int i = 0; i < frames; i++) {
            clock[0] += 16 * MS;
            d.sample(gpuMs, d.generation, clock[0]);
        }
    }

    /** Ступени — от HIGH до LOW, каждая дешевле прошлой не больше чем вдвое; MEDIUM среди них. */
    @Test
    void ladderRunsFromHighToLow() {
        assertEquals(0, PallPass.Quality.HIGH.step());
        assertEquals(LOW, PallPass.Quality.LOW.step());
        int medium = PallPass.Quality.MEDIUM.step();
        assertTrue(medium > 0 && medium < LOW);
        assertEquals(-1, PallPass.Quality.OFF.step());
        for (int i = 1; i < PallPass.LADDER.length; i++) {
            double ratio = PallPass.LADDER[i].cost() / PallPass.LADDER[i - 1].cost();
            assertTrue(ratio >= 0.5 && ratio < 0.9, "ступень " + i + ": " + ratio);
            assertTrue(PallPass.LADDER[i].factor() <= 4 && PallPass.LADDER[i].steps() >= 20, "ниже LOW");
        }
    }

    /** Дольше бюджета — сразу вниз на ступень, что влезет; очень долго (программный OpenGL) — до LOW, но не ниже. */
    @Test
    void overBudgetStepsDownButNotBelowLow() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        d.ceiling(PallPass.Quality.MEDIUM.step());
        frames(d, 3.5, 40, clock);
        assertTrue(d.level > PallPass.Quality.MEDIUM.step(), "3,5 мс при бюджете 2 — ниже");
        assertTrue(d.level < LOW, "3,5 мс — не сразу до LOW");
        frames(d, 150, 200, clock);
        assertEquals(LOW, d.level);
        frames(d, 150, 200, clock);
        assertEquals(LOW, d.level);
    }

    /** В бюджете — ступень держится; запас — вверх, но не сразу после спуска, по ступени и не выше настройки. */
    @Test
    void roomStepsUpWithHysteresis() {
        PallPass.Detail d = new PallPass.Detail();
        long[] clock = {0};
        int medium = PallPass.Quality.MEDIUM.step();
        d.ceiling(medium);
        frames(d, 1.8, 200, clock);
        assertEquals(medium, d.level, "1,8 мс — в бюджете");
        frames(d, 6, 40, clock);
        int down = d.level;
        assertTrue(down > medium);
        // запас большой, но после спуска — пауза
        frames(d, 0.2, 60, clock);
        assertEquals(down, d.level, "сразу после спуска не поднимается");
        frames(d, 0.2, 400, clock);
        assertTrue(d.level < down, "запас — вверх");
        frames(d, 0.2, 4000, clock);
        assertEquals(medium, d.level, "не выше настройки");
        // на нижних ступенях 1,9 мс; ступень выше по цене не влезет в 0,7 бюджета — стоит
        d.ceiling(PallPass.Quality.HIGH.step());
        assertEquals(0, d.level, "новая настройка — сразу её ступень");
        frames(d, 3, 40, clock);
        assertTrue(d.level > 0);
        frames(d, 1.9, 100, clock);
        int level = d.level;
        frames(d, 1.9, 2000, clock);
        assertEquals(level, d.level);
    }

    /** Замеры прежней ступени не считаются, первый кадр новой — тоже; без настройки — ничего. */
    @Test
    void staleSamplesIgnored() {
        PallPass.Detail d = new PallPass.Detail();
        assertFalse(d.sample(100, d.generation, 0));
        d.ceiling(PallPass.Quality.MEDIUM.step());
        int old = d.generation - 1;
        for (int i = 0; i < 100; i++) assertFalse(d.sample(100, old, i * 16 * MS));
        assertEquals(PallPass.Quality.MEDIUM.step(), d.level);
    }
}
