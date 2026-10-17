package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Чанки не в мире, которые мод изменил бы при загрузке, — в LOD Distant Horizons сразу ({@link DhUpdates#offer}): руины
 * ядерки за волной ({@link NuclearPrep}: план уже готов, а сам чанк грузится, только когда к нему подойдут), след
 * подрыва на земле дальше тяжёлой зоны ({@link #scorch}) и лампы погасшего или вновь зажжённого квартала
 * ({@code BlackoutWorld}). Чанк читается с диска (поток ввода-вывода чанков, разбор — {@link RuinWorkers}), в копию его
 * секций ({@link DiskShots#readSections}) кладутся руины по готовому плану ({@link RuinPlan#applyToCopy}: план устарел —
 * копия не годится) или след по плану, построенному тут же по той же копии ({@link DiskShots#readScorch}: снимок — в
 * потоке сервера, план {@link RuinPlanner#scorch} — в фоне), и лампы — по сети сейчас ({@link ChunkLights#applyToCopy}),
 * копия уходит в DH как {@link ProtoChunk}. Мир копия не трогает: чанк, который загрузят потом, получит те же руины и
 * лампы своим путём. Копия без изменений против диска в DH не уходит — его LOD по этим же блокам уже есть.
 * <p>
 * Чанк за волной без готового плана проверяется на диске (поле {@code Status} — чтением заголовка в фоне, то же правило,
 * что у плана с диска, {@link DiskStatus#scan}): блоки окончательные — ждёт плана ({@link #PLAN_WAIT}); недогенерированный
 * или его нет (край исследованного мира: копию не собрать, а LOD там DH строит своим генератором — целым) — в мир
 * квадратом 5×5 ({@link FarZone}): ваниль догенерирует его, руины встанут путём загрузки, LOD уйдёт в DH с их отметкой.
 * Чанк мира 1.17 с догенерацией под нулём ({@link DiskStatus.State#FINAL}), не дождавшийся плана, — тоже в мир квадратом:
 * зона за волной его не грузит (без генерации он не загрузится), и его LOD иначе остался бы целым городом.
 * <p>
 * Только при DH ({@link DhUpdates#room}) и только чанки не дальше {@link #FAR_CHUNKS} от игрока: дальше DH по умолчанию
 * не рассылает обновления, а чтения с диска делят поток ввода-вывода с загрузкой мира игрокам — их не больше
 * {@link #READS} сразу и {@link #PER_TICK} новых за тик; запросов за тик просматривается не больше {@link #VISITS}
 * (у тяжёлой зоны их десятки тысяч, и почти все ждут планов).
 */
public final class FarLods {
    /** Докуда от игрока (чанки, по большей оси): дальность обновлений DH игрокам по умолчанию ({@code realTimeUpdates.playerDistance}). */
    static final int FAR_CHUNKS = 256;
    /** Чтений с диска в работе сразу и новых за тик. */
    static final int READS = 16, PER_TICK = 8;
    /** Сколько тиков чанк за волной ждёт готового плана (строится в фоне); дальше — без руин вдали (свет, если просили, и след подрыва уходят). */
    static final int PLAN_WAIT = 1200;
    /**
     * Запросов из очереди за тик, не больше (остальные — в следующих тиках по кругу): у тяжёлой зоны их десятки тысяч
     * (15278 у Артёма), почти все ждут плана до {@link #PLAN_WAIT}, и каждый — поиск держателя чанка и готового плана.
     */
    public static final int VISITS = 256;
    /** Чтений заголовков чанков с диска в работе сразу. */
    static final int SCANS = 32;
    /**
     * Что на диске у чанка за волной без плана ({@link DiskStatus.State}): заголовок читается; целый или с догенерацией
     * под нулём (план ещё может прийти); недогенерированный или нет.
     */
    private static final byte SCANNING = 1, WHOLE = 2, FINAL = 3, PARTIAL = 4;

    /**
     * Почему чанк в очереди: за волной ядерки — ждать его плана руин ({@link #RUINS}); квартал сменил свет — LOD уходит
     * и без изменений против диска: у DH мог остаться погашенный квартал ({@link #LIGHTS}); след подрыва на земле без
     * руин — кольцо за тяжёлой зоной ({@link #SCORCH}, {@link #scorch}). Бывает и несколько сразу.
     */
    private static final byte RUINS = 1, LIGHTS = 2, SCORCH = 4;
    /** Запросов следа в очереди, не больше: кольцо подаёт следующие по мере того, как очередь читает. */
    static final int SCORCH_QUEUE = 2 * VISITS;
    /**
     * Снимков следа в потоке сервера за тик, в чанках с картами высот на диске ({@link DiskShots.Read#cost}): снимок —
     * десятки микросекунд, досчёт карт высот — до 1,4 мс.
     */
    static final int SHOTS_PER_TICK = 2 * PER_TICK;
    /**
     * Время снимков следа за тик, не больше (первый — всегда): секция детального города — глобальная палитра, и её
     * снимок ({@code ChunkShot.register}) стоит в разы дороже, чем по числу чанков.
     */
    static final long SHOT_NANOS = 2_000_000;
    /**
     * Чанков кольца за тик, не больше: и тех, что далеко от игроков (их запрос не ставится): за 30 с волна проходит
     * сотни тысяч чанков кольца, и обход в одном тике стоил бы десятков мс.
     */
    static final int FEED_PER_TICK = 4 * VISITS;

    /** Запрос в очереди: с какого тика стоит. */
    private record Request(long chunk, long since) {}

    /** Готовая копия (или почему её нет) из фонового потока; {@code scorched} — в ней след подрыва без руин. */
    record Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, boolean lights, @Nullable String skip, boolean scorched) {
        Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, boolean lights, @Nullable String skip) {
            this(chunk, copy, ruined, lights, skip, false);
        }
    }

    /** Прочитанный чанк следа: снимок — в потоке сервера ({@link #shoot}), план и копия — в фоне. */
    private record Read(long chunk, Ring ring, DiskShots.Scorch scorch, boolean lights) {}

    /**
     * След подрыва на земле вдали ({@link #scorch}): чанки до {@link RuinPlanner#scorchReach} по приходу волны, ближние
     * первыми; тяжёлая зона, к которой готовились, — не здесь: её LOD — руины по готовым планам ({@link #RUINS}).
     */
    private static final class Ring {
        final Detonation d;
        /** Свой контекст подрыва: шум мира и край руин; снимков, разломов и карт высот он не держит. */
        final RuinContext ruins;
        /** Чанки (ближняя точка по горизонтали, {@link NuclearPrep#horizontal}) в {@code (skip, reach]}. */
        final double skip, reach;
        /** Чанки кольца по расстоянию — считаются в фоне; прошли все — пусто (память). */
        java.util.concurrent.CompletableFuture<long[]> order;
        int next;

        Ring(ServerLevel level, Detonation d, double skip) {
            this.d = d;
            this.ruins = new RuinContext(level, d);
            this.skip = skip;
            this.reach = RuinPlanner.scorchReach(d);
            this.order = java.util.concurrent.CompletableFuture.supplyAsync(() -> layout(d, skip, reach), RuinWorkers.executor(RuinWorkers.Priority.FAR)).exceptionally(e -> {
                Incoming.LOG.error("Far scorch of detonation #{}: ring layout failed", d.id(), e);
                return new long[0];
            });
        }

        /** След кольца на чанке: в радиусе, и волна дошла (тяжёлая зона тоже: её чанк, не дождавшийся плана). */
        boolean covers(long chunk, long now) {
            ChunkPos pos = new ChunkPos(chunk);
            return NuclearPrep.horizontal(pos, d) <= reach && ScarQueue.due(d, pos) <= now;
        }

        boolean done() {
            long[] o = order.getNow(null);
            return o != null && next >= o.length;
        }
    }

    /** Чанки кольца по расстоянию от эпицентра (любой поток): ключ — расстояние, одна сортировка чисел. */
    private static long[] layout(Detonation d, double skip, double reach) {
        int cx = net.minecraft.util.Mth.floor(d.burst().x) >> 4, cz = net.minecraft.util.Mth.floor(d.burst().z) >> 4;
        int r = net.minecraft.util.Mth.ceil(reach / 16) + 1;
        it.unimi.dsi.fastutil.longs.LongArrayList keyed = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double h = NuclearPrep.horizontal(new ChunkPos(x, z), d);
                // ключ: расстояние (1/16 блока) в старших битах, сдвиг чанка от эпицентра — в младших
                if (h > skip && h <= reach) keyed.add((long) (h * 16) << 32 | (long) (x - cx + 0x8000) << 16 | (z - cz + 0x8000));
            }
        }
        long[] out = keyed.toLongArray();
        java.util.Arrays.sort(out);
        for (int i = 0; i < out.length; i++) out[i] = ChunkPos.asLong(cx + (int) (out[i] >> 16 & 0xFFFF) - 0x8000, cz + (int) (out[i] & 0xFFFF) - 0x8000);
        return out;
    }

    /**
     * Очереди: руины — первыми, свет — остатком просмотров (не меньше четверти, {@link #VISITS}). Блэкаут ставит весь
     * свой радиус сразу (до 159 тыс. запросов света), и в общей очереди руины за волной стояли бы за ним.
     */
    private final ArrayDeque<Request> ruinQueue = new ArrayDeque<>(), lightQueue = new ArrayDeque<>(), scorchQueue = new ArrayDeque<>();
    /** Кольца следа подрывов этого мира ({@link #scorch}): и после того, как прошли, — для запросов света в них. */
    private final java.util.List<Ring> rings = new java.util.ArrayList<>();
    /** Прочитанные чанки следа, ждущие снимка в потоке сервера. */
    private final ConcurrentLinkedQueue<Read> shots = new ConcurrentLinkedQueue<>();
    /**
     * Что просили у чанка в очереди ({@link #RUINS}, {@link #LIGHTS}, {@link #SCORCH}): второй запрос того же чанка
     * добавляет свой вид, а не теряется: иначе запрос руин чанка, уже стоящего в очереди света, пропал бы, и он прошёл бы
     * без руин.
     */
    private final Long2ByteOpenHashMap queued = new Long2ByteOpenHashMap();
    /** Чанки в чтении. */
    private final LongOpenHashSet reading = new LongOpenHashSet();
    /**
     * Из них — чанки следа (чтение, снимок, план и копия): кольцо начинает новые, пока их меньше половины
     * {@link #READS}, остальное — руинам и свету (свет и руины без плана в кольце идут тем же путём и тоже в счёте).
     */
    private final LongOpenHashSet scorchReading = new LongOpenHashSet();
    /** Чанки, которые за время чтения попросили снова, и что просили: после чтения — в очередь ещё раз. */
    private final Long2ByteOpenHashMap again = new Long2ByteOpenHashMap();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    /** Чанки за волной без плана: что на диске ({@link #SCANNING}, {@link #WHOLE}, {@link #FINAL}, {@link #PARTIAL}); ответы — из потока ввода-вывода. */
    private final Long2ByteOpenHashMap disk = new Long2ByteOpenHashMap();
    private final ConcurrentLinkedQueue<long[]> scanned = new ConcurrentLinkedQueue<>();
    private int scanning;
    /** Чанки, не целые на диске: в мир квадратами. */
    private final FarZone zone = new FarZone();
    @Nullable
    private DiskShots.Format format;
    /**
     * Для строки в лог и проверок: ушло в DH (с руинами, со следом подрыва без руин), без изменений, план устарел, не
     * прочитаны, не дождались плана, не целые на диске.
     */
    private long sent, sentRuins, sentScorch, unchanged, stale, unread, noPlan, partial;
    /** Для проверок: больше всего запросов, просмотренных за один тик. */
    private int visitsMax;
    private long nextReport;
    /** Снимок следа уже падал (в лог — первый раз). */
    private boolean shotFailed;
    /** Проверки: место, которое считается игроком ({@link #nearPlayer}), — игроков у GameTest нет. */
    @Nullable
    private ChunkPos viewer;

    /** Для {@link ModAttachments#FAR_LODS}: своё у каждого мира, не сохраняется. */
    public FarLods() {}

    public static FarLods get(ServerLevel level) {
        return level.getData(ModAttachments.FAR_LODS);
    }

    /**
     * Чанк не в мире, чьи лампы или руины сейчас другие, чем в LOD DH: в LOD, если он рядом с игроком.
     *
     * @param ruins чанк за волной ядерки — ждать его плана руин ({@link #PLAN_WAIT}); иначе квартал сменил свет
     */
    public static void request(ServerLevel level, long chunk, boolean ruins) {
        get(level).request(level, chunk, ruins ? RUINS : LIGHTS);
    }

    private void request(ServerLevel level, long chunk, byte kind) {
        if (!DhUpdates.enabled(level) || !nearPlayer(level, chunk)) return;
        // копия тяжёлой зоны, собранная заранее, уходит к фронту сама: руины её чанка и свет квартала — с ней
        if (kind != SCORCH && front(level, chunk, kind == LIGHTS)) return;
        queue(level, chunk, kind);
    }

    /**
     * Чанк, чью копию держит фронт ({@link FrontLods#claim}): запрос — её; тьма квартала к фронту иная, чем у ушедшей
     * копии, — копия заново запросом руин и света (руины ждут плана, то есть фронта).
     */
    private boolean front(ServerLevel level, long chunk, boolean lights) {
        int claim = NuclearWorld.get(level).prep().frontClaim(level, chunk, lights, level.getGameTime());
        if (claim == FrontLods.AFTER_FRONT) {
            queue(level, chunk, RUINS);
            queue(level, chunk, LIGHTS);
        }
        return claim != FrontLods.NONE;
    }

    private void queue(ServerLevel level, long chunk, byte kind) {
        if (reading.contains(chunk)) {
            again.put(chunk, (byte) (again.get(chunk) | kind));
            return;
        }
        byte was = queued.get(chunk);
        if ((was & kind) != 0) return;
        queued.put(chunk, (byte) (was | kind));
        // чанк с руинами проходит очередь руин (свет и след — с ним), со следом — очередь следа (свет — с ним); в
        // остальных очередях он, если был, пропускается
        long now = level.getGameTime();
        if (kind == RUINS) ruinQueue.add(new Request(chunk, now));
        else if (kind == SCORCH && (was & RUINS) == 0) scorchQueue.add(new Request(chunk, now));
        else if (kind == LIGHTS && was == 0) lightQueue.add(new Request(chunk, now));
    }

    /**
     * Подрыв в мире с DH: след на земле без руин ({@link RuinPlanner#scorch}: выгоревший дёрн, гарь, опалённые биомы) —
     * в LOD чанков не в мире до {@link RuinPlanner#scorchReach} (у 15 кт — около 7,5 км), по мере прихода волны, ближние
     * первыми. Без него LOD вдали за тяжёлой зоной оставался бы зелёным: план руин заранее строится только для неё.
     *
     * @param prepared к подрыву готовились ({@link NuclearPrep}): тяжёлая зона уходит в LOD руинами по готовым планам
     */
    public static void scorch(ServerLevel level, Detonation d, boolean prepared) {
        if (!DhUpdates.enabled(level)) return;
        get(level).rings.add(new Ring(level, d, prepared ? NuclearPrep.heavyRadius(d) + 16 : -1));
    }

    /** Кольца следа подают чанки, до которых дошла волна, пока в очереди следа есть место. */
    private void feedRings(ServerLevel level, long now) {
        for (Ring ring : rings) {
            long[] order = ring.order.getNow(null);
            if (order == null || ring.done()) continue;
            for (int n = FEED_PER_TICK; n > 0 && ring.next < order.length && scorchQueue.size() < SCORCH_QUEUE; n--, ring.next++) {
                if (ScarQueue.due(ring.d, new ChunkPos(order[ring.next])) > now) break;
                request(level, order[ring.next], SCORCH);
            }
            if (ring.next >= order.length) {
                Incoming.LOG.info("Far scorch of detonation #{}: all {} chunks to {} m handed to the LOD queue", ring.d.id(), order.length, Math.round(ring.reach));
                ring.order = java.util.concurrent.CompletableFuture.completedFuture(new long[0]);
                ring.next = 0;
            }
        }
    }

    /** Последнее кольцо, чей след лежит на чанке (поток сервера); null — нет такого. */
    @Nullable
    private Ring ringOf(long chunk, long now) {
        for (int i = rings.size() - 1; i >= 0; i--) if (rings.get(i).covers(chunk, now)) return rings.get(i);
        return null;
    }

    /** Чанк в дальности DH ({@link #FAR_CHUNKS}) от игрока этого мира. */
    static boolean nearPlayer(ServerLevel level, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        ChunkPos viewer = get(level).viewer;
        if (viewer != null && Math.max(Math.abs(viewer.x - x), Math.abs(viewer.z - z)) <= FAR_CHUNKS) return true;
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            if (Math.max(Math.abs(at.x - x), Math.abs(at.z - z)) <= FAR_CHUNKS) return true;
        }
        return false;
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.FAR_LODS)) get(level).tick(level);
    }

    private void tick(ServerLevel level) {
        if (queued.isEmpty()) {
            // в очередях остались только снятые запросы
            ruinQueue.clear();
            lightQueue.clear();
            scorchQueue.clear();
        }
        if (queued.isEmpty() && reading.isEmpty() && scanning == 0 && !zone.busy() && !ringsFeeding()) return;
        long now = level.getGameTime();
        for (Built b; (b = built.poll()) != null; ) harvest(level, b);
        shoot(level);
        for (long[] s; (s = scanned.poll()) != null; ) {
            scanning--;
            // ответ на чанк, который уже не ждёт (отбой, загружен), не нужен
            if (queued.containsKey(s[0])) disk.put(s[0], (byte) s[1]);
            else disk.remove(s[0]);
        }
        if (!DhUpdates.enabled(level)) {
            // DH ушёл (мир DH закрыт): просить нечего
            clear(level);
            return;
        }
        feedRings(level, now);
        int room = DhUpdates.room(level) - reading.size();
        NuclearWorld nuclear = NuclearWorld.get(level);
        NuclearEvents events = NuclearEvents.get(level);
        int started = 0, visits = 0;
        // руины — первыми, за ними след; свету остаток, но не меньше четверти просмотров: запросы руин, ждущие плана,
        // ходят по кругу
        int ruinVisits = Math.min(ruinQueue.size(), lightQueue.isEmpty() ? VISITS : VISITS - VISITS / 4);
        for (int n = ruinVisits; n > 0 && started < PER_TICK && reading.size() < READS && room > 0; n--) {
            visits++;
            if (visit(level, ruinQueue, nuclear, events, now)) {
                started++;
                room--;
            }
        }
        // след — не больше половины чтений (их держит и снимок в очереди пула руин), а при свете в очереди — не все новые
        // за тик: руинам и свету остаётся
        int scorchStarts = lightQueue.isEmpty() ? PER_TICK : PER_TICK - PER_TICK / 4;
        for (int n = Math.min(scorchQueue.size(), (lightQueue.isEmpty() ? VISITS : VISITS - VISITS / 4) - visits);
             n > 0 && started < scorchStarts && reading.size() < READS && scorchReading.size() < READS / 2 && room > 0; n--) {
            visits++;
            if (visit(level, scorchQueue, nuclear, events, now)) {
                started++;
                room--;
            }
        }
        for (int n = Math.min(lightQueue.size(), VISITS - visits); n > 0 && started < PER_TICK && reading.size() < READS && room > 0; n--) {
            visits++;
            if (visit(level, lightQueue, nuclear, events, now)) {
                started++;
                room--;
            }
        }
        visitsMax = Math.max(visitsMax, visits);
        // настройку выключили посреди зоны: квадраты больше не держатся
        if (IncomingConfig.SERVER.nukeFarZone.get()) zone.tick(level);
        else zone.clear(level);
        if (now >= nextReport && sent + unchanged + stale + unread + noPlan + partial > 0) {
            nextReport = now + 600;
            Incoming.LOG.info("Far Distant Horizons LODs ({}): sent {} chunks ({} with ruins, {} scorched), unchanged {}, stale plan {}, unread {}, "
                            + "no plan in time {}, incomplete on disk {} (into the world: {}); queued {} (ruins {}, scorch {}), reading {}{}", level.dimension().location(),
                    sent, sentRuins, sentScorch, unchanged, stale, unread, noPlan, partial, zone.summary(), queued.size(), ruinQueue.size(), scorchQueue.size(),
                    reading.size(), ringSummary());
        }
    }

    /** Кольцо следа ещё подаёт чанки (или его порядок считается). */
    private boolean ringsFeeding() {
        for (Ring ring : rings) if (!ring.done()) return true;
        return !shots.isEmpty();
    }

    private String ringSummary() {
        StringBuilder b = new StringBuilder();
        for (Ring ring : rings) {
            long[] o = ring.order.getNow(null);
            if (o != null && ring.next < o.length) b.append(String.format(java.util.Locale.ROOT, "; scorch of detonation #%d: %d of %d chunks to %.0f m", ring.d.id(), ring.next, o.length, ring.reach));
        }
        return b.toString();
    }

    /**
     * Прочитанные чанки следа — снимком в поток сервера (состояния палитр — в таблицу свойств), не больше
     * {@link #SHOTS_PER_TICK} и {@link #SHOT_NANOS} за тик и пока пул руин берёт задачи; план следа и копия — в фоне.
     * Не прочитанные (нет на диске) — сразу, им пул не нужен.
     */
    private void shoot(ServerLevel level) {
        if (shots.isEmpty()) return;
        java.util.List<Read> taken = new java.util.ArrayList<>();
        java.util.List<ChunkShot> made = new java.util.ArrayList<>();
        int cost = 0;
        long until = System.nanoTime() + SHOT_NANOS;
        // берёт только поток сервера: peek и poll дают один и тот же чанк
        for (Read r; (r = shots.peek()) != null; ) {
            String skip = r.scorch.read().skip();
            if (skip == null && (cost >= SHOTS_PER_TICK || !taken.isEmpty() && System.nanoTime() >= until
                    || RuinWorkers.inFlight() + taken.size() >= RuinWorkers.capacity())) break;
            shots.poll();
            if (skip != null) {
                built.add(new Built(r.chunk, null, false, r.lights, skip));
                continue;
            }
            DiskShots.Read read = r.scorch.read();
            cost += read.cost();
            try {
                made.add(ChunkShot.fromDisk(read.primed(Blast.SERVER), null));
                taken.add(r);
            } catch (RuntimeException e) {
                // состояние блока мода, у которого свойства бросают: без копии, а не «Exception ticking world»
                if (!shotFailed) Incoming.LOG.warn("Far Distant Horizons LODs: snapshot of chunk {} failed, it stays without the scorch (further failures are counted as unread)",
                        new ChunkPos(r.chunk), e);
                shotFailed = true;
                built.add(new Built(r.chunk, null, false, r.lights, "snapshot error: " + e));
            }
        }
        if (taken.isEmpty()) return;
        RuinPlanner.ensureOwnStates();
        Blast.PropsView view = Blast.publish();
        boolean fires = IncomingConfig.SERVER.nukeFires.get();
        DiskShots.Format f = format;
        for (int i = 0; i < taken.size(); i++) {
            Read r = taken.get(i);
            RuinContext.Grid g = r.ring.ruins.scorchGrid(level, made.get(i), view);
            java.util.concurrent.CompletableFuture.supplyAsync(() -> build(f, r.scorch.sections(), RuinPlanner.scorch(g), fires, r.lights, true), RuinWorkers.executor(RuinWorkers.Priority.FAR))
                    .exceptionally(e -> new Built(r.chunk, null, false, r.lights, "error: " + e)).thenAccept(built::add);
        }
    }

    /**
     * Запрос из очереди {@code from}: снят (чанк в мире, не целый на диске, плана не дождался), отложен по кругу (ждёт
     * плана руин) или отдан на чтение — тогда true.
     */
    private boolean visit(ServerLevel level, ArrayDeque<Request> from, NuclearWorld nuclear, NuclearEvents events, long now) {
        Request r = from.poll();
        byte kind = queued.get(r.chunk);
        // снят раньше или попросился и с руинами (со следом) — его проходит очередь руин (следа)
        if (kind == 0 || from != ruinQueue && (kind & RUINS) != 0 || from == lightQueue && (kind & SCORCH) != 0) return false;
        if (kind == LIGHTS) {
            // копия чанка к фронту собралась, пока запрос ждал: свет — с ней
            int claim = nuclear.prep().frontClaim(level, r.chunk, true, now);
            if (claim == FrontLods.OURS) {
                done(r.chunk);
                return false;
            }
            if (claim == FrontLods.AFTER_FRONT) {
                queued.put(r.chunk, (byte) (RUINS | LIGHTS));
                ruinQueue.add(new Request(r.chunk, now));
                return false;
            }
        }
        boolean ruins = (kind & RUINS) != 0;
        ChunkPos pos = new ChunkPos(r.chunk);
        if (inWorld(level, r.chunk)) {
            // загружен: руины и лампы ему ставит мир, а LOD — их отметка (DhUpdates.mark)
            done(r.chunk);
            return false;
        }
        RuinPlan plan = nuclear.prep().arrivedPlan(level, events, r.chunk, now);
        if (plan == null && ruins) {
            byte state = disk.get(r.chunk);
            if (state == 0 && scanning < SCANS) {
                scan(level, r.chunk);
                state = SCANNING;
            }
            if (state == PARTIAL) {
                // копию не собрать: чанк — в мир, руины — путём загрузки (настройка far_zone выключена — когда к нему подойдут)
                done(r.chunk);
                if (IncomingConfig.SERVER.nukeFarZone.get()) zone.offer(r.chunk);
                partial++;
                return false;
            }
            if (state != WHOLE && state != FINAL || now - r.since < PLAN_WAIT) {
                from.add(r);
                return false;
            }
            noPlan++;
            if (state == FINAL) {
                // чанк 1.17 без плана: зона за волной его не грузит — в мир квадратом, руины путём загрузки
                done(r.chunk);
                if (IncomingConfig.SERVER.nukeFarZone.get()) zone.offer(r.chunk);
                return false;
            }
            if ((kind & LIGHTS) == 0 && ringOf(r.chunk, now) == null) {
                done(r.chunk);
                return false;
            }
            // квартал сменил свет или на чанке след подрыва: копия уходит и без руин
        }
        done(r.chunk);
        if (format == null) format = DiskShots.Format.of(level);
        reading.add(r.chunk);
        DiskShots.Format f = format;
        boolean fires = IncomingConfig.SERVER.nukeFires.get(), lights = (kind & LIGHTS) != 0;
        // без плана руин на чанке может лежать след подрыва (свет квартала — с ним): снимок, план следа, копия
        Ring ring = plan == null ? ringOf(r.chunk, now) : null;
        if (ring != null) {
            scorchReading.add(r.chunk);
            DiskShots.readScorch(f, pos).thenAccept(s -> shots.add(new Read(r.chunk, ring, s, lights)));
            return true;
        }
        DiskShots.readSections(f, pos).thenApply(s -> build(f, s, plan, fires, lights, false)).exceptionally(e -> new Built(r.chunk, null, false, false, "error: " + e))
                .thenAccept(built::add);
        return true;
    }

    /** Запрос снят с очереди: что у чанка на диске, больше не нужно (ответ на заголовок в работе ещё придёт). */
    private void done(long chunk) {
        queued.remove(chunk);
        if (disk.get(chunk) != SCANNING) disk.remove(chunk);
    }

    /** Заголовок чанка с диска ({@link DiskStatus#scan}): только заголовок, в потоке ввода-вывода чанков; ответ — в {@link #scanned}. */
    private void scan(ServerLevel level, long chunk) {
        disk.put(chunk, SCANNING);
        scanning++;
        ConcurrentLinkedQueue<long[]> out = scanned;
        DiskStatus.scan(level, new ChunkPos(chunk)).thenAccept(state -> out.add(new long[]{chunk, switch (state) {
            case WHOLE -> WHOLE;
            case FINAL -> FINAL;
            case PARTIAL -> PARTIAL;
        }}));
    }

    /** Копия чанка с руинами или следом (фоновый поток): секции с диска, места плана. */
    static Built build(DiskShots.Format f, DiskShots.Sections s, @Nullable RuinPlan plan, boolean fires, boolean lights, boolean scorch) {
        long chunk = s.pos().toLong();
        if (s.skip() != null) return new Built(chunk, null, false, lights, s.skip());
        int biomes = plan == null ? 0 : plan.applyToCopy(s.sections(), fires, f.biomes(), f.minSection());
        if (biomes < 0) return new Built(chunk, null, false, lights, "stale plan");
        ProtoChunk copy = new ProtoChunk(s.pos(), UpgradeData.EMPTY, s.sections(), new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                LevelHeightAccessor.create(f.minY(), f.height()), f.biomes(), null);
        copy.setPersistedStatus(ChunkStatus.FULL);
        return new Built(chunk, copy, plan != null && plan.changedBlocks() + plan.fireCount() + biomes > 0, lights, null, scorch);
    }

    /** Поток сервера: лампы — к сети сейчас; изменилось против диска (или квартал сменил свет) — в DH. */
    private void harvest(ServerLevel level, Built b) {
        reading.remove(b.chunk);
        scorchReading.remove(b.chunk);
        byte asked = again.remove(b.chunk);
        for (byte kind : new byte[]{RUINS, SCORCH, LIGHTS}) if ((asked & kind) != 0) queue(level, b.chunk, kind);
        if (b.copy == null) {
            if ("stale plan".equals(b.skip)) stale++;
            else unread++;
            return;
        }
        // загрузили, пока читали: у мира свой путь
        if (inWorld(level, b.chunk)) return;
        // копия без руин чанка, чью копию с руинами держит фронт: ушла бы поверх неё
        if (!b.ruined && front(level, b.chunk, true)) return;
        // на диске двойников нет: погасить, если квартал тёмный
        boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(b.chunk), ChunkPos.getZ(b.chunk), level.getGameTime());
        int lamps = dark ? ChunkLights.applyToCopy(b.copy.getSections(), true) : 0;
        if (!b.ruined && lamps == 0 && !b.lights) {
            unchanged++;
            return;
        }
        DhUpdates.offer(level, b.copy);
        sent++;
        if (b.ruined && b.scorched) sentScorch++;
        else if (b.ruined) sentRuins++;
    }

    /**
     * Проверки: копия загруженного чанка — его данные сохранения (как на диске), разобранные как чанк не в мире, с
     * руинами плана ({@code fires} — с пожарами) и лампами по сети; null — план к копии не подошёл.
     */
    @Nullable
    public static ProtoChunk testCopy(ServerLevel level, LevelChunk chunk, @Nullable RuinPlan plan, boolean fires) {
        DiskShots.Format f = DiskShots.Format.of(level);
        CompoundTag tag = ChunkSerializer.write(level, chunk);
        Built b = build(f, DiskShots.parseSections(f, chunk.getPos(), tag), plan, fires, false, false);
        if (b.copy == null) return null;
        if (PowerGrid.get(level).dark(chunk.getPos().x, chunk.getPos().z, level.getGameTime())) ChunkLights.applyToCopy(b.copy.getSections(), true);
        return b.copy;
    }

    /**
     * Чанк в памяти, бывший полностью загруженным (и опущенный ниже у края видимости): его руины и лампы ставит мир
     * ({@link ScarQueue}, блэкаут). Недогенерированный чанк в памяти (кольцо вокруг загруженного мира) — не в мире: его
     * никто не пройдёт, пока он не загрузится.
     */
    static boolean inWorld(ServerLevel level, long chunk) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk;
    }

    /**
     * Отбой, DH ушёл или остановка сервера: очередь не нужна (чтения в работе доходят и выбрасываются), квадраты
     * {@link FarZone} отпущены — до {@code util/StopDrain}: генерацию, которую они начали, доводит до конца он.
     */
    public void clear(ServerLevel level) {
        ruinQueue.clear();
        lightQueue.clear();
        scorchQueue.clear();
        // прочитанные чанки следа (в shots) доходят до конца, как и чтения в работе
        rings.clear();
        queued.clear();
        again.clear();
        disk.long2ByteEntrySet().removeIf(e -> e.getByteValue() != SCANNING);
        zone.clear(level);
    }

    /** Проверки: чанки у {@code at} — как у игрока (в дальности DH); null — снова только игроки. */
    public static void testViewer(ServerLevel level, @Nullable ChunkPos at) {
        get(level).viewer = at;
    }

    /** Проверки: срок генерации квадрата {@link FarZone} в тиках; меньше нуля — снова {@link FarZone#LOAD_LIMIT}. */
    public static void testZoneLoadLimit(ServerLevel level, int ticks) {
        get(level).zone.loadLimit(ticks >= 0 ? ticks : FarZone.LOAD_LIMIT);
    }

    /** Для проверок: больше всего запросов, просмотренных за один тик ({@link #VISITS}). */
    public int visitsMax() {
        return visitsMax;
    }

    /**
     * Для проверок: ушло, с руинами, без изменений, план устарел, не прочитаны, не дождались плана, не целые на диске,
     * в очереди, читаются, со следом.
     */
    public long[] stats() {
        return new long[] {sent, sentRuins, unchanged, stale, unread, noPlan, partial, queued.size(), reading.size(), sentScorch};
    }

    /** Для проверок: квадраты в мир ({@link FarZone#stats}): ждут, держатся, взято, отпущено, по сроку, брошено, пик загрузки. */
    public long[] zoneStats() {
        return zone.stats();
    }

    /** Для проверок: дождаться чтений, заголовков и квадратов в работе. */
    public boolean busy() {
        return !queued.isEmpty() || !reading.isEmpty() || scanning > 0 || zone.busy() || ringsFeeding();
    }
}
