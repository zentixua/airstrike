package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Очередь чанков на повреждения (DESIGN-nuke §3.1). Чанк ставится в очередь, когда он загружен и в радиусе
 * подрыва, чей номер новее отметки {@code chunk_scar} на чанке. Обрабатывается не раньше, чем до него дошла волна
 * (загруженные позже — сразу), целиком одной единицей работы под общим бюджетом времени: руины ставятся записью
 * плана в секции ({@link RuinPlan}) — по плану, построенному ещё во время полёта МБР ({@link NuclearPrep}), или по плану
 * на месте. После руин ставится отметка; выгрузился до них — отметки нет, при следующей загрузке пройдёт заново.
 * <p>
 * Чанк в очереди, пока он в памяти, а не пока он полностью загружен: у края видимости чанк то и дело опускается
 * ниже полной загрузки и поднимается обратно, не выгружаясь, — и {@code ChunkEvent.Load} при этом больше не
 * приходит (он один на жизнь чанка в памяти). Такой чанк ждёт в очереди, как и чанк без загруженных соседей;
 * снимок подрыва берёт и его. Иначе он выпадал из очереди навсегда — полосы нетронутых чанков в зоне.
 * <p>
 * Чанк на краю загруженного мира (сам загружен, соседи — нет) сам не дождётся соседей, если игрок не подойдёт
 * ближе, — а на краях двух стоянок игрока так и остаётся нетронутый шов. Такой чанк держит тикет с соседями
 * ({@link NuclearTickets#holdForScar}): ваниль грузит их в фоне, чанк проходится, тикет снимается. Соседи,
 * загруженные ради него, сами тикет не берут — иначе загрузка расползлась бы на весь радиус; они пройдутся,
 * когда их загрузит игрок.
 * <p>
 * В момент подрыва загружены тысячи чанков: и их снимок, и постановка в очередь идут уже под бюджетом
 * ({@link #scanLoaded}), в тике подрыва — ничего.
 */
public final class ScarQueue {
    /** Работа по одному чанку: подрывы по порядку номеров. */
    private static final class Job {
        final long chunk;
        final List<Detonation> events = new ArrayList<>();
        int event;
        long due;
        /** Когда до чанка дошла волна текущего подрыва (срок без повторов «ждём соседей») — от него отставание. */
        long wave;
        /** Может держать тикет с соседями: загружен не нашим тикетом. */
        final boolean mayHold;
        /** Держит тикет с соседями. */
        boolean held;
        /** Срок пришёл: в очереди готовых ({@link #readySeen}/{@link #readyUnseen}), а не в {@link #byDue}. */
        boolean ready;

        Job(long chunk, boolean mayHold) {
            this.chunk = chunk;
            this.mayHold = mayHold;
        }
    }

    /** Загруженные при подрыве чанки в его радиусе (снимок — первой единицей работы); {@code next} — первый не поставленный. */
    private static final class Scan {
        final Detonation d;
        /** Чанки с готовыми руинами — ближние к эпицентру первыми: в очередь раньше всех. */
        final long[] first;
        @Nullable
        long[] chunks;
        int next;

        Scan(Detonation d, long[] first) {
            this.d = d;
            this.first = first;
        }
    }

    /** Сколько чанков ставить в очередь за одну единицу работы (постановка — микросекунды). */
    private static final int OFFERS_PER_UNIT = 64;
    /**
     * За сколько тиков до прихода стены к центру чанка его уже можно разрушать: у эпицентра фронт идёт быстро (сотни
     * чанков за тик), и тик раньше под стеной пыли не видно, а работа растягивается на лишний тик; дальше стена идёт
     * 3 блока за тик — тик раньше это 3 блока.
     */
    private static final int EARLY = 1;
    /** Через сколько тиков снова проверить чанк, который (или чьи соседи) сейчас ниже полной загрузки. */
    private static final int NEIGHBOUR_RETRY = 40;

    private final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
    private long lastSlowChunk = Long.MIN_VALUE / 2;
    private final PriorityQueue<Job> byDue = new PriorityQueue<>(Comparator.comparingLong(j -> j.due));
    /**
     * Чанки, чей срок пришёл: сперва те, что видит игрок (их руины — ровно с фронтом, изменения уходят ему в том же
     * тике), потом остальные (их никто не видит — отставание на несколько тиков не заметно; они всё равно доходят
     * до руин, на диск и в LOD Distant Horizons).
     */
    private record Log(long at, net.minecraft.world.level.block.state.BlockState state) {}

    private final ArrayDeque<Job> readySeen = new ArrayDeque<>(), readyUnseen = new ArrayDeque<>();
    /** Стволы, упавшие в чанк, чьи руины ещё впереди: кладутся после его руин (иначе они старили бы его план). */
    private final Long2ObjectOpenHashMap<List<Log>> logs = new Long2ObjectOpenHashMap<>();
    private final Map<Integer, ColumnScar.Budget> budgets = new HashMap<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    /** Руины, построенные во время полёта МБР ({@link NuclearPrep}): номер подрыва → чанк → план. */
    private final Map<Integer, Long2ObjectOpenHashMap<RuinPlan>> prepared = new HashMap<>();
    /** Для проверок и статуса: чанков с руинами по готовому плану и построенных в момент прихода волны. */
    private int appliedPrepared, appliedFresh, stalePlans;
    /**
     * По подрыву: [0] руин по готовому плану, [1] устаревших планов, [2] наибольшее отставание от прихода волны
     * у чанков, которые видит игрок (тики), [3] время подмен по готовому плану всего и [4] самой долгой (нс),
     * [5] наибольшее отставание у остальных, [6] руин по плану на месте.
     */
    private final Map<Integer, long[]> preparedStats = new HashMap<>();

    public int size() {
        return jobs.size();
    }

    /**
     * Подрыв: загруженные сейчас чанки в его радиусе поставит в очередь {@link #work} — и снимок их координат, и сами
     * чанки (и их отметки) читаются уже под бюджетом. В тике подрыва — ничего.
     */
    public void scanLoaded(Detonation d) {
        scans.add(new Scan(d, new long[0]));
    }

    /**
     * Подрыв, для которого руины построены заранее: их чанки (ближние первыми) встают в очередь раньше остальных
     * загруженных — к приходу волны они уже там.
     */
    public void scanLoaded(Detonation d, Long2ObjectOpenHashMap<RuinPlan> plans, long[] order) {
        prepared.put(d.id(), plans);
        scans.add(new Scan(d, order));
    }

    /** Остались ли неиспользованные руины подрыва (их чанки держит {@link NuclearPrep}). */
    public boolean hasPrepared(int detonation) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(detonation);
        return plans != null && !plans.isEmpty();
    }

    /** Руины подрыва больше не нужны (их чанки отпущены); сводка — в лог. */
    public void dropPrepared(int detonation) {
        Long2ObjectOpenHashMap<RuinPlan> left = prepared.remove(detonation);
        long[] st = preparedStats.remove(detonation);
        if (st == null) st = new long[9];
        Airstrike.LOG.info("Руины подрыва №{}: по готовому плану {}, план устарел {}, на месте {}, не дождались {}; отставание от волны до {} тиков "
                        + "(у игроков до {}, у остальных до {}); подмена чанка в среднем {} мкс, самая долгая {} мс",
                detonation, st[0], st[1], st[6], left == null ? 0 : left.size(), Math.max(st[2], st[5]), st[2], st[5],
                st[0] == 0 ? 0 : st[3] / st[0] / 1000, String.format(java.util.Locale.ROOT, "%.1f", st[4] / 1e6));
        if (st[6] > 0) {
            Airstrike.LOG.info("Руины подрыва №{}: план и подмена на месте — в среднем {} мс, самые долгие {} мс", detonation, ms(st[7] / st[6]), ms(st[8]));
        }
        if (left != null && !left.isEmpty()) {
            // почему не дождались: чанк так и стоял в очереди (соседи не загрузились) или в очередь не попал (выгружен)
            StringBuilder some = new StringBuilder();
            int queued = 0, shown = 0;
            for (long c : left.keySet()) {
                boolean inQueue = jobs.containsKey(c);
                if (inQueue) queued++;
                if (shown++ < 5) some.append(shown > 1 ? ", " : "").append(new ChunkPos(c)).append(inQueue ? " в очереди" : "");
            }
            Airstrike.LOG.info("Руины подрыва №{}: не дождались {} чанков, из них в очереди {}: {}", detonation, left.size(), queued, some);
        }
        long[] ph = RuinPlan.PHASES;
        Airstrike.LOG.info("Руины: подмены по частям (всего с запуска, мс) — проверка {}, секции {}, карты высот {}, свет и пакеты {}, блок-сущности {}",
                ph[0] / 1_000_000, ph[1] / 1_000_000, ph[2] / 1_000_000, ph[3] / 1_000_000, ph[4] / 1_000_000);
        if (RuinPlan.slowestWorldNanos > 0) {
            Airstrike.LOG.info("Руины: дольше всего через мир — чанк {}: {} мест, {} мс (блок-сущности, POI, LOD Distant Horizons); "
                            + "самый долгий блок {} — {} мс, LOD Distant Horizons {} мс",
                    new ChunkPos(RuinPlan.slowestWorldChunk), RuinPlan.slowestWorldCells, ms(RuinPlan.slowestWorldNanos),
                    RuinPlan.slowestWorldBlock == null ? "—" : BuiltInRegistries.BLOCK.getKey(RuinPlan.slowestWorldBlock.getBlock()),
                    ms(RuinPlan.slowestWorldBlockNanos), ms(RuinPlan.slowestWorldDhNanos));
            RuinPlan.slowestWorldNanos = 0;
            RuinPlan.slowestWorldBlock = null;
        }
        // стволы, отложенные до руин чанка, который так и не встал в очередь (выгрузился с готовым планом)
        if (left != null) {
            for (long c : left.keySet()) if (!jobs.containsKey(c)) logs.remove(c);
        }
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6);
    }

    /** Чанк ждёт в очереди повреждений. */
    public boolean queued(long chunk) {
        return jobs.containsKey(chunk);
    }

    /** Не поставленные ещё готовые руины подрыва: есть ли план у чанка. */
    public boolean pendingPlan(int detonation, long chunk) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(detonation);
        return plans != null && plans.containsKey(chunk);
    }

    /** По готовому плану, построенных на месте, устаревших планов (проверки, статус). */
    public int[] ruinStats() {
        return new int[]{appliedPrepared, appliedFresh, stalePlans};
    }

    /**
     * Координаты чанков в памяти в радиусе подрыва. Чанк, который ещё ни разу не был полностью загружен, поставит
     * {@code onChunkLoad}, когда загрузится.
     */
    private static long[] loadedInRange(ServerLevel level, Detonation d, long[] except) {
        double radius = d.radiusMax();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet skip = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(except);
        LongArrayList in = new LongArrayList();
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
            ChunkPos p = holder.getPos();
            if (nearest(p, d) <= radius && !skip.contains(p.toLong())) in.add(p.toLong());
        }
        return in.toLongArray();
    }

    /**
     * Чанк в памяти: полностью загруженный или опущенный ниже (у края видимости), но не выгруженный; null — его нет
     * в памяти или он ещё не бывал полностью загружен ({@code onChunkLoad} поставит его сам).
     */
    @Nullable
    private static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /** Поставить чанк в очередь по подрыву (если чанк в радиусе и подрыв новее отметки на чанке). */
    public void offer(LevelChunk chunk, Detonation d) {
        if (!inRange(chunk.getPos(), d)) return;
        // без отметки — ни одного подрыва ещё не было; getData повесил бы отметку 0 на каждый чанк в радиусе
        int applied = chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0);
        if (d.id() <= applied) return;
        long key = chunk.getPos().toLong();
        Job job = jobs.get(key);
        if (job != null && job.events.stream().anyMatch(e -> e.id() == d.id())) return;
        if (job == null) {
            job = new Job(key, !nearHeld(chunk.getPos()));
            jobs.put(key, job);
        } else if (!job.ready) {
            byDue.remove(job);
        }
        if (job.events.stream().noneMatch(e -> e.id() == d.id())) {
            job.events.add(d);
            job.events.sort(Comparator.comparingInt(Detonation::id));
        }
        // в очереди готовых — срок уже пришёл; новый подрыв он возьмёт следующим
        if (job.ready) return;
        job.due = job.wave = due(job.events.get(job.event), chunk.getPos());
        byDue.add(job);
    }

    /** Рядом (или сам) чанк, который держит тикет с соседями, — значит, этот чанк, скорее всего, загружен им. */
    private boolean nearHeld(ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Job j = jobs.get(ChunkPos.asLong(pos.x + dx, pos.z + dz));
                if (j != null && j.held) return true;
            }
        }
        return false;
    }

    private static void release(ServerLevel level, Job job) {
        if (!job.held) return;
        NuclearTickets.holdForScar(level, new ChunkPos(job.chunk), false);
        job.held = false;
    }

    /** Выгружен: работа снимается (чанк с тикетом не выгружается — тикет снят раньше, в {@link #clear}). */
    public void drop(ChunkPos pos) {
        Job job = jobs.remove(pos.toLong());
        // из очереди готовых снимется сам: работа берётся, только если она ещё в jobs
        if (job != null && !job.ready) byDue.remove(job);
        logs.remove(pos.toLong());
    }

    /** Счётчики пожаров только для подрывов, которые ещё помнятся. */
    public void retainBudgets(java.util.Set<Integer> detonations) {
        budgets.keySet().retainAll(detonations);
        // сводка — только у подрывов с готовыми руинами (её пишет dropPrepared), у остальных забывается
        preparedStats.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
    }

    public void clear(ServerLevel level) {
        jobs.values().forEach(j -> release(level, j));
        scans.clear();
        prepared.clear();
        preparedStats.clear();
        jobs.clear();
        byDue.clear();
        readySeen.clear();
        readyUnseen.clear();
        logs.clear();
        budgets.clear();
    }

    private static boolean inRange(ChunkPos p, Detonation d) {
        return nearest(p, d) <= d.radiusMax();
    }

    /** Наклонная дальность от точки подрыва до ближайшего места чанка на уровне земли. */
    private static double nearest(ChunkPos p, Detonation d) {
        double x = Math.max(p.getMinBlockX(), Math.min(d.burst().x, p.getMaxBlockX() + 1));
        double z = Math.max(p.getMinBlockZ(), Math.min(d.burst().z, p.getMaxBlockZ() + 1));
        double dx = x - d.burst().x, dz = z - d.burst().z, dy = d.burst().y - d.groundY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Когда до чанка доходит стена: по его центру, не по ближнему краю — фронт идёт 3 блока за тик, и по краю дальняя
     * сторона чанка менялась бы секунду до стены.
     */
    private static long due(Detonation d, ChunkPos p) {
        double dx = p.getMiddleBlockX() + 0.5 - d.burst().x, dz = p.getMiddleBlockZ() + 0.5 - d.burst().z, dy = d.burst().y - d.groundY();
        return d.gameTime() + (long) d.arrivalTicks(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /**
     * Обработать, что успеем за бюджет. Чанк, упавший с ошибкой, снимается с очереди один (со своим тикетом), без
     * отметки: при следующей загрузке он пройдёт заново.
     *
     * @param clock бюджет тика: за столбец берёмся, только если он успеет
     */
    public void work(ServerLevel level, long now, WorkClock clock) {
        // сначала — в очередь чанки, загруженные при подрывах. Порядок здесь не важен: очередь сама идёт по приходу
        // волны, а на постановку тысяч чанков уходит несколько тиков — волна за это время проходит пару чанков
        while (!scans.isEmpty() && clock.canStart()) {
            Scan scan = scans.peek();
            long c0 = clock.begin();
            try {
                if (scan.chunks == null) {
                    long[] loaded = loadedInRange(level, scan.d, scan.first);
                    scan.chunks = new long[scan.first.length + loaded.length];
                    System.arraycopy(scan.first, 0, scan.chunks, 0, scan.first.length);
                    System.arraycopy(loaded, 0, scan.chunks, scan.first.length, loaded.length);
                } else {
                    // выгрузился после подрыва — пропускаем; загрузится снова — поставит onChunkLoad
                    int end = Math.min(scan.chunks.length, scan.next + OFFERS_PER_UNIT);
                    while (scan.next < end) {
                        LevelChunk chunk = inMemory(level, scan.chunks[scan.next++]);
                        if (chunk != null) offer(chunk, scan.d);
                    }
                }
            } catch (RuntimeException e) {
                // чанки, загруженные потом, поставит onChunkLoad
                Airstrike.LOG.error("Постановка чанков подрыва №{} в очередь упала с ошибкой; снята", scan.d.id(), e);
                scans.poll();
                continue;
            } finally {
                clock.end(c0);
            }
            if (scan.next >= scan.chunks.length) scans.poll();
        }
        var chunkMap = level.getChunkSource().chunkMap;
        while (!byDue.isEmpty() && byDue.peek().due <= now + EARLY) {
            Job job = byDue.poll();
            job.ready = true;
            (chunkMap.getPlayers(new ChunkPos(job.chunk), false).isEmpty() ? readyUnseen : readySeen).add(job);
        }
        while (clock.canStart()) {
            Job job = !readySeen.isEmpty() ? readySeen.poll() : readyUnseen.poll();
            if (job == null) return;
            job.ready = false;
            if (jobs.get(job.chunk) != job) continue; // выгружен или снят
            try {
                work(level, job, now, clock);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Повреждения чанка {} упали с ошибкой; чанк снят с очереди", new ChunkPos(job.chunk), e);
                jobs.remove(job.chunk);
                logs.remove(job.chunk);
                release(level, job);
            }
        }
    }

    /** Руины в чанке: по готовому плану, если он ещё верен, иначе — план по чанку как есть. */
    private void ruin(ServerLevel level, Detonation d, LevelChunk chunk, ColumnScar.Budget budget, long lag, boolean seen) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(d.id());
        RuinPlan plan = plans != null ? plans.remove(chunk.getPos().toLong()) : null;
        long[] st = preparedStats.computeIfAbsent(d.id(), k -> new long[9]);
        if (seen) st[2] = Math.max(st[2], lag);
        else st[5] = Math.max(st[5], lag);
        if (plan != null) {
            long t0 = System.nanoTime();
            if (plan.apply(level, chunk, budget)) {
                long took = System.nanoTime() - t0;
                appliedPrepared++;
                st[0]++;
                st[3] += took;
                st[4] = Math.max(st[4], took);
                deferLogs(level, d, plan);
                return;
            }
            stalePlans++;
            st[1]++;
        }
        long t0 = System.nanoTime();
        plan = RuinPlanner.plan(level, d, chunk);
        plan.apply(level, chunk, budget);
        long took = System.nanoTime() - t0;
        appliedFresh++;
        st[6]++;
        st[7] += took;
        st[8] = Math.max(st[8], took);
        deferLogs(level, d, plan);
    }

    /** Стволы, упавшие в соседние чанки: сразу — если руины соседа уже стоят или его нет в очереди, иначе — после них. */
    private void deferLogs(ServerLevel level, Detonation d, RuinPlan plan) {
        for (int k = 0; k < plan.outsideCount(); k++) {
            long at = plan.outsidePos(k);
            long chunk = ChunkPos.asLong(BlockPos.getX(at) >> 4, BlockPos.getZ(at) >> 4);
            Job job = jobs.get(chunk);
            // сосед ещё впереди: в очереди с этим подрывом или с готовым планом, который очередь ещё не взяла
            if (job != null ? job.events.stream().anyMatch(e -> e.id() == d.id()) && job.events.indexOf(d) >= job.event
                    : pendingPlan(d.id(), chunk)) {
                logs.computeIfAbsent(chunk, c -> new ArrayList<>()).add(new Log(at, plan.outsideState(k)));
            } else {
                RuinPlan.placeLog(level, at, plan.outsideState(k));
            }
        }
    }

    /** Первый в очереди чанк (его срок пришёл): руины одной единицей работы. */
    private void work(ServerLevel level, Job job, long now, WorkClock clock) {
        if (inMemory(level, job.chunk) == null) {
            // выгружен (onChunkUnload уже убрал бы работу) — загрузится снова, поставит onChunkLoad
            jobs.remove(job.chunk);
            logs.remove(job.chunk);
            return;
        }
        ChunkPos pos = new ChunkPos(job.chunk);
        if (!NuclearTickets.neighbourhoodLoaded(level, pos)) {
            // край загруженного мира (сам чанк или соседи ниже полной загрузки): разрушим, когда загрузятся;
            // полностью загруженный край сам просит соседей
            if (!job.held && job.mayHold && level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                NuclearTickets.holdForScar(level, pos, true);
                job.held = true;
            }
            job.due = now + NEIGHBOUR_RETRY;
            byDue.add(job);
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        Detonation d = job.events.get(job.event);
        // забытый подрыв (чанк впервые загрузился спустя дни) выжигает, но не поджигает: пожары давно бы догорели
        ColumnScar.Budget budget = budgets.computeIfAbsent(d.id(), k -> new ColumnScar.Budget(!NuclearEvents.get(level).isPast(k)));
        long c0 = clock.begin();
        try {
            ruin(level, d, chunk, budget, Math.max(0, now - job.wave), !level.getChunkSource().chunkMap.getPlayers(pos, false).isEmpty());
        } finally {
            long took = clock.end(c0);
            // один чанк дольше 50 мс — это чужая задержка (загрузка чанка, сборщик мусора): в лог, не чаще раза в 5 с
            if (took > 50_000_000L && now - lastSlowChunk >= 100) {
                lastSlowChunk = now;
                Airstrike.LOG.warn("Медленный чанк руин {}: {} мс", pos, took / 1_000_000);
            }
        }
        chunk.setData(ModAttachments.CHUNK_SCAR, d.id());
        chunk.setUnsaved(true);
        List<Log> fallen = logs.remove(job.chunk);
        if (fallen != null) fallen.forEach(l -> RuinPlan.placeLog(level, l.at(), l.state()));
        job.event++;
        if (job.event >= job.events.size()) {
            release(level, job);
            jobs.remove(job.chunk);
        } else {
            job.due = job.wave = due(job.events.get(job.event), chunk.getPos());
            byDue.add(job);
        }
    }
}
