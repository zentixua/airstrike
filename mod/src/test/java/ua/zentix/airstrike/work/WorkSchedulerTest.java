package ua.zentix.airstrike.work;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.nuclear.world.WorkClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkSchedulerTest {
    private static final long MS = 1_000_000L;
    private static final long TOTAL = 30 * MS, GRID_CAP = 4 * MS;

    @Test
    void impactTakesAllUnlessLowerLanesWait() {
        assertEquals(TOTAL, WorkScheduler.impactBudget(TOTAL, false));
        assertEquals(20 * MS, WorkScheduler.impactBudget(TOTAL, true));
    }

    @Test
    void lowerLaneGetsWhatIsLeftUpToItsCap() {
        assertEquals(GRID_CAP, WorkScheduler.remaining(TOTAL, 0, GRID_CAP));
        assertEquals(2 * MS, WorkScheduler.remaining(TOTAL, 28 * MS, GRID_CAP));
        assertEquals(0, WorkScheduler.remaining(TOTAL, 45 * MS, GRID_CAP), "полоса выше ушла за срок — не отрицательный срок");
    }

    /**
     * Волна ядерки и каскад блэкаута одновременно (считающие часы, единица 1 мс, предел ядерки 30): блэкаут получает
     * свои 4 мс, а не одну единицу за тик; без работы у блэкаута ядерке — весь остаток.
     */
    @Test
    void nuclearLeavesGridItsShareWhileGridWaits() {
        long nukeCap = 30 * MS;
        assertEquals(TOTAL, WorkScheduler.nuclearBudget(TOTAL, 0, nukeCap, 0));
        assertEquals(26 * MS, WorkScheduler.nuclearBudget(TOTAL, 0, nukeCap, GRID_CAP));
        assertEquals(0, WorkScheduler.nuclearBudget(TOTAL, 28 * MS, nukeCap, GRID_CAP), "попадания и запас блэкаута съели срок");
        WorkClock nuclear = WorkClock.counting(MS), grid = WorkClock.counting(MS);
        for (int tick = 0; tick < 50; tick++) {
            nuclear.start(WorkScheduler.nuclearBudget(TOTAL, 0, nukeCap, GRID_CAP));
            while (nuclear.canStart()) nuclear.end(nuclear.begin());
            grid.start(WorkScheduler.remaining(TOTAL, nuclear.usedThisTickNanos(), GRID_CAP));
            while (grid.canStart()) grid.end(grid.begin());
            assertTrue(nuclear.usedThisTickNanos() + grid.usedThisTickNanos() <= TOTAL + 2 * MS);
        }
        assertTrue(grid.maxUnitsPerTick() >= 3, "блэкауту за тик " + grid.maxUnitsPerTick() + " единиц");
        assertTrue(nuclear.maxUnitsPerTick() <= 26, "ядерке за тик " + nuclear.maxUnitsPerTick() + " единиц");
    }

    /** Срок полосы уже вышел — одна единица всё равно: иначе полоса ниже вставала бы на весь залп. */
    @Test
    void laneWithNoBudgetStillDoesOneUnit() {
        WorkClock c = WorkClock.counting(MS);
        c.start(0);
        assertTrue(c.canStart());
        c.end(c.begin());
        assertFalse(c.canStart());
        assertTrue(c.workedThisTick());
        assertEquals(MS, c.usedThisTickNanos());
    }

    /**
     * Две полосы с работой без конца, единицы по 1 мс (считающие часы): за тик — не больше общего срока и одной единицы
     * на полосу; попадания — 2/3 общего, блэкаут — остальное до своего предела; оценки полос друг на друга не влияют.
     */
    @Test
    void tickStaysWithinTotalPlusOneUnitPerLane() {
        WorkClock impact = WorkClock.counting(MS), grid = WorkClock.counting(MS);
        for (int tick = 0; tick < 50; tick++) {
            impact.start(WorkScheduler.impactBudget(TOTAL, true));
            while (impact.canStart()) impact.end(impact.begin());
            grid.start(WorkScheduler.remaining(TOTAL, impact.usedThisTickNanos(), GRID_CAP));
            while (grid.canStart()) grid.end(grid.begin());
            long used = impact.usedThisTickNanos() + grid.usedThisTickNanos();
            assertTrue(used <= TOTAL + 2 * MS, "тик " + used / MS + " мс");
            assertTrue(grid.workedThisTick(), "блэкаут без единицы");
            assertTrue(impact.usedThisTickNanos() <= 20 * MS, "попадания " + impact.usedThisTickNanos() / MS + " мс");
        }
        assertEquals(19, impact.maxUnitsPerTick());
        assertEquals(3, grid.maxUnitsPerTick());
    }

    /** Долгий взрыв в полосе попаданий: блэкаут ниже делает свою единицу и не ждёт, пока оценка попаданий спадёт. */
    @Test
    void longImpactUnitDoesNotStarveGrid() {
        long[] now = {0};
        WorkClock impact = WorkClock.decaying(() -> now[0], 0.5);
        WorkClock grid = WorkClock.counting(MS);
        impact.start(WorkScheduler.impactBudget(TOTAL, true));
        long began = impact.begin();
        now[0] += 280 * MS;
        impact.end(began);
        assertFalse(impact.canStart());
        grid.start(WorkScheduler.remaining(TOTAL, impact.usedThisTickNanos(), GRID_CAP));
        assertTrue(grid.canStart());
        grid.end(grid.begin());
        assertFalse(grid.canStart(), "срок блэкаута вышел — только одна единица");
    }
}
