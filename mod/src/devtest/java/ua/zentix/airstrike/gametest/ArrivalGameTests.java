package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.strike.ArrivalTickets;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Место входа игрока грузится раньше районов залпа ({@link ArrivalTickets}).
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArrivalGameTests {
    /** Загрузка места игроком: тикет уровня 33 на один чанк, как {@code UNKNOWN} у синхронного {@code getChunk}. */
    private static final TicketType<ChunkPos> PLAYER_LOAD = TicketType.create("airstrike_test_player_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SALVO = 30, SPREAD = 150;
    /** Выгруженный чанк лежит столько, прежде чем его грузят снова (запись на диск). */
    private static final int SETTLE_TICKS = 60;

    private ArrivalGameTests() {}

    /**
     * Место, где игрок уже был (чанк P и округа сгенерированы и выгружены), и через 5 тиков после залпа из 30 районов с
     * разбросом 150 в свежий рельеф — вход: тикет входа и загрузка P, как её просит тик игрока. Без тикета входа
     * загрузка P стоит в очереди за районами залпа (уровни 29–33 против 33) — на стенде сервер ждал её в тике. С ним P
     * готов, пока районы залпа ещё почти не готовы: засчитывается, если к готовности P готово меньше половины чанков
     * районов, которые не были готовы при входе.
     */
    @GameTest(template = "range", timeoutTicks = 3600, batch = "arrival", skyAccess = true)
    public static void arrivalLoadsBeforeSalvoAreas(GameTestHelper h) {
        arrivalDuringSalvo(h, FlightTickets.DISTANCE);
    }

    /**
     * То же во время залпа «Ланцетов»: их район цели больше ({@link FlightTickets#LOITER_DISTANCE}, центр — уровень 27). С
     * тикетом входа уровня 28 место входа ждало весь залп: готово через 2276 тиков, после всех 888 чанков районов.
     */
    @GameTest(template = "range", timeoutTicks = 3600, batch = "arrival_loiter", skyAccess = true)
    public static void arrivalLoadsBeforeLoiterAreas(GameTestHelper h) {
        arrivalDuringSalvo(h, FlightTickets.LOITER_DISTANCE);
    }

    private static void arrivalDuringSalvo(GameTestHelper h, int distance) {
        ServerLevel level = h.getLevel();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos p = new ChunkPos(base.x + 190, base.z);
        // округа P в дальности тикета входа и ещё 2 — как у места, где игрок уже был
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) level.getChunk(p.x + dx, p.z + dz, ChunkStatus.FULL, true);
        }
        StrikeGameTests.gameSpeed(h);
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        List<ChunkPos> targets = new ArrayList<>();
        List<UUID> keys = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.release(level.getServer(), who, slot);
            level.getChunkSource().chunkMap.getDistanceManager().removeTicket(PLAYER_LOAD, p, ChunkLevel.byStatus(FullChunkStatus.FULL), p);
            for (int i = 0; i < targets.size(); i++) FlightTickets.hold(level, targets.get(i), distance, keys.get(i), false);
        });
        int[] tick = {0}, unloadedAt = {-1}, salvoAt = {-1}, arrivedAt = {-1}, pendingAtArrival = {-1};
        h.onEachTick(() -> {
            tick[0]++;
            if (salvoAt[0] < 0) {
                // P выгружен и лежит на диске
                if (level.getChunkSource().chunkMap.getVisibleChunkIfPresent(p.toLong()) != null) {
                    unloadedAt[0] = -1;
                    return;
                }
                if (unloadedAt[0] < 0) unloadedAt[0] = tick[0];
                if (tick[0] - unloadedAt[0] < SETTLE_TICKS) return;
                Random rnd = new Random(20260930L);
                int cx = base.x - 190, cz = base.z + 190;
                for (int i = 0; i < SALVO; i++) {
                    int bx = cx * 16 + rnd.nextInt(2 * SPREAD + 1) - SPREAD, bz = cz * 16 + rnd.nextInt(2 * SPREAD + 1) - SPREAD;
                    ChunkPos target = new ChunkPos(bx >> 4, bz >> 4);
                    UUID key = UUID.randomUUID();
                    FlightTickets.hold(level, target, distance, key, true);
                    targets.add(target);
                    keys.add(key);
                }
                salvoAt[0] = tick[0];
                return;
            }
            if (arrivedAt[0] < 0) {
                if (tick[0] - salvoAt[0] < 5) return;
                // вход: событие входа ставит тикет, тик игрока просит свой чанк
                ArrivalTickets.arrive(level, p, who, slot);
                level.getChunkSource().chunkMap.getDistanceManager().addTicket(PLAYER_LOAD, p, ChunkLevel.byStatus(FullChunkStatus.FULL), p);
                pendingAtArrival[0] = pending(level, targets, distance);
                arrivedAt[0] = tick[0];
                h.assertTrue(pendingAtArrival[0] > 0, "районы залпа готовы сразу — проверка ничего не проверила");
                return;
            }
            if (!Terrain.ready(level, p.x, p.z)) return;
            int left = pending(level, targets, distance);
            Airstrike.LOG.info("Вход во время залпа (районы {}): P готов через {} тиков, чанков районов не готово {} из {} при входе",
                    distance, tick[0] - arrivedAt[0], left, pendingAtArrival[0]);
            if (left * 2 < pendingAtArrival[0]) {
                throw new GameTestAssertException("место входа ждало районы залпа: готово " + (pendingAtArrival[0] - left) + " из "
                        + pendingAtArrival[0] + " их чанков раньше него");
            }
            h.assertTrue(ArrivalTickets.count(level, who) == 1, "тикет входа снят раньше срока");
            h.succeed();
        });
    }

    /** Тикет входа снимается сам через {@link ArrivalTickets#LIFESPAN} тиков, новый вход заменяет прежний. */
    @GameTest(template = "range", timeoutTicks = ArrivalTickets.LIFESPAN + 100, batch = "arrival_lifespan")
    public static void arrivalTicketExpires(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        StrikeGameTests.afterTest(h, () -> ArrivalTickets.release(level.getServer(), who, slot));
        ArrivalTickets.arrive(level, c, who, slot);
        ArrivalTickets.arrive(level, new ChunkPos(c.x + 1, c.z), who, slot);
        h.assertTrue(ArrivalTickets.count(level, who) == 1, "новый вход не заменил прежний тикет");
        long placed = level.getGameTime();
        h.onEachTick(() -> {
            long age = level.getGameTime() - placed;
            int n = ArrivalTickets.count(level, who);
            if (age < ArrivalTickets.LIFESPAN) {
                if (n != 1) throw new GameTestAssertException("тикет входа снят через " + age + " тиков, раньше срока");
                return;
            }
            if (n != 0) {
                if (age > ArrivalTickets.LIFESPAN + 2) throw new GameTestAssertException("тикет входа стоит " + age + " тиков");
                return;
            }
            h.succeed();
        });
    }

    /**
     * Тикет входа снимается, когда место держат тикеты игроков ({@code TicketType.PLAYER}) на квадрате радиуса
     * {@code DISTANCE − 2}, не дальше обзора. Тикеты игроков здесь ставятся вручную — без игрока: мок-игрок ломается о
     * пакеты Create.
     */
    @GameTest(template = "range", batch = "arrival_players")
    public static void arrivalReleasedWhenPlayersHoldPlace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        int r = ArrivalTickets.DISTANCE - 2, playerLevel = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
        var distances = level.getChunkSource().chunkMap.getDistanceManager();
        List<ChunkPos> placed = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.release(level.getServer(), who, slot);
            for (ChunkPos pos : placed) distances.removeTicket(TicketType.PLAYER, pos, playerLevel, pos);
        });
        ChunkPos corner = new ChunkPos(c.x + r, c.z + r);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(c.x + dx, c.z + dz);
                if (pos.equals(corner)) continue;
                distances.addTicket(TicketType.PLAYER, pos, playerLevel, pos);
                placed.add(pos);
            }
        }
        // без угла квадрата тикет стоит
        ArrivalTickets.arrive(level, c, who, slot);
        ArrivalTickets.tick(level.getServer(), who, slot, 32);
        h.assertTrue(ArrivalTickets.count(level, who) == 1, "тикет входа снят, хотя угол квадрата не держит ни один игрок");
        // обзор меньше квадрата: хватает квадрата обзора
        ArrivalTickets.tick(level.getServer(), who, slot, r - 1);
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "тикет входа не снят, хотя квадрат обзора держат игроки");
        // весь квадрат
        ArrivalTickets.arrive(level, c, who, slot);
        distances.addTicket(TicketType.PLAYER, corner, playerLevel, corner);
        placed.add(corner);
        ArrivalTickets.tick(level.getServer(), who, slot, 32);
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "тикет входа не снят, хотя весь квадрат держат игроки");
        // слот пуст: следующий тик ничего не снимает и не падает
        ArrivalTickets.tick(level.getServer(), who, slot, 32);
        h.succeed();
    }

    /**
     * Тикет на месте возрождения (ставится при смерти) стоит, пока открыт экран смерти, — дольше срока тикета входа и
     * даже когда место держат тикеты игроков; возрождение (вход на новом месте) его заменяет.
     */
    @GameTest(template = "range", timeoutTicks = ArrivalTickets.LIFESPAN + 100, batch = "arrival_respawn")
    public static void respawnTicketWaitsForRespawn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        int r = ArrivalTickets.DISTANCE - 2, playerLevel = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
        var distances = level.getChunkSource().chunkMap.getDistanceManager();
        List<ChunkPos> placed = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.release(level.getServer(), who, slot);
            for (ChunkPos pos : placed) distances.removeTicket(TicketType.PLAYER, pos, playerLevel, pos);
        });
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(c.x + dx, c.z + dz);
                distances.addTicket(TicketType.PLAYER, pos, playerLevel, pos);
                placed.add(pos);
            }
        }
        ArrivalTickets.awaitRespawn(level, c, who, slot);
        long placedAt = level.getGameTime();
        h.onEachTick(() -> {
            ArrivalTickets.tick(level.getServer(), who, slot, 32);
            if (ArrivalTickets.count(level, who) != 1) {
                throw new GameTestAssertException("тикет места возрождения снят через " + (level.getGameTime() - placedAt) + " тиков, игрок ещё мёртв");
            }
            if (level.getGameTime() - placedAt <= ArrivalTickets.LIFESPAN + 20) return;
            // возрождение в другом месте: тикет входа там, тикет места возрождения снят
            ArrivalTickets.arrive(level, new ChunkPos(c.x + 20, c.z), who, slot);
            h.assertTrue(ArrivalTickets.count(level, who) == 1, "возрождение не заменило тикет места возрождения");
            h.succeed();
        });
    }

    /** Чанков районов залпа (квадраты радиуса {@code r}), которые ещё не готовы. */
    private static int pending(ServerLevel level, List<ChunkPos> targets, int r) {
        Set<Long> seen = new HashSet<>();
        int n = 0;
        for (ChunkPos c : targets) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (seen.add(ChunkPos.asLong(c.x + dx, c.z + dz)) && !Terrain.ready(level, c.x + dx, c.z + dz)) n++;
                }
            }
        }
        return n;
    }
}
