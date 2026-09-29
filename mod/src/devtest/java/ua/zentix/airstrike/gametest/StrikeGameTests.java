package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.SplitGuard;
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
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.List;

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
        List<RocketEntity> rockets = new java.util.ArrayList<>();
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
     * получает его путь (фаза, где он, сколько до цели), в 1000 блоках — нет: снаряд вне загруженного мира слышно
     * так же, как в мире, и не дальше, чем его слышно.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "heard", skyAccess = true)
    public static void virtualFlightIsHeardOnlyInRange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        r.launchFrom(point.add(0, 0, -600), new Target.Point(point), point, null);
        VirtualFlights.launch(level, r);
        h.runAfterDelay(10, () -> {
            var flights = ua.zentix.airstrike.strike.FlightSounds.flights(level);
            h.assertTrue(flights.stream().anyMatch(f -> f.getUUID().equals(r.getUUID()) && f.isVirtual()), "снаряда нет среди летящих вне мира");
            Vec3 at = r.position();
            var near = ua.zentix.airstrike.strike.FlightSounds.heard(level, flights, at.add(150, 0, 0), null);
            h.assertTrue(near.size() == 1 && near.getFirst().id().equals(r.getUUID()), "в 150 блоках не слышно: " + near);
            var f = near.getFirst();
            h.assertTrue(f.pos().distanceTo(at) < 1.0e-6 && f.weapon() == WeaponType.ROCKET.id() && !f.bomber(), "не тот путь: " + f);
            h.assertTrue(Math.abs(f.distanceToAim() - at.distanceTo(r.aimPoint())) < 0.01, "до цели: " + f.distanceToAim());
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
