package ua.zentix.airstrike.nuclear.world;

import org.junit.jupiter.api.Test;

import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Замер кучи для руин заранее ({@link HeapWatch#reading}): только старое поколение и только после сборки, которая
 * видит его живой объём, или выше предела. Числа — из лога сборщика игры Артёма 01.10.2026 (ZGC с поколениями, куча
 * 12 ГБ, предел 75 % — 9216 МБ): большой цикл GC(3730) — старое 6342 → 5878 МБ при живых 5386, молодое «после»
 * 1190 МБ при живых 56; малый GC(3739) — старое не освобождал, молодое «после» 1348 МБ при живых 86.
 */
class HeapWatchTest {
    private static final long MB = 1L << 20;
    private static final long LIMIT = 9216 * MB;
    private static final String OLD = "ZGC Old Generation", YOUNG = "ZGC Young Generation", MAJOR = "ZGC Major Cycles", MINOR = "ZGC Minor Cycles";
    private static final String G1_OLD = "G1 Old Gen", G1_EDEN = "G1 Eden Space";

    private static MemoryUsage used(long mb) {
        return new MemoryUsage(0, mb * MB, 12288 * MB, 12288 * MB);
    }

    @Test
    void majorCycleMeasuresOldOnly() {
        long live = HeapWatch.reading(MAJOR, Map.of(OLD, used(6342), YOUNG, used(1840)), Map.of(OLD, used(5878), YOUNG, used(1190)), Set.of(OLD), LIMIT);
        assertEquals(5878 * MB, live, "большой цикл: старое после сборки, без молодого");
    }

    @Test
    void minorCycleIsNoMeasurement() {
        // малый цикл: старое растёт продвижением, мусор в нём не собран
        assertEquals(-1, HeapWatch.reading(MINOR, Map.of(OLD, used(5878), YOUNG, used(3854)), Map.of(OLD, used(5878), YOUNG, used(1348)), Set.of(OLD), LIMIT));
        assertEquals(-1, HeapWatch.reading(MINOR, Map.of(OLD, used(5878)), Map.of(OLD, used(5901)), Set.of(OLD), LIMIT));
    }

    @Test
    void minorCycleThatFreedOldMeasures() {
        // смешанная сборка G1 приходит от «G1 Young Generation»: её видно по тому, что старое стало меньше
        assertEquals(5100 * MB, HeapWatch.reading("G1 Young Generation", Map.of(G1_OLD, used(5600)), Map.of(G1_OLD, used(5100)), Set.of(G1_OLD), LIMIT));
    }

    @Test
    void fullGcOldGrewMeasures() {
        // полная сборка G1 переносит живое из молодого в старое: старое растёт, а замер верный
        long live = HeapWatch.reading("G1 Old Generation", Map.of(G1_OLD, used(7000), G1_EDEN, used(900)), Map.of(G1_OLD, used(7200), G1_EDEN, used(0)), Set.of(G1_OLD), LIMIT);
        assertEquals(7200 * MB, live);
    }

    @Test
    void overLimitMeasuresWithoutFreeing() {
        // старое выше предела, а большой сборки нет: без этого замера руины заранее не остановились бы до нехватки
        assertEquals(9400 * MB, HeapWatch.reading(MINOR, Map.of(OLD, used(9300)), Map.of(OLD, used(9400)), Set.of(OLD), LIMIT));
    }

    @Test
    void missingPoolIsNoMeasurement() {
        assertEquals(-1, HeapWatch.reading(MAJOR, Map.of(YOUNG, used(10)), Map.of(YOUNG, used(5)), Set.of(OLD), LIMIT));
    }

    @Test
    void strikesCountOnlyInARow() {
        int s = HeapWatch.strikes(0, 9300 * MB, LIMIT);
        assertEquals(1, s);
        s = HeapWatch.strikes(s, 6000 * MB, LIMIT);
        assertEquals(0, s, "замер ниже предела сбрасывает счёт");
        s = HeapWatch.strikes(HeapWatch.strikes(s, 9300 * MB, LIMIT), 9400 * MB, LIMIT);
        assertEquals(2, s);
    }
}
