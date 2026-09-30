package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;

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
 * у убранных и новых источников света. Клиенты получают чанк целиком ванильной отправкой чанков. Блоки
 * с блок-сущностью и места POI меняются через мир ({@link ColumnScar#replace}: содержимое не высыпается, отложенные
 * данные снимаются), до подмены секций. Пожары ставятся в секции вместе с руинами (без {@code setBlock}: огонь
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
        if (!current(chunk)) return false;
        ChunkPos pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // блок-сущности и POI — через мир, до подмены: копии секций ниже возьмут уже новое состояние
        for (int c : cells) {
            if ((c & SLOW) == 0) continue;
            at(m, c, x0, z0, minY);
            ColumnScar.replace(level, m, chunk.getBlockState(m), states[(c >>> STATE_SHIFT) & STATE_MASK]);
        }
        // новые секции: копия текущей (секцию нельзя править на месте — её читает поток света) и места плана в ней
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
        Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
        chunk.initializeLightSources();
        for (int c : cells) {
            if ((c & LIGHT) != 0) light.checkBlock(at(m, c, x0, z0, minY));
        }
        for (int k = 0; k < lit; k++) {
            at(m, burning[k], x0, z0, minY);
            light.checkBlock(m);
            // тик огня, как у поставленного огня (FireBlock.onPlace): 30–39 тиков
            level.scheduleTick(m.immutable(), states[(burning[k] >>> STATE_SHIFT) & STATE_MASK].getBlock(),
                    30 + (int) (RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 31) * 10));
        }
        chunk.setUnsaved(true);
        // стволы, упавшие в соседний чанк: бревно на его поверхность, если там не стоит постройка
        if (outside != null) {
            for (int k = 0; k < outside.size(); k++) {
                m.set(outside.getLong(k));
                if (!Terrain.ready(level, m) || !NuclearTickets.aroundLoaded(level, m)) continue;
                m.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, m.getX(), m.getZ()));
                BlockState was = level.getBlockState(m);
                if (was.canBeReplaced()) ColumnScar.replace(level, m, was, outsideState.get(k));
            }
        }
        // клиентам — чанк целиком, ванильной отправкой чанков: ближние первыми и в темпе, который просит клиент (друзья
        // по e4mc), со светом на момент отправки
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(pos, false)) p.connection.chunkSender.markChunkPendingToSend(chunk);
        return true;
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
