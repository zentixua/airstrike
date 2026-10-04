package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.CameraLink;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.target.Sight;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetTracker;

import java.util.Optional;
import java.util.UUID;

/**
 * Приказ игрока по правилам ({@link ServerActions#sighted}): по игроку — только видя его, движущаяся цель — замеченная
 * ({@link Target.Sighted}); за ней идёт только оружие с камерой, пока оператор держит её в кадре ({@link CameraLink}).
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PursuitGameTests {
    private PursuitGameTests() {}

    /**
     * Замеченную цель трекер ведёт, только пока её видно: невидимая остаётся там, где её видели, и не теряется;
     * пропавшая теряется, точка — последняя виденная. Сохранение и загрузка помнят, что цель замеченная.
     */
    @GameTest(template = "pad", batch = "pursuit")
    public static void sightedTargetMovesOnlyInSight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 seen = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 2)));
        ArmorStand stand = new ArmorStand(level, seen.x, seen.y, seen.z);
        level.addFreshEntity(stand);
        Target sighted = new Target.Sighted(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), seen);
        TargetTracker tracker = new TargetTracker(sighted, seen);
        h.assertValueEqual(TargetTracker.load(tracker.save()).target(), sighted, "замеченная цель после загрузки");

        Vec3 moved = seen.add(5, 0, 0);
        stand.teleportTo(moved.x, moved.y, moved.z);
        h.assertTrue(tracker.tick(level, q -> false) == 0 && tracker.point().equals(seen), "цель вне кадра сдвинула точку");
        h.assertFalse(tracker.inSight() || tracker.isLost(), "цель вне кадра видна или потеряна");
        h.assertTrue(Math.abs(tracker.tick(level, q -> q.distanceTo(moved) < 1e-6) - 5) < 1e-6 && tracker.inSight(),
                "цель в кадре не ведётся");
        h.assertTrue(tracker.point().distanceTo(moved) < 1e-6, "точка не там, где цель видна");

        stand.discard();
        tracker.tick(level, q -> true);
        h.assertTrue(tracker.isLost() && tracker.point().distanceTo(moved) < 1e-6, "пропавшая цель не потеряна или сдвинула точку");
        h.succeed();
    }

    /**
     * Приказ игрока по правилам: по невидимому ему игроку не бьёт, по видимому — бьёт туда, где его видели; моб и
     * аппарат — тоже замеченные, без проверки; точка остаётся точкой.
     */
    @GameTest(template = "pad", batch = "pursuit_players")
    public static void playerOrdersNeedSight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer shooter = player(level, "rules_shooter", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
        FakePlayer victim = player(level, "rules_victim", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 1, 6))));
        // по нику цель ищется в мире стреляющего: игрок должен в нём быть
        level.addNewPlayer(victim);
        StrikeGameTests.afterTest(h, () -> level.removePlayerImmediately(victim, Entity.RemovalReason.DISCARDED));

        ServerActions.Aim atVictim = ServerActions.atPlayer(victim);
        ServerActions.Aim seen = ServerActions.sighted(shooter, atVictim, t -> Sight.sees(shooter, victim));
        h.assertTrue(seen != null && seen.target() instanceof Target.Sighted s && s.quarry().equals(atVictim.target())
                && s.seen().equals(atVictim.point()), "по видимому игроку — не замеченная цель: " + (seen == null ? null : seen.target()));

        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(4, y, z), Blocks.STONE);
        h.assertTrue(ServerActions.sighted(shooter, atVictim, t -> Sight.sees(shooter, victim)) == null, "по игроку за стеной приказ принят");

        Cow cow = h.spawn(EntityType.COW, new BlockPos(2, 1, 6));
        ServerActions.Aim atCow = new ServerActions.Aim(Target.OfEntity.center(cow), cow.getBoundingBox().getCenter(), Component.literal("cow"));
        ServerActions.Aim cowAim = ServerActions.sighted(shooter, atCow, v -> false);
        h.assertTrue(cowAim != null && cowAim.target() instanceof Target.Sighted && cowAim.label() == atCow.label(), "моб — не замеченная цель");

        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(3, 1, 3)));
        ServerActions.Aim atPoint = new ServerActions.Aim(new Target.Point(point), point, null);
        h.assertTrue(ServerActions.sighted(shooter, atPoint, v -> false) == atPoint, "точка изменилась");
        h.succeed();
    }

    /**
     * Камера держит цель: свежий вид с борта этого снаряда, цель в кадре и между ними нет блоков. Камера, повёрнутая
     * в сторону, старый вид, чужой снаряд и цель за стеной — не держат.
     */
    @GameTest(template = "pad", batch = "pursuit")
    public static void cameraHoldsOnlyWhatIsInFrame(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer operator = player(level, "camera_operator", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        Vec3 standAt = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 1, 6)));
        stand.moveTo(standAt.x, standAt.y, standAt.z);
        level.addFreshEntity(stand);
        Target subject = new Target.OfEntity(stand.getUUID(), new Vec3(0, 1, 0));
        Vec3 at = standAt.add(0, 1, 0);

        // пуск поднимает шахед на высоту полёта — ставим его обратно под потолок площадки
        DroneEntity drone = drone(level, Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 6))), subject, at, operator.getUUID());
        drone.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 6))));
        DroneEntity other = drone(level, Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 5))), subject, at, operator.getUUID());
        other.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 5))));
        StrikeGameTests.afterTest(h, () -> {
            drone.discard();
            other.discard();
        });
        float[] toTarget = angles(drone.getEyePosition(), at);
        CameraLink.receive(operator, drone.getUUID(), toTarget[0], toTarget[1]);
        h.assertTrue(CameraLink.holds(operator, drone, subject, at), "цель в кадре не держится");
        h.assertFalse(CameraLink.holds(operator, other, subject, at), "держится камерой другого снаряда");

        CameraLink.receive(operator, drone.getUUID(), toTarget[0] + 90, toTarget[1]);
        h.assertFalse(CameraLink.holds(operator, drone, subject, at), "держится камерой, повёрнутой в сторону");

        CameraLink.receive(operator, drone.getUUID(), toTarget[0], toTarget[1]);
        CameraLink view = operator.getData(ModAttachments.CAMERA_LINK).orElseThrow();
        operator.setData(ModAttachments.CAMERA_LINK, Optional.of(new CameraLink(view.projectile(), view.look(), view.tick() - 11)));
        h.assertFalse(CameraLink.holds(operator, drone, subject, at), "держится по старому виду");

        CameraLink.receive(operator, drone.getUUID(), toTarget[0], toTarget[1]);
        for (int z = 0; z < 8; z++) for (int y = 1; y < 6; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        h.assertFalse(CameraLink.holds(operator, drone, subject, at), "держится сквозь стену");
        h.succeed();
    }

    /**
     * Никто не держит замеченную корову в кадре, и она ушла: шахед летит туда, где её видели, а камера показывает
     * «цель вне кадра»; крылатая ракета идёт туда же и погони не показывает — за целью она не идёт вовсе.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "pursuit", skyAccess = true)
    public static void unheldTargetStaysWhereSeen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow cow = h.spawn(EntityType.COW, new BlockPos(32, 12, 32));
        cow.setNoAi(true);
        Vec3 seen = cow.getBoundingBox().getCenter();
        Target sighted = new Target.Sighted(Target.OfEntity.center(cow), seen);
        UUID owner = UUID.randomUUID();
        DroneEntity drone = drone(level, Vec3.atCenterOf(h.absolutePos(new BlockPos(8, 40, 8))), sighted, seen, owner);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(Vec3.atCenterOf(h.absolutePos(new BlockPos(2, 60, 2))), sighted, seen, owner);
        level.addFreshEntity(missile);
        UUID droneId = drone.getUUID(), missileId = missile.getUUID();
        StrikeGameTests.afterTest(h, () -> {
            for (UUID id : new UUID[]{droneId, missileId}) if (level.getEntity(id) != null) level.getEntity(id).discard();
        });
        h.runAfterDelay(1, () -> cow.teleportTo(seen.x + 20, cow.getY(), seen.z));
        h.runAfterDelay(5, () -> {
            h.assertTrue(cow.getBoundingBox().getCenter().distanceTo(seen) > 15, "корова не ушла — проверять нечего");
            for (UUID id : new UUID[]{droneId, missileId}) {
                h.assertTrue(level.getEntity(id) instanceof StrikeProjectile, "снаряд пропал до проверки: " + id);
                StrikeProjectile p = (StrikeProjectile) level.getEntity(id);
                // точка прицела у клиента — во float (DATA_AIM): на дальних координатах GameTest шаг до 1 блока
                Vec3 synced = new Vec3((float) seen.x, (float) seen.y, (float) seen.z);
                h.assertTrue(p.aimPoint().distanceTo(synced) < 0.1, p.weapon() + " целится не туда, где цель видели: " + p.aimPoint());
                h.assertFalse(p.targetLost(), p.weapon() + " потерял живую цель");
                StrikeProjectile.Pursuit expected = p.weapon().spec().tracking() == WeaponSpec.Tracking.CAMERA
                        ? StrikeProjectile.Pursuit.WAITING : StrikeProjectile.Pursuit.NONE;
                h.assertValueEqual(p.pursuit(), expected, p.weapon() + ": погоня в камере");
            }
            h.succeed();
        });
    }

    private static DroneEntity drone(ServerLevel level, Vec3 at, Target target, Vec3 point, UUID owner) {
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        drone.launch(at, target, point, owner);
        level.addFreshEntity(drone);
        return drone;
    }

    /** Курс и тангаж взгляда из {@code from} в {@code to}, как у поворота игрока. */
    private static float[] angles(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new float[]{yaw, pitch};
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }
}
