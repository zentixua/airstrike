package ua.zentix.incoming.nuclear.flash;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.Fragility;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashMarksTest {
    /** Ступень гари растёт со светом и случайностью, у кладки — не выше {@code SOOT_MAX} от черноты. */
    @Test
    void levelGrowsWithLight() {
        int prev = 0;
        for (double q = 0.5; q < 500; q *= 1.3) {
            int l = FlashChar.level(q, FlashChar.CHARS, 0);
            assertTrue(l >= prev, "ступень упала при " + q);
            prev = l;
            assertTrue(FlashChar.level(q, FlashChar.CHARS, 1) >= l);
            assertTrue(FlashChar.level(q, FlashChar.SOOTS, 3) <= Math.round(FlashChar.SOOT_MAX * FlashChar.LEVELS));
        }
        assertEquals(FlashChar.LEVELS, FlashChar.level(500, FlashChar.CHARS, 0));
        assertEquals(0, FlashChar.level(0.1, FlashChar.CHARS, Fragility.TAIL));
        assertEquals(0, FlashChar.level(100, FlashChar.NONE, 0));
    }

    /** {@code possible} и {@code saturated} согласны с самой ступенью на краях случайности. */
    @Test
    void possibleAndSaturatedMatchLevel() {
        for (double q = 0.5; q < 500; q *= 1.1) {
            for (int m : new int[]{FlashChar.CHARS, FlashChar.SOOTS}) {
                assertEquals(FlashChar.level(q, m, Fragility.TAIL) > 0, FlashChar.possible(q, m));
                if (FlashChar.saturated(q, m)) assertEquals(FlashChar.level(q, m, -Fragility.TAIL), FlashChar.level(q, m, 0.7));
            }
        }
    }

    /** Метка хранит место, сторону, ступень и хеш. */
    @Test
    void markRoundTrip() {
        for (Direction f : Direction.values()) {
            int key = FlashChar.key(13, -61, 2, f);
            int mark = FlashChar.mark(key, 5, 41);
            assertEquals(13, FlashChar.lx(mark));
            assertEquals(2, FlashChar.lz(mark));
            assertEquals(-61, FlashChar.y(mark));
            assertEquals(f, FlashChar.face(mark));
            assertEquals(5, FlashChar.level(mark));
            assertEquals(41, FlashChar.hashOf(mark));
            assertEquals(key, mark & FlashChar.KEY_MASK);
        }
        assertEquals(319, FlashChar.y(FlashChar.key(0, 319, 0, Direction.UP)));
    }

    /** Две тени на одной грани — поклеточный максимум; новые грани — в конец. */
    @Test
    void shadowsMerge() {
        int a = FlashChar.mark(FlashChar.key(1, 70, 1, Direction.WEST), 6, 3), b = FlashChar.mark(FlashChar.key(2, 70, 1, Direction.WEST), 6, 3);
        long[] m1 = new long[FlashMarks.MASK_LONGS], m2 = new long[FlashMarks.MASK_LONGS];
        m1[0] = 0b01_11L;
        m2[0] = 0b11_01L;
        FlashMarks marks = FlashMarks.EMPTY.withShadows(7, 100, new int[]{a}, m1).withShadows(7, 100, new int[]{a, b}, concat(m2, m1));
        FlashMarks.Layer l = marks.layers().getFirst();
        assertArrayEquals(new int[]{a, b}, l.shadows());
        assertEquals(3, l.shade(0, 0, 0));
        assertEquals(3, l.shade(0, 1, 0));
        assertEquals(3, l.shade(1, 0, 0));
        assertEquals(1, l.shade(1, 1, 0));
    }

    /** Сверх предела остаются самые тёмные грани; старые слои уступают новому. */
    @Test
    void capKeepsStrongest() {
        int[] old = new int[FlashMarks.MAX_CHARS];
        for (int k = 0; k < old.length; k++) old[k] = FlashChar.mark(k, 1, 0);
        int[] fresh = {FlashChar.mark(FlashChar.key(0, 0, 0, Direction.UP), 7, 0), FlashChar.mark(FlashChar.key(1, 0, 0, Direction.UP), 7, 0)};
        FlashMarks m = FlashMarks.EMPTY.withChars(1, 10, old).withChars(2, 20, fresh);
        int total = 0;
        for (FlashMarks.Layer l : m.layers()) total += l.chars().length;
        assertEquals(FlashMarks.MAX_CHARS, total);
        assertArrayEquals(fresh, m.layers().getLast().chars());
        int[] strongest = FlashMarks.strongest(new int[]{FlashChar.mark(5, 1, 0), FlashChar.mark(6, 7, 0), FlashChar.mark(7, 3, 0)}, 2);
        assertEquals(2, strongest.length);
        assertTrue(Arrays.stream(strongest).allMatch(c -> FlashChar.level(c) >= 3));
    }

    /** Пакет читается обратно тем же. */
    @Test
    void streamCodecRoundTrip() {
        long[] mask = new long[FlashMarks.MASK_LONGS];
        mask[3] = 0xDEADBEEFL;
        FlashMarks m = FlashMarks.EMPTY.withChars(3, 77, new int[]{FlashChar.mark(9, 4, 12)})
                .withShadows(3, 77, new int[]{FlashChar.mark(10, 2, 12)}, mask);
        ByteBuf buf = Unpooled.buffer();
        FlashMarks.STREAM_CODEC.encode(buf, m);
        FlashMarks back = FlashMarks.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes());
        List<FlashMarks.Layer> a = m.layers(), b = back.layers();
        assertEquals(a.size(), b.size());
        assertEquals(a.getFirst().time(), b.getFirst().time());
        assertArrayEquals(a.getFirst().chars(), b.getFirst().chars());
        assertArrayEquals(a.getFirst().shadows(), b.getFirst().shadows());
        assertArrayEquals(a.getFirst().masks(), b.getFirst().masks());
        assertFalse(back.isEmpty());
    }

    private static long[] concat(long[] a, long[] b) {
        long[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
