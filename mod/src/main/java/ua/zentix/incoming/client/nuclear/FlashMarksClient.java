package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.flash.FlashMarks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Следы вспышки у клиента: по чанку — то, что прислал сервер ({@link S2C.NukeFlashMarks}), пока чанк у клиента.
 * Слой подрыва виден, когда часы мира дошли до вспышки: повтор Flashback, перемотанный на миг до подрыва, показывает
 * стены чистыми (чанки перемотки приходят без руин, а следы клиент помнит). Смена видимости или следов — секции
 * чанка строятся заново ({@code setSectionDirty}), грани рисует {@link FlashMarksMesh}.
 * <p>
 * Читают и потоки сборки секций (Sodium может звать событие секции не в потоке клиента): карта — потокобезопасная,
 * следы — неизменяемые.
 */
public final class FlashMarksClient {
    /** Допуск часов: клиент узнаёт время раз в секунду, и часы прыгают назад, когда сервер догоняет отставание. */
    private static final int CLOCK_SLACK = 20;

    private static final Map<Long, FlashMarks> CHUNKS = new ConcurrentHashMap<>();
    /** Сколько слоёв чанка видно сейчас (поток клиента): смена — пересборка его секций. */
    private static final Long2IntOpenHashMap SHOWN = new Long2IntOpenHashMap();

    private FlashMarksClient() {}

    /** Пакет сервера (поток клиента): следы чанка целиком. */
    public static void receive(S2C.NukeFlashMarks p) {
        FlashMarks old = p.marks().isEmpty() ? CHUNKS.remove(p.chunk()) : CHUNKS.put(p.chunk(), p.marks());
        INDEX.remove(p.chunk());
        dirty(p.chunk(), old);
        dirty(p.chunk(), p.marks());
        SHOWN.put(p.chunk(), shown(p.marks(), now()));
    }

    /** Метки одного видимого слоя в одной секции: гарь, силуэты и их маски ({@link FlashMarks.Layer}). */
    record Slice(int[] chars, int[] shadows, long[] masks) {}

    /** Метки видимых слоёв чанка в секции {@code sy} (номер секции в мире), по порядку слоёв; null — нет. */
    @Nullable
    static List<Slice> section(long chunk, int sy, long now) {
        Index i = index(chunk, now);
        return i == null ? null : i.sections.get(sy);
    }

    /** Ступени граней видимых слоёв чанка (ключ {@link FlashChar#key} → наибольшая ступень); null — следов нет. */
    @Nullable
    static Int2IntOpenHashMap levels(long chunk, long now) {
        Index i = index(chunk, now);
        return i == null ? null : i.levels;
    }

    /**
     * Видимые слои чанка, разобранные для меша один раз на эти следы и это число видимых слоёв: ступени граней и метки по
     * секциям (секция строится заново — без прохода по всем меткам чанка). Только читаются.
     */
    private record Index(FlashMarks marks, int shown, Int2IntOpenHashMap levels, Int2ObjectOpenHashMap<List<Slice>> sections) {}

    private static final Map<Long, Index> INDEX = new ConcurrentHashMap<>();

    @Nullable
    private static Index index(long chunk, long now) {
        FlashMarks m = CHUNKS.get(chunk);
        if (m == null) return null;
        int shown = shown(m, now);
        Index cached = INDEX.get(chunk);
        if (cached != null && cached.marks == m && cached.shown == shown) return cached;
        Int2IntOpenHashMap levels = new Int2IntOpenHashMap();
        Int2ObjectOpenHashMap<List<Slice>> sections = new Int2ObjectOpenHashMap<>();
        for (FlashMarks.Layer l : m.layers()) {
            if (l.time() > now + CLOCK_SLACK) continue;
            Int2ObjectOpenHashMap<IntArrayList> chars = new Int2ObjectOpenHashMap<>(), shadows = new Int2ObjectOpenHashMap<>();
            Int2ObjectOpenHashMap<LongArrayList> masks = new Int2ObjectOpenHashMap<>();
            for (int c : l.chars()) {
                levels.mergeInt(c & FlashChar.KEY_MASK, FlashChar.level(c), Math::max);
                chars.computeIfAbsent(FlashChar.y(c) >> 4, k -> new IntArrayList()).add(c);
            }
            for (int k = 0; k < l.shadows().length; k++) {
                int c = l.shadows()[k], sy = FlashChar.y(c) >> 4;
                levels.mergeInt(c & FlashChar.KEY_MASK, FlashChar.level(c), Math::max);
                shadows.computeIfAbsent(sy, y -> new IntArrayList()).add(c);
                LongArrayList ms = masks.computeIfAbsent(sy, y -> new LongArrayList());
                ms.addElements(ms.size(), l.masks(), k * FlashMarks.MASK_LONGS, FlashMarks.MASK_LONGS);
            }
            IntOpenHashSet ys = new IntOpenHashSet(chars.keySet());
            ys.addAll(shadows.keySet());
            for (int sy : ys) {
                IntArrayList c = chars.get(sy), sh = shadows.get(sy);
                LongArrayList ms = masks.get(sy);
                sections.computeIfAbsent(sy, y -> new ArrayList<>()).add(new Slice(c == null ? new int[0] : c.toIntArray(),
                        sh == null ? new int[0] : sh.toIntArray(), ms == null ? new long[0] : ms.toLongArray()));
            }
        }
        Index built = new Index(m, shown, levels, sections);
        INDEX.put(chunk, built);
        return built;
    }

    /** Следы чанка как есть (проверки, сценарий). */
    @Nullable
    public static FlashMarks of(long chunk) {
        return CHUNKS.get(chunk);
    }

    /** Сколько чанков со следами у клиента. */
    public static int chunks() {
        return CHUNKS.size();
    }

    /** Часы дошли до вспышки слоя или ушли назад (перемотка): секции чанка — заново. */
    public static void tick() {
        if (CHUNKS.isEmpty()) return;
        long now = now();
        for (Map.Entry<Long, FlashMarks> e : CHUNKS.entrySet()) {
            long chunk = e.getKey();
            int shown = shown(e.getValue(), now);
            if (SHOWN.getOrDefault(chunk, -1) == shown) continue;
            SHOWN.put(chunk, shown);
            dirty(chunk, e.getValue());
        }
    }

    private static int shown(FlashMarks m, long now) {
        int n = 0;
        for (FlashMarks.Layer l : m.layers()) if (l.time() <= now + CLOCK_SLACK) n++;
        return n;
    }

    private static long now() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? Long.MIN_VALUE / 2 : level.getGameTime();
    }

    /**
     * Пересобрать секции чанка, где есть следы, и секции соседа за краем, у которого следы есть: углы граней у края
     * сглаживаются по граням соседнего чанка ({@link FlashMarksMesh}).
     */
    private static void dirty(long chunk, @Nullable FlashMarks m) {
        if (m == null || m.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) return;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, edges = 0;
        for (FlashMarks.Layer l : m.layers()) {
            for (int[] marks : new int[][]{l.chars(), l.shadows()}) {
                for (int c : marks) {
                    lo = Math.min(lo, FlashChar.y(c));
                    hi = Math.max(hi, FlashChar.y(c));
                    edges |= (FlashChar.lx(c) == 0 ? 1 : 0) | (FlashChar.lx(c) == 15 ? 2 : 0) | (FlashChar.lz(c) == 0 ? 4 : 0) | (FlashChar.lz(c) == 15 ? 8 : 0);
                }
            }
        }
        if (lo > hi) return;
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        for (int sy = lo >> 4; sy <= hi >> 4; sy++) {
            mc.levelRenderer.setSectionDirty(cx, sy, cz);
            if ((edges & 1) != 0) mc.levelRenderer.setSectionDirty(cx - 1, sy, cz);
            if ((edges & 2) != 0) mc.levelRenderer.setSectionDirty(cx + 1, sy, cz);
            if ((edges & 4) != 0) mc.levelRenderer.setSectionDirty(cx, sy, cz - 1);
            if ((edges & 8) != 0) mc.levelRenderer.setSectionDirty(cx, sy, cz + 1);
        }
    }

    /**
     * Чанк ушёл с клиента: его следы — тоже (вернётся — сервер пришлёт снова). В повторе Flashback — остаются: снимок
     * куска записи несёт лишь последний пакет следов, и перемотанный повтор иначе терял бы их у всех чанков, кроме одного.
     */
    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (!e.getLevel().isClientSide() || ua.zentix.incoming.client.replay.Replay.active()) return;
        long key = e.getChunk().getPos().toLong();
        CHUNKS.remove(key);
        SHOWN.remove(key);
        INDEX.remove(key);
    }

    /** Мир клиента сменился (измерение, выход). */
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (e.getLevel().isClientSide()) reset();
    }

    public static void reset() {
        CHUNKS.clear();
        SHOWN.clear();
        INDEX.clear();
    }
}
