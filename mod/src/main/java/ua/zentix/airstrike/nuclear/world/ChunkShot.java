package ua.zentix.airstrike.nuclear.world;

import net.minecraft.util.BitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Исходные блоки чанка для руин — снимок, снятый в потоке сервера: копии палитр секций ({@code PalettedContainer.copy},
 * живые секции только читаются), карты высот и источники неба; у чанка с руинами — старые блоки мест его плана. Снимок
 * бывает и с диска ({@link DiskShots}): чанк, которого нет в памяти, прочитан из файла региона в фоне. Из снимков окно
 * чанка ({@link RuinWindow}) и план ({@link RuinPlanner#finish}) строятся в любом потоке: снимок больше не читает мир и
 * не меняется.
 * <p>
 * Свойства блоков ({@link Blast.Props}) по местам секции берутся через её палитру: у палитры копии (до 256 состояний)
 * — массив свойств по номеру в палитре, место читает номер из хранилища секции, лишней памяти на место нет. Все
 * состояния палитр поток сервера заносит в таблицу ({@link Blast#ensure}), когда снимок создаётся.
 * <p>
 * По чему снят — {@code identityHashCode} чанка, его блоки ({@link #prints}: отпечатки секций) и стояли ли руины:
 * снимок верен, пока чанк тот же, блоки в нём те же (погашенная блэкаутом лампа — та же лампа), руины те же и таблицу
 * свойств не сбрасывали ({@link #current}). Изменения чанка мод не подслушивает: снимок сверяется с чанком сам. У снимка
 * с диска чанка нет (номер 0): план по нему сверяется с чанком при подмене так же, по отпечаткам секций.
 * <p>
 * Номер снимка ({@link #serial}) — по нему разлом и план знают, по каким снимкам построены ({@link RuinWindow.Stamp}):
 * новый снимок берётся, только когда старый неверен.
 */
final class ChunkShot {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final java.util.concurrent.atomic.AtomicLong SERIALS = new java.util.concurrent.atomic.AtomicLong();
    /** Отпечаток секции из одного воздуха (в снимке её нет). */
    private static final long AIR_PRINT = 0x5DEECE66DL;

    final int x, z;
    final int id;
    /** Номер снимка (от 1): разлом и план построены по этому снимку ({@link RuinWindow.Stamp}). */
    final long serial = SERIALS.incrementAndGet();
    /**
     * Отпечатки секций по номеру ({@link #rawPrint}): по ним снимок сверяется с живым чанком. Отпечаток, у которого
     * блоки те же с точностью до погашенных ламп ({@link #normalPrint}), заменяется живым (поток сервера).
     */
    final long[] prints;
    /** Сброс таблицы свойств, после которого снят ({@link Blast#generation}). */
    private final int generation = Blast.generation;
    /** Руины чанка уже стояли: исходные блоки — старые блоки мест плана ({@link #old}), огонь и текущая вода — воздух. */
    final boolean ruined;
    @Nullable
    private final RuinPlan old;
    /** Верх столбцов по старым блокам плана ({@link RuinPlan#oldTops}): посчитан здесь, в потоке сервера. */
    @Nullable
    final int[] oldTops;
    final int minY, sections;
    /** Копии секций; null — секция из одного воздуха. */
    private final PalettedContainer<BlockState>[] states;
    /** Верхний блок ({@code getHeight}) карт {@code WORLD_SURFACE} и {@code MOTION_BLOCKING_NO_LEAVES} (индекс {@code lz << 4 | lx}). */
    final int[] surface = new int[256], solid = new int[256];
    /** Первый свободный по картам {@link RuinPlan#HEIGHTMAP_TYPES}: {@code вид * 256 + столбец}. */
    final int[] heights;
    /** Нижний источник неба столбца ({@code ChunkSkyLightSources}); null — посчитать по блокам (снимок с диска). */
    @Nullable
    final int[] sky;
    /** Свойства мест секции, по мере чтения. */
    private final AtomicReferenceArray<Sec> props;
    /** Сколько байт занимают копии палитр и карты (память подрыва). */
    final long bytes;

    /** Свойства мест одной секции: по номеру в палитре или (руины, глобальная палитра) по месту. */
    static final class Sec {
        @Nullable
        private final BitStorage storage;
        @Nullable
        private final Blast.Props[] local;
        @Nullable
        private final Blast.Props[] full;

        Sec(@Nullable BitStorage storage, @Nullable Blast.Props[] local, @Nullable Blast.Props[] full) {
            this.storage = storage;
            this.local = local;
            this.full = full;
        }

        /** Свойства места секции ({@code y << 8 | z << 4 | x}). */
        Blast.Props get(int i) {
            return full != null ? full[i] : local[storage.get(i)];
        }
    }

    private ChunkShot(int x, int z, int id, @Nullable RuinPlan old, boolean ruined, int minY,
                      PalettedContainer<BlockState>[] states, int[] heights, @Nullable int[] sky) {
        this.x = x;
        this.z = z;
        this.id = id;
        this.ruined = ruined;
        this.old = old;
        this.minY = minY;
        this.states = states;
        this.sections = states.length;
        this.heights = heights;
        this.sky = sky;
        this.prints = new long[states.length];
        for (int i = 0; i < states.length; i++) prints[i] = rawPrint(states[i]);
        long size = 0;
        for (PalettedContainer<BlockState> c : states) {
            if (c == null) continue;
            size += c.getSerializedSize();
            register(c);
        }
        int surfaceType = type(Heightmap.Types.WORLD_SURFACE), solidType = type(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        for (int c = 0; c < 256; c++) {
            surface[c] = heights[surfaceType * 256 + c] - 1;
            solid[c] = heights[solidType * 256 + c] - 1;
        }
        if (old != null) {
            this.oldTops = old.oldTops(minY);
            old.forEachOld(ChunkShot::ensure);
        } else {
            this.oldTops = null;
        }
        this.props = new AtomicReferenceArray<>(sections);
        this.bytes = size + 4L * (heights.length + 512 + (sky == null ? 0 : 256)) + 64L * sections + 256;
    }

    static int type(Heightmap.Types t) {
        for (int i = 0; i < RuinPlan.HEIGHTMAP_TYPES.length; i++) if (RuinPlan.HEIGHTMAP_TYPES[i] == t) return i;
        throw new IllegalStateException(t.name());
    }

    /**
     * Все состояния палитры — в таблицу, пока мы в потоке сервера (у глобальной палитры, больше 256 состояний в секции,
     * список не перебрать — по всем местам); у брёвен — и все оси (руины кладут стволы лёжа).
     */
    private static void register(PalettedContainer<BlockState> c) {
        boolean[] listed = {false};
        if (c.maybeHas(st -> {
            listed[0] = true;
            ensure(st);
            return false;
        }) && !listed[0]) {
            c.getAll(ChunkShot::ensure);
        }
    }

    private static void ensure(BlockState st) {
        Blast.ensure(st);
        if (st.hasProperty(BlockStateProperties.AXIS)) {
            for (var axis : BlockStateProperties.AXIS.getPossibleValues()) Blast.ensure(st.setValue(BlockStateProperties.AXIS, axis));
        }
    }

    /** Снимок чанка в памяти (поток сервера): {@code applied} — руины чанка уже стоят (их план — или null, если отпущен). */
    @SuppressWarnings("unchecked")
    static ChunkShot take(LevelChunk chunk, @Nullable RuinContext.Applied applied) {
        LevelChunkSection[] live = chunk.getSections();
        PalettedContainer<BlockState>[] states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[live.length];
        for (int i = 0; i < live.length; i++) if (!live[i].hasOnlyAir()) states[i] = live[i].getStates().copy();
        int[] heights = new int[RuinPlan.HEIGHTMAP_TYPES.length * 256];
        for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
            Heightmap map = chunk.getOrCreateHeightmapUnprimed(RuinPlan.HEIGHTMAP_TYPES[t]);
            for (int c = 0; c < 256; c++) heights[t * 256 + c] = map.getFirstAvailable(c & 15, c >> 4);
        }
        int[] sky = new int[256];
        var sources = chunk.getSkyLightSources();
        for (int c = 0; c < 256; c++) sky[c] = sources.get(c);
        ChunkPos pos = chunk.getPos();
        return new ChunkShot(pos.x, pos.z, System.identityHashCode(chunk), applied == null ? null : applied.plan(),
                applied != null, chunk.getMinBuildHeight(), states, heights, sky);
    }

    /**
     * Снимок чанка с диска (поток сервера: его состояния — в таблицу), см. {@link DiskShots}; {@code applied} — руины
     * чанка уже стоят (чанк был в памяти после подрыва и ушёл ниже полной загрузки): исходный мир мест его плана — из
     * плана, как у снимка из памяти.
     */
    static ChunkShot fromDisk(DiskShots.Read read, @Nullable RuinContext.Applied applied) {
        return new ChunkShot(read.pos().x, read.pos().z, 0, applied == null ? null : applied.plan(), applied != null,
                read.minY(), read.states(), read.heights(), null);
    }

    /**
     * Снимок по тому же чанку и руинам, что и сейчас (поток сервера). У чанка без руин — и по тем же блокам; у чанка
     * с руинами блоки дальше меняют сами руины (огонь, текущая вода, брёвна), а исходный мир его мест — из плана.
     */
    boolean current(LevelChunk chunk, @Nullable RuinContext.Applied applied) {
        if (System.identityHashCode(chunk) != id || !fresh() || ruined != (applied != null)) return false;
        return applied != null ? applied.plan() == old : sameBlocks(chunk);
    }

    /**
     * Блоки живого чанка те же, что в снимке: отпечатки секций, а где они разошлись — блоки с точностью до погашенных
     * ламп (блэкаут меняет их прямо в секциях); такой отпечаток заменяется живым, и следующая сверка снова дешёвая.
     */
    private boolean sameBlocks(LevelChunk chunk) {
        LevelChunkSection[] live = chunk.getSections();
        if (live.length != sections) return false;
        for (int i = 0; i < sections; i++) {
            long p = rawPrint(live[i]);
            if (p == prints[i]) continue;
            if (normalPrint(live[i]) != normalPrint(states[i], RuinPlan::normal)) return false;
            prints[i] = p;
        }
        return true;
    }

    /** Отпечатки секций снимка с точностью до погашенных ламп (любой поток: состояния — через таблицу свойств). */
    long[] normalPrints(Blast.PropsView view) {
        long[] out = new long[sections];
        for (int i = 0; i < sections; i++) out[i] = normalPrint(states[i], st -> view.get(st).normal());
        return out;
    }

    /** Отпечаток секции мира как есть ({@link #rawPrint(PalettedContainer)}). */
    static long rawPrint(LevelChunkSection s) {
        return rawPrint(s.hasOnlyAir() ? null : s.getStates());
    }

    /**
     * Отпечаток секции как есть: состояния палитры по номерам и хранилище номеров (сотни {@code long}, дёшево). Те же
     * блоки дают другой отпечаток, когда палитру перестроили или лампу погасили; тогда решает {@link #normalPrint}.
     */
    static long rawPrint(@Nullable PalettedContainer<BlockState> c) {
        if (c == null) return AIR_PRINT;
        var data = c.data;
        long h = data.storage().getBits();
        Palette<BlockState> palette = data.palette();
        if (!(palette instanceof GlobalPalette)) {
            for (int k = 0; k < palette.getSize(); k++) h = mix(h, System.identityHashCode(palette.valueFor(k)));
        }
        for (long v : data.storage().getRaw()) h = mix(h, v);
        return h;
    }

    /** Отпечаток секции мира по местам с точностью до погашенных ламп (поток сервера). */
    static long normalPrint(LevelChunkSection s) {
        return normalPrint(s.hasOnlyAir() ? null : s.getStates(), RuinPlan::normal);
    }

    /**
     * Отпечаток секции по местам: состояние каждого места, приведённое {@code normal} (лампа и её двойник — одно).
     * Состояние — по {@code identityHashCode} (состояния — одиночки), не {@code Block.getId}: отпечатки считают и фоновые
     * потоки руин, а им код блоков нельзя ({@code backgroundSolversCallNoWorld}).
     */
    static long normalPrint(@Nullable PalettedContainer<BlockState> c, java.util.function.Function<BlockState, BlockState> normal) {
        if (c == null) return AIR_PRINT;
        var data = c.data;
        Palette<BlockState> palette = data.palette();
        BitStorage storage = data.storage();
        long h = 0;
        if (palette instanceof GlobalPalette) {
            it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap seen = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
            for (int i = 0; i < storage.getSize(); i++) {
                h = mix(h, seen.computeIfAbsent(storage.get(i), id -> System.identityHashCode(normal.apply(palette.valueFor(id)))));
            }
        } else {
            int[] norm = new int[palette.getSize()];
            for (int k = 0; k < norm.length; k++) norm[k] = System.identityHashCode(normal.apply(palette.valueFor(k)));
            for (int i = 0; i < storage.getSize(); i++) h = mix(h, norm[storage.get(i)]);
        }
        return h;
    }

    private static long mix(long h, long v) {
        return (h ^ v) * 0x9E3779B97F4A7C15L + 1;
    }

    /** Снимок с диска, а не из памяти. */
    boolean fromDisk() {
        return id == 0;
    }

    /** Таблицу свойств после снимка не сбрасывали. */
    boolean fresh() {
        return generation == Blast.generation;
    }

    ChunkPos pos() {
        return new ChunkPos(x, z);
    }

    int maxY() {
        return minY + (sections << 4);
    }

    /** Исходное состояние места (y — мира, внутри высоты мира). */
    BlockState get(int lx, int y, int lz) {
        if (ruined) {
            if (old != null) {
                BlockState o = old.oldState(lx, y, lz, minY);
                if (o != null) return o;
            }
            BlockState now = raw(lx, y, lz);
            // последствия руин: огонь и растёкшаяся вода — на месте воздуха
            if (now.getBlock() instanceof BaseFireBlock || !now.getFluidState().isEmpty() && !now.getFluidState().isSource()) return AIR;
            return now;
        }
        return raw(lx, y, lz);
    }

    /** Состояние места в чанке при снимке (с руинами, если они стояли). */
    BlockState raw(int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (i < 0 || i >= sections) return AIR;
        return states[i] == null ? AIR : states[i].get(lx, y & 15, lz);
    }

    /** Свойства мест секции {@code i} (номер от низа мира). */
    Sec props(int i, Blast.PropsView view) {
        Sec p = props.get(i);
        if (p != null) return p;
        PalettedContainer<BlockState> c = states[i];
        if (ruined || c != null && c.data.palette() instanceof GlobalPalette) {
            // по месту: старые блоки плана поверх секции, или глобальная палитра (номер — во всём реестре)
            Blast.Props[] full = new Blast.Props[4096];
            int y0 = minY + (i << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) full[y << 8 | z << 4 | x] = view.get(get(x, y0 + y, z));
                }
            }
            p = new Sec(null, null, full);
        } else if (c == null) {
            p = new Sec(new ZeroBitStorage(4096), new Blast.Props[]{view.get(AIR)}, null);
        } else {
            Palette<BlockState> palette = c.data.palette();
            Blast.Props[] local = new Blast.Props[palette.getSize()];
            for (int k = 0; k < local.length; k++) local[k] = view.get(palette.valueFor(k));
            p = new Sec(c.data.storage(), local, null);
        }
        return props.compareAndSet(i, null, p) ? p : props.get(i);
    }

    /** Может ли в секции быть состояние из {@code test} (по палитре копии). */
    boolean mayHave(int i, java.util.function.Predicate<BlockState> test) {
        if (i < 0 || i >= sections) return false;
        if (ruined && old != null && old.anyOld(i, test)) return true;
        return states[i] == null ? test.test(AIR) : states[i].maybeHas(test);
    }
}
