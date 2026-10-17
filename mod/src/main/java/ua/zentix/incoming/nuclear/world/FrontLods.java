package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LOD Distant Horizons тяжёлой зоны — вместе с фронтом, как чанки у игрока. Путь {@link FarLods} просит копию чанка не
 * в мире только после прихода волны: чтение с диска, копия, очередь {@link DhUpdates} (20 за тик) и своя задержка DH
 * ({@link DhUpdates#LEAD} и больше) — LOD у края зоны 2 кт менялся через десятки секунд после стены. Поэтому, пока
 * летит МБР, копия каждого чанка зоны не в мире, чей план руин с диска готов ({@link NuclearPrep}), собирается заранее
 * тем же путём (секции с диска и места плана — {@link FarLods#build}; горящие лампы сети — местами,
 * {@link ChunkLights#litLamps}) и держится, а после подрыва уходит в DH ({@link DhUpdates#sendHeld}) за
 * {@link DhUpdates#LEAD} тиков до прихода фронта к середине своей секции DH, не раньше подрыва: чанки секции — вместе
 * ({@link FrontSchedule}), лампы — по сети к приходу фронта. Темп — окно DH ({@link DhUpdates#heldRoom}: целая секция
 * или ждать) и время отдачи за тик ({@link #SEND_NANOS}), а не {@code DhUpdates.PER_TICK}.
 * <p>
 * Память — в месте руин заранее ({@code NuclearPrep.PLANS_CAP}) после планов: копии собираются, когда планы с диска всей
 * зоны отданы и готовы (или больше не строятся), из того, что оставили планы, снимки и чтения, ближние к эпицентру
 * первыми; не помещаются — дальние уходят, их LOD — путём {@link FarLods} после волны. Копия чанка, который тем временем
 * загрузился, уходит: руины и LOD ему ставит мир. Запросы {@link FarLods} руин чанка с копией и света его квартала
 * берёт копия ({@link #claim}). Подрыв без полёта ({@code nuke now}) и чанки за тяжёлой зоной — путём {@link FarLods}.
 */
final class FrontLods {
    /** Чтений с диска в работе сразу и новых за тик — как у {@link FarLods}: поток ввода-вывода общий с загрузкой мира. */
    static final int READS = 16, PER_TICK = 8;
    /** Оценка памяти копии в чтении, байт. */
    static final long READ_BYTES = 96L << 10;
    /**
     * Время отдачи за тик, не больше (первая секция — всегда): вызов DH — микросекунды (копия встаёт в его очередь по
     * ссылке), лампы — по местам; у 2 кт почти вся зона уходит в тике подрыва.
     */
    static final long SEND_NANOS = 2_000_000;

    /** Ответ {@link #claim}: запрос не про копию; копия его берёт; копию — заново после фронта (запросом руин). */
    static final int NONE = 0, OURS = 1, AFTER_FRONT = 2;

    /** Копия читается; держится; ушла в DH; показывать нечего (план ничего не меняет, ламп нет). */
    private static final byte BUILDING = 1, READY = 2, SENT = 3, NOTHING = 4;
    private static final int[] NO_LAMPS = new int[0];

    private static final class Copy {
        /** Место в очереди копий ({@link #candidates}): уходят дальние — с конца. */
        final int index;
        byte state = BUILDING;
        @Nullable
        ProtoChunk chunk;
        int[] lamps = NO_LAMPS;
        /**
         * В копии руины; квартал сменил свет ({@link FarLods}: LOD уходит и без изменений против диска); в ней были
         * горящие лампы сети; ушла с погашенными.
         */
        boolean ruined, lights, lit, dark;
        long bytes;
        /** Приход фронта к середине чанка ({@link ScarQueue#due}); до подрыва — 0. */
        long due;

        Copy(int index) {
            this.index = index;
        }
    }

    /** Готовая копия из фонового потока: copy — null, если не собралась (нет на диске, план устарел). */
    private record Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, int[] lamps, long bytes) {}

    private final int strike;
    /** Чанки зоны с готовым планом с диска, по порядку планов (волны); курсор — докуда отданы в чтение. */
    private final LongArrayList candidates = new LongArrayList();
    private int next;
    private final Long2ObjectOpenHashMap<Copy> copies = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    private final FrontSchedule schedule = new FrontSchedule();
    private int reading;
    /** Байт в держанных копиях. */
    private long readyBytes;
    @Nullable
    private DiskShots.Format format;
    @Nullable
    private Detonation d;
    /** Для строки в лог и проверок: собрано, ушло, из них позже {@code due − LEAD}; отдача минус приход фронта — сумма, меньшая, большая. */
    private long made, sent, late, offsetSum, offsetMin = Long.MAX_VALUE, offsetMax = Long.MIN_VALUE;
    /** Не ушли: чанк загрузился, не хватило места, фронт пришёл раньше копии, не собралась, показывать нечего. */
    private long loaded, evicted, abandoned, failed, unchanged;
    /** Строка о передаче к фронту написана (копии были); счёт DH ({@link DhUpdates#takeHeldStats}) идёт; итог написан. */
    private boolean announced, counting, reported;

    FrontLods(int strike) {
        this.strike = strike;
    }

    /** План чанка зоны с диска готов: чанк — в очередь копий (по порядку планов — ближние первыми). Поток сервера. */
    void offer(long chunk) {
        candidates.add(chunk);
    }

    /** Байт под копиями: держанные и чтения в работе. */
    long bytes() {
        return readyBytes + (long) reading * READ_BYTES;
    }

    /**
     * Тик подготовки: готовые копии — держать; сверх места — дальние уходят; новые — в чтение (если {@code build});
     * после подрыва — отдача к фронту.
     *
     * @param plans планы подготовки (копии — по ним)
     * @param room  байт под копии: место руин заранее без планов, снимков и чтений подготовки
     * @param build планы с диска всей зоны готовы (или больше не строятся): копии берут, что осталось
     */
    void tick(ServerLevel level, Long2ObjectOpenHashMap<RuinPlan> plans, long room, boolean build, long now) {
        harvest(level);
        if (!DhUpdates.enabled(level)) {
            // DH нет (или ушёл): копии некому отдать
            clear(level);
            return;
        }
        fit(room);
        if (build) start(level, plans, room, now);
        if (d != null) release(level, now);
    }

    /** Готовые копии из фоновых потоков: держать до подрыва, после — в очередь отдачи. */
    private void harvest(ServerLevel level) {
        for (Built b; (b = built.poll()) != null; ) {
            reading--;
            Copy c = copies.get(b.chunk);
            // снята, пока читалась (загружен, фронт пришёл раньше, отбой)
            if (c == null || c.state != BUILDING) continue;
            if (b.copy == null) {
                copies.remove(b.chunk);
                failed++;
                // свет квартала, который взяла копия, — снова путём FarLods
                if (c.lights) FarLods.request(level, b.chunk, false);
                continue;
            }
            made++;
            if (!b.ruined && b.lamps.length == 0) {
                c.state = NOTHING;
                unchanged++;
                continue;
            }
            c.state = READY;
            c.chunk = b.copy;
            c.lamps = b.lamps;
            c.ruined = b.ruined;
            c.bytes = b.bytes;
            readyBytes += b.bytes;
            if (d != null) schedule.add(b.chunk, sendAt(b.chunk));
        }
    }

    /** Копии сверх места: держанные уходят с конца очереди — дальние от эпицентра (чтения в работе доходят). */
    private void fit(long room) {
        for (int i = candidates.size() - 1; i >= 0 && bytes() > room && readyBytes > 0; i--) {
            long chunk = candidates.getLong(i);
            Copy c = copies.get(chunk);
            if (c == null || c.state != READY || c.index != i) continue;
            drop(chunk, c);
            evicted++;
        }
    }

    /** Новые копии по порядку очереди, пока есть место, чтения и пул фоновых потоков. */
    private void start(ServerLevel level, Long2ObjectOpenHashMap<RuinPlan> plans, long room, long now) {
        boolean fires = IncomingConfig.SERVER.nukeFires.get();
        for (int started = 0; next < candidates.size() && started < PER_TICK && reading < READS && bytes() + READ_BYTES <= room; ) {
            // после подрыва — не больше половины пула: руины чанков у игроков важнее
            if (!RuinWorkers.admit(RuinWorkers.Priority.FAR) || d != null && RuinWorkers.inFlight() >= RuinWorkers.capacity() / 2) return;
            int index = next++;
            long chunk = candidates.getLong(index);
            RuinPlan plan = plans.get(chunk);
            // чанк в мире (руины и LOD ему ставит мир), далеко от игроков (DH им не шлёт), фронт придёт раньше копии
            if (plan == null || copies.containsKey(chunk) || FarLods.inWorld(level, chunk) || !FarLods.nearPlayer(level, chunk)
                    || d != null && ScarQueue.due(d, new ChunkPos(chunk)) - now <= DhUpdates.LEAD) continue;
            Copy c = new Copy(index);
            if (d != null) c.due = ScarQueue.due(d, new ChunkPos(chunk));
            copies.put(chunk, c);
            reading++;
            started++;
            if (format == null) format = DiskShots.Format.of(level);
            DiskShots.Format f = format;
            DiskShots.readSections(f, new ChunkPos(chunk)).thenApply(s -> build(f, s, plan, fires))
                    .exceptionally(e -> new Built(chunk, null, false, NO_LAMPS, 0)).thenAccept(built::add);
        }
    }

    /** Копия чанка с руинами по плану и местами горящих ламп (фоновый поток). */
    private static Built build(DiskShots.Format f, DiskShots.Sections s, RuinPlan plan, boolean fires) {
        FarLods.Built b = FarLods.build(f, s, plan, fires, false, false);
        if (b.copy() == null) return new Built(b.chunk(), null, false, NO_LAMPS, 0);
        LevelChunkSection[] sections = b.copy().getSections();
        int[] lamps = ChunkLights.litLamps(sections);
        return new Built(b.chunk(), b.copy(), b.ruined(), lamps, bytes(sections) + 4L * lamps.length);
    }

    /** Память копии, байт: палитры секций (оценка как у {@code ChunkShot}) и сам чанк-копия. */
    private static long bytes(LevelChunkSection[] sections) {
        long n = 1024;
        for (LevelChunkSection s : sections) {
            if (s != null) n += 64 + s.getStates().getSerializedSize() + s.getBiomes().getSerializedSize();
        }
        return n;
    }

    /**
     * Подрыв, к которому готовились: копиям — срок прихода фронта, держанные — в очередь отдачи (ближние первыми: при
     * одном сроке у секций, как у 2 кт в тике подрыва, окно и время тика достаются им).
     */
    void handOff(Detonation d) {
        this.d = d;
        int ready = 0, building = 0;
        for (int i = 0; i < next; i++) {
            long chunk = candidates.getLong(i);
            Copy c = copies.get(chunk);
            if (c == null || c.index != i) continue;
            c.due = ScarQueue.due(d, new ChunkPos(chunk));
            if (c.state == READY) {
                schedule.add(chunk, sendAt(chunk));
                ready++;
            } else if (c.state == BUILDING) {
                building++;
            }
        }
        announced = next > 0 || !candidates.isEmpty();
        if (!announced) return;
        Incoming.LOG.info("Detonation #{}: heavy zone LODs held for the wave front — {} ready ({} MB), {} reading, {} more queued; sent {} ticks before "
                + "the front reaches their Distant Horizons section", d.id(), ready, readyBytes >> 20, building, candidates.size() - next, DhUpdates.LEAD);
    }

    /** Тик отдачи секции: {@link DhUpdates#LEAD} до прихода фронта к её середине, не раньше подрыва. */
    private long sendAt(long chunk) {
        long s = FrontSchedule.section(chunk);
        double dx = FrontSchedule.middle((int) s) - d.burst().x, dz = FrontSchedule.middle((int) (s >>> 32)) - d.burst().z, dy = d.burst().y - d.groundY();
        long due = d.gameTime() + (long) d.arrivalTicks(Math.sqrt(dx * dx + dy * dy + dz * dz));
        return Math.max(d.gameTime(), due - DhUpdates.LEAD);
    }

    /** Секции, чей срок настал, — в DH целиком, пока есть окно DH и время тика (первая — всегда, если окно есть). */
    private void release(ServerLevel level, long now) {
        long until = System.nanoTime() + SEND_NANOS;
        boolean first = true;
        for (LongArrayList section; (section = schedule.head(now)) != null; first = false) {
            if (!first && System.nanoTime() >= until || section.size() > DhUpdates.heldRoom(level)) return;
            long[] chunks = section.toLongArray();
            schedule.pop();
            for (long chunk : chunks) send(level, chunk, now);
        }
    }

    private void send(ServerLevel level, long chunk, long now) {
        Copy c = copies.get(chunk);
        if (c == null || c.state != READY) return;
        if (FarLods.inWorld(level, chunk)) {
            drop(chunk, c);
            loaded++;
            return;
        }
        // на диске двойников нет: лампы — к сети на приход фронта (каскад блэкаута ядерки идёт быстрее волны)
        boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), Math.max(now, c.due));
        int lamps = dark ? ChunkLights.darken(c.chunk.getSections(), c.lamps) : 0;
        if (!c.ruined && lamps == 0 && !c.lights) {
            readyBytes -= c.bytes;
            c.chunk = null;
            c.state = NOTHING;
            unchanged++;
            return;
        }
        // счёт DH — с первой копии этого подрыва (подтверждения прошлых подрывов мира — не в счёт)
        if (!counting) {
            DhUpdates.takeHeldStats(level);
            counting = true;
        }
        // тот же чанк только что ушёл в DH другим путём: DH пропустил бы копию — следующим тиком
        if (!DhUpdates.sendHeld(level, c.chunk)) {
            schedule.add(chunk, now + 1);
            return;
        }
        readyBytes -= c.bytes;
        c.chunk = null;
        c.lit = c.lamps.length > 0;
        c.lamps = NO_LAMPS;
        c.state = SENT;
        c.dark = dark;
        sent++;
        long offset = now - c.due;
        if (offset > -DhUpdates.LEAD) late++;
        offsetSum += offset;
        offsetMin = Math.min(offsetMin, offset);
        offsetMax = Math.max(offsetMax, offset);
    }

    private void drop(long chunk, Copy c) {
        copies.remove(chunk);
        readyBytes -= c.bytes;
        schedule.remove(chunk);
    }

    /**
     * Запрос {@link FarLods} к чанку с копией: руины (волна дошла) — копии, если она уже собрана (ещё читается — копия
     * уходит, путь {@link FarLods}); свет квартала — копии, пока она держится (лампы ставятся к приходу фронта), а у
     * ушедшей — если ламп в ней не было или тьма к фронту (или сейчас, если он прошёл) та же, что у неё: иначе до
     * фронта — копия заново запросом руин ({@link #AFTER_FRONT}: он ждёт плана, то есть фронта), после фронта — путь
     * {@link FarLods} (каскад блэкаута дошёл до квартала позже волны).
     */
    int claim(ServerLevel level, long chunk, boolean lights, long now) {
        Copy c = copies.get(chunk);
        if (c == null) return NONE;
        switch (c.state) {
            case BUILDING -> {
                if (!lights) {
                    // фронт пришёл раньше копии
                    copies.remove(chunk);
                    abandoned++;
                    return NONE;
                }
                c.lights = true;
                return OURS;
            }
            case READY -> {
                if (lights) c.lights = true;
                return OURS;
            }
            case SENT -> {
                if (!lights || !c.lit) return OURS;
                boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), Math.max(now, c.due));
                if (dark == c.dark) return OURS;
                return now < c.due ? AFTER_FRONT : NONE;
            }
            default -> {
                return OURS;
            }
        }
    }

    /** Чанк загрузился: руины и LOD ему ставит мир. */
    void loaded(long chunk) {
        Copy c = copies.get(chunk);
        if (c == null) return;
        if (c.state == READY) drop(chunk, c);
        else copies.remove(chunk);
        if (c.state == READY || c.state == BUILDING) loaded++;
    }

    /**
     * Отбой, подрыв не тот, остановка по куче, конец подготовки: копии не нужны (чтения в работе доходят и выбрасываются).
     * После подрыва, если копии были, — строка в лог (один раз).
     */
    void clear(ServerLevel level) {
        if (d != null && !reported && (announced || next > 0)) report(level);
        copies.clear();
        schedule.clear();
        candidates.clear();
        next = 0;
        readyBytes = 0;
    }

    private void report(ServerLevel level) {
        reported = true;
        // подтверждения общие для мира: два подрыва в нём за несколько секунд делят счёт между своими итогами
        long[] dh = counting ? DhUpdates.takeHeldStats(level) : new long[6];
        Incoming.LOG.info("Detonation #{} (strike #{}): heavy zone LODs at the wave front — built {}, sent {} ({} less than {} ticks before the front; "
                        + "send minus front arrival min {} avg {} max {} ticks); not sent: loaded {}, over memory {}, front came first {}, unread or stale plan {}, "
                        + "nothing to show {}; Distant Horizons saved {} of {} held after avg {} ms (max {}), unconfirmed {}, still waiting {}",
                d.id(), strike, made, sent, late, DhUpdates.LEAD, sent == 0 ? 0 : offsetMin, sent == 0 ? 0 : offsetSum / sent, sent == 0 ? 0 : offsetMax,
                loaded, evicted, abandoned, failed, unchanged, dh[1], dh[0], dh[3], dh[4], dh[2], dh[5]);
    }

    /**
     * Для проверок: собрано, ушло, позже {@code due − LEAD}, отдача минус приход фронта (меньшая, большая), загружены,
     * вытеснены, фронт раньше, не собрались, показывать нечего, ждут отдачи, байт, читаются, ждут чтения.
     */
    long[] stats() {
        return new long[] {made, sent, late, sent == 0 ? 0 : offsetMin, sent == 0 ? 0 : offsetMax, loaded, evicted, abandoned, failed, unchanged,
                schedule.size(), bytes(), reading, candidates.size() - next};
    }
}
