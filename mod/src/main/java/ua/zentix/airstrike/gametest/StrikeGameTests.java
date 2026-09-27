package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;

import java.util.List;

/**
 * Проверки без окна ({@code ./gradlew runGameTestServer}): каждое оружие долетает и взрывается, бомба бурит,
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
        int air = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, -2, -3), at.offset(3, 0, 3))) {
            if (h.getBlockState(p).isAir()) air++;
        }
        h.assertTrue(air >= 30, "в точке удара нет воронки: пустых блоков " + air);
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
    public static void missileWaitsThenStrikes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        // 199 блоков до цели: бреющий полёт, горка и пикирование
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 5, 1)));
        missile.launch(start, new Target.Point(top(h, RUNWAY_TARGET)), top(h, RUNWAY_TARGET), null);
        level.addFreshEntity(missile);
        h.runAfterDelay(50, () -> h.assertFalse(missile.isRemoved() || missile.isActive(), "ракета должна ждать окончания сирены"));
        h.succeedWhen(() -> {
            h.assertTrue(missile.isRemoved(), "ракета ещё летит: " + missile.position());
            h.assertTrue(missile.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED, "ракета пропала: " + missile.getRemovalReason());
            assertCrater(h, RUNWAY_TARGET);
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

    @GameTest(template = "runway", timeoutTicks = 600, batch = "salvo", skyAccess = true)
    public static void salvoFiresEveryShot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 c = top(h, RUNWAY_TARGET);
        SalvoData.start(level, WeaponType.DRONE, 3, 6, new Target.Point(c), c, 0, null);
        h.succeedWhen(() -> {
            h.assertTrue(SalvoData.get(level).size() == 0, "залп ещё не закончился");
            List<StrikeProjectile> flying = level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(128));
            h.assertTrue(flying.isEmpty(), "ещё летят: " + flying.size());
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
        Loadout l = new Loadout(WeaponType.BUNKER, 7, 33, TargetMode.PLAYER, "ENOTzRPG");
        Loadout back = Loadout.CODEC.parse(NbtOps.INSTANCE, Loadout.CODEC.encodeStart(NbtOps.INSTANCE, l).getOrThrow()).getOrThrow();
        h.assertTrue(l.equals(back), "пульт не пережил сохранение: " + back);

        Target t = new Target.OfSubLevel(new Vec3(1.5, 2.5, 3.5));
        Target tb = Target.CODEC.parse(NbtOps.INSTANCE, Target.CODEC.encodeStart(NbtOps.INSTANCE, t).getOrThrow()).getOrThrow();
        h.assertTrue(t.equals(tb), "цель не пережила сохранение: " + tb);

        Loadout huge = new Loadout(WeaponType.DRONE, 10_000, -5, TargetMode.LOOK, "x".repeat(40));
        h.assertTrue(huge.count() == Loadout.MAX_COUNT && huge.spread() == 0 && huge.player().length() == 16, "зажим значений");
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
