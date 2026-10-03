package ua.zentix.airstrike.work;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Остановки потока сервера дольше {@link #STALL_MS} — в тике или между тиками (задачи чанков, пакеты игроков): когда
 * поток пошёл дальше, строка WARN «Поток сервера стоял N с» с самыми частыми местами по снимкам его стека. Ваниль пишет
 * только накопленное отставание («Can't keep up») и стек — лишь когда сервер падает через 60 с, а строка «Работа мода за
 * 30 с» видит только тики: в игре 03.10.2026 сервер стоял ~14 с между тиками, игроки отключились, и где он стоял, по
 * логам узнать было нельзя.
 * <p>
 * Только замер, поведение не меняет. Поток-наблюдатель просыпается раз в {@link #SAMPLE_MS} и сравнивает отметку тика;
 * стек снимает только во время остановки. Пауза одиночной игры (меню) — не остановка. Не состояние мира — замер (как
 * {@code WorkScheduler.ownNanos}), поэтому статика.
 */
public final class StallWatch {
    static final long STALL_MS = 2000;
    private static final long SAMPLE_MS = 500;
    private static final int MAX_SAMPLES = 120;
    /** Когда поток сервера последний раз прошёл начало или конец тика, {@code System.nanoTime}. */
    private static volatile long progress;
    /** Последний промежуток между отметками дольше {@link #STALL_MS}, нс: длина остановки для строки (0 — забрана). */
    private static volatile long lastGap;
    private static volatile boolean inTick;
    private static volatile Thread watcher;
    private static volatile int reported;

    private StallWatch() {}

    public static void onTickPre(ServerTickEvent.Pre e) {
        mark();
        inTick = true;
        if (watcher == null) start(e.getServer());
    }

    public static void onTickPost(ServerTickEvent.Post e) {
        mark();
        inTick = false;
    }

    private static void mark() {
        long now = System.nanoTime(), gap = now - progress;
        // до отметки: наблюдатель, увидев новую отметку, читает уже эту длину
        if (gap > STALL_MS * 1_000_000L) lastGap = gap;
        progress = now;
    }

    /** Сколько остановок записано в лог (проверки). */
    public static int reported() {
        return reported;
    }

    private static synchronized void start(MinecraftServer server) {
        if (watcher != null) return;
        Thread main = server.getRunningThread();
        Thread t = new Thread(() -> {
            try {
                watch(server, main);
            } finally {
                watcher = null;
            }
        }, "airstrike-stall-watch");
        t.setDaemon(true);
        watcher = t;
        t.start();
    }

    private static void watch(MinecraftServer server, Thread main) {
        List<StackTraceElement[]> samples = new ArrayList<>();
        long stalledAt = 0, pausedAt = 0;
        boolean stalledInTick = false;
        // остановка сервера (сохранение миров) — не остановка потока: наблюдатель кончается с сервером
        while (server.isRunning()) {
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException ex) {
                return;
            }
            long at = progress;
            if (stalledAt != 0 && at != stalledAt) {
                // длина — до первой отметки после остановки (её пишет поток сервера); к пробуждению прошли и другие тики
                long gap = lastGap;
                lastGap = 0;
                report(gap > 0 ? gap : at - stalledAt, stalledInTick, samples);
                samples.clear();
                stalledAt = 0;
            }
            if (server.isPaused()) {
                // пауза одиночной игры: тиков нет, пока игрок в меню, — ждать первого тика после неё
                pausedAt = at;
                samples.clear();
                stalledAt = 0;
                continue;
            }
            if (at == pausedAt || System.nanoTime() - at < STALL_MS * 1_000_000L) continue;
            if (stalledAt == 0) {
                stalledAt = at;
                stalledInTick = inTick;
            }
            if (samples.size() < MAX_SAMPLES) samples.add(main.getStackTrace());
        }
    }

    /** Строка остановки: длина, где (в тике или между тиками) и до трёх мест по числу снимков, сверху стека. */
    private static void report(long nanos, boolean tick, List<StackTraceElement[]> samples) {
        // одинаковые верхушки стека вместе: сколько снимков в каждой
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, StackTraceElement[]> first = new LinkedHashMap<>();
        for (StackTraceElement[] st : samples) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < Math.min(12, st.length); i++) key.append(st[i]).append('|');
            count.merge(key.toString(), 1, Integer::sum);
            first.putIfAbsent(key.toString(), st);
        }
        StringBuilder sb = new StringBuilder();
        count.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(en -> {
            sb.append("\n  снимков ").append(en.getValue()).append(':');
            StackTraceElement[] st = first.get(en.getKey());
            for (int i = 0; i < Math.min(40, st.length); i++) sb.append("\n    at ").append(st[i]);
        });
        reported++;
        Airstrike.LOG.warn("Поток сервера стоял {} с {}, снимков стека {}:{}", String.format(Locale.ROOT, "%.1f", nanos / 1e9),
                tick ? "в тике" : "между тиками", samples.size(), sb);
    }
}
