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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
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
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.nuclear.world.ThermalShadow;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.List;
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
     * entity … found air». Содержимое сундуков не высыпается ни у той, ни у другой.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_block_entities", skyAccess = true)
    public static void scarLeavesNoOrphanBlockEntities(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos chest = CENTER.east(4), packedChest = CENTER.east(6), bell = CENTER.south(4), sign = CENTER.south(6);
        h.setBlock(chest, Blocks.CHEST);
        h.setBlock(packedChest, Blocks.CHEST);
        h.setBlock(bell, Blocks.BELL);
        h.setBlock(sign, Blocks.OAK_SIGN);
        for (BlockPos p : List.of(chest, packedChest)) {
            if (h.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c) c.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
        }
        // сундук — как после загрузки чанка, который не тикал; колокол и табличка — как после генерации
        pend(level, h.absolutePos(packedChest), level.getBlockEntity(h.absolutePos(packedChest)).saveWithFullMetadata(level.registryAccess()));
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

        for (BlockPos p : List.of(chest, packedChest, bell, sign)) h.assertTrue(h.getBlockState(p).isAir(), "не разрушено: " + h.getBlockState(p));
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
                .thenExecute(() -> det[0] = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 15, true, null, 0.1f))
                // снимок и очередь успели взять C; у C нет полностью загруженных соседей — он ждёт
                .thenIdle(20)
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, c, 0, c))
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
