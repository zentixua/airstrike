package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;
import ua.zentix.airstrike.target.TargetTracker;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.work.StallWatch;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * Жизненный цикл снарядов и чанков — то, что стенд нагрузки ({@code tools/stress.sh}) ловил в игре с друзьями:
 * луч прицела не грузит чанки на сервере, тикеты отпускаются при любом уходе снаряда из мира (и при выгрузке
 * вместе с чанком), при остановке сервера снаряды уходят в полёт вне мира, а не замирают в файлах чанков.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LifecycleGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private LifecycleGameTests() {}

    /**
     * «Куда смотрю» с экрана пульта и из команд прицеливается на сервере: луч на 400 блоков не должен грузить
     * чанки на пути (раньше clip грузил и генерировал их прямо в тике — сервер вставал на секунды).
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "picker_chunks")
    public static void serverPickDoesNotLoadChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // выше барьерной стены вокруг площадки: над плоским миром теста луч идёт по воздуху все 400 блоков
        Vec3 eye = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 100, 0);
        Vec3 look = new Vec3(1, 0, 0);
        // первый незагруженный чанк по лучу
        ChunkPos unready = null;
        for (int d = 16; d <= 400 && unready == null; d += 4) {
            BlockPos p = BlockPos.containing(eye.add(look.scale(d)));
            if (!Terrain.ready(level, p)) unready = new ChunkPos(p);
        }
        h.assertTrue(unready != null, "по лучу нет незагруженного чанка — проверять нечего");
        ArmorStand viewer = new ArmorStand(level, eye.x, eye.y - 1.6, eye.z);
        TargetPicker.pick(level, viewer, eye, look, 400);
        h.assertTrue(level.getChunkSource().getChunkNow(unready.x, unready.z) == null, "луч прицела загрузил чанк " + unready);
        h.succeed();
    }

    /**
     * Снаряд в мире держит тикеты своего чанка и чанка впереди; выгрузка вместе с чанком ({@code setRemoved},
     * без {@code remove}) должна их отпустить — иначе чанки висели загруженными до перезапуска.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "tickets_unload", skyAccess = true)
    public static void ticketsReleasedWhenUnloadedWithChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        // на направляющей (стоит до поджига): держит тикет своего чанка и никуда не улетает
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(start, 0, 40, 1000, 0, new Target.Point(start.add(0, 0, 3000)), start.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        h.runAfterDelay(3, () -> {
            h.assertTrue(chunkTickets(level, id) > 0, "снаряд не взял тикет своего чанка: " + state(level, m));
            m.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(chunkTickets(level, id) == 0, "тикет чанка остался после выгрузки снаряда");
            h.succeed();
        });
    }

    /**
     * Район цели (тикет региона или прогрев по чанкам) держит и барражирующий в мире; выгрузка с чанком отпускает и его.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "tickets_area", skyAccess = true)
    public static void targetAreaReleasedWhenUnloadedWithChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0.5, 0);
        LoiterEntity l = ModEntities.LOITER.get().create(level);
        l.launch(target.add(-10, 45, 0), new Target.Point(target), target, null);
        l.setRoute(null);
        level.addFreshEntity(l);
        UUID id = l.getUUID();
        h.runAfterDelay(3, () -> {
            h.assertTrue(flightTickets(level, id) > 0, "барражирующий не взял район цели: " + state(level, l));
            l.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(flightTickets(level, id) == 0, "район цели остался после выгрузки снаряда");
            h.succeed();
        });
    }

    /**
     * Остановка сервера: снаряд из мира переходит в полёт вне мира с тем же UUID (его сохранит {@link VirtualFlights})
     * и отпускает тикеты.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "shutdown_park", skyAccess = true)
    public static void projectileParksOnShutdown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        // на направляющей (стоит до поджига): держит тикет своего чанка и никуда не улетает
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(start, 0, 40, 1000, 0, new Target.Point(start.add(0, 0, 3000)), start.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        h.runAfterDelay(3, () -> {
            h.assertTrue(chunkTickets(level, id) > 0, "снаряд не взял тикет своего чанка: " + state(level, m));
            m.parkForShutdown(level);
            h.assertTrue(m.isRemoved() && level.getEntity(id) == null, "снаряд остался в мире");
            h.assertTrue(VirtualFlights.get(level).flights().stream().anyMatch(p -> p.getUUID().equals(id)), "снаряд не ушёл в полёт вне мира");
            h.assertTrue(chunkTickets(level, id) == 0, "тикет чанка остался");
            VirtualFlights.get(level).flights().removeIf(p -> {
                if (!p.getUUID().equals(id)) return false;
                p.discard();
                return true;
            });
            h.succeed();
        });
    }

    /**
     * Остановка сервера, пока снаряд стоит на пусковой в закрытой ячейке: после запуска он возвращается в мир на ту же
     * направляющую (а не над рельефом, как снаряд в полёте) и по-прежнему скрыт, пока пакет поднимается.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "shutdown_rail", skyAccess = true)
    public static void parkedLauncherRoundReturnsToRail(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(rail, 0, 40, 1000, 200, new Target.Point(rail.add(0, 0, 3000)), rail.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        h.runAfterDelay(3, () -> m.parkForShutdown(level));
        h.runAfterDelay(8, () -> {
            h.assertTrue(level.getEntity(id) instanceof StrikeProjectile, "снаряд не вернулся в мир: " + state(level, m));
            StrikeProjectile back = (StrikeProjectile) level.getEntity(id);
            h.assertTrue(back != m && back.position().distanceTo(rail) < 1e-6, "снаряд вернулся не на направляющую: " + back.position() + " вместо " + rail);
            h.assertTrue(back.flightPhase() == FlightPhase.READY && !back.isActive(), "снаряд в закрытой ячейке виден: " + state(level, back));
            back.discard();
            h.succeed();
        });
    }

    /**
     * Снаряд вне мира, у которого кончился запас хода, убирается — и не берёт район цели заново в том же тике
     * (стенд нагрузки: 38 региональных тикетов остались после ракет, не дождавшихся района цели).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "virtual_expiry", skyAccess = true)
    public static void expiredVirtualFlightLeavesNoTargetArea(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 60, 0);
        Vec3 target = start.add(300, -60, 0);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(start, new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        // запас хода — на два тика: сохранить и прочитать обратно с другим запасом
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        m.saveWithoutId(tag);
        tag.getCompound("mission").putDouble("range", 2 * m.cruiseSpeed());
        m.load(tag);
        VirtualFlights.launch(level, m);
        UUID id = m.getUUID();
        h.runAfterDelay(6, () -> {
            h.assertTrue(m.isRemoved(), "снаряд не убран по сроку жизни: " + state(level, m));
            h.assertTrue(flightTickets(level, id) == 0, "район цели остался за убранным снарядом");
            h.succeed();
        });
    }

    /**
     * Неподвижная цель не теряется, как бы далеко ни был снаряд (раньше ракета «издалека», стартовав дальше
     * 3000 блоков, весь полёт показывала «цель потеряна»); движущаяся теряется, когда пропала или ушла дальше
     * запаса на погоню, и точка остаётся последней известной.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "tracker_loss")
    public static void onlyRunawayTargetIsLost(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8))).add(0, 0, 5000);
        TargetTracker point = new TargetTracker(new Target.Point(far), far);
        for (int i = 0; i < 3; i++) point.tick(level);
        h.assertFalse(point.isLost(), "неподвижная цель потеряна");

        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8)));
        ArmorStand stand = new ArmorStand(level, at.x, at.y, at.z);
        level.addFreshEntity(stand);
        TargetTracker moving = new TargetTracker(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), at);
        Vec3 near = at.add(20, 0, 0);
        stand.teleportTo(near.x, near.y, near.z);
        h.assertTrue(Math.abs(moving.tick(level) - 20) < 1e-6, "сдвиг цели не догоняется");
        h.assertFalse(moving.isLost(), "цель потеряна в пределах запаса на погоню");
        Vec3 runaway = near.add(0, 0, TargetTracker.CHASE_BUDGET);
        stand.teleportTo(runaway.x, runaway.y, runaway.z);
        h.assertTrue(moving.tick(level) == 0 && moving.isLost(), "цель ушла дальше запаса на погоню и не потеряна");
        h.assertTrue(moving.point().distanceTo(near) < 1e-6, "потерянная цель сдвинула точку удара");

        TargetTracker gone = new TargetTracker(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), runaway);
        stand.discard();
        gone.tick(level);
        h.assertTrue(gone.isLost(), "пропавшая цель не потеряна");
        h.succeed();
    }

    /**
     * Цель ушла далеко (игрок улетел за тысячи блоков, перенацеливание): запас хода растёт на пролёт этого сдвига.
     * Раньше он оставался по плану до старой точки, и снаряд пропадал в пути без подрыва (стенд нагрузки:
     * 5 «Ланцетов» и ракета за игроком, улетевшим на 3000 блоков и вышедшим там из игры). Запас на погоню
     * конечен: снаряд, который его уже выбрал, убирается по запасу хода и район цели не держит.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "moved_target", skyAccess = true)
    public static void rangeFollowsMovedTarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // старт над дальним углом площадки: до цели ~70 блоков, за время теста шахед до неё не долетит
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(56, 11, 56))).add(0, 60, 0);
        Vec3 near = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8)));
        ArmorStand stand = new ArmorStand(level, near.x, near.y, near.z);
        level.addFreshEntity(stand);
        StrikeProjectile followed = virtualWithTwoTicks(level, start, new Target.OfEntity(stand.getUUID(), Vec3.ZERO), near);
        StrikeProjectile retargeted = virtualWithTwoTicks(level, start, new Target.Point(near), near);
        // без запаса подорвётся в воздухе по запасу хода — в другом углу, чтобы взрыв не задел остальных
        Vec3 aside = Vec3.atCenterOf(h.absolutePos(new BlockPos(8, 11, 56))).add(0, 60, 0);
        StrikeProjectile exhausted = virtualWithTwoTicks(level, aside, new Target.Point(near), near);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        exhausted.saveWithoutId(tag);
        tag.getCompound("tracker").putDouble("chased", TargetTracker.CHASE_BUDGET);
        exhausted.load(tag);
        // цель переходит в другой угол площадки (шахеду это ~30 тиков полёта), новая цель — за 1500 блоков
        Vec3 moved = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(56, 11, 8)));
        stand.teleportTo(moved.x, moved.y, moved.z);
        Vec3 far = near.add(0, 0, 1500);
        h.assertTrue(retargeted.retarget(new Target.Point(far), far), "шахед не принял новую цель");
        h.assertTrue(exhausted.retarget(new Target.Point(far), far), "шахед без запаса не принял новую цель");
        h.runAfterDelay(6, () -> {
            h.assertFalse(followed.isRemoved(), "шахед за ушедшей целью убрал старый запас хода: " + state(level, followed));
            h.assertFalse(retargeted.isRemoved(), "перенацеленный шахед убрал старый запас хода: " + state(level, retargeted));
            h.assertTrue(exhausted.isRemoved(), "шахед без запаса на погоню не убран по запасу хода: " + state(level, exhausted));
            h.assertTrue(flightTickets(level, exhausted.getUUID()) == 0, "район цели остался за шахедом без запаса");
            for (StrikeProjectile m : List.of(followed, retargeted)) {
                VirtualFlights.get(level).flights().remove(m);
                m.discard();
            }
            stand.discard();
            h.succeed();
        });
    }

    /** Шахед в полёте вне мира, у которого запас хода — на два тика. */
    private static StrikeProjectile virtualWithTwoTicks(ServerLevel level, Vec3 start, Target target, Vec3 point) {
        StrikeProjectile m = ModEntities.DRONE.get().create(level);
        m.launch(start, target, point, null);
        m.setRoute(Route.direct());
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        m.saveWithoutId(tag);
        tag.getCompound("mission").putDouble("range", 2 * m.cruiseSpeed());
        m.load(tag);
        VirtualFlights.launch(level, m);
        return m;
    }

    /**
     * Снаряд в мире улетает за край загруженного: до выхода он держит тикеты, а уйдя в полёт вне мира, не держит
     * ни одного и в мире не остаётся (раньше он шагал в чанк без тика и мог застрять там с тикетами).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "leave_loaded", skyAccess = true)
    public static void projectileLeavingLoadedWorldHoldsNoTickets(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // снаряд РСЗО: баллистика уводит его вверх, над барьерной стеной площадки (крылатая ракета прижалась бы
        // к рельефу и разбилась о стену)
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 10, 0);
        Vec3 target = start.add(600, -10, 0);
        RocketEntity m = ModEntities.ROCKET.get().create(level);
        m.launchFrom(start, new Target.Point(target), target, null);
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        boolean[] held = new boolean[1];
        String[] away = new String[1];
        h.onEachTick(() -> {
            if (away[0] != null) return;
            if (level.getEntity(id) instanceof StrikeProjectile p && !p.isVirtual()) {
                if (chunkTickets(level, id) > 0) held[0] = true;
                h.assertTrue(level.isPositionEntityTicking(p.blockPosition()), "снаряд в мире стоит в чанке без тика: " + state(level, p));
            }
            for (StrikeProjectile p : List.copyOf(VirtualFlights.get(level).flights())) {
                if (!p.getUUID().equals(id)) continue;
                // ушёл: снимок того, что осталось за ним, и копия убирается (дальше тесту она не нужна)
                away[0] = "в мире " + (level.getEntity(id) != null) + ", тикетов " + chunkTickets(level, id);
                VirtualFlights.get(level).flights().remove(p);
                p.discard();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(away[0] != null, "снаряд не ушёл в полёт вне мира: " + state(level, m));
            h.assertTrue(held[0], "в мире снаряд не держал тикетов — проверять нечего");
            h.assertTrue(away[0].equals("в мире false, тикетов 0"), "после ухода из мира: " + away[0]);
        });
    }

    /**
     * Мир без игроков (Незер, куда никто не заходил) через 300 тиков перестаёт тикать сущности, а тикеты снаряда
     * принудительной загрузкой не считаются: пока в мире идёт удар, мир не засыпает. Снаряд на направляющей стоит
     * до поджига и тикает на месте — его возраст растёт, только пока мир тикает сущности.
     */
    @GameTest(template = "pad", timeoutTicks = 1200, batch = "empty_dimension")
    public static void strikeKeepsEmptyDimensionAwake(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null && nether.players().isEmpty(), "нет Незера без игроков");
        // над крышей из бедрока, в колонке площадки теста (свои, дальние координаты у каждого запуска)
        BlockPos at = new BlockPos(h.absolutePos(BlockPos.ZERO).getX(), 140, h.absolutePos(BlockPos.ZERO).getZ());
        ChunkPos chunk = new ChunkPos(at);
        UUID holder = UUID.randomUUID();
        ChunkTickets.hold(nether, holder, chunk.toLong(), true);
        // сущности в чанке тикают, когда готовы и соседи в два чанка: в тесте — сразу, без ожидания фоновой генерации
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) nether.getChunk(chunk.x + dx, chunk.z + dz);
        }
        Vec3 rail = Vec3.atBottomCenterOf(at);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(nether);
        m.placeOnLauncher(rail, 0, 40, 5000, 0, new Target.Point(rail.add(0, 0, 3000)), rail.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        long[] placed = {-1};
        h.onEachTick(() -> {
            if (placed[0] < 0 && nether.isPositionEntityTicking(at)) {
                nether.addFreshEntity(m);
                placed[0] = h.getTick();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(placed[0] >= 0 && h.getTick() - placed[0] >= 400, "ждём 400 тиков после пуска: " + state(nether, m));
            // без удара мир уснул бы не позже чем через 300 тиков, и возраст снаряда остановился бы
            h.assertTrue(m.age() >= 390, "мир без игроков уснул посреди удара: " + state(nether, m));
            m.discard();
            ChunkTickets.hold(nether, holder, chunk.toLong(), false);
        });
    }

    private static String state(ServerLevel level, StrikeProjectile p) {
        return "removed=" + p.getRemovalReason() + " virtual=" + VirtualFlights.get(level).flights().stream().anyMatch(v -> v.getUUID().equals(p.getUUID()))
                + " phase=" + p.flightPhase() + " ticking=" + level.isPositionEntityTicking(p.blockPosition()) + " age=" + p.age();
    }

    /**
     * Тикеты района цели этого снаряда (ключ — его UUID): тикет региона {@code FlightTickets} и, пока район
     * не готов целиком, тикет загрузки {@code AreaLoader}.
     */
    private static int flightTickets(ServerLevel level, UUID id) {
        return TicketProbe.count(level, "airstrike_flight", id) + TicketProbe.count(level, "airstrike_area_load", id);
    }

    /** Тикет района «как раньше» — ванильный тикет региона сразу на весь район (контроль проверки). */
    private static final TicketType<UUID> PLAIN_AREA = TicketType.create("airstrike_test_plain_area", Comparator.<UUID>naturalOrder());

    /**
     * Районы целей в свежем мире ({@code AreaLoader}): в любой тик блоки района тикают только там, где готовы все
     * соседние чанки (VPS 29.09.2026: улей у края района, выпуская пчелу, грузил соседа синхронно, сервер стоял
     * 2–18 с); у района один тикет региона, и его квадрат готов; тикет загрузки стоит, пока район не взят целиком;
     * сущности в центре тикают; отпущенный район не оставляет тикетов. Контроль — ванильный тикет региона на свежий
     * район, как было до {@code AreaLoader}: проверка должна поймать у него тикающие блоки рядом с неготовым чанком.
     */
    @GameTest(template = "range", timeoutTicks = 6000, batch = "tickets_background", skyAccess = true)
    public static void targetAreasTickOnlyNextToReadyChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // районы грузятся в фоне: срок — игровой (сервер GameTest тикает без пауз, на CI он прошёл бы раньше генерации)
        StrikeGameTests.gameSpeed(h);
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos[] centre = {new ChunkPos(base.x + 40, base.z), new ChunkPos(base.x + 70, base.z + 30), new ChunkPos(base.x - 50, base.z - 40)};
        int[] distance = {FlightTickets.DISTANCE, FlightTickets.DISTANCE, 6};
        UUID[] id = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        ChunkPos plain = new ChunkPos(base.x - 40, base.z + 60);
        UUID plainId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            h.assertFalse(Terrain.ready(level, centre[i].x, centre[i].z), "район " + i + " не свежий");
            FlightTickets.hold(level, centre[i], distance[i], id[i], true);
        }
        h.assertFalse(Terrain.ready(level, plain.x, plain.z), "контрольный район не свежий");
        level.getChunkSource().addRegionTicket(PLAIN_AREA, plain, FlightTickets.DISTANCE, plainId);
        int[] tick = {0}, plainTicking = {-1}, plainViolations = {0};
        int[] ticking = {-1, -1, -1};
        h.onEachTick(() -> {
            tick[0]++;
            for (int i = 0; i < 3; i++) {
                String where = "район " + i + ", тик " + tick[0] + ": ";
                String bad = tickingNextToUnready(level, centre[i], distance[i] + 1);
                if (bad != null) throw new GameTestAssertException(where + "блоки тикают в " + bad);
                List<Integer> radii = regionRadii(level, "airstrike_flight", id[i]);
                if (radii.size() > 1) throw new GameTestAssertException(where + "тикеты региона " + radii);
                int r = radii.isEmpty() ? -1 : radii.get(0);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (!Terrain.ready(level, centre[i].x + dx, centre[i].z + dz)) throw new GameTestAssertException(where + "тикет региона радиуса " + r + " на неготовый чанк " + dx + " " + dz);
                    }
                }
                int load = TicketProbe.count(level, "airstrike_area_load", id[i]);
                if (load != (r == distance[i] ? 0 : 1)) throw new GameTestAssertException(where + "тикетов загрузки " + load + " при радиусе " + r);
                if (ticking[i] < 0 && level.isPositionEntityTicking(centre[i].getMiddleBlockPosition(64))) ticking[i] = tick[0];
            }
            if (tickingNextToUnready(level, plain, FlightTickets.DISTANCE + 1) != null) plainViolations[0]++;
            if (plainTicking[0] < 0 && level.isPositionEntityTicking(plain.getMiddleBlockPosition(64))) plainTicking[0] = tick[0];
            boolean done = plainTicking[0] >= 0;
            for (int i = 0; i < 3; i++) done &= ticking[i] >= 0 && regionRadii(level, "airstrike_flight", id[i]).equals(List.of(distance[i]));
            if (!done) return;
            for (int i = 0; i < 3; i++) FlightTickets.hold(level, centre[i], distance[i], id[i], false);
            level.getChunkSource().removeRegionTicket(PLAIN_AREA, plain, FlightTickets.DISTANCE, plainId);
            for (int i = 0; i < 3; i++) h.assertTrue(flightTickets(level, id[i]) == 0, "тикеты района " + i + " остались после отпуска");
            Airstrike.LOG.info("Районы целей в фоне: сущности в центре тикают на тиках {}, район взят целиком на {}; ванильный тикет — на {}, тикающих блоков рядом с неготовым чанком у него {} тиков",
                    java.util.Arrays.toString(ticking), tick[0], plainTicking[0], plainViolations[0]);
            h.assertTrue(plainViolations[0] > 0, "проверка не поймала ванильный тикет региона на свежем районе");
            h.succeed();
        });
    }

    private static final TicketType<UUID> SHARED_A = TicketType.create("airstrike_test_shared_a", Comparator.<UUID>naturalOrder());
    private static final TicketType<UUID> SHARED_B = TicketType.create("airstrike_test_shared_b", Comparator.<UUID>naturalOrder());

    /**
     * Два района с одним центром, радиусом и ключом, но разными типами (как воронка и очередь разрушений ядерного
     * удара на одном чанке): отпуск одного, пока оба ещё грузятся, не снимает загрузку другого, и тот догружается
     * и берётся целиком. Ванильный тикет один на (тип, уровень, значение) — с общим тикетом загрузки второй район
     * оставался без тикета навсегда.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "tickets_shared", skyAccess = true)
    public static void releasingOneAreaKeepsTwinLoading(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(base.x + 60, base.z - 60);
        UUID key = UUID.randomUUID();
        AreaLoader areas = StrikeWorld.get(level).areas();
        AreaLoader.Area a = new AreaLoader.Area(SHARED_A, centre, 2, key), b = new AreaLoader.Area(SHARED_B, centre, 2, key);
        h.assertFalse(Terrain.ready(level, centre.x, centre.z), "район не свежий");
        areas.hold(level, a);
        areas.hold(level, b);
        h.assertTrue(TicketProbe.count(level, "airstrike_area_load", key) == 2, "у двух районов не два тикета загрузки");
        areas.release(level, a);
        h.assertTrue(TicketProbe.count(level, "airstrike_area_load", key) == 1, "отпуск одного района снял загрузку другого");
        h.onEachTick(() -> {
            if (!regionRadii(level, "airstrike_test_shared_b", key).equals(List.of(2))) return;
            h.assertTrue(regionRadii(level, "airstrike_test_shared_a", key).isEmpty(), "отпущенный район снова взят");
            areas.release(level, b);
            h.assertTrue(TicketProbe.count(level, "airstrike_area_load", key) + TicketProbe.count(level, "airstrike_test_shared_b", key) == 0, "тикеты района остались после отпуска");
            h.succeed();
        });
    }

    private static final TicketType<UUID> RELEASED = TicketType.create("airstrike_test_released", Comparator.<UUID>naturalOrder());

    /**
     * Отпуск многих районов разом (Отбой залпа; игра 03.10.2026 — тик 6,8 с, стенд — 5 с на сохранении ~9 тыс. чанков):
     * районы отпускаются по очереди, и в конце тика у ванили на выгрузке не больше её порога (2000 держателей; больше —
     * {@code ChunkMap.processUnloads} выгружает и сохраняет все сразу) и одного района сверх. Отпущенный район сразу не
     * тикает, очередь доходит до конца, тикет отпуска есть у каждого района в очереди и ни у кого больше. Район, взятый
     * снова, пока ждал очереди, тикает сразу. Район, который ещё грузился, отпускается без очереди (его генерация больше
     * не нужна).
     */
    @GameTest(template = "range", timeoutTicks = 4000, batch = "tickets_release", skyAccess = true)
    public static void releasedAreasUnloadInTurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkMap map = level.getChunkSource().chunkMap;
        AreaLoader areas = StrikeWorld.get(level).areas();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        int distance = 2;
        // держатели района — квадрат до уровня 44 (сторона 27): районы через 30 чанков не делят ни одного
        int side = 2 * (distance + ChunkLevel.RADIUS_AROUND_FULL_CHUNK) + 1;
        List<AreaLoader.Area> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AreaLoader.Area a = new AreaLoader.Area(RELEASED, new ChunkPos(base.x + 40 + 30 * i, base.z + 40), distance, UUID.randomUUID());
            h.assertFalse(Terrain.ready(level, a.centre().x, a.centre().z), "район " + i + " не свежий");
            areas.hold(level, a);
            list.add(a);
        }
        AreaLoader.Area kept = list.get(list.size() - 1);
        AreaLoader.Area loading = new AreaLoader.Area(RELEASED, new ChunkPos(base.x + 40, base.z + 80), distance, UUID.randomUUID());
        areas.hold(level, loading);
        areas.release(level, loading);
        h.assertTrue(areas.releasing() == 0 && TicketProbe.count(level, "airstrike_area_load", loading.key()) == 0, "район, который ещё грузился, ждёт очереди");
        int limit = 2000 + side * side;
        int[] tick = {0}, releasedAt = {-1}, keptReleasedAt = {-1}, waiting = {-1}, maxUnloading = {0};
        long[] last = {System.nanoTime()};
        h.onEachTick(() -> {
            tick[0]++;
            if (releasedAt[0] < 0) {
                // районы грузятся в фоне в темпе игры (на CI сервер GameTest без пауз прошёл бы срок раньше генерации);
                // выгрузка потом — без пауз: ваниль разбирает её во время, свободное в тике
                long deadline = last[0] + 50_000_000L, left;
                while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
                last[0] = System.nanoTime();
                for (AreaLoader.Area a : list) if (!regionRadii(level, "airstrike_test_released", a.key()).equals(List.of(distance))) return;
                for (AreaLoader.Area a : list) areas.release(level, a);
                areas.hold(level, kept);
                h.assertTrue(regionRadii(level, "airstrike_test_released", kept.key()).equals(List.of(distance)), "район, взятый снова, не взят целиком");
                releasedAt[0] = tick[0];
                return;
            }
            String where = "тик " + (tick[0] - releasedAt[0]) + " после отпуска: ";
            int unloading = map.toDrop.size() + map.pendingUnloads.size();
            maxUnloading[0] = Math.max(maxUnloading[0], unloading);
            if (unloading > limit) throw new GameTestAssertException(where + "на выгрузке " + unloading + " держателей, предел " + limit);
            int tickets = TicketProbe.count(level, "airstrike_area_release");
            if (tickets != areas.releasing()) throw new GameTestAssertException(where + "тикетов отпуска " + tickets + " у районов в очереди " + areas.releasing());
            for (AreaLoader.Area a : list) {
                boolean ticks = level.shouldTickBlocksAt(a.centre().toLong()) || level.isPositionEntityTicking(a.centre().getMiddleBlockPosition(64));
                if (ticks != (a == kept && keptReleasedAt[0] < 0)) throw new GameTestAssertException(where + "район " + list.indexOf(a) + (ticks ? " тикает" : " не тикает"));
            }
            if (waiting[0] < 0) waiting[0] = areas.releasing();
            if (areas.releasing() > 0) return;
            if (keptReleasedAt[0] < 0) {
                areas.release(level, kept);
                keptReleasedAt[0] = tick[0];
                return;
            }
            h.assertTrue(waiting[0] > 0, "районы отпущены разом, без очереди");
            Airstrike.LOG.info("Отпуск районов по очереди: после первого тика ждали {} из {}, очередь прошла за {} тиков, на выгрузке самое большее {} держателей (предел {})",
                    waiting[0], list.size() - 1, keptReleasedAt[0] - releasedAt[0], maxUnloading[0], limit);
            h.succeed();
        });
    }

    /** Поток сервера стоит в тике дольше 2 с ({@code StallWatch}): когда он пошёл дальше, строка остановки уходит в лог. */
    @GameTest(template = "range", timeoutTicks = 2000, batch = "stall_watch", skyAccess = true)
    public static void longStallIsReported(GameTestHelper h) {
        // ответ наблюдателя — через полсекунды после остановки: срок — игровой
        StrikeGameTests.gameSpeed(h);
        int before = StallWatch.reported();
        boolean[] stalled = {false};
        h.onEachTick(() -> {
            if (!stalled[0]) {
                stalled[0] = true;
                // parkNanos просыпается раньше (задачи чанков будят поток сервера): ждать до срока в цикле
                long deadline = System.nanoTime() + 3_000_000_000L, left;
                while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
                return;
            }
            if (StallWatch.reported() > before) h.succeed();
        });
    }

    /** Чанк в квадрате {@code radius} вокруг {@code c}, где тикают блоки, а сосед не готов, — или {@code null}. */
    private static String tickingNextToUnready(ServerLevel level, ChunkPos c, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = c.x + dx, z = c.z + dz;
                if (!level.shouldTickBlocksAt(ChunkPos.asLong(x, z))) continue;
                for (int nx = -1; nx <= 1; nx++) {
                    for (int nz = -1; nz <= 1; nz++) {
                        if (!Terrain.ready(level, x + nx, z + nz)) return "чанке " + dx + " " + dz + " (сосед " + nx + " " + nz + " не готов)";
                    }
                }
            }
        }
        return null;
    }

    /** Радиусы тикетов региона {@code type} с ключом {@code id} (уровень тикета — 33 − радиус). */
    static List<Integer> regionRadii(ServerLevel level, String type, UUID id) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>) f.get(level.getChunkSource().chunkMap.getDistanceManager());
            List<Integer> radii = new java.util.ArrayList<>();
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    if (t.getType().toString().equals(type) && id.equals(TicketProbe.key(t))) radii.add(33 - t.getTicketLevel());
                }
            }
            return radii;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Тикеты своего чанка и чанка впереди ({@code ChunkTickets}, ключ — UUID снаряда). */
    private static int chunkTickets(ServerLevel level, UUID id) {
        return TicketProbe.count(level, "airstrike_projectile", id);
    }
}
