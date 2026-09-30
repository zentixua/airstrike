package ua.zentix.airstrike.nuclear.world;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;

import ua.zentix.airstrike.util.Terrain;

import java.util.EnumSet;
import java.util.List;

/**
 * Руины одного чанка, построенные заранее ({@link RuinPlanner}), и их подмена в мире одним махом, когда до чанка дошла
 * волна: не блок за блоком ({@code setBlock} с картами высот, светом, соседями и Sable — ≈10 мкс на блок, в городе
 * тысячи блоков на чанк), а целыми секциями 16³ — новая секция встаёт в массив секций чанка вместо старой.
 * <p>
 * Подмена повторяет то, что делает {@code LevelChunk.setBlockState} для каждого блока, один раз на чанк: карты
 * высот заново, источники неба заново, пустота секций — движку света, проверка света в верхней изменённой точке
 * столбца (источники неба в столбце движок ставит по чанку сам), у новых и убранных блоков под уцелевшим верхом и
 * у убранных источников света. Клиенты получают чанк целиком ванильной отправкой чанков. Блоки с блок-сущностью и места POI меняются через мир
 * ({@link ColumnScar#replace}: содержимое не высыпается, отложенные данные снимаются), до подмены секций.
 * <p>
 * План устаревает, если секцию после него меняли (игрок, взрыв, другой мод): у каждой новой секции — отпечаток
 * старой; не совпал — план строится заново по чанку как есть.
 */
public final class RuinPlan {
    private static final EnumSet<Heightmap.Types> HEIGHTMAPS = EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);

    private final ChunkPos pos;
    /** Новые секции (null — секция не меняется). */
    private final LevelChunkSection[] sections;
    /** Отпечатки старых секций, на которых построен план. */
    private final long[] fingerprints;
    private final Long2ObjectLinkedOpenHashMap<BlockState> slow;
    private final LongArrayList fires;
    private final DoubleArrayList fireChance;
    /** Где проверить свет после подмены. */
    private final LongArrayList lightChecks;
    private final int changedBlocks;
    private final LongArrayList outside;
    private final List<BlockState> outsideState;

    private RuinPlan(ChunkPos pos, LevelChunkSection[] sections, long[] fingerprints, Long2ObjectLinkedOpenHashMap<BlockState> slow,
                     LongArrayList fires, DoubleArrayList fireChance, LongArrayList lightChecks, int changedBlocks,
                     LongArrayList outside, List<BlockState> outsideState) {
        this.outside = outside;
        this.outsideState = outsideState;
        this.pos = pos;
        this.sections = sections;
        this.fingerprints = fingerprints;
        this.slow = slow;
        this.fires = fires;
        this.fireChance = fireChance;
        this.lightChecks = lightChecks;
        this.changedBlocks = changedBlocks;
    }

    static RuinPlan of(ServerLevel level, LevelChunk chunk, LevelChunkSection[] original, LevelChunkSection[] copies,
                       Long2ObjectLinkedOpenHashMap<BlockState> slow, LongArrayList fires, DoubleArrayList fireChance, LongArrayList changed,
                       LongArrayList outside, List<BlockState> outsideState) {
        long[] fingerprints = new long[copies.length];
        for (int i = 0; i < copies.length; i++) if (copies[i] != null) fingerprints[i] = fingerprint(original[i]);
        // верх каждого столбца после руин: ниже него убранный или новый блок меняет свет не только как источник неба
        int minY = chunk.getMinBuildHeight();
        Int2IntOpenHashMap newTop = new Int2IntOpenHashMap();
        Int2IntOpenHashMap topChanged = new Int2IntOpenHashMap();
        topChanged.defaultReturnValue(Integer.MIN_VALUE);
        for (int k = 0; k < changed.size(); k++) {
            long p = changed.getLong(k);
            int column = (BlockPos.getX(p) & 15) | (BlockPos.getZ(p) & 15) << 4;
            topChanged.put(column, Math.max(topChanged.get(column), BlockPos.getY(p)));
        }
        for (Int2IntOpenHashMap.Entry e : topChanged.int2IntEntrySet()) {
            int lx = e.getIntKey() & 15, lz = e.getIntKey() >> 4;
            int y = Math.max(e.getIntValue(), chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz));
            while (y >= minY && state(original, copies, minY, lx, y, lz).isAir()) y--;
            newTop.put(e.getIntKey(), y);
        }
        LongArrayList lightChecks = new LongArrayList();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int k = 0; k < changed.size(); k++) {
            long p = changed.getLong(k);
            if (!seen.add(p)) continue;
            int lx = BlockPos.getX(p) & 15, y = BlockPos.getY(p), lz = BlockPos.getZ(p) & 15;
            int column = lx | lz << 4;
            BlockState was = state(original, null, minY, lx, y, lz), now = state(original, copies, minY, lx, y, lz);
            if (was == now) continue;
            boolean top = y == topChanged.get(column);
            BlockPos bp = BlockPos.of(p);
            boolean covered = y < newTop.get(column);
            if (top || was.getLightEmission(chunk, bp) > 0 || now.getLightEmission(chunk, bp) > 0
                    || covered && (was.getLightBlock(chunk, bp) != now.getLightBlock(chunk, bp) || was.useShapeForLightOcclusion() || now.useShapeForLightOcclusion())) {
                lightChecks.add(p);
            }
        }
        return new RuinPlan(chunk.getPos(), copies, fingerprints, slow, fires, fireChance, lightChecks, seen.size(), outside, outsideState);
    }

    private static BlockState state(LevelChunkSection[] original, LevelChunkSection[] copies, int minY, int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (i < 0 || i >= original.length) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        LevelChunkSection s = copies != null && copies[i] != null ? copies[i] : original[i];
        return s.getBlockState(lx, y & 15, lz);
    }

    /** Отпечаток секции: хеш её сетевой записи (палитра и данные). */
    static long fingerprint(LevelChunkSection section) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(section.getSerializedSize()));
        try {
            section.getStates().write(buf);
            long h = 1125899906842597L;
            int n = buf.writerIndex();
            for (int i = 0; i + 8 <= n; i += 8) h = h * 0x9E3779B97F4A7C15L + buf.getLong(i);
            for (int i = n & ~7; i < n; i++) h = h * 31 + buf.getByte(i);
            return h ^ n;
        } finally {
            buf.release();
        }
    }

    public ChunkPos pos() {
        return pos;
    }

    /** Сколько блоков меняет план. */
    public int changedBlocks() {
        return changedBlocks;
    }

    /** Секции чанка всё те же, что при построении плана. */
    boolean current(LevelChunk chunk) {
        LevelChunkSection[] now = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && fingerprint(now[i]) != fingerprints[i]) return false;
        }
        return true;
    }

    /**
     * Поставить руины в чанк (он и соседи загружены целиком — проверяет вызывающий). Устаревший план не ставится.
     *
     * @return false — план устарел, ничего не изменено
     */
    public boolean apply(ServerLevel level, LevelChunk chunk, ColumnScar.Budget budget) {
        if (!current(chunk)) return false;
        if (changedBlocks == 0 && fires.isEmpty() && outside.isEmpty()) return true;
        // блок-сущности и POI — через мир, до подмены: подмена поставит то же состояние ещё раз
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> e : slow.long2ObjectEntrySet()) {
            m.set(e.getLongKey());
            ColumnScar.replace(level, m, chunk.getBlockState(m), e.getValue());
        }
        LevelChunkSection[] now = chunk.getSections();
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;
            boolean wasEmpty = now[i].hasOnlyAir();
            // план мог сделать секцию «пустой» по счётчикам — пересчёт у новой секции уже сделан в конструкторе
            now[i] = sections[i];
            boolean empty = sections[i].hasOnlyAir();
            if (wasEmpty != empty) light.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), empty);
        }
        Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
        chunk.initializeLightSources();
        for (int k = 0; k < lightChecks.size(); k++) light.checkBlock(m.set(lightChecks.getLong(k)));
        chunk.setUnsaved(true);
        // стволы, упавшие в соседний чанк: бревно на его поверхность, если там не стоит постройка
        for (int k = 0; k < outside.size(); k++) {
            m.set(outside.getLong(k));
            if (!Terrain.ready(level, m) || !NuclearTickets.aroundLoaded(level, m)) continue;
            m.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, m.getX(), m.getZ()));
            BlockState was = level.getBlockState(m);
            if (was.canBeReplaced()) ColumnScar.replace(level, m, was, outsideState.get(k));
        }
        for (int k = 0; k < fires.size(); k++) {
            m.set(fires.getLong(k));
            double roll = RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 29);
            if (roll < fireChance.getDouble(k)) ColumnScar.ignite(level, m, budget);
        }
        // клиентам — чанк целиком, ванильной отправкой чанков: ближние первыми и в темпе, который просит клиент (друзья
        // по e4mc), со светом на момент отправки
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(pos, false)) p.connection.chunkSender.markChunkPendingToSend(chunk);
        return true;
    }
}
