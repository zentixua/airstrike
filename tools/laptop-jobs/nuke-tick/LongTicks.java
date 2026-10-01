// Долгие ядерные тики по записи JFR, без сверки часов лога: подряд идущие образцы потока сервера, у которых в стеке
// NuclearStrikes.tick, дольше порога от первого до последнего — один долгий тик (ядерка тикает один раз за тик сервера).
// Для трёх самых долгих: что делал поток (самый внешний метод ядерки, ближайший метод мода, стеки) и события JVM
// в окне ± 0,5 с (сборки, GCLocker, безопасные точки, ожидания потока сервера). Время — UTC.
// Аргументы: файл.jfr [порог, мс; 400] [имя потока; Server thread] [класс#метод окна; ua.zentix.airstrike.nuclear.NuclearStrikes#tick].
import jdk.jfr.consumer.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public class LongTicks {
    record Sample(Instant at, boolean nuke, String outer, String mod, String stack) {}

    static String frame(RecordedFrame f) {
        RecordedMethod m = f.getMethod();
        String t = m.getType().getName();
        return t.substring(t.lastIndexOf('.') + 1) + "." + m.getName() + ":" + f.getLineNumber();
    }

    static void add(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    public static void main(String[] a) throws Exception {
        long minMs = a.length > 1 ? Long.parseLong(a[1]) : 400;
        String thread = a.length > 2 ? a[2] : "Server thread";
        String[] mark = (a.length > 3 ? a[3] : "ua.zentix.airstrike.nuclear.NuclearStrikes#tick").split("#");
        List<Sample> samples = new ArrayList<>();
        List<RecordedEvent> jvm = new ArrayList<>();
        try (RecordingFile f = new RecordingFile(Path.of(a[0]))) {
            while (f.hasMoreEvents()) {
                RecordedEvent e = f.readEvent();
                String type = e.getEventType().getName();
                if (type.equals("jdk.ExecutionSample")) {
                    RecordedThread th = e.getThread("sampledThread");
                    if (th == null || !thread.equals(th.getJavaName())) continue;
                    List<RecordedFrame> fr = e.getStackTrace() == null ? List.of() : e.getStackTrace().getFrames();
                    boolean nuke = false;
                    String outer = "—", mod = "—";
                    for (int i = fr.size() - 1; i >= 0; i--) {
                        String tn = fr.get(i).getMethod().getType().getName();
                        if (tn.equals(mark[0]) && fr.get(i).getMethod().getName().equals(mark[1])) nuke = true;
                        if (nuke && tn.startsWith("ua.zentix.airstrike.nuclear.world") && outer.equals("—")) outer = frame(fr.get(i));
                    }
                    for (RecordedFrame x : fr) if (x.getMethod().getType().getName().startsWith("ua.zentix.airstrike")) { mod = frame(x); break; }
                    StringBuilder sb = new StringBuilder();
                    int own = 0;
                    for (RecordedFrame x : fr) {
                        if (sb.length() > 0) sb.append(" ← ");
                        sb.append(frame(x));
                        if (x.getMethod().getType().getName().startsWith("ua.zentix.airstrike") && ++own >= 3) break;
                        if (sb.length() > 700) break;
                    }
                    samples.add(new Sample(e.getStartTime(), nuke, outer, mod, sb.toString()));
                    continue;
                }
                long ms = e.getDuration().toMillis();
                boolean gc = type.equals("jdk.GarbageCollection") || type.equals("jdk.GCLocker") || type.equals("jdk.ZAllocationStall")
                        || type.startsWith("jdk.Safepoint") && ms >= 5 || type.equals("jdk.GCPhasePause") && ms >= 5;
                RecordedThread et = e.hasField("eventThread") ? e.getThread() : null;
                boolean mine = et != null && thread.equals(et.getJavaName()) && ms >= 20 && (type.equals("jdk.ThreadPark") || type.equals("jdk.JavaMonitorEnter")
                        || type.equals("jdk.JavaMonitorWait") || type.equals("jdk.ThreadSleep") || type.equals("jdk.FileRead") || type.equals("jdk.FileWrite"));
                if (gc || mine) jvm.add(e);
            }
        }
        samples.sort(Comparator.comparing(Sample::at));
        // окна: подряд образцы с ядеркой в стеке
        List<int[]> windows = new ArrayList<>();
        for (int i = 0; i < samples.size(); ) {
            if (!samples.get(i).nuke()) { i++; continue; }
            int j = i;
            while (j + 1 < samples.size() && samples.get(j + 1).nuke()) j++;
            long span = Duration.between(samples.get(i).at(), samples.get(j).at()).toMillis();
            if (span >= minMs) windows.add(new int[]{i, j});
            i = j + 1;
        }
        windows.sort((x, y) -> Long.compare(span(samples, y), span(samples, x)));
        System.out.printf("образцов потока «%s»: %d; ядерных окон не короче %d мс: %d%n", thread, samples.size(), minMs, windows.size());
        for (int[] w : windows.subList(0, Math.min(30, windows.size()))) {
            Sample s0 = samples.get(w[0]), s1 = samples.get(w[1]);
            System.out.printf("  %s – %s UTC: %d мс, образцов %d%n", time(s0.at()), time(s1.at()), span(samples, w), w[1] - w[0] + 1);
        }
        for (int[] w : windows.subList(0, Math.min(3, windows.size()))) {
            Sample s0 = samples.get(w[0]), s1 = samples.get(w[1]);
            int n = w[1] - w[0] + 1;
            // образец до окна: предыдущий образец потока и разрыв до первого образца окна (поток не в Java-коде — его не видно)
            String before = w[0] > 0 ? time(samples.get(w[0] - 1).at()) + " (" + samples.get(w[0] - 1).mod() + ")" : "—";
            System.out.printf("%n=== окно %s – %s UTC, %d мс, образцов %d; образец до окна %s%n", time(s0.at()), time(s1.at()), span(samples, w), n, before);
            Map<String, Integer> outer = new HashMap<>(), mod = new HashMap<>(), stacks = new HashMap<>();
            long gap = 0;
            String gapAt = "";
            for (int k = w[0]; k <= w[1]; k++) {
                Sample s = samples.get(k);
                add(outer, s.outer());
                add(mod, s.mod());
                add(stacks, s.stack());
                if (k > w[0]) {
                    long g = Duration.between(samples.get(k - 1).at(), s.at()).toMillis();
                    if (g > gap) { gap = g; gapAt = time(samples.get(k - 1).at()) + " → " + time(s.at()); }
                }
            }
            System.out.printf("самый большой разрыв между образцами: %d мс (%s)%n", gap, gapAt);
            print("самый внешний метод ядерки", outer, 10, n);
            print("ближайший к вершине метод мода", mod, 12, n);
            print("стеки (до третьего метода мода)", stacks, 6, n);
            Instant from = s0.at().minusMillis(500), to = s1.at().plusMillis(500);
            System.out.println("события JVM в окне ± 0,5 с:");
            int shown = 0;
            for (RecordedEvent e : jvm) {
                if (e.getEndTime().isBefore(from) || e.getStartTime().isAfter(to)) continue;
                if (++shown > 40) break;
                StringBuilder sb = new StringBuilder("  " + time(e.getStartTime()) + " " + e.getEventType().getName() + " " + e.getDuration().toMillis() + " мс");
                RecordedThread et = e.hasField("eventThread") ? e.getThread() : null;
                if (et != null) sb.append(" [").append(et.getJavaName()).append("]");
                for (String k : List.of("name", "cause", "lockCount", "stallCount", "operation", "parkedClass", "monitorClass", "path")) {
                    if (e.hasField(k)) sb.append(" ").append(k).append("=").append(String.valueOf((Object) e.getValue(k)));
                }
                if (et != null && thread.equals(et.getJavaName()) && e.getStackTrace() != null) {
                    int k = 0;
                    for (RecordedFrame x : e.getStackTrace().getFrames()) {
                        sb.append(k == 0 ? " | " : " ← ").append(frame(x));
                        if (++k >= 12) break;
                    }
                }
                System.out.println(sb);
            }
            if (shown == 0) System.out.println("  нет");
        }
    }

    static long span(List<Sample> s, int[] w) { return Duration.between(s.get(w[0]).at(), s.get(w[1]).at()).toMillis(); }

    static String time(Instant t) { return t.atOffset(ZoneOffset.UTC).toLocalTime().toString(); }

    static void print(String title, Map<String, Integer> m, int lim, int n) {
        System.out.println(title + ":");
        m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(lim)
                .forEach(x -> System.out.printf("%6d %5.1f %%  %s%n", x.getValue(), 100.0 * x.getValue() / Math.max(n, 1), x.getKey()));
    }
}
