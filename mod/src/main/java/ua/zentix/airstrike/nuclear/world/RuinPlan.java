package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.jetbrains.annotations.Nullable;

import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.grid.GridLights;
import ua.zentix.airstrike.util.Terrain;

import java.util.EnumSet;
import java.util.List;

/**
 * Руины одного чанка, построенные заранее ({@link RuinPlanner}), и их подмена в мире одним махом, когда до чанка дошла
 * волна: не блок за блоком ({@code setBlock} с картами высот, светом, соседями и Sable — ≈10 мкс на блок, в городе
 * тысячи блоков на чанк), а целыми секциями 16³ — новая секция встаёт в массив секций чанка вместо старой.
 * <p>
 * План — только разница: изменённые места (по 4 байта: место в секции, секция, новое состояние из палитры плана,
 * «проверить свет», «через мир») и пожары. Своих копий секций план не держит: копия текущей секции делается в момент
 * подмены, и в неё пишутся только места плана. Всё остальное в секции остаётся как есть к приходу волны — лампы,
 * которые погасил блэкаут (он меняет их прямо в палитре), блоки, поставленные после плана.
 * <p>
 * План устаревает, если места плана после него меняли (игрок, взрыв, другой мод): у каждой изменённой секции — хеш
 * старых состояний в местах плана (лампа и её погашенный двойник — одно и то же). Не совпал — план строится заново
 * по чанку как есть.
 * <p>
 * Подмена повторяет то, что делает {@code LevelChunk.setBlockState} для каждого блока, один раз на чанк: карты
 * высот заново, источники неба заново, пустота секций — движку света, проверка света в верхней изменённой точке
 * столбца (источники неба в столбце движок ставит по чанку сам), у новых и убранных блоков под уцелевшим верхом и
 * у убранных и новых источников света. Игроки, которые видят чанк, получают изменения в том же тике пакетами секций
 * ({@code ChunkHolder.blockChanged}, как при {@code setBlock}). Блоки с блок-сущностью и места POI в копии не
 * меняются: после подмены их меняет мир ({@link ColumnScar#replace}: {@code onRemove} с его последствиями, содержимое
 * не высыпается, отложенные данные снимаются). Стволы, упавшие в соседний чанк, кладёт очередь после руин соседа. Пожары ставятся в секции вместе с руинами (без {@code setBlock}: огонь
 * у стен и деревьев не будит соседей), им только назначается тик огня.
 */
public final class RuinPlan {
    private static final EnumSet<Heightmap.Types> HEIGHTMAPS = EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);

    /** Упаковка места: 12 бит — место в секции (y, z, x), 8 бит — номер секции, 10 бит — состояние в палитре, флаги. */
    static final int SECTION_SHIFT = 12, STATE_SHIFT = 20, STATE_MASK = 0x3FF;
    /** Флаг места: после подмены проверить свет. */
    static final int LIGHT = 1 << 30;
    /** Флаг места: блок-сущность или POI — меняется через мир. */
    static final int SLOW = 1 << 31;
    /** Сколько разных состояний вмещает палитра плана. */
    static final int MAX_STATES = STATE_MASK + 1;

    private static final int[] NONE = new int[0];
    private static final long[] NO_HASHES = new long[0];
    private static final BlockState[] NO_STATES = new BlockState[0];

    /** План без изменений: чанк, где волне нечего менять (поле, вода, чанк у края зоны). */
    static final RuinPlan EMPTY = new RuinPlan(NONE, NO_HASHES, NO_STATES, NONE, null, null);

    /**
     * Для строки в лог: время подмен по частям, нс — проверка плана, копии секций с местами и пожарами (и замена
     * в чанке), карты высот и источники неба, свет и пакеты игрокам, блок-сущности через мир.
     */
    static final long[] PHASES = new long[5];

    /** Сколько мест с проверкой света. */
    private final int lightChecks;
    /** Места плана по секциям (по возрастанию номера секции). */
    private final int[] cells;
    /** По номеру секции: хеш старых состояний в её местах (секции без мест — 0, не проверяются). */
    private final long[] oldHashes;
    private final BlockState[] states;
    /** Пожары: место (как в {@link #cells}) и состояние огня из палитры. */
    private final int[] fires;
    /** Брёвна поваленных деревьев в соседних чанках: место (высота — по поверхности при подмене) и состояние. */
    private final LongArrayList outside;
    private final List<BlockState> outsideState;

    RuinPlan(int[] cells, long[] oldHashes, BlockState[] states, int[] fires, LongArrayList outside, List<BlockState> outsideState) {
        int light = 0;
        for (int c : cells) if ((c & LIGHT) != 0) light++;
        this.lightChecks = light;
        this.cells = cells;
        this.oldHashes = oldHashes;
        this.states = states;
        this.fires = fires;
        this.outside = outside;
        this.outsideState = outsideState;
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

    /** Сколько блоков меняет план (без пожаров). */
    public int changedBlocks() {
        return cells.length;
    }

    /** Примерный размер плана в памяти, байты (для строки в лог). */
    public long bytes() {
        if (this == EMPTY) return 0;
        return 64 + 4L * cells.length + 8L * oldHashes.length + 8L * states.length + 4L * fires.length
                + (outside == null ? 0 : 16L * outside.size());
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
     * Поставить руины в чанк (он и соседи загружены целиком — проверяет вызывающий). Устаревший план не ставится.
     *
     * @return false — план устарел, ничего не изменено
     */
    public boolean apply(ServerLevel level, LevelChunk chunk, ColumnScar.Budget budget) {
        if (this == EMPTY) return true;
        long t = System.nanoTime();
        if (!current(chunk)) return false;
        PHASES[0] += System.nanoTime() - t;
        t = System.nanoTime();
        ChunkPos pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // новые секции: копия текущей (секцию нельзя править на месте — её читает поток света) и места плана в ней;
        // блок-сущности и POI — через мир после подмены (их onRemove: содержимое, половинки сундука, конвейеры Create)
        LevelChunkSection[] now = chunk.getSections();
        LevelChunkSection[] fresh = new LevelChunkSection[now.length];
        for (int c : cells) {
            if ((c & SLOW) != 0) continue;
            LevelChunkSection s = copy(now, fresh, section(c));
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
            LevelChunkSection s = fresh[i] != null ? fresh[i] : now[i];
            if (!s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15).isAir()) continue;
            copy(now, fresh, i).setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK], false);
            burning[lit++] = c;
            budget.fires++;
        }
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        for (int i = 0; i < fresh.length; i++) {
            if (fresh[i] == null) continue;
            boolean wasEmpty = now[i].hasOnlyAir();
            now[i] = fresh[i];
            boolean empty = fresh[i].hasOnlyAir();
            if (wasEmpty != empty) light.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), empty);
        }
        PHASES[1] += System.nanoTime() - t;
        t = System.nanoTime();
        Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
        chunk.initializeLightSources();
        PHASES[2] += System.nanoTime() - t;
        t = System.nanoTime();
        // кто видит чанк, получает изменения в этом же тике — пакетами секций (как setBlock), а не очередью чанков
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        boolean watched = holder != null && !level.getChunkSource().chunkMap.getPlayers(pos, false).isEmpty();
        boolean sections = watched && holder.getTickingChunk() != null;
        LongArrayList checks = new LongArrayList(lightChecks + lit);
        for (int c : cells) {
            if ((c & SLOW) != 0) continue;
            at(m, c, x0, z0, minY);
            if ((c & LIGHT) != 0) checks.add(m.asLong());
            if (sections) holder.blockChanged(m);
        }
        for (int k = 0; k < lit; k++) {
            at(m, burning[k], x0, z0, minY);
            checks.add(m.asLong());
            if (sections) holder.blockChanged(m);
            // тик огня, как у поставленного огня (FireBlock.onPlace): 30–39 тиков
            level.scheduleTick(m.immutable(), states[(burning[k] >>> STATE_SHIFT) & STATE_MASK].getBlock(),
                    30 + (int) (RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 31) * 10));
        }
        checkLight(light, pos, checks);
        PHASES[3] += System.nanoTime() - t;
        t = System.nanoTime();
        chunk.setUnsaved(true);
        for (int c : cells) {
            if ((c & SLOW) == 0) continue;
            at(m, c, x0, z0, minY);
            ColumnScar.replace(level, m, chunk.getBlockState(m), states[(c >>> STATE_SHIFT) & STATE_MASK]);
        }
        // видит, но чанк не тикает (край прорисовки): чанк целиком ванильной отправкой
        if (watched && !sections) {
            for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(pos, false)) p.connection.chunkSender.markChunkPendingToSend(chunk);
        }
        PHASES[4] += System.nanoTime() - t;
        return true;
    }

    /**
     * Проверки света чанка — одной задачей движка света (как {@code LevelLightEngine.checkBlock} в его потоке), а не
     * задачей с почтой на каждое место: сотни мест на чанк, тысячи чанков за пару секунд. Движок света не ванильный
     * (другой мод) — по одному через его {@code checkBlock}.
     */
    private static void checkLight(LevelLightEngine light, ChunkPos pos, LongArrayList checks) {
        if (checks.isEmpty()) return;
        if (light instanceof ThreadedLevelLightEngine threaded && vanilla(light.blockEngine, BlockLightEngine.class)
                && vanilla(light.skyEngine, SkyLightEngine.class)) {
            long[] at = checks.toLongArray();
            var block = light.blockEngine;
            var sky = light.skyEngine;
            threaded.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (long a : at) {
                    p.set(a);
                    if (block != null) block.checkBlock(p);
                    if (sky != null) sky.checkBlock(p);
                }
            });
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < checks.size(); k++) light.checkBlock(p.set(checks.getLong(k)));
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
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos().set(at);
        if (!Terrain.ready(level, m) || !NuclearTickets.aroundLoaded(level, m)) return;
        m.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, m.getX(), m.getZ()));
        BlockState was = level.getBlockState(m);
        if (was.canBeReplaced()) ColumnScar.replace(level, m, was, log);
    }

    private static LevelChunkSection copy(LevelChunkSection[] now, LevelChunkSection[] fresh, int i) {
        LevelChunkSection s = fresh[i];
        if (s == null) s = fresh[i] = copyOf(now[i]);
        return s;
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

    private static BlockPos.MutableBlockPos at(BlockPos.MutableBlockPos m, int c, int x0, int z0, int minY) {
        return m.set(x0 + (c & 15), minY + (section(c) << 4) + ((c >> 8) & 15), z0 + ((c >> 4) & 15));
    }
}
