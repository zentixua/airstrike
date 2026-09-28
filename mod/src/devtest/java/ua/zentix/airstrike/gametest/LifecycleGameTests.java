package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.nuclear.world.Terrain;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

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
        long chunk = new ChunkPos(BlockPos.containing(start)).toLong();
        DistanceManager tickets = level.getChunkSource().chunkMap.getDistanceManager();
        h.runAfterDelay(3, () -> {
            h.assertTrue(tickets.shouldForceTicks(ChunkPos.asLong(BlockPos.containing(m.position()))), "снаряд не взял тикет своего чанка: " + state(level, m));
            m.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertFalse(tickets.shouldForceTicks(chunk) || tickets.shouldForceTicks(ChunkPos.asLong(BlockPos.containing(m.position()))),
                    "тикет чанка остался после выгрузки снаряда");
            h.succeed();
        });
    }

    /**
     * Район цели (региональный тикет) держит и барражирующий в мире; выгрузка с чанком отпускает и его.
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
        DistanceManager tickets = level.getChunkSource().chunkMap.getDistanceManager();
        h.runAfterDelay(3, () -> {
            long chunk = ChunkPos.asLong(BlockPos.containing(m.position()));
            m.parkForShutdown(level);
            h.assertTrue(m.isRemoved() && level.getEntity(id) == null, "снаряд остался в мире");
            h.assertTrue(VirtualFlights.get(level).flights().stream().anyMatch(p -> p.getUUID().equals(id)), "снаряд не ушёл в полёт вне мира");
            h.assertFalse(tickets.shouldForceTicks(chunk), "тикет чанка остался");
            VirtualFlights.get(level).flights().removeIf(p -> {
                if (!p.getUUID().equals(id)) return false;
                p.discard();
                return true;
            });
            h.succeed();
        });
    }

    /**
     * Снаряд вне мира, у которого вышел срок жизни, убирается — и не берёт район цели заново в том же тике
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
        // срок жизни — два тика: сохранить и прочитать обратно с другим lifetime
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        m.saveWithoutId(tag);
        tag.putInt("lifetime", m.age() + 2);
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
                if (forcedTickets(level, id) > 0) held[0] = true;
                h.assertTrue(level.isPositionEntityTicking(p.blockPosition()), "снаряд в мире стоит в чанке без тика: " + state(level, p));
            }
            for (StrikeProjectile p : List.copyOf(VirtualFlights.get(level).flights())) {
                if (!p.getUUID().equals(id)) continue;
                // ушёл: снимок того, что осталось за ним, и копия убирается (дальше тесту она не нужна)
                away[0] = "в мире " + (level.getEntity(id) != null) + ", тикетов " + forcedTickets(level, id);
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

    private static String state(ServerLevel level, StrikeProjectile p) {
        return "removed=" + p.getRemovalReason() + " virtual=" + VirtualFlights.get(level).flights().stream().anyMatch(v -> v.getUUID().equals(p.getUUID()))
                + " phase=" + p.flightPhase() + " ticking=" + level.isPositionEntityTicking(p.blockPosition()) + " age=" + p.age();
    }

    /** Региональные тикеты района цели этого снаряда (ключ — его UUID). */
    private static int flightTickets(ServerLevel level, UUID id) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>) f.get(level.getChunkSource().chunkMap.getDistanceManager());
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    if (t.getType().toString().equals("airstrike_flight") && id.equals(ticketKey(t))) n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Тикеты NeoForge на чанки, взятые сущностью {@code id} ({@code ForcedChunkManager.TicketOwner.owner}). */
    private static int forcedTickets(ServerLevel level, UUID id) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>) f.get(level.getChunkSource().chunkMap.getDistanceManager());
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    Object key = ticketKey(t);
                    if (key == null || !key.getClass().getSimpleName().equals("TicketOwner")) continue;
                    Field owner = key.getClass().getDeclaredField("owner");
                    owner.setAccessible(true);
                    if (id.equals(owner.get(key))) n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object ticketKey(Ticket<?> t) throws ReflectiveOperationException {
        Field key = Ticket.class.getDeclaredField("key");
        key.setAccessible(true);
        return key.get(t);
    }
}
