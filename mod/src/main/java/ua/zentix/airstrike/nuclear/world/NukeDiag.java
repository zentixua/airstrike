package ua.zentix.airstrike.nuclear.world;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Диагностика зоны за волной для прогонов на ноутбуке: только считает и пишет строки «ДИАГ …», поведение не меняет.
 * Включается свойством JVM {@code -Dairstrike.nukeDiag=true}; без него ни одной строки и ни одного потока.
 * Не состояние мира — замер (как {@code WorkScheduler.ownNanos}).
 */
public final class NukeDiag {
    public static final boolean ON = Boolean.getBoolean("airstrike.nukeDiag");

    /** Почему работа чанка в очереди руин отложена (счёт попыток за окно отчёта). */
    enum Wait {
        BELOW_FULL("сам ниже полной загрузки"),
        SERVER_PLAN("план в потоке сервера ждёт окна REACH"),
        PLAN_R1("готовый план ждёт соседей r1"),
        PLAN_R2("готовый план ждёт соседей r2"),
        WINDOW("окно читается с диска"),
        ADMIT("пул фона полон"),
        BACKGROUND("план строится в фоне"),
        SUBMIT("снимки не отданы"),
        AGAIN("ещё единица (разлом/устарел)");

        final String text;

        Wait(String text) {
            this.text = text;
        }
    }

    private static final long[] WAITS = new long[Wait.values().length];
    private static final AtomicLong READS = new AtomicLong(), READ_NANOS = new AtomicLong(), READ_MAX = new AtomicLong();
    private static final AtomicLong READS_IN_FLIGHT = new AtomicLong();
    /** Тик дольше, мс: его стеки — в лог. */
    private static final long LONG_TICK_MS = 250;
    private static volatile long tickStart;
    private static final List<StackTraceElement[]> samples = new ArrayList<>();
    private static volatile Thread sampler;

    private NukeDiag() {}

    static void waited(Wait w) {
        if (ON) WAITS[w.ordinal()]++;
    }

    /** Чтение окна с диска отдано: ответ — {@link #readDone}. */
    static long readStart() {
        if (!ON) return 0;
        READS_IN_FLIGHT.incrementAndGet();
        return System.nanoTime();
    }

    static void readDone(long start) {
        if (!ON) return;
        long took = System.nanoTime() - start;
        READS_IN_FLIGHT.decrementAndGet();
        READS.incrementAndGet();
        READ_NANOS.addAndGet(took);
        READ_MAX.accumulateAndGet(took, Math::max);
    }

    /** Строка причин ожидания очереди руин и чтений окон за окно отчёта; счётчики — с нуля. */
    static String takeWaits() {
        StringBuilder sb = new StringBuilder();
        for (Wait w : Wait.values()) {
            if (WAITS[w.ordinal()] == 0) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(w.text).append(' ').append(WAITS[w.ordinal()]);
        }
        java.util.Arrays.fill(WAITS, 0);
        long n = READS.getAndSet(0), sum = READ_NANOS.getAndSet(0), max = READ_MAX.getAndSet(0);
        return (sb.isEmpty() ? "нет" : sb) + "; чтений с диска (окна и дальние кольца) " + n + " (в среднем " + (n == 0 ? 0 : sum / n / 1_000_000) + " мс, самое долгое "
                + max / 1_000_000 + " мс, в работе " + READS_IN_FLIGHT.get() + ", в пуле фона задач " + RuinWorkers.inFlight() + " из " + RuinWorkers.capacity() + ")";
    }

    // ---------------------------------------------------------------- долгий тик: стеки потока сервера

    public static void onTickPre(ServerTickEvent.Pre e) {
        if (!ON) return;
        if (sampler == null) startSampler(e.getServer());
        tickStart = System.nanoTime();
    }

    public static void onTickPost(ServerTickEvent.Post e) {
        if (!ON) return;
        long start = tickStart;
        tickStart = 0;
        long ms = (System.nanoTime() - start) / 1_000_000;
        List<StackTraceElement[]> got;
        synchronized (samples) {
            got = new ArrayList<>(samples);
            samples.clear();
        }
        if (start == 0 || ms < LONG_TICK_MS) return;
        // одинаковые верхушки стека вместе: сколько снимков в каждой (снимок — раз в 20 мс после первых 100 мс тика)
        Map<String, Integer> count = new HashMap<>();
        Map<String, StackTraceElement[]> full = new HashMap<>();
        for (StackTraceElement[] st : got) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < Math.min(12, st.length); i++) key.append(st[i]).append('|');
            count.merge(key.toString(), 1, Integer::sum);
            full.putIfAbsent(key.toString(), st);
        }
        StringBuilder sb = new StringBuilder();
        count.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(en -> {
            sb.append("\n  снимков ").append(en.getValue()).append(':');
            StackTraceElement[] st = full.get(en.getKey());
            for (int i = 0; i < Math.min(45, st.length); i++) sb.append("\n    at ").append(st[i]);
        });
        Airstrike.LOG.info("ДИАГ долгий тик {} мс, снимков стека {}:{}", ms, got.size(), sb);
    }

    private static synchronized void startSampler(MinecraftServer server) {
        if (sampler != null) return;
        Thread main = server.getRunningThread();
        sampler = new Thread(() -> {
            try {
                loop(server, main);
            } finally {
                sampler = null;
            }
        }, "airstrike-nuke-diag");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void loop(MinecraftServer server, Thread main) {
        while (server.isRunning()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                return;
            }
            long start = tickStart;
            if (start == 0 || System.nanoTime() - start < 100_000_000L) continue;
            StackTraceElement[] st = main.getStackTrace();
            synchronized (samples) {
                if (samples.size() < 500) samples.add(st);
            }
        }
    }
}
