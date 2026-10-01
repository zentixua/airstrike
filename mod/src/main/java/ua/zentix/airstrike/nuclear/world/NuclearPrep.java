package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Руины заранее — пока летит МБР (полторы минуты): для чанков в памяти в радиусе руин ({@link Detonation#ruinRadius})
 * строится план руин ({@link RuinPlanner}): снимки — в тике сервера, разломы и обрушение — фоновыми потоками
 * ({@link RuinWorkers}), достройка по живому чанку — снова в тике, под бюджетом. Когда волна доходит до чанка, остаётся
 * только записать план в секции ({@link RuinPlan#apply}) — разрушения идут вместе с фронтом, а не догоняют его. Чанки,
 * загруженные во время полёта, подхватываются (раз в {@link #REFRESH} тиков); для подготовки ничего не грузится.
 * <p>
 * После подрыва — зона за волной: тяжёлая зона (у земли от 5 psi) грузится квадратами 5×5 вслед за фронтом, чтобы руины
 * были на диске и в LOD Distant Horizons, а не только там, где стоял игрок. Грузится только то, что уже есть на диске
 * полностью сгенерированным (поле {@code Status} чанка читается в фоне через {@code chunkScanner}) или уже в памяти:
 * ради удара мир не генерируется. Чанки держатся тикетами загрузки без тика ({@link AreaLoader}, {@code ticks = false}),
 * ближние к цели первыми, не больше {@link #HELD_TILES} квадратов сразу; руины их чанков строит очередь
 * ({@link ScarQueue}), квадрат отпускается, когда его руины стоят. Ничего не сохраняется: после перезапуска
 * подготовка начинается заново по запланированному удару.
 * <p>
 * Место подрыва и земля под ним — как у самого подрыва ({@link NuclearWarhead#geometry}); план не зависит ни от времени,
 * ни от сида подрыва — только от места, мощности и масштаба, и подходит подрыву, если тот случился там же.
 */
public final class NuclearPrep {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_nuclear_prep", Comparator.<UUID>naturalOrder());
    /** Давление у земли, до которого руины строятся заранее. */
    static final double PSI = 5;
    /** Квадраты: 5×5 чанков, чьи руины строятся вместе. */
    private static final int TILE_RADIUS = 2;
    private static final int TILE = TILE_RADIUS * 2 + 1;
    /**
     * Радиус тикета квадрата — сам квадрат 5×5: окна чанков у его края очередь руин ({@link ScarQueue}) читает с диска
     * ({@link RuinContext#requestWindow}), готовый план соседей не ждёт (кроме {@link RuinPlan#neighbourRadius} — те держат
     * соседей сами). Раньше держался квадрат 9×9 (с полями {@link RuinPlanner#REACH}): 81 чанк на 25 руин, и зона за
     * волной грузилась втрое дольше (проверка 30.09.2026: ~23 чанка в секунду, отставание у игроков до 6514 тиков).
     */
    private static final int LOAD_RADIUS = TILE_RADIUS;
    /**
     * Квадрат загрузки с кольцами: тикет поднимает и чанки вокруг ниже полной загрузки — на 1 до деталей, на 2 до пещер
     * (CARVERS), и несгенерированный чанк там генерировался бы во время удара, поэтому готовыми на диске должны быть
     * и они. Дальше (3 и 4) — только первые шаги генерации (биомы, начала структур), без рельефа.
     */
    private static final int RING_REACH = LOAD_RADIUS + 2, RING = 2 * RING_REACH + 1;
    /** Сколько квадратов отпускать за тик: каждый — 25 выгрузок чанков с записью на диск в этом тике сервера. */
    private static final int RELEASE_PER_TICK = 2;
    /** Квадратов в загрузке одновременно (не все сразу: игрокам тоже надо грузить мир). */
    private static final int LOADING_TILES = 6;
    /** Чтений заголовков чанков с диска одновременно. */
    private static final int SCANS = 64;
    /** Подготовка не начинается, если до подрыва меньше, тиков. */
    private static final int MIN_LEAD = 100;
    /** Квадратов зоны за волной, которые держатся сразу (в загрузке и с руинами в очереди): память сервера. */
    private static final int HELD_TILES = 6;
    /** Раз в сколько тиков подхватывать чанки, загруженные во время полёта, и писать ход зоны за волной. */
    private static final int REFRESH = 40, ZONE_REPORT = 600;
    /** После подрыва квадраты зоны за волной держатся не дольше, тиков. */
    private static final int ZONE_HOLD = 12_000;
    /**
     * Сколько места под руины заранее, байт: готовые планы, снимки чанков (из памяти и с диска), чтения в работе и буферы
     * фоновых потоков. Сверх него новые чтения ждут, пока планы не отпустят снимки; планы сверх него не строятся.
     */
    private static final long PLANS_CAP = 512L << 20;
    /** Чтений чанков с диска в работе сразу и оценка памяти на одно (разобранный чанк города — десятки КБ). */
    private static final int READS = 64;
    private static final long READ_BYTES = 96L << 10;
    /** Насколько место подрыва может отличаться от места плана, блоки. */
    private static final double SAME_PLACE = 8;

    /** Доля кучи (живой объём после сборки), выше которой руины заранее больше не строятся. */
    private static final double HEAP_LIMIT = 0.75;

    /** Для {@link #heapTight}: число сборок при прошлой проверке и сколько проверок подряд после сборки куча выше предела. */
    private long lastCollections = -1;
    private int heapStrikes;

    private enum TileState { SCAN, SKIP, WAIT, LOADING, READY }

    private static final class Tile {
        final ChunkPos centre;
        TileState state = TileState.SCAN;
        /** Чанков квадрата и кольца вокруг него, чей ответ с диска ещё не пришёл или не запрошен. */
        int unknown = RING * RING;
        boolean missing;
        /** Для {@link NukeDiag}: когда взят тикет и когда все чанки стали полными (игровое время), −1 — ещё нет. */
        long heldAt = -1, readyAt = -1;

        Tile(ChunkPos centre) {
            this.centre = centre;
        }

        AreaLoader.Area area(int strike) {
            return new AreaLoader.Area(TYPE, centre, LOAD_RADIUS, new UUID(strike, centre.toLong()), false);
        }
    }

    private static final class Prep {
        final int strike;
        final long detonateTime;
        @Nullable
        Detonation geometry;
        /** Чанки тяжёлой зоны, ближние первыми (зона за волной). */
        long[] order = new long[0];
        /** Чанки в памяти в радиусе руин, ближние первыми: им план заранее. */
        final LongArrayList todo = new LongArrayList();
        int nextTodo;
        final LongOpenHashSet considered = new LongOpenHashSet();
        /** Все чанки, бывшие в памяти в радиусе руин за полёт (знаменатель строк «готовы» и «руины заранее»). */
        final LongOpenHashSet everInMemory = new LongOpenHashSet();
        /** Планов по чанкам в памяти (ближняя подготовка). */
        int nearPlans;
        /** Чанки, чей план строят фоновые потоки. */
        final LongArrayList submitted = new LongArrayList();
        long nextRefresh;
        /** Байт в готовых планах. */
        long planBytes;
        boolean capped;
        long nextZoneReport;
        final List<Tile> tiles = new ArrayList<>();
        final Long2ObjectOpenHashMap<Tile> tileOf = new Long2ObjectOpenHashMap<>();
        /** Квадраты, которым нужен ответ по чанку (свой или кольцо соседа). */
        final Long2ObjectOpenHashMap<List<Tile>> askedBy = new Long2ObjectOpenHashMap<>();
        /** Памяти мало: руины заранее больше не строятся, новые квадраты не грузятся. */
        boolean heapStop;
        /** Очередь чанков на чтение с диска и ответы (приходят из потока ввода-вывода). */
        final LongArrayList toScan = new LongArrayList();
        int nextScan, scanning;
        final ConcurrentLinkedQueue<long[]> scanned = new ConcurrentLinkedQueue<>();
        /**
         * Для {@link NukeDiag}: на чём остановился набор квадратов в тиках (слоты, квадрат без ответа с диска, волна
         * не дошла, брать нечего, памяти мало) и отпущенные квадраты: число, тикет → полные (сумма, наибольшее),
         * полные → отпущен, волна → тикет.
         */
        final long[] diagStops = new long[5], diagDone = new long[8];
        long nextIoReport;
        final Long2ObjectOpenHashMap<RuinPlan> plans = new Long2ObjectOpenHashMap<>();
        /** Руины по всем чанкам (разломы соседей): переходят подрыву вместе с планами. */
        @Nullable
        RuinContext ruins;
        final LongArrayList planned = new LongArrayList();
        /** Отдан подрыву с этим номером (тикеты ещё держатся); -1 — ещё летит. */
        int detonation = -1;
        /** Руины больше не нужны: квадраты отпускаются по нескольку за тик. */
        boolean draining;
        long handedOff;
        boolean announced;

        // ---- фаза 2: тяжёлая зона не в памяти — с диска, в порядке прихода волны
        @Nullable
        DiskShots.Format format;
        /** Чтения с диска в работе (отдано — и ответ ещё не разобран) и ответы из фоновых потоков. */
        final LongOpenHashSet reading = new LongOpenHashSet();
        final ConcurrentLinkedQueue<DiskShots.Read> reads = new ConcurrentLinkedQueue<>();
        /** Чанки, которых нет на диске полностью сгенерированными: плана им и их соседям заранее нет. */
        final LongOpenHashSet unreadable = new LongOpenHashSet();
        /** Курсоры по {@link #order}: докуда отданы чтения окон и докуда отданы планы. */
        int nextRead, nextFar;
        /** Чанки, чей план по снимкам с диска строят фоновые потоки. */
        final LongArrayList farRunning = new LongArrayList();
        int farPlans, diskShots, farSkipped;
        /** Чанки тяжёлой зоны не в памяти при подрыве: для строки «дальние кольца». */
        @Nullable
        long[] farAtDetonation;

        Prep(int strike, long detonateTime) {
            this.strike = strike;
            this.detonateTime = detonateTime;
        }
    }

    private final List<Prep> preps = new ArrayList<>();
    /** Удары, чья подготовка упала с ошибкой: заново не начинается (иначе — ошибка в лог каждый тик). */
    private final java.util.Set<Integer> failed = new java.util.HashSet<>();
    private final WorkClock clock = WorkClock.decaying(0.5);

    /** Сколько руин готово заранее по всем ударам (проверки, статус). */
    public int plannedChunks() {
        return preps.stream().mapToInt(p -> p.plans.size()).sum();
    }

    /** Подготовки нет ни одной: буферы решателя руин ей не нужны. */
    public boolean idle() {
        return preps.isEmpty();
    }

    /** Чанк выгружен: его фоновый план больше не нужен. */
    void forget(long chunk) {
        for (Prep p : preps) {
            p.submitted.rem(chunk);
            // до подрыва руины подготовки — не в NuclearWorld: задачу снимает она сама
            if (p.ruins != null && p.detonation < 0) p.ruins.forget(chunk);
            p.considered.remove(chunk);
        }
    }

    /** Память руин заранее: планы, снимки, чтения в работе, буферы потоков. */
    private static long memory(ServerLevel level, Prep p) {
        return p.planBytes + p.ruins.shotBytes() + p.reading.size() * READ_BYTES + RuinWorkers.bufferBytes(level.getHeight());
    }

    /** Сколько квадратов держится тикетами (проверки). */
    public int heldTiles() {
        return (int) preps.stream().flatMap(p -> p.tiles.stream()).filter(t -> t.state == TileState.LOADING || t.state == TileState.READY).count();
    }

    // ---------------------------------------------------------------- тик

    /**
     * Подготовка под бюджетом: свой срок ({@code ms} на тик) и общий бюджет ядерной работы.
     *
     * @param shared общие часы тика (уже запущены)
     */
    public void tick(ServerLevel level, NuclearEvents events, ScarQueue scars, WorkClock shared) {
        long now = level.getGameTime();
        int ms = AirstrikeConfig.SERVER.nukePrepMsPerTick.get();
        // новые удары: подготовка с пуска
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            if (ms <= 0 || !AirstrikeConfig.SERVER.nukeBlockDamage.get()) break;
            if (s.detonateTime() - now < MIN_LEAD || failed.contains(s.id()) || preps.stream().anyMatch(p -> p.strike == s.id())) continue;
            // один район — одна подготовка: второй удар по тем же местам руины заранее не строит (память под зону — одна)
            if (preps.stream().anyMatch(p -> p.geometry != null && p.detonation < 0
                    && Math.hypot(p.geometry.burst().x - s.target().x, p.geometry.burst().z - s.target().z) < 2 * heavyRadius(p.geometry) + 64)) continue;
            preps.add(new Prep(s.id(), s.detonateTime()));
        }
        clock.start(Math.max(1, ms) * 1_000_000L);
        for (Iterator<Prep> it = preps.iterator(); it.hasNext(); ) {
            Prep p = it.next();
            try {
                if (p.draining) {
                    p.plans.clear();
                    // до подрыва задачи руин подготовки никому не отданы: бросить
                    if (p.ruins != null && p.detonation < 0) p.ruins.cancelTasks();
                    if (releaseSome(level, p, RELEASE_PER_TICK) == 0) it.remove();
                    continue;
                }
                if (p.detonation >= 0) {
                    // отдан подрыву: зона за волной грузится квадратами, квадрат отпускается, когда его руины стоят
                    if (!p.heapStop && heapTight()) stopForHeap(p);
                    Detonation d = events.detonations().stream().filter(x -> x.id() == p.detonation).findFirst().orElse(null);
                    behindWave(level, p, scars, d, now, shared);
                    if (d != null && p.ruins != null) far(level, p, shared);
                    boolean zoneDone = p.tiles.stream().noneMatch(t -> t.state != TileState.SKIP);
                    if (zoneDone && !scars.hasPrepared(p.detonation) || now - p.handedOff > ZONE_HOLD) {
                        zoneReport(p, scars, d, now);
                        scars.dropPrepared(p.detonation);
                        p.draining = true;
                    }
                    continue;
                }
                NuclearEvents.ScheduledStrike s = events.scheduled().stream().filter(x -> x.id() == p.strike).findFirst().orElse(null);
                if (s == null) {
                    // отбой или подрыв не там, где план
                    p.draining = true;
                    continue;
                }
                work(level, s, p, shared);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Подготовка руин удара №{} упала с ошибкой; снята", p.strike, e);
                failed.add(p.strike);
                if (p.draining) {
                    // упала уже отпуская квадраты: отпустить все разом и больше не трогать (иначе ошибка — каждый тик)
                    it.remove();
                    try {
                        release(level, p);
                    } catch (RuntimeException again) {
                        Airstrike.LOG.error("Подготовка руин удара №{}: квадраты не отпущены", p.strike, again);
                    }
                    continue;
                }
                if (p.detonation >= 0) scars.dropPrepared(p.detonation);
                p.draining = true;
            }
        }
    }

    private void work(ServerLevel level, NuclearEvents.ScheduledStrike s, Prep p, WorkClock shared) {
        if (p.geometry == null) {
            // место подрыва — как у самого подрыва: земля под целью из готового чанка (его держит район цели с пуска)
            if (!Terrain.ready(level, BlockPos.containing(s.target()))) return;
            p.geometry = NuclearWarhead.geometry(level, s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target(), s.yieldKt(), s.airBurst(),
                    AirstrikeConfig.SERVER.nukeEffectsScale.get().floatValue());
            layout(p);
            p.ruins = new RuinContext(p.geometry);
        }
        long now = level.getGameTime();
        if (now >= p.nextRefresh) {
            p.nextRefresh = now + REFRESH;
            refresh(level, p);
        }
        if (!p.heapStop && p.nextTodo < p.todo.size() && heapTight()) stopForHeap(p);
        p.ruins.expire(now);
        harvest(level, p, shared);
        if (!p.heapStop && !p.capped) submit(level, p, shared);
        far(level, p, shared);
        if (!p.announced && p.nextTodo >= p.todo.size() && p.submitted.isEmpty() && p.nextFar >= p.order.length && p.farRunning.isEmpty()) {
            p.announced = true;
            // строка для проверок и съёмки: руины удара готовы заранее (чанки, загруженные потом, ещё подхватываются)
            Runtime rt = Runtime.getRuntime();
            Airstrike.LOG.info("Руины удара №{} готовы: планов по чанкам в памяти {} (чанков в памяти за полёт {}), с диска {} (тяжёлая зона {} чанков), не с диска {} (до подрыва {} с), планы {} МБ, "
                            + "снимки {} МБ, фоновые потоки {} заняты на {} %, куча {} из {} МБ",
                    p.strike, p.nearPlans, p.everInMemory.size(), p.farPlans, p.order.length, p.farSkipped, Math.max(0, p.detonateTime - now) / 20,
                    p.planBytes >> 20, p.ruins.shotBytes() >> 20, RuinWorkers.summary(), Math.round(RuinWorkers.utilisation() * 100),
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
        }
    }

    /** Чанки в памяти в радиусе руин, которых ещё нет в очереди подготовки: в очередь, ближние первыми. */
    private static void refresh(ServerLevel level, Prep p) {
        Detonation d = p.geometry;
        double radius = d.ruinRadius();
        LongArrayList fresh = new LongArrayList();
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
            ChunkPos c = holder.getPos();
            if (p.considered.contains(c.toLong()) || nearest(c, d) > radius || inMemory(level, c.toLong()) == null) continue;
            p.considered.add(c.toLong());
            p.everInMemory.add(c.toLong());
            fresh.add(c.toLong());
        }
        if (fresh.isEmpty()) return;
        // оставшиеся и новые — по расстоянию (ключ — один раз)
        LongArrayList rest = new LongArrayList(p.todo.subList(p.nextTodo, p.todo.size()));
        rest.addAll(fresh);
        long[] keyed = new long[rest.size()];
        for (int i = 0; i < keyed.length; i++) keyed[i] = (long) (horizontal(new ChunkPos(rest.getLong(i)), d) * 16) << 24 | i;
        java.util.Arrays.sort(keyed);
        p.todo.clear();
        for (long k : keyed) p.todo.add(rest.getLong((int) (k & 0xFFFFFF)));
        p.nextTodo = 0;
    }

    /** Наклонная дальность от точки подрыва до ближайшего места чанка на уровне земли (как у очереди руин). */
    private static double nearest(ChunkPos p, Detonation d) {
        double h = horizontal(p, d), dy = d.burst().y - d.groundY();
        return Math.sqrt(h * h + dy * dy);
    }

    /** Готовые фоновые планы — достроить по живому чанку, по одному за единицу работы. */
    private void harvest(ServerLevel level, Prep p, WorkClock shared) {
        for (int i = 0; i < p.submitted.size() && clock.canStart() && shared.canStart(); ) {
            long c = p.submitted.getLong(i);
            if (!p.ruins.done(c)) {
                if (!p.ruins.running(c)) p.submitted.removeLong(i); // задачу сняли (чанк выгружен)
                else i++;
                continue;
            }
            p.submitted.removeLong(i);
            // чанк опустился ниже полной загрузки (край видимости): план верен — подмена сверит его с чанком
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c));
            long c0 = shared.begin();
            try {
                RuinPlan plan = chunk != null ? p.ruins.collect(level, chunk) : p.ruins.collect(new ChunkPos(c));
                if (plan != null) {
                    keep(level, p, c, plan);
                    p.nearPlans++;
                } else if (p.ruins.running(c)) {
                    p.submitted.add(c); // чанк перезагрузили: план заново
                }
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Руины заранее: план чанка {} упал с ошибкой; его руины построятся на месте", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
    }

    /** Готовый план — к руинам заранее; сверх места — дальше не строятся. */
    private static void keep(ServerLevel level, Prep p, long c, RuinPlan plan) {
        p.plans.put(c, plan);
        p.planned.add(c);
        p.planBytes += plan.bytes();
        if (p.planBytes + RuinWorkers.bufferBytes(level.getHeight()) > PLANS_CAP && !p.capped) {
            p.capped = true;
            Airstrike.LOG.warn("Руины удара №{}: готовые планы заняли {} МБ — дальше руины заранее не строятся (планов {}, чанков тяжёлой зоны {})",
                    p.strike, p.planBytes >> 20, p.plans.size(), p.order.length);
        }
    }

    /**
     * Фаза 2 — тяжёлая зона, которой нет в памяти: чанки читаются с диска в порядке прихода волны (окно 5×5 каждого —
     * на {@link #READS} чтений вперёд), снимок с диска встаёт в руины подрыва, план строят фоновые потоки по тем же
     * правилам, что и у чанков в памяти ({@link RuinContext#submitDisk}). Идёт и после подрыва: квадраты зоны за волной
     * берут готовые планы. Ничего не грузится в мир; память — под {@link #PLANS_CAP} вместе с планами.
     */
    private void far(ServerLevel level, Prep p, WorkClock shared) {
        if (p.heapStop || !AirstrikeConfig.SERVER.nukeBlockDamage.get()) return;
        if (p.format == null) {
            p.format = DiskShots.Format.of(level);
            // за краем тяжёлой зоны планов заранее нет: снимки соседей их не ждут
            LongOpenHashSet zone = new LongOpenHashSet(p.order);
            for (long c : p.order) {
                for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                    for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
                        long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                        if (!zone.contains(k)) p.ruins.markNoPlan(k);
                    }
                }
            }
        }
        // ответы с диска: снимок — в руины (состояния — в таблицу свойств), одна единица работы на чанк
        for (DiskShots.Read r; clock.canStart() && shared.canStart() && (r = p.reads.poll()) != null; ) {
            long c = r.pos().toLong();
            p.reading.remove(c);
            long c0 = shared.begin();
            try {
                if (r.skip() != null) {
                    p.unreadable.add(c);
                } else {
                    p.ruins.putDisk(level, r);
                    p.diskShots++;
                }
            } catch (RuntimeException e) {
                p.unreadable.add(c);
                Airstrike.LOG.error("Руины заранее: чанк {} с диска не встал в снимок", r.pos(), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
        // готовые планы
        for (int i = 0; i < p.farRunning.size() && clock.canStart() && shared.canStart(); ) {
            long c = p.farRunning.getLong(i);
            if (p.ruins.running(c) && !p.ruins.done(c)) {
                i++;
                continue;
            }
            p.farRunning.removeLong(i);
            if (!p.ruins.done(c)) continue; // забрал кто-то другой (очередь руин) или задача брошена
            long c0 = shared.begin();
            try {
                RuinPlan plan = p.ruins.collect(new ChunkPos(c));
                if (plan != null) {
                    keep(level, p, c, plan);
                    p.farPlans++;
                }
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Руины заранее: план чанка {} с диска упал с ошибкой; его руины построятся на месте", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
        // чтения окон вперёд по порядку волны
        while (p.nextRead < p.order.length && p.reading.size() < READS && memory(level, p) < PLANS_CAP) {
            long c = p.order[p.nextRead];
            boolean all = true;
            for (int dx = -RuinPlanner.REACH; all && dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; all && dz <= RuinPlanner.REACH; dz++) {
                    long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                    if (p.reading.contains(k) || p.unreadable.contains(k) || p.ruins.shotAvailable(level, k)) continue;
                    if (p.reading.size() >= READS) {
                        all = false;
                        continue;
                    }
                    p.reading.add(k);
                    ConcurrentLinkedQueue<DiskShots.Read> out = p.reads;
                    DiskShots.read(p.format, new ChunkPos(k)).thenAccept(out::add);
                }
            }
            if (!all) break;
            p.nextRead++;
        }
        // планы по порядку волны: окно готово — фоновым потокам; ждёт чтений — дальше не идём
        while (p.nextFar < p.order.length && !p.capped && clock.canStart() && shared.canStart()) {
            long c = p.order[p.nextFar];
            if (p.plans.containsKey(c) || p.ruins.running(c) || p.ruins.failed(c) || p.ruins.doneAt(c) >= 0 || readyChunk(level, c) != null) {
                // готов, строится, или чанк в памяти с соседями — его план заранее строит ближняя подготовка
                p.nextFar++;
                continue;
            }
            int missing = 0;
            boolean skip = false;
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
                    long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                    if (p.unreadable.contains(k) && !p.ruins.shotAvailable(level, k)) skip = true;
                    else if (!p.ruins.shotAvailable(level, k)) missing++;
                }
            }
            if (skip) {
                // окно не прочитать целиком: руины чанка — после волны, как раньше
                p.farSkipped++;
                p.ruins.noPlan(c);
                p.nextFar++;
                continue;
            }
            // после подрыва половина пула — очереди руин: чанки у игроков важнее дальних колец
            if (missing > 0 || !RuinWorkers.admit() || p.detonation >= 0 && RuinWorkers.inFlight() >= RuinWorkers.capacity() / 2) break;
            long c0 = shared.begin();
            try {
                if (!p.ruins.submitDisk(level, new ChunkPos(c))) break;
                p.farRunning.add(c);
                p.nextFar++;
            } catch (RuntimeException e) {
                p.nextFar++;
                Airstrike.LOG.error("Руины заранее: снимки чанка {} с диска упали с ошибкой; его руины построятся на месте", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
    }

    /** Чанки по порядку — фоновым потокам (снимки — единицей работы), пока у подрыва есть место под задачи. */
    private void submit(ServerLevel level, Prep p, WorkClock shared) {
        while (p.nextTodo < p.todo.size() && RuinWorkers.admit() && clock.canStart() && shared.canStart()) {
            long c = p.todo.getLong(p.nextTodo++);
            if (p.plans.containsKey(c) || p.ruins.running(c)) continue;
            // чанк без загруженных соседей (край загруженного мира) план заранее не получает: окно не прочитать
            LevelChunk chunk = readyChunk(level, c);
            if (chunk == null) {
                // соседи ещё не загружены (край загруженного мира): подхватится снова, когда загрузятся
                p.considered.remove(c);
                continue;
            }
            long c0 = shared.begin();
            try {
                if (p.ruins.submit(level, chunk)) p.submitted.add(c);
                else p.nextTodo--;
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Руины заранее: снимки чанка {} упали с ошибкой; его руины построятся на месте", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
    }

    /** Радиус тяжёлой зоны у земли, блоки (бисекция по давлению, монотонному с расстоянием). */
    static double heavyRadius(Detonation d) {
        double lo = 0, hi = d.radiusMax();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            if (d.psi(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) >= PSI) lo = r;
            else hi = r;
        }
        return lo;
    }

    /** Чанки зоны по расстоянию и квадраты загрузки по расстоянию. */
    private static void layout(Prep p) {
        Detonation d = p.geometry;
        double radius = heavyRadius(d) + 16;
        int cx = Mth.floor(d.burst().x) >> 4, cz = Mth.floor(d.burst().z) >> 4, r = Mth.ceil(radius / 16) + 1;
        LongArrayList in = new LongArrayList();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                ChunkPos c = new ChunkPos(x, z);
                if (horizontal(c, d) <= radius) in.add(c.toLong());
            }
        }
        // по расстоянию: ключи — один раз (сортировка с расстоянием в сравнении — десятки мс на 15 тыс. чанков)
        long[] order = in.toLongArray();
        long[] keyed = new long[order.length];
        for (int i = 0; i < order.length; i++) keyed[i] = (long) (horizontal(new ChunkPos(order[i]), d) * 16) << 20 | i;
        java.util.Arrays.sort(keyed);
        p.order = new long[order.length];
        for (int i = 0; i < order.length; i++) p.order[i] = order[(int) (keyed[i] & 0xFFFFF)];
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long c : p.order) {
            ChunkPos pos = new ChunkPos(c);
            ChunkPos centre = new ChunkPos(Math.floorDiv(pos.x, TILE) * TILE + TILE_RADIUS, Math.floorDiv(pos.z, TILE) * TILE + TILE_RADIUS);
            if (!seen.add(centre.toLong())) continue;
            Tile t = new Tile(centre);
            p.tiles.add(t);
            for (int dx = -RING_REACH; dx <= RING_REACH; dx++) {
                for (int dz = -RING_REACH; dz <= RING_REACH; dz++) {
                    long k = ChunkPos.asLong(centre.x + dx, centre.z + dz);
                    if (Math.abs(dx) <= TILE_RADIUS && Math.abs(dz) <= TILE_RADIUS) p.tileOf.put(k, t);
                    List<Tile> asked = p.askedBy.get(k);
                    if (asked == null) {
                        p.askedBy.put(k, asked = new ArrayList<>(1));
                        p.toScan.add(k);
                    }
                    asked.add(t);
                }
            }
        }
    }

    /** Горизонтальное расстояние от эпицентра до ближайшей точки чанка. */
    static double horizontal(ChunkPos p, Detonation d) {
        double x = Math.max(p.getMinBlockX(), Math.min(d.burst().x, p.getMaxBlockX() + 1));
        double z = Math.max(p.getMinBlockZ(), Math.min(d.burst().z, p.getMaxBlockZ() + 1));
        return Math.hypot(x - d.burst().x, z - d.burst().z);
    }

    /** Есть ли чанки на диске полностью сгенерированными: в памяти — сразу, остальные — чтением в фоне. */
    private static void scan(ServerLevel level, Prep p) {
        for (long[] r; (r = p.scanned.poll()) != null; ) {
            p.scanning--;
            answer(p, r[0], r[1] != 0);
        }
        while (p.nextScan < p.toScan.size() && p.scanning < SCANS) {
            long c = p.toScan.getLong(p.nextScan++);
            if (inMemory(level, c) != null) {
                answer(p, c, true);
                continue;
            }
            CollectFields status = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
            p.scanning++;
            ConcurrentLinkedQueue<long[]> out = p.scanned;
            level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(c), status).whenComplete((v, e) -> {
                boolean full = e == null && status.getResult() instanceof CompoundTag tag && "minecraft:full".equals(tag.getString("Status"));
                out.add(new long[]{c, full ? 1 : 0});
            });
        }
    }

    private static void answer(Prep p, long chunk, boolean full) {
        List<Tile> asked = p.askedBy.get(chunk);
        if (asked == null) return;
        for (Tile t : asked) {
            if (!full) t.missing = true;
            if (--t.unknown == 0 && t.state == TileState.SCAN) t.state = t.missing ? TileState.SKIP : TileState.WAIT;
        }
    }

    /**
     * Квадраты — ближние первыми, за фронтом (волна уже прошла ближний край квадрата), не больше нескольких в загрузке
     * и не больше {@link #HELD_TILES} всего; квадрат, чьи чанки все в памяти, не держится — их руины и так в очереди.
     */
    private static void load(ServerLevel level, Prep p, Detonation d, long now) {
        int loading = 0, held = 0;
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING) {
                if (ready(level, t)) {
                    t.state = TileState.READY;
                    t.readyAt = now;
                } else {
                    loading++;
                }
            }
            if (t.state == TileState.LOADING || t.state == TileState.READY) held++;
        }
        int stop = 3;
        for (Tile t : p.tiles) {
            if (loading >= LOADING_TILES || held >= HELD_TILES) {
                stop = 0;
                break;
            }
            if (t.state == TileState.SCAN) {
                stop = 1;
                break; // порядок — по расстоянию: дальние ждут, пока ближние не прочитаны
            }
            if (t.state != TileState.WAIT) continue;
            if (now < d.gameTime() + d.arrivalTicks(nearest(new ChunkPos(t.centre.x - TILE_RADIUS, t.centre.z - TILE_RADIUS), t, d))) {
                stop = 2;
                break;
            }
            if (allInMemory(level, t)) {
                t.state = TileState.SKIP;
                continue;
            }
            StrikeWorld.get(level).areas().hold(level, t.area(p.strike));
            t.state = TileState.LOADING;
            if (NukeDiag.ON) {
                t.heldAt = now;
                long wait = now - d.gameTime() - (long) Math.ceil(d.arrivalTicks(nearest(new ChunkPos(t.centre.x - TILE_RADIUS, t.centre.z - TILE_RADIUS), t, d)));
                p.diagDone[5] += wait;
                p.diagDone[7]++;
                p.diagDone[6] = Math.max(p.diagDone[6], wait);
            }
            loading++;
            held++;
        }
        p.diagStops[stop]++;
    }

    /** Наклонная дальность до ближнего места квадрата на уровне земли. */
    private static double nearest(ChunkPos corner, Tile t, Detonation d) {
        double x = Math.max(corner.getMinBlockX(), Math.min(d.burst().x, (t.centre.x + TILE_RADIUS + 1) * 16.0));
        double z = Math.max(corner.getMinBlockZ(), Math.min(d.burst().z, (t.centre.z + TILE_RADIUS + 1) * 16.0));
        double dx = x - d.burst().x, dz = z - d.burst().z, dy = d.burst().y - d.groundY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean allInMemory(ServerLevel level, Tile t) {
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) if (inMemory(level, ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz)) == null) return false;
        }
        return true;
    }

    /** Зона за волной (после подрыва): чтение заголовков, загрузка квадратов, отпуск тех, где руины стоят. */
    private static void behindWave(ServerLevel level, Prep p, ScarQueue scars, @Nullable Detonation d, long now, WorkClock clock) {
        if (d != null && !p.heapStop) {
            scan(level, p);
            load(level, p, d, now);
        } else {
            // подрыв забыт или памяти мало: зона за волной больше не грузится
            for (Tile t : p.tiles) if (t.state == TileState.SCAN || t.state == TileState.WAIT) t.state = TileState.SKIP;
            p.diagStops[4]++;
        }
        releaseDone(level, p, scars, clock);
        if (NukeDiag.ON && now >= p.nextIoReport) {
            p.nextIoReport = now + 200;
            Airstrike.LOG.info("ДИАГ ввод-вывод №{}: {}", p.detonation, NukeDiag.io(level));
        }
        if (now >= p.nextZoneReport) {
            p.nextZoneReport = now + ZONE_REPORT;
            zoneReport(p, scars, d, now);
            if (NukeDiag.ON) diagReport(level, p, scars, now);
        }
    }

    /**
     * Строки «ДИАГ» (только с {@link NukeDiag#ON}): что держит слоты зоны за волной. Квадраты по состояниям, на чём
     * останавливался набор, сколько шли отпущенные квадраты, у квадратов в загрузке — чанков не полных, у готовых —
     * что с чанками, которые не дают отпустить квадрат; причины ожидания очереди руин и чтения с диска.
     */
    private static void diagReport(ServerLevel level, Prep p, ScarQueue scars, long now) {
        int[] states = new int[TileState.values().length];
        for (Tile t : p.tiles) states[t.state.ordinal()]++;
        long[] s = p.diagStops, dn = p.diagDone;
        long n = Math.max(1, dn[0]);
        Airstrike.LOG.info("ДИАГ зона №{}: квадратов SCAN {}, SKIP {}, WAIT {}, LOADING {}, READY {}; заголовков прочитано {} из {} (в работе {}); "
                        + "набор стоял тиков — слоты {}, ответ с диска {}, волна {}, брать нечего {}, память {}; отпущено {}: тикет→полные в среднем {} (самое большее {}), "
                        + "полные→отпущен {} ({}), волна→тикет {} ({})",
                p.detonation, states[0], states[1], states[2], states[3], states[4], p.nextScan - p.scanning, p.toScan.size(), p.scanning,
                s[0], s[1], s[2], s[3], s[4], dn[0], dn[1] / n, dn[2], dn[3] / n, dn[4], dn[5] / Math.max(1, dn[7]), dn[6]);
        java.util.Arrays.fill(s, 0);
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING) {
                int notFull = 0;
                for (int dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) {
                    for (int dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) if (!Terrain.ready(level, t.centre.x + dx, t.centre.z + dz)) notFull++;
                }
                Airstrike.LOG.info("ДИАГ квадрат {} грузится {} тиков: не полных чанков {} из {}", t.centre, now - t.heldAt, notFull, TILE * TILE);
            } else if (t.state == TileState.READY) {
                java.util.Map<String, Integer> why = new java.util.TreeMap<>();
                String example = null;
                for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                    for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                        long c = ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz);
                        String state = scars.diagState(c, now);
                        if (state == null && scars.pendingPlan(p.detonation, c)) state = "готовый план, работы в очереди нет";
                        if (state == null) continue;
                        why.merge(state, 1, Integer::sum);
                        if (example == null) example = new ChunkPos(c) + " — " + state;
                    }
                }
                Airstrike.LOG.info("ДИАГ квадрат {} готов {} тиков (грузился {}): держат {}{}", t.centre, now - t.readyAt, t.readyAt - t.heldAt,
                        why.isEmpty() ? "никто" : why, example == null ? "" : "; например " + example);
            }
        }
        Airstrike.LOG.info("ДИАГ очередь руин: {}", NukeDiag.takeWaits());
    }

    /** Строка для проверок: сколько чанков тяжёлой зоны уже в руинах (в памяти при подрыве и загруженных за волной). */
    private static void zoneReport(Prep p, ScarQueue scars, @Nullable Detonation d, long now) {
        int done = 0;
        for (long c : p.order) if (scars.ruined(p.detonation, c)) done++;
        Airstrike.LOG.info("Подрыв №{}: зона за волной — готово {} из {} чанков, квадратов держится {}", p.detonation, done, p.order.length, heldTiles(p));
        if (d == null || p.farAtDetonation == null || p.ruins == null) return;
        // дальние кольца: чанки не в памяти при подрыве, до которых волна уже дошла, — был ли план готов к её приходу
        int passed = 0, ready = 0, late = 0, none = 0, unreadable = 0;
        long lateSum = 0, lateMax = 0;
        for (long c : p.farAtDetonation) {
            long wave = d.gameTime() + (long) Math.ceil(d.arrivalTicks(nearest(new ChunkPos(c), d)));
            if (wave > now) continue;
            passed++;
            long at = p.ruins.doneAt(c);
            if (at >= 0 && at <= wave) {
                ready++;
            } else if (at >= 0) {
                late++;
                lateSum += at - wave;
                lateMax = Math.max(lateMax, at - wave);
            } else if (p.unreadable.contains(c)) {
                unreadable++;
            } else {
                none++;
            }
        }
        Airstrike.LOG.info("Подрыв №{}: дальние кольца: готово к волне {} из {} (позже волны {}: в среднем через {} тиков, самое большее через {}; "
                        + "плана ещё нет {}, не с диска {}; волна впереди у {})", p.detonation, ready, passed, late, late == 0 ? 0 : lateSum / late, lateMax,
                none, unreadable, p.farAtDetonation.length - passed);
    }

    private static boolean ready(ServerLevel level, Tile t) {
        for (int dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) {
            for (int dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) if (!Terrain.ready(level, t.centre.x + dx, t.centre.z + dz)) return false;
        }
        return true;
    }

    /**
     * Живой объём кучи выше {@link #HEAP_LIMIT} после двух сборок подряд: объём «после сборки» у старого поколения
     * бывает с несобранным мусором, одна сборка — ещё не нехватка.
     */
    private boolean heapTight() {
        long collections = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) if (collects(gc)) collections += Math.max(0, gc.getCollectionCount());
        if (collections != lastCollections) {
            lastCollections = collections;
            heapStrikes = liveHeap() > Runtime.getRuntime().maxMemory() * HEAP_LIMIT ? heapStrikes + 1 : 0;
        }
        return heapStrikes >= 2;
    }

    /**
     * Сборка, после которой пулы кучи знают живой объём: молодая, смешанная, полная, цикл ZGC или Shenandoah. Не паузы
     * внутри цикла: у G1 в JDK 21 «G1 Concurrent GC» считает Remark и Cleanup (объём после них не новый — одна сборка
     * давала бы два «подряд»), у ZGC и Shenandoah «… Pauses» повторяют их «… Cycles».
     */
    private static boolean collects(GarbageCollectorMXBean gc) {
        String name = gc.getName();
        return !name.contains("Concurrent") && !name.endsWith("Pauses");
    }

    /** Памяти мало: готовые планы остаются, остальные чанки — на месте при волне (медленнее, но без нехватки памяти). */
    private static void stopForHeap(Prep p) {
        Runtime rt = Runtime.getRuntime();
        Airstrike.LOG.warn("Руины удара №{}: куча после сборки {} из {} МБ — дальше руины заранее не строятся (планов {}, чанков тяжёлой зоны {})",
                p.strike, liveHeap() >> 20, rt.maxMemory() >> 20, p.plans.size(), p.order.length);
        p.heapStop = true;
    }

    /**
     * Живой объём кучи: занято после последней сборки (у ZGC и G1 — по пулам кучи); пулы без этих данных — занято
     * сейчас (с мусором, то есть с запасом).
     */
    private static long liveHeap() {
        long sum = 0;
        boolean any = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage after = pool.getCollectionUsage();
            if (after == null) continue;
            sum += after.getUsed();
            any = true;
        }
        if (any) return sum;
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Чанк, если он и чанки в радиусе {@link RuinPlanner#REACH} готовы (руины читают их). */
    @Nullable
    private static LevelChunk readyChunk(ServerLevel level, long c) {
        ChunkPos pos = new ChunkPos(c);
        return NuclearTickets.neighbourhoodLoaded(level, pos, RuinPlanner.REACH) ? level.getChunkSource().getChunkNow(pos.x, pos.z) : null;
    }

    // ---------------------------------------------------------------- подрыв

    /**
     * Подрыв: готовые руины того же места, мощности и масштаба — ему (очередь ставит их чанки первыми). Тикеты держатся,
     * пока руины не поставлены.
     *
     * @return готовые руины и порядок их чанков; null — не готовили
     */
    @Nullable
    Handoff handOff(ServerLevel level, Detonation d, long now) {
        for (Prep p : preps) {
            Detonation g = p.geometry;
            if (p.detonation >= 0 || p.draining || g == null || g.yieldKt() != d.yieldKt() || g.surface() != d.surface() || g.scale() != d.scale()
                    || g.burst().distanceTo(d.burst()) > SAME_PLACE) continue;
            p.detonation = d.id();
            p.handedOff = now;
            p.farAtDetonation = java.util.Arrays.stream(p.order).filter(c -> level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) == null).toArray();
            Airstrike.LOG.info("Подрыв №{}: руины заранее — планов по чанкам в памяти {} (чанков в памяти за полёт {}), ещё строятся {}; с диска {} "
                            + "(чанков зоны не в памяти при подрыве {}), ещё строятся {}, не с диска {}; фоновые потоки {}", d.id(), p.nearPlans, p.everInMemory.size(),
                    p.submitted.size(), p.farPlans,
                    p.farAtDetonation.length, p.farRunning.size(), p.farSkipped, RuinWorkers.summary());
            p.nextZoneReport = now + ZONE_REPORT;
            return new Handoff(p.plans, p.planned.toLongArray(), p.ruins);
        }
        return null;
    }

    record Handoff(Long2ObjectOpenHashMap<RuinPlan> plans, long[] order, RuinContext ruins) {}

    private static int heldTiles(Prep p) {
        int n = 0;
        for (Tile t : p.tiles) if (t.state == TileState.LOADING || t.state == TileState.READY) n++;
        return n;
    }

    /**
     * После подрыва: квадрат, где руины его чанков уже стоят, больше не держится — не больше нескольких квадратов за
     * тик: выгрузка чанка пишет его на диск в тике сервера. Выгруженный чанк сохраняется с руинами; чанки его кольца,
     * чьи руины ещё ждут соседей, пройдут с соседним квадратом или при загрузке.
     */
    private static void releaseDone(ServerLevel level, Prep p, ScarQueue scars, WorkClock clock) {
        int released = 0;
        for (Tile t : p.tiles) {
            if (released >= RELEASE_PER_TICK || !clock.canStart()) return;
            if (t.state != TileState.READY) continue;
            // руины всех чанков стоят — или оставшиеся ждут только соседей: тогда их держат свои тикеты
            boolean done = true;
            int waiting = 0;
            for (int dx = -TILE_RADIUS; done && dx <= TILE_RADIUS; dx++) {
                for (int dz = -TILE_RADIUS; done && dz <= TILE_RADIUS; dz++) {
                    long c = ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz);
                    if (!scars.pendingPlan(p.detonation, c) && !scars.queued(c)) continue;
                    done = scars.waitsNeighbours(c);
                    if (done) waiting++;
                }
            }
            if (!done || waiting > scars.tileHoldsLeft()) continue;
            long c0 = clock.begin();
            if (waiting > 0) {
                for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                    for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) scars.holdForTile(level, ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz));
                }
            }
            StrikeWorld.get(level).areas().release(level, t.area(p.strike));
            clock.end(c0);
            t.state = TileState.SKIP;
            released++;
            if (NukeDiag.ON) {
                long now = level.getGameTime(), load = t.readyAt - t.heldAt, idle = now - t.readyAt;
                long[] dn = p.diagDone;
                dn[0]++;
                dn[1] += load;
                dn[2] = Math.max(dn[2], load);
                dn[3] += idle;
                dn[4] = Math.max(dn[4], idle);
            }
        }
    }

    /** Отпустить до {@code limit} квадратов; сколько ещё держится. */
    private static int releaseSome(ServerLevel level, Prep p, int limit) {
        int held = 0;
        for (Tile t : p.tiles) {
            if (t.state != TileState.READY && t.state != TileState.LOADING) continue;
            if (limit-- > 0) {
                StrikeWorld.get(level).areas().release(level, t.area(p.strike));
                t.state = TileState.SKIP;
            } else {
                held++;
            }
        }
        return held;
    }

    private static void release(ServerLevel level, Prep p) {
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING || t.state == TileState.READY) StrikeWorld.get(level).areas().release(level, t.area(p.strike));
        }
        p.tiles.clear();
    }

    /** Отбой, выгрузка мира, ошибка: все тикеты отпущены, готовые руины, отданные подрыву, очереди больше не нужны. */
    public void clear(ServerLevel level, ScarQueue scars) {
        for (Prep p : preps) {
            release(level, p);
            if (p.ruins != null && p.detonation < 0) p.ruins.cancelTasks();
            if (p.detonation >= 0 && !p.draining) scars.dropPrepared(p.detonation);
        }
        preps.clear();
    }

    @Nullable
    private static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }
}
