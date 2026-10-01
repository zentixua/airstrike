package ua.zentix.airstrike.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import ua.zentix.airstrike.Airstrike;

import java.util.concurrent.locks.LockSupport;

/**
 * Остановка сервера: генерация и загрузка чанков, начатые до выхода, доводятся до конца ещё в
 * {@code ServerStoppingEvent} — до ванильного цикла выгрузки в {@code MinecraftServer.stopServer}.
 * <p>
 * Зачем. Задача генерации ({@code ChunkGenerationTask}) держит держатели всех чанков своего окна
 * ({@code acquireGeneration}: {@code getGenerationRefCount() > 0}), пока не дождётся своих слоёв, — и отменённая тоже;
 * а слой (разбор чанка с диска, шаг FULL) кончается только задачей потока сервера ({@code ServerChunkCache.pollTask}).
 * Ванильный цикл остановки зовёт {@code ServerChunkCache.tick(() -> true, false)}: в {@code ChunkMap.processUnloads}
 * очередь выгрузки разбирается без предела, а задача выгрузки держателя, которого держит генерация
 * ({@code isReadyForSaving} ложно), кладёт себя в ту же очередь сразу же ({@code scheduleUnload}). Вызов не
 * возвращается, задачи потока сервера не выполняются, генерация не кончается — выход висел на «Saving worlds» (ноутбук
 * 30.09.2026 после ядерки: 632 с в {@code processUnloads}). Генерацию запускают и тикеты игрока, но районы и зона мода
 * (руины, подготовка, районы целей, полосы подлёта, вход игрока) делают случай частым.
 * <p>
 * Что делает. После того как мод отпустил свои тикеты и работу (обработчики обычного приоритета; районы — {@code StrikeWorld.releaseAreas}), этот обработчик
 * (последним, {@code LOWEST}) выполняет задачи чанков каждого мира ({@code pollTask}: он же применяет снятые тикеты —
 * {@code runDistanceManagerUpdates} — и будит свет), пока ни один держатель карты чанков не занят генерацией
 * ({@link #inFlight}), но не дольше {@link #LIMIT_NANOS} (тогда — строка в лог, и выход идёт как у ванили). Новой
 * генерации после этого никто не просит: цикл остановки тикеты только снимает. Ванильный код не меняется.
 * Проверка — GameTest {@code stopDrainLetsVanillaUnloadFinish}.
 */
public final class StopDrain {
    /** Предел ожидания на весь сервер. */
    public static final long LIMIT_NANOS = 30_000_000_000L;
    /** Как часто писать, что ожидание идёт. */
    private static final long REPORT_NANOS = 5_000_000_000L;

    private StopDrain() {}

    /** {@code ServerStoppingEvent}, приоритет {@code LOWEST}: после того как мод снял свои тикеты и работу. */
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isSameThread()) return;
        if (drain(server.getAllLevels(), System.nanoTime() + LIMIT_NANOS)) return;
        StringBuilder s = new StringBuilder();
        for (ServerLevel level : server.getAllLevels()) {
            int n = inFlight(level);
            if (n > 0) s.append(' ').append(level.dimension().location()).append(" — ").append(n);
        }
        Airstrike.LOG.warn("Остановка: генерация чанков не кончилась за {} с, держателей занято:{} — выход, вероятно, зависнет на «Saving worlds»",
                LIMIT_NANOS / 1_000_000_000L, s);
    }

    /**
     * Выполнять задачи чанков миров, пока у них идёт генерация или загрузка.
     *
     * @param deadline {@code System.nanoTime()}, после которого бросить
     * @return генерации больше нет ни в одном мире
     */
    public static boolean drain(Iterable<ServerLevel> levels, long deadline) {
        long report = System.nanoTime() + REPORT_NANOS;
        while (true) {
            boolean busy = false;
            for (ServerLevel level : levels) {
                ServerChunkCache cache = level.getChunkSource();
                while (cache.pollTask()) {
                    if (System.nanoTime() > deadline) return false;
                }
                if (inFlight(level) > 0) busy = true;
            }
            if (!busy) return true;
            long now = System.nanoTime();
            if (now > deadline) return false;
            if (now >= report) {
                report = now + REPORT_NANOS;
                int n = 0;
                for (ServerLevel level : levels) n += inFlight(level);
                Airstrike.LOG.info("Остановка: жду генерацию чанков, начатую до выхода (держателей занято {})", n);
            }
            // слои генерации идут в потоках генерации, ввода-вывода и света; задачи потоку сервера они пришлют сами
            LockSupport.parkNanos(1_000_000L);
        }
    }

    /**
     * Держателей карты чанков мира, занятых генерацией или загрузкой (задача генерации берёт центр и всё окно и отпускает
     * их, только дождавшись своих слоёв). Сперва применяет тикеты ({@code runDistanceManagerUpdates}), чтобы видимая
     * карта совпала с рабочей; держатель, занятый генерацией, из рабочей карты не уходит ({@code processUnloads} его
     * пропускает).
     */
    public static int inFlight(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        cache.runDistanceManagerUpdates();
        int n = 0;
        for (ChunkHolder holder : cache.chunkMap.getChunks()) {
            if (holder.getGenerationRefCount() > 0) n++;
        }
        return n;
    }
}
