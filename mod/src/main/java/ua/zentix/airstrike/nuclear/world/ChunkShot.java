package ua.zentix.airstrike.nuclear.world;

import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Исходные блоки чанка для руин — снимок, снятый в потоке сервера: копии палитр секций ({@code PalettedContainer.copy},
 * живые секции только читаются), карты высот верха и опоры, у чанка с руинами — старые блоки мест его плана. Из снимков
 * окно чанка ({@link RuinWindow}) строится в любом потоке: снимок больше не читает мир и не меняется.
 * <p>
 * Свойства блоков ({@link Blast.Props}) по местам секции считаются при первом чтении секции в любом потоке — по таблице,
 * которую поток сервера заполнил по палитрам снимка ({@link Blast#ensure}); готовый массив секции публикуется
 * атомарно, и его берут все окна, которые читают этот чанк (у соседей их до 25).
 * <p>
 * По чему снят — {@code identityHashCode} чанка, счётчик изменений ({@link RuinContext#edits}) и стояли ли руины:
 * снимок верен, пока они те же и таблицу свойств не сбрасывали ({@link #current}).
 */
final class ChunkShot {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final int id;
    final long edits;
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
    /** {@code LevelChunk.getHeight} (верхний блок) карт высот {@code WORLD_SURFACE} и {@code MOTION_BLOCKING_NO_LEAVES} (индекс {@code lz << 4 | lx}). */
    final int[] surface = new int[256], solid = new int[256];
    /** Свойства мест секции (индекс {@code y << 8 | z << 4 | x}), по мере чтения. */
    private final AtomicReferenceArray<Blast.Props[]> props;
    /** Сколько байт занимают копии палитр (память подрыва). */
    final long bytes;

    @SuppressWarnings("unchecked")
    private ChunkShot(LevelChunk chunk, long edits, @Nullable RuinPlan old, boolean ruined) {
        this.id = System.identityHashCode(chunk);
        this.edits = edits;
        this.ruined = ruined;
        this.old = old;
        this.minY = chunk.getMinBuildHeight();
        LevelChunkSection[] live = chunk.getSections();
        this.sections = live.length;
        this.states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[sections];
        long size = 0;
        for (int i = 0; i < sections; i++) {
            if (live[i].hasOnlyAir()) continue;
            PalettedContainer<BlockState> copy = live[i].getStates().copy();
            states[i] = copy;
            size += copy.getSerializedSize();
            // свойства всех состояний палитры — в таблицу, пока мы в потоке сервера; у глобальной палитры (больше 256
            // состояний в секции) список не перебрать — по всем местам
            boolean[] listed = {false};
            if (copy.maybeHas(st -> {
                listed[0] = true;
                Blast.ensure(st);
                return false;
            }) && !listed[0]) {
                copy.getAll(Blast::ensure);
            }
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                surface[lz << 4 | lx] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                solid[lz << 4 | lx] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
            }
        }
        if (old != null) {
            this.oldTops = old.oldTops(minY);
            old.forEachOld(Blast::ensure);
        } else {
            this.oldTops = null;
        }
        this.props = new AtomicReferenceArray<>(sections);
        this.bytes = size + 2048;
    }

    /** Снимок чанка (поток сервера): {@code applied} — руины чанка уже стоят (их план — или null, если отпущен). */
    static ChunkShot take(LevelChunk chunk, long edits, @Nullable RuinContext.Applied applied) {
        return new ChunkShot(chunk, edits, applied == null ? null : applied.plan(), applied != null);
    }

    /** Снимок по тому же чанку, счётчику изменений и руинам, что и сейчас. */
    boolean current(LevelChunk chunk, long edits, @Nullable RuinContext.Applied applied) {
        return System.identityHashCode(chunk) == id && this.edits == edits && generation == Blast.generation && ruined == (applied != null)
                && (applied == null || applied.plan() == old);
    }

    /** Исходное состояние места (y — мира, внутри высоты мира). */
    BlockState get(int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (ruined) {
            if (old != null) {
                BlockState o = old.oldState(lx, y, lz, minY);
                if (o != null) return o;
            }
            BlockState now = states[i] == null ? AIR : states[i].get(lx, y & 15, lz);
            // последствия руин: огонь и растёкшаяся вода — на месте воздуха
            if (now.getBlock() instanceof BaseFireBlock || !now.getFluidState().isEmpty() && !now.getFluidState().isSource()) return AIR;
            return now;
        }
        return states[i] == null ? AIR : states[i].get(lx, y & 15, lz);
    }

    /** Свойства мест секции {@code i} (номер от низа мира), индекс {@code y << 8 | z << 4 | x}. */
    Blast.Props[] props(int i, Blast.PropsView view) {
        Blast.Props[] p = props.get(i);
        if (p != null) return p;
        p = new Blast.Props[4096];
        if (states[i] == null && !ruined) {
            java.util.Arrays.fill(p, view.get(AIR));
        } else {
            int y0 = minY + (i << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) p[y << 8 | z << 4 | x] = view.get(get(x, y0 + y, z));
                }
            }
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
