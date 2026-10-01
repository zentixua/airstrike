package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.airstrike.util.Terrain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Район, который мод грузит заранее (район цели снаряда {@link FlightTickets}, свой чанк и чанк впереди снаряда
 * {@link ChunkTickets}, подсказка карты {@link PickHints}, район взрыва, место ядерного подрыва с пуска, воронка и очередь
 * разрушений): загрузка — сразу и так же, как ванильным тикетом региона, а тик — только там, где у чанков готовы соседи.
 * <p>
 * У {@code DistanceManager} два счёта уровней: загрузки ({@code ticketTracker}: до какого статуса грузится чанк и в каком
 * порядке — очередь генерации идёт по уровню, {@code ChunkTaskPriorityQueue}) и тика ({@code tickingTicketsTracker}:
 * {@code ServerLevel.shouldTickBlocksAt} и {@code DistanceManager.inEntityTickingRange}). Тикет региона
 * ({@code addRegionTicket}) идёт в оба, а блок-сущности полностью загруженного чанка тикают по одному уровню тика, не
 * дожидаясь соседей: улей внутри района цели, выпуская пчелу, читал соседний чанк, который ещё генерировался, и грузил его
 * синхронно ({@code ServerChunkCache.getChunk}) за очередью генерации волны — сервер стоял по 2–10 с (VPS и облако,
 * 29.09.2026, строка стенда «остановка:»). Сущностям ваниль этого не позволяет: чанк тикает сущностями, когда готов
 * квадрат 5×5 вокруг ({@code ChunkMap.prepareEntityTickingChunk}).
 * <p>
 * Поэтому район берётся двумя тикетами. Тикет загрузки ({@code DistanceManager.addTicket}, только счёт загрузки) с тем же
 * уровнем {@code 33 − distance}, что у тикета региона, ставится сразу: те же чанки, те же статусы и тот же порядок
 * генерации, что раньше, — район готов к прибытию снаряда не позже. Тикет региона растёт: радиус {@code k}, когда готов
 * квадрат радиуса {@code k}, — блоки тикают в радиусе {@code k − 1}, где все соседи уже готовы, сущности — в радиусе
 * {@code k − 2}, как их и так пустила бы ваниль. Когда готов весь район, остаётся один тикет региона.
 * <p>
 * Проверено на стенде и не годится (снаряды не дожидались района 60 с): предел числа чанков в загрузке, тикет региона
 * только на готовый район, рост района кольцами по тикетам уровня 33 — все они меняли порядок генерации.
 * <p>
 * Тикет загрузки у каждого района свой (значение тикета — сам район): ваниль хранит одинаковые тикеты (тип, уровень,
 * значение) одним, и отпуск одного района снял бы загрузку у другого района с тем же центром, радиусом и ключом, но
 * другим типом (воронка и очередь разрушений ядерного удара держат один чанк), — тот остался бы без тикета навсегда.
 * <p>
 * Тикет загрузки сам ничего тикать не пускает: чанк тикает блоками и блок-сущностями, только если его накрывает чужой счёт
 * тика (симуляция игрока, тикет региона другого мода). Там ваниль пускает блок-сущность тикать по уровню её чанка, не
 * спрашивая соседей, — так же, как в чанках у края тикетов самого игрока. Хуже ванили это лишь при дальности симуляции
 * больше дальности обзора сервера: чанки за обзором ваниль не грузит, а тикет мода грузит. Правило тика блок-сущностей мод
 * не меняет: оно общее для всех модов сборки.
 * <p>
 * Район без тика ({@link Area#ticks} — false: место ядерного подрыва, пока летит МБР) держит только тикет загрузки:
 * чанки полностью загружены, но не тикают ни блоками, ни сущностями.
 * <p>
 * Состояние не сохраняется (как и тикеты): после перезапуска снаряд попросит район заново. Живёт в
 * {@link StrikeWorld}, тикает и при {@code /tick freeze}: загрузка мира — не симуляция, а трейлер ждёт прогрузки
 * района в замороженном мире.
 */
public final class AreaLoader {
    /** Район: тикет региона {@code type} с уровнем {@code 33 − distance} в {@code centre}, ключ {@code key}. */
    public record Area(TicketType<UUID> type, ChunkPos centre, int distance, UUID key, boolean ticks) {
        /** Район, который тикает по мере готовности. */
        public Area(TicketType<UUID> type, ChunkPos centre, int distance, UUID key) {
            this(type, centre, distance, key, true);
        }

        int level() {
            return ChunkLevel.byStatus(FullChunkStatus.FULL) - distance;
        }
    }

    /**
     * Порядок тикетов загрузки на одном чанке — он же их равенство у ванили: разные районы не должны сравниваться как
     * равные. Типы тикетов различаются по имени (у мода имена уникальны), на случай двух типов с одним именем —
     * ещё и по самому объекту типа.
     */
    private static final TicketType<Area> LOAD = TicketType.create("airstrike_area_load",
            Comparator.<Area, String>comparing(a -> a.type().toString()).thenComparingInt(a -> System.identityHashCode(a.type()))
                    .thenComparing(Area::key).thenComparingInt(Area::distance).thenComparing(Area::ticks)
                    .thenComparingLong(a -> a.centre().toLong()));

    /** Радиус тикета региона, взятого районом: −1 — ещё нет. Равен {@code distance} — район взят целиком. */
    private final Map<Area, Integer> requests = new LinkedHashMap<>();
    /** Когда отпустить район сам (подсказка карты: игрок мог уйти), игровой тик. */
    private final Map<Area, Long> expiring = new LinkedHashMap<>();

    /** Взять район; готовый целиком — тикетом региона сразу. */
    public void hold(ServerLevel level, Area area) {
        hold(level, area, Long.MAX_VALUE);
    }

    /** Взять район на время: в тике {@code until} он отпускается сам (и если ещё рос). */
    public void hold(ServerLevel level, Area area, long until) {
        if (requests.containsKey(area)) return;
        if (until != Long.MAX_VALUE) expiring.put(area, until);
        if (area.ticks() && ready(level, area.centre(), area.distance())) {
            level.getChunkSource().addRegionTicket(area.type(), area.centre(), area.distance(), area.key());
            requests.put(area, area.distance());
            return;
        }
        level.getChunkSource().chunkMap.getDistanceManager().addTicket(LOAD, area.centre(), area.level(), area);
        requests.put(area, -1);
        grow(level, area);
    }

    /** Продлить район, взятый на время, до тика {@code until}; отпущенный — не брать снова. */
    public void renew(Area area, long until) {
        if (requests.containsKey(area)) expiring.put(area, until);
    }

    /** Отпустить район: тикет региона и, если район ещё рос, тикет загрузки. */
    public void release(ServerLevel level, Area area) {
        expiring.remove(area);
        Integer taken = requests.remove(area);
        if (taken == null) return;
        if (taken >= 0) level.getChunkSource().removeRegionTicket(area.type(), area.centre(), taken, area.key());
        if (taken < area.distance()) level.getChunkSource().chunkMap.getDistanceManager().removeTicket(LOAD, area.centre(), area.level(), area);
    }

    /** Сколько районов с тикетом {@code type} и ключом {@code key} взято или растёт (проверки). */
    public int count(TicketType<?> type, UUID key) {
        return (int) requests.keySet().stream().filter(a -> a.type() == type && a.key().equals(key)).count();
    }

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (!expiring.isEmpty()) {
            for (Area area : expiring.entrySet().stream().filter(e -> e.getValue() <= now).map(Map.Entry::getKey).toList()) release(level, area);
        }
        for (Area area : requests.keySet()) grow(level, area);
    }

    /** Тикет региона — на наибольший готовый квадрат вокруг центра; готов весь район — тикет загрузки больше не нужен. */
    private void grow(ServerLevel level, Area area) {
        int taken = requests.get(area);
        if (taken == area.distance() || !area.ticks()) return;
        // квадрат взятого радиуса готов и остаётся готовым (его держат тикеты) — проверить хватит колец дальше
        int k = taken;
        while (k < area.distance() && ringReady(level, area.centre(), k + 1)) k++;
        // радиус 0 тикать не даёт (уровень 33) — берётся, только если весь район такой
        if (k == taken || (k < 1 && k < area.distance())) return;
        // сначала новый, потом старый: уровни чанков не проседают ни на тик
        level.getChunkSource().addRegionTicket(area.type(), area.centre(), k, area.key());
        if (taken >= 0) level.getChunkSource().removeRegionTicket(area.type(), area.centre(), taken, area.key());
        if (k == area.distance()) level.getChunkSource().chunkMap.getDistanceManager().removeTicket(LOAD, area.centre(), area.level(), area);
        requests.put(area, k);
    }

    /** Готовы все чанки кольца на расстоянии {@code radius} от центра (0 — сам центр). */
    private static boolean ringReady(ServerLevel level, ChunkPos c, int radius) {
        for (int i = -radius; i <= radius; i++) {
            if (!Terrain.ready(level, c.x + i, c.z - radius) || !Terrain.ready(level, c.x + i, c.z + radius)) return false;
            if (!Terrain.ready(level, c.x - radius, c.z + i) || !Terrain.ready(level, c.x + radius, c.z + i)) return false;
        }
        return true;
    }

    private static boolean ready(ServerLevel level, ChunkPos c, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) if (!Terrain.ready(level, c.x + dx, c.z + dz)) return false;
        }
        return true;
    }
}
