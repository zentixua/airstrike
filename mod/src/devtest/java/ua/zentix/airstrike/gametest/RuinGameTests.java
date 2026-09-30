package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.nuclear.model.Yield;
import ua.zentix.airstrike.nuclear.world.ColumnScar;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.RuinPlan;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.List;
import java.util.Locale;

import static ua.zentix.airstrike.gametest.NuclearGameTests.CENTER;
import static ua.zentix.airstrike.gametest.NuclearGameTests.HOLD;
import static ua.zentix.airstrike.gametest.NuclearGameTests.atPsi;
import static ua.zentix.airstrike.gametest.NuclearGameTests.chunkCorner;
import static ua.zentix.airstrike.gametest.NuclearGameTests.detonation;
import static ua.zentix.airstrike.gametest.NuclearGameTests.scarAll;
import static ua.zentix.airstrike.gametest.NuclearGameTests.surface;

/**
 * Руины ядерного удара на физике ({@code Blast}, {@code Collapse}): случаи из ревью — пролёты шире пролёта блока,
 * давление в глубине постройки, опоры, навесное, вода, неразрушимое, край загруженного мира, границы квадратов
 * подготовки. Площадка и масштаб — как в {@link NuclearGameTests}: дёрн на y = 11, поверхность y = 12.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinGameTests {
    private RuinGameTests() {}

    private static String psi(Detonation d, GameTestHelper h, BlockPos at) {
        return String.format(Locale.ROOT, "%.1f psi", d.psi(Vec3.atCenterOf(h.absolutePos(at))));
    }

    /**
     * Стеклянные залы с крышей при ~2 psi: стёкла выбиты, и крыше не на чем стоять. У зала 17×17 крыша каменная
     * (середина — в 8 блоках от стены, дальше пролёта камня: 7 и 8 падают по правилу «опора дальше на 3»), у зала
     * 21×21 — бетонная (середина дальше обхода опор: опоры не было видно и до удара, падает каскадом от краёв). От крыш
     * не остаётся ни блока.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_hall", skyAccess = true)
    public static void glassHallRoofFalls(GameTestHelper h) {
        BlockPos small = new BlockPos(50, 12, 13), big = new BlockPos(50, 12, 44);
        Detonation d = atPsi(h, new BlockPos(32, 12, 28), true, small.above(5), 2);
        hall(h, small, 8, Blocks.STONE);
        hall(h, big, 10, Blocks.WHITE_CONCRETE);
        scarAll(h, d, new ColumnScar.Budget(false));
        roofGone(h, d, small, 8, Blocks.STONE);
        roofGone(h, d, big, 10, Blocks.WHITE_CONCRETE);
        h.succeed();
    }

    /** Зал (2r+1)×(2r+1): стеклянные стены в 5 блоков, крыша на 5-м из {@code roof}. */
    private static void hall(GameTestHelper h, BlockPos c, int r, Block roof) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r || Math.abs(dz) == r) {
                    for (int y = 0; y < 5; y++) h.setBlock(c.offset(dx, y, dz), Blocks.GLASS);
                }
                h.setBlock(c.offset(dx, 5, dz), roof);
            }
        }
    }

    private static void roofGone(GameTestHelper h, Detonation d, BlockPos c, int r, Block roof) {
        int left = 0;
        BlockPos any = null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = 1; y <= 5; y++) {
                    if (h.getBlockState(c.offset(dx, y, dz)).is(roof)) {
                        left++;
                        any = c.offset(dx, y, dz);
                    }
                    if (h.getBlockState(c.offset(dx, y, dz)).is(Blocks.GLASS)) h.fail("стекло зала цело в " + c.offset(dx, y, dz).toShortString());
                }
            }
        }
        if (left > 0) h.fail("от крыши зала " + (2 * r + 1) + "×" + (2 * r + 1) + " осталось " + left + " блоков, например " + any.toShortString() + " (" + psi(d, h, c.above(5)) + ")");
    }

    /**
     * Чанк на краю загруженного мира держит тикет с соседями, чтобы его руины встали; чанки, которые загрузил этот
     * тикет, сами тикетов не берут — иначе загрузка расползалась бы от края на весь радиус. Каждый держащий тикет
     * чанк был в памяти полным ещё до подрыва.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_hold_spread", skyAccess = true)
    public static void edgeHoldDoesNotSpread(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos e = new ChunkPos(h.absolutePos(CENTER.north(80)));
        BlockPos[] glass = new BlockPos[1];
        LongOpenHashSet loaded = new LongOpenHashSet();
        String[] spread = {null};
        int[] most = {0};
        chunks.addRegionTicket(HOLD, e, 0, e);
        h.onEachTick(() -> {
            if (loaded.isEmpty()) return;
            long[] held = NuclearWorld.get(level).scarHolds();
            most[0] = Math.max(most[0], held.length);
            for (long k : held) {
                if (spread[0] == null && !loaded.contains(k)) spread[0] = new ChunkPos(k) + " (держат " + held.length + ")";
            }
        });
        Runnable done = () -> {
            chunks.removeRegionTicket(HOLD, e, 0, e);
            NuclearStrikes.clear(level);
        };
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(e.x, e.z) != null, "E грузится"))
                .thenExecute(() -> {
                    glass[0] = surface(level, e);
                    level.setBlock(glass[0], Blocks.GLASS.defaultBlockState(), 3);
                    for (ChunkHolder holder : chunks.chunkMap.getChunks()) {
                        // в памяти полным (и опущенные у края видимости — снимок подрыва берёт и их)
                        if (holder.getChunkIfPresentUnchecked(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) instanceof net.minecraft.world.level.chunk.LevelChunk) {
                            loaded.add(holder.getPos().toLong());
                        }
                    }
                    NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 15, true, null, 0.1f);
                })
                .thenWaitUntil(() -> {
                    if (spread[0] != null) {
                        done.run();
                        throw new GameTestAssertException("тикет взял чанк, загруженный чужим тикетом: " + spread[0]);
                    }
                    h.assertFalse(level.getBlockState(glass[0]).is(Blocks.GLASS), "стекло в E цело");
                })
                // и после руин E: загруженные его тикетом чанки дальше ничего не тянут
                .thenIdle(100)
                .thenExecute(() -> {
                    done.run();
                    if (spread[0] != null) throw new GameTestAssertException("тикет взял чанк, загруженный чужим тикетом: " + spread[0]);
                    h.assertTrue(most[0] > 0, "край ни разу не держал тикет: тест ничего не проверил");
                })
                .thenSucceed();
    }

    /**
     * Башня 21×21×80 у эпицентра (15 кт в воздухе, 1 блок = 3.3 м): стены из бетона с окнами, перекрытия через 4 блока,
     * ядро 5×5 из бетона. Давление входит в пробитые окна и доходит по этажам до ядра (затекает во весь воздух за
     * проломами, а не на несколько блоков): ядро опрокидывается, башня рушится — не больше 5 % блоков выше завала,
     * ничего не висит.
     */
    @GameTest(template = "range", timeoutTicks = 80, batch = "nuke_wide_tower", skyAccess = true)
    public static void wideTowerWithCoreFalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = CENTER;
        int half = 10, height = 80, built = 0;
        for (int y = 0; y < height; y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean wall = Math.abs(dx) == half || Math.abs(dz) == half, core = Math.abs(dx) <= 2 && Math.abs(dz) <= 2;
                    BlockState st = null;
                    if (wall) st = (y % 4 == 1 || y % 4 == 2) && Math.floorMod(dx + dz, 2) == 1 ? Blocks.GLASS.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState();
                    else if (core) st = Blocks.CYAN_CONCRETE.defaultBlockState();
                    else if (y % 4 == 3) st = Blocks.WHITE_CONCRETE.defaultBlockState();
                    if (st != null && level.setBlock(h.absolutePos(c.offset(dx, y, dz)), st, 2)) built++;
                }
            }
        }
        Detonation d = detonation(h, c.offset(14, 0, 9), Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        scarAll(h, d, new ColumnScar.Budget(false));
        int standing = 0, core = 0;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int air = 0;
                for (int y = 0; y < height; y++) {
                    BlockState st = h.getBlockState(c.offset(dx, y, dz));
                    if (st.isAir()) {
                        air++;
                        continue;
                    }
                    if (y >= 4) {
                        standing++;
                        if (st.is(Blocks.CYAN_CONCRETE)) core++;
                    }
                    if (air >= 3) h.fail("висит в воздухе " + st + " в " + c.offset(dx, y, dz).toShortString() + " над пустотой в " + air + " блоков");
                    air = 0;
                }
            }
        }
        h.assertTrue(standing * 20 <= built, "башня 21×21 стоит: выше завала " + standing + " блоков из " + built + ", из них ядра " + core
                + " (у основания ядра " + psi(d, h, c.above(1)) + ")");
        h.succeed();
    }

    /**
     * Причал на озере 20×20 (вода — большая, не бассейн): столбы из забора в воде (в блоке вода), настил из досок над
     * водой. При ~7 psi причал рушится, вода блоков над озером не висит, ничего не висит в воздухе, озеро на месте.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_dock", skyAccess = true)
    public static void dockOverLakeFalls(GameTestHelper h) {
        BlockPos lake = new BlockPos(40, 9, 22); // дно — y = 8, вода — 9…11
        for (BlockPos p : BlockPos.betweenClosed(lake, lake.offset(19, 2, 19))) h.setBlock(p, Blocks.WATER);
        BlockPos deck = lake.offset(8, 4, 2); // настил на y = 13, 3 × 12 от берега
        BlockState post = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        for (int dz = 0; dz < 12; dz += 3) {
            for (int dx : new int[] {0, 2}) {
                for (int y = 9; y <= 11; y++) h.setBlock(new BlockPos(deck.getX() + dx, y, deck.getZ() + dz), post);
                h.setBlock(new BlockPos(deck.getX() + dx, 12, deck.getZ() + dz), Blocks.OAK_FENCE);
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(deck.offset(0, 0, -2), deck.offset(2, 0, 11))) h.setBlock(p, Blocks.OAK_PLANKS);
        Detonation d = atPsi(h, CENTER.west(20), true, deck.offset(1, 0, 6), 7);
        scarAll(h, d, new ColumnScar.Budget(false));
        int water = 0;
        for (int dx = 0; dx < 20; dx++) {
            for (int dz = 0; dz < 20; dz++) {
                for (int y = 9; y <= 11; y++) if (h.getBlockState(lake.offset(dx, y - 9, dz)).getFluidState().is(Fluids.WATER)) water++;
                int air = 0;
                for (int y = 12; y <= 20; y++) {
                    BlockPos p = new BlockPos(lake.getX() + dx, y, lake.getZ() + dz);
                    BlockState st = h.getBlockState(p);
                    if (!st.getFluidState().isEmpty()) h.fail("вода над озером: " + st + " в " + p.toShortString() + " (" + psi(d, h, deck) + ")");
                    if (st.isAir()) {
                        air++;
                        continue;
                    }
                    if (air > 0 || y > 12 && h.getBlockState(p.below()).getFluidState().is(Fluids.WATER)) {
                        h.fail("висит над водой: " + st + " в " + p.toShortString() + " (" + psi(d, h, deck) + ")");
                    }
                }
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(deck.offset(0, 0, -2), deck.offset(2, 0, 11))) {
            if (h.getBlockState(p).is(Blocks.OAK_PLANKS)) h.fail("настил стоит в " + p.toShortString() + " (" + psi(d, h, deck) + ")");
        }
        h.assertTrue(water >= 20 * 20 * 3 * 9 / 10, "озеро ушло: воды " + water);
        h.succeed();
    }

    /**
     * Бассейн на крыше кирпичного дома 7×7×8 при ~15 psi: дом рушится, вода бассейна (малая) уходит с ним — ни одного
     * блока воды над пустотой.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_roof_pool", skyAccess = true)
    public static void rooftopPoolDrainsWithHouse(GameTestHelper h) {
        BlockPos c = new BlockPos(44, 12, 29);
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean edge = dx == 0 || dx == 6 || dz == 0 || dz == 6;
                for (int y = 0; y < 8; y++) if (edge || y == 4) h.setBlock(c.offset(dx, y, dz), Blocks.BRICKS);
                h.setBlock(c.offset(dx, 8, dz), Blocks.BRICKS);
                h.setBlock(c.offset(dx, 9, dz), edge ? Blocks.BRICKS : Blocks.WATER);
            }
        }
        Detonation d = atPsi(h, CENTER.west(10), true, c.offset(3, 8, 3), 15);
        scarAll(h, d, new ColumnScar.Budget(false));
        boolean roof = false;
        for (int dx = -2; dx < 9; dx++) {
            for (int dz = -2; dz < 9; dz++) {
                if (h.getBlockState(c.offset(dx, 8, dz)).is(Blocks.BRICKS)) roof = true;
                for (int y = 0; y <= 10; y++) {
                    BlockPos p = c.offset(dx, y, dz);
                    if (!h.getBlockState(p).getFluidState().isEmpty() && h.getBlockState(p.below()).isAir()) {
                        h.fail("вода бассейна висит: " + p.toShortString() + " (" + psi(d, h, c.offset(3, 8, 3)) + ")");
                    }
                }
            }
        }
        h.assertFalse(roof, "крыша дома с бассейном стоит (" + psi(d, h, c.offset(3, 8, 3)) + "): тест ничего не проверил");
        h.succeed();
    }

    /**
     * Перекрытие с одной дырой (стеклянный блок в плите, волна выбивает его сверху) при ~3 psi: дыра не режет щелей.
     * Плита из кирпича 9×9 — выпадает только стекло; такая же 15×15, где у середины опора дальше пролёта кирпича, —
     * тоже (опора стала длиннее меньше чем на 3); каменная 9×9 (грунт в постройке — кладка без арматуры, держится,
     * только пока опора не удлинилась) — выпадает не больше соседей дыры.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_floor_hole", skyAccess = true)
    public static void floorWithHoleHasNoSlits(GameTestHelper h) {
        BlockPos masonry = new BlockPos(40, 12, 4), wide = new BlockPos(40, 12, 16), stone = new BlockPos(40, 12, 34);
        Detonation d = atPsi(h, new BlockPos(8, 12, 24), true, wide.offset(7, 5, 7), 3);
        floor(h, masonry, 9, Blocks.STONE_BRICKS, 2, 4);
        floor(h, wide, 15, Blocks.STONE_BRICKS, 6, 7);
        floor(h, stone, 9, Blocks.STONE, 2, 4);
        scarAll(h, d, new ColumnScar.Budget(false));
        String at = " (" + psi(d, h, wide.offset(7, 5, 7)) + ")";
        slab(h, masonry, 9, Blocks.STONE_BRICKS, 2, 4, 1, "кирпичное 9×9" + at);
        slab(h, wide, 15, Blocks.STONE_BRICKS, 6, 7, 1, "кирпичное 15×15" + at);
        slab(h, stone, 9, Blocks.STONE, 2, 4, 2, "каменное 9×9" + at);
        h.succeed();
    }

    /** Коробка n×n: стены из каменного кирпича в 5 блоков, плита на 5-м из {@code slab}, стекло в плите в (hx, hz). */
    private static void floor(GameTestHelper h, BlockPos c, int n, Block slab, int hx, int hz) {
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) {
                if (dx == 0 || dz == 0 || dx == n - 1 || dz == n - 1) {
                    for (int y = 0; y < 5; y++) h.setBlock(c.offset(dx, y, dz), Blocks.STONE_BRICKS);
                }
                h.setBlock(c.offset(dx, 5, dz), dx == hx && dz == hz ? Blocks.GLASS : slab);
            }
        }
    }

    /** Плита стоит вся, кроме дыры и мест ближе {@code keep} к ней (по сторонам). */
    private static void slab(GameTestHelper h, BlockPos c, int n, Block slab, int hx, int hz, int keep, String what) {
        h.assertFalse(h.getBlockState(c.offset(hx, 5, hz)).is(Blocks.GLASS), "стекло в перекрытии " + what + " цело");
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) {
                if (Math.abs(dx - hx) + Math.abs(dz - hz) < keep) continue;
                BlockPos p = c.offset(dx, 5, dz);
                if (!h.getBlockState(p).is(slab)) h.fail("перекрытие " + what + " с дырой в " + hx + "," + hz + " потеряло " + dx + "," + dz + ": " + h.getBlockState(p));
            }
        }
    }

    /**
     * Навесное уходит с тем, на чём держалось: деревянный дом при ~8 psi рушится, и ни факел на стене, ни дверь, ни
     * ковёр на перекрытии не остаются висеть в воздухе — каждый оставшийся стоит на своём.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_attached", skyAccess = true)
    public static void attachedBlocksGoWithSupport(GameTestHelper h) {
        BlockPos c = new BlockPos(44, 12, 28);
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean wall = dx == 0 || dx == 4 || dz == 0 || dz == 6;
                for (int y = 0; y <= 6; y++) {
                    if (wall || y == 3 || y == 6) h.setBlock(c.offset(dx, y, dz), Blocks.OAK_PLANKS);
                }
                if (!wall) h.setBlock(c.offset(dx, 4, dz), Blocks.RED_CARPET);
            }
        }
        // дверь в западной стене, факелы снаружи на восточной и на северной
        BlockPos door = c.offset(0, 0, 3);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
        h.getLevel().setBlock(h.absolutePos(door), lower, 2);
        h.getLevel().setBlock(h.absolutePos(door.above()), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        for (int dz = 1; dz < 6; dz += 2) h.setBlock(c.offset(5, 2, dz), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
        for (int dx = 1; dx < 4; dx += 2) h.setBlock(c.offset(dx, 2, -1), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
        Detonation d = atPsi(h, CENTER.west(10), true, c.offset(2, 3, 3), 8);
        scarAll(h, d, new ColumnScar.Budget(false));
        ServerLevel level = h.getLevel();
        int planks = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-1, 0, -1), c.offset(5, 7, 7))) {
            BlockState st = h.getBlockState(p);
            if (st.is(Blocks.OAK_PLANKS) && p.getY() - c.getY() >= 3) planks++;
            boolean attached = st.is(BlockTags.WOOL_CARPETS) || st.is(BlockTags.DOORS) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.TORCH);
            if (attached && !st.canSurvive(level, h.absolutePos(p))) h.fail("висит без опоры: " + st + " в " + p.toShortString() + " (" + psi(d, h, c) + ")");
        }
        h.assertTrue(planks < 20, "дом стоит (" + psi(d, h, c.offset(2, 3, 3)) + ", досок выше 3-го блока " + planks + "): тест ничего не проверил");
        h.succeed();
    }

    /**
     * Песчаный навес над обрывом из песчаника (природный: опоры под ним не было и до удара) при ~3 psi стоит — волна
     * его не сломала и опоры не отняла. Рядом с навесом волна выбивает стекло: обрушение считается, и навес проходит
     * его проверку, а не пропускается.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_sand", skyAccess = true)
    public static void sandOverhangStays(GameTestHelper h) {
        BlockPos cliff = new BlockPos(46, 12, 28);
        for (BlockPos p : BlockPos.betweenClosed(cliff, cliff.offset(4, 3, 4))) h.setBlock(p, Blocks.SANDSTONE);
        BlockPos overhang = cliff.offset(-2, 4, 0);
        for (BlockPos p : BlockPos.betweenClosed(overhang, overhang.offset(6, 0, 4))) h.setBlock(p, Blocks.SAND);
        BlockPos glass = overhang.offset(7, 0, 2);
        h.setBlock(glass, Blocks.GLASS);
        Detonation d = atPsi(h, CENTER.west(10), true, overhang, 3);
        scarAll(h, d, new ColumnScar.Budget(false));
        h.assertFalse(h.getBlockState(glass).is(Blocks.GLASS), "стекло у навеса цело: обрушение не считалось (" + psi(d, h, overhang) + ")");
        for (BlockPos p : BlockPos.betweenClosed(overhang, overhang.offset(6, 0, 4))) {
            if (!h.getBlockState(p).is(Blocks.SAND)) h.fail("песчаный навес упал в " + p.toShortString() + ": " + h.getBlockState(p) + " (" + psi(d, h, overhang) + ")");
        }
        h.succeed();
    }

    /**
     * Вода за стеклом перепада не снимает: стенка аквариума из стекла, за ней вода, лицом к взрыву — воздух, при ~2 psi
     * лопается (давление на лицо, за хрупким стеклом ничего твёрдого). Стекло, со всех сторон окружённое водой, —
     * цело: давлению не к чему приложиться.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_aquarium", skyAccess = true)
    public static void glassBackedByWaterBreaks(GameTestHelper h) {
        BlockPos front = new BlockPos(44, 12, 28); // стекло на x = 44, вода — 45…47, задняя стенка — 48
        for (int dz = -1; dz <= 5; dz++) {
            for (int y = 0; y < 3; y++) {
                for (int dx = 0; dx <= 4; dx++) {
                    boolean side = dz == -1 || dz == 5;
                    Block b = side || dx == 4 ? Blocks.STONE_BRICKS : dx == 0 ? Blocks.GLASS : Blocks.WATER;
                    h.setBlock(front.offset(dx, y, dz), b);
                }
            }
        }
        BlockPos sunk = front.offset(2, 0, 2); // на дне: и когда вода стечёт, стоит на грунте
        h.setBlock(sunk, Blocks.GLASS);
        Detonation d = atPsi(h, CENTER.west(10), true, front.offset(0, 1, 2), 2);
        scarAll(h, d, new ColumnScar.Budget(false));
        String at = " (" + psi(d, h, front.offset(0, 1, 2)) + ")";
        for (int dz = 0; dz < 5; dz++) {
            for (int y = 0; y < 3; y++) {
                if (h.getBlockState(front.offset(0, y, dz)).is(Blocks.GLASS)) h.fail("стекло аквариума с водой за ним цело в " + front.offset(0, y, dz).toShortString() + at);
            }
        }
        h.assertTrue(h.getBlockState(sunk).is(Blocks.GLASS), "стекло в толще воды разбито" + at);
        h.succeed();
    }

    /**
     * Неразрушимое (коренная порода, обсидиан, барьер, блок света) не падает и не отрывается даже в воздухе без опоры и
     * держит, как земля: бетон на висящей коренной породе стоит, хотя рядом волна выбила стекло.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_fixed", skyAccess = true)
    public static void indestructibleBlocksStandAndHold(GameTestHelper h) {
        BlockPos at = new BlockPos(44, 12, 30);
        for (int y = 0; y < 6; y++) h.setBlock(at.offset(0, y, 0), Blocks.BARRIER);
        h.setBlock(at.offset(0, 6, 0), Blocks.LIGHT);
        h.setBlock(at.offset(3, 8, 0), Blocks.OBSIDIAN);
        BlockPos rock = at.offset(3, 8, 4);
        h.setBlock(rock, Blocks.BEDROCK);
        h.setBlock(rock.above(), Blocks.WHITE_CONCRETE);
        h.setBlock(rock.above().east(), Blocks.GLASS);
        Detonation d = atPsi(h, CENTER.west(10), true, rock, 2);
        scarAll(h, d, new ColumnScar.Budget(false));
        String where = " (" + psi(d, h, rock) + ")";
        for (int y = 0; y < 6; y++) h.assertTrue(h.getBlockState(at.offset(0, y, 0)).is(Blocks.BARRIER), "барьер упал на " + y + where);
        h.assertTrue(h.getBlockState(at.offset(0, 6, 0)).is(Blocks.LIGHT), "блок света снят" + where);
        h.assertTrue(h.getBlockState(at.offset(3, 8, 0)).is(Blocks.OBSIDIAN), "висящий обсидиан упал" + where);
        h.assertTrue(h.getBlockState(rock).is(Blocks.BEDROCK), "висящая коренная порода упала" + where);
        h.assertFalse(h.getBlockState(rock.above().east()).is(Blocks.GLASS), "стекло цело: тест ничего не проверил" + where);
        h.assertTrue(h.getBlockState(rock.above()).is(Blocks.WHITE_CONCRETE), "бетон на коренной породе упал" + where);
        h.succeed();
    }

    /**
     * Тики жидкости: волна выбивает стеклянные блоки шахматкой над разливом воды 32×32 (большая вода — не стекает;
     * в воде стекло не ломается: вода за ним не даёт перепада) при ~3 psi — воде под каждым тик. План чанка ставит тики не больше предела за секунду, остальные — в
     * следующие секунды; хотя бы у одного чанка их больше предела.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_fluid_ticks", skyAccess = true)
    public static void fluidTicksStayUnderLimit(GameTestHelper h) {
        BlockPos a = new BlockPos(16, 12, 16);
        for (int dx = -1; dx <= 32; dx++) {
            for (int dz = -1; dz <= 32; dz++) {
                boolean rim = dx < 0 || dz < 0 || dx == 32 || dz == 32;
                h.setBlock(a.offset(dx, 0, dz), rim ? Blocks.STONE_BRICKS : Blocks.WATER);
                if (!rim && (dx + dz) % 2 == 0) h.setBlock(a.offset(dx, 1, dz), Blocks.GLASS);
            }
        }
        Detonation d = atPsi(h, new BlockPos(-60, 12, 32), true, a.offset(16, 0, 16), 3);
        List<RuinPlan> plans = scarAll(h, d, new ColumnScar.Budget(false));
        int most = 0;
        for (RuinPlan p : plans) {
            most = Math.max(most, p.fluidTickCount());
            h.assertTrue(p.maxFluidTicksPerSecond() <= 64, "тиков жидкости за секунду " + p.maxFluidTicksPerSecond() + " из " + p.fluidTickCount());
        }
        h.assertTrue(most > 64, "у чанков не больше 64 тиков жидкости (" + most + ", " + psi(d, h, a.offset(16, 0, 16)) + "): предел не проверен");
        h.succeed();
    }

    /**
     * Руины заранее на границе квадратов подготовки (каждый 5-й чанк): глухая стена из терракоты в последнем столбце
     * чанка одного квадрата, дом с перекрытиями — в соседнем квадрате (15 кт в воздухе, 1 блок = 20 м). Квадраты
     * готовятся порознь, а руины у их границы — те же, что и в середине: стена рушится с домом. Если на площадке нет
     * границы квадратов (1 раз из 25 по её месту), стена стоит на обычной границе чанков.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_prep_border", skyAccess = true)
    public static void wallOnPrepTileBorderFalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // граница квадратов по x: чанк cx ≡ 0 (mod 5), дом — в нём, стена — в последнем столбце чанка cx − 1
        BlockPos wall = null;
        boolean alongX = true;
        for (int rx = 8; rx <= 50 && wall == null; rx++) {
            int ax = h.absolutePos(new BlockPos(rx, 0, 0)).getX();
            if ((ax & 15) == 0 && Math.floorMod(ax >> 4, 5) == 0) wall = new BlockPos(rx - 1, 12, 26);
        }
        for (int rz = 8; rz <= 50 && wall == null; rz++) {
            int az = h.absolutePos(new BlockPos(0, 0, rz)).getZ();
            if ((az & 15) == 0 && Math.floorMod(az >> 4, 5) == 0) {
                wall = new BlockPos(26, 12, rz - 1);
                alongX = false;
            }
        }
        if (wall == null) wall = chunkCorner(h).west();
        int height = 30;
        for (int y = 0; y < height; y++) {
            for (int u = 0; u <= 6; u++) {
                for (int v = 0; v <= 6; v++) {
                    // u — поперёк границы (0 — стена), v — вдоль
                    boolean edge = u == 0 || u == 6 || v == 0 || v == 6;
                    BlockState st = null;
                    if (u == 0) st = Blocks.BROWN_TERRACOTTA.defaultBlockState();
                    else if (edge) st = y % 5 == 2 && (u + v) % 2 == 0 ? Blocks.GLASS.defaultBlockState() : Blocks.BROWN_TERRACOTTA.defaultBlockState();
                    else if (y % 5 == 4) st = Blocks.WHITE_TERRACOTTA.defaultBlockState();
                    if (st != null) level.setBlock(h.absolutePos(alongX ? wall.offset(u, y, v) : wall.offset(v, y, u)), st, 2);
                }
            }
        }
        BlockPos wallAt = wall;
        boolean x = alongX;
        detonation(h, CENTER, 0, 15, 0.05f); // только засыпка слоя под площадкой: подрыв — по расписанию
        double scale = AirstrikeConfig.SERVER.nukeEffectsScale.get();
        AirstrikeConfig.SERVER.nukeEffectsScale.set(0.05);
        NuclearWorld w = NuclearWorld.get(level);
        NuclearWorld.useClock(level.getServer(), WorkClock.counting(1_000_000L));
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, true, now, now + 300, target, java.util.Optional.empty(), false));
        Runnable restore = () -> {
            AirstrikeConfig.SERVER.nukeEffectsScale.set(scale);
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
        };
        h.onEachTick(() -> {
            Detonation d = events.detonations().stream().filter(e -> e.burst().distanceTo(target) < 400).findFirst().orElse(null);
            String failure = null;
            if (d != null) {
                boolean scarred = true;
                for (int v = 0; v <= 6 && scarred; v++) {
                    BlockPos p = h.absolutePos(x ? wallAt.offset(0, 0, v) : wallAt.offset(v, 0, 0));
                    var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
                    var house = level.getChunkSource().getChunkNow((p.getX() + (x ? 1 : 0)) >> 4, (p.getZ() + (x ? 0 : 1)) >> 4);
                    scarred = chunk != null && house != null && chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id()
                            && house.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
                }
                if (!scarred || w.plannedChunks() > 0 || w.prepTiles() > 0) {
                    if (level.getGameTime() < now + 1150) return;
                    failure = "руины у стены не встали: осталось " + w.plannedChunks() + ", квадратов " + w.prepTiles();
                } else if (w.ruinStats()[0] == 0) {
                    failure = "ни один чанк не встал по готовому плану";
                } else {
                    for (int v = 0; v <= 6 && failure == null; v++) {
                        for (int y = 5; y < height; y++) {
                            BlockPos p = x ? wallAt.offset(0, y, v) : wallAt.offset(v, y, 0);
                            if (!h.getBlockState(p).isAir()) {
                                failure = "стена на границе квадратов стоит: " + h.getBlockState(p) + " в " + p.toShortString();
                                break;
                            }
                        }
                    }
                }
                restore.run();
                if (failure != null) throw new GameTestAssertException(failure);
                h.succeed();
            } else if (level.getGameTime() > now + 1150) {
                restore.run();
                throw new GameTestAssertException("подрыва не было");
            }
        });
    }
}
