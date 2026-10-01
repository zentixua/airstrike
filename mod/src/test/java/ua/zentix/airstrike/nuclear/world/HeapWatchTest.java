package ua.zentix.airstrike.nuclear.world;

import org.junit.jupiter.api.Test;

import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Замер кучи для руин заранее ({@link HeapWatch#freedOld}): только старое поколение и только после сборки, которая
 * его освободила. Числа — из лога сборщика игры Артёма 01.10.2026 (ZGC с поколениями, куча 12 ГБ): большой цикл
 * GC(3730) — старое 6342 → 5878 МБ при живых 5386, молодое «после» 1190 МБ при живых 56; малый GC(3739) — старое
 * не освобождал, молодое «после» 1348 МБ при живых 86.
 */
class HeapWatchTest {
    private static final long MB = 1L << 20;
    private static final String OLD = "ZGC Old Generation", YOUNG = "ZGC Young Generation";

    private static MemoryUsage used(long mb) {
        return new MemoryUsage(0, mb * MB, 12288 * MB, 12288 * MB);
    }

    @Test
    void majorCycleMeasuresOldOnly() {
        long live = HeapWatch.freedOld(Map.of(OLD, used(6342), YOUNG, used(1840)), Map.of(OLD, used(5878), YOUNG, used(1190)), Set.of(OLD));
        assertEquals(5878 * MB, live, "большой цикл: старое после сборки, без молодого");
    }

    @Test
    void minorCycleIsNoMeasurement() {
        // малый цикл: старое растёт продвижением, мусор в нём не собран
        assertEquals(-1, HeapWatch.freedOld(Map.of(OLD, used(5878), YOUNG, used(3854)), Map.of(OLD, used(5878), YOUNG, used(1348)), Set.of(OLD)));
        assertEquals(-1, HeapWatch.freedOld(Map.of(OLD, used(5878)), Map.of(OLD, used(5901)), Set.of(OLD)));
    }

    @Test
    void missingPoolIsNoMeasurement() {
        assertEquals(-1, HeapWatch.freedOld(Map.of(YOUNG, used(10)), Map.of(YOUNG, used(5)), Set.of(OLD)));
    }

    @Test
    void strikesCountOnlyInARow() {
        long limit = 9216 * MB;
        int s = HeapWatch.strikes(0, 9300 * MB, limit);
        assertEquals(1, s);
        s = HeapWatch.strikes(s, 6000 * MB, limit);
        assertEquals(0, s, "замер ниже предела сбрасывает счёт");
        s = HeapWatch.strikes(HeapWatch.strikes(s, 9300 * MB, limit), 9400 * MB, limit);
        assertEquals(2, s);
    }
}
