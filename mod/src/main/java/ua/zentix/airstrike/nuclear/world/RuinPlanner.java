package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.ThermalModel;
import ua.zentix.airstrike.registry.ModBlocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Руины одного чанка от одного подрыва — заранее, без изменений в мире ({@link RuinPlan}): что с каждым местом
 * сделали волна и свет, разницей к секциям чанка; пожары — там, где огонь удержится на руинах (DESIGN-nuke §3.2–3.4).
 * Разрушение — два физических процесса, без правил «постройка или рельеф»:
 * <ul>
 * <li>перепад давления ломает тонкое ({@link Blast}): стены, перекрытия, окна, колонны, высокие узкие массивы;
 * гора, скала, берег, земля толще — стоят;</li>
 * <li>падает то, что потеряло опору ({@link Collapse}): этажи без стен, крыша на выбитых стёклах, листва без ствола,
 * вода без дна, навесное без того, на чём висело.</li>
 * </ul>
 * Потом — по земле после руин (верхний опорный блок): завал из щебня, где рухнула постройка (в огненном
 * шаре — ничего), стволы, поваленные от эпицентра, содранный от 8 psi дёрн, свет: выжженная трава, растаявший снег,
 * тринитит и вскипевшая вода в шаре.
 * <p>
 * Всё считается по исходным блокам окна чанка (он и 8 соседей; у соседей, чьи руины уже стоят, — их старые блоки),
 * случайное — из хеша координат: план места один и тот же заранее (во время полёта МБР) и в момент подрыва, в каком
 * бы порядке ни шли чанки. Чанк и соседи в радиусе 2 загружены целиком (разломы соседей читают своих соседей).
 */
public final class RuinPlanner {
    /** С какого давления волна сдирает дёрн. */
    static final double STRIP_PSI = 8;
    /** Сколько чанков вокруг должны быть загружены целиком для плана. */
    public static final int REACH = 2;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState[] STONE_RUBBLE = {Blocks.GRAVEL.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
    private static final BlockState[] WOOD_RUBBLE = {Blocks.COARSE_DIRT.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
            Blocks.COARSE_DIRT.defaultBlockState(), Blocks.ROOTED_DIRT.defaultBlockState()};

    private RuinPlanner() {}

    /** Хеш места и соли — число в [0, 1). */
    static double hash(int x, int y, int z, int salt) {
        int h = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)) * 31 + salt);
        return (h >>> 8) / (double) (1 << 24);
    }

    /** План руин чанка (он и соседи в радиусе {@link #REACH} загружены целиком, поток сервера). */
    public static RuinPlan plan(ServerLevel level, Detonation d, LevelChunk chunk) {
        return plan(level, NuclearWorld.get(level).ruins(d, level.getGameTime()), chunk);
    }

    /**
     * Готов ли чанк к плану одной единицей работы: разломы его окна в кэше. Нет — посчитан один из них (это и есть
     * единица работы), план — следующей.
     */
    public static boolean blastsReady(ServerLevel level, Detonation d, LevelChunk chunk) {
        return blastsReady(level, NuclearWorld.get(level).ruins(d, level.getGameTime()), chunk);
    }

    static boolean blastsReady(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        return !AirstrikeConfig.SERVER.nukeBlockDamage.get() || ctx.blastsReady(level, chunk.getPos());
    }

    static RuinPlan plan(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        Detonation d = ctx.d;
        Sections s = new Sections(chunk);
        boolean blockDamage = AirstrikeConfig.SERVER.nukeBlockDamage.get();
        boolean fires = AirstrikeConfig.SERVER.nukeFires.get();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Collapse c = Collapse.solve(level, ctx, chunk.getPos());
        for (var e : c.changes.long2ObjectEntrySet()) {
            long at = e.getLongKey();
            s.set(BlockPos.getX(at) & 15, BlockPos.getY(at), BlockPos.getZ(at) & 15, e.getValue());
        }
        // листва, сорванная волной, на свету загорается там, где волна слабая и огонь не сбит
        if (fires) {
            for (int k = 0; k < c.leaves.size(); k++) {
                m.set(c.leaves.getLong(k));
                if (d.psi(Vec3.atCenterOf(m)) < 3 && ctx.lit(level, m) >= 10) s.fire(m.getX(), m.getY(), m.getZ(), 1.0);
            }
        }
        for (int column = 0; column < 256; column++) {
            int ground = c.ground[column];
            if (ground == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4, x = x0 + lx, z = z0 + lz;
            double groundPsi = d.psi(new Vec3(x + 0.5, ground + 1, z + 0.5));
            Vec3 at = new Vec3(x + 0.5, ground + 0.5, z + 0.5);
            boolean inFireball = d.surface() && Math.hypot(at.x - d.burst().x, at.z - d.burst().z) < d.fireballRadius() * 0.8
                    && at.y > d.groundY() - d.fireballRadius();
            // рухнувшее оставляет завал при любом давлении (дом падает и ниже 5 psi); в огненном шаре — ничего
            if (blockDamage && !inFireball && c.removed[column] >= 3) rubble(s, lx, ground, lz, x, z, c.removed[column], c.wood[column]);
            // скоростной напор сдирает дёрн и траву: от 8 psi — голая земля, даже в тени
            if (blockDamage && groundPsi >= STRIP_PSI) strip(s, lx, ground, lz, x, z);
            scorch(level, ctx, s, lx, ground, lz, x, z, inFireball, groundPsi, fires);
        }
        Blast own = c.blast();
        if (own != null) for (int i = 0; i < own.trees().size(); i++) lay(s, own.trees().get(i), own.treeLogs().get(i));
        RuinPlan plan = s.finish(ctx, c.fluidTicks.toLongArray());
        ctx.planned(chunk.getPos());
        return plan;
    }

    /** Завал на месте рухнувшей постройки: высота — по тому, сколько рухнуло, материал — по тому, из чего. */
    private static void rubble(Sections s, int lx, int ground, int lz, int x, int z, int removed, int wood) {
        int height = Math.min(4, Math.max(1, Math.round(removed * 0.12f)));
        BlockState[] kind = wood * 2 > removed ? WOOD_RUBBLE : STONE_RUBBLE;
        for (int i = 1; i <= height; i++) {
            int y = ground + i;
            if (y >= s.maxY() || !s.get(lx, y, lz).isAir()) break;
            s.set(lx, y, lz, kind[(int) (hash(x, y, z, 17) * kind.length)]);
        }
    }

    private static void strip(Sections s, int lx, int ground, int lz, int x, int z) {
        BlockState st = s.get(lx, ground, lz);
        if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)) {
            s.set(lx, ground, lz, (hash(x, ground, z, 3) < 1 / 3.0 ? Blocks.DIRT : Blocks.COARSE_DIRT).defaultBlockState());
        }
    }

    /** Световой импульс на верхнем блоке грунта: пожары, выгоревшая трава, растаявший снег, тринитит у шара. */
    private static void scorch(ServerLevel level, RuinContext ctx, Sections s, int lx, int ground, int lz, int x, int z, boolean inFireball,
                               double groundPsi, boolean fires) {
        BlockPos top = new BlockPos(x, ground, z);
        double q = ctx.lit(level, top);
        if (q < ThermalModel.IGNITE) return;
        BlockState st = s.get(lx, ground, lz);
        if (inFireball) {
            // внутри огненного шара у земли: песок сплавляется, вода вскипает
            if (st.is(BlockTags.SAND)) s.set(lx, ground, lz, ModBlocks.TRINITITE.get().defaultBlockState());
            for (int i = 1; i <= 3 && ground + i < s.maxY(); i++) {
                if (!s.get(lx, ground + i, lz).getFluidState().isSource()) break;
                s.set(lx, ground + i, lz, AIR);
            }
            return;
        }
        if (q >= 15) {
            if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM)) {
                s.set(lx, ground, lz, (hash(x, ground, z, 5) < 0.5 ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState());
            }
            if (st.is(Blocks.SNOW_BLOCK)) s.set(lx, ground, lz, Blocks.WATER.defaultBlockState());
        }
        if (!fires || ground + 1 >= s.maxY()) return;
        // волна гасит часть огня там, где давление ≥ 2 psi
        double chance = Math.min(1, q / 20) * (groundPsi >= 2 ? 0.5 : 1);
        BlockState now = s.get(lx, ground, lz);
        if (s.get(lx, ground + 1, lz).isAir() && (now.isFlammable(level, top, Direction.UP) || now.is(Blocks.GRASS_BLOCK) || now.is(BlockTags.DIRT))) s.fire(x, ground + 1, z, chance);
    }

    /** Поваленный ствол: брёвна лёжа по направлению от эпицентра, по поверхности. */
    private static void lay(Sections s, long[] tree, BlockState log) {
        BlockPos base = BlockPos.of(tree[0]);
        Direction dir = Direction.from3DDataValue((int) tree[2]);
        BlockState lying = log.hasProperty(RotatedPillarBlock.AXIS) ? log.setValue(BlockStateProperties.AXIS, dir.getAxis()) : log;
        for (int i = 0; i < tree[1]; i++) {
            BlockPos p = base.relative(dir, i);
            if (p.getX() >> 4 != s.chunkX || p.getZ() >> 4 != s.chunkZ) {
                // дальше по ходу ствола — соседний чанк: брёвна кладутся туда через мир при подмене
                s.outside.add(p.asLong());
                s.outsideState.add(lying);
                continue;
            }
            int lx = p.getX() & 15, lz = p.getZ() & 15;
            // первый непроходимый блок сверху вниз от уровня комля (+8: склон вверх)
            int y = Math.min(s.maxY() - 1, base.getY() + 8);
            while (y > s.minY && s.get(lx, y, lz).canBeReplaced()) y--;
            if (y + 1 >= s.maxY() || !s.get(lx, y + 1, lz).canBeReplaced()) break;
            s.set(lx, y + 1, lz, lying);
        }
    }

    /**
     * Секции чанка для плана: чтение — из копии, если секция уже менялась, иначе из самого чанка; запись — в копию.
     * Копии живут только пока строится план: в план идёт одна разница ({@link RuinPlan}).
     */
    static final class Sections implements BlockGetter {
        final LevelChunk chunk;
        final LevelChunkSection[] original;
        final LevelChunkSection[] copies;
        final int minY, chunkX, chunkZ;
        /** Кандидаты в пожары: позиция и вероятность. */
        final LongArrayList fires = new LongArrayList();
        final it.unimi.dsi.fastutil.doubles.DoubleArrayList fireChance = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        /** Брёвна поваленных деревьев в соседних чанках: место (высота — по поверхности при подмене) и состояние. */
        final LongArrayList outside = new LongArrayList();
        final List<BlockState> outsideState = new ArrayList<>();
        /** Изменённые места: номер секции << 12 | место в секции (с повторами). */
        final IntArrayList changed = new IntArrayList();

        Sections(LevelChunk chunk) {
            this.chunk = chunk;
            this.original = chunk.getSections();
            this.copies = new LevelChunkSection[original.length];
            this.minY = chunk.getMinBuildHeight();
            this.chunkX = chunk.getPos().x;
            this.chunkZ = chunk.getPos().z;
        }

        int maxY() {
            return chunk.getMaxBuildHeight();
        }

        BlockState get(int lx, int y, int lz) {
            int i = (y - minY) >> 4;
            if (i < 0 || i >= original.length) return AIR;
            LevelChunkSection sec = copies[i] != null ? copies[i] : original[i];
            return sec.getBlockState(lx, y & 15, lz);
        }

        void set(int lx, int y, int lz, BlockState state) {
            int i = (y - minY) >> 4;
            if (i < 0 || i >= original.length) return;
            LevelChunkSection copy = copies[i];
            if (copy == null) {
                copy = copies[i] = RuinPlan.copyOf(original[i]);
            }
            copy.setBlockState(lx, y & 15, lz, state, false);
            changed.add(i << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx);
        }

        void fire(int x, int y, int z, double chance) {
            if (chance <= 0) return;
            fires.add(BlockPos.asLong(x, y, z));
            fireChance.add(chance);
        }

        // ---- чтение плана как мира (огонь выбирает вид и опору по соседям): вне чанка — воздух

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (pos.getX() >> 4 != chunkX || pos.getZ() >> 4 != chunkZ) return AIR;
            return get(pos.getX() & 15, pos.getY(), pos.getZ() & 15);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return chunk.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return minY;
        }

        /** Огонь в месте плана, если он там удержится (как {@code FireBlock.canSurvive}); null — не удержится. */
        @Nullable
        private BlockState fireAt(BlockPos pos) {
            if (!getBlockState(pos).isAir()) return null;
            BlockState fire = BaseFireBlock.getState(this, pos);
            BlockPos below = pos.below();
            if (getBlockState(below).isFaceSturdy(this, below, Direction.UP)) return fire;
            if (!fire.is(Blocks.FIRE)) return null;
            for (Direction dir : Direction.values()) {
                BlockPos n = pos.relative(dir);
                if (getBlockState(n).isFlammable(this, n, dir.getOpposite())) return fire;
            }
            return null;
        }

        RuinPlan finish(RuinContext ctx, long[] fluidTicks) {
            // места без повторов, по секциям
            int[] cells = changed.toIntArray();
            java.util.Arrays.sort(cells);
            int n = 0;
            for (int k = 0; k < cells.length; k++) if (k == 0 || cells[k] != cells[k - 1]) cells[n++] = cells[k];
            // пожары: бросок — хешем места (план и подрыв совпадают), место — где огонь удержится на руинах
            IntArrayList fireCells = new IntArrayList();
            List<BlockState> fireStates = new ArrayList<>();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int k = 0; k < fires.size(); k++) {
                m.set(fires.getLong(k));
                if (hash(m.getX(), m.getY(), m.getZ(), 29) >= fireChance.getDouble(k)) continue;
                int i = (m.getY() - minY) >> 4;
                if (i < 0 || i >= original.length) continue;
                BlockState fire = fireAt(m);
                if (fire == null) continue;
                fireCells.add(i << RuinPlan.SECTION_SHIFT | (m.getY() & 15) << 8 | (m.getZ() & 15) << 4 | m.getX() & 15);
                fireStates.add(fire);
            }
            if (n == 0 && fireCells.isEmpty() && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(chunk, ctx);
            // верх каждого столбца после руин: ниже него убранный или новый блок меняет свет не только как источник неба
            int[] topChanged = new int[256];
            java.util.Arrays.fill(topChanged, Integer.MIN_VALUE);
            for (int k = 0; k < n; k++) {
                int c = cells[k], column = c & 0xFF;
                topChanged[column] = Math.max(topChanged[column], y(c));
            }
            int[] newTop = new int[256];
            for (int column = 0; column < 256; column++) {
                if (topChanged[column] == Integer.MIN_VALUE) continue;
                int lx = column & 15, lz = column >> 4;
                int y = Math.max(topChanged[column], chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz));
                while (y >= minY && get(lx, y, lz).isAir()) y--;
                newTop[column] = y;
            }
            int[] heights = heights(topChanged);
            LongArrayList lightAt = new LongArrayList();
            Object2IntOpenHashMap<BlockState> palette = new Object2IntOpenHashMap<>();
            List<BlockState> states = new ArrayList<>();
            BlockState[] olds = new BlockState[n];
            long[] hashes = new long[original.length];
            int kept = 0;
            for (int k = 0; k < n; k++) {
                int c = cells[k];
                int i = c >>> RuinPlan.SECTION_SHIFT, lx = c & 15, lz = (c >> 4) & 15, y = y(c), column = c & 0xFF;
                BlockState was = original[i].getBlockState(lx, y & 15, lz), now = copies[i].getBlockState(lx, y & 15, lz);
                if (was == now) continue;
                int idx = palette.computeIfAbsent(now, s -> {
                    states.add((BlockState) s);
                    return states.size() - 1;
                });
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Руины чанка " + chunk.getPos() + ": больше " + RuinPlan.MAX_STATES + " состояний");
                hashes[i] = RuinPlan.mix(hashes[i], was);
                m.set((chunkX << 4) + lx, y, (chunkZ << 4) + lz);
                boolean covered = y < newTop[column];
                boolean light = y == topChanged[column] || was.getLightEmission(chunk, m) > 0 || now.getLightEmission(chunk, m) > 0
                        || covered && (was.getLightBlock(chunk, m) != now.getLightBlock(chunk, m) || was.useShapeForLightOcclusion() || now.useShapeForLightOcclusion());
                boolean slow = was.hasBlockEntity() || PoiTypes.forState(was).isPresent();
                if (light && !slow) lightAt.add(m.asLong());
                olds[kept] = was;
                cells[kept++] = c | idx << RuinPlan.STATE_SHIFT | (light ? RuinPlan.LIGHT : 0) | (slow ? RuinPlan.SLOW : 0);
            }
            int[] fireOut = new int[fireCells.size()];
            for (int k = 0; k < fireOut.length; k++) {
                BlockState fire = fireStates.get(k);
                int idx = palette.computeIfAbsent(fire, s -> {
                    states.add((BlockState) s);
                    return states.size() - 1;
                });
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Руины чанка " + chunk.getPos() + ": больше " + RuinPlan.MAX_STATES + " состояний");
                fireOut[k] = fireCells.getInt(k) | idx << RuinPlan.STATE_SHIFT;
            }
            if (kept == 0 && fireOut.length == 0 && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(chunk, ctx);
            return new RuinPlan(java.util.Arrays.copyOf(cells, kept), hashes, states.toArray(new BlockState[0]), java.util.Arrays.copyOf(olds, kept), fireOut, heights,
                    lightAt.toLongArray(), System.identityHashCode(chunk), RuinPlan.edits(chunk),
                    outside.isEmpty() ? null : outside, outside.isEmpty() ? null : outsideState, ctx, fluidTicks);
        }

        /**
         * Карты высот и нижние источники неба изменённых столбцов после руин (для {@link RuinPlan}): тем же правилом, что
         * у {@code Heightmap.update} и {@code ChunkSkyLightSources}, но по копиям секций и один раз на столбец. Выше
         * верхнего изменённого блока столбец тот же, поэтому поиск идёт от него вниз.
         */
        private int[] heights(int[] topChanged) {
            IntArrayList out = new IntArrayList();
            ChunkSkyLightSources sky = chunk.getSkyLightSources();
            BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos();
            int skyMin = minY - 1;
            for (int column = 0; column < 256; column++) {
                int top = topChanged[column];
                if (top == Integer.MIN_VALUE) continue;
                int lx = column & 15, lz = column >> 4, x = (chunkX << 4) + lx, z = (chunkZ << 4) + lz;
                for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
                    Heightmap.Types type = RuinPlan.HEIGHTMAP_TYPES[t];
                    int old = chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(lx, lz);
                    int y = Math.max(old, top + 1) - 1;
                    while (y >= minY && !type.isOpaque().test(get(lx, y, lz))) y--;
                    if (y + 1 != old) triple(out, column << 3 | t, old, y + 1);
                }
                // нижний источник неба: верх первой сверху закрытой грани; выше top + 1 грани не менялись
                int old = sky.get(column);
                if (old > top + 1) continue;
                int source = skyMin;
                BlockState upper = get(lx, top + 1, lz);
                for (int y = top; y >= skyMin; y--) {
                    BlockState lower = get(lx, y, lz);
                    if (edgeOccluded(above.set(x, y + 1, z), upper, below.set(x, y, z), lower)) {
                        source = y + 1;
                        break;
                    }
                    upper = lower;
                }
                if (source != old) triple(out, column << 3 | RuinPlan.SKY, old, source);
            }
            return out.toIntArray();
        }

        private static void triple(IntArrayList out, int key, int old, int now) {
            out.add(key);
            out.add(old);
            out.add(now);
        }

        /** Как {@code ChunkSkyLightSources.isEdgeOccluded}: свет неба не проходит вниз через грань между блоками. */
        private boolean edgeOccluded(BlockPos upperPos, BlockState upper, BlockPos lowerPos, BlockState lower) {
            if (lower.getLightBlock(this, lowerPos) != 0) return true;
            return Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(this, upperPos, upper, Direction.DOWN),
                    LightEngine.getOcclusionShape(this, lowerPos, lower, Direction.UP));
        }

        private int y(int c) {
            return minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
        }
    }
}
