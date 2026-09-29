package ua.zentix.airstrike.gametest;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.grid.BlackoutWorld;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.grid.ChunkLights;
import ua.zentix.airstrike.grid.ChunkSaves;
import ua.zentix.airstrike.grid.GridLights;
import ua.zentix.airstrike.grid.Node;
import ua.zentix.airstrike.grid.PowerGrid;
import ua.zentix.airstrike.grid.SubstationBlock;
import ua.zentix.airstrike.grid.block.Unlit;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.world.NuclearTickets;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModBlocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Блэкаут: лампы гаснут двойниками и возвращаются точно такими же, каскад от выбитой подстанции идёт под бюджетом,
 * незагруженные чанки не грузятся (переводятся при загрузке), на диск двойники не попадают никогда.
 * Каждый тест — в своей партии: сеть одна на мир. Перед каждым — свет, погашенный другими проверками (ядерные тесты
 * тоже обесточивают район), возвращается.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GridGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 12, 32);
    private static final TicketType<ChunkPos> HOLD = TicketType.create("airstrike_test_grid_hold", Comparator.comparingLong(ChunkPos::toLong));

    private GridGameTests() {}

    /**
     * Отключения других проверок — свет во всех кварталах сейчас, чтобы они не гасили площадку посреди этой
     * (с разбросом команды квартал площадки оставался бы тёмным до 10 с — дольше срока иных проверок).
     */
    private static void quiet(ServerLevel level) {
        Blackouts.restore(level, null, 0, 0);
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
        // в чанке середины площадки (он весь на ней)
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        Map<BlockPos, BlockState> placed = lampCases(level, new BlockPos(mid.getMinBlockX() + 3, h.absolutePos(CENTER).getY() + 1, mid.getMinBlockZ() + 3));
        List<ChunkPos> chunks = placed.keySet().stream().map(ChunkPos::new).distinct().toList();
        h.assertTrue(chunks.size() == 1, "лампы в " + chunks.size() + " чанках");
        // единица работы — не больше заданного числа ламп, остальное — следующей
        LevelChunk chunk = level.getChunk(chunks.get(0).x, chunks.get(0).z);
        int dark = ChunkLights.apply(level, chunk, true, 5);
        h.assertTrue(dark == 5, "за единицу погашено " + dark + " вместо 5");
        dark += ChunkLights.apply(level, chunk, true);
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
     * Все 19 ламп сети в «неудобных» состояниях — висящий фонарь под водой, жабосветы по осям, стержень края
     * на восток, лампа из красного камня и медная под сигналом, блок света 7 под водой — двумя рядами по x
     * от {@code origin} (10 × 3 блока). Возвращает поставленное: позиция → состояние.
     */
    private static Map<BlockPos, BlockState> lampCases(ServerLevel level, BlockPos origin) {
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        List<BlockState> states = new ArrayList<>(List.of(
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true).setValue(LanternBlock.WATERLOGGED, true),
                Blocks.SOUL_LANTERN.defaultBlockState(),
                Blocks.SEA_LANTERN.defaultBlockState(),
                Blocks.GLOWSTONE.defaultBlockState(),
                Blocks.SHROOMLIGHT.defaultBlockState(),
                Blocks.OCHRE_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X),
                Blocks.VERDANT_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z),
                Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(),
                Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.EAST),
                Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true),
                Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 7).setValue(BlockStateProperties.WATERLOGGED, true)));
        Block[] bulbs = {Blocks.COPPER_BULB, Blocks.EXPOSED_COPPER_BULB, Blocks.WEATHERED_COPPER_BULB, Blocks.OXIDIZED_COPPER_BULB,
                Blocks.WAXED_COPPER_BULB, Blocks.WAXED_EXPOSED_COPPER_BULB, Blocks.WAXED_WEATHERED_COPPER_BULB, Blocks.WAXED_OXIDIZED_COPPER_BULB};
        for (int i = 0; i < bulbs.length; i++) {
            // через одну — под сигналом
            states.add(bulbs[i].defaultBlockState().setValue(CopperBulbBlock.LIT, true).setValue(CopperBulbBlock.POWERED, i % 2 == 1));
        }
        for (int i = 0; i < states.size(); i++) {
            BlockState s = states.get(i);
            BlockPos p = origin.offset(i % 10, 0, i / 10 * 3);
            // опора висящему фонарю, сигнал лампе из красного камня и медным «под сигналом»
            boolean powered = s.is(Blocks.REDSTONE_LAMP) || s.hasProperty(CopperBulbBlock.POWERED) && s.getValue(CopperBulbBlock.POWERED);
            if (s.is(Blocks.LANTERN) || powered) level.setBlock(p.above(), (powered ? Blocks.REDSTONE_BLOCK : Blocks.STONE).defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(p, s, Block.UPDATE_ALL);
            placed.put(p, level.getBlockState(p));
        }
        for (var e : placed.entrySet()) {
            if (!GridLights.isLit(e.getValue())) throw new IllegalStateException("не лампа сети: " + e.getValue() + " в " + e.getKey());
        }
        if (placed.size() != 19) throw new IllegalStateException("ламп " + placed.size());
        return placed;
    }

    /**
     * Лампа из красного камня и медная лампа, у которых в темноте пропал или появился сигнал, после возврата света
     * ведут себя, как от обновления соседа: первая гаснет, вторая переключается.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "grid_signal", skyAccess = true)
    public static void restoredLampsFollowSignalChangedInTheDark(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        // обе — в чанке середины площадки (он весь на ней)
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        BlockPos lamp = new BlockPos(mid.getMinBlockX() + 4, h.absolutePos(CENTER).getY() + 1, mid.getMinBlockZ() + 4), bulb = lamp.east(4);
        level.setBlock(lamp.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_ALL);
        level.setBlock(bulb, Blocks.COPPER_BULB.defaultBlockState(), Block.UPDATE_ALL);
        h.assertTrue(level.getBlockState(bulb).is(Blocks.COPPER_BULB) && !level.getBlockState(bulb).getValue(CopperBulbBlock.LIT), "медная лампа не та");
        LevelChunk chunk = level.getChunkAt(lamp);
        h.assertTrue(ChunkLights.apply(level, chunk, true) == 2, "лампы не погасли");
        // в темноте: у лампы из красного камня сигнал пропал, медной — появился
        level.setBlock(lamp.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(bulb.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp)) && GridLights.isUnlit(level.getBlockState(bulb)), "двойники сменились от соседей");
        h.assertTrue(ChunkLights.apply(level, chunk, false) == 2, "лампы не зажглись");
        h.startSequence()
                .thenWaitUntil(() -> {
                    BlockState l = level.getBlockState(lamp), b = level.getBlockState(bulb);
                    h.assertTrue(l.is(Blocks.REDSTONE_LAMP) && !l.getValue(RedstoneLampBlock.LIT), "лампа без сигнала горит: " + l);
                    h.assertTrue(b.is(Blocks.COPPER_BULB) && b.getValue(CopperBulbBlock.LIT) && b.getValue(CopperBulbBlock.POWERED),
                            "медная лампа не заметила сигнал: " + b);
                })
                .thenSucceed();
    }

    /**
     * Погашенная лампа даёт ту же добычу, что лампа (у двойника нет своей таблицы): те же предметы киркой и киркой
     * с шёлковым касанием, и разбитая в мире — выпадает.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_loot", skyAccess = true)
    public static void brokenTwinDropsTheLampsLoot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(CENTER);
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE), silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
        for (ModBlocks.UnlitPair pair : ModBlocks.UNLIT) {
            BlockState lamp = pair.lit().get().defaultBlockState(), twin = GridLights.unlit(lamp);
            h.assertTrue(twin.getBlock().getLootTable() == lamp.getBlock().getLootTable(),
                    "таблица добычи " + twin + ": " + twin.getBlock().getLootTable().location());
            for (ItemStack tool : List.of(pickaxe, silk)) {
                var want = Block.getDrops(lamp, level, at, null, null, tool).stream().map(ItemStack::getItem).collect(Collectors.toSet());
                var got = Block.getDrops(twin, level, at, null, null, tool).stream().map(ItemStack::getItem).collect(Collectors.toSet());
                h.assertTrue(want.equals(got), twin + " даёт " + got + ", лампа — " + want + " (" + tool + ")");
            }
        }
        Map<Block, Item> drops = Map.of(Blocks.LANTERN, Items.LANTERN, Blocks.OCHRE_FROGLIGHT, Items.OCHRE_FROGLIGHT,
                Blocks.REDSTONE_LAMP, Items.REDSTONE_LAMP, Blocks.WAXED_COPPER_BULB, Items.WAXED_COPPER_BULB);
        int i = 0;
        for (var e : drops.entrySet()) {
            BlockPos p = new BlockPos(16 + 8 * i++, 13, 16);
            h.setBlock(p, GridLights.unlit(e.getKey().defaultBlockState()));
            level.destroyBlock(h.absolutePos(p), true);
            h.assertItemEntityPresent(e.getValue(), p, 2);
        }
        h.succeed();
    }

    /**
     * Двойник не от блэкаута в светлом квартале снова лампа: поставленный в мир (поршень, аппарат) — сразу, а в чанке
     * с диска (сохранение старой версии) — ещё в палитре, до того как чанк станет частью мира.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "grid_stray", skyAccess = true)
    public static void strayTwinRelightsInLitDistrict(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        BlockPos moved = new BlockPos(20, 13, 20), legacy = new BlockPos(24, 13, 20);
        BlockState froglight = Blocks.VERDANT_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        h.setBlock(moved, GridLights.unlit(froglight));
        LevelChunk chunk = level.getChunkAt(h.absolutePos(legacy));
        BlockPos abs = h.absolutePos(legacy);
        chunk.getSection(chunk.getSectionIndex(abs.getY())).setBlockState(abs.getX() & 15, abs.getY() & 15, abs.getZ() & 15,
                GridLights.unlit(Blocks.GLOWSTONE.defaultBlockState()), false);
        ChunkSaves.onLoad(new ChunkDataEvent.Load(chunk, new CompoundTag(), ChunkType.LEVELCHUNK));
        h.assertTrue(level.getBlockState(abs).is(Blocks.GLOWSTONE), "двойник из сохранения остался: " + level.getBlockState(abs));
        h.startSequence()
                .thenWaitUntil(() -> h.assertBlockState(moved, s -> s == froglight, () -> "сдвинутый двойник не зажёгся: " + h.getBlockState(moved)))
                .thenSucceed();
    }

    /**
     * Аппарат Sable, собранный в тёмном квартале, уносит погашенные лампы в свой плот — не чанк мира: очередь
     * блэкаута его не видит, и сохраняет его Sable своим кодеком, мимо {@code ChunkDataEvent.Save}. У аппарата своё
     * питание: двойники в плоте снова лампы — в том же тике, что и сборка (обновление соседа), до любого сохранения.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_sable", skyAccess = true)
    public static void twinsCarriedIntoSableShipRelight(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        var server = level.getServer();
        quiet(level);
        BlockPos from = CENTER.offset(-2, 3, -2), to = from.offset(4, 0, 4);
        BlockPos sea = from.offset(1, 1, 1), lantern = from.offset(3, 1, 3);
        // квартал сперва тёмный: двойники, поставленные в нём, сами не зажигаются
        Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 200, 1000, -1);
        BlockPos a = h.absolutePos(from), b = h.absolutePos(lantern);
        Vec3 craft = Vec3.atCenterOf(h.absolutePos(from.offset(2, 0, 2)));
        SubLevelAccess[] sub = new SubLevelAccess[1];
        int[] assembled = {0};
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(PowerGrid.get(level).dark(a.getX() >> 4, a.getZ() >> 4, level.getGameTime())
                        && PowerGrid.get(level).dark(b.getX() >> 4, b.getZ() >> 4, level.getGameTime()), "квартал ещё светлый"))
                .thenExecute(() -> {
                    BlockPos.betweenClosed(from, to).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
                    h.setBlock(sea, GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()));
                    h.setBlock(lantern, GridLights.unlit(Blocks.LANTERN.defaultBlockState()));
                })
                .thenIdle(5)
                // сразу после обхода плотов: до следующего обхода — PLOT_SCAN тиков, зажечь двойников может только сборка
                .thenWaitUntil(() -> h.assertTrue(server.getTickCount() % BlackoutWorld.PLOT_SCAN == 1, "ждём тик после обхода плотов"))
                .thenExecute(() -> {
                    h.assertTrue(GridLights.isUnlit(h.getBlockState(sea)), "двойник в тёмном квартале зажёгся: " + h.getBlockState(sea));
                    assembled[0] = server.getTickCount();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                            String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                                    Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
                })
                .thenWaitUntil(() -> {
                    List<SubLevelAccess> near = SubLevels.near(level, craft, 8);
                    h.assertFalse(near.isEmpty(), "аппарат не собран");
                    sub[0] = near.getFirst();
                })
                .thenExecute(() -> {
                    h.assertTrue(server.getTickCount() - assembled[0] < BlackoutWorld.PLOT_SCAN - 1, "аппарат найден уже после обхода плотов — проверять нечего");
                    // обхода плотов ещё не было: двойники зажглись от сборки (обновление соседа), раньше любого сохранения
                    int[] lamps = plotLamps(level, sub[0]);
                    h.assertTrue(lamps[0] == 2 && lamps[1] == 0, "до обхода плотов в плоте горит " + lamps[0] + ", погашено " + lamps[1]);
                })
                .thenIdle(BlackoutWorld.PLOT_SCAN + 1)
                .thenExecute(() -> {
                    int[] lamps = plotLamps(level, sub[0]);
                    h.assertTrue(lamps[0] == 2 && lamps[1] == 0, "в плоте аппарата горит " + lamps[0] + ", погашено " + lamps[1]);
                })
                .thenExecute(() -> Blackouts.restore(level, null, 0))
                .thenSucceed();
    }

    /** Лампы вокруг центра плота аппарата: {горящих, погашенных}. */
    private static int[] plotLamps(ServerLevel level, SubLevelAccess sub) {
        BlockPos c = BlockPos.containing(SubLevels.toPlot(sub, SubLevels.center(sub)));
        int lit = 0, dark = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -6, -6), c.offset(6, 6, 6))) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.SEA_LANTERN) || st.is(Blocks.LANTERN)) lit++;
            if (GridLights.isUnlit(st)) dark++;
        }
        return new int[]{lit, dark};
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
                    // порыв заряда ветра (радиус 1.2, «задеть блоки») — не взрыв для подстанции
                    level.explode(null, null, null, c.x, c.y, c.z, 1.2f, false, Level.ExplosionInteraction.TRIGGER,
                            ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE, SoundEvents.WIND_CHARGE_BURST);
                    h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isEmpty(), "заряд ветра выбил подстанцию");
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
                    h.assertTrue(Blackouts.restore(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 8) >= 1, "возвращать нечего");
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
     * Ядерный удар гасит свой радиус и выбивает подстанции в нём: подстанция не «под током» (не гудит, искрит), пока
     * свет не вернут; после возврата — лампы прежние, подстанция работает.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "grid_nuke", skyAccess = true)
    public static void nukeBlacksOutAndKnocksOutSubstations(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        h.setBlock(CENTER, ModBlocks.SUBSTATION.get());
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(CENTER));
        h.assertTrue(node != null && node.block(), "подстанция не стала узлом сети");
        List<BlockPos> lamps = List.of(new BlockPos(18, 12, 18), new BlockPos(45, 12, 45));
        for (BlockPos p : lamps) h.setBlock(p, Blocks.LANTERN);
        // воздушный подрыв в стороне от подстанции (как в ядерных проверках: 15 кт, масштаб 0.1); сам подрыв здесь не нужен
        BlockPos g = h.absolutePos(CENTER.east(8));
        Detonation d = new Detonation(2_000_000 + level.random.nextInt(1000), new Vec3(g.getX() + 0.5, g.getY() + 300, g.getZ() + 0.5), g.getY(),
                15, false, level.getGameTime(), 0, 0, 20_000, 7, 0.1f, false);
        h.assertTrue(d.radiusMax() > 64, "радиус подрыва " + d.radiusMax() + " не накрывает площадку");
        Blackouts.nuke(level, d);
        h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isPresent(), "ядерный удар не выбил подстанцию");
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertFalse(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "подстанция в радиусе подрыва «под током»");
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(h.getBlockState(p)), "фонарь " + p + " горит");
                })
                .thenExecute(() -> h.assertTrue(Blackouts.restore(level, Vec3.atCenterOf(g), 64) == 2, "возвращать не в двух отключениях"))
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertBlockState(p, s -> s == Blocks.LANTERN.defaultBlockState(), () -> "фонарь " + p + " не зажёгся прежним");
                    h.assertTrue(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "подстанция не заработала");
                })
                .thenSucceed();
    }

    /**
     * Лампа, поставленная в тёмном квартале, когда каскад уже прошёл, гаснет — по событию постановки (сама по себе
     * она не погасла бы: каскад по её чанку больше не придёт).
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_placed", skyAccess = true)
    public static void lampPlacedInDarkDistrictGoesOut(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        BlockPos first = new BlockPos(18, 12, 18), placed = new BlockPos(22, 12, 22);
        h.setBlock(first, Blocks.LANTERN);
        Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 200, 1000, -1);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(h.getBlockState(first)), "фонарь " + first + " горит"))
                .thenExecute(() -> h.setBlock(placed, Blocks.LANTERN))
                .thenIdle(10)
                .thenExecute(() -> {
                    h.assertBlockState(placed, s -> s.is(Blocks.LANTERN), () -> "фонарь погас без события: " + h.getBlockState(placed));
                    BlockPos abs = h.absolutePos(placed);
                    Blackouts.onBlockPlaced(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, abs), Blocks.STONE.defaultBlockState(), null));
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(h.getBlockState(placed)), "поставленный фонарь горит"))
                .thenSucceed();
    }

    /**
     * Перезапуск посреди каскада: очередь блэкаута не сохраняется (несохраняемый attachment мира) — каскад идёт
     * заново по сохранённой сети, и район гаснет, как без перезапуска.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "grid_restart", skyAccess = true)
    public static void cascadeResumesAfterRestart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        List<BlockPos> lamps = List.of(new BlockPos(18, 12, 18), new BlockPos(45, 12, 45));
        for (BlockPos p : lamps) h.setBlock(p, Blocks.LANTERN);
        // медленный каскад (10 блоков в секунду); память блэкаута — как у только что загруженного мира
        Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 200, 0.5, -1);
        level.setData(ModAttachments.BLACKOUT_WORLD, new BlackoutWorld());
        h.startSequence()
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(h.getBlockState(p)), "фонарь " + p + " горит");
                })
                .thenExecute(() -> Blackouts.restore(level, null, 0, 0))
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertBlockState(p, s -> s == Blocks.LANTERN.defaultBlockState(), () -> "фонарь " + p + " не зажёгся прежним");
                })
                .thenSucceed();
    }

    /**
     * Чанк, сохранённый светлым, в тёмном квартале: каскад его не грузит, он гаснет при загрузке — все 19 ламп ровно
     * двойниками, и свет посчитан по погашенным. Свет вернули — при следующей загрузке он горит прежними лампами.
     */
    @GameTest(template = "range", timeoutTicks = 3600, batch = "grid_lazy", skyAccess = true)
    public static void unloadedChunkDarkensOnLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.east(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockPos[] glow = new BlockPos[1];
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится"))
                .thenExecute(() -> {
                    placed.putAll(lampCases(level, caseOrigin(level, far)));
                    glow[0] = find(placed, Blocks.GLOWSTONE);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    assertNoTwinsOnDisk(h, level, far);
                    Blackouts.blackout(level, Vec3.atCenterOf(glow[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "каскад идёт"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "каскад загрузил чанк");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится");
                    assertLamps(h, level, placed, true);
                    assertBlockLight(h, level, glow[0].above(), 0);
                })
                .thenExecute(() -> Blackouts.restore(level, Vec3.atCenterOf(glow[0]), 8))
                .thenWaitUntil(() -> {
                    assertLamps(h, level, placed, false);
                    assertBlockLight(h, level, glow[0].above(), 14);
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenSucceed();
    }

    /**
     * Чанк на краю загруженного мира — в памяти, но соседи не загружены (и не загрузятся, пока игрок не подойдёт):
     * его лампы гаснут и зажигаются без ожидания соседей, очередь блэкаута пустеет.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_edge", skyAccess = true)
    public static void edgeChunkSwitchesWithoutNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.south(480)));
        // билет уровня 33: сам чанк полностью загружен, соседи — нет
        chunks.addRegionTicket(HOLD, far, 0, far);
        level.getChunk(far.x, far.z);
        BlockPos[] lamp = new BlockPos[1];
        h.startSequence()
                .thenExecute(() -> {
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "соседи чанка загружены — проверять нечего");
                    int x = far.getMiddleBlockX(), z = far.getMiddleBlockZ();
                    lamp[0] = new BlockPos(x, level.getChunk(far.x, far.z).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 3, z);
                    // без обновлений соседей: они прочли бы соседний чанк
                    level.setBlock(lamp[0], Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp[0])), "светокамень на краю мира горит"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "очередь блэкаута не пустеет: " + java.util.Arrays.toString(BlackoutWorld.get(level).backlog())))
                .thenExecute(() -> Blackouts.restore(level, Vec3.atCenterOf(lamp[0]), 8))
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(Blocks.GLOWSTONE), "свет на краю мира не вернулся"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "очередь блэкаута не пустеет после возврата"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк выгрузился");
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "блэкаут загрузил соседей краевого чанка");
                    chunks.removeRegionTicket(HOLD, far, 0, far);
                })
                .thenSucceed();
    }

    /**
     * Лампа из красного камня на краю загруженного мира: сигнал пропал, пока квартал был тёмным. Свет возвращается
     * в палитре (соседей нет — сверить сигнал нельзя), лампа — какой была; сверка с сигналом — когда соседи загрузились:
     * лампа гаснет, как от обновления соседа.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_edge_signal", skyAccess = true)
    public static void edgeLampFollowsSignalOnceNeighboursLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.west(480)));
        chunks.addRegionTicket(HOLD, far, 0, far);
        level.getChunk(far.x, far.z);
        BlockPos[] lamp = new BlockPos[1];
        h.startSequence()
                .thenExecute(() -> {
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "соседи чанка загружены — проверять нечего");
                    int x = far.getMiddleBlockX(), z = far.getMiddleBlockZ();
                    lamp[0] = new BlockPos(x, level.getChunk(far.x, far.z).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 3, z);
                    // без обновлений соседей: они прочли бы соседний чанк
                    level.setBlock(lamp[0].above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(lamp[0], Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_CLIENTS);
                    Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp[0])), "лампа на краю мира горит"))
                .thenExecute(() -> {
                    // сигнал пропал в темноте
                    level.setBlock(lamp[0].above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Blackouts.restore(level, Vec3.atCenterOf(lamp[0]), 8);
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(Blocks.REDSTONE_LAMP), "свет на краю мира не вернулся"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "очередь блэкаута не пустеет"))
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "лампа без соседей сверилась с сигналом");
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "блэкаут загрузил соседей краевого чанка");
                    hold(level, far);
                })
                .thenWaitUntil(() -> h.assertFalse(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "лампа не сверилась с сигналом, когда соседи загрузились"))
                .thenExecute(() -> {
                    chunks.removeRegionTicket(HOLD, far, 0, far);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * То же, но соседи краевого чанка уже бывали загружены и лишь опустились ниже полной загрузки (игрок отошёл): когда
     * они поднимаются обратно, события загрузки нет, а лампа всё равно сверяется с сигналом.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_edge_signal_back", skyAccess = true)
    public static void edgeLampFollowsSignalWhenNeighboursReturn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.west(480).north(480)));
        chunks.addRegionTicket(HOLD, far, 0, far);
        // соседи — полностью загружены (событие загрузки было), потом опускаются ниже, оставаясь в памяти
        hold(level, far);
        BlockPos[] lamp = new BlockPos[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(NuclearTickets.neighbourhoodLoaded(level, far), "соседи грузятся"))
                .thenExecute(() -> {
                    int x = far.getMiddleBlockX(), z = far.getMiddleBlockZ();
                    lamp[0] = new BlockPos(x, level.getChunk(far.x, far.z).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 3, z);
                    level.setBlock(lamp[0].above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(lamp[0], Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_CLIENTS);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> {
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "соседи ещё полностью загружены");
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(far.x + 1, far.z)) != null, "сосед выгрузился совсем");
                })
                .thenExecute(() -> Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1))
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp[0])), "лампа на краю мира горит"))
                .thenExecute(() -> {
                    level.setBlock(lamp[0].above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Blackouts.restore(level, Vec3.atCenterOf(lamp[0]), 8);
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(Blocks.REDSTONE_LAMP), "свет на краю мира не вернулся"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "очередь блэкаута не пустеет"))
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "лампа без соседей сверилась с сигналом");
                    // соседи снова полностью загружены — без события загрузки: чанки те же, что были
                    chunks.addRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> h.assertFalse(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "лампа не сверилась с сигналом, когда соседи вернулись"))
                .thenExecute(() -> {
                    chunks.removeRegionTicket(HOLD, far, 0, far);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * Тёмный чанк через диск туда и обратно. Сохраняется он с настоящими лампами (ни одного {@code airstrike:unlit}
     * в теге, свет — пересчитать); загруженный, пока квартал тёмный, — снова ровно двойники всех 19 ламп, сундук
     * с содержимым и биомы те же, свет посчитан по погашенным. Свет вернули, пока чанк на диске, — загружается он
     * прежними лампами и светит.
     */
    @GameTest(template = "range", timeoutTicks = 4800, batch = "grid_disk", skyAccess = true)
    public static void darkChunkRoundTripsThroughDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.north(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        Map<BlockPos, Holder<Biome>> biomes = new LinkedHashMap<>();
        BlockPos[] glow = new BlockPos[1], chest = new BlockPos[1];
        ItemStack loot = new ItemStack(Items.DIAMOND, 7);
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится"))
                .thenExecute(() -> {
                    BlockPos origin = caseOrigin(level, far);
                    placed.putAll(lampCases(level, origin));
                    glow[0] = find(placed, Blocks.GLOWSTONE);
                    chest[0] = origin.offset(0, 0, 9);
                    level.setBlock(chest[0], Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
                    ((ChestBlockEntity) level.getBlockEntity(chest[0])).setItem(4, loot.copy());
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += 16) {
                        BlockPos p = new BlockPos(far.getMinBlockX() + 5, y, far.getMinBlockZ() + 9);
                        biomes.put(p, level.getBiome(p));
                    }
                    Blackouts.blackout(level, Vec3.atCenterOf(glow[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    assertLamps(h, level, placed, true);
                    assertBlockLight(h, level, glow[0].above(), 0);
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    CompoundTag saved = assertNoTwinsOnDisk(h, level, far);
                    h.assertFalse(saved.getBoolean(ChunkSerializer.IS_LIGHT_ON_TAG), "свет погашенных сохранён как верный");
                    h.assertTrue(saved.toString().contains("minecraft:glowstone"), "светокамня нет в сохранении");
                    h.assertTrue(PowerGrid.get(level).dark(far.x, far.z, level.getGameTime()), "квартал уже светлый");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится");
                    assertLamps(h, level, placed, true);
                    assertBlockLight(h, level, glow[0].above(), 0);
                })
                .thenExecute(() -> {
                    h.assertTrue(level.getChunkAt(glow[0]).hasData(ModAttachments.GRID_DARK), "загруженный тёмным чанк не отмечен");
                    h.assertTrue(level.getBlockEntity(chest[0]) instanceof ChestBlockEntity c && ItemStack.matches(c.getItem(4), loot),
                            "сундук после диска: " + level.getBlockEntity(chest[0]));
                    for (var e : biomes.entrySet()) {
                        h.assertTrue(level.getBiome(e.getKey()).value() == e.getValue().value(), "биом в " + e.getKey() + ": " + level.getBiome(e.getKey()));
                    }
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    assertNoTwinsOnDisk(h, level, far);
                    h.assertTrue(Blackouts.restore(level, Vec3.atCenterOf(glow[0]), 8) == 1, "возвращать нечего");
                })
                .thenWaitUntil(() -> h.assertFalse(PowerGrid.get(level).dark(far.x, far.z, level.getGameTime()), "квартал ещё тёмный"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "возврат света загрузил чанк");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится");
                    assertLamps(h, level, placed, false);
                    assertBlockLight(h, level, glow[0].above(), 14);
                })
                .thenExecute(() -> {
                    h.assertFalse(level.getChunkAt(glow[0]).hasData(ModAttachments.GRID_DARK), "светлый чанк отмечен тёмным");
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * Перехват сохранения трогает только тег, который написала ваниль для этого же чанка. Свой тег (как в чанке)
     * — лампы вместо двойников; чужие — пустой, секции не списком, секции вне высоты мира, блоки секции не тем
     * типом или другие, чем в чанке, тег недогруженного чанка — остаются байт в байт.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_save_tags", skyAccess = true)
    public static void saveLeavesForeignTagsAlone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        BlockPos lamp = h.absolutePos(CENTER);
        level.setBlock(lamp, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        LevelChunk chunk = level.getChunkAt(lamp);
        h.assertTrue(ChunkLights.apply(level, chunk, true) >= 1, "фонарь не погас");
        try {
            CompoundTag own = ChunkSerializer.write(level, chunk);
            h.assertTrue(own.toString().contains("airstrike:unlit_sea_lantern"), "в теге нет двойника — проверять нечего");
            ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, own));
            h.assertFalse(own.toString().contains("airstrike:unlit"), "двойник остался в теге: " + own.toString().indexOf("airstrike:unlit"));
            h.assertTrue(own.toString().contains("minecraft:sea_lantern"), "фонаря нет в теге");
            h.assertFalse(own.getBoolean(ChunkSerializer.IS_LIGHT_ON_TAG), "свет погашенных сохранён как верный");
            h.assertTrue(level.getBlockState(lamp) == GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()), "сохранение зажгло фонарь в мире");

            CompoundTag pattern = ChunkSerializer.write(level, chunk);
            int sy = SectionPos.blockToSectionCoord(lamp.getY());
            PalettedContainer<BlockState> stone = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.STONE.defaultBlockState(),
                    PalettedContainer.Strategy.SECTION_STATES);
            List<CompoundTag> foreign = List.of(
                    new CompoundTag(),
                    with(pattern, t -> t.put(ChunkSerializer.SECTIONS_TAG, new CompoundTag())),
                    with(pattern, t -> sections(t).forEach(s -> s.putByte("Y", (byte) (s.getByte("Y") + 64)))),
                    with(pattern, t -> sections(t).forEach(s -> s.putString("block_states", "foreign"))),
                    with(pattern, t -> sections(t).stream().filter(s -> s.getByte("Y") == sy)
                            .forEach(s -> s.put("block_states", BLOCK_STATES.encodeStart(NbtOps.INSTANCE, stone).getOrThrow()))));
            for (CompoundTag t : foreign) {
                CompoundTag before = t.copy();
                ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, t));
                h.assertTrue(t.equals(before), "чужой тег изменён: " + before.getAllKeys());
            }
            ProtoChunk proto = new ProtoChunk(chunk.getPos(), UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            CompoundTag protoTag = pattern.copy();
            ChunkSaves.onSave(new ChunkDataEvent.Save(proto, level, protoTag));
            h.assertTrue(protoTag.equals(pattern), "тег недогруженного чанка изменён");
        } finally {
            ChunkLights.apply(level, chunk, false);
        }
        h.succeed();
    }

    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private static CompoundTag with(CompoundTag tag, Consumer<CompoundTag> edit) {
        CompoundTag t = tag.copy();
        edit.accept(t);
        return t;
    }

    private static List<CompoundTag> sections(CompoundTag tag) {
        ListTag list = tag.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        return java.util.stream.IntStream.range(0, list.size()).mapToObj(list::getCompound).toList();
    }

    /**
     * Держать чанк и его соседей и загрузить их сразу (с диска или генерацией): сервер GameTest на CI тикает
     * в десятки раз быстрее игры, и срок теста проходит раньше фоновой загрузки.
     */
    private static void hold(ServerLevel level, ChunkPos p) {
        level.getChunkSource().addRegionTicket(HOLD, p, 2, p);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.getChunk(p.x + dx, p.z + dz);
        }
    }

    /** Угол раскладки ламп в чанке: над самым высоким блоком чанка, чтобы над лампами был воздух. */
    private static BlockPos caseOrigin(ServerLevel level, ChunkPos p) {
        int top = level.getMinBuildHeight();
        for (int x = p.getMinBlockX(); x <= p.getMaxBlockX(); x++) {
            for (int z = p.getMinBlockZ(); z <= p.getMaxBlockZ(); z++) top = Math.max(top, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        }
        return new BlockPos(p.getMinBlockX() + 3, top + 3, p.getMinBlockZ() + 3);
    }

    private static BlockPos find(Map<BlockPos, BlockState> placed, Block block) {
        return placed.entrySet().stream().filter(e -> e.getValue().is(block)).findFirst().orElseThrow().getKey();
    }

    /** Все лампы — ровно двойники поставленных ({@code dark}) или ровно поставленные. */
    private static void assertLamps(GameTestHelper h, ServerLevel level, Map<BlockPos, BlockState> placed, boolean dark) {
        for (var e : placed.entrySet()) {
            BlockState want = dark ? GridLights.unlit(e.getValue()) : e.getValue();
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == want, "в " + e.getKey() + " ждали " + want + ", стоит " + now);
        }
    }

    private static void assertBlockLight(GameTestHelper h, ServerLevel level, BlockPos p, int want) {
        int got = level.getBrightness(LightLayer.BLOCK, p);
        h.assertTrue(got == want, "свет блоков в " + p + ": " + got + ", ждали " + want);
    }

    /** Тег чанка с диска (или из очереди записи): есть и без единого двойника. */
    private static CompoundTag assertNoTwinsOnDisk(GameTestHelper h, ServerLevel level, ChunkPos p) {
        CompoundTag tag = level.getChunkSource().chunkMap.read(p).join().orElse(null);
        h.assertTrue(tag != null, "чанк " + p + " не сохранён");
        String text = tag.toString();
        h.assertFalse(text.contains("airstrike:unlit"), "двойник на диске: …" + text.substring(Math.max(0, text.indexOf("airstrike:unlit") - 40),
                Math.min(text.length(), text.indexOf("airstrike:unlit") + 60)) + "…");
        return tag;
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
}
