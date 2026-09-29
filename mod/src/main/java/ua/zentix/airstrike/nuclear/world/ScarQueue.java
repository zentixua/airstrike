package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.nuclear.Detonation;
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
 * (загруженные позже — сразу), столбец за столбцом под общим бюджетом времени; после всех столбцов отметка
 * ставится. Выгрузился посреди обработки — отметки нет, при следующей загрузке пройдёт заново (идемпотентно).
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
    /** Работа по одному чанку: подрывы по порядку номеров, текущий столбец. */
    private static final class Job {
        final long chunk;
        final List<Detonation> events = new ArrayList<>();
        int event;
        int column;
        long due;
        /** Может держать тикет с соседями: загружен не нашим тикетом. */
        final boolean mayHold;
        /** Держит тикет с соседями. */
        boolean held;

        Job(long chunk, boolean mayHold) {
            this.chunk = chunk;
            this.mayHold = mayHold;
        }
    }

    /** Загруженные при подрыве чанки в его радиусе (снимок — первой единицей работы); {@code next} — первый не поставленный. */
    private static final class Scan {
        final Detonation d;
        @Nullable
        long[] chunks;
        int next;

        Scan(Detonation d) {
            this.d = d;
        }
    }

    /** Через сколько тиков снова проверить чанк, который (или чьи соседи) сейчас ниже полной загрузки. */
    private static final int NEIGHBOUR_RETRY = 40;

    private final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
    private long lastSlowColumn = Long.MIN_VALUE / 2;
    private final PriorityQueue<Job> byDue = new PriorityQueue<>(Comparator.comparingLong(j -> j.due));
    private final Map<Integer, ColumnScar.Budget> budgets = new HashMap<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();

    public int size() {
        return jobs.size();
    }

    /**
     * Подрыв: загруженные сейчас чанки в его радиусе поставит в очередь {@link #work} — и снимок их координат, и сами
     * чанки (и их отметки) читаются уже под бюджетом. В тике подрыва — ничего.
     */
    public void scanLoaded(Detonation d) {
        scans.add(new Scan(d));
    }

    /**
     * Координаты чанков в памяти в радиусе подрыва. Чанк, который ещё ни разу не был полностью загружен, поставит
     * {@code onChunkLoad}, когда загрузится.
     */
    private static long[] loadedInRange(ServerLevel level, Detonation d) {
        double radius = d.radiusMax();
        LongArrayList in = new LongArrayList();
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
            ChunkPos p = holder.getPos();
            if (nearest(p, d) <= radius) in.add(p.toLong());
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
        if (job == null) {
            job = new Job(key, !nearHeld(chunk.getPos()));
            jobs.put(key, job);
        } else {
            byDue.remove(job);
        }
        if (job.events.stream().noneMatch(e -> e.id() == d.id())) {
            job.events.add(d);
            job.events.sort(Comparator.comparingInt(Detonation::id));
        }
        job.due = due(job.events.get(job.event), chunk.getPos());
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
        if (job != null) byDue.remove(job);
    }

    /** Счётчики пожаров только для подрывов, которые ещё помнятся. */
    public void retainBudgets(java.util.Set<Integer> detonations) {
        budgets.keySet().retainAll(detonations);
    }

    public void clear(ServerLevel level) {
        jobs.values().forEach(j -> release(level, j));
        scans.clear();
        jobs.clear();
        byDue.clear();
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

    private static long due(Detonation d, ChunkPos p) {
        return d.gameTime() + (long) d.arrivalTicks(nearest(p, d));
    }

    /**
     * Обработать, что успеем за бюджет.
     *
     * @param clock бюджет тика: за столбец берёмся, только если он успеет
     */
    public void work(ServerLevel level, long now, WorkClock clock, RandomSource random) {
        // сначала — в очередь чанки, загруженные при подрывах. Порядок здесь не важен: очередь сама идёт по приходу
        // волны, а на постановку тысяч чанков уходит несколько тиков — волна за это время проходит пару чанков
        while (!scans.isEmpty() && clock.canStart()) {
            Scan scan = scans.peek();
            long c0 = clock.begin();
            if (scan.chunks == null) {
                scan.chunks = loadedInRange(level, scan.d);
            } else {
                // выгрузился после подрыва — пропускаем; загрузится снова — поставит onChunkLoad
                LevelChunk chunk = inMemory(level, scan.chunks[scan.next++]);
                if (chunk != null) offer(chunk, scan.d);
            }
            clock.end(c0);
            if (scan.next >= scan.chunks.length) scans.poll();
        }
        while (!byDue.isEmpty() && clock.canStart()) {
            Job job = byDue.peek();
            if (job.due > now) return;
            if (inMemory(level, job.chunk) == null) {
                // выгружен (onChunkUnload уже убрал бы работу) — загрузится снова, поставит onChunkLoad
                byDue.poll();
                jobs.remove(job.chunk);
                continue;
            }
            ChunkPos pos = new ChunkPos(job.chunk);
            if (!NuclearTickets.neighbourhoodLoaded(level, pos)) {
                // край загруженного мира (сам чанк или соседи ниже полной загрузки): разрушим, когда загрузятся;
                // полностью загруженный край сам просит соседей
                if (!job.held && job.mayHold && level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                    NuclearTickets.holdForScar(level, pos, true);
                    job.held = true;
                }
                byDue.poll();
                job.due = now + NEIGHBOUR_RETRY;
                byDue.add(job);
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            Detonation d = job.events.get(job.event);
            ColumnScar.Budget budget = budgets.computeIfAbsent(d.id(), k -> new ColumnScar.Budget());
            int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
            while (job.column < 256 && clock.canStart()) {
                long c0 = clock.begin();
                ColumnScar.apply(level, d, x0 + (job.column & 15), z0 + (job.column >> 4), budget, random);
                long took = clock.end(c0);
                // один столбец дольше 50 мс — это чужая задержка (загрузка чанка, сборщик мусора): в лог, не чаще раза в 5 с
                if (took > 50_000_000L && now - lastSlowColumn >= 100) {
                    lastSlowColumn = now;
                    ua.zentix.airstrike.Airstrike.LOG.warn("Медленный столбец {} {}: {} мс", x0 + (job.column & 15), z0 + (job.column >> 4), took / 1_000_000);
                }
                job.column++;
            }
            if (job.column < 256) return;
            chunk.setData(ModAttachments.CHUNK_SCAR, d.id());
            chunk.setUnsaved(true);
            job.column = 0;
            job.event++;
            byDue.poll();
            if (job.event >= job.events.size()) {
                release(level, job);
                jobs.remove(job.chunk);
            } else {
                job.due = due(job.events.get(job.event), chunk.getPos());
                byDue.add(job);
            }
        }
    }
}
