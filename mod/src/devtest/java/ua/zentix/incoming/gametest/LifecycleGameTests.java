package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.legacy.OldNamespace;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.ChunkTickets;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetPicker;
import ua.zentix.incoming.target.TargetTracker;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.StallWatch;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Жизненный цикл снарядов и чанков — то, что стенд нагрузки ({@code tools/stress.sh}) проверяет в игре с друзьями:
 * луч прицела не грузит чанки на сервере, тикеты отпускаются при любом уходе снаряда из мира (и при выгрузке
 * вместе с чанком), при остановке сервера снаряды уходят в полёт вне мира, а не замирают в файлах чанков.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LifecycleGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private LifecycleGameTests() {}

    /**
     * Мир с записями под именем {@code airstrike}: каждая запись мода находится и по этому имени, а блок из
     * палитры чанка с ним читается блоком Incoming, а не воздухом.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "old_namespace")
    public static void oldNamespaceResolves(GameTestHelper helper) {
        int checked = 0;
        for (Registry<?> registry : List.<Registry<?>>of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM,
                BuiltInRegistries.ENTITY_TYPE, BuiltInRegistries.BLOCK_ENTITY_TYPE, BuiltInRegistries.MOB_EFFECT,
                BuiltInRegistries.DATA_COMPONENT_TYPE, NeoForgeRegistries.ATTACHMENT_TYPES)) {
            for (ResourceLocation id : registry.keySet()) {
                if (!id.getNamespace().equals(Incoming.MOD_ID)) continue;
                ResourceLocation old = ResourceLocation.fromNamespaceAndPath(OldNamespace.ID, id.getPath());
                if (registry.get(old) != registry.get(id)) {
                    throw new GameTestAssertException(old + " does not resolve to " + id + " in " + registry.key().location());
                }
                checked++;
            }
        }
        if (checked < 20) throw new GameTestAssertException("only " + checked + " entries of the mod found");
        Block block = BuiltInRegistries.BLOCK.stream()
                .filter(b -> BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals(Incoming.MOD_ID)).findFirst().orElseThrow();
        CompoundTag state = new CompoundTag();
        state.putString("Name", OldNamespace.ID + ":" + BuiltInRegistries.BLOCK.getKey(block).getPath());
        BlockState read = NbtUtils.readBlockState(helper.getLevel().holderLookup(Registries.BLOCK), state);
        helper.assertTrue(read.is(block), "old block name read as " + read);
        // данные мира переименовываются там, где их держит игра: Незер — DIM-1, Энд — DIM1
        Path root = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT);
        List<Path> folders = OldNamespace.dataFolders(helper.getLevel().getServer());
        for (String dim : List.of("DIM-1", "DIM1")) {
            helper.assertTrue(folders.contains(root.resolve(dim).resolve("data")), "no " + dim + "/data among " + folders);
        }
        helper.assertTrue(folders.contains(root.resolve("data")), "no data folder of the overworld among " + folders);
        helper.succeed();
    }

    /**
     * «Куда смотрю» с экрана пульта и из команд прицеливается на сервере: луч на 400 блоков не должен грузить
     * чанки на пути (ванильный clip грузит и генерирует их прямо в тике — сервер встаёт на секунды).
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
     * без {@code remove}) должна их отпустить — иначе чанки висели бы загруженными до перезапуска.
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
     * (иначе региональные тикеты остаются после ракет, не дождавшихся района цели).
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
     * Неподвижная цель не теряется, как бы далеко ни был снаряд (ракета, стартовавшая дальше 3000 блоков, не
     * показывает весь полёт «цель потеряна»); движущаяся теряется, когда пропала или ушла дальше
     * запаса на погоню, и точка остаётся последней известной.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "tracker_loss")
    public static void onlyRunawayTargetIsLost(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8))).add(0, 0, 5000);
        TargetTracker point = new TargetTracker(new Target.Point(far), far);
        for (int i = 0; i < 3; i++) point.tick(level, q -> false);
        h.assertFalse(point.isLost(), "неподвижная цель потеряна");

        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8)));
        ArmorStand stand = new ArmorStand(level, at.x, at.y, at.z);
        level.addFreshEntity(stand);
        TargetTracker moving = new TargetTracker(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), at);
        Vec3 near = at.add(20, 0, 0);
        stand.teleportTo(near.x, near.y, near.z);
        h.assertTrue(Math.abs(moving.tick(level, q -> false) - 20) < 1e-6, "сдвиг цели не догоняется");
        h.assertFalse(moving.isLost(), "цель потеряна в пределах запаса на погоню");
        Vec3 runaway = near.add(0, 0, TargetTracker.CHASE_BUDGET);
        stand.teleportTo(runaway.x, runaway.y, runaway.z);
        h.assertTrue(moving.tick(level, q -> false) == 0 && moving.isLost(), "цель ушла дальше запаса на погоню и не потеряна");
        h.assertTrue(moving.point().distanceTo(near) < 1e-6, "потерянная цель сдвинула точку удара");

        TargetTracker gone = new TargetTracker(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), runaway);
        stand.discard();
        gone.tick(level, q -> false);
        h.assertTrue(gone.isLost(), "пропавшая цель не потеряна");
        h.succeed();
    }

    /**
     * Цель ушла далеко (игрок улетел за тысячи блоков, перенацеливание): запас хода растёт на пролёт этого сдвига.
     * Без этого он остаётся по плану до старой точки, и снаряд пропадает в пути без подрыва (например,
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
     * ни одного и в мире не остаётся (иначе он шагает в чанк без тика и может застрять там с тикетами).
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
     * Снаряд из полёта вне мира не вошёл в мир — обработчик входа упал или отменил вход (моды защиты территорий): он
     * убирается и отпускает тикеты своих чанков, взятые до входа, и района цели. Ошибка не уходит из тика мира (там она
     * роняла бы сервер): следующий полёт того же тика тоже пробует войти.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "virtual_return_refused", skyAccess = true)
    public static void refusedReturnFromVirtualFlightLeavesNoTickets(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // середина площадки, путь на 16 блоков вперёд — тоже на ней; сущности в чанке тикают, когда готовы и соседи в
        // два чанка: в тесте — сразу, без ожидания фоновой генерации
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(20, 11, 30))).add(0, 20, 0);
        ChunkPos from = new ChunkPos(BlockPos.containing(start)), to = new ChunkPos(BlockPos.containing(start.add(40, 0, 4)));
        for (int x = Math.min(from.x, to.x) - 2; x <= Math.max(from.x, to.x) + 2; x++) {
            for (int z = Math.min(from.z, to.z) - 2; z <= Math.max(from.z, to.z) + 2; z++) level.getChunk(x, z);
        }
        StrikeProjectile crashing = ModEntities.DRONE.get().create(level);
        StrikeProjectile vetoed = ModEntities.DRONE.get().create(level);
        for (StrikeProjectile p : List.of(crashing, vetoed)) {
            Vec3 at = p == crashing ? start : start.add(0, 0, 4);
            Vec3 target = at.add(300, -20, 0);
            p.launch(at, new Target.Point(target), target, null);
            p.setRoute(Route.direct());
        }
        Map<UUID, Long> tried = new HashMap<>();
        Consumer<EntityJoinLevelEvent> refuse = e -> {
            UUID id = e.getEntity().getUUID();
            if (id.equals(crashing.getUUID()) || id.equals(vetoed.getUUID())) {
                tried.put(id, h.getTick());
                if (id.equals(crashing.getUUID())) throw new IllegalStateException("проверка: вход в мир падает с ошибкой");
                e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(refuse);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(refuse);
            VirtualFlights.get(level).clear(level, p -> p == crashing || p == vetoed);
        });
        boolean[] launched = {false};
        h.onEachTick(() -> {
            // в полёт вне мира — когда он сразу может вернуться: упавший вход первым в списке, отменённый за ним
            if (launched[0] || !crashing.canMaterialize(level) || !vetoed.canMaterialize(level)) return;
            VirtualFlights.launch(level, crashing);
            VirtualFlights.launch(level, vetoed);
            launched[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(tried.size() == 2, "в мир пробовали войти не оба: " + tried.size() + ", " + state(level, crashing) + "; " + state(level, vetoed));
            h.assertValueEqual(tried.get(vetoed.getUUID()), tried.get(crashing.getUUID()), "тик входа отменённого после упавшего");
            for (StrikeProjectile p : List.of(crashing, vetoed)) {
                UUID id = p.getUUID();
                h.assertTrue(p.isRemoved() && level.getEntity(id) == null && VirtualFlights.get(level).flights().stream().noneMatch(v -> v == p),
                        "снаряд, не вошедший в мир, остался: " + state(level, p));
                h.assertValueEqual(chunkTickets(level, id), 0, "тикеты чанков не вошедшего в мир снаряда");
                h.assertValueEqual(flightTickets(level, id), 0, "тикеты района цели не вошедшего в мир снаряда");
            }
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
        return TicketProbe.count(level, "incoming_flight", id) + TicketProbe.count(level, "incoming_area_load", id);
    }

    /** Тикет района без {@code AreaLoader} — ванильный тикет региона сразу на весь район (контроль проверки). */
    private static final TicketType<UUID> PLAIN_AREA = TicketType.create("incoming_test_plain_area", Comparator.<UUID>naturalOrder());

    /**
     * Районы целей в свежем мире ({@code AreaLoader}): в любой тик блоки района тикают только там, где готовы все
     * соседние чанки (иначе улей у края района, выпуская пчелу, грузит соседа синхронно, и сервер стоит
     * 2–18 с); у района один тикет региона, и его квадрат готов; тикет загрузки стоит, пока район не взят целиком;
     * сущности в центре тикают; отпущенный район не оставляет тикетов. Контроль — ванильный тикет региона на свежий
     * район без {@code AreaLoader}: проверка должна поймать у него тикающие блоки рядом с неготовым чанком.
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
                List<Integer> radii = regionRadii(level, "incoming_flight", id[i]);
                if (radii.size() > 1) throw new GameTestAssertException(where + "тикеты региона " + radii);
                int r = radii.isEmpty() ? -1 : radii.get(0);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (!Terrain.ready(level, centre[i].x + dx, centre[i].z + dz)) throw new GameTestAssertException(where + "тикет региона радиуса " + r + " на неготовый чанк " + dx + " " + dz);
                    }
                }
                int load = TicketProbe.count(level, "incoming_area_load", id[i]);
                if (load != (r == distance[i] ? 0 : 1)) throw new GameTestAssertException(where + "тикетов загрузки " + load + " при радиусе " + r);
                if (ticking[i] < 0 && level.isPositionEntityTicking(centre[i].getMiddleBlockPosition(64))) ticking[i] = tick[0];
            }
            if (tickingNextToUnready(level, plain, FlightTickets.DISTANCE + 1) != null) plainViolations[0]++;
            if (plainTicking[0] < 0 && level.isPositionEntityTicking(plain.getMiddleBlockPosition(64))) plainTicking[0] = tick[0];
            boolean done = plainTicking[0] >= 0;
            for (int i = 0; i < 3; i++) done &= ticking[i] >= 0 && regionRadii(level, "incoming_flight", id[i]).equals(List.of(distance[i]));
            if (!done) return;
            for (int i = 0; i < 3; i++) FlightTickets.hold(level, centre[i], distance[i], id[i], false);
            level.getChunkSource().removeRegionTicket(PLAIN_AREA, plain, FlightTickets.DISTANCE, plainId);
            for (int i = 0; i < 3; i++) h.assertTrue(flightTickets(level, id[i]) == 0, "тикеты района " + i + " остались после отпуска");
            Incoming.LOG.info("Районы целей в фоне: сущности в центре тикают на тиках {}, район взят целиком на {}; ванильный тикет — на {}, тикающих блоков рядом с неготовым чанком у него {} тиков",
                    java.util.Arrays.toString(ticking), tick[0], plainTicking[0], plainViolations[0]);
            h.assertTrue(plainViolations[0] > 0, "проверка не поймала ванильный тикет региона на свежем районе");
            h.succeed();
        });
    }

    private static final TicketType<UUID> SHARED_A = TicketType.create("incoming_test_shared_a", Comparator.<UUID>naturalOrder());
    private static final TicketType<UUID> SHARED_B = TicketType.create("incoming_test_shared_b", Comparator.<UUID>naturalOrder());

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
        h.assertTrue(TicketProbe.count(level, "incoming_area_load", key) == 2, "у двух районов не два тикета загрузки");
        areas.release(level, a);
        h.assertTrue(TicketProbe.count(level, "incoming_area_load", key) == 1, "отпуск одного района снял загрузку другого");
        h.onEachTick(() -> {
            if (!regionRadii(level, "incoming_test_shared_b", key).equals(List.of(2))) return;
            h.assertTrue(regionRadii(level, "incoming_test_shared_a", key).isEmpty(), "отпущенный район снова взят");
            areas.release(level, b);
            h.assertTrue(TicketProbe.count(level, "incoming_area_load", key) + TicketProbe.count(level, "incoming_test_shared_b", key) == 0, "тикеты района остались после отпуска");
            h.succeed();
        });
    }

    private static final TicketType<UUID> RELEASED = TicketType.create("incoming_test_released", Comparator.<UUID>naturalOrder());

    /**
     * Отпуск многих районов разом (Отбой залпа; разом — тик в 5–7 с на сохранении ~9 тыс. чанков):
     * районы отпускаются по очереди, и в конце тика у ванили на выгрузке ({@link AreaLoader#unloading}) не больше её
     * порога (2000 держателей; что сверх, {@code ChunkMap.processUnloads} сохраняет в следующем тике разом), её темпа без
     * свободного времени (200) и одного района. Без очереди там 3645 при пределе 2729. Отпущенный район сразу не
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
        h.assertTrue(areas.releasing() == 0 && TicketProbe.count(level, "incoming_area_load", loading.key()) == 0, "район, который ещё грузился, ждёт очереди");
        int limit = 2000 + 200 + side * side;
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
                for (AreaLoader.Area a : list) if (!regionRadii(level, "incoming_test_released", a.key()).equals(List.of(distance))) return;
                for (AreaLoader.Area a : list) areas.release(level, a);
                areas.hold(level, kept);
                h.assertTrue(regionRadii(level, "incoming_test_released", kept.key()).equals(List.of(distance)), "район, взятый снова, не взят целиком");
                releasedAt[0] = tick[0];
                return;
            }
            String where = "тик " + (tick[0] - releasedAt[0]) + " после отпуска: ";
            int unloading = AreaLoader.unloading(map);
            maxUnloading[0] = Math.max(maxUnloading[0], unloading);
            if (unloading > limit) throw new GameTestAssertException(where + "на выгрузке " + unloading + " держателей, предел " + limit);
            int tickets = TicketProbe.count(level, "incoming_area_release");
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
            Incoming.LOG.info("Отпуск районов по очереди: после первого тика ждали {} из {}, очередь прошла за {} тиков, на выгрузке самое большее {} держателей (предел {})",
                    waiting[0], list.size() - 1, keptReleasedAt[0] - releasedAt[0], maxUnloading[0], limit);
            h.succeed();
        });
    }

    /**
     * Отпуск районов на сервере без свободного времени в тике (у GameTest — {@code /tick sprint}: ваниль тикает с
     * {@code hasTimeLeft = () -> false}). Ваниль тогда держит на выгрузке до 2000 держателей и разбирает только то, что
     * сверх, и очередь, которая ждала бы места на выгрузке, отпускала бы район раз в 100 тиков: после ~60 залпов —
     * 12,5 тыс. тикетов отпуска и ~25 тыс. загруженных чанков. Поэтому очередь идёт темпом ванили (она переводит в
     * выгрузку 200 держателей за тик и без свободного времени): 8 районов без общих держателей уходят за тики на их
     * квадраты в этом темпе и по два тика на район, на выгрузке ({@link AreaLoader#unloading}) в конце тика не больше
     * 2000, этого темпа и одного района (за тик ваниль сохраняет сверх своих 2000 не больше своего темпа и одного района —
     * и в первом тике, когда вернётся свободное время), тикет отпуска — ровно у районов в очереди, отпущенные не тикают.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "tickets_release_busy", skyAccess = true)
    public static void releasedAreasUnloadWithoutFreeTime(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkMap map = level.getChunkSource().chunkMap;
        AreaLoader areas = StrikeWorld.get(level).areas();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        int distance = 2;
        int side = 2 * (distance + ChunkLevel.RADIUS_AROUND_FULL_CHUNK) + 1;
        List<AreaLoader.Area> list = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            AreaLoader.Area a = new AreaLoader.Area(RELEASED, new ChunkPos(base.x + 40 + 30 * i, base.z - 100), distance, UUID.randomUUID());
            areas.hold(level, a);
            list.add(a);
        }
        StrikeGameTests.afterTest(h, () -> {
            level.getServer().tickRateManager().stopSprinting();
            for (AreaLoader.Area a : list) areas.release(level, a);
        });
        // срок — квадраты всех районов в темпе ванили и по два тика на район (очередь, ждущая места на выгрузке, — по
        // 100 тиков на район)
        int limit = list.size() * side * side / 200 + 2 * list.size();
        int bound = 2000 + 200 + side * side;
        int[] tick = {0}, releasedAt = {-1}, maxQueued = {0}, maxUnloading = {0};
        long[] last = {System.nanoTime()};
        h.onEachTick(() -> {
            tick[0]++;
            if (releasedAt[0] < 0) {
                // районы грузятся в фоне в темпе игры, отпуск потом — без пауз и без свободного времени
                long deadline = last[0] + 50_000_000L, left;
                while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
                last[0] = System.nanoTime();
                for (AreaLoader.Area a : list) if (!regionRadii(level, "incoming_test_released", a.key()).equals(List.of(distance))) return;
                level.getServer().tickRateManager().requestGameToSprint(3000);
                for (AreaLoader.Area a : list) areas.release(level, a);
                releasedAt[0] = tick[0];
                return;
            }
            int ticks = tick[0] - releasedAt[0];
            String where = "тик " + ticks + " после отпуска: ";
            int queued = AreaLoader.unloading(map);
            maxQueued[0] = Math.max(maxQueued[0], queued);
            maxUnloading[0] = Math.max(maxUnloading[0], map.toDrop.size() + map.pendingUnloads.size());
            if (queued > bound) throw new GameTestAssertException(where + "на выгрузке " + queued + " держателей, предел " + bound);
            int tickets = TicketProbe.count(level, "incoming_area_release");
            if (tickets != areas.releasing()) throw new GameTestAssertException(where + "тикетов отпуска " + tickets + " у районов в очереди " + areas.releasing());
            for (AreaLoader.Area a : list) {
                if (level.shouldTickBlocksAt(a.centre().toLong()) || level.isPositionEntityTicking(a.centre().getMiddleBlockPosition(64))) {
                    throw new GameTestAssertException(where + "отпущенный район " + list.indexOf(a) + " тикает");
                }
            }
            if (areas.releasing() > 0) {
                if (ticks > limit) throw new GameTestAssertException(where + "в очереди ещё " + areas.releasing() + " из " + list.size() + " районов, срок " + limit);
                return;
            }
            level.getServer().tickRateManager().stopSprinting();
            Incoming.LOG.info("Отпуск районов без свободного времени: очередь из {} прошла за {} тиков (срок {}), на выгрузке самое большее {} (предел {}), с держателями генерации {}",
                    list.size(), ticks, limit, maxQueued[0], bound, maxUnloading[0]);
            // ваниль без свободного времени держала выгрузку полной: иначе проверялся бы не занятый сервер
            h.assertTrue(maxUnloading[0] > 2000, "на выгрузке не больше 2000 — сервер разбирал её во время, свободное в тике");
            h.succeed();
        });
    }

    /**
     * Залп на занятом сервере (без свободного времени в тике, {@code /tick sprint}): 4 ракеты РСЗО по свежим районам
     * через 300 блоков. Когда снаряды отгремели и мод отпустил все районы (цели, свои чанки снарядов, взрывы), очередь
     * отпуска пуста не позже чем через {@link #SALVO_DRAIN} тиков и в мире не остаётся ни одного тикета мода.
     * Очередь, ждущая места на выгрузке, отпускала бы район раз в 100 тиков: здесь через 100 тиков в ней ждали бы
     * 17 районов залпа, а на выгрузке стояли бы 2000 держателей, которых ваниль без свободного времени не разбирает.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "salvo_busy_tickets", skyAccess = true)
    public static void salvoLeavesNoTicketsOnBusyServer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        AreaLoader areas = StrikeWorld.get(level).areas();
        StrikeGameTests.gameSpeed(h);
        StrikeGameTests.afterTest(h, () -> level.getServer().tickRateManager().stopSprinting());
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 10, 0);
        List<UUID> salvo = new ArrayList<>();
        int[] tick = {0}, firedAt = {-1}, goneAt = {-1}, releasedAt = {-1}, queued = {0};
        h.onEachTick(() -> {
            tick[0]++;
            if (firedAt[0] >= 0) queued[0] = Math.max(queued[0], areas.releasing());
            if (firedAt[0] < 0) {
                // очередь прежних партий пуста: дальше всё в ней — от этого залпа
                if (areas.releasing() > 0) return;
                level.getServer().tickRateManager().requestGameToSprint(2400);
                for (int i = 1; i <= 4; i++) {
                    Vec3 aim = new Vec3(start.x + 300 * i, level.getSeaLevel() + 60, start.z);
                    RocketEntity m = ModEntities.ROCKET.get().create(level);
                    m.launchFrom(start.add(0, 0, 3 * i), new Target.Point(aim), aim, null);
                    level.addFreshEntity(m);
                    salvo.add(m.getUUID());
                }
                firedAt[0] = tick[0];
                return;
            }
            if (goneAt[0] < 0) {
                boolean flying = salvo.stream().anyMatch(id -> level.getEntity(id) instanceof StrikeProjectile p && !p.isRemoved()
                        || VirtualFlights.get(level).flights().stream().anyMatch(v -> v.getUUID().equals(id)));
                if (!flying) goneAt[0] = tick[0];
                return;
            }
            // районы взрывов живут, пока не кончатся их взрывы и осыпание
            if (releasedAt[0] < 0) {
                if (areas.size() > 0) return;
                releasedAt[0] = tick[0];
            }
            int ticks = tick[0] - releasedAt[0];
            if (areas.releasing() > 0) {
                if (ticks > SALVO_DRAIN) {
                    throw new GameTestAssertException("через " + ticks + " тиков после отпуска всех районов в очереди ещё " + areas.releasing()
                            + ", тикетов отпуска " + TicketProbe.count(level, "incoming_area_release") + "; toDrop " + level.getChunkSource().chunkMap.toDrop.size()
                            + ", pendingUnloads " + level.getChunkSource().chunkMap.pendingUnloads.size());
                }
                return;
            }
            level.getServer().tickRateManager().stopSprinting();
            List<String> left = new ArrayList<>();
            for (String type : List.of("incoming_area_release", "incoming_area_load", "incoming_projectile", "incoming_flight", "incoming_blast")) {
                int n = TicketProbe.count(level, type);
                if (n > 0) left.add(type + " " + n);
            }
            h.assertTrue(left.isEmpty(), "после залпа остались тикеты: " + left);
            h.assertTrue(queued[0] > 0, "очередь отпуска не понадобилась — проверять нечего");
            Incoming.LOG.info("Залп на занятом сервере: снаряды отгремели на тике {} после пуска, районы отпущены на {}, очередь пуста через {} тиков, в очереди самое большее {}",
                    goneAt[0] - firedAt[0], releasedAt[0] - firedAt[0], ticks, queued[0]);
            h.succeed();
        });
    }

    /** Срок очереди отпуска после залпа на занятом сервере, тиков (ждущая места на выгрузке — район в 100 тиков). */
    private static final int SALVO_DRAIN = 100;

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
        return TicketProbe.count(level, "incoming_projectile", id);
    }
}
