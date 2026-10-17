package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.PriorityQueue;

/**
 * Очередь держанных копий LOD к фронту ({@link FrontLods}) — по секциям Distant Horizons: чанки секции уходят вместе, в
 * срок секции, секции — по сроку (без Minecraft — юнит-тесты). DH пишет LOD в базу участками 64×64 блока
 * ({@code FullDataSourceV2.WIDTH}) — 4×4 чанка — не раньше секунды после последней записи в участок: чанк секции,
 * отданный позже остальных, отложил бы весь участок.
 */
final class FrontSchedule {
    /** Чанков в секции DH по оси. */
    static final int SECTION_CHUNKS = 4;

    /** Чанки одной секции со сроком отдачи; порядок — для одинаковых сроков. */
    private record Batch(long section, long at, long order, LongArrayList chunks) {}

    private final PriorityQueue<Batch> queue = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.order, b.order));
    /** Секция → её пачка, ещё не отданная (в неё встают чанки той же секции). */
    private final Long2ObjectOpenHashMap<Batch> open = new Long2ObjectOpenHashMap<>();
    private long added;
    private int size;

    /** Секция DH чанка (как {@code ChunkPos.asLong}: x — младшие 32 бита, z — старшие). */
    static long section(long chunk) {
        int x = Math.floorDiv((int) chunk, SECTION_CHUNKS), z = Math.floorDiv((int) (chunk >>> 32), SECTION_CHUNKS);
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    /** Середина секции DH по оси, в блоках (номер секции по оси). */
    static double middle(int section) {
        return (section * SECTION_CHUNKS + SECTION_CHUNKS / 2) * 16.0;
    }

    /**
     * Чанк — к отдаче в тик {@code at}. Чанки одной секции — с одним сроком: чанк встаёт в ещё не отданную пачку своей
     * секции (с её сроком), а если её нет — в новую пачку (копия, собранная после отдачи секции, уходит сразу).
     */
    void add(long chunk, long at) {
        long s = section(chunk);
        Batch b = open.get(s);
        if (b == null) {
            b = new Batch(s, at, added++, new LongArrayList(1));
            open.put(s, b);
            queue.add(b);
        }
        b.chunks.add(chunk);
        size++;
    }

    /** Чанк больше не ждёт отдачи (загружен, вытеснен); false — его не было в очереди. */
    boolean remove(long chunk) {
        Batch b = open.get(section(chunk));
        if (b == null || !b.chunks.rem(chunk)) return false;
        size--;
        return true;
    }

    /** Чанки первой по сроку секции, чей срок настал (пустые пачки пропускаются); null — таких нет. Не снимает. */
    @Nullable
    LongArrayList head(long now) {
        for (Batch b; (b = queue.peek()) != null; ) {
            if (!b.chunks.isEmpty()) return b.at <= now ? b.chunks : null;
            queue.poll();
            open.remove(b.section, b);
        }
        return null;
    }

    /** Снять первую по сроку секцию ({@link #head}): её чанки отданы. */
    void pop() {
        Batch b = queue.poll();
        if (b == null) return;
        open.remove(b.section, b);
        size -= b.chunks.size();
    }

    /** Чанков ждёт отдачи. */
    int size() {
        return size;
    }

    void clear() {
        queue.clear();
        open.clear();
        size = 0;
    }
}
