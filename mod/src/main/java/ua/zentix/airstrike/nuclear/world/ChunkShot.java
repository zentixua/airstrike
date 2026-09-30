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
 * По чему снят — {@code identityHashCode} чанка, счётчик изменений ({@link RuinContext#edits}) и стояли ли руины:
 * снимок верен, пока они те же и таблицу свойств не сбрасывали ({@link #current}). У снимка с диска чанка нет: номер 0,
 * счётчик −1 — подмена проверит план по хешу старых состояний и посчитает столбцы заново.
 */
final class ChunkShot {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final int x, z;
    final int id;
    final long edits;
    /** Счётчик изменений живого чанка при снимке ({@link RuinPlan#edits}), −1 — нет (с диска): его помнит план. */
    final long planEdits;
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

    private ChunkShot(int x, int z, int id, long edits, long planEdits, @Nullable RuinPlan old, boolean ruined, int minY,
                      PalettedContainer<BlockState>[] states, int[] heights, @Nullable int[] sky) {
        this.x = x;
        this.z = z;
        this.id = id;
        this.edits = edits;
        this.planEdits = planEdits;
        this.ruined = ruined;
        this.old = old;
        this.minY = minY;
        this.states = states;
        this.sections = states.length;
        this.heights = heights;
        this.sky = sky;
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
    static ChunkShot take(LevelChunk chunk, long edits, @Nullable RuinContext.Applied applied) {
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
        return new ChunkShot(pos.x, pos.z, System.identityHashCode(chunk), edits, RuinPlan.edits(chunk), applied == null ? null : applied.plan(),
                applied != null, chunk.getMinBuildHeight(), states, heights, sky);
    }

    /**
     * Снимок чанка с диска (поток сервера: его состояния — в таблицу), см. {@link DiskShots}; {@code applied} — руины
     * чанка уже стоят (чанк был в памяти после подрыва и ушёл ниже полной загрузки): исходный мир мест его плана — из
     * плана, как у снимка из памяти.
     */
    static ChunkShot fromDisk(DiskShots.Read read, @Nullable RuinContext.Applied applied) {
        return new ChunkShot(read.pos().x, read.pos().z, 0, -1, -1, applied == null ? null : applied.plan(), applied != null,
                read.minY(), read.states(), read.heights(), null);
    }

    /** Снимок по тому же чанку, счётчику изменений и руинам, что и сейчас. */
    boolean current(LevelChunk chunk, long edits, @Nullable RuinContext.Applied applied) {
        return System.identityHashCode(chunk) == id && this.edits == edits && fresh() && ruined == (applied != null)
                && (applied == null || applied.plan() == old);
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
