package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.Comparator;
import java.util.UUID;

/**
 * Место игрока, который вошёл в мир, перешёл в другое измерение или ждёт возрождения, грузится раньше районов мода.
 * <p>
 * В первом своём тике игрок грузит свой чанк синхронно ({@code ServerPlayer.doTick → Entity.updateFluidOnEyes →
 * Level.getChunk}, тикет {@code UNKNOWN} уровня 33, {@code ServerChunkCache.getChunk}). Задачи загрузки и генерации идут по
 * уровню держателя чанка ({@code ChunkMap.runGenerationTask}, {@code ChunkTaskPriorityQueue}: меньший раньше, при равном —
 * по порядку), а районы мода — уровни 27–33 (центр района цели {@link FlightTickets#DISTANCE} — 29, у «Ланцета»
 * {@link FlightTickets#LOITER_DISTANCE} — 27), тикеты игроков — 31. Во время залпа загрузка места входа стояла за всеми
 * районами, и сервер ждал её в тике 0,55–0,8 с (замер 30.09.2026, облако; так же в 2.1.4).
 * <p>
 * Поэтому при входе ({@code PlayerLoggedInEvent}: конец {@code PlayerList.placeNewPlayer}, до первого тика игрока) и после
 * перехода между измерениями ({@code PlayerChangedDimensionEvent}) на чанк игрока ставится тикет загрузки уровня
 * {@code 33 − }{@link #DISTANCE} = 26 — ниже центра любого района мода. Загрузка полного чанка с диска ждёт соседей
 * (радиус 1, {@code ChunkPyramid.LOADING_PYRAMID}: {@code LIGHT}), у них уровень 27: впереди остаются только центры районов
 * «Ланцетов», взятые раньше, а не их кольца. С тикетом уровня 28 место входа во время залпа «Ланцетов» ждало весь залп
 * (GameTest {@code arrivalLoadsBeforeLoiterAreas}). Тикет только загружает ({@link AreaLoader} без тика): счёта тика он не
 * добавляет, блоки и сущности там тикают, как и без него, — по тикетам игроков. Уровни до 32 он даёт квадрату радиуса 6 —
 * тем чанкам, которым их через несколько тиков дадут и тикеты игрока в дальности обзора; что будет тикать, решает ваниль
 * по своим правилам (см. {@link AreaLoader} о тикете загрузки).
 * Полностью загружен квадрат радиуса 7 — у дальности обзора меньше 7 это на время тикета больше, чем держит сам игрок;
 * чанки места входа обычно лежат на диске: загрузка, а не генерация.
 * <p>
 * Тикет входа снимается, когда на квадрате радиуса {@code DISTANCE − 2} = 5 (не дальше обзора) стоят тикеты игроков
 * ({@code TicketType.PLAYER}, любого игрока: их ставит {@code DistanceManager} по очереди): их уровни тогда не выше
 * уровней тикета входа по статусу, и снятие ничего не опускает. И не позже {@link #LIFESPAN} тиков (игрок ушёл далеко, вышел;
 * при {@code /tick freeze} срок стоит вместе с часами мира). Районы залпа, взятые в первые секунды после входа, готовы
 * позже — снаряды ждут района сами.
 * <p>
 * Наблюдателю без генерации чанков ({@code spectatorsGenerateChunks} = false) тикет не ставится: ваниль не даёт ему своих
 * тикетов и не тикает его над незагруженными чанками.
 * <p>
 * Возрождение: {@code PlayerList.respawn} читает блок кровати или якоря синхронно
 * ({@code ServerPlayer.findRespawnAndUseSpawnBlock → Level.getBlockState}) ещё до {@code PlayerRespawnPositionEvent} и
 * {@code PlayerRespawnEvent}, — поэтому тот же тикет ставится уже при смерти ({@code LivingDeathEvent}) на место
 * возрождения: точку возрождения игрока ({@code getRespawnPosition}/{@code getRespawnDimension}). Без неё игрок появляется
 * у точки появления мира, которую держит ванильный тикет {@code START} ({@code spawnChunkRadius}); тикет мода там — только
 * при {@code spawnChunkRadius} = 0. Пока открыт экран смерти, место грузится первым. Тикет возрождения — аренда на
 * {@link #LEASE} тиков, которую продлевает тик мёртвого игрока (мёртвый игрок на экране смерти тикает): его снимает
 * возрождение (тикет входа уже на том месте, где игрок появился), выход игрока, тик живого игрока (смерть отменил другой
 * мод) — а игрока, который больше не тикает (выгружен, чужая подделка игрока), отпускает срок аренды. Слот переходит к
 * новому объекту игрока в {@code PlayerEvent.Clone}: attachment без сериализатора сам не копируется. Пока тикет стоит,
 * квадрат радиуса 5 вокруг кровати тикает сущности без игрока рядом: там идёт {@code checkDespawn}, как у точки появления
 * мира, — случайные мобы у кровати исчезают, если другой игрок в этом измерении дальше 128 блоков.
 * <p>
 * Кровать или якорь на аппарате Sable: точка возрождения — координаты в сетке плотов, а возрождает Sable по своей точке
 * в мире ({@code ServerPlayerMixin} Sable); в сетке плотов тикетов загрузки быть не должно (там держатели чанков Sable) —
 * тикета нет. В хардкоре после возрождения игрок становится наблюдателем без генерации — тикета входа нет.
 * <p>
 * Не закрывает поиск портала ({@code PortalForcer}) и телепорт внутри измерения — они грузят чанки до того, как мод о них
 * узнаёт; при возрождении — место, которое другой мод подменил в {@code PlayerRespawnPositionEvent}, место возрождения на
 * аппарате и точку появления мира, когда кровати уже нет. Игрок, который вышел с экрана смерти и вошёл снова, получает
 * только тикет входа на чанк, где умер (там его объект), а тикета места возрождения — нет: смерти при входе не бывает.
 */
public final class ArrivalTickets {
    /** Уровень тикета 33 − 7 = 26: ниже центра района «Ланцета» (27). */
    public static final int DISTANCE = FlightTickets.LOITER_DISTANCE + 1;
    /** Тикет входа снимается сам через 10 с. */
    public static final int LIFESPAN = 200;
    /** Аренда тикета возрождения: 5 с после последнего тика мёртвого игрока. */
    public static final int LEASE = 100;
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_arrival", Comparator.<UUID>naturalOrder());

    private ArrivalTickets() {}

    /**
     * Где стоит тикет игрока (несохраняемый attachment). После возрождения старый и новый объект игрока держат один и тот
     * же слот ({@link #onClone}): снятие идемпотентно, а выход, случившийся между {@code Clone} и концом
     * {@code PlayerList.respawn}, снимает тикет через старый объект — копия со сброшенным старым слотом его бы потеряла.
     */
    public static final class Slot {
        @Nullable
        ResourceKey<Level> dimension;
        @Nullable
        ChunkPos held;
        long until;
        /** Тикет стоит на месте возрождения, игрок мёртв: аренда, которую продлевает его тик. */
        boolean respawn;
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) arrive(p);
    }

    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) arrive(p);
    }

    /**
     * Приоритет {@code LOWEST}: после всех слушателей, кроме других {@code LOWEST}. Отменённая раньше смерть сюда не
     * приходит ({@code addListener} без {@code receiveCanceled}); отменённую позже снимает тик живого игрока.
     */
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) awaitRespawn(p);
    }

    /** Слот — новому объекту игрока (возрождение, выход из Края): без сериализатора attachment не копируется. */
    public static void onClone(PlayerEvent.Clone e) {
        if (e.getOriginal().hasData(ModAttachments.ARRIVAL.get())) {
            e.getEntity().setData(ModAttachments.ARRIVAL.get(), e.getOriginal().getData(ModAttachments.ARRIVAL.get()));
        }
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        // хардкор: игрок станет наблюдателем без генерации уже после события
        if (p.server.isHardcore() && !e.isEndConquered()) {
            if (p.hasData(ModAttachments.ARRIVAL.get())) release(p.server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
            return;
        }
        arrive(p);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.hasData(ModAttachments.ARRIVAL.get())) {
            release(p.server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
        }
    }

    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.hasData(ModAttachments.ARRIVAL.get())) return;
        tick(p.server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()), p.server.getPlayerList().getViewDistance(), p.isDeadOrDying());
    }

    /**
     * Тикет на место возрождения мёртвого игрока {@code p}: его точка (кровать, якорь) или, без неё, точка появления
     * верхнего мира, как у {@code PlayerList.respawn}. Прежний тикет снимается; нового нет у наблюдателя без генерации, для
     * точки в сетке плотов Sable и для точки появления мира, которую держит {@code START}.
     */
    private static void awaitRespawn(ServerPlayer p) {
        Slot slot = p.getData(ModAttachments.ARRIVAL.get());
        release(p.server, p.getUUID(), slot);
        if (!generates(p)) return;
        ServerLevel level = p.server.getLevel(p.getRespawnDimension());
        BlockPos pos = p.getRespawnPosition();
        if (level == null || pos == null) {
            level = p.server.overworld();
            if (level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) > 0) return;
            pos = level.getSharedSpawnPos();
        }
        ChunkPos chunk = new ChunkPos(pos);
        // кровать на аппарате: Sable возрождает по своей точке в мире, а в сетке плотов тикетов быть не должно
        if (SubLevels.inPlotGrid(level, chunk)) return;
        awaitRespawn(level, chunk, p.getUUID(), slot);
    }

    /** Поставить тикет на место возрождения вместо прежнего, арендой на {@link #LEASE} тиков (и в тестах, где игрока нет). */
    public static void awaitRespawn(ServerLevel level, ChunkPos pos, UUID who, Slot slot) {
        release(level.getServer(), who, slot);
        slot.until = level.getGameTime() + LEASE;
        slot.respawn = true;
        StrikeWorld.get(level).areas().hold(level, area(pos, who), slot.until);
        slot.dimension = level.dimension();
        slot.held = pos;
    }

    private static void arrive(ServerPlayer p) {
        if (!generates(p)) return;
        arrive(p.serverLevel(), p.chunkPosition(), p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
    }

    /** Ваниль не грузит чанки наблюдателю без генерации, и мод тоже. */
    private static boolean generates(ServerPlayer p) {
        return !p.isSpectator() || p.serverLevel().getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
    }

    /** Поставить тикет входа на чанк {@code pos} вместо прежнего (и в тестах, где игрока нет). */
    public static void arrive(ServerLevel level, ChunkPos pos, UUID who, Slot slot) {
        release(level.getServer(), who, slot);
        slot.until = level.getGameTime() + LIFESPAN;
        StrikeWorld.get(level).areas().hold(level, area(pos, who), slot.until);
        slot.dimension = level.dimension();
        slot.held = pos;
    }

    /**
     * Тик игрока {@code who} (и в тестах, где игрока нет). Тикет входа снимается, когда место держат тикеты игроков (на
     * квадрате радиуса {@code DISTANCE − 2}, не дальше обзора {@code viewDistance}) или вышел срок. Тикет возрождения
     * у мёртвого игрока продлевается на {@link #LEASE} тиков, у живого — снимается.
     */
    public static void tick(MinecraftServer server, UUID who, Slot slot, int viewDistance, boolean dead) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (slot.respawn) {
            if (level == null || !dead) {
                release(server, who, slot);
            } else {
                slot.until = level.getGameTime() + LEASE;
                StrikeWorld.get(level).areas().renew(area(slot.held, who), slot.until);
            }
            return;
        }
        if (level == null || level.getGameTime() >= slot.until || coveredByPlayers(level, slot.held, Math.min(DISTANCE - 2, viewDistance))) {
            release(server, who, slot);
        }
    }

    /** Снять тикет; снятый раньше по сроку ({@link AreaLoader}) — только очистить слот. */
    public static void release(MinecraftServer server, UUID who, Slot slot) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (level != null) StrikeWorld.get(level).areas().release(level, area(slot.held, who));
        slot.held = null;
        slot.dimension = null;
        slot.respawn = false;
    }

    /** Сколько тикетов входа у игрока стоит (проверки). */
    public static int count(ServerLevel level, UUID who) {
        return StrikeWorld.get(level).areas().count(TYPE, who);
    }

    /** Чанк, на котором стоит тикет слота, или null (проверки). */
    @Nullable
    public static ChunkPos held(Slot slot) {
        return slot.held;
    }

    private static AreaLoader.Area area(ChunkPos pos, UUID who) {
        return new AreaLoader.Area(TYPE, pos, DISTANCE, who, false);
    }

    /** На каждом чанке квадрата {@code radius} вокруг {@code centre} стоит тикет игрока (любого). */
    private static boolean coveredByPlayers(ServerLevel level, ChunkPos centre, int radius) {
        var tickets = level.getChunkSource().chunkMap.getDistanceManager().tickets;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                SortedArraySet<Ticket<?>> at = tickets.get(ChunkPos.asLong(centre.x + dx, centre.z + dz));
                if (at == null || at.stream().noneMatch(t -> t.getType() == TicketType.PLAYER)) return false;
            }
        }
        return true;
    }
}
