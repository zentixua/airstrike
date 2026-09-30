package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.strike.ArrivalTickets;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Место входа или возрождения игрока грузится раньше районов залпа ({@link ArrivalTickets}).
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArrivalGameTests {
    /** Загрузка места игроком: тикет уровня 33 на один чанк, как {@code UNKNOWN} у синхронного {@code getChunk}. */
    private static final TicketType<ChunkPos> PLAYER_LOAD = TicketType.create("airstrike_test_player_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SALVO = 30, SPREAD = 150;
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);
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
        ArrivalTickets.tick(level.getServer(), who, slot, 32, false);
        h.assertTrue(ArrivalTickets.count(level, who) == 1, "тикет входа снят, хотя угол квадрата не держит ни один игрок");
        // обзор меньше квадрата: хватает квадрата обзора
        ArrivalTickets.tick(level.getServer(), who, slot, r - 1, false);
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "тикет входа не снят, хотя квадрат обзора держат игроки");
        // весь квадрат
        ArrivalTickets.arrive(level, c, who, slot);
        distances.addTicket(TicketType.PLAYER, corner, playerLevel, corner);
        placed.add(corner);
        ArrivalTickets.tick(level.getServer(), who, slot, 32, false);
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "тикет входа не снят, хотя весь квадрат держат игроки");
        // слот пуст: следующий тик ничего не снимает и не падает
        ArrivalTickets.tick(level.getServer(), who, slot, 32, false);
        h.succeed();
    }

    /**
     * Тикет на месте возрождения (ставится при смерти) стоит, пока мёртвый игрок тикает, — дольше срока тикета входа и
     * аренды и даже когда место держат тикеты игроков; возрождение (вход на новом месте) его заменяет, и тикет входа снова
     * снимается по тикетам игроков.
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
            ArrivalTickets.tick(level.getServer(), who, slot, 32, true);
            if (ArrivalTickets.count(level, who) != 1) {
                throw new GameTestAssertException("тикет места возрождения снят через " + (level.getGameTime() - placedAt) + " тиков, игрок ещё мёртв");
            }
            if (level.getGameTime() - placedAt <= ArrivalTickets.LIFESPAN + 20) return;
            // возрождение в другом месте: тикет входа там, тикет места возрождения снят
            ArrivalTickets.arrive(level, new ChunkPos(c.x + 20, c.z), who, slot);
            h.assertTrue(ArrivalTickets.count(level, who) == 1, "возрождение не заменило тикет места возрождения");
            // вход на месте, которое держат игроки: это уже тикет входа, и тик его снимает (флаг «мёртв» у тикета входа
            // ничего не значит; остался бы флаг тикета возрождения — тик продлил бы его)
            ArrivalTickets.arrive(level, c, who, slot);
            ArrivalTickets.tick(level.getServer(), who, slot, 32, true);
            h.assertTrue(ArrivalTickets.count(level, who) == 0, "тикет входа после возрождения остался тикетом возрождения");
            h.succeed();
        });
    }

    /** Тикет места возрождения, который никто не продлевает (игрок больше не тикает), снимается сам через аренду. */
    @GameTest(template = "range", timeoutTicks = ArrivalTickets.LEASE + 100, batch = "arrival_respawn")
    public static void respawnTicketLeaseExpires(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO).offset(16 * 30, 0, 0));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        StrikeGameTests.afterTest(h, () -> ArrivalTickets.release(level.getServer(), who, slot));
        ArrivalTickets.awaitRespawn(level, c, who, slot);
        long placedAt = level.getGameTime();
        h.onEachTick(() -> {
            long age = level.getGameTime() - placedAt;
            int n = ArrivalTickets.count(level, who);
            if (age < ArrivalTickets.LEASE) {
                if (n != 1) throw new GameTestAssertException("тикет места возрождения снят раньше аренды: через " + age + " тиков");
                return;
            }
            if (age < ArrivalTickets.LEASE + 5) return;
            h.assertTrue(n == 0, "тикет места возрождения стоит через " + age + " тиков без тика игрока");
            h.succeed();
        });
    }

    /**
     * Обработчики событий на {@code FakePlayer} NeoForge (он не входит в список игроков и пакетов не шлёт, в отличие от
     * мок-игрока GameTest): смерть ставит тикет на кровать, тик мёртвого его держит, {@code Clone} передаёт слот новому
     * объекту, возрождение переносит тикет на место появления, выход снимает; выход мёртвым и тик ожившего — тоже снимают.
     * Без точки возрождения тикета нет: точку появления мира держит ванильный {@code START}.
     */
    @GameTest(template = "range", batch = "arrival_respawn_events")
    public static void respawnEventsMoveTicket(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = h.absolutePos(BlockPos.ZERO);
        BlockPos bed = base.offset(16 * 20, 0, 0);
        List<FakePlayer> made = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            for (FakePlayer p : made) ArrivalTickets.release(level.getServer(), p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
        });
        // смерть → экран смерти → возрождение → выход
        GameProfile profile = new GameProfile(UUID.randomUUID(), "airstrike_arrival_a");
        FakePlayer dead = died(level, profile, bed, made);
        UUID who = profile.getId();
        h.assertTrue(ArrivalTickets.count(level, who) == 1 && new ChunkPos(bed).equals(held(dead)), "смерть не поставила тикет на кровать: " + held(dead));
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(dead));
        h.assertTrue(ArrivalTickets.count(level, who) == 1, "тик мёртвого игрока снял тикет места возрождения");
        FakePlayer neu = new FakePlayer(level, profile);
        made.add(neu);
        neu.moveTo(Vec3.atCenterOf(base.offset(16 * 40, 0, 0)));
        ArrivalTickets.onClone(new PlayerEvent.Clone(neu, dead, true));
        ArrivalTickets.onRespawn(new PlayerEvent.PlayerRespawnEvent(neu, false));
        h.assertTrue(ArrivalTickets.count(level, who) == 1 && neu.chunkPosition().equals(held(neu)),
                "возрождение не перенесло тикет на место появления: тикетов " + ArrivalTickets.count(level, who) + ", слот "
                        + held(neu) + ", игрок в " + neu.chunkPosition());
        ArrivalTickets.onLogout(new PlayerEvent.PlayerLoggedOutEvent(neu));
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "выход не снял тикет входа");
        // выход с экрана смерти
        GameProfile quitter = new GameProfile(UUID.randomUUID(), "airstrike_arrival_b");
        FakePlayer gone = died(level, quitter, bed, made);
        h.assertTrue(ArrivalTickets.count(level, quitter.getId()) == 1, "смерть не поставила тикет");
        ArrivalTickets.onLogout(new PlayerEvent.PlayerLoggedOutEvent(gone));
        h.assertTrue(ArrivalTickets.count(level, quitter.getId()) == 0, "выход мёртвым не снял тикет места возрождения");
        // смерть отменил другой мод и вылечил игрока
        GameProfile revived = new GameProfile(UUID.randomUUID(), "airstrike_arrival_c");
        FakePlayer alive = died(level, revived, bed, made);
        alive.setHealth(alive.getMaxHealth());
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(alive));
        h.assertTrue(ArrivalTickets.count(level, revived.getId()) == 0, "тик живого игрока не снял тикет места возрождения");
        // без точки возрождения: точку появления мира держит START
        GameProfile homeless = new GameProfile(UUID.randomUUID(), "airstrike_arrival_d");
        died(level, homeless, null, made);
        if (level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) > 0) {
            h.assertTrue(ArrivalTickets.count(level, homeless.getId()) == 0, "тикет на точке появления мира, которую держит START");
        } else {
            h.assertTrue(ArrivalTickets.count(level, homeless.getId()) == 1, "без точки появления в чанках START тикета нет");
        }
        h.succeed();
    }

    /**
     * Кровать на аппарате Sable: точка возрождения — в сетке плотов (возрождает Sable по своей точке в мире), тикета там
     * нет. Без правки тикет уровня 26 вставал в сетку плотов, мимо защиты Sable ({@code addRegionTicket}).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "arrival_respawn_craft", skyAccess = true)
    public static void respawnOnCraftNoTicket(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos floor = RANGE_CENTER.above();
        BlockPos.betweenClosed(floor.offset(-1, 0, -1), floor.offset(1, 0, 1)).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
        BlockPos a = h.absolutePos(floor.offset(-1, 0, -1)), b = h.absolutePos(floor.offset(1, 0, 1));
        Vec3 centre = Vec3.atCenterOf(h.absolutePos(floor));
        List<FakePlayer> made = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            for (FakePlayer p : made) ArrivalTickets.release(server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
        });
        h.startSequence()
                .thenExecute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                        String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ())))
                .thenWaitUntil(() -> h.assertFalse(SubLevels.near(level, centre, 8).isEmpty(), "аппарат не собран"))
                .thenExecute(() -> {
                    SubLevelAccess sub = SubLevels.near(level, centre, 8).getFirst();
                    BlockPos plot = BlockPos.containing(SubLevels.toPlot(sub, centre.add(0, 1, 0)));
                    h.assertTrue(SubLevels.inPlotGrid(level, new ChunkPos(plot)), "точка аппарата " + plot + " не в сетке плотов");
                    GameProfile profile = new GameProfile(UUID.randomUUID(), "airstrike_arrival_craft");
                    FakePlayer dead = died(level, profile, plot, made);
                    h.assertTrue(ArrivalTickets.count(level, profile.getId()) == 0,
                            "тикет места возрождения в сетке плотов: " + held(dead) + " (точка " + dead.getRespawnPosition() + ")");
                })
                .thenSucceed();
    }

    /** Игрок (без списка игроков и пакетов) с точкой возрождения {@code bed} (null — без неё) умер: смерть дошла до мода. */
    private static FakePlayer died(ServerLevel level, GameProfile profile, @Nullable BlockPos bed, List<FakePlayer> made) {
        FakePlayer p = new FakePlayer(level, profile);
        made.add(p);
        if (bed != null) p.setRespawnPosition(level.dimension(), bed, 0, true, false);
        p.setHealth(0);
        ArrivalTickets.onDeath(new LivingDeathEvent(p, level.damageSources().genericKill()));
        return p;
    }

    @Nullable
    private static ChunkPos held(ServerPlayer p) {
        return ArrivalTickets.held(p.getData(ModAttachments.ARRIVAL.get()));
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
