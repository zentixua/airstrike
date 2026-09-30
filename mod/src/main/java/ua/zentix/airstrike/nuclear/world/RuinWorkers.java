package ua.zentix.airstrike.nuclear.world;

import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Фоновые потоки планов руин: разломы и обрушение по снимкам чанков ({@link ChunkShot}) — то, что стоит десятки мс на
 * чанк в городе, — идут мимо тика сервера. Свой пул, а не общий {@code Util.backgroundExecutor} (им грузятся и
 * генерируются чанки): потоков {@code ruin_threads}, по умолчанию не больше двух и два ядра оставлены серверу и
 * системе. Задач в работе не больше {@link #capacity}: очередь в пуле — только чтобы потоки не простаивали между
 * тиками. Поток сервера отдаёт задачи и забирает готовые, мир в фоне не читается.
 * <p>
 * Остановка сервера или мира: пул закрывается ({@code shutdownNow}), начатые задачи дорабатывают в пустоту, их
 * результаты никто не берёт. Потоки — демоны: выход из игры их не ждёт.
 */
public final class RuinWorkers {
    private static final AtomicInteger NUMBER = new AtomicInteger();
    @Nullable
    private static ThreadPoolExecutor pool;
    private static int size;
    /** Сколько времени потоки заняты планами с запуска пула, нс (загрузка потоков в сводке). */
    private static final AtomicLong BUSY = new AtomicLong();
    private static long startedAt;

    private RuinWorkers() {}

    /** Сколько потоков по настройке: 0 — по числу ядер. */
    static int threads() {
        int n = AirstrikeConfig.SERVER.nukeRuinThreads.get();
        if (n > 0) return n;
        return Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));
    }

    private static ThreadPoolExecutor pool() {
        int n = threads();
        if (pool != null && size == n) return pool;
        if (pool != null) pool.shutdown();
        size = n;
        pool = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "Airstrike ruins #" + NUMBER.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        startedAt = System.nanoTime();
        BUSY.set(0);
        return pool;
    }

    /** Сколько задач держать в работе сразу (в потоках и в очереди пула). */
    static int capacity() {
        return threads() * 3;
    }

    /** Задач в работе (в потоках и в очереди). */
    static int inFlight() {
        ThreadPoolExecutor p = pool;
        return p == null ? 0 : p.getActiveCount() + p.getQueue().size();
    }

    /** Задача в пул (поток сервера). */
    static <T> Future<T> submit(Callable<T> task) {
        return pool().submit(() -> {
            long t0 = System.nanoTime();
            try {
                return task.call();
            } finally {
                BUSY.addAndGet(System.nanoTime() - t0);
            }
        });
    }

    /** Доля времени, которую потоки заняты планами с запуска пула (для строки «готовы»). */
    static double utilisation() {
        if (pool == null) return 0;
        long wall = System.nanoTime() - startedAt;
        return wall <= 0 ? 0 : Math.min(1, BUSY.get() / (double) wall / size);
    }

    /** Остановка сервера: задачи бросаются, пул закрывается. */
    public static void shutdown() {
        ThreadPoolExecutor p = pool;
        pool = null;
        if (p == null) return;
        p.shutdownNow();
        Airstrike.LOG.debug("Фоновые планы руин остановлены");
    }
}
