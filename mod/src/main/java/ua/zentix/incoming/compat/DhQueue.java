package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Очередь обновлений Distant Horizons 3.3.3, как её видит мод (без Minecraft — юнит-тесты): своя задержка DH от вызова
 * API до записи LOD в его базу ({@link #LEAD}) и окно держанных копий в его ограниченной очереди ({@link #window};
 * экземпляр — учёт отданных и не подтверждённых, {@link DhUpdates#sendHeld}).
 * <p>
 * Задержка — из кода DH: обновление ждёт в очереди не меньше {@code ChunkUpdateQueueManager.UPDATE_DEBOUNCE_NANOS}
 * ({@link #DEBOUNCE_MS}); собранный LOD чанка пишется в участок 64×64 блока (4×4 чанка), а участок — в базу не раньше
 * {@code saveDelayInMs} после последней записи в него ({@code AbstractDhLevel}: {@link #SAVE_DELAY_MS}, каждая запись
 * откладывает снова), поток сохранения ({@code AbstractDelayedSaveCache.runCleanupLoop}) просыпается раз в секунду и
 * ещё {@link #SAVE_PAUSE_MS} ждёт перед записью; потом DH рассылает участок (игрокам и своей отрисовке) и шлёт
 * {@code DhApiChunkModifiedEvent} на каждый его записанный чанк. Грубые уровни LOD догоняют за ним — до 250 мс на
 * уровень ({@code FullDataUpdatePropagatorV2}).
 * <p>
 * Очередь DH ограничена ({@code ChunkUpdateQueueManager.maxSize}): {@link #PER_THREAD} позиций на поток его разбора
 * ({@code numberOfThreads}), в одиночной игре — на каждого игрока, на сервере — на 1 + игроков; переполненная молча
 * выкидывает дальнюю от игрока (на выделенном сервере — от 0 0) и раз в 30 с пишет «overloaded». Копию чанка она
 * держит, пока не разберёт, а разбор идёт по одной в потоке очереди (карты высот и хеш чанка, затем сборка в потоках DH).
 */
final class DhQueue {
    /** {@code ChunkUpdateQueueManager.UPDATE_DEBOUNCE_NANOS}: обновление ждёт в очереди не меньше, мс. */
    static final int DEBOUNCE_MS = 250;
    /** {@code AbstractDhLevel}: участок пишется в базу не раньше, чем через столько после последней записи, мс. */
    static final int SAVE_DELAY_MS = 1000;
    /** {@code AbstractDelayedSaveCache.cleanUp}: пауза потока сохранения перед записью снятых участков, мс. */
    static final int SAVE_PAUSE_MS = 100;
    /**
     * Своя задержка DH, тиков, — наименьшая (5 + 20 + 2 тика по 50 мс, без разбора и без ожидания потока сохранения):
     * копия, отданная за столько до прихода фронта, меняет LOD не раньше фронта; позже — на разбор, до секунды ожидания
     * потока сохранения и грубые уровни.
     */
    static final int LEAD = (DEBOUNCE_MS + SAVE_DELAY_MS + SAVE_PAUSE_MS + 49) / 50;
    /** {@code ChunkUpdateQueueManager.MAX_UPDATING_CHUNK_COUNT_PER_THREAD_AND_PLAYER}. */
    static final int PER_THREAD = 1000;
    /**
     * Подтверждения ждать не дольше, нс: вдвое дольше наибольшей задержки DH без очереди (250 мс, до 2 с ожидания
     * сохранения и 100 мс): без подтверждения (DH только читает — повтор Flashback; проверка неизменных чанков у DH
     * выключена — события нет; чанк не изменился — DH его не пишет) копия уходит из окна по сроку.
     */
    static final long TIMEOUT_NANOS = 5_000_000_000L;

    /** Отданные держанные копии без подтверждения: чанк → когда отданы ({@code System.nanoTime}). */
    private final Long2LongOpenHashMap out = new Long2LongOpenHashMap();
    /** Для строки в лог ({@link #take}): отдано, подтверждено, по сроку; задержка подтверждённых — сумма и наибольшая, нс. */
    private long sent, acked, expired, latencySum, latencyMax;

    /**
     * Сколько держанных копий может ждать в очереди DH разом: половина её наименьшего предела (одиночная игра, один
     * игрок) — вторая половина остаётся обновлениям самого DH и отметкам мода ({@link DhUpdates#mark}).
     *
     * @param threads потоки разбора DH ({@code numberOfThreads}; не знаем — 1)
     */
    static int window(int threads) {
        return PER_THREAD * Math.max(1, threads) / 2;
    }

    /** Отдано и не подтверждено. */
    int waiting() {
        return out.size();
    }

    void sent(long chunk, long nanos) {
        out.put(chunk, nanos);
        sent++;
    }

    /** DH записал чанк в базу: копия уходит из окна; false — не ждали (своё обновление DH, прошлый мир). */
    boolean ack(long chunk, long nanos) {
        if (!out.containsKey(chunk)) return false;
        long took = nanos - out.remove(chunk);
        acked++;
        latencySum += took;
        latencyMax = Math.max(latencyMax, took);
        return true;
    }

    /** Ждавшие подтверждения дольше {@link #TIMEOUT_NANOS} — из окна. */
    void expire(long nanos) {
        if (out.isEmpty()) return;
        for (var it = out.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            if (nanos - e.getLongValue() >= TIMEOUT_NANOS) {
                it.remove();
                expired++;
            }
        }
    }

    /** Проверки: окно пусто (счётчики остаются). */
    void clear() {
        out.clear();
    }

    /**
     * Отдано, подтверждено, по сроку, средняя и наибольшая задержка подтверждённых (мс) с прошлого вызова, ждут
     * подтверждения сейчас; счёт начинается заново.
     */
    long[] take() {
        long[] s = {sent, acked, expired, acked == 0 ? 0 : latencySum / acked / 1_000_000, latencyMax / 1_000_000, out.size()};
        sent = acked = expired = latencySum = latencyMax = 0;
        return s;
    }
}
