package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.Mth;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.warhead.Warheads;
import ua.zentix.airstrike.work.WorkScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Общий бюджет тяжёлой работы ({@link WorkScheduler}): попадания — единицами под сроком тика, взрыв — по частям
 * с тем же итогом, что у ванили, порядок работ удара, неготовые районы, доля блэкаута. Время — считающими часами
 * ({@link WorkClock#counting}: единица = 1 мс), настенное время на CI не мерится.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkGameTests {
    private static final long MS = 1_000_000L;
    /** Середина площадки «range» на уровне травы (пол — 11 слоёв). */
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private WorkGameTests() {}

    /**
     * Квартал: дома 7×7 из каменного кирпича высотой 9 со стёклами и светокамнем внутри, сеткой через 14 блоков вокруг
     * середины площадки (x и z от 11 до 53). Обломки на время теста не ложатся блоками.
     */
    private static void buildTown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // обломки ложатся блоками (угольный блок, базальт) и в воронку: «снесён ли блок» — только без них
        boolean debris = AirstrikeConfig.SERVER.debrisStay.get();
        AirstrikeConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> AirstrikeConfig.SERVER.debrisStay.set(debris));
        BlockPos c = h.absolutePos(CENTER);
        for (int gx = -1; gx <= 1; gx++) {
            for (int gz = -1; gz <= 1; gz++) {
                BlockPos base = c.offset(gx * 14 - 3, 0, gz * 14 - 3);
                for (int x = 0; x < 7; x++) for (int y = 0; y < 9; y++) for (int z = 0; z < 7; z++) {
                    boolean wall = x == 0 || x == 6 || z == 0 || z == 6 || y == 8;
                    BlockState s = !wall ? Blocks.AIR.defaultBlockState()
                            : y % 3 == 1 && (x == 3 || z == 3) && y != 8 ? Blocks.GLASS.defaultBlockState()
                            : Blocks.STONE_BRICKS.defaultBlockState();
                    level.setBlock(base.offset(x, y, z), s, 2);
                }
                level.setBlock(base.offset(3, 7, 3), Blocks.GLOWSTONE.defaultBlockState(), 2);
            }
        }
    }

    /** Взрывы у площадки: что каждый решил снести (блоки, не воздух), по порядку. */
    private record Blast(Vec3 at, float radius, long gameTime, Set<BlockPos> blocks) {}

    private static List<Blast> recordBlasts(GameTestHelper h, double range) {
        ServerLevel level = h.getLevel();
        Vec3 c = Vec3.atCenterOf(h.absolutePos(CENTER));
        List<Blast> out = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> on = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > range) return;
            Set<BlockPos> solid = new HashSet<>();
            for (BlockPos p : e.getAffectedBlocks()) if (!level.getBlockState(p).isAir()) solid.add(p.immutable());
            out.add(new Blast(e.getExplosion().center(), e.getExplosion().radius(), level.getGameTime(), solid));
        };
        NeoForge.EVENT_BUS.addListener(on);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(on));
        return out;
    }

    /** Тикеты районов взрывов ({@code BlastArea}: регион и загрузка) в чанках района {@code at}. */
    @SuppressWarnings("unchecked")
    private static int blastTickets(ServerLevel level, Vec3 at) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            Field key = Ticket.class.getDeclaredField("key");
            key.setAccessible(true);
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) f.get(level.getChunkSource().chunkMap.getDistanceManager());
            ChunkPos c = new ChunkPos(BlockPos.containing(at));
            int n = 0;
            for (var entry : map.long2ObjectEntrySet()) {
                ChunkPos p = new ChunkPos(entry.getLongKey());
                if (Math.abs(p.x - c.x) > 4 || Math.abs(p.z - c.z) > 4) continue;
                for (Ticket<?> t : entry.getValue()) {
                    Object k = key.get(t);
                    String type = k instanceof AreaLoader.Area area ? area.type().toString() : t.getType().toString();
                    if (type.equals("airstrike_blast")) n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException ex) {
            throw new GameTestAssertException("тикеты не читаются: " + ex);
        }
    }

    private static boolean gone(BlockState s) {
        return s.isAir() || s.getBlock() instanceof BaseFireBlock;
    }

    private static void useCounting(GameTestHelper h, WorkClock clock) {
        MinecraftServer server = h.getLevel().getServer();
        WorkScheduler.useImpactClock(server, clock);
        StrikeGameTests.afterTest(h, () -> WorkScheduler.useImpactClock(server, WorkScheduler.newImpactClock()));
    }

    /**
     * Залп по кварталу (пять шахедов и ракета в одном тике) на считающих часах: за тик — не больше общего срока и одной
     * единицы; работа растянута на несколько тиков; итог — как у ванили: каждый блок, который выбрал любой взрыв
     * ({@code ExplosionEvent.Detonate}), снесён (или горит).
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "work_budget", skyAccess = true)
    public static void impactsStayWithinBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        for (int i = 0; i < 5; i++) {
            double a = i * Math.PI * 2 / 5;
            Warheads.detonate(level, WeaponType.DRONE, c.add(Math.cos(a) * 14, 0, Math.sin(a) * 14), null, null);
        }
        Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        long total = WorkScheduler.totalNanos();
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий: " + StrikeWorld.get(level).impacts().size());
            h.assertTrue(clock.maxTickNanos() <= total + MS, "тик " + clock.maxTickNanos() / MS + " мс при сроке " + total / MS);
            h.assertTrue(clock.ticksWorked() > 1, "всё в одном тике — бюджет не делит работу (" + clock.units() + " единиц)");
            // пять шахедов: подрыв, огненный шар, четыре вторичных; ракета: подрыв, огненный шар, пять вторичных
            h.assertTrue(blasts.size() == 5 * 6 + 7, "взрывов " + blasts.size());
            int blocks = 0;
            for (Blast b : blasts) {
                for (BlockPos p : b.blocks()) {
                    h.assertTrue(gone(level.getBlockState(p)), "взрыв у " + b.at() + " не снёс " + p + ": " + level.getBlockState(p));
                }
                blocks += b.blocks().size();
            }
            h.assertTrue(blocks > 500, "снесено блоков " + blocks);
            Airstrike.LOG.info("WORKBENCH залп: {} единиц за {} тиков (до {} за тик), взрывов {}, блоков {}", clock.units(),
                    clock.ticksWorked(), clock.maxUnitsPerTick(), blasts.size(), blocks);
        });
    }

    /** Одиночное попадание при пустой очереди: подрыв (лучи, урон, события) — в тике удара, без задержки картинки. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "work_same_tick", skyAccess = true)
    public static void impactWithEmptyQueueRunsSameTick(GameTestHelper h) {
        // очередь мира общая: работы ударов прошлых партий могут ещё доделываться — ждём, пока опустеет
        whenQueueEmpty(h, 150, () -> {
            ServerLevel level = h.getLevel();
            // считающие часы: единица = 1 мс, срок 30 — подрыв (начало, 11 единиц лучей, Detonate) влезает в тик
            useCounting(h, WorkClock.counting(MS));
            List<Blast> blasts = recordBlasts(h, 16);
            long t = level.getGameTime();
            Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), null, null);
            h.runAfterDelay(1, () -> {
                h.assertFalse(blasts.isEmpty(), "подрыва нет и тиком позже");
                h.assertTrue(blasts.getFirst().gameTime() == t, "подрыв на тике " + blasts.getFirst().gameTime() + ", удар — на " + t);
                h.succeed();
            });
        });
    }

    /** {@code then} — в первом тике, когда очередь попаданий мира пуста (проверка тика — до планировщика). */
    private static void whenQueueEmpty(GameTestHelper h, int ticksLeft, Runnable then) {
        if (StrikeWorld.get(h.getLevel()).impacts().isEmpty()) {
            then.run();
            return;
        }
        if (ticksLeft <= 0) throw new GameTestAssertException("очередь попаданий не опустела: " + StrikeWorld.get(h.getLevel()).impacts().size());
        h.runAfterDelay(1, () -> whenQueueEmpty(h, ticksLeft - 1, then));
    }

    /**
     * Порядок: два шахеда в одном тике — подрывы обоих (лучи, урон) в тике удара, раньше порций блоков; огненный шар
     * и вторичные первого — только когда его подрыв снёс всё своё.
     */
    @GameTest(template = "range", timeoutTicks = 1000, batch = "work_order", skyAccess = true)
    public static void impactUnitsKeepOrder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Vec3 first = c.add(-14, 0, 0), second = c.add(14, 0, 0);
        float power = AirstrikeConfig.SERVER.dronePower.get().floatValue();
        List<String> early = new ArrayList<>();
        int[] followers = {0};
        Consumer<ExplosionEvent.Detonate> check = e -> {
            // после записи взрывов (LOWEST): первый в списке — подрыв первого шахеда
            if (e.getLevel() != level || blasts.isEmpty() || e.getExplosion().radius() >= power) return;
            if (e.getExplosion().center().distanceTo(first) > 12) return;
            followers[0]++;
            for (BlockPos p : blasts.getFirst().blocks()) {
                if (!gone(level.getBlockState(p))) {
                    early.add("взрыв силы " + e.getExplosion().radius() + " раньше, чем снесён " + p);
                    return;
                }
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, check);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(check));
        List<Vec3> starts = new ArrayList<>();
        List<Long> startTicks = new ArrayList<>();
        Consumer<ExplosionEvent.Start> onStart = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > 48) return;
            starts.add(e.getExplosion().center());
            startTicks.add(level.getGameTime());
        };
        NeoForge.EVENT_BUS.addListener(onStart);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(onStart));
        long t = level.getGameTime();
        Warheads.detonate(level, WeaponType.DRONE, first, null, null);
        Warheads.detonate(level, WeaponType.DRONE, second, null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty() && blasts.size() == 12, "взрывов " + blasts.size());
            h.assertTrue(blasts.get(0).at().distanceTo(first) < 0.01 && Mth.equal(blasts.get(0).radius(), power), "первым — не подрыв первого");
            h.assertTrue(starts.size() >= 2 && starts.get(0).distanceTo(first) < 0.01 && starts.get(1).distanceTo(second) < 0.01,
                    "первые единицы — не подрывы шахедов: " + starts);
            h.assertTrue(startTicks.get(0) == t && startTicks.get(1) == t, "подрывы начались не в тике удара: " + startTicks + " (удар " + t + ")");
            h.assertTrue(followers[0] == 5, "огненный шар и вторичные первого: " + followers[0]);
            h.assertTrue(early.isEmpty(), early.toString());
            h.assertTrue(clock.ticksWorked() > 1, "всё в одном тике — порядок не проверен");
        });
    }

    /**
     * Остановка сервера посреди очереди ({@code StrikeWorld.onServerStopping} → {@code UnitQueue.finish}): начатые
     * взрывы доделываются сразу (блоки, выбранные лучами, снесены), работа в неготовом районе вдали отпускается и его
     * не читает.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_stop", skyAccess = true)
    public static void stopFinishesQueue(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Vec3 far = c.add(0, 0, 5200);
        h.assertFalse(Terrain.readyAround(level, far, 32), "район вдали уже загружен");
        Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        Warheads.detonate(level, WeaponType.DRONE, far, null, null);
        h.runAfterDelay(1, () -> {
            h.assertFalse(blasts.isEmpty(), "подрыва нет");
            boolean half = blasts.getFirst().blocks().stream().anyMatch(p -> !gone(level.getBlockState(p)));
            h.assertTrue(half, "подрыв ракеты снесён за тик — нечего доделывать");
            StrikeWorld.get(level).impacts().finish(level);
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь не пуста после остановки");
            for (Blast b : blasts) {
                for (BlockPos p : b.blocks()) h.assertTrue(gone(level.getBlockState(p)), "не снесён " + p + " взрыва у " + b.at());
            }
            h.assertFalse(Terrain.readyAround(level, far, 32), "район вдали загрузился синхронно");
            // остановка без самой остановки: таймлайны ударов (вторичные ракеты, шахед вдали) не должны достаться другим тестам
            StrikeWorld.get(level).dropAll(level);
            h.succeed();
        });
    }

    /** Блок, сменившийся между лучами и своей порцией (поставил игрок, натекла вода), взрыв не трогает. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_changed", skyAccess = true)
    public static void portionSkipsChangedBlock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        buildTown(h);
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        BlockPos[] changed = {null};
        h.runAfterDelay(1, () -> {
            // самый дальний блок подрыва — в последних порциях
            BlockPos far = null;
            for (BlockPos p : blasts.getFirst().blocks()) {
                if (!gone(level.getBlockState(p)) && (far == null || p.distToCenterSqr(c) > far.distToCenterSqr(c))) far = p;
            }
            h.assertTrue(far != null, "подрыв ракеты снесён за тик — нечего проверять");
            level.setBlock(far, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            changed[0] = far;
        });
        h.succeedWhen(() -> {
            h.assertTrue(changed[0] != null && StrikeWorld.get(level).impacts().isEmpty(), "очередь не пуста");
            h.assertTrue(level.getBlockState(changed[0]).is(Blocks.GOLD_BLOCK), "сменившийся блок снесён: " + level.getBlockState(changed[0]));
        });
    }

    /**
     * Соединяющиеся блоки: снятие внутренней порции меняет свойства внешних соседей (забор, стена и панель теряют
     * соседа, лестница — форму, листва — дистанцию), но это тот же блок — лучи его выбрали, и он снесён.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "work_connected", skyAccess = true)
    public static void portionTakesReshapedNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean debris = AirstrikeConfig.SERVER.debrisStay.get();
        AirstrikeConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> AirstrikeConfig.SERVER.debrisStay.set(debris));
        BlockPos c = h.absolutePos(CENTER);
        BlockState[] rows = {Blocks.OAK_FENCE.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.IRON_BARS.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()};
        // ряды через центр по x и по z, соседи соединяются (флаг 3 — обновления формы)
        for (int i = -20; i <= 20; i++) {
            for (int y = 0; y < rows.length; y++) {
                level.setBlock(c.offset(i, y, 1), rows[y], 3);
                level.setBlock(c.offset(1, y, i), rows[y], 3);
            }
        }
        WorkClock clock = WorkClock.counting(MS);
        useCounting(h, clock);
        List<Blast> blasts = recordBlasts(h, 48);
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(c), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(!blasts.isEmpty() && StrikeWorld.get(level).impacts().isEmpty(), "очередь не пуста");
            int connected = 0;
            for (Blast b : blasts) {
                for (BlockPos p : b.blocks()) {
                    BlockState now = level.getBlockState(p);
                    h.assertTrue(gone(now), "выбран лучами и не снесён: " + p + " " + now + " (взрыв у " + b.at() + ")");
                }
            }
            for (BlockPos p : blasts.getFirst().blocks()) if (Math.abs(p.getX() - c.getX()) <= 1 || Math.abs(p.getZ() - c.getZ()) <= 1) connected++;
            h.assertTrue(connected > 3 * 16, "лучи задели мало рядов (" + connected + ") — порций снаружи нет");
        });
    }

    /**
     * Лучи своим циклом выбирают то же, что ванильный {@code explode()}: один сид, площадка из камня, воды, обсидиана,
     * стекла и руды, ванильный калькулятор и калькулятор бомбы (ослабленные блоки), все лучи одной единицей
     * и по 128. Взрыв без разрушений — площадка одна на все прогоны.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_rays", skyAccess = true)
    public static void raysMatchVanilla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(CENTER);
        BlockState[] mix = {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(),
                Blocks.GLASS.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()};
        java.util.Random pick = new java.util.Random(7);
        for (int x = -9; x <= 9; x++) for (int y = 0; y <= 9; y++) for (int z = -9; z <= 9; z++) {
            if (x == 0 && z == 0 && y <= 1) continue;
            level.setBlock(c.offset(x, y, z), mix[pick.nextInt(mix.length)], 2);
        }
        // вода не течёт: иначе между взрывами (и между единицами по 128 лучей) мир менялся, и прогоны сравнивали разное
        net.minecraft.world.level.levelgen.structure.BoundingBox box = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                c.offset(-10, -1, -10), c.offset(10, 10, 10));
        level.getFluidTicks().clearArea(box);
        List<BlockState> before = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-10, -1, -10), c.offset(10, 10, 10))) before.add(level.getBlockState(p));
        Set<BlockPos> weakened = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-3, 0, -3), c.offset(3, 3, 3))) if (pick.nextBoolean()) weakened.add(p.immutable());
        net.minecraft.world.level.ExplosionDamageCalculator bunker = new net.minecraft.world.level.ExplosionDamageCalculator() {
            @Override
            public java.util.Optional<Float> getBlockExplosionResistance(net.minecraft.world.level.Explosion explosion,
                    net.minecraft.world.level.BlockGetter reader, BlockPos p, BlockState state, net.minecraft.world.level.material.FluidState fluid) {
                return weakened.contains(p) ? java.util.Optional.of(0f) : super.getBlockExplosionResistance(explosion, reader, p, state, fluid);
            }
        };
        Vec3 at = Vec3.atCenterOf(c.above());
        record Run(boolean vanilla, int rays, boolean weak) {}
        List<Run> runs = List.of(new Run(true, 1352, false), new Run(false, 1352, false), new Run(false, 128, false),
                new Run(true, 1352, true), new Run(false, 128, true));
        List<Set<BlockPos>> seen = new ArrayList<>();
        List<Warheads.Probe> probes = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> on = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(at) > 0.01) return;
            Set<BlockPos> set = new HashSet<>();
            for (BlockPos p : e.getAffectedBlocks()) set.add(p.immutable());
            seen.add(set);
        };
        NeoForge.EVENT_BUS.addListener(on);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(on));
        // по одному взрыву: следующий — когда прошлый выбрал
        int[] started = {0};
        h.onEachTick(() -> {
            if (started[0] > seen.size() || started[0] >= runs.size() || !StrikeWorld.get(level).impacts().isEmpty()) return;
            Run r = runs.get(started[0]++);
            probes.add(Warheads.testRays(level, at, 6, r.weak() ? bunker : null, 12345L, r.vanilla(), r.rays()));
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen.size() == runs.size() && StrikeWorld.get(level).impacts().isEmpty(), "взрывов " + seen.size());
            int i0 = 0;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-10, -1, -10), c.offset(10, 10, 10))) {
                if (level.getBlockState(p) != before.get(i0++)) throw new GameTestAssertException("площадка изменилась у " + p.immutable() + ": прогоны сравнивали разный мир");
            }
            h.assertTrue(seen.get(0).size() > 100, "лучи выбрали мало: " + seen.get(0).size());
            for (int i = 1; i < runs.size(); i++) {
                Set<BlockPos> want = runs.get(i).weak() ? seen.get(3) : seen.get(0);
                if (!seen.get(i).equals(want)) {
                    Set<BlockPos> extra = new HashSet<>(seen.get(i));
                    extra.removeAll(want);
                    Set<BlockPos> missing = new HashSet<>(want);
                    missing.removeAll(seen.get(i));
                    throw new GameTestAssertException(runs.get(i) + ": лишние " + extra.size() + ", нет " + missing.size() + " из " + want.size());
                }
            }
            h.assertFalse(seen.get(3).equals(seen.get(0)), "калькулятор бомбы ничего не изменил — проверка пустая");
            for (int i = 0; i < runs.size(); i++) {
                h.assertTrue(probes.get(i).vanilla() == runs.get(i).vanilla(), runs.get(i) + ": путь взрыва не тот, что задан");
            }
        });
    }

    /**
     * Взрыв у собранного аппарата Sable: блоки аппарата (в сетке плотов), которые выбрали лучи, сняты сразу после урона,
     * а не порциями с блоками мира, — под считающими часами это тик взрыва.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_craft", skyAccess = true)
    public static void craftBlocksGoRightAfterDamage(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos base = CENTER.offset(3, 0, -2);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 5; z++) h.setBlock(base.offset(x, y, z), Blocks.OAK_PLANKS);
        BlockPos a = h.absolutePos(base), b = h.absolutePos(base.offset(2, 2, 4));
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        List<BlockPos> plot = new ArrayList<>();
        long[] at = {-1};
        Consumer<ExplosionEvent.Detonate> onBlast = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > 0.01) return;
            at[0] = level.getGameTime();
            for (BlockPos p : e.getAffectedBlocks()) {
                if (SubLevels.inPlotGrid(level, new ChunkPos(p)) && !level.getBlockState(p).isAir()) plot.add(p.immutable());
            }
        };
        List<String> left = new ArrayList<>();
        boolean[] checked = {false};
        Consumer<ServerTickEvent.Post> afterScheduler = e -> {
            if (at[0] < 0 || checked[0]) return;
            checked[0] = true;
            for (BlockPos p : plot) if (!level.getBlockState(p).isAir()) left.add(p + " " + level.getBlockState(p));
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(onBlast);
            NeoForge.EVENT_BUS.unregister(afterScheduler);
        });
        int[] waited = {0};
        h.onEachTick(() -> {
            // Sable достраивает аппарат не обязательно в том же тике: взрыв — когда площадка под ним пуста
            if (at[0] >= 0 || waited[0]++ != 5) return;
            h.assertTrue(level.getBlockState(a).isAir(), "аппарат не собран: " + level.getBlockState(a));
            WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(MS));
            Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
        });
        StrikeGameTests.afterTest(h, () -> WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock()));
        h.succeedWhen(() -> {
            h.assertTrue(checked[0], "взрыва нет");
            h.assertFalse(plot.isEmpty(), "лучи не выбрали ни одного блока аппарата");
            // блоки аппарата в выбранном — значит, лучи шли ванильным explode() с миксином Sable
            h.assertTrue(left.isEmpty(), "блоки аппарата не сняты в тике взрыва: " + left);
        });
    }

    /**
     * Аппарат в охвате лучей, но дальше 1,3 силы (в воздухе луч уходит до ~1,73 силы): взрыв — ванильным путём, иначе
     * миксин Sable не толкнул бы аппарат и не снял бы его блоки. На старом охвате (сила × 1,3 + 1) падает.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_craft_reach", skyAccess = true)
    public static void craftInRayReachGoesVanilla(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        float power = 8;
        // ближний край аппарата — в 12 блоках: дальше 1,3 × 8 + 1, ближе, чем луч силы до 10,4 уходит по воздуху (~13,9)
        BlockPos base = CENTER.offset(12, 0, -1);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) h.setBlock(base.offset(x, y, z), Blocks.OAK_PLANKS);
        BlockPos a = h.absolutePos(base), b = h.absolutePos(base.offset(2, 2, 2));
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        int[] waited = {0};
        Warheads.Probe[] blast = {null};
        h.onEachTick(() -> {
            if (blast[0] != null || waited[0]++ != 5) return;
            h.assertTrue(level.getBlockState(a).isAir(), "аппарат не собран: " + level.getBlockState(a));
            h.assertTrue(at.distanceTo(Vec3.atLowerCornerOf(a)) > power * 1.3 + 1, "аппарат ближе старого охвата — проверять нечего");
            blast[0] = Warheads.testRays(level, at, power, null, 1L, false, 128);
        });
        h.succeedWhen(() -> {
            h.assertTrue(blast[0] != null && blast[0].done(), "взрыв не кончился");
            h.assertTrue(blast[0].vanilla(), "аппарат в охвате лучей, а путь свой");
        });
    }

    /**
     * Три подрыва бетонобойной бомбы (один район, один тик) на считающих часах: все выбирают лучами по нетронутой породе —
     * ни один блок, выбранный прошлым подрывом, не снят к {@code ExplosionEvent.Detonate} следующего. Иначе лучи
     * следующего шли то по снятому, то нет (и песок, начавший падать, разлетался от следующего подрыва), и воронка
     * зависела от скорости сервера: на CI — от 5,3 до 8,6 тыс. блоков.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_area_order", skyAccess = true)
    public static void areaExplosionsPickTogether(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(CENTER.offset(-10, -10, -10), CENTER.offset(10, 0, 10))) {
            level.setBlock(h.absolutePos(p), Blocks.STONE.defaultBlockState(), 2);
        }
        useCounting(h, WorkClock.counting(MS));
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(CENTER.offset(0, -5, 0)));
        List<Blast> blasts = recordBlasts(h, 48);
        List<String> early = new ArrayList<>();
        long[] first = {-1}, last = {-1};
        Consumer<ExplosionEvent.Detonate> check = e -> {
            // после записи взрывов (LOWEST): последний в списке — этот подрыв
            if (e.getLevel() != level || e.getExplosion().radius() < 12 || e.getExplosion().center().distanceTo(charge) > 8) return;
            if (first[0] < 0) first[0] = level.getGameTime();
            last[0] = level.getGameTime();
            for (int i = 0; i < blasts.size() - 1; i++) {
                if (blasts.get(i).radius() < 12) continue;
                for (BlockPos p : blasts.get(i).blocks()) {
                    if (gone(level.getBlockState(p))) {
                        early.add("к подрыву " + (i + 2) + " снят " + p + " прошлого");
                        return;
                    }
                }
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, check);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(check));
        Warheads.bunker(level, charge, charge.add(0, 14, 0), null, null);
        h.succeedWhen(() -> {
            long mains = blasts.stream().filter(b -> b.radius() >= 12).count();
            h.assertTrue(mains == 3 && StrikeWorld.get(level).impacts().isEmpty(), "подрывов " + mains);
            h.assertTrue(last[0] > first[0], "все подрывы в одном тике — порядок не проверен");
            h.assertTrue(early.isEmpty(), early.toString());
        });
    }

    /**
     * Порции блоков — по времени: на считающих часах (1 мс на блок, срок 5 мс) в порции ровно 5 блоков; блок дольше
     * 10 мс (11 мс на блок) — порция из одного блока и строка «блок снимался» (в лог — первые пять за удар). Всё, что
     * выбрали лучи, снесено.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "work_portion_time", skyAccess = true)
    public static void blockPortionsByTime(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos[] centres = {CENTER.offset(-14, -6, 0), CENTER.offset(14, -6, 0)};
        for (BlockPos c : centres) {
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-5, -5, -5), c.offset(5, 5, 5))) {
                level.setBlock(h.absolutePos(p), Blocks.DIRT.defaultBlockState(), 2);
            }
        }
        List<Blast> blasts = recordBlasts(h, 48);
        long[] fast = {0}, slow = {0};
        Warheads.Probe quick = Warheads.testBlast(level, Vec3.atCenterOf(h.absolutePos(centres[0])), 6, true, false,
                () -> fast[0] += MS, 5 * MS);
        Warheads.Probe heavy = Warheads.testBlast(level, Vec3.atCenterOf(h.absolutePos(centres[1])), 6, true, false,
                () -> slow[0] += 11 * MS, 5 * MS);
        h.succeedWhen(() -> {
            h.assertTrue(quick.done() && heavy.done() && blasts.size() == 2, "взрывов " + blasts.size());
            int blocks = 0;
            for (Blast b : blasts) {
                for (BlockPos p : b.blocks()) h.assertTrue(gone(level.getBlockState(p)), "выбран лучами и не снесён: " + p);
                blocks += b.blocks().size();
            }
            h.assertTrue(blocks > 60, "лучи выбрали мало: " + blocks);
            h.assertTrue(quick.largestPortion() == 5, "порция по 1 мс на блок: " + quick.largestPortion() + " блоков");
            h.assertTrue(quick.slowBlocks() == 0, "долгих блоков при 1 мс: " + quick.slowBlocks());
            h.assertTrue(heavy.largestPortion() == 1, "порция по 11 мс на блок: " + heavy.largestPortion() + " блоков");
            h.assertTrue(heavy.slowBlocks() > BLAST_SLOW_LOGGED, "долгих блоков " + heavy.slowBlocks());
        });
    }

    /** Строк «блок снимался» за удар (как {@code BlastArea.SLOW_BLOCKS_LOGGED}). */
    private static final int BLAST_SLOW_LOGGED = 5;

    /**
     * Ванильный путь (как у аппарата): урон — по списку сущностей после {@code ExplosionEvent.Detonate}, и бьют его
     * единицы мода (обработчик {@code Detonate} мода забирает список, ванильный цикл {@code explode()} идёт по пустому).
     * Корова, которую обработчик убрал из списка, цела; соседняя — ранена.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "work_handoff", skyAccess = true)
    public static void vanillaPathDamagesDetonateList(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        net.minecraft.world.entity.animal.Cow spared = h.spawn(net.minecraft.world.entity.EntityType.COW, CENTER.offset(-3, 1, 0));
        net.minecraft.world.entity.animal.Cow hit = h.spawn(net.minecraft.world.entity.EntityType.COW, CENTER.offset(3, 1, 0));
        boolean[] listed = {false};
        Consumer<ExplosionEvent.Detonate> on = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(at) > 0.01) return;
            listed[0] = e.getAffectedEntities().remove(spared);
        };
        NeoForge.EVENT_BUS.addListener(on);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(on));
        Warheads.Probe blast = Warheads.testBlast(level, at, 4, false, true, System::nanoTime, 5 * MS);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            h.assertTrue(listed[0], "корова не попала в список Detonate — проверять нечего");
            h.assertTrue(blast.vanilla(), "взрыв шёл не ванильным путём");
            h.assertTrue(blast.handedDamage(), "урон сделал ванильный цикл explode(), а не единицы мода");
            h.assertTrue(spared.isAlive() && spared.getHealth() == spared.getMaxHealth(), "убранная из списка ранена: " + spared.getHealth());
            h.assertTrue(!hit.isAlive() || hit.getHealth() < hit.getMaxHealth(), "корова в списке не ранена");
        });
    }

    /**
     * Взрыв у аппарата Sable: блок аппарата, который обработчик {@code ExplosionEvent.Detonate} убрал из выбранного,
     * остаётся; остальные выбранные блоки аппарата сняты в тике, где взрыв добил сущности (настоящие часы: единицы
     * взрыва могут уйти и в следующие тики).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_handoff_craft", skyAccess = true)
    public static void craftBlockLeftByDetonateStays(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos base = CENTER.offset(3, 0, -2);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 5; z++) h.setBlock(base.offset(x, y, z), Blocks.OAK_PLANKS);
        BlockPos a = h.absolutePos(base), b = h.absolutePos(base.offset(2, 2, 4));
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        BlockPos[] kept = {null};
        List<BlockPos> plot = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> onBlast = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(c) > 0.01) return;
            for (BlockPos p : e.getAffectedBlocks()) {
                if (!SubLevels.inPlotGrid(level, new ChunkPos(p)) || level.getBlockState(p).isAir()) continue;
                if (kept[0] == null) kept[0] = p.immutable();
                else plot.add(p.immutable());
            }
            if (kept[0] != null) e.getAffectedBlocks().remove(kept[0]);
        };
        Warheads.Probe[] blast = {null};
        List<String> wrong = new ArrayList<>();
        boolean[] checked = {false};
        Consumer<ServerTickEvent.Post> afterScheduler = e -> {
            if (blast[0] == null || blast[0].picking() || checked[0]) return;
            checked[0] = true;
            if (kept[0] != null && level.getBlockState(kept[0]).isAir()) wrong.add("убранный из выбранного снят: " + kept[0]);
            for (BlockPos p : plot) if (!level.getBlockState(p).isAir()) wrong.add("не снят " + p + " " + level.getBlockState(p));
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(onBlast);
            NeoForge.EVENT_BUS.unregister(afterScheduler);
        });
        int[] waited = {0};
        h.onEachTick(() -> {
            if (blast[0] != null || waited[0]++ != 5) return;
            h.assertTrue(level.getBlockState(a).isAir(), "аппарат не собран: " + level.getBlockState(a));
            blast[0] = Warheads.testBlast(level, c, 4, true, false, System::nanoTime, 5 * MS);
        });
        h.succeedWhen(() -> {
            h.assertTrue(checked[0] && blast[0].done(), "взрыва нет");
            h.assertTrue(kept[0] != null && !plot.isEmpty(), "лучи выбрали мало блоков аппарата: " + (kept[0] == null ? 0 : 1 + plot.size()));
            h.assertTrue(blast[0].vanilla(), "у аппарата не ванильный путь");
            h.assertTrue(wrong.isEmpty(), wrong.toString());
        });
    }

    /**
     * Обшивка аппарата закрывает от взрыва, как у ванили: стенка из стекла (аппарат Sable) между взрывом и коровой.
     * Ванильный {@code level.explode} бьёт сущности до снятия блоков — корова за стенкой почти цела; у мода (ванильный
     * путь, урон — его единицами) корова ранена не больше, хотя лучи выбрали стенку и к концу взрыва она снята.
     * Пока блоки аппарата снимались при разделе, до урона, корова оставалась без обшивки и погибала.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_craft_shield", skyAccess = true)
    public static void craftHullShieldsLikeVanilla(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        // мод — A, ваниль — B: в 24 блоках, дальше охвата лучей и урона (2 × сила) друг друга
        BlockPos oA = CENTER.offset(0, 0, -12), oB = CENTER.offset(0, 0, 12);
        float power = 4;
        net.minecraft.world.entity.animal.Cow[] cows = new net.minecraft.world.entity.animal.Cow[2];
        BlockPos[] walls = new BlockPos[2];
        Vec3[] centers = new Vec3[2];
        for (int i = 0; i < 2; i++) {
            BlockPos o = i == 0 ? oA : oB;
            BlockPos w = o.offset(1, 0, -1);
            for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) h.setBlock(w.offset(0, y, z), Blocks.GLASS);
            BlockPos a = h.absolutePos(w), b = h.absolutePos(w.offset(0, 2, 2));
            level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4)
                    .withSuppressedOutput(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d",
                    a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
            walls[i] = a;
            centers[i] = Vec3.atBottomCenterOf(h.absolutePos(o)).add(0, 1.5, 0);
            cows[i] = h.spawn(net.minecraft.world.entity.EntityType.COW, o.offset(4, 0, 0));
            cows[i].setNoAi(true);
        }
        List<BlockPos> plot = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> onBlast = e -> {
            if (e.getLevel() != level || e.getExplosion().center().distanceTo(centers[0]) > 0.01) return;
            for (BlockPos p : e.getAffectedBlocks()) {
                if (SubLevels.inPlotGrid(level, new ChunkPos(p)) && !level.getBlockState(p).isAir()) plot.add(p.immutable());
            }
        };
        Warheads.Probe[] blast = {null};
        float[] health = {-1, -1};
        Consumer<ServerTickEvent.Post> afterScheduler = e -> {
            if (blast[0] == null || blast[0].picking() || health[0] >= 0) return;
            health[0] = cows[0].getHealth();
            health[1] = cows[1].getHealth();
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(onBlast);
            NeoForge.EVENT_BUS.unregister(afterScheduler);
            WorkScheduler.useImpactClock(level.getServer(), WorkScheduler.newImpactClock());
        });
        int[] waited = {0};
        h.onEachTick(() -> {
            if (blast[0] != null || waited[0]++ != 5) return;
            for (BlockPos w : walls) h.assertTrue(level.getBlockState(w).isAir(), "аппарат не собран: " + level.getBlockState(w));
            // считающие часы: весь взрыв мода — в этом же тике, аппараты не успевают сдвинуться между лучами и уроном
            WorkScheduler.useImpactClock(level.getServer(), WorkClock.counting(MS));
            blast[0] = Warheads.testBlast(level, centers[0], power, true, true, System::nanoTime, 5 * MS);
            level.explode(null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, null, null), null,
                    centers[1].x, centers[1].y, centers[1].z, power, false, Level.ExplosionInteraction.TNT);
        });
        h.succeedWhen(() -> {
            h.assertTrue(health[0] >= 0 && blast[0].done(), "взрыва нет");
            h.assertTrue(blast[0].vanilla(), "у аппарата не ванильный путь");
            float max = cows[1].getMaxHealth();
            // без обшивки корова в 3,5 блока от взрыва силы 4 гибнет; за ней — 1 единица урона
            h.assertTrue(health[1] >= max - 1.5f, "ваниль: корова за обшивкой ранена " + health[1] + " из " + max + " — обшивка не закрывает, проверять нечего");
            h.assertTrue(plot.size() >= 3, "лучи выбрали мало блоков обшивки: " + plot.size());
            List<String> left = new ArrayList<>();
            for (BlockPos p : plot) if (!level.getBlockState(p).isAir()) left.add(p + " " + level.getBlockState(p));
            h.assertTrue(left.isEmpty(), "выбранная обшивка не снята: " + left);
            h.assertTrue(health[0] >= health[1] - 0.01f, "мод: корова за обшивкой ранена сильнее ванили: " + health[0] + " против " + health[1]);
        });
    }

    /**
     * Работа, чей район не готов, ждёт и ничего не читает (иначе чанк загрузился бы в тике), а следующие за ней идут:
     * подрыв вдали от загруженного мира и подрыв на площадке — второй в тике удара, первый — когда район догружен.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "work_unready", skyAccess = true)
    public static void unitSkipsUnreadyChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 near = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Vec3 far = near.add(0, 0, -5200);
        double area = 32;
        h.assertFalse(Terrain.readyAround(level, far, area), "район вдали уже загружен");
        int[] nearN = {0}, farN = {0};
        Consumer<ExplosionEvent.Detonate> count = e -> {
            if (e.getLevel() != level) return;
            if (e.getExplosion().center().distanceTo(near) < 24) nearN[0]++;
            if (e.getExplosion().center().distanceTo(far) < 24) farN[0]++;
        };
        boolean[] checked = {false};
        Consumer<ServerTickEvent.Post> afterScheduler = e -> {
            if (checked[0]) return;
            checked[0] = true;
            // в том же тике, после планировщика: ближний сделан, дальний ждёт и район не загружен синхронно
            if (nearN[0] < 1) h.fail("подрыв на площадке не сделан в тике удара");
            if (farN[0] != 0) h.fail("подрыв вдали сделан в неготовом районе");
            if (Terrain.readyAround(level, far, area)) h.fail("район вдали загрузился в тике удара");
        };
        NeoForge.EVENT_BUS.addListener(count);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, afterScheduler);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(count);
            NeoForge.EVENT_BUS.unregister(afterScheduler);
        });
        Warheads.detonate(level, WeaponType.DRONE, far, null, null);
        Warheads.detonate(level, WeaponType.DRONE, near, null, null);
        h.runAfterDelay(3, () -> {
            for (int cx = Mth.floor(far.x - area) >> 4; cx <= Mth.floor(far.x + area) >> 4; cx++)
                for (int cz = Mth.floor(far.z - area) >> 4; cz <= Mth.floor(far.z + area) >> 4; cz++) level.getChunk(cx, cz);
        });
        h.succeedWhen(() -> {
            h.assertTrue(checked[0], "тик планировщика не прошёл");
            h.assertTrue(farN[0] >= 1, "подрыв вдали не сделан и после загрузки района");
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста");
            // район держали таймлайн и работы очереди: отпущен, когда отпустили все
            h.assertTrue(blastTickets(level, far) == 0, "тикеты района вдали остались: " + blastTickets(level, far));
        });
    }

    /**
     * Блэкаут и залп в одних тиках (считающие часы у обеих полос): пока у блэкаута есть работа, попадания берут не больше
     * 2/3 общего срока и одной единицы, а блэкаут делает свои единицы; весь тик — не больше общего срока и одной
     * единицы на полосу.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "work_lanes", skyAccess = true)
    public static void lanesShareTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        buildTown(h);
        WorkClock impact = WorkClock.counting(MS), grid = WorkClock.counting(MS);
        useCounting(h, impact);
        Blackouts.useClock(server, grid);
        long total = WorkScheduler.totalNanos();
        boolean[] gridBusy = {false};
        int[] shared = {0}, gridWorked = {0};
        List<String> over = new ArrayList<>();
        Consumer<ServerTickEvent.Pre> before = e -> gridBusy[0] = Blackouts.pending(server);
        Consumer<ServerTickEvent.Post> after = e -> {
            long used = impact.usedThisTickNanos() + grid.usedThisTickNanos();
            if (impact.workedThisTick() && used > total + 2 * MS) over.add("тик " + used / MS + " мс");
            if (!gridBusy[0] || !impact.workedThisTick()) return;
            shared[0]++;
            if (grid.workedThisTick()) gridWorked[0]++;
            if (impact.usedThisTickNanos() > total * 2 / 3 + MS) over.add("попадания " + impact.usedThisTickNanos() / MS + " мс при блэкауте");
        };
        NeoForge.EVENT_BUS.addListener(before);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, after);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(before);
            NeoForge.EVENT_BUS.unregister(after);
            Blackouts.useClock(server, Blackouts.newClock());
            Blackouts.restore(level, null, 0, 0);
        });
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Blackouts.blackout(level, c, 200, 2, -1);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(Blackouts.pending(server), "у блэкаута нет работы"))
                .thenExecute(() -> {
                    for (int i = 0; i < 5; i++) {
                        double a = i * Math.PI * 2 / 5;
                        Warheads.detonate(level, WeaponType.DRONE, c.add(Math.cos(a) * 14, 0, Math.sin(a) * 14), null, null);
                    }
                    Warheads.detonate(level, WeaponType.MISSILE, c, null, null);
                })
                .thenWaitUntil(() -> h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь попаданий не пуста"))
                .thenExecute(() -> {
                    h.assertTrue(over.isEmpty(), "за бюджетом: " + over);
                    h.assertTrue(shared[0] > 0, "залп и блэкаут ни разу не шли в одном тике");
                    h.assertTrue(gridWorked[0] > 0, "в общих тиках блэкаут не сделал ни одной единицы (" + shared[0] + " тиков)");
                    Airstrike.LOG.info("WORKBENCH полосы: общих тиков {}, из них блэкаут работал в {}", shared[0], gridWorked[0]);
                })
                .thenSucceed();
    }
}
