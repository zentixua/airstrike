package ua.zentix.airstrike.strike;

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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.Comparator;
import java.util.UUID;

/**
 * Место игрока, который вошёл в мир или перешёл в другое измерение, грузится раньше районов мода.
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
 * (GameTest {@code arrivalLoadsBeforeLoiterAreas}). Тикет только загружает ({@link AreaLoader} без тика): блоки и сущности
 * там тикают, как и без него, — по тикетам игроков; блок-сущности у края ждут готовых соседей ({@code util/BlockTicking}).
 * Полностью загружен квадрат радиуса 7 — у дальности обзора меньше 7 это на время тикета больше, чем держит сам игрок;
 * чанки места входа обычно лежат на диске: загрузка, а не генерация.
 * <p>
 * Тикет снимается, когда на квадрате радиуса {@code DISTANCE − 2} = 5 (не дальше обзора) стоят тикеты игроков
 * ({@code TicketType.PLAYER}, любого игрока: их ставит {@code DistanceManager} по очереди): их уровни тогда не выше
 * уровней тикета входа по статусу, и снятие ничего не опускает. И не позже {@link #LIFESPAN} тиков (игрок ушёл далеко, вышел;
 * при {@code /tick freeze} срок стоит вместе с часами мира). Районы залпа, взятые в первые секунды после входа, готовы
 * позже — снаряды ждут района сами.
 * <p>
 * Наблюдателю без генерации чанков ({@code spectatorsGenerateChunks} = false) тикет не ставится: ваниль не даёт ему своих
 * тикетов и не тикает его над незагруженными чанками.
 * <p>
 * Не закрывает возрождение ({@code PlayerList.respawn} проверяет место синхронно ещё до события), поиск портала
 * ({@code PortalForcer}) и телепорт внутри измерения — они грузят чанки до того, как мод о них узнаёт.
 */
public final class ArrivalTickets {
    /** Уровень тикета 33 − 7 = 26: ниже центра района «Ланцета» (27). */
    public static final int DISTANCE = FlightTickets.LOITER_DISTANCE + 1;
    /** Тикет снимается сам через 10 с. */
    public static final int LIFESPAN = 200;
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_arrival", Comparator.<UUID>naturalOrder());

    private ArrivalTickets() {}

    /** Место входа игрока (несохраняемый attachment): где стоит тикет. */
    public static final class Slot {
        @Nullable
        ResourceKey<Level> dimension;
        @Nullable
        ChunkPos held;
        long until;
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) arrive(p);
    }

    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) arrive(p);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.hasData(ModAttachments.ARRIVAL.get())) {
            release(p.server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
        }
    }

    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.hasData(ModAttachments.ARRIVAL.get())) return;
        tick(p.server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()), p.server.getPlayerList().getViewDistance());
    }

    private static void arrive(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        if (p.isSpectator() && !level.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS)) return;
        arrive(level, p.chunkPosition(), p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
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
     * Снять тикет, когда место держат тикеты игроков (на квадрате радиуса {@code DISTANCE − 2}, не дальше обзора
     * {@code viewDistance}) или вышел срок; и в тестах, где игрока нет.
     */
    public static void tick(MinecraftServer server, UUID who, Slot slot, int viewDistance) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (level == null || level.getGameTime() >= slot.until || coveredByPlayers(level, slot.held, Math.min(DISTANCE - 2, viewDistance))) {
            release(server, who, slot);
        }
    }

    /** Снять тикет входа; снятый раньше по сроку ({@link AreaLoader}) — только очистить слот. */
    public static void release(MinecraftServer server, UUID who, Slot slot) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (level != null) StrikeWorld.get(level).areas().release(level, area(slot.held, who));
        slot.held = null;
        slot.dimension = null;
    }

    /** Сколько тикетов входа у игрока стоит (проверки). */
    public static int count(ServerLevel level, UUID who) {
        return StrikeWorld.get(level).areas().count(TYPE, who);
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
