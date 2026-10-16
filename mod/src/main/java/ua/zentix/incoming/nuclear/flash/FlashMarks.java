package ua.zentix.incoming.nuclear.flash;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Следы вспышки на гранях одного чанка (attachment чанка, сохраняется с ним): обугленные грани ({@link FlashChar}) и
 * силуэты — грани, на которых тело заслонило часть света ({@link FlashShadows}): там, где тень, грань осталась светлой.
 * По слою на подрыв: клиент показывает слой, когда его часы дошли до вспышки (повтор Flashback, перемотанный назад, —
 * без гари).
 * <p>
 * Мало данных: метка грани — одно {@code int} ({@link FlashChar#mark}), силуэт — ещё 16×16 клеток по 2 бита (тень
 * 0, ⅓, ⅔, целиком) — 8 {@code long}. Не больше {@link #MAX_CHARS} обугленных граней и {@link #MAX_SHADOWS} граней
 * с силуэтом на чанк (во всех слоях): сверх — самые слабые уходят. Чанк города — сотни, у стены к шару — до пары
 * тысяч граней: 16 КБ и 6 КБ в худшем случае.
 */
public record FlashMarks(List<Layer> layers) {
    /** Обугленных граней на чанк, не больше. */
    public static final int MAX_CHARS = 4096;
    /** Граней с силуэтом на чанк, не больше. */
    public static final int MAX_SHADOWS = 96;
    /** Клеток силуэта по стороне грани (как пикселей у текстуры блока) и {@code long} на маску (2 бита на клетку). */
    public static final int CELLS = 16, MASK_LONGS = CELLS * CELLS * 2 / 64;

    public static final FlashMarks EMPTY = new FlashMarks(List.of());

    /**
     * Следы одного подрыва.
     *
     * @param detonation номер подрыва
     * @param time       игровое время вспышки
     * @param chars      обугленные грани ({@link FlashChar#mark}), по возрастанию ключа
     * @param shadows    грани с силуэтом (метки со своей ступенью гари)
     * @param masks      маски силуэтов: по {@link #MASK_LONGS} на грань, клетка {@code (u, v)} — биты {@code 2(v·16 + u)}
     */
    public record Layer(int detonation, long time, int[] chars, int[] shadows, long[] masks) {
        static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("detonation").forGetter(Layer::detonation),
                Codec.LONG.fieldOf("time").forGetter(Layer::time),
                Codec.INT_STREAM.xmap(IntStream::toArray, IntStream::of).fieldOf("chars").forGetter(Layer::chars),
                Codec.INT_STREAM.xmap(IntStream::toArray, IntStream::of).fieldOf("shadows").forGetter(Layer::shadows),
                Codec.LONG_STREAM.xmap(LongStream::toArray, LongStream::of).fieldOf("masks").forGetter(Layer::masks)
        ).apply(i, Layer::new));

        boolean isEmpty() {
            return chars.length == 0 && shadows.length == 0;
        }

        /** Тень клетки силуэта {@code k}: 0 — свет, 3 — тень целиком. */
        public int shade(int k, int u, int v) {
            int bit = 2 * (v * CELLS + u);
            return (int) (masks[k * MASK_LONGS + (bit >> 6)] >>> (bit & 63)) & 3;
        }
    }

    public static final Codec<FlashMarks> CODEC = Layer.CODEC.listOf().xmap(FlashMarks::new, FlashMarks::layers);

    public static final StreamCodec<ByteBuf, FlashMarks> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public FlashMarks decode(ByteBuf b) {
            int n = ByteBufCodecs.VAR_INT.decode(b);
            if (n > 16) throw new IllegalArgumentException("flash marks: " + n + " layers");
            List<Layer> layers = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int det = ByteBufCodecs.VAR_INT.decode(b);
                long time = b.readLong();
                int[] chars = ints(b, MAX_CHARS);
                int[] shadows = ints(b, MAX_SHADOWS);
                long[] masks = new long[shadows.length * MASK_LONGS];
                for (int k = 0; k < masks.length; k++) masks[k] = b.readLong();
                layers.add(new Layer(det, time, chars, shadows, masks));
            }
            return new FlashMarks(List.copyOf(layers));
        }

        @Override
        public void encode(ByteBuf b, FlashMarks m) {
            ByteBufCodecs.VAR_INT.encode(b, m.layers.size());
            for (Layer l : m.layers) {
                ByteBufCodecs.VAR_INT.encode(b, l.detonation);
                b.writeLong(l.time);
                ByteBufCodecs.VAR_INT.encode(b, l.chars.length);
                for (int c : l.chars) b.writeInt(c);
                ByteBufCodecs.VAR_INT.encode(b, l.shadows.length);
                for (int c : l.shadows) b.writeInt(c);
                for (long w : l.masks) b.writeLong(w);
            }
        }

        private static int[] ints(ByteBuf b, int max) {
            int n = ByteBufCodecs.VAR_INT.decode(b);
            if (n < 0 || n > max) throw new IllegalArgumentException("flash marks: " + n + " entries");
            int[] out = new int[n];
            for (int k = 0; k < n; k++) out[k] = b.readInt();
            return out;
        }
    };

    /** Для {@link FriendlyByteBuf} пакетов. */
    public static final StreamCodec<FriendlyByteBuf, FlashMarks> FRIENDLY = STREAM_CODEC.cast();

    public boolean isEmpty() {
        return layers.isEmpty();
    }

    /** Сколько граней во всех слоях (гарь и силуэты). */
    public int size() {
        int n = 0;
        for (Layer l : layers) n += l.chars.length + l.shadows.length;
        return n;
    }

    /**
     * Обугленные грани подрыва {@code detonation} вместо прежних его же (руины чанка поставлены заново), силуэты слоя
     * остаются.
     */
    public FlashMarks withChars(int detonation, long time, int[] chars) {
        Layer old = layer(detonation);
        return with(new Layer(detonation, time, chars, old == null ? new int[0] : old.shadows, old == null ? new long[0] : old.masks));
    }

    /** Силуэты подрыва {@code detonation} добавлены к прежним (одна грань — у кого тень больше, та и есть). */
    public FlashMarks withShadows(int detonation, long time, int[] shadows, long[] masks) {
        Layer old = layer(detonation);
        if (old == null) return with(new Layer(detonation, time, new int[0], shadows, masks));
        Int2IntOpenHashMap at = new Int2IntOpenHashMap();
        IntArrayList keys = new IntArrayList(old.shadows);
        LongArrayList all = new LongArrayList(old.masks);
        for (int k = 0; k < old.shadows.length; k++) at.put(old.shadows[k] & FlashChar.KEY_MASK, k);
        for (int k = 0; k < shadows.length; k++) {
            int was = at.getOrDefault(shadows[k] & FlashChar.KEY_MASK, -1);
            if (was < 0) {
                at.put(shadows[k] & FlashChar.KEY_MASK, keys.size());
                keys.add(shadows[k]);
                for (int w = 0; w < MASK_LONGS; w++) all.add(masks[k * MASK_LONGS + w]);
                continue;
            }
            // два тела перед одной гранью: тень — где тень от любого
            for (int w = 0; w < MASK_LONGS; w++) all.set(was * MASK_LONGS + w, maxShade(all.getLong(was * MASK_LONGS + w), masks[k * MASK_LONGS + w]));
        }
        return with(new Layer(detonation, time, old.chars, keys.toIntArray(), all.toLongArray()));
    }

    /** Поклеточный максимум двух масок по 2 бита. */
    static long maxShade(long a, long b) {
        long out = 0;
        for (int s = 0; s < 64; s += 2) out |= Math.max(a >>> s & 3, b >>> s & 3) << s;
        return out;
    }

    private Layer layer(int detonation) {
        for (Layer l : layers) if (l.detonation == detonation) return l;
        return null;
    }

    /** Слой подрыва вместо прежнего его же; пределы граней — по всем слоям (сверх — самые слабые, раньше — старые слои). */
    private FlashMarks with(Layer layer) {
        List<Layer> out = new ArrayList<>(layers.size() + 1);
        for (Layer l : layers) if (l.detonation != layer.detonation) out.add(l);
        out.add(layer);
        out.sort(java.util.Comparator.comparingInt(Layer::detonation));
        return new FlashMarks(List.copyOf(capped(out)));
    }

    private static List<Layer> capped(List<Layer> layers) {
        int chars = 0, shadows = 0;
        for (Layer l : layers) {
            chars += l.chars.length;
            shadows += l.shadows.length;
        }
        if (chars <= MAX_CHARS && shadows <= MAX_SHADOWS) return layers;
        // сверх предела — сперва из старых слоёв: новый подрыв важнее
        List<Layer> out = new ArrayList<>(layers);
        for (int i = 0; i < out.size() && (chars > MAX_CHARS || shadows > MAX_SHADOWS); i++) {
            Layer l = out.get(i);
            int[] c = strongest(l.chars, Math.max(0, l.chars.length - Math.max(0, chars - MAX_CHARS)));
            chars -= l.chars.length - c.length;
            int keep = Math.max(0, l.shadows.length - Math.max(0, shadows - MAX_SHADOWS));
            int[] s = Arrays.copyOf(l.shadows, keep);
            long[] m = Arrays.copyOf(l.masks, keep * MASK_LONGS);
            shadows -= l.shadows.length - keep;
            out.set(i, new Layer(l.detonation, l.time, c, s, m));
        }
        out.removeIf(Layer::isEmpty);
        return out;
    }

    /** Не больше {@code max} самых тёмных граней, по возрастанию ключа (план одинаков в любом порядке). */
    public static int[] strongest(int[] marks, int max) {
        if (marks.length <= max) return marks;
        Integer[] boxed = new Integer[marks.length];
        for (int k = 0; k < marks.length; k++) boxed[k] = marks[k];
        Arrays.sort(boxed, (a, b) -> FlashChar.level(a) != FlashChar.level(b) ? Integer.compare(FlashChar.level(b), FlashChar.level(a))
                : Integer.compare(a & FlashChar.KEY_MASK, b & FlashChar.KEY_MASK));
        int[] out = new int[max];
        for (int k = 0; k < max; k++) out[k] = boxed[k];
        Arrays.sort(out);
        return out;
    }

    /**
     * Без граней, чей блок сменился (руины, игрок): хеш блока метки не тот, что сейчас в чанке. Ничего не убрано —
     * тот же объект.
     */
    public FlashMarks pruned(LevelChunk chunk) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        boolean changed = false;
        List<Layer> out = new ArrayList<>(layers.size());
        for (Layer l : layers) {
            IntArrayList chars = new IntArrayList(l.chars.length);
            for (int c : l.chars) if (same(chunk, c, cx, cz, m)) chars.add(c);
            IntArrayList shadows = new IntArrayList(l.shadows.length);
            LongArrayList masks = new LongArrayList(l.masks.length);
            for (int k = 0; k < l.shadows.length; k++) {
                if (!same(chunk, l.shadows[k], cx, cz, m)) continue;
                shadows.add(l.shadows[k]);
                for (int w = 0; w < MASK_LONGS; w++) masks.add(l.masks[k * MASK_LONGS + w]);
            }
            if (chars.size() == l.chars.length && shadows.size() == l.shadows.length) {
                out.add(l);
                continue;
            }
            changed = true;
            Layer kept = new Layer(l.detonation, l.time, chars.toIntArray(), shadows.toIntArray(), masks.toLongArray());
            if (!kept.isEmpty()) out.add(kept);
        }
        return changed ? new FlashMarks(List.copyOf(out)) : this;
    }

    private static boolean same(LevelChunk chunk, int mark, int cx, int cz, BlockPos.MutableBlockPos m) {
        m.set(cx << 4 | FlashChar.lx(mark), FlashChar.y(mark), cz << 4 | FlashChar.lz(mark));
        return FlashChar.hash(chunk.getBlockState(m).getBlock()) == FlashChar.hashOf(mark);
    }
}
