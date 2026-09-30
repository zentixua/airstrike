package ua.zentix.airstrike.gametest;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.nuclear.model.CraterModel;
import ua.zentix.airstrike.nuclear.model.PromptRadiationModel;
import ua.zentix.airstrike.nuclear.model.Yield;
import ua.zentix.airstrike.nuclear.world.ColumnScar;
import ua.zentix.airstrike.nuclear.world.RuinPlan;
import ua.zentix.airstrike.nuclear.world.RuinPlanner;
import ua.zentix.airstrike.nuclear.world.CraterJob;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.nuclear.world.ThermalShadow;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.airstrike.nuclear.radiation.MobFallout;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
import ua.zentix.airstrike.registry.ModEffects;
import java.util.Comparator;

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
        scarAll(h, d, new ColumnScar.Budget(true));
    }

    private static void scarAll(GameTestHelper h, Detonation d, ColumnScar.Budget budget) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                h.assertTrue(RuinPlanner.plan(level, d, chunk).apply(level, chunk, budget), "свежий план устарел");
            }
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

    /**
     * Руины: башня из каменного кирпича 3×3×4 в 480 м от 15 кт у земли (1 блок = 40 м, ~30 psi) рушится, на её месте —
     * завал в блок высотой; карты высот чанка — по руинам (подмена секций целиком, без {@code setBlock} на каждый блок).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_ruins", skyAccess = true)
    public static void collapsedBuildingLeavesRubble(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos tower = CENTER.east(12);
        for (BlockPos p : BlockPos.betweenClosed(tower.offset(-1, 0, -1), tower.offset(1, 3, 1))) h.setBlock(p, Blocks.STONE_BRICKS);
        scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false));
        for (BlockPos p : BlockPos.betweenClosed(tower.offset(-1, 0, -1), tower.offset(1, 0, 1))) {
            BlockPos q = p.immutable();
            var rubble = h.getBlockState(q);
            h.assertTrue(rubble.is(Blocks.GRAVEL) || rubble.is(Blocks.COBBLESTONE) || rubble.is(Blocks.ANDESITE) || rubble.is(Blocks.TUFF),
                    "нет завала в " + q.toShortString() + ": " + rubble);
            for (int y = 1; y <= 3; y++) h.assertTrue(h.getBlockState(q.above(y)).isAir(), "башня стоит: " + q.above(y).toShortString());
            BlockPos abs = h.absolutePos(q);
            h.assertTrue(level.getHeight(Heightmap.Types.WORLD_SURFACE, abs.getX(), abs.getZ()) == abs.getY() + 1,
                    "карта высот не по руинам: " + level.getHeight(Heightmap.Types.WORLD_SURFACE, abs.getX(), abs.getZ()) + " вместо " + (abs.getY() + 1));
        }
        h.succeed();
    }

    /** План, после которого чанк меняли, не ставится: руины строятся заново по чанку как есть. */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_stale_plan", skyAccess = true)
    public static void stalePlanIsRebuilt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = CENTER.west(8);
        h.setBlock(glass, Blocks.GLASS);
        Detonation d = detonation(h, CENTER, 0, 15, 0.025f);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(glass));
        RuinPlan plan = RuinPlanner.plan(level, d, chunk);
        // игрок поставил стекло рядом уже после плана — в той же секции
        BlockPos added = glass.above();
        h.setBlock(added, Blocks.GLASS);
        ColumnScar.Budget budget = new ColumnScar.Budget(false);
        h.assertFalse(plan.apply(level, chunk, budget), "устаревший план поставлен");
        h.assertBlockPresent(Blocks.GLASS, glass);
        h.assertTrue(RuinPlanner.plan(level, d, chunk).apply(level, chunk, budget), "новый план не поставлен");
        h.assertBlockNotPresent(Blocks.GLASS, glass);
        h.assertBlockNotPresent(Blocks.GLASS, added);
        h.succeed();
    }

    /**
     * Руины заранее: пока летит МБР, чанки тяжёлой зоны готовятся; в момент подрыва их руины встают по плану, каждый
     * чанк — не позже чем через 2 тика после прихода фронта к нему (стекло в 16 блоках, 15 кт в воздухе, масштаб 0.02),
     * а тикеты подготовки отпускаются. Считающие часы: бюджет не зависит от машины CI.
     */
    @GameTest(template = "range", timeoutTicks = 900, batch = "nuke_prep", skyAccess = true)
    public static void ruinsPreparedDuringFlightFallWithFront(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = CENTER.west(16);
        h.setBlock(glass, Blocks.GLASS);
        double scale = AirstrikeConfig.SERVER.nukeEffectsScale.get();
        AirstrikeConfig.SERVER.nukeEffectsScale.set(0.02);
        NuclearWorld w = NuclearWorld.get(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        NuclearWorld.useClock(level.getServer(), clock);
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, true, now, now + 300, target, java.util.Optional.empty(), false));
        int[] plannedBefore = {-1};
        long[] glassGone = {-1};
        h.onEachTick(() -> {
            String failure = null;
            if (!events.scheduled().isEmpty()) plannedBefore[0] = w.plannedChunks();
            if (glassGone[0] < 0 && !h.getBlockState(glass).is(Blocks.GLASS)) glassGone[0] = level.getGameTime(); // на месте стекла бывает и пожар
            Detonation d = events.detonations().stream().filter(x -> x.burst().distanceTo(target) < 20).findFirst().orElse(null);
            if (d != null && glassGone[0] >= 0) {
                BlockPos g = h.absolutePos(glass);
                ChunkPos c = new ChunkPos(g);
                double x = Math.max(c.getMinBlockX(), Math.min(d.burst().x, c.getMaxBlockX() + 1)), z = Math.max(c.getMinBlockZ(), Math.min(d.burst().z, c.getMaxBlockZ() + 1));
                double slant = Math.sqrt((x - d.burst().x) * (x - d.burst().x) + (z - d.burst().z) * (z - d.burst().z) + Math.pow(d.burst().y - d.groundY(), 2));
                long due = d.gameTime() + (long) d.arrivalTicks(slant);
                if (plannedBefore[0] <= 0) failure = "руины не готовились во время полёта";
                else if (w.ruinStats()[0] == 0) failure = "ни один чанк не встал по готовому плану";
                else if (glassGone[0] > due + 2) failure = "руины отстали от фронта: стекло выбито через " + (glassGone[0] - due) + " тиков после прихода волны";
                else if (w.plannedChunks() > 0 || w.prepTiles() > 0) return; // ждём, пока подготовка отпустит тикеты
                else {
                    h.assertTrue(clock.maxUnitsPerTick() <= AirstrikeConfig.SERVER.nukeTimeBudgetMs.get(), "за тик " + clock.maxUnitsPerTick() + " единиц");
                    AirstrikeConfig.SERVER.nukeEffectsScale.set(scale);
                    NuclearWorld.useClock(level.getServer(), new WorkClock());
                    NuclearStrikes.clear(level);
                    h.succeed();
                    return;
                }
            }
            if (failure != null || level.getGameTime() > now + 850) {
                AirstrikeConfig.SERVER.nukeEffectsScale.set(scale);
                NuclearWorld.useClock(level.getServer(), new WorkClock());
                NuclearStrikes.clear(level);
                throw new net.minecraft.gametest.framework.GameTestAssertException(failure != null ? failure
                        : "подрыв не прошёл до конца: готово " + plannedBefore[0] + ", по плану " + w.ruinStats()[0] + ", осталось " + w.plannedChunks()
                        + ", квадратов " + w.prepTiles() + ", подрыв " + (d != null) + ", стекло " + glassGone[0]);
            }
        });
    }

    /** Забытый подрыв (чанк впервые загрузился спустя дни) выжигает, но не поджигает; свежий — поджигает. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_fires", skyAccess = true)
    public static void forgottenDetonationDoesNotIgnite(GameTestHelper h) {
        Detonation d = detonation(h, CENTER, 60, 15, 0.025f);
        scarAll(h, d, new ColumnScar.Budget(false));
        h.assertTrue(fires(h) == 0, "забытый подрыв поджёг: " + fires(h));
        scarAll(h, d, new ColumnScar.Budget(true));
        h.assertTrue(fires(h) > 0, "свежий подрыв не поджёг — проверка выше ничего не значит");
        h.succeed();
    }

    private static int fires(GameTestHelper h) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(63, 16, 63))) {
            if (h.getBlockState(p).is(BlockTags.FIRE)) n++;
        }
        return n;
    }

    /**
     * Выжигание не оставляет данных блок-сущностей там, где теперь воздух: ни у живой блок-сущности, ни у отложенной.
     * Отложенная — у чанка, который ещё не тикал: после генерации заглушка «DUMMY» (кровати, колокола, сундуки
     * деревни, {@code WorldGenRegion.setBlock}), после загрузки — сохранённые данные. Раньше замена блока снимала
     * только живую, отложенные данные уходили в сохранение, и чанк при загрузке писал «Tried to load a DUMMY block
     * entity … found air». Содержимое сундуков не высыпается ни у той, ни у другой, а неразвёрнутая добыча сундуков
     * генерации не разворачивается вовсе (карта исследователя в ней ищет сооружение в потоке сервера).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_block_entities", skyAccess = true)
    public static void scarLeavesNoOrphanBlockEntities(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos chest = CENTER.east(4), packedChest = CENTER.east(6), bell = CENTER.south(4), sign = CENTER.south(6);
        BlockPos lootChest = CENTER.west(4), packedLootBarrel = CENTER.west(6);
        h.setBlock(chest, Blocks.CHEST);
        h.setBlock(packedChest, Blocks.CHEST);
        h.setBlock(bell, Blocks.BELL);
        h.setBlock(sign, Blocks.OAK_SIGN);
        h.setBlock(lootChest, Blocks.CHEST);
        h.setBlock(packedLootBarrel, Blocks.BARREL);
        for (BlockPos p : List.of(chest, packedChest)) {
            if (h.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c) c.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
        }
        // неоткрытые сундук и бочка генерации: таблица добычи (в темнице она никогда не пуста) ещё не развёрнута
        for (BlockPos p : List.of(lootChest, packedLootBarrel)) {
            if (h.getBlockEntity(p) instanceof net.minecraft.world.RandomizableContainer c) c.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON, 1L);
        }
        // сундуки и бочка — как после загрузки чанка, который не тикал; колокол и табличка — как после генерации
        for (BlockPos p : List.of(packedChest, packedLootBarrel)) pend(level, h.absolutePos(p), level.getBlockEntity(h.absolutePos(p)).saveWithFullMetadata(level.registryAccess()));
        for (BlockPos p : List.of(bell, sign)) {
            BlockPos abs = h.absolutePos(p);
            net.minecraft.nbt.CompoundTag dummy = new net.minecraft.nbt.CompoundTag();
            dummy.putInt("x", abs.getX());
            dummy.putInt("y", abs.getY());
            dummy.putInt("z", abs.getZ());
            dummy.putString("id", "DUMMY");
            pend(level, abs, dummy);
        }

        scarAll(h, detonation(h, CENTER, 0, 15, 0.025f), new ColumnScar.Budget(false)); // без пожаров: на месте блоков — воздух

        for (BlockPos p : List.of(chest, packedChest, bell, sign, lootChest, packedLootBarrel)) h.assertTrue(h.getBlockState(p).isAir(), "не разрушено: " + h.getBlockState(p));
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                // то, что уйдёт в сохранение чанка (живые и отложенные), — только у блоков с блок-сущностью
                for (BlockPos p : chunk.getBlockEntitiesPos()) {
                    h.assertTrue(chunk.getBlockState(p).hasBlockEntity(), "данные блок-сущности у " + chunk.getBlockState(p) + " в " + p.toShortString());
                }
            }
        }
        h.assertEntityNotPresent(EntityType.ITEM);
        h.succeed();
    }

    /** Блок-сущность в {@code pos} — отложенными данными {@code tag}, как у чанка, который ещё не тикал. */
    private static void pend(ServerLevel level, BlockPos pos, net.minecraft.nbt.CompoundTag tag) {
        LevelChunk chunk = level.getChunkAt(pos);
        chunk.removeBlockEntity(pos);
        chunk.setBlockEntityNbt(tag);
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
     * Весь путь: подрыв 1 кт (1 блок = 10 м) → очередь по чанкам → стекло в 160 м выбито, чанк помечен номером
     * подрыва, воронка вырыта, и очередь держит бюджет тика. Бюджет проверяется на считающих часах
     * ({@link WorkClock#counting}: каждая единица работы — ровно 1 мс), а не по настенному времени: на общих машинах
     * CI любой столбец может затянуться из-за соседей по машине, и проверка падала бы не по вине очереди.
     * <p>
     * <p>
     * Срок — не проверка скорости: тест кончается, как только стекло выбито. До стекла очередь проходит по порядку
     * прихода волны десятки чанков по 256 столбцов, ~29 столбцов за тик — 100–200 тиков, и сколько чанков в радиусе
     * загружено (соседние площадки, фоновая генерация), от запуска к запуску разное; прежний срок в 200 тиков был
     * впритык. Стекло — в чанке, все соседи которого внутри площадки (x и z от 16 до 47 при любом выравнивании по
     * чанкам): площадку GameTest грузит сразу, а чанки за её краем догенерируются в фоне, и очередь ждала бы их.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_pipeline", skyAccess = true)
    public static void detonationRunsBudgetedQueue(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = CENTER.west(16);
        h.setBlock(glass, Blocks.GLASS);
        NuclearWorld w = NuclearWorld.get(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        NuclearWorld.useClock(level.getServer(), clock);
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 1, false, null, 0.1f);
        int budgetMs = AirstrikeConfig.SERVER.nukeTimeBudgetMs.get();
        h.succeedWhen(() -> {
            // чанки на краю загруженного мира ждут соседей (иначе Sable догружал бы их на каждом блоке) — они в очереди
            h.assertTrue(w.craterJobs() == 0, "воронка ещё роется");
            h.assertBlockNotPresent(Blocks.GLASS, glass);
            int scar = level.getChunkAt(h.absolutePos(glass)).getData(ModAttachments.CHUNK_SCAR);
            h.assertTrue(scar >= d.id(), "чанк не помечен подрывом: " + scar + " < " + d.id());
            h.assertTrue(clock.maxUnitsPerTick() <= budgetMs, "за тик " + clock.maxUnitsPerTick() + " единиц по 1 мс при бюджете " + budgetMs + " мс");
            h.assertTrue(clock.ticksWorked() > 1, "вся работа уместилась в один тик — бюджет не проверен");
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
        });
    }

    /**
     * Свет и проникающая радиация — не в тике подрыва, а под бюджетом, ближние первыми: сразу после подрыва
     * 40 коров в 300 м (1 кт, 1 блок = 10 м) ещё целы, потом импульс проходит по всем за несколько тиков, ни один
     * тик не выходит за бюджет (считающие часы, как в {@link #detonationRunsBudgetedQueue}). 31 кал/см² на открытом
     * месте — смертельные ожоги.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_pulse", skyAccess = true)
    public static void lightPulseRunsUnderBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<Cow> cows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            cows.add(h.spawn(EntityType.COW, CENTER.offset(Mth.floor(Math.cos(a) * 29), 0, Mth.floor(Math.sin(a) * 29))));
        }
        NuclearWorld w = NuclearWorld.get(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        NuclearWorld.useClock(level.getServer(), clock);
        NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 1, false, null, 0.1f);
        for (Cow cow : cows) h.assertTrue(cow.isAlive() && !cow.isOnFire() && cow.getHealth() == cow.getMaxHealth(), "подрыв тронул сущность в своём тике");
        h.assertTrue(w.pulseJobs() == 1, "импульс не поставлен в работу");
        int budgetMs = AirstrikeConfig.SERVER.nukeTimeBudgetMs.get();
        h.succeedWhen(() -> {
            h.assertTrue(w.pulseJobs() == 0, "импульс ещё идёт");
            long alive = cows.stream().filter(Cow::isAlive).count();
            h.assertTrue(alive == 0, "живых коров в 300 м: " + alive);
            h.assertTrue(clock.maxUnitsPerTick() <= budgetMs, "за тик " + clock.maxUnitsPerTick() + " единиц по 1 мс при бюджете " + budgetMs + " мс");
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
        });
    }

    /**
     * Перезапуск посреди волны: {@link NuclearWorld} не сохраняется, фронт по сущностям заводится заново — и отсчёт
     * у него от фронта прошлого тика, а не от эпицентра. 0.1 кт, 1 блок = 20 м: через 2 тика после подрыва фронт
     * между 22 и 40 блоками; корова в 10 блоках (5 psi — смерть, если ударить) им уже пройдена и второй раз
     * не бьётся, корову в 26 блоках (1.2 psi) волна ранит.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_restart_front", skyAccess = true)
    public static void frontAfterRestartSkipsEntitiesInside(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow inside = h.spawn(EntityType.COW, CENTER.east(10));
        Cow ahead = h.spawn(EntityType.COW, CENTER.east(26));
        BlockPos g = h.absolutePos(CENTER);
        Detonation d = new Detonation(1_000_000 + level.random.nextInt(1000), Vec3.atBottomCenterOf(g), g.getY(), 0.1, true,
                level.getGameTime() - 2, 0, 0, 20_000, 7, 0.05f, false);
        h.assertTrue(d.frontRadius(1) > 10 && d.frontRadius(2) > 26 && d.frontRadius(1) < 26, "фронт не там, где ждёт проверка");
        NuclearEvents.get(level).add(d);
        // как после перезапуска: ядерные очереди мира — с чистого листа, подрыв — из сохранения
        level.removeData(ModAttachments.NUCLEAR_WORLD);
        h.succeedWhen(() -> {
            h.assertTrue(ahead.getHealth() < ahead.getMaxHealth(), "волна не дошла до коровы впереди фронта");
            h.assertTrue(inside.isAlive() && inside.getHealth() == inside.getMaxHealth(), "фронт второй раз ударил корову, которую уже прошёл");
            NuclearStrikes.clear(level);
        });
    }

    /**
     * Волна по аппарату Sable (доски 5×2×5 над площадкой; 0.1 кт, 1 блок = 20 м — у аппарата ~8 psi): взрыв в его
     * ближайшей к эпицентру точке ломает доски аппарата и не трогает сущности (их волна бьёт сама, по давлению) —
     * предмет на земле рядом цел; ванильный взрыв уничтожил бы его.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_aircraft", skyAccess = true)
    public static void blastBreaksAircraftNotEntities(GameTestHelper h) {
        aircraftBlast(h, true);
    }

    /** Без {@code block_damage} взрыв по аппарату не ломает ничего: ни аппарат, ни стекло рядом с ним. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_aircraft_no_blocks", skyAccess = true)
    public static void aircraftBlastKeepsBlocksWithoutBlockDamage(GameTestHelper h) {
        aircraftBlast(h, false);
    }

    private static void aircraftBlast(GameTestHelper h, boolean blockDamage) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos from = CENTER.offset(6, 5, -2), to = from.offset(4, 1, 4);
        BlockPos.betweenClosed(from, to).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
        BlockPos glass = CENTER.offset(8, 0, -5);
        h.setBlock(glass, Blocks.GLASS);
        ItemEntity item = h.spawnItem(Items.STONE, CENTER.offset(8, 0, 5));
        BlockPos a = h.absolutePos(from), b = h.absolutePos(to);
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        Vec3 craft = Vec3.atCenterOf(h.absolutePos(from.offset(2, 0, 2)));
        SubLevelAccess[] sub = new SubLevelAccess[1];
        int[] planks = new int[1];
        boolean fires = AirstrikeConfig.SERVER.nukeFires.get();
        h.startSequence()
                .thenWaitUntil(() -> {
                    List<SubLevelAccess> near = SubLevels.near(level, craft, 8);
                    h.assertFalse(near.isEmpty(), "аппарат не собран");
                    sub[0] = near.getFirst();
                })
                .thenExecute(() -> {
                    planks[0] = aircraftPlanks(level, sub[0]);
                    h.assertTrue(planks[0] == 50, "в аппарате досок: " + planks[0]);
                    // пожары от света сожгли бы предмет на земле — проверяется только волна
                    AirstrikeConfig.SERVER.nukeFires.set(false);
                    AirstrikeConfig.SERVER.nukeBlockDamage.set(blockDamage);
                    NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 0.1, false, null, 0.05f);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    AirstrikeConfig.SERVER.nukeFires.set(fires);
                    AirstrikeConfig.SERVER.nukeBlockDamage.set(true);
                    NuclearStrikes.clear(level);
                    int left = aircraftPlanks(level, sub[0]);
                    if (blockDamage) {
                        h.assertTrue(left < planks[0], "волна не сломала аппарат: досок " + left);
                    } else {
                        h.assertTrue(left == planks[0], "без block_damage волна сломала аппарат: досок " + left);
                        h.assertBlockPresent(Blocks.GLASS, glass);
                    }
                    h.assertTrue(item.isAlive(), "взрыв по аппарату задел предмет на земле");
                })
                .thenSucceed();
    }

    /** Доски в плоте аппарата вокруг его центра. */
    private static int aircraftPlanks(ServerLevel level, SubLevelAccess sub) {
        BlockPos c = BlockPos.containing(SubLevels.toPlot(sub, SubLevels.center(sub)));
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -6, -6), c.offset(6, 6, 6))) {
            if (level.getBlockState(p).is(Blocks.OAK_PLANKS)) n++;
        }
        return n;
    }

    private static final TicketType<ChunkPos> HOLD = TicketType.create("airstrike_test_hold", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Полосы нетронутых чанков в зоне: у края видимости чанк опускается ниже полной загрузки и поднимается обратно,
     * не выгружаясь, — {@code ChunkEvent.Load} при этом не приходит. Чанк C полностью загружен при подрыве, но его
     * соседи нет (ждёт в очереди), потом он опускается и снова поднимается; чанк D при подрыве уже опущен (в снимке
     * подрыва его нет среди полностью загруженных) и поднимается после. Чанк E — край загруженного мира: он загружен
     * полностью всё время, его соседи — никогда (как шов между двумя стоянками игрока). Все три должны быть
     * разрушены и помечены. Держатели «ниже полной» — тикеты в двух чанках от них (уровень 35: в памяти, но не
     * загружен полностью).
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_stripes", skyAccess = true)
    public static void chunkDroppedBelowFullLoadStillScarred(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos c = new ChunkPos(h.absolutePos(CENTER.east(80))), dPos = new ChunkPos(h.absolutePos(CENTER.west(80)));
        ChunkPos e = new ChunkPos(h.absolutePos(CENTER.north(80)));
        ChunkPos cAnchor = new ChunkPos(c.x + 2, c.z), dAnchor = new ChunkPos(dPos.x - 2, dPos.z);
        BlockPos[] glass = new BlockPos[3];
        Detonation[] det = new Detonation[1];
        chunks.addRegionTicket(HOLD, cAnchor, 0, cAnchor);
        chunks.addRegionTicket(HOLD, dAnchor, 0, dAnchor);
        chunks.addRegionTicket(HOLD, c, 0, c);
        chunks.addRegionTicket(HOLD, dPos, 0, dPos);
        chunks.addRegionTicket(HOLD, e, 0, e);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(c.x, c.z) != null && chunks.getChunkNow(dPos.x, dPos.z) != null
                        && chunks.getChunkNow(e.x, e.z) != null, "чанки грузятся"))
                .thenExecute(() -> {
                    glass[0] = surface(level, c);
                    glass[1] = surface(level, dPos);
                    glass[2] = surface(level, e);
                    for (BlockPos g : glass) level.setBlock(g, Blocks.GLASS.defaultBlockState(), 3);
                    chunks.removeRegionTicket(HOLD, dPos, 0, dPos);
                })
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(dPos.x, dPos.z) == null, "D не опустился ниже полной загрузки"))
                .thenExecute(() -> {
                    // снимок подрыва взял C полностью загруженным; у C нет полностью загруженных соседей — он ждёт.
                    // Держатель снимается в том же тике: чанк опускается на следующем тике мира, раньше, чем до него
                    // дойдёт очередь (волна приходит к нему через несколько тиков). Дошедшая до ещё полного C очередь
                    // сама держит его и соседей (край загруженного мира), и тогда он уже не опустился бы: с паузой
                    // в 20 тиков тест зависел от того, успела ли очередь до C (на CI не всегда)
                    det[0] = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 15, true, null, 0.1f);
                    chunks.removeRegionTicket(HOLD, c, 0, c);
                })
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "C не опустился ниже полной загрузки"))
                // дольше повтора очереди (40 тиков): раньше здесь C выпадал из неё навсегда
                .thenIdle(60)
                .thenExecute(() -> {
                    chunks.addRegionTicket(HOLD, c, 1, c);
                    chunks.addRegionTicket(HOLD, dPos, 1, dPos);
                })
                .thenWaitUntil(() -> {
                    for (int i = 0; i < 3; i++) {
                        LevelChunk chunk = chunks.getChunkNow(glass[i].getX() >> 4, glass[i].getZ() >> 4);
                        String name = "CDE".substring(i, i + 1);
                        h.assertTrue(chunk != null, name + " не загрузился снова");
                        h.assertFalse(level.getBlockState(glass[i]).is(Blocks.GLASS), "стекло в " + name + " цело");
                        int scar = chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0);
                        h.assertTrue(scar >= det[0].id(), name + " не помечен подрывом: " + scar);
                    }
                })
                .thenExecute(() -> {
                    for (ChunkPos p : new ChunkPos[]{cAnchor, dAnchor}) chunks.removeRegionTicket(HOLD, p, 0, p);
                    for (ChunkPos p : new ChunkPos[]{c, dPos}) chunks.removeRegionTicket(HOLD, p, 1, p);
                    chunks.removeRegionTicket(HOLD, e, 0, e);
                    NuclearStrikes.clear(level);
                })
                .thenSucceed();
    }

    /** Верх земли в середине чанка (чанк загружен). */
    private static BlockPos surface(ServerLevel level, ChunkPos p) {
        int x = p.getMiddleBlockX(), z = p.getMiddleBlockZ();
        return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
    }

    /**
     * Мобы болеют, как игроки: 60 Гр — эффект болезни сразу, смерть через игровой час (1000 тиков), а не в момент
     * дозы; нежить не болеет; в следе осадков моб набирает дозу (порциями под бюджетом, {@link MobFallout}).
     */
    @GameTest(template = "range", timeoutTicks = 1300, batch = "nuke_mob_radiation", skyAccess = true)
    public static void mobsGetRadiationSickness(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow sick = h.spawn(EntityType.COW, CENTER.east(10));
        Cow grazing = h.spawn(EntityType.COW, CENTER.west(3));
        var zombie = h.spawn(EntityType.ZOMBIE, CENTER.north(10));
        h.assertTrue(RadiationTicker.affectsMob(sick), "корова не облучается");
        h.assertFalse(RadiationTicker.affectsMob(zombie), "нежить облучается");
        RadiationTicker.addDose(sick, 60);
        h.assertTrue(sick.hasEffect(ModEffects.RADIATION_SICKNESS), "у коровы с 60 Гр нет лучевой болезни");
        // наземный подрыв с осадками двумя игровыми часами раньше; корова у эпицентра — в самом следе
        BlockPos g = h.absolutePos(CENTER);
        Detonation d = new Detonation(1_000_000 + level.random.nextInt(1000), Vec3.atBottomCenterOf(g), g.getY(), 15, true,
                level.getGameTime() - 2000, 0, 5, 20_000, 7, 0.1f, true);
        MobFallout fallout = new MobFallout();
        WorkClock clock = WorkClock.counting(1_000_000L);
        long start = level.getGameTime();
        h.onEachTick(() -> {
            clock.start(AirstrikeConfig.SERVER.nukeTimeBudgetMs.get() * 1_000_000L);
            fallout.work(level, List.of(d), clock);
            if (level.getGameTime() - start < 900) h.assertTrue(sick.isAlive(), "моб с 60 Гр умер раньше срока болезни");
        });
        h.succeedWhen(() -> {
            h.assertTrue(RadiationTicker.dose(grazing).doseGy() > 0, "в следе осадков моб не набрал дозы");
            h.assertTrue(RadiationTicker.dose(zombie).doseGy() == 0, "нежить набрала дозу");
            h.assertFalse(sick.isAlive(), "моб с 60 Гр ещё жив");
        });
    }

    /**
     * Обход мобов, растянутый бюджетом дольше периода (очередь занята разрушениями: здесь по одной единице работы
     * за тик на сотни мобов), не теряет времени: прибавка дозы за обход — мощность осадков × всё время с прошлого
     * снимка. Раньше время обхода обрезалось двумя периодами, и в занятой очереди мобы недобирали дозу.
     */
    @GameTest(template = "range", timeoutTicks = 1400, batch = "nuke_mob_fallout_starved", skyAccess = true)
    public static void starvedFalloutPassKeepsFullDose(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow grazing = h.spawn(EntityType.COW, CENTER.west(3));
        for (int i = 0; i < 2 * MobFallout.PERIOD + 100; i++) h.spawn(EntityType.SHEEP, CENTER.offset(-20 + i % 41, 0, 5 + i / 41));
        BlockPos g = h.absolutePos(CENTER);
        Detonation d = new Detonation(1_000_000 + level.random.nextInt(1000), Vec3.atBottomCenterOf(g), g.getY(), 15, true,
                level.getGameTime() - 2000, 0, 5, 20_000, 7, 0.1f, true);
        MobFallout fallout = new MobFallout();
        WorkClock clock = WorkClock.counting(1_000_000L);
        // когда корова получала дозу (тик и прибавка): по двум соседним прибавкам виден обход целиком
        List<long[]> gains = new java.util.ArrayList<>();
        double[] last = {0};
        h.onEachTick(() -> {
            clock.start(1_000_000L); // одна единица работы за тик
            fallout.work(level, List.of(d), clock);
            double dose = RadiationTicker.dose(grazing).doseGy();
            if (dose > last[0]) gains.add(new long[]{level.getGameTime(), Double.doubleToLongBits(dose - last[0])});
            last[0] = dose;
        });
        h.succeedWhen(() -> {
            h.assertTrue(gains.size() >= 2, "обходов с дозой: " + gains.size());
            for (int i = 1; i < gains.size(); i++) {
                long t = gains.get(i)[0], ticks = t - gains.get(i - 1)[0];
                h.assertTrue(ticks > 2 * MobFallout.PERIOD, "обход короче двух периодов: " + ticks + " тиков — проверка ни о чём");
                double gain = Double.longBitsToDouble(gains.get(i)[1]);
                double expected = d.falloutRate(grazing.getX(), grazing.getZ(), t - d.gameTime())
                        * RadiationTicker.roofShielding(level, grazing.blockPosition()) * RadiationTicker.GY_PER_R * ticks / 1000.0;
                h.assertTrue(Math.abs(gain / expected - 1) < 0.05, "за обход " + ticks + " тиков доза " + gain + " Гр, ждали " + expected);
            }
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

    /** Подрыв и запланированный удар (по месту с карты — подрыв на поверхности) переживают сохранение, отбой отменяет удар. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_schedule", skyAccess = true)
    public static void scheduledStrikeSavesAndClears(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        h.assertTrue(NuclearStrikes.launch(level, target, true, 15, true, null), "удалённый пуск не прошёл");
        List<NuclearEvents.ScheduledStrike> scheduled = NuclearEvents.get(level).scheduled();
        h.assertTrue(scheduled.size() == 1, "запланировано ударов: " + scheduled.size());
        NuclearEvents.ScheduledStrike s = scheduled.getFirst();
        h.assertTrue(s.surface(), "удар по месту с карты не помечен как удар по поверхности");
        var tag = NuclearEvents.ScheduledStrike.CODEC.encodeStart(NbtOps.INSTANCE, s).getOrThrow();
        h.assertTrue(NuclearEvents.ScheduledStrike.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().equals(s), "удар не пережил сохранение");
        Detonation d = detonation(h, CENTER, 300, 15, 0.1f);
        var dt = Detonation.CODEC.encodeStart(NbtOps.INSTANCE, d).getOrThrow();
        h.assertTrue(Detonation.CODEC.parse(NbtOps.INSTANCE, dt).getOrThrow().equals(d), "подрыв не пережил сохранение");
        // забытый подрыв (осадки спали) по-прежнему разрушает чанки, загруженные позже; «Отбой» забывает и его
        NuclearEvents events = NuclearEvents.get(level);
        Detonation old = new Detonation(d.id(), d.burst(), d.groundY(), d.yieldKt(), d.surface(), level.getGameTime() - NuclearEvents.FORGET_AFTER - 1,
                d.windDir(), d.windSpeed(), d.visibility(), d.seed(), d.scale(), d.fallout());
        events.add(old);
        events.prune(level.getGameTime());
        h.assertFalse(events.detonations().contains(old), "старый подрыв не забыт");
        h.assertTrue(events.past().contains(old), "забытый подрыв не помнится для разрушений");
        h.assertTrue(events.isPast(old.id()), "забытый подрыв не узнаётся по номеру");
        NuclearStrikes.clear(level);
        h.assertTrue(NuclearEvents.get(level).scheduled().isEmpty(), "отбой не отменил удар");
        h.assertTrue(events.past().isEmpty(), "отбой не забыл прошлые подрывы");
        h.succeed();
    }

    /**
     * Место подрыва грузится с пуска, а не за 10 с до нуля: в следующем тике у чанка эпицентра уже есть тикет
     * загрузки (уровень 32 и ниже; без тика — ни тикета региона, ни тика блоков), и свежее место готово раньше,
     * чем кончится отсчёт, — сервер идёт в темпе игры, чтобы генерация успевала как в игре (облако 29.09.2026:
     * за 200 тиков до подрыва тикет стоял в очереди за районами залпов, отсчёт доходил до нуля, а подрыва не
     * было). Место только грузится: пока идёт отсчёт, у него нет тикета региона и блоки не тикают — и ещё
     * {@link #HELD} тиков после того, как готов квадрат 3 × 3, где район с тиком уже затикал бы. Отбой отпускает место.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "nuke_ground", skyAccess = true)
    public static void nukeGroundLoadsFromLaunch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER).offset(1500, 0, -1500));
        ChunkPos ground = new ChunkPos(BlockPos.containing(target));
        h.assertFalse(ua.zentix.airstrike.util.Terrain.ready(level, ground.x, ground.z), "место подрыва не свежее");
        h.assertTrue(NuclearStrikes.launchFrom(level, target, 15, true, null, 0, null), "пуск не прошёл");
        long detonate = NuclearEvents.get(level).scheduled().getFirst().detonateTime();
        java.util.UUID groundKey = new java.util.UUID(0L, NuclearEvents.get(level).scheduled().getFirst().id());
        int[] tick = {0}, readyAt = {-1}, aroundAt = {-1};
        h.onEachTick(() -> {
            tick[0]++;
            var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ground.toLong());
            String failure = null;
            if (tick[0] == 2 && (holder == null || holder.getTicketLevel() > 32)) {
                failure = "место подрыва не грузится с пуска: уровень " + (holder == null ? "-" : holder.getTicketLevel());
            }
            boolean ready = ua.zentix.airstrike.util.Terrain.ready(level, ground.x, ground.z);
            if (!ready && level.getGameTime() >= detonate) failure = "отсчёт кончился, а место подрыва не готово";
            // место только грузится: ни тикета региона, ни тика блоков
            if (!LifecycleGameTests.regionRadii(level, "airstrike_nuclear_ground", groundKey).isEmpty()) failure = "у места подрыва тикет региона";
            if (level.shouldTickBlocksAt(ground.toLong())) failure = "место подрыва тикает блоками";
            if (failure != null) {
                // удар не должен дожить до других тестов этого мира
                NuclearStrikes.clear(level);
                throw new net.minecraft.gametest.framework.GameTestAssertException(failure);
            }
            if (!ready) return;
            if (readyAt[0] < 0) {
                Airstrike.LOG.info("Место подрыва готово через {} тиков после пуска, отсчёт — {}", tick[0], detonate - level.getGameTime() + tick[0]);
                readyAt[0] = tick[0];
            }
            // район с тиком взял бы тикет региона, когда готов весь квадрат 3 × 3, — и центр затикал бы блоками:
            // проверка идёт ещё HELD тиков после этого
            if (!aroundReady(level, ground)) return;
            if (aroundAt[0] < 0) aroundAt[0] = tick[0];
            if (tick[0] - aroundAt[0] < HELD) return;
            NuclearStrikes.clear(level);
            h.assertTrue(NuclearEvents.get(level).scheduled().isEmpty(), "отбой не отменил удар");
            h.succeed();
        });
    }

    /** Сколько тиков место подрыва проверяется без тика после того, как готов квадрат 3 × 3 вокруг него. */
    private static final int HELD = 40;

    private static boolean aroundReady(ServerLevel level, ChunkPos centre) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if (!ua.zentix.airstrike.util.Terrain.ready(level, centre.x + dx, centre.z + dz)) return false;
        }
        return true;
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

    /**
     * Обычный отбой (у кого нет права на ядерное оружие) не отменяет ядерных ударов: МБР и её таймер, ракета с ядерной
     * БЧ на пусковой и вне мира остаются, обычные ракеты убраны. Ядерный отбой убирает всё.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_clear", skyAccess = true)
    public static void conventionalClearKeepsNuclearStrikes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 launcher = Vec3.atBottomCenterOf(h.absolutePos(CENTER.west(28)));
        Vec3 target = launcher.add(-500, 0, 0);
        h.assertTrue(NuclearStrikes.launchFrom(level, target, 15, true, launcher, 90, null), "пуск МБР не прошёл");
        IcbmEntity icbm = level.getEntitiesOfClass(IcbmEntity.class, new net.minecraft.world.phys.AABB(launcher, launcher).inflate(64)).getFirst();
        Loadout.Nuke warhead = new Loadout.Nuke(15, true, true);
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        CruiseMissileEntity nuclearOnRail = onRail(level, rail, target, warhead);
        CruiseMissileEntity conventionalOnRail = onRail(level, rail.add(0, 0, 4), target, null);
        CruiseMissileEntity nuclearVirtual = virtual(level, target.add(0, 60, 2000), target, warhead);
        CruiseMissileEntity conventionalVirtual = virtual(level, target.add(0, 60, -2000), target, null);
        LauncherEntity missileLauncher = LauncherEntity.create(level, rail.add(6, -1, 0), 0, WeaponType.MISSILE, null);
        LauncherEntity droneLauncher = LauncherEntity.create(level, rail.add(-6, -1, 0), 0, WeaponType.DRONE, null);
        level.addFreshEntity(missileLauncher);
        level.addFreshEntity(droneLauncher);

        ServerActions.clearAll(level.getServer(), false);
        h.assertFalse(missileLauncher.isRemoved(), "обычный отбой убрал пусковую из-под ракеты с ядерной БЧ");
        h.assertTrue(droneLauncher.isRemoved(), "обычный отбой не убрал пустую пусковую");
        h.assertTrue(!icbm.isRemoved() && NuclearEvents.get(level).scheduled().size() == 1, "обычный отбой отменил МБР");
        h.assertFalse(nuclearOnRail.isRemoved(), "обычный отбой убрал ракету с ядерной БЧ");
        h.assertTrue(VirtualFlights.get(level).flights().contains(nuclearVirtual), "обычный отбой убрал ядерную ракету вне мира");
        h.assertTrue(conventionalOnRail.isRemoved() && conventionalVirtual.isRemoved()
                && !VirtualFlights.get(level).flights().contains(conventionalVirtual), "обычный отбой не убрал обычные ракеты");

        ServerActions.clearAll(level.getServer(), true);
        h.assertTrue(icbm.isRemoved() && NuclearEvents.get(level).scheduled().isEmpty(), "ядерный отбой не отменил МБР");
        h.assertTrue(nuclearOnRail.isRemoved() && nuclearVirtual.isRemoved() && VirtualFlights.get(level).flights().isEmpty(),
                "ядерный отбой не убрал ракеты с ядерной БЧ");
        h.assertTrue(missileLauncher.isRemoved(), "ядерный отбой не убрал пусковую");
        h.succeed();
    }

    /** Крылатая ракета на направляющей: стоит до поджига. */
    private static CruiseMissileEntity onRail(ServerLevel level, Vec3 rail, Vec3 target, @Nullable Loadout.Nuke warhead) {
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(rail, 0, 40, 1000, 0, new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        m.setNuclear(warhead);
        level.addFreshEntity(m);
        return m;
    }

    /** Крылатая ракета в полёте вне мира. */
    private static CruiseMissileEntity virtual(ServerLevel level, Vec3 start, Vec3 target, @Nullable Loadout.Nuke warhead) {
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(start, new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        m.setNuclear(warhead);
        VirtualFlights.launch(level, m);
        return m;
    }
}
