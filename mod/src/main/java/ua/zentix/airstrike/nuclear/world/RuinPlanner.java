package ua.zentix.airstrike.nuclear.world;

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

    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState(), COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState(),
            WATER = Blocks.WATER.defaultBlockState(), FIRE = Blocks.FIRE.defaultBlockState(), SOUL_FIRE = Blocks.SOUL_FIRE.defaultBlockState();
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
        for (BlockState st : new BlockState[]{AIR, DIRT, COARSE_DIRT, WATER, ModBlocks.TRINITITE.get().defaultBlockState()}) Blast.ensure(st);
        for (BlockState st : Blocks.FIRE.getStateDefinition().getPossibleStates()) Blast.ensure(st);
        for (BlockState st : Blocks.SOUL_FIRE.getStateDefinition().getPossibleStates()) Blast.ensure(st);
    }

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
     * Для проверок: план чанка с чистого листа (без кэша подрыва), в потоке сервера или фоновыми потоками (поток
     * сервера ждёт задачу). Одинаковые планы обоими путями — фоновый решатель не читает мир.
     */
    public static RuinPlan planFresh(ServerLevel level, Detonation d, LevelChunk chunk, boolean background) {
        RuinContext ctx = new RuinContext(d);
        if (!background) return plan(level, ctx, chunk);
        if (!ctx.submit(level, chunk)) throw new IllegalStateException("фоновые потоки руин заняты");
        return await(level, ctx, chunk);
    }

    /**
     * Для проверок: план чанка по снимку, прочитанному с диска ({@link DiskShots}), фоновыми потоками — чанк и его
     * соседи записаны так, как их записал бы мир ({@code ChunkSerializer.write}), и разобраны как чанки не в памяти.
     */
    public static RuinPlan planFromDisk(ServerLevel level, Detonation d, LevelChunk chunk) {
        RuinContext ctx = new RuinContext(d);
        ChunkPos c = chunk.getPos();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                LevelChunk n = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                if (n == null) throw new IllegalStateException("сосед " + (c.x + dx) + ", " + (c.z + dz) + " не загружен");
                DiskShots.Read read = DiskShots.parse(level, new ChunkPos(c.x + dx, c.z + dz),
                        net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, n));
                if (read.skip() != null) throw new IllegalStateException("чанк с диска не разобран: " + read.skip());
                ctx.putDisk(level, read);
            }
        }
        if (!ctx.submitDisk(level, c)) throw new IllegalStateException("фоновые потоки руин заняты");
        return await(level, ctx, chunk);
    }

    /**
     * Для проверок: фоновый план чанка в памяти, как его строит очередь руин ({@link ScarQueue}): окно — снимки соседей
     * в памяти, остальные — с диска ({@link RuinContext#requestWindow}); соседа нет на диске целым — в окне сплошной
     * массив. {@code window}: [0] соседей прочитано с диска, [1] из них нет на диске целыми.
     */
    public static RuinPlan planWithWindow(ServerLevel level, Detonation d, LevelChunk chunk, int[] window) {
        RuinContext ctx = new RuinContext(d);
        long until = System.nanoTime() + 60_000_000_000L;
        while (ctx.requestWindow(level, chunk.getPos()) > 0) {
            if (System.nanoTime() > until) throw new IllegalStateException("соседи окна не прочитались с диска за минуту");
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        window[0] = ctx.windowReads;
        window[1] = ctx.windowAbsent;
        if (!ctx.submit(level, chunk)) throw new IllegalStateException("фоновые потоки руин заняты");
        return await(level, ctx, chunk);
    }

    private static RuinPlan await(ServerLevel level, RuinContext ctx, LevelChunk chunk) {
        long until = System.nanoTime() + 60_000_000_000L;
        while (!ctx.done(chunk.getPos().toLong())) {
            if (System.nanoTime() > until) throw new IllegalStateException("фоновый план не готов за минуту");
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        RuinPlan plan = ctx.collect(level, chunk);
        if (plan == null) throw new IllegalStateException("фоновый план упал");
        return plan;
    }

    /**
     * Замер цены плана (проверки): {время обрушения, время достройки} в нс — по чанку, чьи разломы уже в кэше подрыва
     * ({@link #blastsReady}).
     */
    public static long[] timedParts(ServerLevel level, Detonation d, LevelChunk chunk) {
        RuinContext ctx = NuclearWorld.get(level).ruins(d, level.getGameTime());
        RuinContext.Grid g = ctx.grid(level, chunk.getPos(), false);
        long t0 = System.nanoTime();
        Collapse c = Collapse.solve(g);
        long t1 = System.nanoTime();
        finish(g, c);
        return new long[]{t1 - t0, System.nanoTime() - t1};
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
        boolean blockDamage = g.blockDamage(), fires = g.fires();
        int x0 = shot.x << 4, z0 = shot.z << 4;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (var e : c.changes.long2ObjectEntrySet()) {
            long at = e.getLongKey();
            s.set(BlockPos.getX(at) & 15, BlockPos.getY(at), BlockPos.getZ(at) & 15, e.getValue());
        }
        // листва, сорванная волной, на свету загорается там, где волна слабая и огонь не сбит
        if (fires) {
            for (int k = 0; k < c.leaves.size(); k++) {
                m.set(c.leaves.getLong(k));
                if (d.psi(Vec3.atCenterOf(m)) < 3 && g.lit(m) >= 10) s.fire(m.getX(), m.getY(), m.getZ(), 1.0);
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
            scorch(g, s, lx, ground, lz, x, z, inFireball, groundPsi, fires);
        }
        Blast own = c.blast();
        if (own != null) for (int i = 0; i < own.trees().size(); i++) lay(s, own.trees().get(i), own.treeLogs().get(i));
        return s.finish(g.ctx(), c.fluidTicks.toLongArray());
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

    private static void strip(Sections s, int lx, int ground, int lz, int x, int z) {
        Blast.Props p = s.props(lx, ground, lz);
        if (p.is(Blast.Props.GRASS) || p.is(Blast.Props.MOSS)) {
            s.set(lx, ground, lz, hash(x, ground, z, 3) < 1 / 3.0 ? DIRT : COARSE_DIRT);
        }
    }

    /** Световой импульс на верхнем блоке грунта: пожары, выгоревшая трава, растаявший снег, тринитит у шара. */
    private static void scorch(RuinContext.Grid g, Sections s, int lx, int ground, int lz, int x, int z, boolean inFireball,
                               double groundPsi, boolean fires) {
        double q = g.lit(new BlockPos(x, ground, z));
        if (q < ThermalModel.IGNITE) return;
        Blast.Props p = s.props(lx, ground, lz);
        if (inFireball) {
            // внутри огненного шара у земли: песок сплавляется, вода вскипает
            if (p.is(Blast.Props.SAND)) s.set(lx, ground, lz, ModBlocks.TRINITITE.get().defaultBlockState());
            for (int i = 1; i <= 3 && ground + i < s.maxY; i++) {
                if (!s.props(lx, ground + i, lz).is(Blast.Props.SOURCE)) break;
                s.set(lx, ground + i, lz, AIR);
            }
            return;
        }
        if (q >= 15) {
            if (p.is(Blast.Props.GRASS)) s.set(lx, ground, lz, hash(x, ground, z, 5) < 0.5 ? COARSE_DIRT : DIRT);
            if (p.is(Blast.Props.SNOW)) s.set(lx, ground, lz, WATER);
        }
        if (!fires || ground + 1 >= s.maxY) return;
        // волна гасит часть огня там, где давление ≥ 2 psi
        double chance = Math.min(1, q / 20) * (groundPsi >= 2 ? 0.5 : 1);
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

        RuinPlan finish(RuinContext ctx, long[] fluidTicks) {
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
            if (n == 0 && fireCells.isEmpty() && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(ctx);
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
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Руины чанка " + pos + ": больше " + RuinPlan.MAX_STATES + " состояний");
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
                if (idx >= RuinPlan.MAX_STATES) throw new IllegalStateException("Руины чанка " + pos + ": больше " + RuinPlan.MAX_STATES + " состояний");
                fireOut[k] = fireCells.getInt(k) | idx << RuinPlan.STATE_SHIFT;
            }
            if (kept == 0 && fireOut.length == 0 && outside.isEmpty() && fluidTicks.length == 0) return RuinPlan.nothing(ctx);
            return new RuinPlan(java.util.Arrays.copyOf(cells, kept), hashes, states.toArray(new BlockState[0]), java.util.Arrays.copyOf(olds, kept), fireOut, heights,
                    lightAt.toLongArray(), shot.prints.clone(), shot.normalPrints(view),
                    outside.isEmpty() ? null : outside, outside.isEmpty() ? null : outsideState, ctx, fluidTicks);
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
