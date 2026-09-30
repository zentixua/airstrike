package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.warhead.Warheads;
import ua.zentix.airstrike.work.WorkScheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
     * середины площадки (x и z от 11 до 53).
     */
    private static void buildTown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
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
        ServerLevel level = h.getLevel();
        List<Blast> blasts = recordBlasts(h, 16);
        h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "очередь не пуста до удара");
        long t = level.getGameTime();
        Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), null, null);
        h.runAfterDelay(1, () -> {
            h.assertFalse(blasts.isEmpty(), "подрыва нет и тиком позже");
            h.assertTrue(blasts.getFirst().gameTime() == t, "подрыв на тике " + blasts.getFirst().gameTime() + ", удар — на " + t);
            h.succeed();
        });
    }

    /**
     * Порядок: два шахеда в одном тике — подрыв первого снесён до конца раньше, чем начался подрыв второго; огненные шары
     * и вторичные — после обоих подрывов (очередь по порядку постановки, работа не прерывается другой).
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
        boolean[] firstDoneAtSecond = {false};
        Consumer<ExplosionEvent.Detonate> check = e -> {
            // после записи взрывов (LOWEST): подрыв второго — уже второй в списке
            if (e.getLevel() == level && e.getExplosion().center().distanceTo(second) < 0.01 && blasts.size() == 2) {
                firstDoneAtSecond[0] = blasts.getFirst().blocks().stream().allMatch(p -> gone(level.getBlockState(p)));
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, check);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(check));
        Warheads.detonate(level, WeaponType.DRONE, first, null, null);
        Warheads.detonate(level, WeaponType.DRONE, second, null, null);
        float power = AirstrikeConfig.SERVER.dronePower.get().floatValue();
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty() && blasts.size() == 12, "взрывов " + blasts.size());
            h.assertTrue(blasts.get(0).at().distanceTo(first) < 0.01 && Mth.equal(blasts.get(0).radius(), power), "первым — не подрыв первого");
            h.assertTrue(blasts.get(1).at().distanceTo(second) < 0.01 && Mth.equal(blasts.get(1).radius(), power), "вторым — не подрыв второго");
            h.assertTrue(firstDoneAtSecond[0], "подрыв второго начался, когда первый ещё не снёс свои блоки");
            for (int i = 2; i < blasts.size(); i++) h.assertTrue(blasts.get(i).radius() < power, "после подрывов — снова подрыв (" + i + ")");
            h.assertTrue(clock.ticksWorked() > 1, "всё в одном тике — порядок не проверен");
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
