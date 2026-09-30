package ua.zentix.airstrike.strike;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
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
 * уровню держателя чанка ({@code ChunkMap.runGenerationTask}, {@code ChunkTaskPriorityQueue}: меньший раньше), а районы
 * мода — уровни 29–33 ({@link FlightTickets#DISTANCE}), тикеты игроков — 31. Во время залпа загрузка места входа стояла
 * за всеми районами, и сервер ждал её в тике 0,55–0,8 с (замер 30.09.2026, облако; так же в 2.1.4).
 * <p>
 * Поэтому при входе ({@code PlayerLoggedInEvent}: конец {@code PlayerList.placeNewPlayer}, до первого тика игрока) и после
 * перехода между измерениями ({@code PlayerChangedDimensionEvent}) на чанк игрока ставится тикет загрузки уровня
 * {@code 33 − }{@link #DISTANCE} = 28 — на единицу меньше центра района цели: загрузка места игрока встаёт в очереди
 * первой. Тикет только загружает ({@link AreaLoader} без тика): блоки и сущности там тикают, как и без него, — по тикетам
 * самого игрока; блок-сущности у края ждут готовых соседей ({@code util/BlockTicking}). Радиус полной загрузки — 5 чанков,
 * внутри дальности обзора; там уже стоят тикеты игрока, и чанки обычно лежат на диске: загрузка, а не генерация.
 * <p>
 * Тикет снимается, как только тикеты самого игрока ({@code TicketType.PLAYER}, их ставит {@code DistanceManager} по
 * очереди) стоят на всём его квадрате, — иначе чанки опустились бы и выгрузились; и не позже {@link #LIFESPAN} тиков
 * (игрок ушёл далеко или вышел). Районы залпа, взятые в первые секунды после входа, готовы на ~1,5–2 с позже —
 * снаряды ждут района сами.
 * <p>
 * Не закрывает возрождение ({@code PlayerList.respawn} проверяет место синхронно ещё до события) и поиск портала
 * ({@code PortalForcer}) — оба грузят чанки до того, как мод о них узнаёт.
 */
public final class ArrivalTickets {
    /** Уровень тикета 33 − 5 = 28. */
    public static final int DISTANCE = 5;
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
        Slot slot = p.getData(ModAttachments.ARRIVAL.get());
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = p.server.getLevel(slot.dimension);
        if (level == null || coveredByPlayers(level, slot.held, Math.min(DISTANCE, p.server.getPlayerList().getViewDistance()))) {
            release(p.server, p.getUUID(), slot);
        }
    }

    private static void arrive(ServerPlayer p) {
        arrive(p.serverLevel(), p.chunkPosition(), p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
    }

    /** Поставить тикет входа на чанк {@code pos} вместо прежнего (и в тестах, где игрока нет). */
    public static void arrive(ServerLevel level, ChunkPos pos, UUID who, Slot slot) {
        release(level.getServer(), who, slot);
        StrikeWorld.get(level).areas().hold(level, area(pos, who), level.getGameTime() + LIFESPAN);
        slot.dimension = level.dimension();
        slot.held = pos;
    }

    /** Снять тикет входа; снятый раньше сам по сроку ({@link AreaLoader}) — ничего не делает. */
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

    /** На каждом чанке квадрата {@code radius} вокруг {@code centre} стоит тикет игрока: место держит сам игрок. */
    static boolean coveredByPlayers(ServerLevel level, ChunkPos centre, int radius) {
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
