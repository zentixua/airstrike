package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontScheduleTest {
    /** Как {@code ChunkPos.asLong}. */
    private static long chunk(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    private static long[] sorted(LongArrayList chunks) {
        long[] out = chunks.toLongArray();
        java.util.Arrays.sort(out);
        return out;
    }

    /** Секция DH — 4×4 чанка от кратного 4, и по отрицательным координатам тоже; середина — в блоках. */
    @Test
    void sectionsAreFourByFourChunks() {
        assertEquals(chunk(0, 0), FrontSchedule.section(chunk(3, 0)));
        assertEquals(chunk(1, 0), FrontSchedule.section(chunk(4, 3)));
        assertEquals(chunk(-1, -1), FrontSchedule.section(chunk(-1, -4)));
        assertEquals(chunk(-2, 0), FrontSchedule.section(chunk(-5, 2)));
        assertEquals(32.0, FrontSchedule.middle(0));
        assertEquals(-32.0, FrontSchedule.middle(-1));
        assertEquals(96.0, FrontSchedule.middle(1));
    }

    /** Секции — по сроку; чанки секции — вместе; до срока — ничего. */
    @Test
    void sectionsGoWholeInOrderOfTheirTime() {
        FrontSchedule s = new FrontSchedule();
        s.add(chunk(5, 0), 20);
        s.add(chunk(0, 0), 10);
        s.add(chunk(1, 2), 10);
        s.add(chunk(6, 1), 20);
        assertEquals(4, s.size());
        assertNull(s.head(9));
        LongArrayList first = s.head(10);
        assertEquals(2, first.size());
        assertTrue(java.util.Arrays.equals(new long[] {chunk(0, 0), chunk(1, 2)}, sorted(first)));
        s.pop();
        assertNull(s.head(19));
        assertEquals(2, s.head(25).size());
        s.pop();
        assertNull(s.head(Long.MAX_VALUE));
        assertEquals(0, s.size());
    }

    /** Секции с одним сроком (у 2 кт — вся зона в тике подрыва) — в порядке постановки: ближние к эпицентру первыми. */
    @Test
    void sameTimeKeepsOrder() {
        FrontSchedule s = new FrontSchedule();
        s.add(chunk(8, 0), 5);
        s.add(chunk(0, 8), 5);
        s.add(chunk(-8, 0), 5);
        assertEquals(chunk(8, 0), s.head(5).getLong(0));
        s.pop();
        assertEquals(chunk(0, 8), s.head(5).getLong(0));
        s.pop();
        assertEquals(chunk(-8, 0), s.head(5).getLong(0));
    }

    /** Копия, собранная после отдачи своей секции, уходит сразу — в новой пачке с тем же (прошедшим) сроком. */
    @Test
    void lateChunkOfReleasedSectionGoesAtOnce() {
        FrontSchedule s = new FrontSchedule();
        s.add(chunk(0, 0), 10);
        s.add(chunk(9, 9), 30);
        s.pop();
        s.add(chunk(1, 1), 10);
        LongArrayList head = s.head(12);
        assertEquals(1, head.size());
        assertEquals(chunk(1, 1), head.getLong(0));
    }

    /** Снятый чанк (загружен, вытеснен) не уходит; пустая секция пропускается. */
    @Test
    void removedChunksAreSkipped() {
        FrontSchedule s = new FrontSchedule();
        s.add(chunk(0, 0), 10);
        s.add(chunk(1, 0), 10);
        s.add(chunk(8, 8), 12);
        assertTrue(s.remove(chunk(1, 0)));
        assertFalse(s.remove(chunk(1, 0)));
        assertEquals(1, s.head(10).size());
        assertTrue(s.remove(chunk(0, 0)));
        assertNull(s.head(11));
        assertEquals(chunk(8, 8), s.head(12).getLong(0));
        assertEquals(1, s.size());
    }
}
