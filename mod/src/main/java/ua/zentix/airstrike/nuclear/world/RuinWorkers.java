package ua.zentix.airstrike.nuclear.world;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Фоновые потоки планов руин: разломы, обрушение и достройка по снимкам чанков ({@link ChunkShot}) — то, что стоит
 * десятки мс на чанк в городе, — и разбор чанков, прочитанных с диска ({@link DiskShots}), идут мимо тика сервера. Свой
 * пул, а не общий {@code Util.backgroundExecutor} (им грузятся и генерируются чанки).
 * <p>
 * Сколько потоков: {@code ruin_threads}, 0 — сами: все ядра, кроме занятых игрой, — {@code ядра − 1 (поток сервера)
 * − 1 (своей игре: поток отрисовки клиента, если сервер встроенный) − 1 (ваниль: ввод-вывод чанков, генерация, сборщик
 * мусора)}, не меньше одного. Потолка нет: чем больше ядер, тем быстрее встают дальние кольца. Приоритет потоков Java
 * на Linux не действует, поэтому место игре оставляет только это число и подстройка под тик: раз в секунду средний тик
 * сервера за последнюю секунду — выше {@link #BUSY_TICK_MS} мс, и потоков в работе становится на четверть меньше (не
 * меньше одного), ниже {@link #FREE_TICK_MS} — на один больше (не больше числа потоков). Выбранное число и каждое
 * снижение и возврат — в лог.
 * <p>
 * Задач в пуле (в работе и в очереди) не больше {@link #capacity}: очередь — чтобы потоки не простаивали между тиками
 * (задача — 10–50 мс, тик — 50 мс). Поток сервера отдаёт задачи и забирает готовые, мир в фоне не читается.
 * <p>
 * Память: у каждого потока свои буферы решателей ({@code ThreadLocal}: {@code Collapse.Buffers} — {@code before},
 * {@code after}, {@code ring}; {@code Blast.Buffers} — {@code sky}, {@code gone}), по окну 48×48 на всю высоту мира —
 * {@link #bufferBytes} считает их к пределу памяти подготовки руин.
 * <p>
 * Остановка сервера или мира: пул закрывается ({@code shutdownNow}), начатые задачи дорабатывают в пустоту, их
 * результаты никто не берёт. Потоки — демоны: выход из игры их не ждёт.
 */
public final class RuinWorkers {
    /** Средний тик выше — потоков в работе меньше; ниже {@link #FREE_TICK_MS} — больше. */
    static final double BUSY_TICK_MS = 40, FREE_TICK_MS = 30;
    /** Раз в сколько тиков подстраиваться. */
    private static final int ADAPT_EVERY = 20;
    /** Задач в пуле на поток в работе: очередь на тик вперёд. */
    private static final int QUEUE_PER_THREAD = 4;

    private static final AtomicInteger NUMBER = new AtomicInteger();
    @Nullable
    private static ThreadPoolExecutor pool;
    /** Потоков по настройке (или по ядрам) и сколько сейчас в работе (подстройка под тик). */
    private static int size, active;
    /** Сколько времени потоки заняты с запуска пула, нс (загрузка потоков в сводке). */
    private static final AtomicLong BUSY = new AtomicLong();
    private static long startedAt;
    private static long lastAdapt = -ADAPT_EVERY;
    /** Снижений и возвратов с запуска пула; зависших задач. */
    private static int lowered, raised, expired;

    private RuinWorkers() {}

    /** Сколько ядер оставить игре: поток сервера, отрисовка клиента на встроенном сервере, ваниль. */
    static int reserve(boolean integrated) {
        return 1 + (integrated ? 1 : 0) + 1;
    }

    /** Сколько потоков по настройке: 0 — все ядра, кроме оставленных игре ({@link #reserve}). */
    static int threads(boolean integrated) {
        int n = AirstrikeConfig.SERVER.nukeRuinThreads.get();
        if (n > 0) return n;
        return Math.max(1, Runtime.getRuntime().availableProcessors() - reserve(integrated));
    }

    private static boolean integrated() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null && !server.isDedicatedServer();
    }

    private static ThreadPoolExecutor pool() {
        boolean integrated = integrated();
        int n = threads(integrated);
        if (pool != null && size == n) return pool;
        if (pool != null) pool.shutdown();
        size = active = n;
        lowered = raised = 0;
        pool = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "Airstrike ruins #" + NUMBER.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        startedAt = System.nanoTime();
        BUSY.set(0);
        int manual = AirstrikeConfig.SERVER.nukeRuinThreads.get();
        Airstrike.LOG.info("Руины: фоновых потоков {} ({}; ядер {}, оставлено игре {}{})", n, manual > 0 ? "из настройки ruin_threads" : "сами",
                Runtime.getRuntime().availableProcessors(), manual > 0 ? 0 : reserve(integrated), integrated ? ", сервер встроенный" : "");
        return pool;
    }

    /** Сколько задач держать в пуле сразу (в работе и в очереди). */
    static int capacity() {
        if (pool == null) pool();
        return Math.max(2, active * QUEUE_PER_THREAD);
    }

    /** Можно ли отдать ещё задачу: в пуле меньше {@link #capacity}. */
    static boolean admit() {
        return inFlight() < capacity();
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

    /** Исполнитель для разбора чанков с диска (задача из потока ввода-вывода — в пул). */
    static Executor executor() {
        ThreadPoolExecutor p = pool();
        return r -> p.execute(() -> {
            long t0 = System.nanoTime();
            try {
                r.run();
            } finally {
                BUSY.addAndGet(System.nanoTime() - t0);
            }
        });
    }

    /**
     * Подстройка под тик (поток сервера, каждый тик; работает раз в {@link #ADAPT_EVERY}): средний тик сервера за
     * последнюю секунду выше {@link #BUSY_TICK_MS} — потоков в работе на четверть меньше, ниже {@link #FREE_TICK_MS} —
     * на один больше. Пока пул не нужен — ничего.
     */
    public static void adapt(MinecraftServer server) {
        ThreadPoolExecutor p = pool;
        if (p == null) return;
        int tick = server.getTickCount();
        if (tick >= lastAdapt && tick - lastAdapt < ADAPT_EVERY) return;
        lastAdapt = tick;
        if (inFlight() == 0 && active == size) return;
        long[] times = server.getTickTimesNanos();
        long sum = 0;
        int n = Math.min(ADAPT_EVERY, times.length);
        for (int i = 1; i <= n; i++) sum += times[Math.floorMod(tick - i, times.length)];
        double ms = sum / (double) n / 1e6;
        int now = active;
        if (ms > BUSY_TICK_MS && active > 1) {
            active = Math.max(1, active - Math.max(1, active / 4));
            lowered++;
        } else if (ms < FREE_TICK_MS && active < size) {
            active++;
            raised++;
        }
        if (active == now) return;
        if (active < now) {
            p.setCorePoolSize(active);
            p.setMaximumPoolSize(active);
        } else {
            p.setMaximumPoolSize(active);
            p.setCorePoolSize(active);
        }
        Airstrike.LOG.info("Руины: фоновых потоков в работе {} из {} (тик сервера {} мс)", active, size, String.format(java.util.Locale.ROOT, "%.1f", ms));
    }

    /** Фоновый план брошен: не готов за срок ({@link RuinContext#TASK_AGE_LIMIT}). */
    static void expired() {
        expired++;
    }

    /** Для строки «готовы»: потоков, в работе, снижений, возвратов, брошенных задач. */
    static String summary() {
        return size + " (в работе " + active + ", снижений " + lowered + ", возвратов " + raised + ", брошено задач " + expired + ")";
    }

    /** Буферы решателей всех потоков пула, байт (оценка по окну 48×48 на высоту мира: 7 байт на место). */
    static long bufferBytes(int height) {
        return (long) size * RuinWindow.SIDE * RuinWindow.SIDE * height * 7;
    }

    /** Доля времени, которую потоки заняты с запуска пула (для строки «готовы»). */
    static double utilisation() {
        if (pool == null) return 0;
        long wall = System.nanoTime() - startedAt;
        return wall <= 0 ? 0 : Math.min(1, BUSY.get() / (double) wall / size);
    }

    /** Остановка сервера: задачи бросаются, пул закрывается. */
    public static void shutdown() {
        ThreadPoolExecutor p = pool;
        pool = null;
        lastAdapt = -ADAPT_EVERY;
        if (p == null) return;
        p.shutdownNow();
        Airstrike.LOG.debug("Фоновые планы руин остановлены");
    }
}
