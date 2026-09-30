package ua.zentix.airstrike.gametest;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
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
        // подстанции других проверок остаются в мире: ядерный удар этой выбил бы их (порядок партий — по хешу имён)
        PowerGrid grid = PowerGrid.get(level);
        List<Integer> ids = new ArrayList<>();
        for (Node n : grid.nodes()) ids.add(n.id());
        ids.forEach(grid::removeNode);
    }

    /**
     * Каждое состояние каждой лампы сети туда и обратно — то же состояние; у двойника нет света. На площадке —
     * лампы в «неудобных» состояниях (висящий фонарь под водой, стержень края на восток, лампа из красного камня под
     * сигналом, медная лампа под сигналом, блок света 7 под водой): после погашения и соседских обновлений двойники
     * те же, после возврата света — ровно прежние состояния. Каждая пара для мира — один блок, кроме света
     * ({@link GridLights#inPlace}): очередь меняет их прямо в палитре секции.
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
                // очередь меняет лампы прямо в палитре: для мира пара — один блок, кроме света
                h.assertTrue(GridLights.inPlace(s) && GridLights.inPlace(off), s + " и " + off + " различаются не только светом");
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
        ChunkLights.Pass first = ChunkLights.apply(level, chunk, true, 5);
        int dark = first.changed();
        h.assertTrue(dark == 5 && !first.done(), "за единицу погашено " + dark + " вместо 5 (чанк пройден: " + first.done() + ")");
        // ровно столько ламп, сколько осталось, с места остановки — чанк пройден этим же проходом
        ChunkLights.Pass rest = ChunkLights.apply(level, chunk, true, placed.size() - 5, first.next());
        dark += rest.changed();
        h.assertTrue(rest.done(), "чанк ровно с лимитом ламп не пройден до конца");
        h.assertTrue(ChunkLights.apply(level, chunk, true, 1).changed() == 0, "после прохода до конца остались лампы");
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
                .thenExecute(() -> Blackouts.useClock(level.getServer(), Blackouts.newClock()))
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
                // подрыв и выбитая им подстанция — два отключения (других нет: quiet)
                .thenExecute(() -> h.assertTrue(Blackouts.restore(level, null, 0) == 2, "возвращено не два отключения"))
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertBlockState(p, s -> s == Blocks.LANTERN.defaultBlockState(), () -> "фонарь " + p + " не зажёгся прежним");
                    h.assertTrue(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "подстанция не заработала");
                })
                .thenSucceed();
    }

    /**
     * Радиус отключения города: угол города — в ~373 блоках от центра, а зерно квартала (ячейка Вороного) лежит до ~60
     * блоков дальше своих чанков; при 400 угловой чанк на некоторых местах площадки оставался вне отключения.
     */
    private static final int CITY_RADIUS = 480;

    /**
     * Город: 1024 загруженных чанка по 8 ламп. Единица работы очереди — {@code UNIT_WORK} работы по стольким чанкам,
     * сколько уместится (лампа в палитре — 1, проход секций чанка с лампами — {@code SCAN_COST}), и чанк кончается
     * тем же проходом, что перевёл его последнюю лампу: на считающих часах и гашение, и возврат света идут не меньше
     * 1.2 чанка на единицу работы (при полной очереди — 2, но каскад идёт по кварталам, и последняя единица квартала
     * бывает неполной; по чанку за единицу и ещё единица на холостой проход, как до правки, — полчанка). Потом тот же каскад на настоящих часах блэкаута — в лог чанки за тик и самый долгий тик, и время
     * на лампу (не проверка: настенное время на CI плавает).
     */
    @GameTest(template = "range", timeoutTicks = 4800, batch = "grid_city", skyAccess = true)
    public static void cityQueueRunsManyChunksPerTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        int y = h.absolutePos(CENTER).getY() + 1;
        List<ChunkPos> held = new ArrayList<>();
        List<BlockPos> lamps = new ArrayList<>();
        for (int dx = -16; dx < 16; dx++) {
            for (int dz = -16; dz < 16; dz++) {
                ChunkPos p = new ChunkPos(mid.x + dx, mid.z + dz);
                chunks.addRegionTicket(HOLD, p, 1, p);
                held.add(p);
            }
        }
        for (ChunkPos p : held) {
            level.getChunk(p.x, p.z);
            for (int i = 0; i < 8; i++) {
                BlockPos at = new BlockPos(p.getMinBlockX() + 1 + i, y, p.getMinBlockZ() + 1 + i);
                level.setBlock(at, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
                lamps.add(at);
            }
        }
        // время на лампу: один setBlock и чанк целиком через ChunkLights.apply (с проходом секций) — по 128 чанков
        long t0 = System.nanoTime();
        for (int i = 0; i < 128 * 8; i++) level.setBlock(lamps.get(i), GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        long set = System.nanoTime() - t0;
        for (int i = 0; i < 128 * 8; i++) level.setBlock(lamps.get(i), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        t0 = System.nanoTime();
        int n = 0;
        for (int i = 128; i < 256; i++) n += ChunkLights.apply(level, level.getChunk(held.get(i).x, held.get(i).z), true);
        long apply = System.nanoTime() - t0;
        for (int i = 128; i < 256; i++) ChunkLights.apply(level, level.getChunk(held.get(i).x, held.get(i).z), false);
        // и лампы соседних площадок, если они в этих чанках
        h.assertTrue(n >= 128 * 8, "погашено " + n);
        t0 = System.nanoTime();
        int m = 0;
        for (int i = 256; i < 384; i++) {
            m += ChunkLights.applyInPlace(level, level.getChunk(held.get(i).x, held.get(i).z), true, Integer.MAX_VALUE, 0, true, new LongArrayList()).changed();
        }
        long inPlace = System.nanoTime() - t0;
        for (int i = 256; i < 384; i++) ChunkLights.apply(level, level.getChunk(held.get(i).x, held.get(i).z), false);
        Airstrike.LOG.info("GRIDBENCH лампа: setBlock {} мкс, ChunkLights.apply {} мкс, в палитре {} мкс (с проходом секций, по {} и {} ламп)",
                String.format(Locale.ROOT, "%.1f", set / 1e3 / (128 * 8)), String.format(Locale.ROOT, "%.1f", apply / 1e3 / n),
                String.format(Locale.ROOT, "%.1f", inPlace / 1e3 / m), n, m);

        Vec3 at = Vec3.atCenterOf(h.absolutePos(CENTER));
        WorkClock counting = WorkClock.counting(1_000_000L);
        WorkClock[] real = {null};
        int[] ticks = new int[4], units = new int[2];
        long[] maxTick = new long[2];
        Blackouts.useClock(level.getServer(), counting);
        // счётчики работы для /airstrike grid status (с загрузки мира: окно в 100 тиков сервер GameTest на CI
        // проходит целиком за время гашения, и прошлое окно бывает пустым) — единицы, лампы, пройденные чанки
        long[] before = BlackoutWorld.get(level).totals();
        Blackouts.blackout(level, at, CITY_RADIUS, 1000, -1);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "гашение идёт"))
                .thenExecute(() -> {
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(level.getBlockState(p)), "лампа " + p + " горит");
                    long[] after = BlackoutWorld.get(level).totals();
                    for (BlackoutWorld.Work w : List.of(BlackoutWorld.Work.UNIT, BlackoutWorld.Work.LAMPS, BlackoutWorld.Work.PASS_DONE)) {
                        h.assertTrue(after[w.ordinal()] > before[w.ordinal()], "счётчик " + w + " не вырос за гашение");
                    }
                    ticks[0] = counting.ticksWorked();
                    units[0] = counting.units();
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "возврат идёт"))
                .thenExecute(() -> {
                    ticks[1] = counting.ticksWorked() - ticks[0];
                    units[1] = counting.units() - units[0];
                    assertAllLit(h, level, lamps, mid);
                    // на настоящих часах блэкаута — как в игре
                    real[0] = Blackouts.newClock();
                    Blackouts.useClock(level.getServer(), real[0]);
                    Blackouts.blackout(level, at, CITY_RADIUS, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "гашение идёт"))
                .thenExecute(() -> {
                    // шаг выше упал (проверка теста): без часов — дальше ничего не мерить, сервер не ронять
                    h.assertTrue(real[0] != null, "настоящие часы не поставлены — упал прошлый шаг");
                    ticks[2] = real[0].ticksWorked();
                    maxTick[0] = real[0].maxTickNanos();
                    real[0] = Blackouts.newClock();
                    Blackouts.useClock(level.getServer(), real[0]);
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "возврат идёт"))
                .thenExecute(() -> {
                    h.assertTrue(real[0] != null, "настоящие часы не поставлены — упал прошлый шаг");
                    ticks[3] = real[0].ticksWorked();
                    maxTick[1] = real[0].maxTickNanos();
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    held.forEach(p -> chunks.removeRegionTicket(HOLD, p, 1, p));
                    assertAllLit(h, level, lamps, mid);
                    int budget = AirstrikeConfig.SERVER.gridTimeBudgetMs.get();
                    // на единицу, а не на тик: каскад идёт по кварталам со сдвигом, и в части тиков очередь пустеет раньше срока
                    double off = (double) held.size() / units[0], on = (double) held.size() / units[1];
                    Airstrike.LOG.info("GRIDBENCH считающие часы (до {} единиц за тик): гашение {} чанков на единицу ({} единиц, {} тиков), возврат {} ({} единиц, {} тиков)",
                            counting.maxUnitsPerTick(), String.format(Locale.ROOT, "%.2f", off), units[0], ticks[0], String.format(Locale.ROOT, "%.2f", on), units[1], ticks[1]);
                    Airstrike.LOG.info("GRIDBENCH настоящие часы (бюджет {} мс): гашение {} чанков/тик ({} тиков, самый долгий {} мс), возврат {} чанков/тик ({} тиков, самый долгий {} мс)",
                            budget, String.format(Locale.ROOT, "%.1f", (double) held.size() / ticks[2]), ticks[2], String.format(Locale.ROOT, "%.2f", maxTick[0] / 1e6),
                            String.format(Locale.ROOT, "%.1f", (double) held.size() / ticks[3]), ticks[3], String.format(Locale.ROOT, "%.2f", maxTick[1] / 1e6));
                    h.assertTrue(off >= 1.2 && on >= 1.2, "чанков на единицу работы: гашение " + off + ", возврат " + on + " (нужно не меньше 1.2)");
                })
                .thenSucceed();
    }

    /** Все лампы города снова прежние (первая не зажёгшаяся — в лог с её чанком). */
    private static void assertAllLit(GameTestHelper h, ServerLevel level, List<BlockPos> lamps, ChunkPos mid) {
        List<BlockPos> bad = lamps.stream().filter(p -> level.getBlockState(p) != Blocks.SEA_LANTERN.defaultBlockState()).toList();
        if (bad.isEmpty()) return;
        BlockPos b = bad.get(0);
        ChunkPos c = new ChunkPos(b);
        Airstrike.LOG.info("GRIDDEBUG не зажглось {}: первая {} {} чанк {} ({} {}), тёмный {}, задет {}", bad.size(), b, level.getBlockState(b), c,
                c.x - mid.x, c.z - mid.z, PowerGrid.get(level).dark(c.x, c.z, level.getGameTime()), PowerGrid.get(level).covered(c.x, c.z));
        h.fail(bad.size() + " ламп не зажглись прежними, первая " + b);
    }

    /**
     * Чанк, залитый светом: 32 768 блоков света и фонаря (как невидимые блоки света карт-городов, до 7400 на чанк) —
     * десятки единиц работы. Очередь переводит его проходами с места остановки и доводит до конца (неверный конец чанка
     * оставил бы лампы гореть). Потом чанк выгружается посреди гашения (по единице за тик) и загружается снова: в тёмном
     * квартале — ни одной горящей лампы; свет возвращается весь. В темпе игры: выгрузка ждёт записи чанка в фоне, а
     * сервер GameTest без пауз проходил бы башню раньше.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_tower", skyAccess = true)
    public static void lampTowerConvertsAcrossPassesAndUnload(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.west(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockState light = Blocks.LIGHT.defaultBlockState(), lantern = Blocks.SEA_LANTERN.defaultBlockState();
        WorkClock fast = WorkClock.counting(1_000_000L);
        // 3 мс единица при бюджете 4 мс — одна единица за тик: гашение башни идёт десятки тиков
        WorkClock slow = WorkClock.counting(3_000_000L);
        long[] lampsAtStart = {0};
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится"))
                .thenExecute(() -> {
                    placed.putAll(fill(level, far, 128, light, lantern));
                    Blackouts.useClock(level.getServer(), fast);
                    Blackouts.blackout(level, Vec3.atCenterOf(placed.keySet().iterator().next()), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "гашение идёт");
                    assertLamps(h, level, placed, true);
                })
                .thenExecute(() -> {
                    h.assertTrue(fast.units() >= placed.size() / BlackoutWorld.UNIT_WORK, "башня погасла за " + fast.units() + " единиц — проходов не было");
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "возврат идёт");
                    assertLamps(h, level, placed, false);
                })
                .thenExecute(() -> {
                    lampsAtStart[0] = BlackoutWorld.get(level).totals()[BlackoutWorld.Work.LAMPS.ordinal()];
                    Blackouts.useClock(level.getServer(), slow);
                    Blackouts.blackout(level, Vec3.atCenterOf(placed.keySet().iterator().next()), 200, 1000, -1);
                })
                // первые лампы погасли — чанк уходит из памяти посреди перевода
                .thenWaitUntil(() -> h.assertTrue(placed.keySet().stream().anyMatch(p -> GridLights.isUnlit(level.getBlockState(p))), "гашение не началось"))
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    long lamps = BlackoutWorld.get(level).totals()[BlackoutWorld.Work.LAMPS.ordinal()] - lampsAtStart[0];
                    h.assertTrue(lamps < placed.size(), "башня погасла до выгрузки (" + lamps + " ламп) — выгрузки посреди прохода не было");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится");
                    h.assertTrue(BlackoutWorld.get(level).idle(), "гашение идёт");
                    assertLamps(h, level, placed, true);
                })
                .thenExecute(() -> Blackouts.restore(level, null, 0, 0))
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "возврат идёт");
                    assertLamps(h, level, placed, false);
                })
                .thenExecute(() -> {
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * Четыре чанка по 7168 блоков света (плотнее самых плотных чанков карт-городов): лампы меняются в палитре, и
     * очередь переводит их не дороже чем по {@code UNIT_WORK} ламп на единицу работы (через {@code setBlock} было 32),
     * оба пути до конца; свет в середине куба гаснет и возвращается.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_dense", skyAccess = true)
    public static void denseLightChunksConvertInBoundedUnits(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos base = new ChunkPos(h.absolutePos(CENTER.east(480).south(480)));
        List<ChunkPos> held = List.of(base, new ChunkPos(base.x + 1, base.z), new ChunkPos(base.x, base.z + 1), new ChunkPos(base.x + 1, base.z + 1));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockState light = Blocks.LIGHT.defaultBlockState();
        WorkClock clock = WorkClock.counting(1_000_000L);
        BlockPos[] mid = {null};
        int[] units = new int[2];
        long[] before = new long[BlackoutWorld.Work.values().length];
        held.forEach(p -> hold(level, p));
        h.startSequence()
                .thenWaitUntil(() -> held.forEach(p -> h.assertTrue(chunks.getChunkNow(p.x, p.z) != null, "чанк " + p + " грузится")))
                .thenExecute(() -> {
                    // одна высота на все четыре чанка — сплошной куб, его середина далеко от края
                    int y = held.stream().mapToInt(p -> caseOrigin(level, p).getY()).max().orElseThrow();
                    for (ChunkPos p : held) placed.putAll(fill(level, p, y, 28, light, light));
                    mid[0] = new BlockPos(base.getMaxBlockX(), y + 14, base.getMaxBlockZ());
                    Blackouts.useClock(level.getServer(), clock);
                })
                .thenWaitUntil(() -> assertBlockLight(h, level, mid[0], 15))
                .thenExecute(() -> {
                    System.arraycopy(BlackoutWorld.get(level).totals(), 0, before, 0, before.length);
                    Blackouts.blackout(level, Vec3.atCenterOf(mid[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "гашение идёт");
                    assertLamps(h, level, placed, true);
                })
                .thenWaitUntil(() -> assertBlockLight(h, level, mid[0], 0))
                .thenExecute(() -> {
                    units[0] = (int) (BlackoutWorld.get(level).totals()[BlackoutWorld.Work.UNIT.ordinal()] - before[BlackoutWorld.Work.UNIT.ordinal()]);
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "возврат идёт");
                    assertLamps(h, level, placed, false);
                })
                .thenWaitUntil(() -> assertBlockLight(h, level, mid[0], 15))
                .thenExecute(() -> {
                    units[1] = (int) (BlackoutWorld.get(level).totals()[BlackoutWorld.Work.UNIT.ordinal()] - before[BlackoutWorld.Work.UNIT.ordinal()]) - units[0];
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    held.forEach(p -> chunks.removeRegionTicket(HOLD, p, 2, p));
                    // единицы перевода (без разбора каскада на ряды): по единице на UNIT_WORK ламп и запас на концы
                    // проходов и пустые чанки района (через setBlock по 32 лампы — 900 единиц)
                    int most = placed.size() * 115 / 100 / BlackoutWorld.UNIT_WORK + 2 * held.size();
                    long[] t = BlackoutWorld.get(level).totals();
                    StringBuilder kinds = new StringBuilder();
                    for (BlackoutWorld.Work w : BlackoutWorld.Work.values()) kinds.append(' ').append(w).append('=').append(t[w.ordinal()] - before[w.ordinal()]);
                    Airstrike.LOG.info("GRIDBENCH плотные чанки: {} блоков света, гашение {} единиц, возврат {} (не больше {});{}", placed.size(), units[0], units[1], most, kinds);
                    h.assertTrue(units[0] <= most && units[1] <= most, "единиц работы: гашение " + units[0] + ", возврат " + units[1] + " (не больше " + most + ")");
                })
                .thenSucceed();
    }

    /** Столб блоков с угла чанка высотой {@code height} над его рельефом: каждый девятый — {@code other}, остальные — {@code block}. */
    private static Map<BlockPos, BlockState> fill(ServerLevel level, ChunkPos p, int height, BlockState block, BlockState other) {
        return fill(level, p, caseOrigin(level, p).getY(), height, block, other);
    }

    private static Map<BlockPos, BlockState> fill(ServerLevel level, ChunkPos p, int y0, int height, BlockState block, BlockState other) {
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos at = new BlockPos(p.getMinBlockX() + x, y0 + y, p.getMinBlockZ() + z);
                    BlockState s = (x + y + z) % 9 == 0 ? other : block;
                    level.setBlock(at, s, Block.UPDATE_CLIENTS);
                    placed.put(at, s);
                }
            }
        }
        return placed;
    }

    /**
     * Чтение чанка с диска не для мира (LOD, карты: другие моды зовут {@code ChunkSerializer.read}, в том числе в своих
     * потоках) не трогает очередь блэкаута: копия уже загруженного чанка в потоке сервера и чтение в чужом потоке не
     * ставят свет с диска чанку мира (иначе очередь снова и снова проходила бы его лампы) и не пишут в её карты.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_foreign_read", skyAccess = true)
    public static void foreignChunkReadsLeaveQueueAlone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.south(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockState lantern = Blocks.SEA_LANTERN.defaultBlockState();
        RegionStorageInfo info = new RegionStorageInfo("gametest", level.dimension(), "chunk");
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "чанк грузится"))
                .thenExecute(() -> {
                    BlockPos origin = caseOrigin(level, far);
                    for (int i = 0; i < 40; i++) placed.put(origin.offset(i % 10, i / 10, 0), lantern);
                    placed.keySet().forEach(p -> level.setBlock(p, lantern, Block.UPDATE_ALL));
                    Blackouts.blackout(level, Vec3.atCenterOf(origin), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    assertLamps(h, level, placed, true);
                    h.assertFalse(BlackoutWorld.get(level).busy(), "очередь занята");
                })
                .thenExecute(() -> {
                    LevelChunk chunk = level.getChunk(far.x, far.z);
                    // как на диске: лампы вместо двойников
                    CompoundTag tag = ChunkSerializer.write(level, chunk);
                    NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, tag));
                    h.assertFalse(tag.toString().contains(Airstrike.MOD_ID + ":unlit"), "в теге двойники");
                    long copies = ChunkSaves.foreignReads(ChunkSaves.COPY), offThread = ChunkSaves.foreignReads(ChunkSaves.OFF_THREAD);
                    // копия в потоке сервера
                    LevelChunk copy = ((ImposterProtoChunk) ChunkSerializer.read(level, level.getPoiManager(), info, far, tag)).getWrapped();
                    h.assertTrue(ChunkSaves.foreignReads(ChunkSaves.COPY) == copies + 1, "копия чанка мира не узнана");
                    h.assertFalse(BlackoutWorld.get(level).busy(), "копия чанка мира поставила его лампы в очередь");
                    // то же событие в чужом потоке (как у LOD)
                    Thread reader = new Thread(() -> NeoForge.EVENT_BUS.post(new ChunkDataEvent.Load(copy, tag, ChunkType.LEVELCHUNK)), "gametest-foreign-reader");
                    reader.start();
                    try {
                        reader.join(10_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    h.assertTrue(ChunkSaves.foreignReads(ChunkSaves.OFF_THREAD) == offThread + 1, "чтение в чужом потоке не узнано");
                    h.assertFalse(BlackoutWorld.get(level).busy(), "чтение в чужом потоке поставило лампы в очередь");
                    assertLamps(h, level, placed, true);
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "возврат идёт");
                    assertLamps(h, level, placed, false);
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenSucceed();
    }

    /**
     * Чанки без ламп не тормозят очередь: пачкой за единицу работы, а не по одному (каждый ждал бы оценки тяжёлого
     * чанка с лампами). 144 загруженных чанка района при бюджете 4 мс (считающие часы: 3 единицы по 1 мс за тик) по
     * одному — 48 тиков одной очереди, пачками — несколько тиков на весь каскад.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_settle", skyAccess = true)
    public static void emptyChunksSettleInBatches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        List<ChunkPos> held = new ArrayList<>();
        for (int dx = -6; dx < 6; dx++) {
            for (int dz = -6; dz < 6; dz++) {
                ChunkPos p = new ChunkPos(mid.x + dx, mid.z + dz);
                chunks.addRegionTicket(HOLD, p, 1, p);
                level.getChunk(p.x, p.z);
                held.add(p);
            }
        }
        BlockPos lamp = new BlockPos(18, 12, 18);
        h.setBlock(lamp, Blocks.LANTERN);
        WorkClock clock = WorkClock.counting(1_000_000L);
        int budget = AirstrikeConfig.SERVER.gridTimeBudgetMs.get();
        long[] start = {0};
        long[] took = {0};
        h.startSequence()
                // возврат света от прошлых проверок (quiet) должен пройти до замера
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "очередь от прошлых проверок идёт"))
                .thenExecute(() -> {
                    Blackouts.useClock(level.getServer(), clock);
                    start[0] = level.getGameTime();
                    Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 150, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    BlackoutWorld world = BlackoutWorld.get(level);
                    h.assertTrue(world.idle() && GridLights.isUnlit(h.getBlockState(lamp)), "каскад идёт");
                    took[0] = level.getGameTime() - start[0];
                })
                .thenExecute(() -> {
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    held.forEach(p -> chunks.removeRegionTicket(HOLD, p, 1, p));
                    // чанки в памяти, которые задевает отключение, — каждый проходит очередь
                    long covered = held.stream().filter(p -> PowerGrid.get(level).covered(p.x, p.z)).count();
                    h.assertTrue(covered >= 100, "отключение задело только " + covered + " чанков — проверять нечего");
                    // по единице на чанк — covered / units тиков на одну очередь
                    int units = clock.maxUnitsPerTick();
                    h.assertTrue(took[0] * units < covered, "каскад по " + covered + " чанкам шёл " + took[0] + " тиков при " + units + " единицах за тик (бюджет " + budget + " мс)");
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
        // обе — в чанке середины площадки (он весь на ней): этот чанк каскад уже прошёл
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        int y = h.absolutePos(CENTER).getY() + 1;
        BlockPos first = new BlockPos(mid.getMinBlockX() + 3, y, mid.getMinBlockZ() + 3), placed = first.offset(6, 0, 6);
        level.setBlock(first, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 200, 1000, -1);
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(GridLights.isUnlit(level.getBlockState(first)), "фонарь " + first + " горит");
                    // весь каскад прошёл: лампу могла бы погасить только постановка
                    h.assertTrue(BlackoutWorld.get(level).idle(), "каскад идёт");
                })
                .thenExecute(() -> level.setBlock(placed, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(10)
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(placed).is(Blocks.LANTERN), "фонарь погас без события: " + level.getBlockState(placed));
                    Blackouts.onBlockPlaced(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, placed), Blocks.STONE.defaultBlockState(), null));
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(placed)), "поставленный фонарь горит"))
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

    /**
     * Секция, где двойнику нужна новая запись в палитре, а места в ней нет: сохранение пишет лампы и не трогает секцию
     * мира. Копия {@code PalettedContainer.copy()} любой палитры, кроме глобальной, держит обработчик роста живой секции
     * (палитра из одного значения — и вовсе та же), и замена в копии расширяла секцию мира, а запись падала — чанк
     * уходил на диск с двойниками. Секции: вся из двойника (одно значение), полная линейная палитра (16 состояний)
     * и глобальная (больше 256 состояний) с двойником и без него — поиск ламп по ней считает блоки ({@link ChunkLights#contains}).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_save_tags", skyAccess = true)
    public static void saveLeavesFullPaletteSectionsAlone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        BlockState twin = GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState());
        PalettedContainer<BlockState> single = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, twin, PalettedContainer.Strategy.SECTION_STATES);
        // воздух, 14 других блоков и двойник: 16 состояний — линейная палитра заполнена, фонаря в ней нет
        List<Block> others = List.of(Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.SAND,
                Blocks.GRAVEL, Blocks.GLASS, Blocks.BRICKS, Blocks.OAK_LOG, Blocks.WHITE_WOOL, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE);
        PalettedContainer<BlockState> linear = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        for (int i = 0; i < others.size(); i++) linear.set(i, 0, 0, others.get(i).defaultBlockState());
        linear.set(15, 15, 15, twin);
        PalettedContainer<BlockState> global = globalWithoutLamps(), globalTwin = globalWithoutLamps();
        globalTwin.set(15, 15, 15, twin);
        h.assertTrue(global.maybeHas(GridLights::isUnlit), "палитра без двойников отвечает «нет» сама: она не глобальная");
        LevelChunk chunk = level.getChunkAt(h.absolutePos(CENTER));
        // пустая секция над площадкой — на время проверки одна из этих
        int index = chunk.getSectionIndex(h.absolutePos(CENTER).getY()) + 4;
        LevelChunkSection air = chunk.getSections()[index];
        h.assertTrue(air.hasOnlyAir(), "секция над площадкой не пустая");
        int sectionY = chunk.getSectionYFromSectionIndex(index);
        for (PalettedContainer<BlockState> live : List.of(single, linear, global, globalTwin)) {
            chunk.getSections()[index] = new LevelChunkSection(live, air.getBiomes());
            try {
                Tag before = BLOCK_STATES.encodeStart(NbtOps.INSTANCE, live).getOrThrow();
                int size = live.getSerializedSize();
                CompoundTag tag = ChunkSerializer.write(level, chunk);
                ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, tag));
                CompoundTag saved = sections(tag).stream().filter(s -> s.getByte("Y") == sectionY).findFirst().orElseThrow().getCompound("block_states");
                PalettedContainer<BlockState> disk = BLOCK_STATES.parse(NbtOps.INSTANCE, saved).getOrThrow();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState inWorld = live.get(x, y, z), onDisk = disk.get(x, y, z);
                            BlockState expected = inWorld == twin ? Blocks.SEA_LANTERN.defaultBlockState() : inWorld;
                            if (onDisk != expected) h.fail("на диске " + onDisk + " вместо " + expected + " в " + x + " " + y + " " + z);
                        }
                    }
                }
                h.assertTrue(BLOCK_STATES.encodeStart(NbtOps.INSTANCE, live).getOrThrow().equals(before), "сохранение изменило секцию мира");
                // в глобальной палитре без двойников в этом месте — другой блок (его сверил цикл выше)
                h.assertTrue(live == global || live.get(15, 15, 15) == twin, "сохранение зажгло фонарь в мире");
                // палитра секции мира не выросла (блоки те же, но хранилище уже другое)
                h.assertTrue(live.getSerializedSize() == size, "сохранение расширило палитру секции мира");
            } finally {
                chunk.getSections()[index] = air;
            }
        }
        h.succeed();
    }

    /**
     * «Есть ли двойник в секции» — точно при любой палитре. Глобальная палитра (больше 256 состояний в секции, обычное
     * дело в детальном городе) на {@code maybeHas} всегда отвечает «да», и без подсчёта каждая загрузка и сохранение
     * такого чанка проходили все блоки секции и ставили его в очередь блэкаута; малые палитры (линейная до 16 состояний,
     * хеш-таблица до 256) помнят ушедшие состояния.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "grid_palette", skyAccess = true)
    public static void lampSearchIsExactForEveryPalette(GameTestHelper h) {
        BlockState twin = GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState());
        PalettedContainer<BlockState> global = globalWithoutLamps();
        h.assertTrue(global.maybeHas(GridLights::isUnlit), "палитра отвечает «нет» сама — проверять нечего");
        h.assertFalse(ChunkLights.contains(global, GridLights::isUnlit), "в глобальной палитре без двойников найден двойник");
        global.set(7, 7, 7, twin);
        h.assertTrue(ChunkLights.contains(global, GridLights::isUnlit), "двойник в глобальной палитре не найден");

        PalettedContainer<BlockState> stale = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        stale.set(1, 2, 3, twin);
        stale.set(1, 2, 3, Blocks.STONE.defaultBlockState());
        h.assertTrue(stale.maybeHas(GridLights::isUnlit), "малая палитра забыла ушедший двойник — проверять нечего");
        h.assertFalse(ChunkLights.contains(stale, GridLights::isUnlit), "ушедший из секции двойник найден по палитре");

        // 100 разных состояний — палитра-хеш-таблица, тоже помнит ушедший двойник
        PalettedContainer<BlockState> hashed = withoutLamps(100);
        hashed.set(15, 15, 15, twin);
        hashed.set(15, 15, 15, Blocks.STONE.defaultBlockState());
        h.assertTrue(hashed.maybeHas(GridLights::isUnlit), "палитра-хеш-таблица забыла ушедший двойник — проверять нечего");
        h.assertFalse(ChunkLights.contains(hashed, GridLights::isUnlit), "ушедший двойник найден по палитре-хеш-таблице");
        hashed.set(15, 15, 15, twin);
        h.assertTrue(ChunkLights.contains(hashed, GridLights::isUnlit), "двойник в палитре-хеш-таблице не найден");
        h.succeed();
    }

    /** Секция из 4096 разных состояний без ламп и двойников: глобальная палитра. */
    private static PalettedContainer<BlockState> globalWithoutLamps() {
        return withoutLamps(4096);
    }

    /** Секция из {@code distinct} разных состояний без ламп и двойников (остальное — воздух). */
    private static PalettedContainer<BlockState> withoutLamps(int distinct) {
        PalettedContainer<BlockState> c = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        int placed = 0;
        for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
            if (placed == distinct) break;
            if (s.isAir() || GridLights.isLit(s) || GridLights.isUnlit(s)) continue;
            c.set(placed & 15, placed >> 8, placed >> 4 & 15, s);
            placed++;
        }
        if (placed < distinct) throw new IllegalStateException("в реестре меньше " + distinct + " состояний");
        return c;
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
