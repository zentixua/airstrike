package ua.zentix.airstrike.nuclear.world;

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
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.grid.ChunkLights;
import ua.zentix.airstrike.grid.PowerGrid;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Чанки не в мире, которые мод изменил бы при загрузке, — в LOD Distant Horizons сразу ({@link DhUpdates#offer}): руины
 * ядерки за волной ({@link NuclearPrep}: план уже готов, а сам чанк грузится, только когда к нему подойдут) и лампы
 * погасшего или вновь зажжённого квартала ({@code BlackoutWorld}). Чанк читается с диска (поток ввода-вывода чанков,
 * разбор — {@link RuinWorkers}), в копию его секций ({@link DiskShots#readSections}) кладутся руины по готовому плану
 * ({@link RuinPlan#applyToCopy}: план устарел — копия не годится) и лампы — по сети сейчас ({@link ChunkLights#applyToCopy}),
 * копия уходит в DH как {@link ProtoChunk}. Мир копия не трогает: чанк, который загрузят потом, получит те же руины и
 * лампы своим путём. Копия без изменений против диска в DH не уходит — его LOD по этим же блокам уже есть.
 * <p>
 * Чанк за волной без готового плана проверяется на диске (поле {@code Status} — чтением заголовка в фоне, как у зоны за
 * волной, {@link DiskStatus#whole}): целый — ждёт плана ({@link #PLAN_WAIT}); не целый или его нет (край исследованного
 * мира: копию не собрать, а LOD там DH строит своим генератором — целым) — в мир квадратом 5×5 ({@link FarZone}):
 * ваниль догенерирует его, руины встанут путём загрузки, LOD уйдёт в DH с их отметкой.
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
    /** Сколько тиков чанк за волной ждёт готового плана (строится в фоне); дальше — без руин вдали (свет, если просили, уходит). */
    static final int PLAN_WAIT = 1200;
    /**
     * Запросов из очереди за тик, не больше (остальные — в следующих тиках по кругу): у тяжёлой зоны их десятки тысяч
     * (15278 у Артёма), почти все ждут плана до {@link #PLAN_WAIT}, и каждый — поиск держателя чанка и готового плана.
     */
    public static final int VISITS = 256;
    /** Чтений заголовков чанков с диска в работе сразу. */
    static final int SCANS = 32;
    /** Что на диске у чанка за волной без плана: заголовок читается, целый (план ещё может прийти), не целый или нет. */
    private static final byte SCANNING = 1, WHOLE = 2, PARTIAL = 3;

    /**
     * Почему чанк в очереди: за волной ядерки — ждать его плана руин ({@link #RUINS}); квартал сменил свет — LOD уходит
     * и без изменений против диска: у DH мог остаться погашенный квартал ({@link #LIGHTS}). Бывает и то и другое.
     */
    private static final byte RUINS = 1, LIGHTS = 2;

    /** Запрос в очереди: с какого тика стоит. */
    private record Request(long chunk, long since) {}

    /** Готовая копия (или почему её нет) из фонового потока. */
    private record Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, boolean lights, @Nullable String skip) {}

    /**
     * Очереди: руины — первыми, свет — остатком просмотров (не меньше четверти, {@link #VISITS}). Блэкаут ставит весь
     * свой радиус сразу, и руины за волной стояли за ним (игра Артёма 01.10.2026: 159 тыс. запросов света).
     */
    private final ArrayDeque<Request> ruinQueue = new ArrayDeque<>(), lightQueue = new ArrayDeque<>();
    /**
     * Что просили у чанка в очереди ({@link #RUINS}, {@link #LIGHTS}): второй запрос того же чанка добавляет свой вид, а
     * не теряется — раньше запрос руин чанка, уже стоявшего в очереди света, пропадал, и он проходил без руин.
     */
    private final Long2ByteOpenHashMap queued = new Long2ByteOpenHashMap();
    /** Чанки в чтении. */
    private final LongOpenHashSet reading = new LongOpenHashSet();
    /** Чанки, которые за время чтения попросили снова, и что просили: после чтения — в очередь ещё раз. */
    private final Long2ByteOpenHashMap again = new Long2ByteOpenHashMap();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    /** Чанки за волной без плана: что на диске ({@link #SCANNING}, {@link #WHOLE}, {@link #PARTIAL}); ответы — из потока ввода-вывода. */
    private final Long2ByteOpenHashMap disk = new Long2ByteOpenHashMap();
    private final ConcurrentLinkedQueue<long[]> scanned = new ConcurrentLinkedQueue<>();
    private int scanning;
    /** Чанки, не целые на диске: в мир квадратами. */
    private final FarZone zone = new FarZone();
    @Nullable
    private DiskShots.Format format;
    /**
     * Для строки в лог и проверок: ушло в DH (с руинами), без изменений, план устарел, не прочитаны, не дождались плана,
     * не целые на диске.
     */
    private long sent, sentRuins, unchanged, stale, unread, noPlan, partial;
    /** Для проверок: больше всего запросов, просмотренных за один тик. */
    private int visitsMax;
    private long nextReport;
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
        if (!DhUpdates.enabled(level) || !nearPlayer(level, chunk)) return;
        FarLods lods = get(level);
        byte kind = ruins ? RUINS : LIGHTS;
        if (lods.reading.contains(chunk)) {
            lods.again.put(chunk, (byte) (lods.again.get(chunk) | kind));
            return;
        }
        byte was = lods.queued.get(chunk);
        if ((was & kind) != 0) return;
        lods.queued.put(chunk, (byte) (was | kind));
        // чанк с руинами проходит очередь руин (и свет — с ним); в очереди света он, если был, пропускается
        if (ruins) lods.ruinQueue.add(new Request(chunk, level.getGameTime()));
        else if (was == 0) lods.lightQueue.add(new Request(chunk, level.getGameTime()));
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
        }
        if (queued.isEmpty() && reading.isEmpty() && scanning == 0 && !zone.busy()) return;
        long now = level.getGameTime();
        for (Built b; (b = built.poll()) != null; ) harvest(level, b);
        for (long[] s; (s = scanned.poll()) != null; ) {
            scanning--;
            // ответ на чанк, который уже не ждёт (отбой, загружен), не нужен
            if (queued.containsKey(s[0])) disk.put(s[0], s[1] != 0 ? WHOLE : PARTIAL);
            else disk.remove(s[0]);
        }
        if (!DhUpdates.enabled(level)) {
            // DH ушёл (мир DH закрыт): просить нечего
            clear(level);
            return;
        }
        int room = DhUpdates.room(level) - reading.size();
        NuclearWorld nuclear = NuclearWorld.get(level);
        NuclearEvents events = NuclearEvents.get(level);
        int started = 0, visits = 0;
        // руины — первыми; свету остаток, но не меньше четверти просмотров: запросы руин, ждущие плана, ходят по кругу
        int ruinVisits = Math.min(ruinQueue.size(), lightQueue.isEmpty() ? VISITS : VISITS - VISITS / 4);
        for (int n = ruinVisits; n > 0 && started < PER_TICK && reading.size() < READS && room > 0; n--) {
            visits++;
            if (visit(level, ruinQueue, nuclear, events, now)) {
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
        if (AirstrikeConfig.SERVER.nukeFarZone.get()) zone.tick(level);
        else zone.clear(level);
        if (now >= nextReport && sent + unchanged + stale + unread + noPlan + partial > 0) {
            nextReport = now + 600;
            Airstrike.LOG.info("LOD Distant Horizons вдали ({}): ушло {} чанков (с руинами {}), без изменений {}, план устарел {}, не прочитаны {}, "
                            + "не дождались плана {}, не целые на диске {} (в мир: {}); в очереди {}, читаются {}", level.dimension().location(), sent,
                    sentRuins, unchanged, stale, unread, noPlan, partial, zone.summary(), queued.size(), reading.size());
        }
    }

    /**
     * Запрос из очереди {@code from}: снят (чанк в мире, не целый на диске, плана не дождался), отложен по кругу (ждёт
     * плана руин) или отдан на чтение — тогда true.
     */
    private boolean visit(ServerLevel level, ArrayDeque<Request> from, NuclearWorld nuclear, NuclearEvents events, long now) {
        Request r = from.poll();
        byte kind = queued.get(r.chunk);
        // снят раньше или попросился и с руинами — его проходит очередь руин
        if (kind == 0 || from == lightQueue && (kind & RUINS) != 0) return false;
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
                if (AirstrikeConfig.SERVER.nukeFarZone.get()) zone.offer(r.chunk);
                partial++;
                return false;
            }
            if (state != WHOLE || now - r.since < PLAN_WAIT) {
                from.add(r);
                return false;
            }
            noPlan++;
            if ((kind & LIGHTS) == 0) {
                done(r.chunk);
                return false;
            }
            // квартал сменил свет: копия уходит и без руин
        }
        done(r.chunk);
        if (format == null) format = DiskShots.Format.of(level);
        reading.add(r.chunk);
        DiskShots.Format f = format;
        boolean fires = AirstrikeConfig.SERVER.nukeFires.get(), lights = (kind & LIGHTS) != 0;
        DiskShots.readSections(f, pos).thenApply(s -> build(f, s, plan, fires, lights)).exceptionally(e -> new Built(r.chunk, null, false, false, "ошибка: " + e))
                .thenAccept(built::add);
        return true;
    }

    /** Запрос снят с очереди: что у чанка на диске, больше не нужно (ответ на заголовок в работе ещё придёт). */
    private void done(long chunk) {
        queued.remove(chunk);
        if (disk.get(chunk) != SCANNING) disk.remove(chunk);
    }

    /** Заголовок чанка с диска ({@link DiskStatus#whole}): только заголовок, в потоке ввода-вывода чанков; ответ — в {@link #scanned}. */
    private void scan(ServerLevel level, long chunk) {
        disk.put(chunk, SCANNING);
        scanning++;
        ConcurrentLinkedQueue<long[]> out = scanned;
        DiskStatus.scan(level, new ChunkPos(chunk)).thenAccept(whole -> out.add(new long[]{chunk, whole ? 1 : 0}));
    }

    /** Копия чанка с руинами (фоновый поток): секции с диска, места плана. */
    private static Built build(DiskShots.Format f, DiskShots.Sections s, @Nullable RuinPlan plan, boolean fires, boolean lights) {
        long chunk = s.pos().toLong();
        if (s.skip() != null) return new Built(chunk, null, false, lights, s.skip());
        if (plan != null && !plan.applyToCopy(s.sections(), fires)) return new Built(chunk, null, false, lights, "план устарел");
        ProtoChunk copy = new ProtoChunk(s.pos(), UpgradeData.EMPTY, s.sections(), new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                LevelHeightAccessor.create(f.minY(), f.height()), f.biomes(), null);
        copy.setPersistedStatus(ChunkStatus.FULL);
        return new Built(chunk, copy, plan != null && plan.changedBlocks() + plan.fireCount() > 0, lights, null);
    }

    /** Поток сервера: лампы — к сети сейчас; изменилось против диска (или квартал сменил свет) — в DH. */
    private void harvest(ServerLevel level, Built b) {
        reading.remove(b.chunk);
        byte asked = again.remove(b.chunk);
        if ((asked & RUINS) != 0) request(level, b.chunk, true);
        if ((asked & LIGHTS) != 0) request(level, b.chunk, false);
        if (b.copy == null) {
            if ("план устарел".equals(b.skip)) stale++;
            else unread++;
            return;
        }
        // загрузили, пока читали: у мира свой путь
        if (inWorld(level, b.chunk)) return;
        // на диске двойников нет: погасить, если квартал тёмный
        boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(b.chunk), ChunkPos.getZ(b.chunk), level.getGameTime());
        int lamps = dark ? ChunkLights.applyToCopy(b.copy.getSections(), true) : 0;
        if (!b.ruined && lamps == 0 && !b.lights) {
            unchanged++;
            return;
        }
        DhUpdates.offer(level, b.copy);
        sent++;
        if (b.ruined) sentRuins++;
    }

    /**
     * Проверки: копия загруженного чанка — его данные сохранения (как на диске), разобранные как чанк не в мире, с
     * руинами плана ({@code fires} — с пожарами) и лампами по сети; null — план к копии не подошёл.
     */
    @Nullable
    public static ProtoChunk testCopy(ServerLevel level, LevelChunk chunk, @Nullable RuinPlan plan, boolean fires) {
        DiskShots.Format f = DiskShots.Format.of(level);
        CompoundTag tag = ChunkSerializer.write(level, chunk);
        Built b = build(f, DiskShots.parseSections(f, chunk.getPos(), tag), plan, fires, false);
        if (b.copy == null) return null;
        if (PowerGrid.get(level).dark(chunk.getPos().x, chunk.getPos().z, level.getGameTime())) ChunkLights.applyToCopy(b.copy.getSections(), true);
        return b.copy;
    }

    /**
     * Чанк в памяти, бывший полностью загруженным (и опущенный ниже у края видимости): его руины и лампы ставит мир
     * ({@link ScarQueue}, блэкаут). Недогенерированный чанк в памяти (кольцо вокруг загруженного мира) — не в мире: его
     * никто не пройдёт, пока он не загрузится.
     */
    private static boolean inWorld(ServerLevel level, long chunk) {
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
     * в очереди, читаются.
     */
    public long[] stats() {
        return new long[] {sent, sentRuins, unchanged, stale, unread, noPlan, partial, queued.size(), reading.size()};
    }

    /** Для проверок: квадраты в мир ({@link FarZone#stats}): ждут, держатся, взято, отпущено, по сроку, брошено, пик загрузки. */
    public long[] zoneStats() {
        return zone.stats();
    }

    /** Для проверок: дождаться чтений, заголовков и квадратов в работе. */
    public boolean busy() {
        return !queued.isEmpty() || !reading.isEmpty() || scanning > 0 || zone.busy();
    }
}
