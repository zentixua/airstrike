package ua.zentix.airstrike.strike;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
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
 * Район без тика ({@link Area#ticks} — false: место ядерного подрыва, пока летит МБР; район цели снаряда до последних
 * секунд подлёта) держит только тикет загрузки:
 * чанки полностью загружены, но не тикают ни блоками, ни сущностями.
 * <p>
 * Отпущенный район тикать перестаёт сразу, а его чанки держит тикет без тика, пока очередь не отдаст их ваниле в её темпе
 * ({@link #drainReleases}): отпуск сотен районов разом (Отбой, снаряды, не дождавшиеся района) иначе выгружал и сохранял
 * тысячи чанков в одном тике.
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

    /**
     * Тикет отпущенного района ({@link #drainReleases}): без тика, того же уровня, что у района. Значение — номер отпуска,
     * а не район: тикеты района по его ключу после отпуска не находятся.
     */
    private static final TicketType<Long> RELEASE = TicketType.create("airstrike_area_release", Long::compare);
    /**
     * Сколько держателей чанков на выгрузке ваниль разбирает по времени тика ({@code ChunkMap.processUnloads}): что сверх
     * этого в {@code toDrop}, она ставит в выгрузку в том же тике, а готовые к выгрузке сверх этого — сохраняет и выгружает.
     */
    private static final int UNLOAD_CALM = 2000;
    /**
     * Сколько держателей ваниль переводит из {@code toDrop} в выгрузку за тик и без свободного времени
     * ({@code ChunkMap.processUnloads}) — её собственный темп выгрузки на занятом сервере.
     */
    private static final int UNLOAD_PER_TICK = 200;

    /** Радиус тикета региона, взятого районом: −1 — ещё нет. Равен {@code distance} — район взят целиком. */
    private final Map<Area, Integer> requests = new LinkedHashMap<>();
    /** Отпущенные районы, чьи чанки ещё держит тикет отпуска (номер в его значении), в порядке отпуска. */
    private final Map<Area, Long> releasing = new LinkedHashMap<>();
    private long released;
    /**
     * Сколько держателей очередь ещё отдаёт ваниле в этом тике сверх места на выгрузке ({@link #drainReleases}): каждый
     * тик прибавляется {@link #UNLOAD_PER_TICK}, но не больше его; меньше нуля — долг за район больше этого темпа.
     */
    private int credit;
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
        } else {
            distances(level).addTicket(LOAD, area.centre(), area.level(), area);
            requests.put(area, -1);
            grow(level, area);
        }
        // взят снова, пока ждал снятия тикета отпуска: те же чанки уже держат тикеты района
        Long release = releasing.remove(area);
        if (release != null) distances(level).removeTicket(RELEASE, area.centre(), area.level(), release);
    }

    /** Продлить район, взятый на время, до тика {@code until}; отпущенный — не брать снова. */
    public void renew(Area area, long until) {
        if (requests.containsKey(area)) expiring.put(area, until);
    }

    /**
     * Отпустить район: тикет региона и, если район ещё рос, тикет загрузки. Тикать район перестаёт сразу, а чанки
     * готового района держит тикет отпуска, пока до него не дойдёт очередь {@link #drainReleases}. Район, который ещё
     * грузился, отпускается сразу: тикет отпуска того же уровня догенерировал бы ненужное. При остановке сервера — тоже
     * сразу: очередь больше не тикает, а {@code util/StopDrain} ждёт генерацию, которую держат тикеты.
     */
    public void release(ServerLevel level, Area area) {
        expiring.remove(area);
        Integer taken = requests.remove(area);
        if (taken == null) return;
        if (level.getServer().isRunning() && (area.ticks() ? taken == area.distance() : ready(level, area.centre(), area.distance()))) {
            // сначала тикет отпуска, потом тикеты района: уровни загрузки чанков не меняются, пока не дойдёт очередь
            long release = released++;
            distances(level).addTicket(RELEASE, area.centre(), area.level(), release);
            releasing.put(area, release);
        }
        removeTickets(level, area, taken);
    }

    /**
     * Отпустить все районы сразу, без очереди (остановка сервера): тикеты районов не сохраняются, а взятые при остановке
     * запускали бы генерацию, которую ждёт {@code util/StopDrain}. После запуска районы берут заново их владельцы.
     */
    public void releaseAll(ServerLevel level) {
        requests.forEach((area, taken) -> removeTickets(level, area, taken));
        requests.clear();
        expiring.clear();
        releasing.forEach((area, release) -> distances(level).removeTicket(RELEASE, area.centre(), area.level(), release));
        releasing.clear();
    }

    private static void removeTickets(ServerLevel level, Area area, int taken) {
        if (taken >= 0) level.getChunkSource().removeRegionTicket(area.type(), area.centre(), taken, area.key());
        if (taken < area.distance()) distances(level).removeTicket(LOAD, area.centre(), area.level(), area);
    }

    /** Отпущенных районов, чьи чанки ещё держит тикет отпуска (проверки). */
    public int releasing() {
        return releasing.size();
    }

    /** Районов взято или растёт (проверки). */
    public int size() {
        return requests.size();
    }

    /** Взят ли район или растёт (проверки). */
    public boolean holds(Area area) {
        return requests.containsKey(area);
    }

    /** Сколько районов с тикетом {@code type} и ключом {@code key} взято или растёт (проверки). */
    public int count(TicketType<?> type, UUID key) {
        return (int) requests.keySet().stream().filter(a -> a.type() == type && a.key().equals(key)).count();
    }

    /** То же только среди тикающих районов ({@link Area#ticks}) или только среди районов без тика (проверки). */
    public int count(TicketType<?> type, UUID key, boolean ticks) {
        return (int) requests.keySet().stream().filter(a -> a.type() == type && a.key().equals(key) && a.ticks() == ticks).count();
    }

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (!expiring.isEmpty()) {
            for (Area area : expiring.entrySet().stream().filter(e -> e.getValue() <= now).map(Map.Entry::getKey).toList()) release(level, area);
        }
        for (Area area : requests.keySet()) grow(level, area);
        drainReleases(level);
    }

    /**
     * Снять тикеты отпущенных районов в темпе, в котором ваниль выгружает сама. Держатели чанков, оставшиеся без тикетов,
     * ваниль сохраняет и выгружает во время, свободное в тике, а без него — {@link #UNLOAD_PER_TICK} из {@code toDrop} за
     * тик и всё, что сверх {@link #UNLOAD_CALM} в {@code toDrop} и в очереди выгрузки, — в том же тике: стенд 03.10.2026
     * стоял 5 с на сохранении ~9 тыс. чанков, отпущенных разом. Поэтому районы отпускаются по порядку, и после каждого
     * ваниль сразу пересчитывает уровни ({@code runDistanceManagerUpdates}) — видно, сколько держателей он отдал.
     * <p>
     * Следующий район идёт, если на выгрузке хватит места на весь его квадрат держателей ({@link #holders}): его ваниль
     * разберёт во время, свободное в тике. Но сервер без свободного времени разбирает только то, что сверх
     * {@link #UNLOAD_CALM} на выгрузке, а держателей, которых ещё держит генерация соседей, не трогает вовсе, — места
     * может не стать никогда: очередь, ждавшая одного места, на занятом сервере отпускала район раз в 100 тиков (Zearth
     * 04.10.2026: после ~60 залпов — 12,5 тыс. тикетов отпуска, ~25 тыс. загруженных чанков, TPS 13–15, и чем больше
     * чанков держала очередь, тем меньше свободного времени оставалось). Поэтому сверх места очередь отдаёт держатели и
     * темпом ванили без свободного времени — {@link #UNLOAD_PER_TICK} за тик ({@link #credit}): район больше этого идёт
     * в долг, и следующий ждёт, пока долг не покроют следующие тики. И только пока на выгрузке ({@link #unloading}: без
     * держателей генерации — их ваниль не трогает, и очередь не ждёт их) меньше {@link #UNLOAD_CALM} и этого темпа, а в
     * {@code toDrop} — меньше {@link #UNLOAD_CALM}: в первом же тике со свободным временем ваниль переводит в выгрузку весь
     * {@code toDrop} и сохраняет всё, что сверх её {@link #UNLOAD_CALM}, даже когда время кончилось, — после очереди это
     * не больше её темпа и одного района, было у сервера свободное время или нет.
     * <p>
     * Чанки отпущенного района не тикают, а очередь идёт и при {@code /tick freeze}, как загрузка.
     */
    private void drainReleases(ServerLevel level) {
        credit = Math.min(credit + UNLOAD_PER_TICK, UNLOAD_PER_TICK);
        if (releasing.isEmpty()) return;
        ServerChunkCache chunks = level.getChunkSource();
        ChunkMap map = chunks.chunkMap;
        // раз на тик: отпуск только добавляет в toDrop, и держатели генерации, которых он добавил, идут в счёт выгрузки
        int generating = generating(map);
        // каждый раз — первый в очереди, без живого итератора: пересчёт уровней доходит до сущностей в чанках, и снаряд,
        // уходя с ними, может отпустить свой район
        for (int n = releasing.size(); n > 0 && !releasing.isEmpty(); n--) {
            Map.Entry<Area, Long> next = releasing.entrySet().iterator().next();
            Area area = next.getKey();
            int unloading = map.toDrop.size() + map.pendingUnloads.size() - generating;
            boolean room = unloading + holders(area) <= UNLOAD_CALM;
            if (!room && (credit <= 0 || unloading >= UNLOAD_CALM + UNLOAD_PER_TICK || map.toDrop.size() >= UNLOAD_CALM)) return;
            int dropping = map.toDrop.size();
            releasing.remove(area);
            distances(level).removeTicket(RELEASE, area.centre(), area.level(), next.getValue());
            chunks.runDistanceManagerUpdates();
            if (!room) credit -= Math.max(0, map.toDrop.size() - dropping);
        }
    }

    /**
     * Держателей чанков на выгрузке, которых ваниль разберёт ({@code ChunkMap.processUnloads}): ушедших из загрузки и уже
     * поставленных в выгрузку, кроме тех, что ещё держит генерация соседей (и проверки).
     */
    public static int unloading(ChunkMap map) {
        return map.toDrop.size() + map.pendingUnloads.size() - generating(map);
    }

    /** Держателей в {@code toDrop}, которых ещё держит генерация соседей: ваниль пропускает их, пока она не кончится. */
    private static int generating(ChunkMap map) {
        int n = 0;
        for (LongIterator it = map.toDrop.iterator(); it.hasNext(); ) {
            ChunkHolder holder = map.getVisibleChunkIfPresent(it.nextLong());
            if (holder != null && holder.getGenerationRefCount() != 0) n++;
        }
        return n;
    }

    /**
     * Сколько держателей чанков самое большее отдаёт отпуск района: тикет уровня {@code 33 − distance} держит квадрат
     * радиусом {@code distance + ChunkLevel.RADIUS_AROUND_FULL_CHUNK}.
     */
    static int holders(Area area) {
        int side = 2 * (area.distance() + ChunkLevel.RADIUS_AROUND_FULL_CHUNK) + 1;
        return side * side;
    }

    private static DistanceManager distances(ServerLevel level) {
        return level.getChunkSource().chunkMap.getDistanceManager();
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
        if (k == area.distance()) distances(level).removeTicket(LOAD, area.centre(), area.level(), area);
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
