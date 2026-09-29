package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.grid.BlackoutWorld;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.grid.ChunkLights;
import ua.zentix.airstrike.grid.GridLights;
import ua.zentix.airstrike.grid.Node;
import ua.zentix.airstrike.grid.PowerGrid;
import ua.zentix.airstrike.grid.SubstationBlock;
import ua.zentix.airstrike.grid.block.Unlit;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModBlocks;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Блэкаут: лампы гаснут двойниками и возвращаются точно такими же, каскад от выбитой подстанции идёт под бюджетом,
 * незагруженные чанки не грузятся (переводятся при загрузке), «вернуть свет везде» доходит и до них.
 * Каждый тест — в своей партии: сеть одна на мир. Перед каждым — свет, погашенный другими проверками (ядерные тесты
 * тоже обесточивают район), возвращается.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GridGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 12, 32);
    private static final TicketType<ChunkPos> HOLD = TicketType.create("airstrike_test_grid_hold", Comparator.comparingLong(ChunkPos::toLong));

    private GridGameTests() {}

    /** Отключения других проверок — на возврат, чтобы они не гасили площадку посреди этой. */
    private static void quiet(ServerLevel level) {
        Blackouts.restore(level, null, 0, false);
    }

    /**
     * Каждое состояние каждой лампы сети туда и обратно — то же состояние; у двойника нет света. На площадке —
     * лампы в «неудобных» состояниях (висящий фонарь под водой, стержень края на восток, лампа из красного камня под
     * сигналом, медная лампа под сигналом, блок света 7 под водой): после погашения и соседских обновлений двойники
     * те же, после возврата света — ровно прежние состояния.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_round_trip", skyAccess = true)
    public static void lampsRoundTripExactly(GameTestHelper h) {
        int pairs = 0;
        for (ModBlocks.UnlitPair pair : ModBlocks.UNLIT) {
            pairs++;
            Block lit = pair.lit().get(), unlit = pair.unlit().get();
            h.assertTrue(unlit instanceof Unlit u && u.lit() == lit, BuiltInRegistries.BLOCK.getKey(unlit) + " — не двойник " + lit);
            for (BlockState s : lit.getStateDefinition().getPossibleStates()) {
                BlockState off = GridLights.unlit(s);
                h.assertTrue(off.is(unlit), s + " гаснет в " + off);
                int emission = off.getLightEmission(h.getLevel(), h.absolutePos(CENTER));
                h.assertTrue(emission == 0, off + " светит " + emission);
                h.assertTrue(GridLights.lit(off) == s, s + " → " + off + " → " + GridLights.lit(off));
                h.assertTrue(off.getValues().equals(s.getValues()), "свойства " + s + " и " + off + " разные");
            }
        }
        h.assertTrue(pairs == 19, "ламп сети: " + pairs);

        ServerLevel level = h.getLevel();
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockPos row = new BlockPos(20, 12, 24);
        int[] x = {0};
        java.util.function.BiConsumer<BlockPos, BlockState> put = (rel, s) -> {
            h.setBlock(rel, s);
            placed.put(h.absolutePos(rel), h.getBlockState(rel));
        };
        java.util.function.Function<Integer, BlockPos> next = dy -> row.offset(x[0] += 2, dy, 0);
        BlockPos hanging = next.apply(1);
        h.setBlock(hanging.above(), Blocks.STONE);
        put.accept(hanging, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true).setValue(LanternBlock.WATERLOGGED, true));
        put.accept(next.apply(0), Blocks.SOUL_LANTERN.defaultBlockState());
        put.accept(next.apply(0), Blocks.SEA_LANTERN.defaultBlockState());
        put.accept(next.apply(0), Blocks.GLOWSTONE.defaultBlockState());
        put.accept(next.apply(0), Blocks.SHROOMLIGHT.defaultBlockState());
        put.accept(next.apply(0), Blocks.OCHRE_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        put.accept(next.apply(0), Blocks.VERDANT_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        put.accept(next.apply(0), Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
        put.accept(next.apply(0), Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.EAST));
        BlockPos lamp = next.apply(0);
        h.setBlock(lamp.above(), Blocks.REDSTONE_BLOCK);
        put.accept(lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
        put.accept(next.apply(0), Blocks.COPPER_BULB.defaultBlockState().setValue(CopperBulbBlock.LIT, true));
        BlockPos bulb = next.apply(0);
        h.setBlock(bulb.above(), Blocks.REDSTONE_BLOCK);
        put.accept(bulb, Blocks.WAXED_OXIDIZED_COPPER_BULB.defaultBlockState().setValue(CopperBulbBlock.LIT, true).setValue(CopperBulbBlock.POWERED, true));
        put.accept(next.apply(1), Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 7).setValue(BlockStateProperties.WATERLOGGED, true));

        List<ChunkPos> chunks = placed.keySet().stream().map(ChunkPos::new).distinct().toList();
        int dark = 0;
        for (ChunkPos c : chunks) dark += ChunkLights.apply(level, level.getChunk(c.x, c.z), true);
        h.assertTrue(dark == placed.size(), "погашено " + dark + " из " + placed.size());
        for (var e : placed.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == GridLights.unlit(e.getValue()), e.getValue() + " погас в " + now);
        }
        // соседи обновляются: лампа из красного камня под сигналом и медные лампы остаются погашенными как были
        for (BlockPos p : placed.keySet()) level.updateNeighborsAt(p, Blocks.STONE);
        for (var e : placed.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == GridLights.unlit(e.getValue()), "двойник " + e.getValue() + " изменился от соседей: " + now);
        }
        int light = 0;
        for (ChunkPos c : chunks) light += ChunkLights.apply(level, level.getChunk(c.x, c.z), false);
        h.assertTrue(light == placed.size(), "зажжено " + light + " из " + placed.size());
        for (var e : placed.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == e.getValue(), "было " + e.getValue() + ", стало " + now);
        }
        h.succeed();
    }

    /**
     * Подстанция — узел сети; взрыв рядом (без разрушений) её выбивает: она «сгорает», лампы площадки гаснут, ни один
     * тик не выходит за бюджет (считающие часы: единица работы = 1 мс). Свет по команде возвращается, подстанция снова
     * работает, лампы — прежние.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "grid_cascade", skyAccess = true)
    public static void struckSubstationBlacksOutAndRestores(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        Blackouts.useClock(level.getServer(), clock);
        h.setBlock(CENTER, ModBlocks.SUBSTATION.get());
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(CENTER));
        h.assertTrue(node != null && node.block(), "подстанция не стала узлом сети");
        // лампы — в чанках, чьи соседи тоже на площадке: к ним каскад приходит без ожидания соседей
        List<BlockPos> lamps = List.of(new BlockPos(18, 12, 18), new BlockPos(45, 12, 45), new BlockPos(32, 12, 20), new BlockPos(20, 12, 44));
        for (BlockPos p : lamps) h.setBlock(p, Blocks.LANTERN);
        int budgetMs = AirstrikeConfig.SERVER.gridTimeBudgetMs.get();
        h.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    Vec3 c = Vec3.atCenterOf(h.absolutePos(CENTER.east(3)));
                    level.explode(null, c.x, c.y, c.z, 2f, Level.ExplosionInteraction.NONE);
                    h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isPresent(), "взрыв не выбил подстанцию");
                })
                .thenWaitUntil(() -> {
                    h.assertFalse(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "подстанция выбита, но «под током»");
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(h.getBlockState(p)), "фонарь " + p + " горит");
                    h.assertTrue(level.getChunkAt(h.absolutePos(lamps.get(0))).hasData(ModAttachments.GRID_DARK), "чанк не отмечен тёмным");
                })
                .thenExecute(() -> {
                    h.assertTrue(clock.maxUnitsPerTick() <= budgetMs, "за тик " + clock.maxUnitsPerTick() + " единиц по 1 мс при бюджете " + budgetMs + " мс");
                    h.assertTrue(clock.ticksWorked() > 1, "вся работа уместилась в один тик — бюджет не проверен");
                    int[] r = Blackouts.restore(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 8, false);
                    h.assertTrue(r[0] >= 1, "возвращать нечего");
                })
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertBlockState(p, s -> s == Blocks.LANTERN.defaultBlockState(), () -> "фонарь " + p + " не зажёгся прежним");
                    h.assertTrue(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "подстанция не заработала");
                    h.assertFalse(level.getChunkAt(h.absolutePos(lamps.get(0))).hasData(ModAttachments.GRID_DARK), "отметка тёмного чанка осталась");
                })
                .thenExecute(() -> Blackouts.useClock(level.getServer(), new WorkClock()))
                .thenSucceed();
    }

    /**
     * Чанк вне загруженного мира каскад не грузит: он гаснет, когда его загрузят. Свет вернули — при следующей загрузке
     * он горит.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_lazy", skyAccess = true)
    public static void unloadedChunkDarkensOnLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.east(480)));
        BlockPos[] lamp = new BlockPos[1];
        chunks.addRegionTicket(HOLD, far, 2, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится"))
                .thenExecute(() -> {
                    lamp[0] = surface(level, far);
                    level.setBlock(lamp[0], Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "каскад идёт"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "каскад загрузил чанк");
                    chunks.addRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(ModBlocks.UNLIT.get(0).unlit().get()),
                        "светокамень в загруженном чанке горит: " + level.getBlockState(lamp[0])))
                .thenExecute(() -> Blackouts.restore(level, Vec3.atCenterOf(lamp[0]), 8, false))
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(Blocks.GLOWSTONE), "свет не вернулся"))
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenSucceed();
    }

    /**
     * «Вернуть свет везде» (перед удалением мода): погашенный чанк вне загруженного мира загружается, лампы в нём
     * зажигаются и сохраняются на диск — после всего, без отключений и отметок, загруженный снова чанк светит.
     */
    @GameTest(template = "range", timeoutTicks = 4000, batch = "grid_restore_all", skyAccess = true)
    public static void restoreEverywhereReachesUnloadedChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.west(480)));
        BlockPos[] lamp = new BlockPos[1];
        BlockState lantern = Blocks.SOUL_LANTERN.defaultBlockState();
        chunks.addRegionTicket(HOLD, far, 2, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится"))
                .thenExecute(() -> {
                    lamp[0] = surface(level, far);
                    level.setBlock(lamp[0], lantern, Block.UPDATE_ALL);
                    Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp[0])), "фонарь не погас"))
                .thenExecute(() -> {
                    h.assertTrue(PowerGrid.get(level).darkChunks().contains(far.toLong()), "чанка нет в указателе тёмных");
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    int[] r = Blackouts.restore(level, null, 0, true);
                    h.assertTrue(r[1] >= 1, "незагруженных тёмных чанков к загрузке: " + r[1]);
                })
                .thenWaitUntil(() -> {
                    PowerGrid grid = PowerGrid.get(level);
                    h.assertFalse(grid.darkChunks().contains(far.toLong()), "чанк ещё в указателе тёмных");
                    h.assertTrue(grid.outages().isEmpty(), "отключения ещё не кончились: " + grid.outages().size());
                    h.assertTrue(BlackoutWorld.get(level).idle(), "блэкаут ещё работает: " + java.util.Arrays.toString(BlackoutWorld.get(level).backlog())
                            + " каскады " + BlackoutWorld.get(level).sweeping());
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "чанк держится после возврата света");
                })
                .thenExecute(() -> chunks.addRegionTicket(HOLD, far, 2, far))
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится снова"))
                .thenExecute(() -> {
                    BlockState s = level.getBlockState(lamp[0]);
                    h.assertTrue(s == lantern, "на диске остался " + s);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * Ждать выгрузки чанка. Сервер GameTest тикает без пауз, и «лишнего времени» у него нет никогда, а выгрузку
     * (очередь {@code unloadQueue}) ваниль делает только в оставшееся время тика — как делает любой живой сервер.
     * Здесь это время ему даётся: один проход выгрузки за шаг ожидания.
     */
    private static void unloaded(GameTestHelper h, ServerLevel level, ChunkPos p) {
        level.getChunkSource().tick(() -> true, false);
        var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(p.toLong());
        h.assertTrue(holder == null, "чанк не выгрузился: уровень " + (holder == null ? 0 : holder.getTicketLevel()));
    }

    /** Верх земли в середине чанка (чанк загружен). */
    private static BlockPos surface(ServerLevel level, ChunkPos p) {
        int x = p.getMiddleBlockX(), z = p.getMiddleBlockZ();
        return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
    }
}
