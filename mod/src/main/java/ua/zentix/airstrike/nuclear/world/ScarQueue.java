package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.registry.ModAttachments;

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
 */
public final class ScarQueue {
    /** Работа по одному чанку: подрывы по порядку номеров, текущий столбец. */
    private static final class Job {
        final long chunk;
        final List<Detonation> events = new ArrayList<>();
        int event;
        int column;
        long due;

        Job(long chunk) {
            this.chunk = chunk;
        }
    }

    private final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Job> byDue = new PriorityQueue<>(Comparator.comparingLong(j -> j.due));
    private final Map<Integer, ColumnScar.Budget> budgets = new HashMap<>();

    public int size() {
        return jobs.size();
    }

    /** Поставить чанк в очередь по подрыву (если он новее отметки на чанке и чанк в радиусе). */
    public void offer(LevelChunk chunk, Detonation d) {
        int applied = chunk.getData(ModAttachments.CHUNK_SCAR);
        if (d.id() <= applied || !inRange(chunk.getPos(), d)) return;
        long key = chunk.getPos().toLong();
        Job job = jobs.get(key);
        if (job == null) {
            job = new Job(key);
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

    public void drop(ChunkPos pos) {
        Job job = jobs.remove(pos.toLong());
        if (job != null) byDue.remove(job);
    }

    public void clear() {
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
     * @param deadline {@link System#nanoTime()}, после которого останавливаемся
     */
    public void work(ServerLevel level, long now, long deadline, RandomSource random) {
        while (!byDue.isEmpty() && System.nanoTime() < deadline) {
            Job job = byDue.peek();
            if (job.due > now) return;
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(job.chunk), ChunkPos.getZ(job.chunk));
            if (chunk == null) {
                byDue.poll();
                jobs.remove(job.chunk);
                continue;
            }
            Detonation d = job.events.get(job.event);
            ColumnScar.Budget budget = budgets.computeIfAbsent(d.id(), k -> new ColumnScar.Budget());
            int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
            while (job.column < 256 && System.nanoTime() < deadline) {
                ColumnScar.apply(level, d, x0 + (job.column & 15), z0 + (job.column >> 4), budget, random);
                job.column++;
            }
            if (job.column < 256) return;
            chunk.setData(ModAttachments.CHUNK_SCAR, d.id());
            chunk.setUnsaved(true);
            job.column = 0;
            job.event++;
            byDue.poll();
            if (job.event >= job.events.size()) {
                jobs.remove(job.chunk);
            } else {
                job.due = due(job.events.get(job.event), chunk.getPos());
                byDue.add(job);
            }
        }
    }
}
