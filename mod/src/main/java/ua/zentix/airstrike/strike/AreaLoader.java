package ua.zentix.airstrike.strike;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Район, который мод грузит заранее (район цели снаряда {@link FlightTickets}, подсказка карты {@link PickHints}):
 * тикет региона ставится, только когда все чанки района уже готовы, а до того район догружается по чанкам —
 * не больше {@link #IN_FLIGHT} неготовых разом на весь мир, от центра наружу.
 * <p>
 * Так же ваниль грузит чанки игроков ({@code DistanceManager.ticketThrottler}: тикет игрока ставится в чанк, когда
 * из четырёх занятых мест одно освободилось). Тикет региона сразу на свежий район ставил в очередь генерации сотни
 * чанков с уровнем 29–33 — выше, чем у синхронной загрузки чанка из тика ({@code ServerChunkCache.getChunk}:
 * тикет {@code UNKNOWN} уровня 33), и она ждала всю эту очередь: улей у края загруженного мира, читающий соседний
 * чанк, стоял сервер по 8–18 с, пока залп РСЗО грузил районы целей в 250 и 1500 блоках. Теперь перед ней не больше
 * четырёх наших чанков.
 * <p>
 * Прогретый чанк держит свой тикет (уровень 33, сам чанк загружен полностью), пока район не взят тикетом региона:
 * иначе, опустившись, он выгрузился бы раньше, чем догрузятся остальные. Состояние не сохраняется (как и тикеты):
 * после перезапуска снаряд попросит район заново. Живёт в {@link StrikeWorld}, тикает и при {@code /tick freeze}:
 * загрузка мира — не симуляция, а трейлер ждёт прогрузки района в замороженном мире.
 */
public final class AreaLoader {
    /**
     * Сколько неготовых чанков районов грузится разом: не меньше, чем у игроков ({@code DistanceManager.ticketThrottler}
     * — 4), и не меньше потоков генерации ({@code Util.makeExecutor}: ядер − 1, не больше {@code max.bg.threads}), чтобы
     * на сильной машине район грузился всеми потоками, а очередь перед синхронной загрузкой была не длиннее одного круга.
     */
    public static final int IN_FLIGHT = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors() - 1, maxThreads()));
    private static final TicketType<UUID> PREFETCH = TicketType.create("airstrike_prefetch", Comparator.<UUID>naturalOrder());

    /** Район: тикет региона {@code type} с уровнем {@code 33 − distance} в {@code centre}, ключ {@code key}. */
    public record Area(TicketType<UUID> type, ChunkPos centre, int distance, UUID key) {}

    private static final class Request {
        final List<ChunkPos> order;
        int next;
        final List<ChunkPos> prefetched = new ArrayList<>();

        Request(Area area) {
            order = new ArrayList<>((2 * area.distance() + 1) * (2 * area.distance() + 1));
            ChunkPos c = area.centre();
            for (int dx = -area.distance(); dx <= area.distance(); dx++) {
                for (int dz = -area.distance(); dz <= area.distance(); dz++) order.add(new ChunkPos(c.x + dx, c.z + dz));
            }
            // от центра наружу: цель и её соседи готовы первыми
            order.sort(Comparator.<ChunkPos>comparingInt(p -> p.getChessboardDistance(c))
                    .thenComparingInt(p -> (p.x - c.x) * (p.x - c.x) + (p.z - c.z) * (p.z - c.z)));
        }
    }

    /** Районы в порядке просьб: догружаются — с ходом загрузки, взятые тикетом региона — {@code null}. */
    private final Map<Area, Request> requests = new LinkedHashMap<>();
    /** Когда отпустить взятый район сам (подсказка карты: игрок мог уйти), игровой тик. */
    private final Map<Area, Long> expiring = new LinkedHashMap<>();

    /** Взять район; готовый целиком — тикетом региона сразу. */
    public void hold(ServerLevel level, Area area) {
        hold(level, area, Long.MAX_VALUE);
    }

    /** Взять район на время: в тике {@code until} он отпускается сам (и если ещё догружался). */
    public void hold(ServerLevel level, Area area, long until) {
        if (requests.containsKey(area)) return;
        if (until != Long.MAX_VALUE) expiring.put(area, until);
        Request r = new Request(area);
        if (ready(level, r.order)) {
            take(level, area, null);
            requests.put(area, null);
        } else {
            requests.put(area, r);
        }
    }

    /** Отпустить район (догружался — снять тикеты прогретых чанков, взят — тикет региона). */
    public void release(ServerLevel level, Area area) {
        expiring.remove(area);
        if (!requests.containsKey(area)) return;
        Request r = requests.remove(area);
        if (r == null) level.getChunkSource().removeRegionTicket(area.type(), area.centre(), area.distance(), area.key());
        else unprefetch(level, area, r);
    }

    /** Район взят тикетом региона (все его чанки были готовы). */
    public boolean taken(Area area) {
        return requests.containsKey(area) && requests.get(area) == null;
    }

    /** Сколько районов с тикетом {@code type} и ключом {@code key} взято или догружается (проверки). */
    public int count(TicketType<?> type, UUID key) {
        return (int) requests.keySet().stream().filter(a -> a.type() == type && a.key().equals(key)).count();
    }

    /** Районы, которые ещё догружаются. */
    public int loading() {
        return (int) requests.values().stream().filter(r -> r != null).count();
    }

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (!expiring.isEmpty()) {
            for (Area area : expiring.entrySet().stream().filter(e -> e.getValue() <= now).map(Map.Entry::getKey).toList()) release(level, area);
        }
        // неготовые чанки, которые уже грузятся по нашим тикетам (у соседних районов бывают общие)
        LongSet inFlight = new LongOpenHashSet();
        for (Request r : requests.values()) {
            if (r == null) continue;
            for (ChunkPos p : r.prefetched) if (!Terrain.ready(level, p.x, p.z)) inFlight.add(p.toLong());
        }
        for (Map.Entry<Area, Request> e : requests.entrySet()) {
            Request r = e.getValue();
            if (r == null) continue;
            Area area = e.getKey();
            while (r.next < r.order.size() && inFlight.size() < IN_FLIGHT) {
                ChunkPos p = r.order.get(r.next++);
                level.getChunkSource().addRegionTicket(PREFETCH, p, 0, area.key());
                r.prefetched.add(p);
                if (!Terrain.ready(level, p.x, p.z)) inFlight.add(p.toLong());
            }
            if (r.next == r.order.size() && ready(level, r.prefetched)) {
                take(level, area, r);
                e.setValue(null);
            }
        }
    }

    /** Все чанки района готовы: тикет региона (чанки уже загружены, он только поднимает их до тика) вместо прогрева. */
    private static void take(ServerLevel level, Area area, @Nullable Request r) {
        level.getChunkSource().addRegionTicket(area.type(), area.centre(), area.distance(), area.key());
        if (r != null) unprefetch(level, area, r);
    }

    private static void unprefetch(ServerLevel level, Area area, Request r) {
        for (ChunkPos p : r.prefetched) level.getChunkSource().removeRegionTicket(PREFETCH, p, 0, area.key());
        r.prefetched.clear();
    }

    /** {@code Util.getMaxThreads}: свойство {@code max.bg.threads} (1–255), иначе 255. */
    private static int maxThreads() {
        try {
            int n = Integer.parseInt(System.getProperty("max.bg.threads", "255"));
            return n >= 1 && n <= 255 ? n : 255;
        } catch (NumberFormatException e) {
            return 255;
        }
    }

    private static boolean ready(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos p : chunks) if (!Terrain.ready(level, p.x, p.z)) return false;
        return true;
    }
}
