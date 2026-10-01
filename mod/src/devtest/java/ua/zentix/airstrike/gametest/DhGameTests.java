package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.grid.ChunkLights;
import ua.zentix.airstrike.grid.GridLights;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.world.ColumnScar;
import ua.zentix.airstrike.nuclear.world.FarLods;
import ua.zentix.airstrike.nuclear.world.RuinPlan;
import ua.zentix.airstrike.nuclear.world.RuinPlanner;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;

/**
 * Изменения мира — в LOD Distant Horizons ({@link DhUpdates}, {@link FarLods}). Самого DH в проверках нет: чанки уходят
 * в подставленный приёмник ({@link DhUpdates#testSink}).
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DhGameTests {
    /** Середина площадки «range» на уровне травы (пол — 11 слоёв). */
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private DhGameTests() {}

    /** Чанк уходит в приёмник: тик → сколько раз за тик, и все отправки по порядку. */
    private record Sent(Long2IntOpenHashMap perTick, LongArrayList chunks, List<Long> ticks) {
        Sent() {
            this(new Long2IntOpenHashMap(), new LongArrayList(), new ArrayList<>());
        }

        int count(ChunkPos c) {
            int n = 0;
            for (int i = 0; i < chunks.size(); i++) if (chunks.getLong(i) == c.toLong()) n++;
            return n;
        }

        long first(ChunkPos c) {
            for (int i = 0; i < chunks.size(); i++) if (chunks.getLong(i) == c.toLong()) return ticks.get(i);
            return -1;
        }
    }

    private static Sent sink(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Sent sent = new Sent();
        DhUpdates.testSink(level, (l, chunk) -> {
            long now = l.getGameTime();
            sent.perTick.addTo(now, 1);
            sent.chunks.add(chunk.getPos().toLong());
            sent.ticks.add(now);
        });
        StrikeGameTests.afterTest(h, () -> DhUpdates.testSink(level, null));
        return sent;
    }

    /**
     * Взрыв боевой части: чанк воронки уходит в LOD, когда взрыв отпустил район, — один раз сразу (порции взрыва —
     * одним вызовом), второй — когда район перестал осыпаться. За тик — не больше предела очереди.
     */
    @GameTest(template = "range", timeoutTicks = 800, batch = "dh_blast", skyAccess = true)
    public static void blastReachesDhTwice(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Sent sent = sink(h);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(CENTER.below(2)));
        ChunkPos crater = new ChunkPos(BlockPos.containing(at));
        long start = level.getGameTime();
        Warheads.Probe blast = Warheads.testBlast(level, at, 6, true, false, System::nanoTime, 5_000_000L);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "взрыв не кончился");
            h.assertTrue(sent.count(crater) >= 2, "чанк воронки ушёл в LOD " + sent.count(crater) + " раз");
            long first = sent.first(crater);
            h.assertTrue(first > start, "чанк ушёл до взрыва");
            for (var e : sent.perTick.long2IntEntrySet()) h.assertTrue(e.getIntValue() <= 20, "за тик " + e.getLongKey() + " ушло " + e.getIntValue());
            int sameTick = 0;
            for (int i = 0; i < sent.chunks.size(); i++) if (sent.chunks.getLong(i) == crater.toLong() && sent.ticks.get(i) == first) sameTick++;
            h.assertTrue(sameTick == 1, "чанк ушёл несколько раз в одном тике");
        });
    }

    /**
     * Отметки одного чанка подряд: один вызов, повтор — не раньше чем через 40 тиков после него (DH ещё держит чанк в
     * очереди и пропустил бы повтор молча).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "dh_pace", skyAccess = true)
    public static void marksCoalesceAndResendLater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Sent sent = sink(h);
        ChunkPos c = new ChunkPos(h.absolutePos(CENTER));
        long now = level.getGameTime();
        DhUpdates.mark(level, c, now);
        DhUpdates.mark(level, c, now + 1);
        DhUpdates.mark(level, c, now + 2);
        h.runAfterDelay(5, () -> DhUpdates.mark(level, c, level.getGameTime()));
        h.succeedWhen(() -> {
            h.assertTrue(sent.count(c) == 2, "отправок " + sent.count(c));
            long a = sent.ticks.get(0), b = sent.ticks.get(1);
            h.assertTrue(b - a >= 40, "повтор через " + (b - a) + " тиков");
        });
    }

    /**
     * Копия чанка с диска с руинами плана ({@link FarLods#testCopy}: данные сохранения, разобранные как чанк не в мире) —
     * блок в блок то же, что чанк после настоящей подмены, и биомы те же.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "dh_ruins", skyAccess = true)
    public static void farCopyMatchesRuins(GameTestHelper h) {
        RuinCostGameTests.city(h);
        ServerLevel level = h.getLevel();
        Detonation d = NuclearGameTests.atPsi(h, new BlockPos(-600, 0, 32), true, new BlockPos(32, 1, 32), 12);
        int compared = 0, cells = 0;
        for (int rx : new int[]{24, 40}) {
            for (int rz : new int[]{24, 40}) {
                ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz)));
                for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                    for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.x + dx, c.z + dz);
                }
                LevelChunk chunk = level.getChunk(c.x, c.z);
                RuinPlan plan = RuinPlanner.planFresh(level, d, chunk, false);
                ProtoChunk copy = FarLods.testCopy(level, chunk, plan, false);
                h.assertTrue(copy != null, "план не подошёл к копии чанка " + c);
                h.assertTrue(plan.apply(level, chunk, new ColumnScar.Budget(false)), "план чанка " + c + " устарел");
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockPos p = c.getBlockAt(x, y, z);
                            BlockState real = chunk.getBlockState(p), far = copy.getBlockState(p);
                            h.assertTrue(real == far, "в копии " + p + " — " + far + ", в мире — " + real);
                        }
                    }
                }
                BlockPos mid = c.getMiddleBlockPosition(CENTER.getY());
                h.assertTrue(copy.getNoiseBiome(mid.getX() >> 2, mid.getY() >> 2, mid.getZ() >> 2).is(chunk.getNoiseBiome(mid.getX() >> 2, mid.getY() >> 2, mid.getZ() >> 2)),
                        "биом копии не тот");
                compared++;
                cells += plan.changedBlocks();
            }
        }
        h.assertTrue(cells > 0, "город не разрушен: сравнивать нечего");
        Airstrike.LOG.info("LOD вдали: {} копий совпали с руинами, {} мест", compared, cells);
        h.succeed();
    }

    /** Погасший квартал в копии: каждая лампа — её двойник без света, остальное не тронуто. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "dh_lamps", skyAccess = true)
    public static void farCopyDarkensLamps(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos lamp = h.absolutePos(CENTER.above(2)), sea = h.absolutePos(CENTER.above(3));
        level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 2);
        level.setBlock(sea, Blocks.SEA_LANTERN.defaultBlockState(), 2);
        LevelChunk chunk = level.getChunkAt(lamp);
        ProtoChunk copy = FarLods.testCopy(level, chunk, null, false);
        h.assertTrue(copy != null, "копии нет");
        int n = ChunkLights.applyToCopy(copy.getSections(), true);
        h.assertTrue(n >= 2, "погашено ламп " + n);
        h.assertTrue(copy.getBlockState(lamp) == GridLights.unlit(Blocks.GLOWSTONE.defaultBlockState()), "светокамень: " + copy.getBlockState(lamp));
        h.assertTrue(copy.getBlockState(sea) == GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()), "морской фонарь: " + copy.getBlockState(sea));
        h.assertTrue(copy.getBlockState(lamp.below()) == chunk.getBlockState(lamp.below()), "тронут блок не лампа");
        h.succeed();
    }
}
