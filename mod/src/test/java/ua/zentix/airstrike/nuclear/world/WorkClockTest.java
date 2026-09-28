package ua.zentix.airstrike.nuclear.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkClockTest {
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
}
