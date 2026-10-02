package ua.zentix.airstrike.nuclear.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkClockTest {
    private static final long MS = 1_000_000L;
    private static final long BUDGET = 4 * MS, UNIT = 300_000L;

    private final long[] now = {0};

    /** Единица, которая не успеет до срока, не начинается. */
    @Test
    void doesNotStartWorkThatWouldOverrun() {
        WorkClock c = new WorkClock();
        c.start(10_000_000L);
        assertTrue(c.canStart());
        c.record(20_000_000L);
        assertFalse(c.canStart(), "единица по 20 мс в бюджет 10 мс не влезает");
    }

    /** Даже после очень долгой единицы в новом тике одна единица делается: очередь не встаёт. */
    @Test
    void alwaysDoesOneUnitPerTick() {
        WorkClock c = new WorkClock();
        c.start(10_000_000L);
        c.record(50_000_000L);
        c.start(10_000_000L);
        assertTrue(c.canStart());
    }

    /** Оценка затухает: после одного тяжёлого столбца за полсотни лёгких снова берём работу впритык. */
    @Test
    void estimateDecays() {
        WorkClock c = new WorkClock();
        c.start(5_000_000_000L);
        c.record(8_000_000L);
        for (int i = 0; i < 60; i++) c.record(10_000L);
        c.start(1_000_000L);
        c.record(10_000L);
        assertTrue(c.canStart(), "оценка не затухла");
    }

    /** Считающие часы: очередь из 1000 единиц по 1 мс при бюджете 30 мс идёт по 29 за тик и не больше. */
    @Test
    void countingClockSplitsQueueByBudget() {
        WorkClock c = WorkClock.counting(1_000_000L);
        int left = 1000, ticks = 0;
        while (left > 0) {
            c.start(30_000_000L);
            int units = 0;
            while (left > 0 && c.canStart()) {
                c.end(c.begin());
                left--;
                units++;
            }
            ticks++;
            assertTrue(units >= 1 && units <= 30, "за тик " + units + " единиц");
        }
        assertEquals(ticks, c.ticksWorked());
        assertEquals(29, c.maxUnitsPerTick());
    }

    /** Тик очереди из ровных единиц по 0.3 мс; первая единица тика — {@code first} нс. Возвращает число единиц. */
    private int tick(WorkClock clock, long first) {
        clock.start(BUDGET);
        int units = 0;
        while (clock.canStart()) {
            long began = clock.begin();
            now[0] += units == 0 ? first : UNIT;
            clock.end(began);
            units++;
        }
        now[0] += 50 * MS;
        return units;
    }

    @Test
    void cheapUnitsFillTheBudget() {
        WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
        tick(clock, UNIT);
        int units = tick(clock, UNIT);
        // 0.3 мс единица, 4 мс бюджет: 13 единиц, последняя начинается до срока с запасом на оценку
        assertEquals(13, units);
        assertTrue(clock.maxTickNanos() <= BUDGET, "тик " + clock.maxTickNanos() + " нс");
    }

    /** Всплеск в 20 мс (пауза GC посреди единицы): тающая к тику оценка отпускает очередь за несколько тиков. */
    @Test
    void spikeReleasesWithinFewTicks() {
        WorkClock decaying = WorkClock.decaying(() -> now[0], 0.5);
        tick(decaying, 20 * MS);
        int ticks = 0;
        while (tick(decaying, UNIT) < 13) ticks++;
        assertTrue(ticks <= 4, "после всплеска " + ticks + " тиков неполной очереди");
        assertEquals(20 * MS, decaying.largestRecentNanos());
    }

    /** Оценка, тающая только по единицам, после того же всплеска держит очередь на одной единице за тик десятки тиков. */
    @Test
    void perUnitDecayAloneStaysSlow() {
        WorkClock plain = WorkClock.decaying(() -> now[0], 1);
        tick(plain, 20 * MS);
        int single = 0;
        while (tick(plain, UNIT) == 1) single++;
        assertTrue(single > 20, "одна единица за тик только " + single + " тиков");
    }

    @Test
    void statusReportsLastTick() {
        WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
        int units = tick(clock, UNIT);
        clock.start(BUDGET);
        assertEquals(units, clock.unitsLastTick());
        assertEquals(UNIT, clock.largestRecentNanos());
    }

    /**
     * Осмотр того, что ждёт, — не единица: время — в счёт тика, оценка и число единиц не меняются, первая единица тика
     * по-прежнему пускается; срок ({@link WorkClock#overdue}) — по настоящему времени. Считающие часы осмотр не двигает.
     */
    @Test
    void spentWorkCountsTimeNotUnits() {
        WorkClock clock = WorkClock.decaying(() -> now[0], 1);
        clock.start(BUDGET);
        long began = clock.begin();
        now[0] += 3 * MS;
        assertEquals(3 * MS, clock.spent(began));
        assertEquals(3 * MS, clock.usedThisTickNanos());
        assertFalse(clock.workedThisTick());
        assertEquals(0, clock.estimateNanos());
        assertTrue(clock.canStart(), "после осмотров первая единица тика не пускается");
        assertFalse(clock.overdue());
        now[0] += MS;
        assertTrue(clock.overdue(), "срок вышел, а часы его не видят");

        WorkClock counting = WorkClock.counting(MS);
        counting.start(BUDGET);
        for (int i = 0; i < 10; i++) counting.spent(counting.begin());
        assertFalse(counting.overdue(), "осмотры двинули считающие часы");
        assertEquals(0, counting.usedThisTickNanos());
    }

    @Test
    void tickDecayOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkClock.decaying(0));
        assertThrows(IllegalArgumentException.class, () -> WorkClock.decaying(1.5));
    }

    /** Часы внутри единицы: у считающих — свой счётчик по цене единицы, время очереди он не двигает; у настоящих — их время. */
    @Test
    void samplerCountsWithoutMovingQueueTime() {
        WorkClock counting = WorkClock.counting(UNIT);
        java.util.function.LongSupplier sample = counting.sampler();
        counting.start(BUDGET);
        long began = counting.begin();
        long a = sample.getAsLong(), b = sample.getAsLong();
        assertEquals(UNIT, b - a);
        assertEquals(UNIT, counting.end(began, 0));
        now[0] = 42;
        assertEquals(42, WorkClock.decaying(() -> now[0], 1).sampler().getAsLong());
    }
}
