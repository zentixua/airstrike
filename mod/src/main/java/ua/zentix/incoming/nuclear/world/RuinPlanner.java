package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.WorldNoise;
import ua.zentix.incoming.registry.ModBlocks;

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
 * шаре — ничего), стволы, поваленные от эпицентра, содранный волной дёрн, свет: гарь, выжженная трава, сгоревшие
 * растения, растаявший снег, тринитит и вскипевшая вода в шаре, опалённые биомы ({@link ScorchedBiomes}). Где что
 * случилось — не по порогу, а по кривым поражения с шумом мира ({@link Fragility}): края зон рваные, пятнами.
 * <p>
 * Всё считается по исходным блокам окна чанка (он и 8 соседей; у соседей, чьи руины уже стоят, — их старые блоки),
 * случайное — из хеша координат: план места один и тот же заранее (во время полёта МБР) и в момент подрыва, в каком
 * бы порядке ни шли чанки. Чанк и соседи в радиусе 2 загружены целиком (разломы соседей читают своих соседей).
 */
public final class RuinPlanner {
    /** Давление, при котором волна сдирает дёрн с половины мест (разброс {@link #STRIP_BETA}). */
    static final double STRIP_PSI = 8;
    /** Свет на земле (кал/см²), при котором у половины мест дёрн выгорает до земли. */
    static final double BURN_Q = 15;
    /** Свет, при котором у половины мест остаётся гарь — обугленная подстилка (подзол). */
    static final double CHAR_Q = 30;
    /** Свет, при котором половина растений над землёй (трава, цветы, кусты, посевы) сгорает и снег-слой тает. */
    static final double PLANT_Q = 6;
    /** Разброс кривых поражения (логнормальный): напор ровнее, гарь — пятнами шире всех. */
    static final double STRIP_BETA = 0.25, BURN_BETA = 0.35, CHAR_BETA = 0.45, PLANT_BETA = 0.4;
    /** Доля сгоревших растений, от которых остаётся сухой куст. */
    static final double DEAD_BUSH = 0.08;
    /** Слои шума мира: разные последствия — разные пятна. */
    private static final int LAYER_BURN = 1, LAYER_CHAR = 2, LAYER_STRIP = 3, LAYER_PLANT = 4, LAYER_BIOME = 5;
    /** Случайность места — шум мира (пятна) и хеш места (зерно): веса при сумме квадратов 1, разброс остаётся 1. */
    private static final double SMOOTH = 0.8, GRAIN = 0.6;
    /**
     * Свет на склоне к взрыву — не больше стольких раз от ровной земли; полутень у края тени склона, рад (шар — не точка:
     * за километры его видно в несколько градусов), и высота взрыва над горизонтом в расчёте — не ниже её.
     */
    private static final double MAX_INCIDENCE = 2.5, LOW_SUN = 0.1;
    /** Уклон земли — по соседям до стольких столбцов в каждую сторону: ступени рельефа не дают полос по горизонталям. */
    private static final int SLOPE_REACH = 2;
    /**
     * Доля радиуса руин у его края, на которой след света сходит на нет: дальше планов нет, и склон к взрыву, которому
     * свет там ещё оставил бы опалённую траву, обрывался бы кругом.
     */
    private static final double EDGE_FADE = 0.2;
    /** Сколько чанков вокруг должны быть загружены целиком для плана. */
    public static final int REACH = 2;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState[] STONE_RUBBLE = {Blocks.GRAVEL.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
    private static final BlockState[] WOOD_RUBBLE = {Blocks.COARSE_DIRT.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
            Blocks.COARSE_DIRT.defaultBlockState(), Blocks.ROOTED_DIRT.defaultBlockState()};

    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState(), COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState(),
            PODZOL = Blocks.PODZOL.defaultBlockState(), DEAD_BUSH_STATE = Blocks.DEAD_BUSH.defaultBlockState(),
            WATER = Blocks.WATER.defaultBlockState(), FIRE = Blocks.FIRE.defaultBlockState(), SOUL_FIRE = Blocks.SOUL_FIRE.defaultBlockState();
    /** Земля, которая под снегом «заснежена» ({@code snowy}): снега над ней нет — она без снега. */
    private static final BlockState[] SNOWY_GROUND = {Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.PODZOL.defaultBlockState(),
            Blocks.MYCELIUM.defaultBlockState()};
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Грани огня к горючим соседям (как {@code FireBlock.getStateForPlacement}: вниз граней нет). */
    private static final Direction[] FIRE_FACE_DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
    private static final net.minecraft.world.level.block.state.properties.BooleanProperty[] FIRE_FACES = {BlockStateProperties.NORTH,
            BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST, BlockStateProperties.UP};

    /** Сброс таблицы свойств, после которого свои состояния уже в ней (поток сервера). */
    private static int ownStates = -1;

    /** Состояния, которые пишет план сам (завал, огонь, земля): их свойства — в таблицу вместе со снимками. */
    static void ensureOwnStates() {
        if (ownStates == Blast.generation) return;
        ownStates = Blast.generation;
        for (BlockState st : STONE_RUBBLE) Blast.ensure(st);
        for (BlockState st : WOOD_RUBBLE) Blast.ensure(st);
        for (BlockState st : new BlockState[]{AIR, DIRT, COARSE_DIRT, PODZOL, DEAD_BUSH_STATE, WATER, ModBlocks.TRINITITE.get().defaultBlockState()}) Blast.ensure(st);
        for (BlockState st : SNOWY_GROUND) {
            Blast.ensure(st);
            Blast.ensure(st.setValue(BlockStateProperties.SNOWY, true));
        }
        for (BlockState st : Blocks.FIRE.getStateDefinition().getPossibleStates()) Blast.ensure(st);
        for (BlockState st : Blocks.SOUL_FIRE.getStateDefinition().getPossibleStates()) Blast.ensure(st);
    }

    private RuinPlanner() {}

    /** Хеш места и соли — число в [0, 1). */
    static double hash(int x, int y, int z, int salt) {
        int h = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)) * 31 + salt);
        return (h >>> 8) / (double) (1 << 24);
    }

    /** Хеш места как нормальная величина ~N(0, 1). */
    static double grain(int x, int y, int z, int salt) {
        return Fragility.inverse((hash(x, y, z, salt) * (1 << 24) + 0.5) / (1 << 24));
    }

    /** Случайность столбца для кривой поражения: шум мира слоя {@code layer} с зерном хеша, ~N(0, 1). */
    private static double chance(WorldNoise noise, int x, int z, int layer, int salt) {
        return SMOOTH * noise.at(x + 0.5, z + 0.5, layer) + GRAIN * grain(x, 0, z, salt);
    }

    /** Кривая поражения места при воздействии {@code effect}; шум считается, только если последствие вообще возможно. */
    private static boolean struck(double effect, double median, double beta, WorldNoise noise, int x, int z, int layer, int salt) {
        return Fragility.possible(effect, median, beta) && Fragility.hit(effect, median, beta, chance(noise, x, z, layer, salt));
    }

    /** План руин чанка (он и соседи в радиусе {@link #REACH} загружены целиком, поток сервера). */
    public static RuinPlan plan(ServerLevel level, Detonation d, LevelChunk chunk) {
        return plan(level, NuclearWorld.get(level).ruins(level, d, level.getGameTime()), chunk);
    }

    /**
     * Для проверок: план чанка с чистого листа (без кэша подрыва), в потоке сервера или фоновыми потоками (поток
     * сервера ждёт задачу). Одинаковые планы обоими путями — фоновый решатель не читает мир.
     */
    public static RuinPlan planFresh(ServerLevel level, Detonation d, LevelChunk chunk, boolean background) {
        RuinContext ctx = new RuinContext(level, d);
        if (!background) return plan(level, ctx, chunk);
        if (!ctx.submit(level, chunk)) throw new IllegalStateException("ruin background threads busy");
        return await(level, ctx, chunk);
    }

    /**
     * Для проверок: план чанка по снимку, прочитанному с диска ({@link DiskShots}), фоновыми потоками — чанк и его
     * соседи записаны так, как их записал бы мир ({@code ChunkSerializer.write}), и разобраны как чанки не в памяти.
     */
    public static RuinPlan planFromDisk(ServerLevel level, Detonation d, LevelChunk chunk) {
        return planFromDisk(level, d, chunk, java.util.function.UnaryOperator.identity());
    }

    /** То же, а записанное — каким оно лежит на диске ({@code onDisk}: например, чанк мира 1.17 после обновления). */
    public static RuinPlan planFromDisk(ServerLevel level, Detonation d, LevelChunk chunk, java.util.function.UnaryOperator<net.minecraft.nbt.CompoundTag> onDisk) {
        RuinContext ctx = new RuinContext(level, d);
        ChunkPos c = chunk.getPos();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                LevelChunk n = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                if (n == null) throw new IllegalStateException("neighbour " + (c.x + dx) + ", " + (c.z + dz) + " not loaded");
                DiskShots.Read read = DiskShots.parse(level, new ChunkPos(c.x + dx, c.z + dz),
                        onDisk.apply(net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, n)));
                if (read.skip() != null) throw new IllegalStateException("chunk from disk not parsed: " + read.skip());
                ctx.putDisk(level, read);
            }
        }
        if (!ctx.submitDisk(level, c)) throw new IllegalStateException("ruin background threads busy");
        return await(level, ctx, chunk);
    }

    /**
     * Для проверок: фоновый план чанка в памяти, как его строит очередь руин ({@link ScarQueue}): окно — снимки соседей
     * в памяти, остальные — с диска ({@link RuinContext#requestWindow}); соседа нет на диске целым — в окне сплошной
     * массив. {@code window}: [0] соседей прочитано с диска, [1] из них нет на диске целыми.
     */
    public static RuinPlan planWithWindow(ServerLevel level, Detonation d, LevelChunk chunk, int[] window) {
        RuinContext ctx = new RuinContext(level, d);
        long until = System.nanoTime() + 60_000_000_000L;
        while (ctx.requestWindow(level, chunk.getPos()) > 0) {
            if (System.nanoTime() > until) throw new IllegalStateException("window neighbours not read from disk within a minute");
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        window[0] = ctx.windowReads;
        window[1] = ctx.windowAbsent;
        if (!ctx.submit(level, chunk)) throw new IllegalStateException("ruin background threads busy");
        return await(level, ctx, chunk);
    }

    private static RuinPlan await(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        long until = System.nanoTime() + 60_000_000_000L;
        while (!ctx.done(chunk.getPos().toLong())) {
            if (System.nanoTime() > until) throw new IllegalStateException("background plan not ready within a minute");
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        RuinPlan plan = ctx.collect(level, chunk);
        if (plan == null) throw new IllegalStateException("background plan failed");
        return plan;
    }

    /**
     * Замер цены плана (проверки): {время обрушения, время достройки} в нс — по чанку, чьи разломы уже в кэше подрыва
     * ({@link #blastsReady}).
     */
    public static long[] timedParts(ServerLevel level, Detonation d, LevelChunk chunk) {
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        RuinContext.Grid g = ctx.grid(level, chunk.getPos(), false);
        long t0 = System.nanoTime();
        Collapse c = Collapse.solve(g);
        long t1 = System.nanoTime();
        finish(g, c);
        return new long[]{t1 - t0, System.nanoTime() - t1};
    }

    /**
     * Проверки: след на земле без руин ({@link #scorch}) по чанку в памяти — с картами высот тени того же подрыва, что
     * и {@link #plan(ServerLevel, Detonation, LevelChunk)}.
     */
    public static RuinPlan testScorch(ServerLevel level, Detonation d, LevelChunk chunk) {
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        RuinContext.Grid g = ctx.grid(level, chunk.getPos(), false);
        return scorch(ctx.scorchGrid(level, g.at(0, 0), Blast.SERVER));
    }

    /**
     * Для проверок: падающее давление у земли, psi, от которого волна может сломать растение — отражённое на открытой
     * грани ({@link Blast#reflected}) достаёт до нижнего порога растений.
     */
    public static double testPlantBreakPsi() {
        double lo = 0, hi = BlockResponse.PLANT_PSI;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (Blast.reflected(mid) >= BlockResponse.PLANT_PSI * BlockResponse.JITTER_MIN) hi = mid;
            else lo = mid;
        }
        return lo;
    }

    /**
     * Готов ли чанк к плану одной единицей работы: разломы его окна в кэше. Нет — посчитан один из них (это и есть
     * единица работы), план — следующей.
     */
    public static boolean blastsReady(ServerLevel level, Detonation d, LevelChunk chunk) {
        return blastsReady(level, NuclearWorld.get(level).ruins(level, d, level.getGameTime()), chunk);
    }

    static boolean blastsReady(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        return !IncomingConfig.SERVER.nukeBlockDamage.get() || ctx.blastsReady(level, chunk.getPos());
    }

    static RuinPlan plan(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        RuinContext.Grid g = ctx.grid(level, chunk.getPos(), false);
        RuinPlan plan = finish(g, Collapse.solve(g));
        ctx.planned(chunk.getPos());
        return plan;
    }

    /**
     * План по готовому обрушению (любой поток): пожары, завал, дёрн, свет, поваленные стволы — по снимку чанка, и
     * разница к его секциям. Мира не читает: блоки — из снимка, свойства — из таблицы окна ({@link Blast.Props}),
     * тень света — по картам высот подрыва ({@link RuinContext.Grid#lit}).
     */
    static RuinPlan finish(RuinContext.Grid g, Collapse c) {
        Detonation d = g.ctx().d;
        ChunkShot shot = g.at(0, 0);
        Sections s = new Sections(shot, g.view());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (var e : c.changes.long2ObjectEntrySet()) {
            long at = e.getLongKey();
            s.set(BlockPos.getX(at) & 15, BlockPos.getY(at), BlockPos.getZ(at) & 15, e.getValue());
        }
        // листва, сорванная волной, на свету загорается там, где волна слабая и огонь не сбит
        if (g.fires()) {
            for (int k = 0; k < c.leaves.size(); k++) {
                m.set(c.leaves.getLong(k));
                if (d.psi(Vec3.atCenterOf(m)) < 3 && g.lit(m) >= 10) s.fire(m.getX(), m.getY(), m.getZ(), 1.0);
            }
        }
        double[] heat = land(g, s, c.ground, c.removed, c.wood);
        Blast own = c.blast();
        if (own != null) for (int i = 0; i < own.trees().size(); i++) lay(s, own.trees().get(i), own.treeLogs().get(i));
        // после всего, что ложится на землю (завал, стволы)
        for (int column = 0; column < 256; column++) if (c.ground[column] != Integer.MIN_VALUE) snowy(s, column & 15, c.ground[column], column >> 4);
        RuinPlan plan = s.finish(g.ctx(), c.fluidTicks.toLongArray(), biomes(g.ctx().noise, c.ground, heat, shot.x << 4, shot.z << 4));
        // гарь вспышки на гранях того, что уцелеет: по миру до волны, ставится с руинами
        if (plan != RuinPlan.EMPTY) plan.flash(FlashFaces.chars(g, s));
        return plan;
    }

    /**
     * След подрыва на земле чанка без руин (любой поток): свет и напор — как в плане руин ({@link #finish}) там, где
     * волна ничего не сломала, — по снимку одного чанка, без окна и соседей. Для LOD Distant Horizons вдали за тяжёлой
     * зоной ({@link FarLods}): там волна бьёт стёкла, а издали видно землю — выгоревший дёрн, гарь, опалённые биомы. Чанк,
     * который потом загрузят, получает свои руины целиком, и его земля та же, если волна у него ничего не обрушила.
     */
    static RuinPlan scorch(RuinContext.Grid g) {
        ChunkShot shot = g.at(0, 0);
        Sections s = new Sections(shot, g.view());
        // дно — как у обрушения (Collapse.yb): на 16 ниже самой низкой опоры (вода — опора), под глубокой
        // водой земли нет; окно обрушения — и соседи, здесь — один чанк
        int low = Integer.MAX_VALUE;
        for (int column = 0; column < 256; column++) if (shot.solid[column] >= s.minY) low = Math.min(low, shot.solid[column]);
        int bottom = low == Integer.MAX_VALUE ? s.minY : Math.max(s.minY, low - 16);
        int[] ground = new int[256];
        for (int column = 0; column < 256; column++) ground[column] = groundOf(s, column & 15, column >> 4, bottom);
        double[] heat = land(g, s, ground, null, null);
        for (int column = 0; column < 256; column++) if (ground[column] != Integer.MIN_VALUE) snowy(s, column & 15, ground[column], column >> 4);
        return s.finish(g.ctx(), new long[0], biomes(g.ctx().noise, ground, heat, shot.x << 4, shot.z << 4));
    }

    /**
     * Земля столбца без руин — как у {@link Collapse}, где ничего не сломано: верхний опорный блок, не бревно, не ниже
     * {@code bottom} ({@code Integer.MIN_VALUE} — нет). Секции из одного воздуха пропускаются целиком.
     */
    private static int groundOf(Sections s, int lx, int lz, int bottom) {
        for (int i = s.sections - 1; i >= 0 && s.minY + (i << 4) + 15 >= bottom; i--) {
            if (!s.shot.mayHave(i, st -> !st.isAir())) continue;
            for (int y = s.minY + (i << 4) + 15; y >= Math.max(bottom, s.minY + (i << 4)); y--) {
                Blast.Props p = s.props(lx, y, lz);
                if (p.bearing() && !p.log()) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Земля по столбцам (любой поток): завал, где рухнула постройка, содранный дёрн, свет — гарь, выжженная трава,
     * сгоревшие растения, пожары, тринитит и вскипевшая вода в огненном шаре. {@code removed}, {@code wood} — сколько
     * снесено над столбцом и из них деревянных (null — ничего не снесено: {@link #scorch}).
     *
     * @return свет на земле столбцов с учётом склона — для ступеней биома
     */
    private static double[] land(RuinContext.Grid g, Sections s, int[] groundOf, @Nullable int[] removed, @Nullable int[] wood) {
        Detonation d = g.ctx().d;
        WorldNoise noise = g.ctx().noise;
        boolean blockDamage = g.blockDamage(), fires = g.fires();
        int x0 = s.chunkX << 4, z0 = s.chunkZ << 4;
        double[] heat = new double[256];
        for (int column = 0; column < 256; column++) {
            int ground = groundOf[column];
            if (ground == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4, x = x0 + lx, z = z0 + lz;
            double groundPsi = d.psi(new Vec3(x + 0.5, ground + 1, z + 0.5));
            Vec3 at = new Vec3(x + 0.5, ground + 0.5, z + 0.5);
            boolean inFireball = d.surface() && Math.hypot(at.x - d.burst().x, at.z - d.burst().z) < d.fireballRadius() * 0.8
                    && at.y > d.groundY() - d.fireballRadius();
            double lit = g.lit(new BlockPos(x, ground, z));
            double q = lit > 0 ? lit * incidence(groundOf, lx, lz, ground, x, z, d.burst()) * edge(x, z, d.burst(), g.ctx().ruinRadius) : 0;
            heat[column] = inFireball ? lit : q;
            // рухнувшее оставляет завал при любом давлении (дом падает и ниже 5 psi); в огненном шаре — ничего
            if (blockDamage && !inFireball && removed != null && removed[column] >= 3) rubble(s, lx, ground, lz, x, z, removed[column], wood[column]);
            if (inFireball) {
                if (blockDamage) strip(s, lx, ground, lz, x, z);
                if (lit >= ThermalModel.IGNITE) fireball(s, lx, ground, lz);
                continue;
            }
            boolean stripped = ground(s, noise, lx, ground, lz, x, z, q, groundPsi, blockDamage);
            plants(s, noise, lx, ground, lz, x, z, q, stripped);
            if (fires && q >= ThermalModel.IGNITE) fire(s, lx, ground, lz, x, z, q, groundPsi);
        }
        return heat;
    }

    /**
     * Ступени опалённого биома по клеткам 4×4 чанка ({@link ScorchedBiomes}): средний свет на земле клетки и шум мира;
     * клетки меняются от земли клетки на {@link ScorchedBiomes#BELOW} вниз. Земля клетки — нижняя четверть её столбцов:
     * трава улицы между домами — под ней, а колодец или яма в одном столбце клетку вниз не уводят. Null — ни одной.
     */
    @Nullable
    private static ScorchedBiomes.Cells biomes(WorldNoise noise, int[] ground, double[] heat, int x0, int z0) {
        byte[] levels = new byte[16];
        int[] from = new int[16], tops = new int[16];
        boolean any = false;
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                double sum = 0;
                int n = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int column = (qz << 2 | dz) << 4 | qx << 2 | dx;
                        if (ground[column] == Integer.MIN_VALUE) continue;
                        sum += heat[column];
                        tops[n++] = ground[column];
                    }
                }
                if (n == 0 || !Fragility.possible(sum / n, ScorchedBiomes.MEDIAN[0], ScorchedBiomes.BETA)) continue;
                int level = ScorchedBiomes.level(sum / n, noise.at(x0 + (qx << 2) + 2, z0 + (qz << 2) + 2, LAYER_BIOME));
                if (level == 0) continue;
                java.util.Arrays.sort(tops, 0, n);
                levels[qz << 2 | qx] = (byte) level;
                from[qz << 2 | qx] = tops[n / 4] - ScorchedBiomes.BELOW;
                any = true;
            }
        }
        return any ? new ScorchedBiomes.Cells(levels, from) : null;
    }

    /**
     * Во сколько раз свет на земле столбца сильнее, чем на ровной земле там же: поток через площадку — косинус угла
     * между лучом и нормалью (склон к взрыву жжёт сильнее, склон от взрыва — слабее, круче луча — тень, с полутенью в
     * {@link #LOW_SUN} рад: шар не точка), пороги ожогов — для ровной земли. Нормаль — по земле чанка после руин;
     * перепад — не больше блока на блок (стена, завал и ствол склоном не считаются). Ровная земля — ровно 1 при любой
     * высоте взрыва.
     */
    static double incidence(int[] ground, int lx, int lz, int h, int x, int z, Vec3 burst) {
        double gx = slope(ground, lx, lz, h, 1, 0), gz = slope(ground, lx, lz, h, 0, 1);
        double rx = burst.x - (x + 0.5), ry = burst.y - (h + 1), rz = burst.z - (z + 0.5);
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1e-6) return 1;
        rx /= len;
        ry /= len;
        rz /= len;
        double cos = (-gx * rx + ry - gz * rz) / Math.sqrt(gx * gx + 1 + gz * gz);
        return Math.min(MAX_INCIDENCE, Math.max(0, cos + LOW_SUN) / Math.max(ry + LOW_SUN, LOW_SUN));
    }

    /** След света у края руин: 1 до {@code 1 − EDGE_FADE} радиуса, дальше плавно до 0 на краю. */
    static double edge(int x, int z, Vec3 burst, double ruinRadius) {
        double f = Mth.clamp((1 - Math.hypot(x + 0.5 - burst.x, z + 0.5 - burst.z) / ruinRadius) / EDGE_FADE, 0, 1);
        return f * f * (3 - 2 * f);
    }

    /**
     * Докуда свет оставляет след на земле, блоки по горизонтали от эпицентра ({@link #scorch}): дальше и склон к взрыву
     * ({@link #MAX_INCIDENCE}) не дотягивает ни до растений, ни до нижней ступени биома даже в крайнем хвосте кривой
     * ({@link Fragility#possible}); не дальше радиуса руин.
     */
    static double scorchReach(Detonation d) {
        double lo = 0, hi = d.ruinRadius();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            double q = d.fluence(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) * MAX_INCIDENCE;
            if (Fragility.possible(q, PLANT_Q, PLANT_BETA) || Fragility.possible(q, ScorchedBiomes.MEDIAN[0], ScorchedBiomes.BETA)) lo = r;
            else hi = r;
        }
        return lo;
    }

    /**
     * Уклон земли вдоль оси ({@code dx, dz}): средний перепад к столбцам чанка до {@link #SLOPE_REACH} в обе стороны,
     * каждый не больше блока на блок.
     */
    private static double slope(int[] ground, int lx, int lz, int h, int dx, int dz) {
        double sum = 0;
        int n = 0;
        for (int k = 1; k <= SLOPE_REACH; k++) {
            int ax = lx + dx * k, az = lz + dz * k, bx = lx - dx * k, bz = lz - dz * k;
            if (ax < 16 && az < 16 && ground[az << 4 | ax] != Integer.MIN_VALUE) {
                sum += Mth.clamp(ground[az << 4 | ax] - h, -k, k) / (double) k;
                n++;
            }
            if (bx >= 0 && bz >= 0 && ground[bz << 4 | bx] != Integer.MIN_VALUE) {
                sum += Mth.clamp(h - ground[bz << 4 | bx], -k, k) / (double) k;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Завал на месте рухнувшей постройки: высота — по тому, сколько рухнуло, материал — по тому, из чего. */
    private static void rubble(Sections s, int lx, int ground, int lz, int x, int z, int removed, int wood) {
        int height = Math.min(4, Math.max(1, Math.round(removed * 0.12f)));
        BlockState[] kind = wood * 2 > removed ? WOOD_RUBBLE : STONE_RUBBLE;
        for (int i = 1; i <= height; i++) {
            int y = ground + i;
            if (y >= s.maxY || !s.get(lx, y, lz).isAir()) break;
            s.set(lx, y, lz, kind[(int) (hash(x, y, z, 17) * kind.length)]);
        }
    }

    /** Скоростной напор содрал дёрн и траву: голая земля. */
    private static void strip(Sections s, int lx, int ground, int lz, int x, int z) {
        Blast.Props p = s.props(lx, ground, lz);
        if (p.is(Blast.Props.GRASS) || p.is(Blast.Props.MOSS)) {
            s.set(lx, ground, lz, hash(x, ground, z, 3) < 1 / 3.0 ? DIRT : COARSE_DIRT);
        }
    }

    /** Внутри огненного шара у земли: песок сплавляется, вода вскипает. */
    private static void fireball(Sections s, int lx, int ground, int lz) {
        if (s.props(lx, ground, lz).is(Blast.Props.SAND)) s.set(lx, ground, lz, ModBlocks.TRINITITE.get().defaultBlockState());
        for (int i = 1; i <= 3 && ground + i < s.maxY; i++) {
            if (!s.props(lx, ground + i, lz).is(Blast.Props.SOURCE)) break;
            s.set(lx, ground + i, lz, AIR);
        }
    }

    /**
     * Верхний блок грунта: напор сдирает дёрн (голая земля, даже в тени), свет выжигает траву до земли, а там, где он
     * сильнее всего, оставляет гарь пятнами; снег тает. Каждое — кривой поражения со своим шумом мира.
     *
     * @return дёрн содран напором (с ним — и растения над ним)
     */
    private static boolean ground(Sections s, WorldNoise noise, int lx, int ground, int lz, int x, int z, double q, double psi, boolean blockDamage) {
        Blast.Props p = s.props(lx, ground, lz);
        if (p.is(Blast.Props.SNOW)) {
            if (struck(q, BURN_Q, BURN_BETA, noise, x, z, LAYER_BURN, 5)) s.set(lx, ground, lz, WATER);
            return false;
        }
        if (!p.is(Blast.Props.GRASS) && !p.is(Blast.Props.MOSS)) return false;
        if (blockDamage && struck(psi, STRIP_PSI, STRIP_BETA, noise, x, z, LAYER_STRIP, 3)) {
            strip(s, lx, ground, lz, x, z);
            return true;
        }
        // гарь — только там, где дёрн и так выгорел: пятна гари внутри выгоревшего, а не крапинами в зелёной траве
        if (struck(q, BURN_Q, BURN_BETA, noise, x, z, LAYER_BURN, 5)) {
            s.set(lx, ground, lz, struck(q, CHAR_Q, CHAR_BETA, noise, x, z, LAYER_CHAR, 7) ? PODZOL
                    : hash(x, ground, z, 5) < 0.5 ? COARSE_DIRT : DIRT);
        }
        return false;
    }

    /**
     * Над землёй: трава, цветы, кусты и посевы ({@code nuke_plants}) сгорают (от части остаётся сухой куст) или уходят с
     * содранным дёрном — со всем, что над ними (верх высокой травы, тростник), снег-слой тает. Случайность — больше
     * зерно места, чем пятна: трава горит клочьями.
     */
    private static void plants(Sections s, WorldNoise noise, int lx, int ground, int lz, int x, int z, double q, boolean stripped) {
        int y = ground + 1;
        if (y >= s.maxY) return;
        Blast.Props p = s.props(lx, y, lz);
        boolean plant = p.is(Blast.Props.PLANT), snow = p.is(Blast.Props.SNOW_LAYER);
        if (!plant && !snow) return;
        if (!stripped && !(Fragility.possible(q, PLANT_Q, PLANT_BETA)
                && Fragility.hit(q, PLANT_Q, PLANT_BETA, 0.5 * noise.at(x + 0.5, z + 0.5, LAYER_PLANT) + 0.87 * grain(x, y, z, 41)))) return;
        if (snow) {
            s.set(lx, y, lz, AIR);
            return;
        }
        // двойная трава и высокие цветы, тростник: верх уходит с низом
        for (int up = y + 1; up < s.maxY && s.props(lx, up, lz).is(Blast.Props.PLANT); up++) s.set(lx, up, lz, AIR);
        Blast.Props under = s.props(lx, ground, lz);
        boolean bush = !stripped && hash(x, y, z, 43) < DEAD_BUSH && (under.is(Blast.Props.DIRT) || under.is(Blast.Props.SAND));
        s.set(lx, y, lz, bush ? DEAD_BUSH_STATE : AIR);
    }

    /**
     * Земля «заснежена» ({@code snowy}: белые бока у дёрна), только пока над ней снег, как у {@code SnowyDirtBlock}:
     * снежный слой сдуло волной, растопило светом или накрыло стволом — бока зелёные, выгоревший под уцелевшим снегом —
     * снова белые. Только там, где план менял землю или место над ней: остальной мир — как был.
     */
    private static void snowy(Sections s, int lx, int ground, int lz) {
        if (ground + 1 >= s.maxY || !s.touched(lx, ground, lz) && !s.touched(lx, ground + 1, lz)) return;
        BlockState under = s.get(lx, ground, lz);
        if (!under.hasProperty(BlockStateProperties.SNOWY)) return;
        boolean now = s.props(lx, ground + 1, lz).is(Blast.Props.SNOWY_TOP);
        if (under.getValue(BlockStateProperties.SNOWY) == now) return;
        for (BlockState st : SNOWY_GROUND) if (under.getBlock() == st.getBlock()) s.set(lx, ground, lz, st.setValue(BlockStateProperties.SNOWY, now));
    }

    /** Пожар на земле, где свет поджёг горючее (вероятность растёт со светом), а волна сбила огонь не везде (≥ 2 psi). */
    private static void fire(Sections s, int lx, int ground, int lz, int x, int z, double q, double psi) {
        if (ground + 1 >= s.maxY) return;
        double chance = Math.min(1, q / 20) * (psi >= 2 ? 0.5 : 1);
        Blast.Props now = s.props(lx, ground, lz);
        if (s.get(lx, ground + 1, lz).isAir() && (now.is(Blast.Props.FLAMMABLE) || now.is(Blast.Props.DIRT))) s.fire(x, ground + 1, z, chance);
    }

    /** Поваленный ствол: брёвна лёжа по направлению от эпицентра, по поверхности. */
    private static void lay(Sections s, long[] tree, BlockState log) {
        BlockPos base = BlockPos.of(tree[0]);
        Direction dir = Direction.from3DDataValue((int) tree[2]);
        BlockState lying = log.hasProperty(BlockStateProperties.AXIS) ? log.setValue(BlockStateProperties.AXIS, dir.getAxis()) : log;
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
            int y = Math.min(s.maxY - 1, base.getY() + 8);
            while (y > s.minY && s.props(lx, y, lz).is(Blast.Props.REPLACEABLE)) y--;
            if (y + 1 >= s.maxY || !s.props(lx, y + 1, lz).is(Blast.Props.REPLACEABLE)) break;
            s.set(lx, y + 1, lz, lying);
        }
    }

    /**
     * Чанк для плана: исходные блоки — из снимка, изменения — поверх (место → состояние). В план идёт одна разница
     * ({@link RuinPlan}); свойства — из таблицы окна, код блоков не зовётся.
     */
    static final class Sections {
        final ChunkShot shot;
        final Blast.PropsView view;
        final int minY, maxY, sections, chunkX, chunkZ;
        /** Изменения: номер секции {@code << 12} | место в секции. */
        private final Int2ObjectOpenHashMap<BlockState> changes = new Int2ObjectOpenHashMap<>();
        /** Кандидаты в пожары: позиция и вероятность. */
        final LongArrayList fires = new LongArrayList();
        final it.unimi.dsi.fastutil.doubles.DoubleArrayList fireChance = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        /** Брёвна поваленных деревьев в соседних чанках: место (высота — по поверхности при подмене) и состояние. */
        final LongArrayList outside = new LongArrayList();
        final List<BlockState> outsideState = new ArrayList<>();
        /** Изменённые места: номер секции << 12 | место в секции (с повторами). */
        final IntArrayList changed = new IntArrayList();

        Sections(ChunkShot shot, Blast.PropsView view) {
            this.shot = shot;
            this.view = view;
            this.minY = shot.minY;
            this.maxY = shot.maxY();
            this.sections = shot.sections;
            this.chunkX = shot.x;
            this.chunkZ = shot.z;
        }

        private int key(int lx, int y, int lz) {
            return ((y - minY) >> 4) << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx;
        }

        BlockState get(int lx, int y, int lz) {
            if (y < minY || y >= maxY) return AIR;
            BlockState now = changes.get(key(lx, y, lz));
            return now != null ? now : shot.raw(lx, y, lz);
        }

        Blast.Props props(int lx, int y, int lz) {
            return view.get(get(lx, y, lz));
        }

        /** Менял ли план это место. */
        boolean touched(int lx, int y, int lz) {
            return y >= minY && y < maxY && changes.containsKey(key(lx, y, lz));
        }

        void set(int lx, int y, int lz, BlockState state) {
            if (y < minY || y >= maxY) return;
            int k = key(lx, y, lz);
            changes.put(k, state);
            changed.add(k);
        }

        void fire(int x, int y, int z, double chance) {
            if (chance <= 0) return;
            fires.add(BlockPos.asLong(x, y, z));
            fireChance.add(chance);
        }

        /** Состояние по координатам мира после плана: вне чанка — воздух (огонь выбирает вид по соседям в чанке). */
        private BlockState at(int x, int y, int z) {
            if (x >> 4 != chunkX || z >> 4 != chunkZ) return AIR;
            return get(x & 15, y, z & 15);
        }

        /**
         * Огонь в месте плана, если он там удержится (как {@code FireBlock.canSurvive}); null — не удержится. Вид огня
         * — как {@code BaseFireBlock.getState}: на основе для синего — синий, иначе обычный, грани — к горючим соседям.
         */
        @Nullable
        private BlockState fireAt(int x, int y, int z) {
            if (!at(x, y, z).isAir()) return null;
            Blast.Props below = view.get(at(x, y - 1, z));
            BlockState fire;
            if (below.is(Blast.Props.SOUL_BASE)) {
                fire = SOUL_FIRE;
            } else if (!below.is(Blast.Props.FLAMMABLE) && !below.is(Blast.Props.STURDY_UP)) {
                fire = FIRE;
                for (int k = 0; k < FIRE_FACES.length; k++) {
                    Direction dir = FIRE_FACE_DIRS[k];
                    fire = fire.setValue(FIRE_FACES[k], view.get(at(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ())).is(Blast.Props.FLAMMABLE));
                }
            } else {
                fire = FIRE;
            }
            if (below.is(Blast.Props.STURDY_UP)) return fire;
            if (fire.getBlock() != Blocks.FIRE) return null;
            for (Direction dir : DIRECTIONS) {
                if (view.get(at(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ())).is(Blast.Props.FLAMMABLE)) return fire;
            }
            return null;
        }

        RuinPlan finish(RuinContext ctx, long[] fluidTicks, @Nullable ScorchedBiomes.Cells biomes) {
            // места без повторов, по секциям
            int[] cells = changed.toIntArray();
            java.util.Arrays.sort(cells);
            int n = 0;
            for (int k = 0; k < cells.length; k++) if (k == 0 || cells[k] != cells[k - 1]) cells[n++] = cells[k];
            // пожары: бросок — хешем места (план и подрыв совпадают), место — где огонь удержится на руинах
            IntArrayList fireCells = new IntArrayList();
            List<BlockState> fireStates = new ArrayList<>();
            for (int k = 0; k < fires.size(); k++) {
                long f = fires.getLong(k);
                int fx = BlockPos.getX(f), fy = BlockPos.getY(f), fz = BlockPos.getZ(f);
                if (hash(fx, fy, fz, 29) >= fireChance.getDouble(k)) continue;
                int i = (fy - minY) >> 4;
                if (i < 0 || i >= sections) continue;
                BlockState fire = fireAt(fx, fy, fz);
                if (fire == null) continue;
                fireCells.add(i << RuinPlan.SECTION_SHIFT | (fy & 15) << 8 | (fz & 15) << 4 | fx & 15);
                fireStates.add(fire);
            }
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            if (n == 0 && fireCells.isEmpty() && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(ctx, biomes);
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
                int y = Math.max(topChanged[column], shot.surface[column]);
                while (y >= minY && get(lx, y, lz).isAir()) y--;
                newTop[column] = y;
            }
            int[] heights = heights(topChanged);
            LongArrayList lightAt = new LongArrayList();
            Object2IntOpenHashMap<BlockState> palette = new Object2IntOpenHashMap<>();
            List<BlockState> states = new ArrayList<>();
            BlockState[] olds = new BlockState[n];
            long[] hashes = new long[sections];
            int kept = 0;
            for (int k = 0; k < n; k++) {
                int c = cells[k];
                int i = c >>> RuinPlan.SECTION_SHIFT, lx = c & 15, lz = (c >> 4) & 15, y = y(c), column = c & 0xFF;
                BlockState was = shot.raw(lx, y, lz), now = changes.get(c);
                if (now == null || was == now) continue;
                int idx = palette.computeIfAbsent(now, s -> {
                    states.add((BlockState) s);
                    return states.size() - 1;
                });
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Ruins of chunk " + pos + ": more than " + RuinPlan.MAX_STATES + " states");
                Blast.Props pw = view.get(was), pn = view.get(now);
                hashes[i] = RuinPlan.mixNormal(hashes[i], pw.normal());
                boolean covered = y < newTop[column];
                boolean light = y == topChanged[column] || pw.emission() > 0 || pn.emission() > 0
                        || covered && (pw.lightBlock() != pn.lightBlock() || pw.is(Blast.Props.SHAPE_LIGHT) || pn.is(Blast.Props.SHAPE_LIGHT));
                boolean slow = pw.is(Blast.Props.SLOW);
                if (light && !slow) lightAt.add(BlockPos.asLong((chunkX << 4) + lx, y, (chunkZ << 4) + lz));
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
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Ruins of chunk " + pos + ": more than " + RuinPlan.MAX_STATES + " states");
                fireOut[k] = fireCells.getInt(k) | idx << RuinPlan.STATE_SHIFT;
            }
            if (kept == 0 && fireOut.length == 0 && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(ctx, biomes);
            return new RuinPlan(java.util.Arrays.copyOf(cells, kept), hashes, states.toArray(new BlockState[0]), java.util.Arrays.copyOf(olds, kept), fireOut, heights,
                    lightAt.toLongArray(), shot.prints.clone(), shot.normalPrints(view),
                    outside.isEmpty() ? null : outside, outside.isEmpty() ? null : outsideState, ctx, fluidTicks, biomes);
        }

        /**
         * Карты высот и нижние источники неба изменённых столбцов после руин (для {@link RuinPlan}): тем же правилом, что
         * у {@code Heightmap.update} и {@code ChunkSkyLightSources}, но по изменениям поверх снимка и один раз на столбец.
         * Выше верхнего изменённого блока столбец тот же, поэтому поиск идёт от него вниз. Значения «до» — из снимка (у
         * снимка с диска источник неба считается здесь же по его блокам).
         */
        private int[] heights(int[] topChanged) {
            IntArrayList out = new IntArrayList();
            int skyMin = minY - 1;
            for (int column = 0; column < 256; column++) {
                int top = topChanged[column];
                if (top == Integer.MIN_VALUE) continue;
                int lx = column & 15, lz = column >> 4;
                for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
                    int old = shot.heights[t * 256 + column];
                    int y = Math.max(old, top + 1) - 1;
                    while (y >= minY && !props(lx, y, lz).opaque(t)) y--;
                    if (y + 1 != old) triple(out, column << 3 | t, old, y + 1);
                }
                // нижний источник неба: верх первой сверху закрытой грани; выше top + 1 грани не менялись
                int old = shot.sky != null ? shot.sky[column] : oldSky(lx, lz);
                if (old > top + 1) continue;
                int source = skyMin;
                BlockState upper = get(lx, top + 1, lz);
                for (int y = top; y >= skyMin; y--) {
                    BlockState lower = get(lx, y, lz);
                    if (edgeOccluded(upper, lower)) {
                        source = y + 1;
                        break;
                    }
                    upper = lower;
                }
                if (source != old) triple(out, column << 3 | RuinPlan.SKY, old, source);
            }
            return out.toIntArray();
        }

        /** Нижний источник неба столбца по исходным блокам снимка ({@code ChunkSkyLightSources.findLowestSourceY}). */
        private int oldSky(int lx, int lz) {
            BlockState upper = AIR;
            for (int y = maxY - 1; y >= minY; y--) {
                BlockState lower = shot.raw(lx, y, lz);
                if (edgeOccluded(upper, lower)) return y + 1;
                upper = lower;
            }
            return minY - 1;
        }

        private static void triple(IntArrayList out, int key, int old, int now) {
            out.add(key);
            out.add(old);
            out.add(now);
        }

        /** Как {@code ChunkSkyLightSources.isEdgeOccluded}: свет неба не проходит вниз через грань между блоками. */
        private boolean edgeOccluded(BlockState upper, BlockState lower) {
            Blast.Props below = view.get(lower);
            if (below.lightBlock() != 0) return true;
            return Shapes.faceShapeOccludes(view.get(upper).occludeDown(), below.occludeUp());
        }

        private int y(int c) {
            return minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
        }
    }
}
