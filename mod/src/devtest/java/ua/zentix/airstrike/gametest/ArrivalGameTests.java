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
            for (int i = 0; i < targets.size(); i++) FlightTickets.hold(level, targets.get(i), FlightTickets.DISTANCE, keys.get(i), false);
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
                    FlightTickets.hold(level, target, FlightTickets.DISTANCE, key, true);
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
                pendingAtArrival[0] = pending(level, targets);
                arrivedAt[0] = tick[0];
                h.assertTrue(pendingAtArrival[0] > 0, "районы залпа готовы сразу — проверка ничего не проверила");
                return;
            }
            if (!Terrain.ready(level, p.x, p.z)) return;
            int left = pending(level, targets);
            Airstrike.LOG.info("Вход во время залпа: P готов через {} тиков, чанков районов не готово {} из {} при входе",
                    tick[0] - arrivedAt[0], left, pendingAtArrival[0]);
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

    /** Чанков районов залпа (квадраты радиуса {@link FlightTickets#DISTANCE}), которые ещё не готовы. */
    private static int pending(ServerLevel level, List<ChunkPos> targets) {
        Set<Long> seen = new HashSet<>();
        int n = 0, r = FlightTickets.DISTANCE;
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
