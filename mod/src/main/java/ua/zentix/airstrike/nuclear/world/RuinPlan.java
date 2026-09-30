package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.DhChunks;
import ua.zentix.airstrike.grid.GridLights;
import ua.zentix.airstrike.util.Terrain;

import java.util.List;

/**
 * Руины одного чанка, построенные заранее ({@link RuinPlanner}), и их подмена в мире одним махом, когда до чанка дошла
 * волна: не блок за блоком ({@code setBlock} с картами высот, светом, соседями и Sable — ≈10 мкс на блок, в городе
 * тысячи блоков на чанк), а записью мест плана прямо в секции чанка и работой {@code setBlock} один раз на чанк.
 * <p>
 * План — только разница: изменённые места (по 4 байта: место в секции, секция, новое состояние из палитры плана,
 * «проверить свет», «через мир»), пожары, карты высот и нижние источники неба изменённых столбцов (значение до и после)
 * и места проверок света. Своих копий секций план не держит. Всё остальное в секции остаётся как есть к приходу волны —
 * лампы, которые погасил блэкаут (он меняет их прямо в палитре), блоки, поставленные после плана.
 * <p>
 * План устаревает, если места плана после него меняли (игрок, взрыв, другой мод): у каждой изменённой секции — хеш
 * старых состояний в местах плана (лампа и её погашенный двойник — одно и то же). Не совпал — план строится заново
 * по чанку как есть.
 * <p>
 * Подмена повторяет то, что делает {@code LevelChunk.setBlockState} для каждого блока, один раз на чанк: места — в
 * секции на месте (как и у {@code setBlock}; секцию не заменяют чтением в неё — см. подводные камни), карты высот и
 * источники неба столбцов — из плана (значение «до» в чанке уже другое — столбец меняли после плана — весь чанк
 * заново), пустота секций — движку света, проверки света (верхняя изменённая точка столбца, новые и убранные блоки под
 * уцелевшим верхом, убранные и новые источники света) — одной задачей движку света. Игроки, которые видят чанк,
 * получают изменения в том же тике пакетами секций ({@code ChunkHolder.blockChanged}, как при {@code setBlock}), LOD
 * Distant Horizons — сразу ({@link DhChunks}). Блоки с блок-сущностью и места POI так не меняются: после подмены их
 * меняет мир ({@link ColumnScar#replace}: {@code onRemove} с его последствиями, содержимое не высыпается, отложенные
 * данные снимаются). Стволы, упавшие в соседний чанк, кладёт очередь после руин соседа. Пожары ставятся вместе
 * с руинами (без {@code setBlock}: огонь у стен и деревьев не будит соседей), им только назначается тик огня.
 */
public final class RuinPlan {
    /** Карты высот чанка на сервере; номер в этом массиве — вид карты в {@link #heights}. */
    public static final Heightmap.Types[] HEIGHTMAP_TYPES = {Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE};
    /** Вид в {@link #heights}: нижний источник неба столбца. */
    static final int SKY = HEIGHTMAP_TYPES.length;

    /** Упаковка места: 12 бит — место в секции (y, z, x), 8 бит — номер секции, 10 бит — состояние в палитре, флаги. */
    static final int SECTION_SHIFT = 12, STATE_SHIFT = 20, STATE_MASK = 0x3FF;
    /** Флаг места: после подмены проверить свет. */
    static final int LIGHT = 1 << 30;
    /** Флаг места: блок-сущность или POI — меняется через мир. */
    static final int SLOW = 1 << 31;
    /** Сколько разных состояний вмещает палитра плана. */
    static final int MAX_STATES = STATE_MASK + 1;

    /** Тиков жидкости за секунду на чанк (остальные — в следующие секунды). */
    private static final int FLUID_TICKS = 64;
    private static final int[] NONE = new int[0];
    private static final long[] NO_LONGS = new long[0];
    private static final BlockState[] NO_STATES = new BlockState[0];

    /** План без изменений: чанк, где волне нечего менять (поле, вода, чанк у края зоны). */
    static final RuinPlan EMPTY = new RuinPlan(NONE, NO_LONGS, NO_STATES, NO_STATES, NONE, NONE, NO_LONGS, 0, -1, null, null, null, NO_LONGS);

    /** Нечего менять, но руины чанка «стоят»: соседи читают его исходным (он и есть исходный), счётчики — на подмене. */
    static RuinPlan nothing(int chunkId, long edits, @Nullable RuinContext ctx) {
        return ctx == null ? EMPTY : new RuinPlan(NONE, NO_LONGS, NO_STATES, NO_STATES, NONE, NONE, NO_LONGS, chunkId, edits, null, null, ctx, NO_LONGS);
    }

    /**
     * Идёт подмена руин (поток сервера): изменения чанков в это время — сами руины и их последствия, счётчик изменений
     * ({@link Edits}) их не считает — иначе план соседа устаревал бы от руин этого чанка.
     */
    private static boolean applying;

    public static boolean applying() {
        return applying;
    }

    /**
     * Для строки в лог: время подмен по частям, нс — проверка плана, места и пожары в секциях (и пустота секций
     * движку света), карты высот и источники неба, свет и пакеты игрокам, блок-сущности через мир.
     */
    static final long[] PHASES = new long[5];
    /** Для строки в лог: самая долгая часть «через мир» — время (нс), чанк, сколько мест через мир (с прошлой строки). */
    static long slowestWorldNanos, slowestWorldChunk;
    static int slowestWorldCells;
    /** В этом чанке: самый долгий блок через мир (его замена), и вызов LOD Distant Horizons, нс. */
    @Nullable
    static BlockState slowestWorldBlock;
    static long slowestWorldBlockNanos, slowestWorldDhNanos;

    /**
     * Карты высот и источники неба изменённых столбцов, посчитанные по плану: тройки (столбец {@code << 3} | вид —
     * номер в {@link #HEIGHTMAP_TYPES} или {@link #SKY}; значение до руин; значение после). Только те, что меняются.
     */
    private final int[] heights;
    /** Места с проверкой света (кроме мест «через мир»: их свет проверяет мир), как {@link BlockPos#asLong}. */
    private final long[] lightAt;
    /** Чанк плана ({@code identityHashCode}) и его счётчик изменений блоков ({@link Edits}) при построении плана; −1 — счётчика нет. */
    private final int chunkId;
    private final long edits;
    /** Места плана по секциям (по возрастанию номера секции). */
    private final int[] cells;
    /** По номеру секции: хеш старых состояний в её местах (секции без мест — 0, не проверяются). */
    private final long[] oldHashes;
    private final BlockState[] states;
    /** Старые состояния мест плана (по порядку {@link #cells}): соседи читают по ним исходный чанк, пока его руины стоят. */
    private final BlockState[] olds;
    /** Руины подрыва по всем чанкам (подмена отмечается там: её старые блоки — исходный мир соседей); null — вне подрыва. */
    @Nullable
    private final RuinContext ctx;
    /** Жидкость у изменённых мест: место ({@link BlockPos#asLong}) — ей тик, чтобы она затекла в пролом или стекла. */
    private final long[] fluidTicks;
    /** Пожары: место (как в {@link #cells}) и состояние огня из палитры. */
    private final int[] fires;
    /** Брёвна поваленных деревьев в соседних чанках: место (высота — по поверхности при подмене) и состояние. */
    private final LongArrayList outside;
    private final List<BlockState> outsideState;

    RuinPlan(int[] cells, long[] oldHashes, BlockState[] states, BlockState[] olds, int[] fires, int[] heights, long[] lightAt, int chunkId, long edits,
            LongArrayList outside, List<BlockState> outsideState, @Nullable RuinContext ctx, long[] fluidTicks) {
        this.olds = olds;
        this.ctx = ctx;
        this.fluidTicks = fluidTicks;
        this.chunkId = chunkId;
        this.edits = edits;
        this.heights = heights;
        this.lightAt = lightAt;
        this.cells = cells;
        this.oldHashes = oldHashes;
        this.states = states;
        this.fires = fires;
        this.outside = outside;
        this.outsideState = outsideState;
    }

    /**
     * Счётчик изменений блоков чанка через {@code LevelChunk.setBlockState} (миксин {@code LevelChunkEditsMixin}): чанк
     * меняли после плана и вне мест плана (блок внутри дома) — карты высот из плана могли устареть.
     */
    public interface Edits {
        long airstrike$edits();
    }

    /** Работает ли счётчик ({@link #edits}): 0 — ещё не проверяли, 1 — да, −1 — нет. На всю JVM: миксины — тоже. */
    private static int editsWork;

    /**
     * Счётчик изменений блоков чанка; −1 — счётчика нет (миксин не встал), и подмена тогда считает столбцы плана
     * заново всегда. Первый вызов проверяет счётчик на деле: запись в чанк того же состояния, что там уже стоит
     * ({@code setBlockState} выходит сразу, ничего не меняя), должна его сдвинуть. Только в потоке сервера.
     */
    static long edits(LevelChunk chunk) {
        if (!(chunk instanceof Edits e)) return -1;
        if (editsWork == 0) {
            BlockPos p = new BlockPos(chunk.getPos().getMinBlockX() + 8, chunk.getMinBuildHeight(), chunk.getPos().getMinBlockZ() + 8);
            long before = e.airstrike$edits();
            chunk.setBlockState(p, chunk.getBlockState(p), false);
            editsWork = e.airstrike$edits() != before ? 1 : -1;
            if (editsWork < 0) {
                Airstrike.LOG.warn("Руины ядерки: счётчик изменений чанка (LevelChunkEditsMixin) не встал — карты высот столбцов руин считаются заново при каждой подмене");
            }
        }
        return editsWork > 0 ? e.airstrike$edits() : -1;
    }

    /** Состояние для сравнения со старым: погашенная блэкаутом лампа — та же лампа. */
    static BlockState normal(BlockState s) {
        BlockState lit = GridLights.lit(s);
        return lit != null ? lit : s;
    }

    /** Добавить старое состояние места к хешу секции (порядок мест — как в плане). */
    static long mix(long h, BlockState old) {
        return (h ^ System.identityHashCode(normal(old))) * 0x9E3779B97F4A7C15L + 1;
    }

    /** Для проверок: чем план отличается от другого (те же места, новые и старые состояния, пожары); null — ничем. */
    @Nullable
    public String differs(RuinPlan o) {
        int mask = (1 << STATE_SHIFT) - 1;
        java.util.TreeMap<Integer, String> a = new java.util.TreeMap<>(), b = new java.util.TreeMap<>();
        for (int k = 0; k < cells.length; k++) a.put(cells[k] & mask, olds[k] + "→" + states[(cells[k] >>> STATE_SHIFT) & STATE_MASK]);
        for (int k = 0; k < o.cells.length; k++) b.put(o.cells[k] & mask, o.olds[k] + "→" + o.states[(o.cells[k] >>> STATE_SHIFT) & STATE_MASK]);
        int n = 0;
        StringBuilder out = new StringBuilder();
        java.util.TreeSet<Integer> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (int c : keys) {
            if (java.util.Objects.equals(a.get(c), b.get(c))) continue;
            if (n++ < 4) out.append(" [x ").append(c & 15).append(" y ").append((section(c) << 4) + ((c >> 8) & 15)).append(" z ").append((c >> 4) & 15)
                    .append(": ").append(a.get(c)).append(" | ").append(b.get(c)).append(']');
        }
        // пожары — по местам: тень светового импульса у соседа, чьи руины уже стоят, — та же, что до них
        java.util.TreeSet<Integer> fa = new java.util.TreeSet<>(), fb = new java.util.TreeSet<>();
        for (int c : fires) fa.add(c & mask);
        for (int c : o.fires) fb.add(c & mask);
        java.util.TreeSet<Integer> onlyA = new java.util.TreeSet<>(fa), onlyB = new java.util.TreeSet<>(fb);
        onlyA.removeAll(fb);
        onlyB.removeAll(fa);
        if (!onlyA.isEmpty() || !onlyB.isEmpty()) {
            out.append(" пожаров ").append(fa.size()).append(" | ").append(fb.size()).append(", только слева ").append(onlyA.size())
                    .append(", только справа ").append(onlyB.size());
        }
        // карты высот и нижние источники неба изменённых столбцов (у снимка с диска источники неба посчитаны по блокам)
        boolean heightsDiffer = !java.util.Arrays.equals(heights, o.heights);
        if (heightsDiffer) out.append(" карты высот различаются (").append(heights.length).append(" | ").append(o.heights.length).append(')');
        return n == 0 && onlyA.isEmpty() && onlyB.isEmpty() && !heightsDiffer ? null : n + " мест" + out;
    }

    /** Сколько тиков жидкости ставит план. */
    public int fluidTickCount() {
        return fluidTicks.length;
    }

    /** Больше всего тиков жидкости плана за одну секунду его расписания (проверки): не больше {@link #FLUID_TICKS}. */
    public int maxFluidTicksPerSecond() {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap at = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int most = 0;
        for (int k = 0; k < fluidTicks.length; k++) {
            long p = fluidTicks[k];
            int second = (fluidDelay(k, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)) - FLUID_DELAY) / 20;
            most = Math.max(most, at.addTo(second, 1) + 1);
        }
        return most;
    }

    /** Первый тик жидкости — через столько тиков после подмены. */
    private static final int FLUID_DELAY = 5;

    /**
     * Через сколько тиков тик жидкости {@code k} плана: по {@link #FLUID_TICKS} на секунду по порядку, внутри секунды —
     * вразнобой по хешу места.
     */
    private static int fluidDelay(int k, int x, int y, int z) {
        return FLUID_DELAY + 20 * (k / FLUID_TICKS) + (int) (RuinPlanner.hash(x, y, z, 37) * 20);
    }

    /** Сколько пожаров ставит план (проверки). */
    public int fireCount() {
        return fires.length;
    }

    /** Сколько блоков меняет план (без пожаров). */
    public int changedBlocks() {
        return cells.length;
    }

    /** Примерный размер плана в памяти, байты (для строки в лог). */
    public long bytes() {
        if (this == EMPTY) return 0;
        return 64 + 12L * cells.length + 8L * fluidTicks.length + 8L * oldHashes.length + 8L * states.length + 4L * fires.length + 4L * heights.length
                + 8L * lightAt.length + (outside == null ? 0 : 16L * outside.size());
    }

    private static int section(int cell) {
        return (cell >>> SECTION_SHIFT) & 0xFF;
    }

    /** Места плана в секциях чанка всё те же, что при построении плана. */
    boolean current(LevelChunk chunk) {
        LevelChunkSection[] now = chunk.getSections();
        int k = 0;
        while (k < cells.length) {
            int i = section(cells[k]);
            if (i >= now.length) return false;
            LevelChunkSection s = now[i];
            long h = 0;
            for (; k < cells.length && section(cells[k]) == i; k++) {
                int c = cells[k];
                h = mix(h, s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15));
            }
            if (h != oldHashes[i]) return false;
        }
        return true;
    }

    /**
     * Старое состояние места чанка до этих руин (y — мира) или null, если место не из плана. Для соседей, которые
     * строят свои планы по исходному миру, пока руины этого чанка уже стоят.
     */
    @Nullable
    BlockState oldState(int lx, int y, int lz, int minY) {
        int i = (y - minY) >> 4;
        if (i < 0 || i >= oldHashes.length || oldHashes[i] == 0) return null;
        int key = i << SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx;
        int lo = 0, hi = cells.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1, k = cells[m] & (1 << STATE_SHIFT) - 1;
            if (k < key) lo = m + 1;
            else if (k > key) hi = m - 1;
            else return olds[m];
        }
        return null;
    }

    /** Каждое старое состояние мест плана (с повторами). */
    void forEachOld(java.util.function.Consumer<BlockState> each) {
        for (BlockState o : olds) each.accept(o);
    }

    /** Есть ли среди старых состояний мест секции {@code section} такое, что {@code test}. */
    boolean anyOld(int section, java.util.function.Predicate<BlockState> test) {
        if (section < 0 || section >= oldHashes.length || oldHashes[section] == 0) return false;
        for (int k = 0; k < cells.length; k++) if (section(cells[k]) == section && test.test(olds[k])) return true;
        return false;
    }

    /** Верх столбцов по старым блокам мест плана: [0..255] — не-воздух, [256..511] — опора без листвы (или жидкость); MIN — нет. */
    int[] oldTops(int minY) {
        int[] t = oldTops;
        if (t != null) return t;
        t = new int[512];
        java.util.Arrays.fill(t, Integer.MIN_VALUE);
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k], column = c & 0xFF, y = minY + (section(c) << 4) + ((c >> 8) & 15);
            BlockState o = olds[k];
            if (!o.isAir()) t[column] = Math.max(t[column], y);
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(o)) t[256 + column] = Math.max(t[256 + column], y);
        }
        return oldTops = t;
    }

    @Nullable
    private int[] oldTops;

    /**
     * Поставить руины в чанк (он и соседи загружены целиком — проверяет вызывающий). Устаревший план не ставится.
     *
     * @return false — план устарел, ничего не изменено
     */
    public boolean apply(ServerLevel level, LevelChunk chunk, ColumnScar.Budget budget) {
        if (this == EMPTY) return true;
        long t = System.nanoTime();
        if (!current(chunk)) return false;
        long before = edits(chunk);
        short[] motion = new short[256];
        for (int column = 0; column < 256; column++) motion[column] = (short) (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, column & 15, column >> 4) + 1);
        applying = true;
        try {
            if (cells.length > 0 || fires.length > 0 || outside != null || fluidTicks.length > 0) write(level, chunk, budget, t);
        } finally {
            applying = false;
        }
        if (ctx != null) ctx.applied(chunk, this, before, motion);
        return true;
    }

    private boolean write(ServerLevel level, LevelChunk chunk, ColumnScar.Budget budget, long t) {
        PHASES[0] += System.nanoTime() - t;
        t = System.nanoTime();
        ChunkPos pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // места плана — прямо в секциях чанка, как setBlock (он тоже меняет секцию на месте, пока поток света читает
        // чанк), но без его работы на каждый блок; блок-сущности и POI — через мир после подмены (их onRemove:
        // содержимое, половинки сундука, конвейеры Create)
        LevelChunkSection[] now = chunk.getSections();
        // изменённые секции и какие из них были пустыми (по биту на секцию: секций бывает и больше 64)
        long[] touched = new long[(now.length + 63) >> 6], wasEmpty = new long[touched.length];
        for (int c : cells) {
            if ((c & SLOW) != 0) continue;
            int i = section(c);
            LevelChunkSection s = now[i];
            if (!bit(touched, i)) {
                set(touched, i);
                if (s.hasOnlyAir()) set(wasEmpty, i);
            }
            s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK], false);
        }
        // пожары — там, где по плану воздух, пока не кончился счётчик пожаров подрыва
        int lit = 0;
        boolean ignites = budget.ignites && AirstrikeConfig.SERVER.nukeFires.get();
        int maxFires = AirstrikeConfig.SERVER.nukeMaxFires.get();
        int[] burning = fires.length == 0 || !ignites ? NONE : new int[fires.length];
        for (int k = 0; k < burning.length && budget.fires < maxFires; k++) {
            int c = fires[k];
            int i = section(c);
            LevelChunkSection s = now[i];
            if (!s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15).isAir()) continue;
            if (!bit(touched, i)) {
                set(touched, i);
                if (s.hasOnlyAir()) set(wasEmpty, i);
            }
            s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK], false);
            burning[lit++] = c;
            budget.fires++;
        }
        // Sable держит копию блоков мира у аппаратов и видит только LevelChunk.setBlockState: у аппаратов — сообщить
        if (ua.zentix.airstrike.compat.SableTerrain.present() && ua.zentix.airstrike.compat.SableTerrain.watched(level, chunk)) {
            for (int k = 0; k < cells.length; k++) {
                int c = cells[k];
                if ((c & SLOW) != 0) continue;
                at(m, c, x0, z0, minY);
                ua.zentix.airstrike.compat.SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), olds[k], states[(c >>> STATE_SHIFT) & STATE_MASK]);
            }
            for (int k = 0; k < lit; k++) {
                at(m, burning[k], x0, z0, minY);
                ua.zentix.airstrike.compat.SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        states[(burning[k] >>> STATE_SHIFT) & STATE_MASK]);
            }
        }
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        for (int i = 0; i < now.length; i++) {
            if (!bit(touched, i)) continue;
            boolean empty = now[i].hasOnlyAir();
            if (bit(wasEmpty, i) != empty) light.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), empty);
        }
        PHASES[1] += System.nanoTime() - t;
        t = System.nanoTime();
        // карты высот и источники неба — из плана; чанк меняли после плана вне мест плана (блок внутри дома) или
        // значение «до» в столбце не то — все столбцы плана заново по чанку
        boolean untouched = edits >= 0 && chunkId == System.identityHashCode(chunk) && edits(chunk) == edits;
        if (!untouched || !setHeights(chunk)) rescanColumns(chunk);
        if (lit > 0) {
            // огонь не воздух, но не держит движение и не закрывает небо: из карт высот он меняет только поверхность мира
            Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            for (int k = 0; k < lit; k++) {
                int c = burning[k];
                surface.update(c & 15, minY + (section(c) << 4) + ((c >> 8) & 15), (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK]);
            }
        }
        PHASES[2] += System.nanoTime() - t;
        t = System.nanoTime();
        // кто видит чанк, получает изменения в этом же тике — пакетами секций (как setBlock), а не очередью чанков
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        List<ServerPlayer> players = holder == null ? List.of() : level.getChunkSource().chunkMap.getPlayers(pos, false);
        boolean watched = !players.isEmpty();
        boolean sections = watched && holder.getTickingChunk() != null;
        if (sections) {
            for (int c : cells) if ((c & SLOW) == 0) holder.blockChanged(at(m, c, x0, z0, minY));
        }
        long[] checks = lightAt;
        if (lit > 0) {
            checks = java.util.Arrays.copyOf(lightAt, lightAt.length + lit);
        }
        for (int k = 0; k < lit; k++) {
            at(m, burning[k], x0, z0, minY);
            checks[lightAt.length + k] = m.asLong();
            if (sections) holder.blockChanged(m);
            // тик огня, как у поставленного огня (FireBlock.onPlace): 30–39 тиков
            level.scheduleTick(m.immutable(), states[(burning[k] >>> STATE_SHIFT) & STATE_MASK].getBlock(),
                    30 + (int) (RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 31) * 10));
        }
        checkLight(light, pos, checks);
        // жидкость у пролома (и у соседей): тик, как дал бы ей setBlock соседа, — вразнобой по хешу места и не больше
        // FLUID_TICKS за секунду: подмена не будит соседей, без тика вода стояла бы стеной
        for (int k = 0; k < fluidTicks.length; k++) {
            m.set(fluidTicks[k]);
            BlockState st = level.getBlockState(m);
            if (st.getFluidState().isEmpty()) continue;
            level.scheduleTick(m.immutable(), st.getFluidState().getType(), fluidDelay(k, m.getX(), m.getY(), m.getZ()));
        }
        PHASES[3] += System.nanoTime() - t;
        t = System.nanoTime();
        chunk.setUnsaved(true);
        // самый долгий блок чанка — в строку лога: onRemove бывает дорогим (сеть валов Create обходится целиком,
        // конвейер снимает всю цепочку)
        BlockState slowBlock = null;
        long slowBlockNanos = 0;
        for (int c : cells) {
            if ((c & SLOW) == 0) continue;
            at(m, c, x0, z0, minY);
            BlockState old = chunk.getBlockState(m);
            long b = System.nanoTime();
            ColumnScar.replace(level, m, old, states[(c >>> STATE_SHIFT) & STATE_MASK]);
            b = System.nanoTime() - b;
            if (b > slowBlockNanos) {
                slowBlockNanos = b;
                slowBlock = old;
            }
        }
        // видит, но чанк не тикает (край прорисовки): чанк целиком ванильной отправкой
        if (watched && !sections) {
            for (ServerPlayer p : players) p.connection.chunkSender.markChunkPendingToSend(chunk);
        }
        // LOD Distant Horizons: руины вместе с волной, а не при следующем сохранении чанка
        long dh = System.nanoTime();
        DhChunks.changed(level, chunk);
        long took = System.nanoTime() - t;
        dh = System.nanoTime() - dh;
        PHASES[4] += took;
        if (took > slowestWorldNanos) {
            slowestWorldNanos = took;
            slowestWorldChunk = pos.toLong();
            int n = 0;
            for (int c : cells) if ((c & SLOW) != 0) n++;
            slowestWorldCells = n;
            slowestWorldBlock = slowBlock;
            slowestWorldBlockNanos = slowBlockNanos;
            slowestWorldDhNanos = dh;
        }
        return true;
    }

    /**
     * Проверки света чанка — одной задачей движка света (как {@code LevelLightEngine.checkBlock} в его потоке), а не
     * задачей с почтой на каждое место: сотни мест на чанк, тысячи чанков за пару секунд. Движок света не ванильный
     * (другой мод) — по одному через его {@code checkBlock}.
     */
    private static void checkLight(LevelLightEngine light, ChunkPos pos, long[] at) {
        if (at.length == 0) return;
        // движок блоков есть всегда; нет — свет считает другой мод (Starlight, ScalableLux): задача была бы пустой
        if (light instanceof ThreadedLevelLightEngine threaded && light.blockEngine != null && vanilla(light.blockEngine, BlockLightEngine.class)
                && vanilla(light.skyEngine, SkyLightEngine.class)) {
            var block = light.blockEngine;
            var sky = light.skyEngine;
            threaded.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (long a : at) {
                    p.set(a);
                    block.checkBlock(p);
                    if (sky != null) sky.checkBlock(p);
                }
            });
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (long a : at) light.checkBlock(p.set(a));
    }

    /**
     * Карты высот и источники неба столбцов из плана. Значение до руин в чанке уже не то (столбец меняли после плана) —
     * false, ничего не изменено: вызывающий пересчитает столбцы плана по чанку.
     */
    private boolean setHeights(LevelChunk chunk) {
        ChunkSkyLightSources sky = chunk.getSkyLightSources();
        for (int k = 0; k < heights.length; k += 3) {
            int key = heights[k], column = key >> 3, type = key & 7;
            int was = type == SKY ? sky.get(column) : chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[type]).getFirstAvailable(column & 15, column >> 4);
            if (was != heights[k + 1]) return false;
        }
        for (int k = 0; k < heights.length; k += 3) {
            int key = heights[k], column = key >> 3, type = key & 7;
            if (type == SKY) sky.set(column, heights[k + 2]);
            else chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[type]).setHeight(column & 15, column >> 4, heights[k + 2]);
        }
        return true;
    }

    /**
     * Карты высот и нижние источники неба всех столбцов с местами плана — по чанку после записи мест плана, как
     * {@code Heightmap.primeHeightmaps} и {@code ChunkSkyLightSources.fillFrom}, но одним проходом по столбцу для всех
     * карт и неба и только от верхнего места плана вниз: выше него подмена ничего не меняла, и значение, которое там
     * держит мир, верно. Чанк меняли после плана (крышу над выпотрошенным домом сломали — высота встала на этаж,
     * который план сносит), и значения из плана могли устареть. Столбцы без мест плана подмена не трогает — их вёл
     * сам мир; блок-сущности и POI ставятся через мир после этого.
     */
    private void rescanColumns(LevelChunk chunk) {
        int minY = chunk.getMinBuildHeight();
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        for (int c : cells) {
            if ((c & SLOW) != 0) continue;
            int y = minY + (section(c) << 4) + ((c >> 8) & 15);
            if (y > top[c & 0xFF]) top[c & 0xFF] = y;
        }
        ChunkSkyLightSources sky = chunk.getSkyLightSources();
        Heightmap[] maps = new Heightmap[HEIGHTMAP_TYPES.length];
        for (int i = 0; i < maps.length; i++) maps[i] = chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[i]);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos upper = new BlockPos.MutableBlockPos(), lower = new BlockPos.MutableBlockPos();
        for (int column = 0; column < 256; column++) {
            int t = top[column];
            if (t == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4, x = x0 + lx, z = z0 + lz;
            // что искать: значение не выше верхнего места плана (выше — блок над планом, он не менялся)
            int open = 0;
            for (int i = 0; i < maps.length; i++) if (maps[i].getFirstAvailable(lx, lz) <= t + 1) open |= 1 << i;
            boolean skyOpen = sky.get(column) <= t + 1;
            BlockState above = chunk.getBlockState(upper.set(x, t + 1, z));
            for (int y = t; y >= minY - 1 && (open != 0 || skyOpen); y--) {
                BlockState below = chunk.getBlockState(lower.set(x, y, z));
                for (int i = 0; open != 0 && i < maps.length; i++) {
                    if ((open & 1 << i) != 0 && y >= minY && HEIGHTMAP_TYPES[i].isOpaque().test(below)) {
                        maps[i].setHeight(lx, lz, y + 1);
                        open &= ~(1 << i);
                    }
                }
                if (skyOpen) {
                    upper.set(x, y + 1, z);
                    if (below.getLightBlock(chunk, lower) != 0 || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(chunk, upper, above, Direction.DOWN),
                            LightEngine.getOcclusionShape(chunk, lower, below, Direction.UP))) {
                        sky.set(column, y + 1);
                        skyOpen = false;
                    }
                }
                above = below;
            }
            for (int i = 0; i < maps.length; i++) if ((open & 1 << i) != 0) maps[i].setHeight(lx, lz, minY);
            if (skyOpen) sky.set(column, minY - 1);
        }
    }

    private static boolean vanilla(@Nullable Object engine, Class<?> type) {
        return engine == null || engine.getClass() == type;
    }

    /** Стволы, упавшие в соседние чанки: сколько. */
    public int outsideCount() {
        return outside == null ? 0 : outside.size();
    }

    public long outsidePos(int k) {
        return outside.getLong(k);
    }

    public BlockState outsideState(int k) {
        return outsideState.get(k);
    }

    /** Бревно поваленного ствола — на поверхность соседнего чанка (после его руин), если там не стоит постройка. */
    public static void placeLog(ServerLevel level, long at, BlockState log) {
        applying = true;
        try {
            placeLogNow(level, at, log);
        } finally {
            applying = false;
        }
    }

    private static void placeLogNow(ServerLevel level, long at, BlockState log) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos().set(at);
        if (!Terrain.ready(level, m) || !NuclearTickets.aroundLoaded(level, m)) return;
        m.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, m.getX(), m.getZ()));
        BlockState was = level.getBlockState(m);
        if (was.canBeReplaced()) ColumnScar.replace(level, m, was, log);
    }

    private static final ThreadLocal<FriendlyByteBuf> COPY_BUFFER = ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(16384)));

    /**
     * Независимая копия секции. Не {@code PalettedContainer.copy()}: у её палитры остаётся обработчик роста палитры
     * старого контейнера (а одноцветная палитра — вовсе тот же объект), и новое состояние в копии перестраивает данные
     * старой секции мира, а в копию пишет чужой номер («The value 1 is not in the specified inclusive range of 0 to 0»).
     * Запись и чтение через буфер собирают палитру заново, со своим обработчиком.
     */
    static LevelChunkSection copyOf(LevelChunkSection section) {
        FriendlyByteBuf buf = COPY_BUFFER.get();
        buf.clear();
        section.getStates().write(buf);
        PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        states.read(buf);
        return new LevelChunkSection(states, section.getBiomes());
    }

    private static boolean bit(long[] bits, int i) {
        return (bits[i >> 6] & 1L << (i & 63)) != 0;
    }

    private static void set(long[] bits, int i) {
        bits[i >> 6] |= 1L << (i & 63);
    }

    private static BlockPos.MutableBlockPos at(BlockPos.MutableBlockPos m, int c, int x0, int z0, int minY) {
        return m.set(x0 + (c & 15), minY + (section(c) << 4) + ((c >> 8) & 15), z0 + ((c >> 4) & 15));
    }
}
