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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.grid.ChunkLights;
import ua.zentix.airstrike.grid.GridLights;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.world.ColumnScar;
import ua.zentix.airstrike.nuclear.world.FarLods;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.RuinPlan;
import ua.zentix.airstrike.nuclear.world.RuinPlanner;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
                h.assertTrue(copy.getNoiseBiome(mid.getX() >> 2, mid.getY() >> 2, mid.getZ() >> 2).value() == chunk.getNoiseBiome(mid.getX() >> 2, mid.getY() >> 2, mid.getZ() >> 2).value(),
                        "биом копии не тот");
                compared++;
                cells += plan.changedBlocks();
            }
        }
        h.assertTrue(cells > 0, "город не разрушен: сравнивать нечего");
        Airstrike.LOG.info("LOD вдали: {} копий совпали с руинами, {} мест", compared, cells);
        h.succeed();
    }

    /**
     * Чанк зоны, которого нет на диске (край исследованного мира): копию с руинами с диска не собрать, поэтому его квадрат
     * 5×5 берётся в мир ({@code FarZone}) — ваниль генерирует его в фоне, руины встают путём загрузки (у свежего чанка
     * нет отметки {@code chunk_scar}: очередь руин ставит его сама), чанк с руинами уходит в DH, квадрат отпускается.
     * Мир GameTest плоский — ломать нечего, поэтому на свежий чанк до прихода волны ставится стекло: его волна бьёт.
     * Сервер идёт в темпе игры: генерация — фоновая, по настенному времени.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "dh_far_zone", skyAccess = true)
    public static void farZoneLoadsMissingChunksForDh(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        // далеко за площадкой: там ничего не сгенерировано; чанк — середина своего квадрата 5×5 (квадрат один)
        ChunkPos near = new ChunkPos(h.absolutePos(CENTER).offset(6000, 0, -6000));
        ChunkPos c = new ChunkPos(Math.floorDiv(near.x, 5) * 5 + 2, Math.floorDiv(near.z, 5) * 5 + 2);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "чанк " + c + " уже в памяти");
        // счётчики FarLods — на весь мир, с прошлых проверок: сравнивается прирост
        long[] lods0 = FarLods.get(level).stats(), zone0 = FarLods.get(level).zoneStats();
        BlockPos[] glass = {null};
        // что ушло в DH: чанк и стояли ли в нём руины в момент отправки
        int[] sent = {0, 0};
        DhUpdates.testSink(level, (l, chunk) -> {
            if (!chunk.getPos().equals(c) || !(chunk instanceof LevelChunk lc)) return;
            sent[0]++;
            if (glass[0] != null && lc.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) > 0 && lc.getBlockState(glass[0]).isAir()) sent[1]++;
        });
        FarLods.testViewer(level, c);
        StrikeGameTests.afterTest(h, () -> {
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        // подрыв в воздухе над чанком; волна дойдёт до его середины через WAVE тиков — к тому времени он сгенерирован
        int wave = 400;
        double ground = level.getMinBuildHeight() + 3, height = 40;
        Vec3 burst = new Vec3(c.getMiddleBlockX() + 0.5, ground + height, c.getMiddleBlockZ() + 0.5);
        Detonation probe = new Detonation(0, burst, ground, 15, false, 0, 0, 0, 20_000, 7, 0.05f, false);
        long now = level.getGameTime();
        Detonation d = new Detonation(NuclearGameTests.IDS.incrementAndGet(), burst, ground, 15, false, now + wave - (long) probe.arrivalTicks(height), 0, 0,
                20_000, 7, 0.05f, false);
        NuclearEvents.get(level).add(d);
        FarLods.request(level, c.toLong(), true);
        h.onEachTick(() -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(c.x, c.z);
            if (glass[0] == null && chunk != null) {
                h.assertTrue(level.getGameTime() < now + wave - 20, "квадрат грузился дольше " + (wave - 20) + " тиков");
                h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).isEmpty(), "у свежего чанка уже есть отметка руин");
                // верхний блок столбца (а не первый воздух, как у карты высот)
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
                glass[0] = c.getBlockAt(8, top + 1, 8);
                for (int y = 0; y < 3; y++) level.setBlock(glass[0].above(y), Blocks.GLASS.defaultBlockState(), 2);
            }
        });
        h.succeedWhen(() -> {
            long[] zone = FarLods.get(level).zoneStats();
            long partial = FarLods.get(level).stats()[6] - lods0[6], taken = zone[2] - zone0[2], released = zone[3] - zone0[3], expired = zone[4] - zone0[4];
            h.assertTrue(glass[0] != null, "чанк не загрузился");
            h.assertTrue(partial == 1, "не целых на диске: " + partial);
            h.assertTrue(sent[0] >= 1, "чанк с руинами не ушёл в DH");
            h.assertTrue(sent[1] >= 1, "в DH ушёл чанк без руин (отправок " + sent[0] + ")");
            h.assertTrue(taken == 1 && released == 1 && zone[1] == 0 && zone[0] == 0, "квадраты: ждут " + zone[0] + ", держатся " + zone[1] + ", взято " + taken
                    + ", отпущено " + released);
            h.assertTrue(expired == 0 && zone[6] <= 2,"по сроку " + expired + ", в загрузке разом " + zone[6]);
        });
    }

    /**
     * Остановка сервера, пока квадрат {@code FarZone} грузится: его тикет отпускается вместе с остальной работой руин
     * ({@link NuclearWorld#onServerStopping}, до {@code util/StopDrain}) — генерацию после этого никто не держит. Сервер
     * идёт в темпе игры: квадрат берётся после ответа потока ввода-вывода.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "dh_far_zone_stop", skyAccess = true)
    public static void farZoneReleasedOnStop(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos near = new ChunkPos(h.absolutePos(CENTER).offset(-6000, 0, 6000));
        ChunkPos c = new ChunkPos(Math.floorDiv(near.x, 5) * 5 + 2, Math.floorDiv(near.z, 5) * 5 + 2);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "чанк " + c + " уже в памяти");
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, c);
        StrikeGameTests.afterTest(h, () -> {
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        FarLods.request(level, c.toLong(), true);
        h.onEachTick(() -> {
            if (FarLods.get(level).zoneStats()[1] == 0) return;
            h.assertTrue(farZoneTickets(level, c) > 0, "квадрат взят, а тикета нет");
            boolean stopped = NuclearWorld.onServerStopping(level.getServer());
            long[] zone = FarLods.get(level).zoneStats();
            h.assertTrue(stopped, "фоновые потоки руин не остановились за срок");
            h.assertTrue(zone[0] == 0 && zone[1] == 0, "после остановки квадратов ждёт " + zone[0] + ", держится " + zone[1]);
            h.assertTrue(farZoneTickets(level, c) == 0, "после остановки остались тикеты квадратов: " + farZoneTickets(level, c));
            h.succeed();
        });
    }

    /**
     * Настройка {@code nuclear.far_zone} выключена: чанк, которого нет на диске, так и остаётся несгенерированным. Сервер
     * идёт в темпе игры: заголовок чанка читается потоком ввода-вывода, по настенному времени.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "dh_far_zone_off", skyAccess = true)
    public static void farZoneOffGeneratesNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos near = new ChunkPos(h.absolutePos(CENTER).offset(6000, 0, 6000));
        ChunkPos c = new ChunkPos(Math.floorDiv(near.x, 5) * 5 + 2, Math.floorDiv(near.z, 5) * 5 + 2);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "чанк " + c + " уже в памяти");
        // счётчики FarLods — на весь мир, с прошлых проверок: сравнивается прирост
        long partial0 = FarLods.get(level).stats()[6], taken0 = FarLods.get(level).zoneStats()[2];
        boolean was = AirstrikeConfig.SERVER.nukeFarZone.get();
        AirstrikeConfig.SERVER.nukeFarZone.set(false);
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, c);
        StrikeGameTests.afterTest(h, () -> {
            AirstrikeConfig.SERVER.nukeFarZone.set(was);
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        FarLods.request(level, c.toLong(), true);
        h.succeedWhen(() -> {
            h.assertTrue(FarLods.get(level).stats()[6] - partial0 == 1, "заголовок с диска ещё не прочитан");
            h.assertTrue(FarLods.get(level).zoneStats()[2] == taken0, "квадрат взят при выключенной настройке");
            h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "чанк " + c + " загружен");
        });
    }

    /** Тикеты квадрата {@code FarZone} с серединой {@code c} во всём мире: ключ его района — {@code UUID(0, c)}. */
    private static int farZoneTickets(ServerLevel level, ChunkPos c) {
        return TicketProbe.count(level, t -> true, new UUID(0L, c.toLong()));
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
