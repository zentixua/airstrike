package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.SplitGuard;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.SpentBoosterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.PickHints;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.warhead.Warheads;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * Проверки без окна ({@code ./gradlew runGameTestServer}): каждое оружие долетает и взрывается, шахед сходит
 * с пусковой, ракета пролетает незагруженный мир, бомба бурит,
 * залп выпускает все снаряды, прицел различает блок и сущность, данные переживают сохранение.
 * Шаблоны (scripts/gen_test_structures.py): «range» — площадка 64×64, дёрн на y = 11 (поверхность y = 12);
 * «runway» — полоса 32×256, дёрн на y = 3 (поверхность y = 4): снаряды заходят с настоящей дистанции.
 * Полётным тестам нужен skyAccess: иначе GameTest накрывает площадку потолком из барьеров, и рельеф — это потолок.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StrikeGameTests {
    /** Цель на полосе: дёрн в 200 блоках от начала (дальше рельеф «видит» стену полигона). */
    private static final BlockPos RUNWAY_TARGET = new BlockPos(16, 3, 200);
    /** Середина площадки «range». */
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private StrikeGameTests() {}

    /** Верх блока цели. */
    private static Vec3 top(GameTestHelper h, BlockPos block) {
        return Vec3.atCenterOf(h.absolutePos(block)).add(0, 0.5, 0);
    }

    /** Взрыв выбил воронку в точке цели (обломки потом могут лечь обратно, поэтому считаем объём). */
    private static void assertCrater(GameTestHelper h, BlockPos at) {
        assertCrater(h, at, "");
    }

    private static void assertCrater(GameTestHelper h, BlockPos at, String flight) {
        int air = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, -2, -3), at.offset(3, 0, 3))) {
            if (h.getBlockState(p).isAir()) air++;
        }
        h.assertTrue(air >= 30, "в точке удара нет воронки: пустых блоков " + air + (flight.isEmpty() ? "" : "; последнее: " + flight));
    }

    @GameTest(template = "runway", timeoutTicks = 400, batch = "drone", skyAccess = true)
    public static void droneFliesAndExplodes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 50, 4)));
        drone.launch(start, new Target.Point(top(h, RUNWAY_TARGET)), top(h, RUNWAY_TARGET), null);
        level.addFreshEntity(drone);
        h.succeedWhen(() -> {
            h.assertTrue(drone.isRemoved(), "шахед ещё летит: " + drone.position());
            h.assertTrue(drone.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED, "шахед пропал: " + drone.getRemovalReason());
            assertCrater(h, RUNWAY_TARGET);
        });
    }

    @GameTest(template = "runway", timeoutTicks = 300, batch = "missile", skyAccess = true)
    public static void missileStrikesAfterPopUp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        // 199 блоков до цели: бреющий полёт, горка и пикирование
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 5, 1)));
        missile.launch(start, new Target.Point(top(h, RUNWAY_TARGET)), top(h, RUNWAY_TARGET), null);
        level.addFreshEntity(missile);
        h.succeedWhen(() -> {
            h.assertTrue(missile.isRemoved(), "ракета ещё летит: " + missile.position());
            h.assertTrue(missile.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED, "ракета пропала: " + missile.getRemovalReason());
            assertCrater(h, RUNWAY_TARGET);
        });
    }

    /**
     * Пуск с пусковой: стоит на направляющей до поджига, разгонный блок, отделение ускорителя (он падает рядом),
     * набор высоты — и удар по цели в конце полосы.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "launcher", skyAccess = true)
    public static void droneLaunchesFromLauncher(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        LauncherEntity launcher = LauncherEntity.create(level, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12))), 0, WeaponType.DRONE, null);
        level.addFreshEntity(launcher);
        Vec3 point = top(h, RUNWAY_TARGET);
        int ready = LauncherEntity.DEPLOY_TICKS + 10;
        Vec3 rail = launcher.railPoint(0);
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        drone.placeOnLauncher(rail, 0, launcher.elevation(), ready, LauncherEntity.DEPLOY_TICKS, new Target.Point(point), point, null);
        drone.setRoute(Route.plan(rail, point, new Vec3(0, 0, 1), 0, 120, 1));
        level.addFreshEntity(drone);
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(drone.getUUID()) instanceof DroneEntity d) last[0] = d.flightPhase() + " " + h.relativeVec(d.position()) + " v=" + d.speed();
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(drone.flightPhase() == FlightPhase.READY && !drone.isActive(), "пакет ещё поднимается — шахеда не видно");
            h.assertTrue(drone.position().distanceTo(rail) < 0.01, "шахед сошёл с направляющей раньше поджига");
        });
        h.runAfterDelay(ready + 4, () -> h.assertTrue(drone.flightPhase() == FlightPhase.IGNITION, "нет поджига: " + drone.flightPhase()));
        h.runAfterDelay(ready + 70, () -> {
            h.assertTrue(drone.isRemoved() || drone.flightPhase().ordinal() >= FlightPhase.CLIMB.ordinal(), "ускоритель не отделился: " + drone.flightPhase());
            h.assertFalse(level.getEntitiesOfClass(SpentBoosterEntity.class, h.getBounds().inflate(64)).isEmpty(), "нет отработавшего ускорителя");
        });
        h.succeedWhen(() -> {
            // уйдя из загруженных чанков, шахед летит вне мира и возвращается новой сущностью с тем же UUID
            h.assertTrue(level.getEntity(drone.getUUID()) == null && VirtualFlights.get(level).flights().isEmpty(), "шахед ещё летит: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * РСЗО: три снаряда из труб одного пакета — очередь, а не разом (даже заказанные, пока пакет поднимается),
     * баллистическая дуга выше полусотни блоков и попадание в цель в конце полосы.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "rocket", skyAccess = true)
    public static void rocketsRippleFromLauncher(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        LauncherEntity launcher = LauncherEntity.create(level, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12))), 0, WeaponType.ROCKET, null);
        level.addFreshEntity(launcher);
        List<RocketEntity> rockets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int[] slot = launcher.reserve(level.getGameTime(), 10, ua.zentix.airstrike.strike.StrikeService.ROCKET_RELOAD);
            RocketEntity r = ModEntities.ROCKET.get().create(level);
            r.placeInTube(launcher.railPoint(slot[0]), launcher.getYRot(), launcher.elevation(), slot[1], LauncherEntity.DEPLOY_TICKS,
                    new Target.Point(point), point, null);
            level.addFreshEntity(r);
            rockets.add(r);
        }
        long[] fired = new long[3];
        double[] apex = {-1e9};
        String[] last = {""};
        h.onEachTick(() -> {
            for (int i = 0; i < 3; i++) {
                RocketEntity r = rockets.get(i);
                if (fired[i] == 0 && r.flightPhase().ordinal() >= FlightPhase.BOOST.ordinal()) fired[i] = level.getGameTime();
                if (!r.isRemoved()) {
                    apex[0] = Math.max(apex[0], r.getY() - point.y);
                    last[0] = "№" + i + " " + r.flightPhase() + " " + h.relativeVec(r.position()) + " v=" + r.speed();
                }
            }
        });
        h.succeedWhen(() -> {
            for (RocketEntity r : rockets) h.assertTrue(r.isRemoved(), "снаряд ещё летит: " + last[0]);
            for (int i = 1; i < 3; i++) {
                h.assertTrue(fired[i] - fired[i - 1] >= LauncherEntity.spacing(WeaponType.ROCKET), "сход не очередью: " + java.util.Arrays.toString(fired));
            }
            h.assertTrue(apex[0] > 40, "не баллистика: вершина дуги " + apex[0] + " над целью");
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /** РСЗО без стреляющего: снаряд с позиции за 600 блоков, полёт вне мира и попадание. */
    @GameTest(template = "runway", timeoutTicks = 800, batch = "rocket_far", skyAccess = true)
    public static void rocketFromAfar(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        r.launchFrom(point.add(0, 0, -600), new Target.Point(point), point, null);
        VirtualFlights.launch(level, r);
        java.util.UUID id = r.getUUID();
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(id) instanceof RocketEntity e) last[0] = "в мире " + e.flightPhase() + " " + h.relativeVec(e.position());
        });
        h.succeedWhen(() -> {
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "снаряд ещё летит: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * Звук снаряда вне мира: снаряд РСЗО летит «виртуально» в 600 блоках от цели — слушатель в 150 блоках от него
     * получает его путь (фаза, где он, скорость — его сдвиг за тик, куда он летит), в 1000 блоках — нет: снаряд
     * вне загруженного мира слышно так же, как в мире, и не дальше, чем его слышно.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "heard", skyAccess = true)
    public static void virtualFlightIsHeardOnlyInRange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        r.launchFrom(point.add(0, 0, -600), new Target.Point(point), point, null);
        VirtualFlights.launch(level, r);
        Vec3[] before = new Vec3[1];
        h.runAfterDelay(9, () -> before[0] = r.position());
        h.runAfterDelay(10, () -> {
            var flights = ua.zentix.airstrike.strike.FlightSounds.flights(level);
            h.assertTrue(flights.stream().anyMatch(f -> f.getUUID().equals(r.getUUID()) && f.isVirtual()), "снаряда нет среди летящих вне мира");
            Vec3 at = r.position();
            var near = ua.zentix.airstrike.strike.FlightSounds.heard(level, flights, at.add(150, 0, 0), null);
            h.assertTrue(near.size() == 1 && near.getFirst().id().equals(r.getUUID()), "в 150 блоках не слышно: " + near);
            var f = near.getFirst();
            h.assertTrue(f.pos().distanceTo(at) < 1.0e-6 && f.weapon() == WeaponType.ROCKET.id() && !f.bomber(), "не тот путь: " + f);
            h.assertTrue(f.aim().distanceTo(r.aimPoint()) < 1.0e-6, "цель: " + f.aim());
            Vec3 step = at.subtract(before[0]);
            h.assertTrue(step.length() > 1 && f.velocity().distanceTo(step) < 1.0e-6, "скорость " + f.velocity() + ", а сдвиг за тик " + step);
            h.assertTrue(ua.zentix.airstrike.strike.FlightSounds.heard(level, flights, at.add(1000, 0, 0), null).isEmpty(), "слышно за 1000 блоков");
            // не долетать: в партии теста больше никого, а снаряд, упавший после конца теста, упал бы на чужую площадку
            VirtualFlights.get(level).clear(level, p -> true);
            h.succeed();
        });
    }

    /**
     * Крылатая ракета на атаке, у которой цель оказалась сбоку внутри круга разворота (на 12 блоках/тик и 3°/тик —
     * радиус ~240 блоков): уходит прямо, пока цель не выйдет из круга, и заходит снова. Раньше она кружила вокруг
     * цели, пока не выходил срок жизни (стенд нагрузки на ноутбуке: ракета убрана в 270 блоках от цели, на атаке).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "missile_reattack", skyAccess = true)
    public static void missileReattacksTargetInsideTurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 60, 0);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(target.add(0, 0, -150), new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        // курс на восток, цель в 150 блоках к югу, фаза — уже атака, срока жизни — на заход с запасом
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        m.saveWithoutId(tag);
        tag.getCompound("flight").putFloat("yaw", -90);
        tag.getCompound("flight").putFloat("pitch", 0);
        tag.putString("flight_phase", FlightPhase.TERMINAL.getSerializedName());
        tag.putInt("lifetime", m.age() + 300);
        m.load(tag);
        VirtualFlights.launch(level, m);
        java.util.UUID id = m.getUUID();
        Vec3[] lastPos = {m.position()};
        int[] lastAge = {0};
        boolean[] reattacked = {false};
        String[] last = {""};
        h.onEachTick(() -> {
            StrikeProjectile p = level.getEntity(id) instanceof StrikeProjectile e ? e : VirtualFlights.get(level).flights().stream()
                    .filter(f -> f.getUUID().equals(id)).findFirst().orElse(null);
            if (p == null) return;
            lastPos[0] = p.position();
            lastAge[0] = p.age();
            if (p.flightPhase() == FlightPhase.CRUISE) reattacked[0] = true;
            last[0] = p.flightPhase() + " " + h.relativeVec(p.position()) + " возраст " + p.age();
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getEntity(id) == null && VirtualFlights.get(level).flights().stream().noneMatch(f -> f.getUUID().equals(id)),
                    "ракета ещё летит: " + last[0]);
            h.assertTrue(reattacked[0], "атака не отменялась: " + last[0]);
            h.assertTrue(lastPos[0].distanceTo(target) < 20, "ракета убрана не у цели (кружила до конца срока жизни?): " + last[0]);
        });
    }

    /** Барражирующий по UUID: в мире или вне его (уходя из загруженных чанков, он становится новой сущностью). */
    private static LoiterEntity findLoiter(ServerLevel level, java.util.UUID id) {
        if (level.getEntity(id) instanceof LoiterEntity e) return e;
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
            if (p.getUUID().equals(id) && p instanceof LoiterEntity e) return e;
        }
        return null;
    }

    /**
     * Цель барражирующего — в воздухе на 60 блоков над полосой: круг (ещё на 45 выше) и пике идут над барьерной
     * стеной полигона, а круг радиусом 40 шире полосы.
     */
    private static Vec3 airTarget(GameTestHelper h) {
        return top(h, RUNWAY_TARGET).add(0, 60, 0);
    }

    /**
     * Барражирующий: подлёт, круги над целью всё время барража (из настроек, ±20%) на своём радиусе, потом пике
     * и подрыв у цели.
     */
    @GameTest(template = "runway", timeoutTicks = 1400, batch = "loiter", skyAccess = true)
    public static void loiterCirclesThenDives(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = airTarget(h);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.launch(point.add(0, LoiterEntity.LOITER_HEIGHT, -150), new Target.Point(point), point, null);
        e.setRoute(null);
        level.addFreshEntity(e);
        java.util.UUID id = e.getUUID();
        int[] loiter = {0};
        boolean[] dived = {false};
        // сильнее всего отклонение от своего круга (боеприпас на круг выходит по касательной — с первого тика)
        double[] drift = {0, 0};
        Vec3[] lastPos = {null};
        String[] last = {""};
        h.onEachTick(() -> {
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            lastPos[0] = l.position();
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " v=" + l.speed();
            if (l.flightPhase() == FlightPhase.LOITER) {
                loiter[0]++;
                double off = Math.hypot(l.getX() - point.x, l.getZ() - point.z) - l.orbitRadius();
                if (Math.abs(off) > Math.abs(drift[0])) {
                    drift[0] = off;
                    drift[1] = l.orbitRadius();
                }
            }
            if (l.flightPhase() == FlightPhase.TERMINAL) dived[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(findLoiter(level, id) == null, "барражирующий ещё летит: " + last[0]);
            int min = (int) (ua.zentix.airstrike.AirstrikeConfig.SERVER.loiterTime.get() * 20 * 0.8);
            h.assertTrue(loiter[0] >= min, "кружил " + loiter[0] + " тиков, а должен не меньше " + min);
            h.assertTrue(Math.abs(drift[0]) <= ua.zentix.airstrike.guidance.Orbit.TOLERANCE, "ушёл с круга радиусом " + drift[1] + " на " + drift[0]);
            h.assertTrue(dived[0], "не пикировал: " + last[0]);
            h.assertTrue(lastPos[0].distanceTo(point) < 8, "подрыв не у цели: " + last[0]);
        });
    }

    /** Цель из камеры во время барража: пике сразу, не дожидаясь конца круга, и подрыв у новой цели. */
    @GameTest(template = "runway", timeoutTicks = 700, batch = "loiter", skyAccess = true)
    public static void loiterStrikesOnRetarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = airTarget(h);
        Vec3 other = point.add(8, 0, -12);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.launch(point.add(0, LoiterEntity.LOITER_HEIGHT, -150), new Target.Point(point), point, null);
        e.setRoute(null);
        level.addFreshEntity(e);
        java.util.UUID id = e.getUUID();
        int[] loiter = {0};
        long[] retargeted = {0}, dived = {0};
        Vec3[] lastPos = {null};
        String[] last = {""};
        h.onEachTick(() -> {
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            lastPos[0] = l.position();
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " v=" + l.speed();
            if (l.flightPhase() == FlightPhase.LOITER && ++loiter[0] == 60) {
                h.assertTrue(l.retarget(new Target.Point(other), other), "не принял цель");
                retargeted[0] = level.getGameTime();
            }
            if (l.flightPhase() == FlightPhase.TERMINAL && dived[0] == 0) dived[0] = level.getGameTime();
        });
        h.succeedWhen(() -> {
            h.assertTrue(retargeted[0] > 0 && findLoiter(level, id) == null, "барражирующий ещё летит: " + last[0]);
            h.assertTrue(dived[0] - retargeted[0] <= 120, "пике не сразу: через " + (dived[0] - retargeted[0]) + " тиков");
            h.assertTrue(lastPos[0].distanceTo(other) < 8, "подрыв не у новой цели: " + last[0]);
        });
    }

    /**
     * Цель ходит (моб, игрок в полёте кружит): барражирующий кружит за ней и в пике попадает с упреждением
     * по её скорости. Раньше пике шло туда, где цель сейчас, мимо неё, и снаряд петлял вверх-вниз до конца срока.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "loiter_moving", skyAccess = true)
    public static void loiterHitsMovingTarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // высоко над барьерной стеной вокруг площадки (высота шаблона 64): круг цели доходит до края полосы (x = 32),
        // и пике у края на высоте стены, как и выход из пике после промаха (до 45 блоков ниже цели) и повторный заход,
        // били в барьер (CI 29.09.2026: снаряд пропал в 12 блоках от цели)
        Vec3 center = airTarget(h).add(0, 100, 0);
        ArmorStand stand = new ArmorStand(level, center.x + 16, center.y, center.z);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.launch(center.add(0, LoiterEntity.LOITER_HEIGHT, -150), new Target.OfEntity(stand.getUUID(), Vec3.ZERO), stand.position(), null);
        e.setRoute(null);
        level.addFreshEntity(e);
        java.util.UUID id = e.getUUID();
        int[] loiter = {0}, tick = {0};
        Vec3[] lastPos = {null};
        String[] last = {""};
        h.onEachTick(() -> {
            // кружит радиусом 16 блоков со скоростью 0.5 блока/тик, как игрок в полёте на стенде нагрузки
            double a = ++tick[0] * 0.5 / 16;
            stand.teleportTo(center.x + 16 * Math.cos(a), center.y, center.z + 16 * Math.sin(a));
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            lastPos[0] = l.position();
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " до цели " + String.format(java.util.Locale.ROOT, "%.1f", l.position().distanceTo(stand.position()));
            if (l.flightPhase() == FlightPhase.LOITER && ++loiter[0] == 60) {
                h.assertTrue(l.retarget(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), stand.position()), "не принял цель");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(loiter[0] >= 60, "не вышел на круг над идущей целью: " + last[0]);
            h.assertTrue(findLoiter(level, id) == null, "барражирующий ещё летит: " + last[0]);
            h.assertTrue(lastPos[0].distanceTo(stand.position()) < 10, "подрыв не у цели: " + last[0]);
            stand.discard();
        });
    }

    /**
     * Залп по цели на краю обрыва: точка снаряда с разбросом — на земле под ней, а не в воздухе на высоте цели
     * (стенд нагрузки: «Ланцет» пикировал на точку в 64 блоках над землёй и петлял до конца срока).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "spread_ground", skyAccess = true)
    public static void salvoSpreadAroundEntityLandsOnGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = RANGE_CENTER.below();
        for (int y = 1; y <= 12; y++) h.setBlock(base.above(y), Blocks.STONE);
        Vec3 top = Vec3.atBottomCenterOf(h.absolutePos(base.above(13)));
        ArmorStand stand = new ArmorStand(level, top.x, top.y, top.z);
        level.addFreshEntity(stand);
        Target spread = new Target.OfEntity(stand.getUUID(), new Vec3(0, 1, 0)).offset(new Vec3(12, 0, 0));
        h.runAfterDelay(10, () -> {
            h.assertTrue(stand.onGround(), "стойка не стоит на столбе");
            Vec3 p = spread.resolve(level).orElseThrow();
            double ground = Vec3.atBottomCenterOf(h.absolutePos(base.above())).y;
            h.assertTrue(Math.abs(p.y - ground) < 1.5 && Math.abs(p.x - (stand.getX() + 12)) < 1e-6,
                    "точка залпа не на земле: " + h.relativeVec(p) + ", земля на " + (ground - h.absolutePos(BlockPos.ZERO).getY()));
            stand.discard();
            h.succeed();
        });
    }

    /**
     * Место с карты: высота с клиента — только оценка (здесь на 40 блоков под землёй), шахед всё равно бьёт в поверхность
     * этого места, как только знает её.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "map_target", skyAccess = true)
    public static void mapTargetHitsSurface(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 50, 4)));
        Vec3 guess = top(h, RUNWAY_TARGET).subtract(0, 40, 0);
        Target.Ground ground = new Target.Ground(guess);
        drone.launch(start, ground, ground.surface(level), null);
        level.addFreshEntity(drone);
        h.succeedWhen(() -> {
            h.assertTrue(drone.isRemoved(), "шахед ещё летит: " + h.relativeVec(drone.position()));
            assertCrater(h, RUNWAY_TARGET);
        });
    }

    /** Место с карты берёт высоту земли в своей колонке: и в центре, и у снаряда залпа со сдвигом (на столбе). */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void groundTargetTakesSurface(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pillar = RANGE_CENTER.offset(12, 0, 0);
        for (int y = 1; y <= 6; y++) h.setBlock(pillar.above(y), Blocks.STONE);
        Target.Ground center = new Target.Ground(Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 50, 0));
        double surface = h.absolutePos(RANGE_CENTER).getY() + 0.5;
        Vec3 c = center.resolve(level).orElseThrow();
        h.assertTrue(Math.abs(c.y - surface) < 1e-6, "центр не на земле: " + h.relativeVec(c));
        Vec3 shot = center.offset(new Vec3(12, 7, 0)).resolve(level).orElseThrow();
        h.assertTrue(Math.abs(shot.y - (surface + 6)) < 1e-6 && Math.abs(shot.x - (c.x + 12)) < 1e-6,
                "сдвиг залпа не на столбе: " + h.relativeVec(shot));
        h.succeed();
    }

    /**
     * Место с карты: высоту знает только сервер. У готового чанка — верх земли, у незагруженного — рельеф генератора,
     * а не дно мира, и чанк при этом не грузится.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void groundAtFindsHeightWithoutLoading(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 here = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER));
        Vec3 near = Target.Ground.at(level, here.x, here.z).pos();
        h.assertTrue(Math.abs(near.y - (h.absolutePos(RANGE_CENTER).getY() + 0.5)) < 1e-6, "у готового чанка не верх земли: " + h.relativeVec(near));
        Vec3 far = here.add(4096, 0, 0);
        BlockPos column = BlockPos.containing(far);
        h.assertFalse(Terrain.ready(level, column), "район вдали уже загружен");
        Vec3 estimate = Target.Ground.at(level, far.x, far.z).pos();
        int bottom = level.getMinBuildHeight();
        h.assertTrue(estimate.y > bottom + 1 && estimate.y < level.getMaxBuildHeight(), "вдали не рельеф генератора: y " + estimate.y);
        h.assertTrue(estimate.x == far.x && estimate.z == far.z, "место сдвинулось");
        h.assertFalse(Terrain.ready(level, column), "оценка загрузила чанк");
        h.succeed();
    }

    /**
     * Полёт вне загруженных чанков: ракета стартует в 1.5 км от цели, где мира нет, летит «виртуально» и возвращается
     * в мир у цели (чанки цели загружает её же тикет), не трогая по пути незагруженное.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual", skyAccess = true)
    public static void missileFliesThroughUnloadedWorld(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        // выше барьерной стены вокруг площадки теста (высота шаблона 64): иначе ракета бьётся в неё на входе
        missile.launch(point.add(0, 80, -1500), new Target.Point(point), point, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        java.util.UUID id = missile.getUUID();
        boolean[] seen = new boolean[1];
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(id) instanceof CruiseMissileEntity m && !m.isVirtual()) {
                seen[0] = true;
                last[0] = "в мире " + m.flightPhase() + " " + h.relativeVec(m.position()) + " v=" + m.speed();
            }
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (p.getUUID().equals(id)) last[0] = "вне мира " + p.flightPhase() + " " + h.relativeVec(p.position()) + " v=" + p.speed();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty(), "ракета ещё вне мира");
            h.assertTrue(seen[0], "ракета не вернулась в мир");
            h.assertTrue(level.getEntity(id) == null, "ракета ещё летит");
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * «/tick freeze»: ракета вне мира стоит, как стоят сущности в мире, и летит дальше после разморозки (иначе снаряды
     * вне мира уходили вперёд замороженного мира — трейлер замораживает мир, пока камера ждёт прогрузки).
     * Замороженный мир останавливает и сам тест (его часы — время мира), поэтому размораживает сервер через
     * секунду: за неё сервер делает ~20 тиков с замороженным миром.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "frozen", skyAccess = true)
    public static void virtualFlightStopsWhileFrozen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(point.add(0, 80, -1500), new Target.Point(point), point, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        java.util.UUID id = missile.getUUID();
        java.util.function.Supplier<Vec3> where = () -> VirtualFlights.get(level).flights().stream()
                .filter(p -> p.getUUID().equals(id)).map(StrikeProjectile::position).findFirst().orElse(null);
        var server = level.getServer();
        int[] tick = {0};
        boolean[] held = {false}, thawed = {false};
        h.onEachTick(() -> {
            if (++tick[0] != 20) return;
            Vec3 frozenAt = where.get();
            level.tickRateManager().setFrozen(true);
            java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS).execute(() -> server.execute(() -> {
                Vec3 now = where.get();
                held[0] = frozenAt != null && now != null && now.distanceTo(frozenAt) < 1e-6;
                level.tickRateManager().setFrozen(false);
                thawed[0] = true;
            }));
        });
        h.succeedWhen(() -> {
            h.assertTrue(thawed[0], "ещё заморожено");
            h.assertTrue(held[0], "ракета вне мира двигалась, пока мир заморожен");
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "ракета ещё летит");
            assertCrater(h, RUNWAY_TARGET, "после разморозки");
        });
    }

    /**
     * Движущаяся цель вне загруженного мира: ракета берёт район цели, пока та в начале полосы, а цель уходит на 160
     * блоков — район должен уйти за ней, иначе ракета ждёт у цели загрузки и пропадает по сроку жизни.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_moving", skyAccess = true)
    public static void missileFollowsMovingTargetArea(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 end = top(h, RUNWAY_TARGET);
        Vec3 start = end.add(0, 0, -160);
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        stand.setNoGravity(true);
        stand.moveTo(start.x, start.y, start.z);
        level.addFreshEntity(stand);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(end.add(0, 80, -1500), Target.OfEntity.center(stand), start, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        java.util.UUID id = missile.getUUID();
        boolean[] moved = new boolean[1];
        String[] last = {""};
        h.onEachTick(() -> {
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (!p.getUUID().equals(id)) continue;
                last[0] = "вне мира " + p.flightPhase() + " " + h.relativeVec(p.position());
                // ракета уже взяла район у начала полосы — цель уходит к концу
                if (!moved[0] && p.position().distanceTo(start) < 700) {
                    moved[0] = true;
                    stand.teleportTo(end.x, end.y, end.z);
                }
            }
            if (level.getEntity(id) instanceof CruiseMissileEntity m && !m.isVirtual()) {
                last[0] = "в мире " + m.flightPhase() + " " + h.relativeVec(m.position());
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(moved[0], "цель не сдвинулась");
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "ракета ещё летит: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * Взрыв у неготовых чанков не читает их в тике (сервер вставал на секунду с лишним): ждёт, пока район станет
     * готовым, и срабатывает, когда всё в его досягаемости готово. Фоновая генерация за 4 км идёт сколько угодно
     * против тиков сервера GameTest (он тикает без пауз), поэтому район здесь догружает сам тест — синхронно, после
     * тика, в котором взрыв уже ждал: срок теста зависит только от тиков, не от скорости машины.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "deferred_blast")
    public static void blastWaitsForUnreadyChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 far = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(4096, 0, 0);
        double reach = Warheads.reach(4);
        h.assertFalse(Terrain.readyAround(level, far, reach), "район вдали уже загружен");
        boolean[] ran = new boolean[1];
        boolean[] readyWhenRan = new boolean[1];
        Warheads.whenReady(level, far, reach, l -> {
            ran[0] = true;
            readyWhenRan[0] = Terrain.readyAround(l, far, reach);
        });
        h.assertFalse(ran[0], "взрыв сработал сразу, не дождавшись района");
        h.runAfterDelay(2, () -> {
            // взрыв уже тикал в очереди: если район к этому времени сам не догрузился, взрыва быть не должно
            h.assertTrue(!ran[0] || readyWhenRan[0], "взрыв сработал до готовности района");
            for (int cx = Mth.floor(far.x - reach) >> 4; cx <= Mth.floor(far.x + reach) >> 4; cx++) {
                for (int cz = Mth.floor(far.z - reach) >> 4; cz <= Mth.floor(far.z + reach) >> 4; cz++) level.getChunk(cx, cz);
            }
            h.assertTrue(Terrain.readyAround(level, far, reach), "район не стал готовым после загрузки");
        });
        h.succeedWhen(() -> {
            h.assertTrue(ran[0], "взрыв всё ещё ждёт района");
            h.assertTrue(readyWhenRan[0], "взрыв сработал до готовности района");
        });
    }

    @GameTest(template = "runway", timeoutTicks = 300, batch = "bunker", skyAccess = true)
    public static void bunkerBusterDrillsAndDetonatesUnderground(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        // как со штатного B-2: сброс на 170 блоков выше цели за 85 блоков до неё
        Vec3 surface = top(h, RUNWAY_TARGET);
        bomb.drop(surface.add(0, 170, -85), 0, surface, null, null);
        level.addFreshEntity(bomb);
        h.succeedWhen(() -> {
            h.assertTrue(bomb.isRemoved(), "бомба ещё не взорвалась");
            h.assertFalse(h.getBlockState(RUNWAY_TARGET).is(Blocks.GRASS_BLOCK), "нет входного отверстия");
            h.assertFalse(h.getBlockState(RUNWAY_TARGET.below()).is(Blocks.DIRT), "бомба не пробила грунт");
        });
    }

    /**
     * B-2, у которого точка сброса оказалась внутри круга разворота (перенацелили сбоку; стенд VPS 29.09.2026: стенд
     * перенацелил бомбардировщик в 200 блоках от него), уходит прямо, заходит снова и сбрасывает бомбу. Раньше он
     * на пределе поворота кружил вокруг точки в 300–480 блоках от неё до «Отбоя».
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_reattack", skyAccess = true)
    public static void bomberReattacksAimInsideItsTurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = top(h, RUNWAY_TARGET);
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        // курс на +z, далеко вперёд; сразу перенацеливание на 150 блоков вбок и 60 вперёд — внутрь круга разворота
        bomber.launch(start, start.add(0, 0, 3000), null, null);
        Vec3 aside = start.add(150, 0, 60);
        h.assertTrue(bomber.retarget(new Target.Point(aside), aside), "бомбардировщик не принял перенацеливание");
        level.addFreshEntity(bomber);
        UUID id = bomber.getUUID();
        BomberEntity[] last = {bomber};
        h.onEachTick(() -> {
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            if (p instanceof BomberEntity b) last[0] = b;
        });
        h.succeedWhen(() -> h.assertTrue(last[0].hasReleased(), "бомба не сброшена, B-2 в " + (int) last[0].position().subtract(aside).horizontalDistance()
                + " блоках от точки сброса"));
    }

    /**
     * B-2, перенацеленный на точку за спиной ближе дальности сброса, не бросает бомбу назад: сброшенная так бомба
     * падала круто вниз по курсу (не выравнивается и не рулит, когда цель позади) и уходила в землю в ~150 блоках
     * от точки. Он уходит, заходит снова, и бомба попадает.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_behind", skyAccess = true)
    public static void bomberRetargetedJustBehindHitsOnReattack(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = top(h, RUNWAY_TARGET);
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        // курс на +z; точка сброса — в 60 блоках позади, ближе RELEASE_DISTANCE
        bomber.launch(start, start.add(0, 0, 3000), null, null);
        Vec3 behind = start.add(0, 0, -60);
        h.assertTrue(bomber.retarget(new Target.Point(behind), behind), "бомбардировщик не принял перенацеливание");
        level.addFreshEntity(bomber);
        Vec3[] entry = {null};
        h.onEachTick(() -> {
            if (entry[0] != null) return;
            List<BunkerBusterEntity> bombs = level.getEntitiesOfClass(BunkerBusterEntity.class, new AABB(behind, behind).inflate(400, 400, 400));
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) if (p instanceof BunkerBusterEntity b) bombs = java.util.stream.Stream
                    .concat(bombs.stream(), java.util.stream.Stream.of(b)).toList();
            for (BunkerBusterEntity b : bombs) if (b.isDrilling()) entry[0] = b.entry();
        });
        h.succeedWhen(() -> {
            h.assertTrue(entry[0] != null, "бомба ещё не вошла в грунт");
            double miss = entry[0].subtract(behind).horizontalDistance();
            h.assertTrue(miss < 16, "бомба вошла в грунт в " + (int) miss + " блоках от точки сброса");
        });
    }

    /**
     * Бомба, сброшенная под точку на своей высоте (цель в воздухе: игрок в полёте, а чанк под ним у пуска не был
     * готов), не выравнивается и не кружит, а падает на землю задолго до конца срока жизни. Раньше она тянулась
     * к точке, проходила под ней и уходила на круг радиусом ~240 блоков до конца срока (стенд VPS 29.09.2026).
     */
    @GameTest(template = "runway", timeoutTicks = 200, batch = "bunker_air", skyAccess = true)
    public static void bunkerBusterFallsUnderAirborneAim(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        Vec3 aim = top(h, RUNWAY_TARGET).add(0, 64, 0);
        bomb.drop(aim.add(0, -4, -85), 0, aim, null, null);
        level.addFreshEntity(bomb);
        double[] lastY = {bomb.getY()};
        boolean[] drilled = {false};
        h.onEachTick(() -> {
            if (bomb.isRemoved()) return;
            h.assertTrue(bomb.getY() <= lastY[0] + 1e-6, "бомба набирает высоту: " + lastY[0] + " → " + bomb.getY());
            lastY[0] = bomb.getY();
            if (bomb.isDrilling()) drilled[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(drilled[0], "бомба не вошла в грунт");
            h.assertTrue(bomb.isRemoved(), "бомба ещё не взорвалась");
            Vec3 entry = bomb.entry();
            h.assertTrue(Math.abs(entry.x - aim.x) < 16 && entry.z > aim.z - 85 && entry.z < aim.z + 120,
                    "бомба упала далеко от цели: " + entry);
        });
    }

    /**
     * Снаряд вне мира, который прошёл мимо цели, не уходит под землю: вне мира нет столкновений, и такой снаряд падал
     * без взрыва до конца срока жизни или до низа мира (#108: бомба на точку позади B-2 — под миром на y=−3022).
     * Теперь путь вне мира кончается на поверхности (карта высот готового чанка, у неготового — уровень моря): снаряд
     * ждёт загрузки этого места, возвращается в мир на поверхности и взрывается обычным попаданием. Бомба на точку
     * позади себя не рулит и падает круто вниз по курсу — должна войти в грунт там, где её путь встречает рельеф.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "virtual_ground", skyAccess = true)
    public static void virtualMissNeverFallsBelowGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // место падения заранее не известно, оно грузится в фоне, пока бомба ждёт: время ожидания — игровое
        gameSpeed(h);
        // далеко за площадкой: вокруг ничего не загружено, бомба сразу летит вне мира
        BlockPos origin = h.absolutePos(RUNWAY_TARGET).offset(-4000, 0, 4000);
        Vec3 from = new Vec3(origin.getX() + 0.5, level.getSeaLevel() + 200, origin.getZ() + 0.5);
        Vec3 behind = from.add(0, -150, -300);
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        bomb.drop(from, 0, behind, null, null);
        VirtualFlights.launch(level, bomb);
        UUID id = bomb.getUUID();
        Vec3[] entry = {null};
        double[] lowest = {from.y};
        int[] ground = {0};
        h.onEachTick(() -> {
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e ? e : null);
            if (p == null) return;
            lowest[0] = Math.min(lowest[0], p.getY());
            if (entry[0] == null && p instanceof BunkerBusterEntity b && b.isDrilling()) {
                entry[0] = b.entry();
                // рельеф рядом со скважиной: в самой скважине бомба уже выбрала грунт
                ground[0] = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(entry[0].x) + 24, Mth.floor(entry[0].z));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(lowest[0] > level.getMinBuildHeight(), "бомба ушла под мир: y=" + (int) lowest[0]);
            h.assertTrue(entry[0] != null, "бомба не вошла в грунт, ниже всего y=" + (int) lowest[0]);
            // вход на 1 блок выше точки попадания (дым из скважины)
            h.assertTrue(Math.abs(entry[0].y - 1 - ground[0]) < 2, "бомба вошла в грунт не на поверхности: y=" + (int) entry[0].y + ", рельеф " + ground[0]);
            // падает вперёд по курсу (+z), не рулит: вбок не уходит, вперёд — не дальше двух высот падения
            h.assertTrue(entry[0].z > from.z && entry[0].z - from.z < 2 * (from.y - ground[0]) && Math.abs(entry[0].x - from.x) < 8,
                    "бомба вошла в грунт не на своём пути: " + entry[0].subtract(from));
        });
    }

    /**
     * Бомба вне мира падает над неготовым чанком, где пол полёта — уровень моря, а чанк посреди падения становится
     * готовым, и настоящая поверхность выше бомбы. Бомба не рулит вверх: ниже поверхности она уже в земле и попадает
     * в поверхность над собой. Раньше пол «встал» выше неё, пересечения сверху вниз не было, и она падала под мир.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "virtual_risen_ground", skyAccess = true)
    public static void virtualBombUnderRisenSurfaceHitsIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // место падения грузится в фоне, пока бомба ждёт: время ожидания — игровое
        gameSpeed(h);
        BlockPos origin = h.absolutePos(RUNWAY_TARGET).offset(4000, 0, -4000);
        Vec3 from = new Vec3(origin.getX() + 0.5, level.getSeaLevel() + 200, origin.getZ() + 0.5);
        Vec3 behind = from.add(0, -150, -300);
        int sea = level.getChunkSource().getGenerator().getSeaLevel();
        // «рельеф», которого вне мира не видно: плита на 80 блоков выше моря генератора
        int roof = sea + 80;
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        bomb.drop(from, 0, behind, null, null);
        VirtualFlights.launch(level, bomb);
        UUID id = bomb.getUUID();
        Vec3[] entry = {null};
        double[] lowest = {from.y};
        boolean[] risen = {false};
        double[] risenZ = {0};
        List<ChunkPos> held = new ArrayList<>();
        afterTest(h, () -> held.forEach(c -> level.getChunkSource().removeRegionTicket(READY_ONLY, c, 0, c)));
        h.onEachTick(() -> {
            StrikeProjectile p = flight(level, id);
            if (p == null) return;
            lowest[0] = Math.min(lowest[0], p.getY());
            // бомба вне мира ниже будущей плиты, но выше моря — её чанки готовы, а плита уже над ней
            if (!risen[0] && p.isVirtual() && p.getY() < roof - 20 && p.getY() > sea + 5) {
                risen[0] = true;
                risenZ[0] = p.getZ();
                BlockPos at = p.blockPosition();
                // плита короткая: за ней бомба уходит ниже низа мира лишь через ~35 блоков по курсу — туда плита не
                // достаёт, и в неё попадает только бомба, которая упала в поверхность там, где плита встала над ней
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -8; dz <= 16; dz++) {
                        BlockPos b = new BlockPos(at.getX() + dx, roof, at.getZ() + dz);
                        ChunkPos c = new ChunkPos(b);
                        if (!held.contains(c)) {
                            // уровень 33: чанк готов (FULL), но не тикает — бомба над ним остаётся вне мира
                            level.getChunkSource().addRegionTicket(READY_ONLY, c, 0, c);
                            level.getChunk(c.x, c.z);
                            held.add(c);
                        }
                        level.setBlock(b, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            if (entry[0] == null && p instanceof BunkerBusterEntity b && b.isDrilling()) entry[0] = b.entry();
        });
        h.succeedWhen(() -> {
            h.assertTrue(risen[0], "плита не встала над бомбой");
            h.assertTrue(lowest[0] > level.getMinBuildHeight(), "бомба ушла под мир: y=" + (int) lowest[0]);
            h.assertTrue(entry[0] != null, "бомба не вошла в грунт, ниже всего y=" + (int) lowest[0]);
            // вход на 1 блок выше точки попадания (дым из скважины); попадание — верх плиты
            h.assertTrue(Math.abs(entry[0].y - 1 - (roof + 1)) < 2, "бомба вошла не в плиту над собой: y=" + (int) entry[0].y + ", плита " + roof);
            h.assertTrue(Math.abs(entry[0].z - risenZ[0]) < 4, "бомба вошла в плиту не там, где плита встала над ней: " + (entry[0].z - risenZ[0]));
        });
    }

    /**
     * Цель ниже рельефа (пещера, карьер, овраг; в обычном мире — всё, что ниже уровня моря 63, у неготовых чанков это
     * и есть поверхность вне мира): вне мира снаряд летит на высоте цели — пуск издалека над ней, РСЗО с её высоты — и
     * под поверхностью, пока не ниже цели, летит дальше. Здесь над местом пуска — «рельеф» из готового, но не тикающего
     * чанка на 60 блоков выше цели; раньше первый же шаг вне мира кончался на нём, и взрыв был в 1500 блоках от цели.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_deep_missile", skyAccess = true)
    public static void virtualMissileUnderTerrainReachesDeepTarget(GameTestHelper h) {
        Vec3 aim = top(h, RUNWAY_TARGET);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(h.getLevel());
        // как пуск издалека (StrikeService.fromAfar): над целью на 12 блоков
        Vec3 start = aim.add(0, 12, -1500);
        missile.launch(start, new Target.Point(aim), aim, null);
        missile.setRoute(Route.direct());
        reachesUnderTerrain(h, missile, start, aim);
    }

    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_deep_rocket", skyAccess = true)
    public static void virtualRocketUnderTerrainReachesDeepTarget(GameTestHelper h) {
        Vec3 aim = top(h, RUNWAY_TARGET);
        RocketEntity rocket = ModEntities.ROCKET.get().create(h.getLevel());
        // как пуск РСЗО издалека (StrikeService): с высоты цели, в 600 блоках
        Vec3 start = aim.add(0, 0, -600);
        rocket.launchFrom(start, new Target.Point(aim), aim, null);
        reachesUnderTerrain(h, rocket, start, aim);
    }

    private static final TicketType<ChunkPos> READY_ONLY = TicketType.create("airstrike_test_ready", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Цель, за которой снаряд держит высоту, поднялась на 40 блоков (игрок вышел из оврага, телепорт): пол полёта вне
     * мира ({@code BELOW_AIM} под целью) встал выше снаряда. Раньше снаряд на бреющем падал там, где его это застало, —
     * в 1500 блоках от цели; теперь набирает высоту за целью и бьёт по ней.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_rising_aim", skyAccess = true)
    public static void virtualMissileClimbsAfterRisingAim(GameTestHelper h) {
        // ракета спустилась под «рельеф» на свою высоту над целью (+12)
        climbsAfterRisingAim(h, (y, floor, dy) -> y < floor - 11);
    }

    /**
     * То же, но цель поднимается, пока ракета ещё снижается к её прежней высоте, — в тот тик, после которого шаг ракеты
     * прошёл бы через новый пол: пол встал выше неё сам, это не пересечение сверху вниз.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_rising_aim_descent", skyAccess = true)
    public static void virtualMissileClimbsAfterAimRisesMidDescent(GameTestHelper h) {
        climbsAfterRisingAim(h, (y, floor, dy) -> dy > 0 && y >= floor && y - dy < floor);
    }

    @FunctionalInterface
    private interface RiseWhen {
        /** Поднять цель сейчас: высота ракеты, пол после подъёма цели, снижение ракеты за прошлый тик. */
        boolean test(double y, double floorAfter, double descent);
    }

    private static void climbsAfterRisingAim(GameTestHelper h, RiseWhen when) {
        ServerLevel level = h.getLevel();
        Vec3 low = top(h, RUNWAY_TARGET);
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        stand.setNoGravity(true);
        stand.moveTo(low.x, low.y, low.z);
        level.addFreshEntity(stand);
        Vec3 start = low.add(0, 12, -1500);
        int roofLength = 1000;
        roofOverStart(h, start, Mth.floor(low.y) + 60, roofLength);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(start, Target.OfEntity.center(stand), low, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        UUID id = missile.getUUID();
        Vec3 high = low.add(0, 40, 0);
        // пол полёта вне мира после подъёма: центр цели на 16 блоков ниже (рельеф над путём выше)
        double floorAfter = high.y + stand.getBbHeight() / 2 - 16;
        Vec3[] last = {start};
        boolean[] raised = {false};
        h.onEachTick(() -> {
            StrikeProjectile f = flight(level, id);
            if (f == null) return;
            double descent = last[0].y - f.getY();
            last[0] = f.position();
            if (!raised[0] && f.isVirtual() && f.getZ() < start.z + roofLength - 100 && when.test(f.getY(), floorAfter, descent)) {
                raised[0] = true;
                stand.teleportTo(high.x, high.y, high.z);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(raised[0], "цель не поднялась");
            h.assertFalse(flight(level, id) != null, "ракета ещё летит, до цели " + (int) last[0].distanceTo(high));
            h.assertTrue(last[0].distanceTo(high) < 24, "ракета взорвалась не у цели: " + last[0].subtract(high));
        });
    }

    /** Снаряд по UUID — вне мира или в мире (null — его уже нет). */
    @Nullable
    private static StrikeProjectile flight(ServerLevel level, UUID id) {
        return VirtualFlights.get(level).flights().stream().filter(v -> v.getUUID().equals(id)).findFirst()
                .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
    }

    /** Снаряд под «рельефом» на 60 блоков выше цели над местом пуска долетает до цели и взрывается у неё. */
    private static void reachesUnderTerrain(GameTestHelper h, StrikeProjectile p, Vec3 start, Vec3 aim) {
        ServerLevel level = h.getLevel();
        roofOverStart(h, start, Mth.floor(aim.y) + 60, 64);
        VirtualFlights.launch(level, p);
        UUID id = p.getUUID();
        Vec3[] last = {start};
        h.onEachTick(() -> {
            StrikeProjectile f = flight(level, id);
            if (f != null) last[0] = f.position();
        });
        h.succeedWhen(() -> {
            h.assertFalse(flight(level, id) != null, "снаряд ещё летит, до цели " + (int) last[0].distanceTo(aim));
            h.assertTrue(last[0].distanceTo(aim) < 24, "снаряд взорвался не у цели: " + last[0].subtract(aim));
            assertCrater(h, RUNWAY_TARGET, "у цели");
        });
    }

    /**
     * «Рельеф» на высоте {@code roof} над местом пуска и {@code length} блоками пути вперёд (+z): готовые чанки, где
     * сущности не тикают, — снаряд над ними летит вне мира. Тикеты снимаются после теста при любом исходе.
     */
    private static void roofOverStart(GameTestHelper h, Vec3 start, int roof, int length) {
        ServerLevel level = h.getLevel();
        List<ChunkPos> held = new ArrayList<>();
        for (int dz = 0; dz <= length; dz++) {
            BlockPos b = BlockPos.containing(start.x, roof, start.z + dz);
            ChunkPos c = new ChunkPos(b);
            if (!held.contains(c)) {
                // уровень 33: чанк готов (FULL), но не тикает
                level.getChunkSource().addRegionTicket(READY_ONLY, c, 0, c);
                level.getChunk(c.x, c.z);
                held.add(c);
            }
            level.setBlock(b, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        afterTest(h, () -> held.forEach(c -> level.getChunkSource().removeRegionTicket(READY_ONLY, c, 0, c)));
    }

    /** Уборка после теста — и когда он прошёл, и когда упал (в {@code succeedWhen} она шла бы только после успеха). */
    private static void afterTest(GameTestHelper h, Runnable cleanup) {
        h.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                cleanup.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                cleanup.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo old, GameTestInfo rerun, GameTestRunner runner) {
            }
        });
    }

    /**
     * Снаряд вне мира, долетевший до цели, чей район уже тикает, взрывается у цели, даже если за целью чанки не тикают.
     * В мир он возвращается, только когда тикает и место впереди по курсу (запас от прыжков на границе); у цели в чанке,
     * который тикает один (игрок в воздухе над краем загрузки), этого не бывало, и снаряд пролетал цель без взрыва и
     * падал по баллистике до конца срока жизни: стенд VPS 29.09.2026 — 3 ракеты РСЗО из ~360 в 900 блоках под миром.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "virtual_arrival", skyAccess = true)
    public static void virtualRocketDetonatesAtTickingAim(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // далеко за площадкой: тикает только чанк цели (принудительно), соседи — нет
        BlockPos origin = h.absolutePos(RUNWAY_TARGET).offset(4000, 0, 4000);
        ChunkPos chunk = new ChunkPos(origin);
        level.setChunkForced(chunk.x, chunk.z, true);
        afterTest(h, () -> level.setChunkForced(chunk.x, chunk.z, false));
        // сущности в чанке цели тикают, только когда готовы соседи (5×5), а их фоновая генерация на CI (сервер тикает без
        // пауз) шла дольше срока теста — снаряд ждал района у цели («ещё летит, до цели 65»); соседи — готовые, но не тикающие
        generateNow(level, chunk);
        Vec3 aim = new Vec3(chunk.getMiddleBlockX() + 0.5, level.getSeaLevel() + 140, chunk.getMiddleBlockZ() + 0.5);
        RocketEntity rocket = ModEntities.ROCKET.get().create(level);
        rocket.launchFrom(aim.add(-300, -140, 0), new Target.Point(aim), aim, null);
        VirtualFlights.launch(level, rocket);
        UUID id = rocket.getUUID();
        Vec3[] last = {rocket.position()};
        boolean[] passed = {false};
        h.onEachTick(() -> {
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            if (p == null) return;
            last[0] = p.position();
            // пролетел цель: дальше неё по курсу (снаряд идёт по +x)
            if (p.getX() > aim.x + 16) passed[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertFalse(passed[0], "снаряд пролетел цель: " + last[0].subtract(aim));
            boolean flying = level.getEntity(id) != null && !level.getEntity(id).isRemoved()
                    || VirtualFlights.get(level).flights().stream().anyMatch(f -> f.getUUID().equals(id));
            h.assertFalse(flying, "снаряд ещё летит, до цели " + (int) last[0].distanceTo(aim));
            h.assertTrue(last[0].distanceTo(aim) < 16, "снаряд пропал не у цели: " + last[0].subtract(aim));
        });
    }

    /**
     * Снаряд РСЗО по свежему району рядом: сходит с пакета сразу (без ожидания в трубе) и до взрыва движется каждый тик.
     * Полёт (~100 тиков) не длиннее загрузки района, и раньше снаряд вне мира замирал в воздухе у цели (сценарий пролёта
     * 29.09.2026: вой обрывался на 8–40 тиков); теперь конец полёта вне мира растягивается во времени. В игровом темпе:
     * без пауз фоновая генерация идёт тысячи тиков, и снаряд вставал бы у черты; растяжение на всю длину, до готовности
     * района, проверяет тест с удержанным районом ({@link #rocketStretchesFlightWhileAimAreaLoads}).
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "rocket_fresh_near", skyAccess = true)
    public static void rocketToFreshNearAreaNeverFreezes(GameTestHelper h) {
        gameSpeed(h);
        rocketLaunchesAtOnceAndNeverFreezes(h, 250, 0);
    }

    /**
     * Район цели грузится дольше полёта (тест держит его незагруженным 300 тиков при полёте ~100): снаряд сходит сразу,
     * вне мира подходит к цели всё медленнее, ни одного тика не стоит и, когда район готов, взрывается у цели.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "rocket_slow_area", skyAccess = true)
    public static void rocketStretchesFlightWhileAimAreaLoads(GameTestHelper h) {
        rocketLaunchesAtOnceAndNeverFreezes(h, 250, 300);
    }

    /**
     * Конец растяжения без скачка: район цели готов, снаряд вне мира разгоняется обратно плавно. Скорость — та, что уходит клиентам в пути звука ({@code velocity()}): скачок
     * в ней — ступенька тона и громкости воя. Шаг в саму цель на последнем тике не считается.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "rocket_stretch_end", skyAccess = true)
    public static void rocketStretchEndsWithoutSpeedStep(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RUNWAY_TARGET)).add(0, 3, 0);
        ChunkPos aimChunk = new ChunkPos(BlockPos.containing(rail).offset(250, 0, 0));
        Vec3 aim = new Vec3(aimChunk.getMiddleBlockX() + 0.5, level.getSeaLevel() + 60, aimChunk.getMiddleBlockZ() + 0.5);
        RocketEntity rocket = ModEntities.ROCKET.get().create(level);
        rocket.placeInTube(rail, -90, LauncherEntity.elevation(WeaponType.ROCKET), 5, 0, new Target.Point(aim), aim, null);
        level.addFreshEntity(rocket);
        UUID id = rocket.getUUID();
        int withhold = 300;
        int[] tick = {0};
        List<String> track = new ArrayList<>();
        List<Double> speeds = new ArrayList<>();
        double[] minRate = {1};
        Vec3[] last = {rail};
        h.onEachTick(() -> {
            tick[0]++;
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            if (p != null) FlightTickets.hold(level, aimChunk, FlightTickets.DISTANCE, id, tick[0] >= withhold);
            if (tick[0] == withhold) generateNow(level, aimChunk);
            // после выгорания: дальше скорость меняют только тяготение и темп растяжения
            if (p == null || p.flightPhase() == FlightPhase.BOOST || p.flightPhase().onLauncher() || p.flightPhase() == FlightPhase.IGNITION) return;
            last[0] = p.position();
            double rate = ((RocketEntity) p).timeRate();
            minRate[0] = Math.min(minRate[0], rate);
            speeds.add(p.velocity().length());
            track.add(String.format(java.util.Locale.ROOT, "тик %d (вне мира %s, темп %.2f, до цели %.0f)", tick[0], p.isVirtual(), rate, p.position().distanceTo(aim)));
        });
        h.succeedWhen(() -> {
            boolean flying = level.getEntity(id) != null && !level.getEntity(id).isRemoved()
                    || VirtualFlights.get(level).flights().stream().anyMatch(f -> f.getUUID().equals(id));
            h.assertFalse(flying, "снаряд ещё летит");
            // взрыв у цели, а не конец ожидания района (AREA_WAIT_LIMIT): иначе конца растяжения и не было
            h.assertTrue(last[0].distanceTo(aim) < 16, "снаряд пропал не у цели: " + last[0].subtract(aim));
            h.assertTrue(minRate[0] < 0.5, "полёт не растягивался (темп не ниже " + minRate[0] + ")");
            // последний тик — шаг в саму цель (доходит до неё, а не на длину шага), его не считаем
            double worst = 0;
            String where = "";
            for (int i = 1; i < speeds.size() - 1; i++) {
                double d = Math.abs(speeds.get(i) - speeds.get(i - 1));
                if (d > worst) {
                    worst = d;
                    where = String.format(java.util.Locale.ROOT, "%.2f → %.2f, %s", speeds.get(i - 1), speeds.get(i), track.get(i));
                }
            }
            Airstrike.LOG.info("Замер РСЗО: наибольший скачок скорости за тик {}", where);
            // темп меняется не быстрее 0,05 за тик: при скорости до ~8 блоков/тик это до 0,4, плюс тяготение
            h.assertTrue(worst <= 0.6, "скачок скорости снаряда " + where);
        });
    }

    /** Дальний свежий район: полёт длиннее загрузки района — сход на тике приказа, без растяжения и остановок. */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "rocket_fresh_far", skyAccess = true)
    public static void rocketToFreshFarAreaLaunchesAtOnce(GameTestHelper h) {
        // «полёт длиннее загрузки района» — в игровом времени: без паузы 1200 тиков срока проходили быстрее генерации
        gameSpeed(h);
        rocketLaunchesAtOnceAndNeverFreezes(h, 1750, 0);
    }

    /**
     * Район цели, отпущенный тестом, готов сразу: чанки 5×5 вокруг — синхронно, тикет уже стоит. Растяжение меряется
     * тиками, а сервер GameTest тикает без пауз (на CI ~2000 тиков в секунду): фоновая генерация после срока шла
     * тысячи тиков, и снаряд успевал встать у черты (main, 29.09.2026: «стоял в воздухе 522 тиков»). Так момент
     * готовности — тик срока, на любой скорости раннера.
     */
    private static void generateNow(ServerLevel level, ChunkPos centre) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) level.getChunk(centre.x + dx, centre.z + dz);
    }

    /**
     * Темп игры (20 тиков в секунду) на время теста, который ждёт фоновой загрузки района: мод меряет ожидание тиками
     * (предел {@code AREA_WAIT_LIMIT} — 1200 тиков, минута игры), а сервер GameTest тикает без пауз — на CI около
     * 2000 тиков в секунду, и минута игры проходила за полсекунды, раньше генерации свежего района.
     */
    private static void gameSpeed(GameTestHelper h) {
        long[] last = {System.nanoTime()};
        h.onEachTick(() -> {
            long wait = 50_000_000L - (System.nanoTime() - last[0]);
            if (wait > 0) LockSupport.parkNanos(wait);
            last[0] = System.nanoTime();
        });
    }

    /** @param withhold сколько тиков район цели не грузится (тест снимает тикет снаряда, потом ставит его сам) */
    private static void rocketLaunchesAtOnceAndNeverFreezes(GameTestHelper h, int distance, int withhold) {
        ServerLevel level = h.getLevel();
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RUNWAY_TARGET)).add(0, 3, 0);
        ChunkPos aimChunk = new ChunkPos(BlockPos.containing(rail).offset(distance, 0, 0));
        Vec3 aim = new Vec3(aimChunk.getMiddleBlockX() + 0.5, level.getSeaLevel() + 60, aimChunk.getMiddleBlockZ() + 0.5);
        RocketEntity rocket = ModEntities.ROCKET.get().create(level);
        int ready = 5;
        rocket.placeInTube(rail, -90, LauncherEntity.elevation(WeaponType.ROCKET), ready, 0, new Target.Point(aim), aim, null);
        level.addFreshEntity(rocket);
        UUID id = rocket.getUUID();
        long orderedAt = System.nanoTime();
        int[] tick = {0};
        int[] leftTube = {-1};
        int[] eta = {0};
        int[] lastSeen = {0};
        int[] stalls = {0};
        Vec3[] last = {rocket.position()};
        boolean[] reported = {false};
        int[] areaReadyAt = {-1};
        BlockPos aimPos = BlockPos.containing(aim);
        h.onEachTick(() -> {
            tick[0]++;
            if (areaReadyAt[0] < 0 && Terrain.ready(level, aimPos) && level.isPositionEntityTicking(aimPos)) areaReadyAt[0] = tick[0];
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            // район «грузится долго»: тикет снаряда снимается, пока не выйдет срок, потом ставится снова (снимет его снаряд)
            if (withhold > 0 && p != null) FlightTickets.hold(level, aimChunk, FlightTickets.DISTANCE, id, tick[0] >= withhold);
            if (withhold > 0 && tick[0] == withhold) generateNow(level, aimChunk);
            if (p == null) return;
            lastSeen[0] = tick[0];
            boolean moved = p.position().distanceToSqr(last[0]) > 1.0e-6;
            last[0] = p.position();
            // сход — первый сдвиг из трубы; дальше каждый тик до взрыва снаряд движется
            if (leftTube[0] < 0) {
                if (moved) {
                    leftTube[0] = tick[0];
                    eta[0] = p.etaTicks();
                }
            } else if (!moved) {
                stalls[0]++;
            }
        });
        h.succeedWhen(() -> {
            boolean flying = level.getEntity(id) != null && !level.getEntity(id).isRemoved()
                    || VirtualFlights.get(level).flights().stream().anyMatch(f -> f.getUUID().equals(id));
            h.assertFalse(flying, "снаряд ещё летит, до цели " + (int) last[0].distanceTo(aim));
            // замер для PR: задержка схода и растяжение полёта вне мира (тики сверх расчётного времени полёта)
            if (!reported[0]) Airstrike.LOG.info("Замер РСЗО {} блоков: сход на тике {} (готов к {}), полёт {} тиков при расчётных {}, растяжение {}, район цели готов на тике {}, {} мс",
                    distance, leftTube[0], ready + RocketEntity.IGNITION_TICKS, lastSeen[0] - leftTube[0], eta[0],
                    lastSeen[0] - leftTube[0] - eta[0], areaReadyAt[0],
                    (System.nanoTime() - orderedAt) / 1_000_000);
            reported[0] = true;
            h.assertTrue(leftTube[0] > 0 && leftTube[0] <= ready + RocketEntity.IGNITION_TICKS + 2,
                    "снаряд сошёл с пакета на тике " + leftTube[0] + ", а готов к " + (ready + RocketEntity.IGNITION_TICKS));
            h.assertTrue(stalls[0] == 0, "снаряд стоял в воздухе " + stalls[0] + " тиков");
            if (withhold > 0) h.assertTrue(areaReadyAt[0] >= withhold, "район цели загрузился раньше, чем тест его отпустил");
            h.assertTrue(last[0].distanceTo(aim) < 16, "снаряд пропал не у цели: " + last[0].subtract(aim));
        });
    }

    /**
     * Подсказка карты под спамом: два игрока кликают по новому месту каждый тик 100 тиков. У каждого в любой тик не
     * больше одного района подсказки (и не больше одного тикета региона), новый — не чаще раза в
     * {@link PickHints#MIN_INTERVAL} тиков, последний клик берётся (отложенный), а через {@link PickHints#LIFESPAN}
     * тиков район отпускается сам — ни тикета региона, ни тикета загрузки.
     */
    @GameTest(template = "runway", timeoutTicks = 1000, batch = "pick_spam", skyAccess = true)
    public static void mapPickSpamHoldsOneAreaPerPlayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos origin = h.absolutePos(RUNWAY_TARGET);
        UUID[] who = {UUID.randomUUID(), UUID.randomUUID()};
        PickHints.Slot[] slots = {new PickHints.Slot(), new PickHints.Slot()};
        int[] tick = {0};
        int[] taken = {0, 0};
        ChunkPos[] lastHeld = {null, null};
        ChunkPos[] lastClick = {null, null};
        int spam = 100;
        h.onEachTick(() -> {
            tick[0]++;
            for (int i = 0; i < 2; i++) {
                if (tick[0] <= spam) {
                    // каждый клик — другой свежий район (шаг 500 блоков), у второго игрока — в другую сторону
                    double x = origin.getX() + (i == 0 ? 1 : -1) * 500.0 * tick[0], z = origin.getZ() + 3000 * i;
                    PickHints.pick(level, who[i], slots[i], x, z);
                    lastClick[i] = new ChunkPos(BlockPos.containing(x, 0, z));
                }
                PickHints.tick(level, who[i], slots[i]);
                if (slots[i].held() != null && !slots[i].held().equals(lastHeld[i])) taken[i]++;
                lastHeld[i] = slots[i].held();
                int held = pickTickets(level, who[i]), areas = PickHints.areas(level, who[i]);
                if (held > 1 || areas > 1) throw new GameTestAssertException("у игрока " + i + " районов подсказки: " + areas + ", тикетов " + held + " на тике " + tick[0]);
            }
        });
        h.runAtTickTime(spam + PickHints.MIN_INTERVAL + 1, () -> {
            for (int i = 0; i < 2; i++) {
                h.assertTrue(lastClick[i].equals(slots[i].held()), "последний клик не взят: держится " + slots[i].held() + ", клик " + lastClick[i]);
                int limit = spam / PickHints.MIN_INTERVAL + 2;
                h.assertTrue(taken[i] <= limit, "новых районов " + taken[i] + " за " + spam + " тиков, предел " + limit);
                h.assertTrue(PickHints.areas(level, who[i]) == 1, "район последнего клика не взят");
            }
        });
        h.runAtTickTime(spam + PickHints.MIN_INTERVAL + PickHints.LIFESPAN + 20, () -> {
            for (int i = 0; i < 2; i++) {
                h.assertTrue(PickHints.areas(level, who[i]) == 0 && pickTickets(level, who[i]) == 0 && loadTickets(level, who[i]) == 0,
                        "район подсказки не отпущен за " + PickHints.LIFESPAN + " тиков");
            }
            h.succeed();
        });
    }

    /** Тикеты подсказки карты с ключом {@code who} во всём мире (из очереди тикетов ванили). */
    private static int pickTickets(ServerLevel level, UUID who) {
        return tickets(level, who, PickHints::isPickTicket);
    }

    /** Тикеты загрузки района ({@code AreaLoader}) с ключом {@code who}. */
    private static int loadTickets(ServerLevel level, UUID who) {
        return tickets(level, who, t -> t.toString().equals("airstrike_area_load"));
    }

    @SuppressWarnings("unchecked")
    private static int tickets(ServerLevel level, UUID who, java.util.function.Predicate<TicketType<?>> type) {
        try {
            Field dm = ChunkMap.class.getDeclaredField("distanceManager");
            dm.setAccessible(true);
            DistanceManager d = (DistanceManager) dm.get(level.getChunkSource().chunkMap);
            Field tf = DistanceManager.class.getDeclaredField("tickets");
            tf.setAccessible(true);
            Field key = Ticket.class.getDeclaredField("key");
            key.setAccessible(true);
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : ((Long2ObjectMap<SortedArraySet<Ticket<?>>>) tf.get(d)).values()) {
                for (Ticket<?> t : set) if (type.test(t.getType()) && who.equals(key.get(t))) n++;
            }
            return n;
        } catch (ReflectiveOperationException ex) {
            throw new GameTestAssertException("очередь тикетов не читается: " + ex);
        }
    }

    @GameTest(template = "runway", timeoutTicks = 2400, batch = "salvo", skyAccess = true)
    public static void salvoFiresEveryShot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 c = top(h, RUNWAY_TARGET);
        SalvoData.start(level, WeaponType.DRONE, 3, 6, new Target.Point(c), c, 0, null, Loadout.Nuke.DEFAULT);
        h.succeedWhen(() -> {
            h.assertTrue(SalvoData.get(level).size() == 0, "залп ещё не закончился");
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty(), "ещё летят вне мира: " + VirtualFlights.get(level).flights().size());
            List<StrikeProjectile> flying = level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(128));
            h.assertTrue(flying.isEmpty(), "ещё летят: " + flying.stream().map(p -> p.flightPhase() + " " + h.relativeVec(p.position())
                    + " возраст " + p.age() + " до цели " + (int) p.position().distanceTo(p.aimPoint())).toList());
        });
    }

    /**
     * Ядерных залпов нет: приказ от консоли с ядерной БЧ на ракете — одна ракета, а не залп (как у игрока), и снаряды
     * залпа ядерной БЧ не несут, даже если она попала в залп.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "salvo_nuke", skyAccess = true)
    public static void salvoNeverCarriesNuke(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // далеко за площадкой: до конца теста никто не долетит
        Vec3 far = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0, 3000);
        Loadout.Nuke nuke = new Loadout.Nuke(15, true, true);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(far), far, null);
        // свои снаряды — по точке цели (в мире теста могут лететь и чужие)
        java.util.function.Predicate<StrikeProjectile> ours = p -> p.aimPoint().distanceTo(far) < 40;
        h.assertTrue(ServerActions.dispatch(level, "GameTest", 0, WeaponType.MISSILE, 5, 20, aim, nuke), "пуск от консоли не прошёл");
        h.assertTrue(SalvoData.get(level).size() == 0, "ядерный приказ стал залпом");
        boolean carriers = ua.zentix.airstrike.AirstrikeConfig.SERVER.carrierNukes.get();
        List<StrikeProjectile> single = VirtualFlights.get(level).flights().stream().filter(ours).toList();
        h.assertTrue(single.size() == 1 && single.getFirst().isNuclear() == carriers,
                "не одна ракета с ядерной БЧ: " + single.stream().map(StrikeProjectile::isNuclear).toList());
        VirtualFlights.get(level).clear(level, ours);
        SalvoData.start(level, WeaponType.MISSILE, 3, 20, new Target.Point(far), far, 0, null, nuke);
        h.runAfterDelay(3, () -> {
            List<StrikeProjectile> fired = VirtualFlights.get(level).flights().stream().filter(ours).toList();
            h.assertTrue(!fired.isEmpty() && fired.stream().noneMatch(StrikeProjectile::isNuclear),
                    "снаряды залпа: " + fired.stream().map(StrikeProjectile::isNuclear).toList());
            SalvoData.get(level).clear();
            VirtualFlights.get(level).clear(level, ours);
            h.succeed();
        });
    }

    /**
     * Предел ударов у игрока ({@code max_active_per_player}) считает всё его в работе: невыпущенные снаряды залпа,
     * снаряды в мире и вне его; чужие — нет. Выпущенный снаряд залпа переходит из одного в другое, итог тот же.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "active_count", skyAccess = true)
    public static void activeShotsCountEverythingOfOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        java.util.UUID owner = java.util.UUID.randomUUID(), other = java.util.UUID.randomUUID();
        Vec3 far = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0, 3000);
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        SalvoData.start(level, WeaponType.DRONE, 5, 10, new Target.Point(far), far, 0, owner, Loadout.Nuke.DEFAULT);
        CruiseMissileEntity onRail = ModEntities.CRUISE_MISSILE.get().create(level);
        onRail.placeOnLauncher(rail, 0, 40, 1000, 0, new Target.Point(far), far, owner);
        onRail.setRoute(Route.direct());
        level.addFreshEntity(onRail);
        for (java.util.UUID id : List.of(owner, other)) {
            CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
            m.launch(far.add(0, 60, 2000), new Target.Point(far), far, id);
            m.setRoute(Route.direct());
            VirtualFlights.launch(level, m);
        }
        h.assertTrue(StrikeWorld.active(level.getServer(), owner) == 7, "в работе: " + StrikeWorld.active(level.getServer(), owner));
        h.runAfterDelay(3, () -> {
            h.assertTrue(SalvoData.get(level).remaining(owner) < 5, "залп не выпустил ни одного снаряда");
            h.assertTrue(StrikeWorld.active(level.getServer(), owner) == 7, "после пуска из залпа: " + StrikeWorld.active(level.getServer(), owner));
            h.assertTrue(StrikeWorld.active(level.getServer(), other) == 1, "у другого игрока: " + StrikeWorld.active(level.getServer(), other));
            SalvoData.get(level).clear();
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()) || other.equals(p.ownerId()));
            onRail.discard();
            h.succeed();
        });
    }

    /**
     * Ударная волна выбивает стёкла и листву в своём кубе, по секциям чанков: каждый такой блок внутри (и на
     * стыках секций), ни одного снаружи и ничего другого.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void shatterBreaksGlassAndLeavesInBox(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(RANGE_CENTER.above(8));
        // по углам и серединам куба 2r+1 — точки в разных секциях; снаружи — на блок за краем
        int r = 9;
        List<BlockPos> inside = new ArrayList<>();
        for (int dx : new int[]{-r, 0, r}) {
            for (int dy : new int[]{-3, 0, 5}) {
                for (int dz : new int[]{-r, 0, r}) {
                    if (dx != 0 || dy != 0 || dz != 0) inside.add(c.offset(dx, dy, dz));
                }
            }
        }
        List<BlockPos> outside = List.of(c.offset(r + 1, 0, 0), c.offset(0, 6, 0), c.offset(0, -4, -r - 1));
        for (int i = 0; i < inside.size(); i++) level.setBlock(inside.get(i), (i % 2 == 0 ? Blocks.GLASS : Blocks.GLASS_PANE).defaultBlockState(), 3);
        for (BlockPos p : outside) level.setBlock(p, Blocks.GLASS.defaultBlockState(), 3);
        BlockPos leaves = c.offset(2, 1, -2), stone = c.offset(-2, 1, 2);
        level.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState(), 3);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        Vec3 centre = Vec3.atCenterOf(c);
        int glass = Warheads.shatter(level, centre, r, 3, 5, ua.zentix.airstrike.registry.ModTags.SHATTERS);
        h.assertTrue(glass == inside.size(), "выбито стёкол " + glass + " из " + inside.size());
        for (BlockPos p : inside) h.assertTrue(level.getBlockState(p).isAir(), "стекло осталось в " + h.relativePos(p));
        for (BlockPos p : outside) h.assertTrue(level.getBlockState(p).is(Blocks.GLASS), "выбито стекло за краем в " + h.relativePos(p));
        h.assertTrue(level.getBlockState(leaves).is(Blocks.OAK_LEAVES) && level.getBlockState(stone).is(Blocks.STONE), "волна по стёклам тронула не стекло");
        h.assertTrue(Warheads.shatter(level, centre, r, 3, 5, net.minecraft.tags.BlockTags.LEAVES) == 1 && level.getBlockState(leaves).isAir(),
                "листва не выбита");
        h.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "волна по листве тронула камень");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20)
    public static void pickerSeesBlockAndEntity(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ArmorStand viewer = h.spawn(EntityType.ARMOR_STAND, new BlockPos(32, 12, 4));
        Vec3 eye = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 14, 4)));
        Vec3 down = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 11, 20))).subtract(eye).normalize();

        TargetPicker.Pick ground = TargetPicker.pick(level, viewer, eye, down, 200);
        h.assertTrue(ground != null && ground.kind() == TargetPicker.Kind.BLOCK, "должен видеть землю: " + (ground == null ? null : ground.kind()));

        Cow cow = h.spawn(EntityType.COW, new BlockPos(32, 12, 12));
        cow.setNoAi(true);
        Vec3 atCow = cow.getBoundingBox().getCenter().subtract(eye).normalize();
        TargetPicker.Pick pick = TargetPicker.pick(level, viewer, eye, atCow, 200);
        h.assertTrue(pick != null && pick.entity() == cow, "должен видеть корову, а видит " + (pick == null ? null : pick.kind() + " " + pick.label().getString() + " " + pick.point()) + ", корова " + cow.getBoundingBox());
        h.assertTrue(pick.target() instanceof Target.OfEntity, "цель — сущность");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 5)
    public static void dataSurvivesSaving(GameTestHelper h) {
        Loadout l = new Loadout(WeaponType.BUNKER, 7, 33, TargetMode.PLAYER, "ENOTzRPG", new Loadout.Nuke(100, false, true));
        h.assertTrue(l.nuclear(), "B-2 с ядерной БЧ");
        Loadout back = Loadout.CODEC.parse(NbtOps.INSTANCE, Loadout.CODEC.encodeStart(NbtOps.INSTANCE, l).getOrThrow()).getOrThrow();
        h.assertTrue(l.equals(back), "пульт не пережил сохранение: " + back);

        Target t = new Target.OfSubLevel(new Vec3(1.5, 2.5, 3.5));
        Target tb = Target.CODEC.parse(NbtOps.INSTANCE, Target.CODEC.encodeStart(NbtOps.INSTANCE, t).getOrThrow()).getOrThrow();
        h.assertTrue(t.equals(tb), "цель не пережила сохранение: " + tb);
        Target g = new Target.Ground(new Vec3(-1234.5, 70, 987.25));
        Target gb = Target.CODEC.parse(NbtOps.INSTANCE, Target.CODEC.encodeStart(NbtOps.INSTANCE, g).getOrThrow()).getOrThrow();
        h.assertTrue(g.equals(gb), "место с карты не пережило сохранение: " + gb);

        Loadout huge = new Loadout(WeaponType.DRONE, 10_000, -5, TargetMode.LOOK, "x".repeat(40), new Loadout.Nuke(0, true));
        h.assertTrue(huge.count() == Loadout.MAX_COUNT && huge.spread() == 0 && huge.player().length() == 16, "зажим значений");
        h.succeed();
    }

    /** Обёртка дробления аппаратов Sable встала (CI гоняет GameTest с Sable): иначе краш 27.09.2026 вернётся. */
    @GameTest(template = "pad", timeoutTicks = 5)
    public static void sableSplitGuardApplied(GameTestHelper h) throws ClassNotFoundException {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        Class<?> heat = Class.forName("dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager");
        h.assertTrue(SplitGuard.class.isAssignableFrom(heat), "миксин SubLevelSplitGuardMixin не применился к Sable");
        // интерфейс вливается и тогда, когда обёртка не встала (require = 0): проверяем саму обёртку
        h.assertTrue(java.util.Arrays.stream(heat.getDeclaredMethods()).anyMatch(m -> m.getName().contains("guardSplit")),
                "обёртка split не встала: " + java.util.Arrays.stream(heat.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList());
        h.assertTrue(SplitGuard.isRemovedPlot(new RuntimeException(SplitGuard.REMOVED_PLOT)), "узнаёт сбой Sable");
        h.assertFalse(SplitGuard.isRemovedPlot(new RuntimeException("другое")), "чужие исключения не глотает");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "debris", skyAccess = true)
    public static void debrisLandsAsBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 20, 32)));
        DebrisEntity d = DebrisEntity.create(level, at, Blocks.COBBLESTONE.defaultBlockState(), false, true, new Vec3(0.2, 0.5, 0));
        level.addFreshEntity(d);
        h.succeedWhen(() -> {
            h.assertTrue(d.isRemoved(), "обломок ещё летит");
            boolean found = false;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(28, 12, 28), new BlockPos(44, 13, 36))) {
                if (h.getBlockState(p).is(Blocks.COBBLESTONE)) found = true;
            }
            h.assertTrue(found, "обломок не лёг блоком");
        });
    }
}
