package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.nuclear.model.CraterModel;
import ua.zentix.airstrike.nuclear.model.PromptRadiationModel;
import ua.zentix.airstrike.nuclear.model.Yield;
import ua.zentix.airstrike.nuclear.world.ColumnScar;
import ua.zentix.airstrike.nuclear.world.CraterJob;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.ThermalShadow;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.List;

/**
 * Ядерный удар без окна (DESIGN-nuke §12) — в уменьшенном масштабе ({@code scale} 0.01–0.07: 1 блок = 15–100 м),
 * чтобы зоны давления уместились на площадке 64×64 «range» (дёрн на y = 11, поверхность y = 12).
 * Каждый тест в своей партии: подрыв задевает всё вокруг, соседние площадки не должны мешать друг другу.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 12, 32);

    private NuclearGameTests() {}

    private static Detonation detonation(GameTestHelper h, BlockPos at, double hob, double yieldKt, float scale) {
        BlockPos g = h.absolutePos(at);
        return new Detonation(1_000_000 + h.getLevel().random.nextInt(1000), new Vec3(g.getX() + 0.5, g.getY() + hob, g.getZ() + 0.5), g.getY(),
                yieldKt, hob <= 0, h.getLevel().getGameTime(), 0, 0, 20_000, 7, scale, false);
    }

    /** Все столбцы площадки — как их прошла бы очередь разрушений. */
    private static void scarAll(GameTestHelper h, Detonation d) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        ColumnScar.Budget budget = new ColumnScar.Budget();
        RandomSource random = RandomSource.create(1);
        for (int x = Math.min(a.getX(), b.getX()); x <= Math.max(a.getX(), b.getX()); x++) {
            for (int z = Math.min(a.getZ(), b.getZ()); z <= Math.max(a.getZ(), b.getZ()); z++) ColumnScar.apply(level, d, x, z, budget, random);
        }
    }

    /**
     * 15 кт у земли, 1 блок = 40 м: дерево в 600 м (18 psi) валится стволом от эпицентра, стекло в 1.2 км (4 psi)
     * и доски в 880 м (7 psi) выбиты, каменный кирпич в 1 км (6 psi, порог 12) стоит, грунт цел.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_blocks", skyAccess = true)
    public static void blastBreaksLightKeepsMasonryFellsTrees(GameTestHelper h) {
        BlockPos tree = CENTER.east(15);
        for (int i = 0; i < 4; i++) h.setBlock(tree.above(i), Blocks.OAK_LOG);
        for (BlockPos p : BlockPos.betweenClosed(tree.offset(-2, 3, -2), tree.offset(2, 5, 2))) {
            if (h.getBlockState(p).isAir()) h.setBlock(p, Blocks.OAK_LEAVES);
        }
        BlockPos glass = CENTER.west(29), bricks = CENTER.north(25), planks = CENTER.south(22);
        h.setBlock(glass, Blocks.GLASS);
        h.setBlock(bricks, Blocks.STONE_BRICKS);
        h.setBlock(bricks.above(), Blocks.STONE_BRICKS);
        h.setBlock(planks, Blocks.OAK_PLANKS);

        scarAll(h, detonation(h, CENTER, 0, 15, 0.025f));

        h.assertBlockNotPresent(Blocks.GLASS, glass);
        h.assertBlockNotPresent(Blocks.OAK_PLANKS, planks);
        h.assertBlockPresent(Blocks.STONE_BRICKS, bricks);
        h.assertBlockPresent(Blocks.STONE_BRICKS, bricks.above());
        h.assertTrue(!h.getBlockState(tree.above()).is(BlockTags.LOGS), "ствол дерева стоит");
        int lying = 0;
        for (int i = 0; i < 6; i++) {
            var s = h.getBlockState(tree.east(i));
            if (s.is(BlockTags.LOGS) && s.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X) lying++;
        }
        h.assertTrue(lying >= 3, "дерево не легло от эпицентра: брёвен вдоль x " + lying);
        h.assertTrue(!h.getBlockState(CENTER.east(10).below()).isAir(), "волна тронула грунт");
        h.succeed();
    }

    /** Тень: за стеной огненный шар не виден (ни света, ни пожара), на открытом месте — виден. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_shadow", skyAccess = true)
    public static void wallCastsThermalShadow(GameTestHelper h) {
        for (int y = 0; y < 6; y++) {
            for (int z = -3; z <= 3; z++) h.setBlock(CENTER.offset(8, y, z), Blocks.STONE);
        }
        Vec3 fireball = Vec3.atCenterOf(h.absolutePos(CENTER.above(4)));
        Vec3 behind = Vec3.atCenterOf(h.absolutePos(CENTER.east(12)));
        Vec3 open = Vec3.atCenterOf(h.absolutePos(CENTER.west(12)));
        h.assertFalse(ThermalShadow.visible(h.getLevel(), fireball, behind), "за стеной шар виден");
        h.assertTrue(ThermalShadow.visible(h.getLevel(), fireball, open), "на открытом месте шар не виден");
        h.succeed();
    }

    /**
     * Наземный подрыв 1 Мт, 1 блок = 15 м: чаша по профилю модели ±1 блок от природного грунта каждого столбца
     * (бугор в чаше углубляется от своей высоты, холм на валу не срезается), вокруг вал; воздушный подрыв воронки не даёт.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_crater", skyAccess = true)
    public static void groundBurstDigsCraterProfile(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Detonation d = detonation(h, CENTER, 0, 1000, 0.065f);
        double dry = d.blocks(CraterModel.radius(d.yieldKt(), CraterModel.Soil.DRY));
        // бугор из грунта в половине радиуса и холм из камня на валу
        BlockPos bump = CENTER.north(Mth.floor(dry * 0.5)), hill = CENTER.south(Mth.floor(dry * 1.6));
        for (int y = 0; y < 3; y++) h.setBlock(bump.above(y), Blocks.DIRT);
        for (int y = 0; y < 6; y++) h.setBlock(hill.above(y), Blocks.STONE);
        CraterJob job = new CraterJob(d, 0);
        RandomSource random = RandomSource.create(3);
        h.succeedWhen(() -> {
            // чанки площадки загружены, но воронка идёт по бюджету: копаем здесь, пока не закончит
            for (int i = 0; i < 5000 && job.step(level, random) == CraterJob.Step.PROGRESS; i++) {
            }
            h.assertTrue(job.step(level, random) == CraterJob.Step.DONE, "воронка ещё роется");
            CraterModel.Soil soil = job.soil();
            double radius = d.blocks(CraterModel.radius(d.yieldKt(), soil));
            for (double f : new double[]{0, 0.3}) {
                BlockPos p = h.absolutePos(CENTER.east(Mth.floor(radius * f)));
                assertSurface(h, d, soil, p, d.groundY(), "на " + f + " радиуса");
            }
            assertSurface(h, d, soil, h.absolutePos(bump), d.groundY() + 3, "на бугре");
            BlockPos hillTop = h.absolutePos(hill);
            h.assertTrue(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hillTop.getX(), hillTop.getZ()) >= hillTop.getY() + 6,
                    "холм на валу срезан");
            BlockPos rim = h.absolutePos(CENTER.east(Mth.floor(radius * 1.05)));
            h.assertTrue(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rim.getX(), rim.getZ()) >= d.groundY(), "нет вала за кромкой");
            h.assertFalse(CraterModel.formsCrater(Yield.optimalBurstHeight(15), 15), "воздушный подрыв на оптимальной высоте роет воронку");
        });
    }

    private static void assertSurface(GameTestHelper h, Detonation d, CraterModel.Soil soil, BlockPos p, double ground, String where) {
        double r = d.metres(Math.hypot(p.getX() + 0.5 - d.burst().x, p.getZ() + 0.5 - d.burst().z));
        int expected = Mth.floor(ground - d.blocks(CraterModel.profileDepth(r, d.yieldKt(), soil)));
        int actual = h.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        h.assertTrue(Math.abs(actual - expected) <= 1, "профиль воронки " + where + ": высота " + actual + ", ждали " + expected);
    }

    /**
     * Весь путь: подрыв 1 кт (1 блок = 10 м) → очередь по чанкам → стекло в 200 м выбито, чанк помечен номером
     * подрыва, воронка вырыта, и ни в одном тике обработка не вышла за бюджет (+1 мс).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_pipeline", skyAccess = true)
    public static void detonationRunsBudgetedQueue(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = CENTER.west(20);
        h.setBlock(glass, Blocks.GLASS);
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 1, false, null, 0.1f);
        long budget = (AirstrikeConfig.SERVER.nukeTimeBudgetMs.get() + 1) * 1_000_000L;
        h.succeedWhen(() -> {
            NuclearWorld w = NuclearWorld.get(level);
            // чанки на краю загруженного мира ждут соседей (иначе Sable догружал бы их на каждом блоке) — они в очереди
            h.assertTrue(w.craterJobs() == 0, "воронка ещё роется");
            h.assertBlockNotPresent(Blocks.GLASS, glass);
            int scar = level.getChunkAt(h.absolutePos(glass)).getData(ModAttachments.CHUNK_SCAR);
            h.assertTrue(scar >= d.id(), "чанк не помечен подрывом: " + scar + " < " + d.id());
            h.assertTrue(w.maxWorkNanos() <= budget, "обработка за тик " + w.maxWorkNanos() / 1e6 + " мс — больше бюджета");
            NuclearStrikes.clear(level);
        });
    }

    /** Подвал под тремя блоками камня: проникающая радиация ослаблена больше чем в 100 раз. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_shielding", skyAccess = true)
    public static void basementShieldsPromptRadiation(GameTestHelper h) {
        BlockPos cellar = CENTER.east(5);
        for (int i = 1; i <= 3; i++) h.setBlock(cellar.above(i), Blocks.STONE);
        Vec3 inside = Vec3.atCenterOf(h.absolutePos(cellar));
        Vec3 outside = Vec3.atCenterOf(h.absolutePos(CENTER.west(5)));
        Vec3 burst = inside.add(0, 50, 0);
        double open = NuclearWarhead.shielding(h.getLevel(), outside, outside.add(0, 50, 0));
        double roof = NuclearWarhead.shielding(h.getLevel(), inside, burst);
        h.assertTrue(open > 0.99, "на открытом месте есть экран: " + open);
        h.assertTrue(roof < 0.01, "три блока камня ослабили только до " + roof);
        h.assertTrue(PromptRadiationModel.shielding(3, 0, 0, 0, 0) < 0.01, "модель экранирования");
        h.succeed();
    }

    /** Подрыв и запланированный удар переживают сохранение (кодеки), отбой отменяет удар. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_schedule", skyAccess = true)
    public static void scheduledStrikeSavesAndClears(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        h.assertTrue(NuclearStrikes.launch(level, target, 15, true, null), "удалённый пуск не прошёл");
        List<NuclearEvents.ScheduledStrike> scheduled = NuclearEvents.get(level).scheduled();
        h.assertTrue(scheduled.size() == 1, "запланировано ударов: " + scheduled.size());
        NuclearEvents.ScheduledStrike s = scheduled.getFirst();
        var tag = NuclearEvents.ScheduledStrike.CODEC.encodeStart(NbtOps.INSTANCE, s).getOrThrow();
        h.assertTrue(NuclearEvents.ScheduledStrike.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().equals(s), "удар не пережил сохранение");
        Detonation d = detonation(h, CENTER, 300, 15, 0.1f);
        var dt = Detonation.CODEC.encodeStart(NbtOps.INSTANCE, d).getOrThrow();
        h.assertTrue(Detonation.CODEC.parse(NbtOps.INSTANCE, dt).getOrThrow().equals(d), "подрыв не пережил сохранение");
        NuclearStrikes.clear(level);
        h.assertTrue(NuclearEvents.get(level).scheduled().isEmpty(), "отбой не отменил удар");
        h.succeed();
    }

    /** МБР стартует у запустившего (в 30 блоках позади) и уходит вверх; удар записан в таймер. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_icbm", skyAccess = true)
    public static void icbmLiftsOffBehindLauncher(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 launcher = Vec3.atBottomCenterOf(h.absolutePos(CENTER.west(28)));
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER.west(28)).west(500));
        // смотрит на запад (yaw 90), к цели — ракета стартует в 30 блоках позади, к востоку, на площадке
        h.assertTrue(NuclearStrikes.launchFrom(level, target, 15, true, launcher, 90, null), "пуск не прошёл");
        h.assertTrue(NuclearEvents.get(level).scheduled().size() == 1, "удар не записан");
        // таймер отменяем сразу: полный подрыв в тестовом мире не нужен, ракета летит сама по себе
        NuclearStrikes.clear(level);
        List<IcbmEntity> icbms = level.getEntitiesOfClass(IcbmEntity.class, new net.minecraft.world.phys.AABB(launcher, launcher).inflate(64));
        h.assertTrue(icbms.size() == 1, "ракет у пусковой: " + icbms.size());
        IcbmEntity icbm = icbms.getFirst();
        h.assertTrue(icbm.getX() > launcher.x + 25, "ракета стартовала не позади: " + icbm.position());
        double startY = icbm.getY();
        h.succeedWhen(() -> h.assertTrue(icbm.isRemoved() || icbm.getY() > startY + 40, "ракета не набирает высоту: " + icbm.getY()));
    }
}
