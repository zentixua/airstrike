import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Поток сервера в записях JFR запусков salvo-bench: на что уходит тик — по частям тика ванили, по владельцам кода
 * (пакет или мод миксина) и по горячим методам; мс на тик = доля образцов в тике × средний тик из строк лога.
 * Задание pack-e (удаляется вместе с ним). Запуск: java pack-e-jfr.java ИМЯ ЛОГ JFR [ИМЯ ЛОГ JFR …]; разница —
 * каждого следующего запуска с предыдущим, порядок строк — по первой разнице.
 */
public class JfrServer {
    static final Pattern MSPT = Pattern.compile("^\\[(\\d{2}\\w{3}\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\].*salvo-bench mspt=([\\d.]+)");
    static final DateTimeFormatter LOGTIME = DateTimeFormatter.ofPattern("ddMMMyyyy HH:mm:ss.SSS", Locale.ENGLISH);
    /** Обработчик миксина с модом в имени (Mixin Fabric/NeoForge): handler$zza000$modid$name. */
    static final Pattern MIXIN = Pattern.compile("^[a-zA-Z]+\\$[a-z]{3}\\d{3}\\$([a-z0-9_]+)\\$");
    static final int ROWS = 280;

    static final String MS = "net.minecraft.server.MinecraftServer.";
    static final String[][] PARTS = {
            {"сущности", "net.minecraft.server.level.ServerLevel.tickNonPassenger", "net.minecraft.server.level.ServerLevel.tickPassenger"},
            {"блок-сущности", "net.minecraft.world.level.Level.tickBlockEntities"},
            {"тики блоков и жидкостей", "net.minecraft.world.ticks.LevelTicks.tick"},
            {"спавн мобов", "net.minecraft.world.level.NaturalSpawner.spawnForChunk", "net.minecraft.world.level.NaturalSpawner.createState"},
            {"случайные тики и погода", "net.minecraft.server.level.ServerLevel.tickChunk"},
            {"тик чанков: остальное (обход)", "net.minecraft.server.level.ServerChunkCache.tickChunks"},
            {"события блоков", "net.minecraft.server.level.ServerLevel.runBlockEvents"},
            {"отправка сущностей игрокам", "net.minecraft.server.level.ChunkMap.tick()V"},
            {"выгрузка и сохранение чанков", "net.minecraft.server.level.ChunkMap.tick(Ljava/util/function/BooleanSupplier;)V"},
            {"тикеты и уровни чанков", "net.minecraft.server.level.DistanceManager.runAllUpdates"},
            {"сеть", "net.minecraft.server.network.ServerConnectionListener.tick"},
            {"автосохранение", MS + "saveEverything", MS + "saveAllChunks"},
            {"события тика (моды)", "net.neoforged.neoforge.event.EventHooks.fireServerTickPre", "net.neoforged.neoforge.event.EventHooks.fireServerTickPost",
                    "net.neoforged.neoforge.event.EventHooks.fireLevelTickPre", "net.neoforged.neoforge.event.EventHooks.fireLevelTickPost"},
            {"мир: остальное", "net.minecraft.server.level.ServerLevel.tick"},
    };

    record Run(String name, double mspt, int rows, Instant from, Instant to,
               Map<String, Integer> parts, Map<String, Integer> owners, Map<String, Integer> self, Map<String, Integer> ents,
               Map<String, Integer> bes, int samples, int inTick, int gcCount, double gcMs, double cpuMachine, double cpuJvm,
               double serverCpu) {
        double perTick(int n) { return inTick == 0 ? 0 : n * mspt / inTick; }
    }

    public static void main(String[] a) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i + 2 < a.length; i += 3) runs.add(read(a[i], Path.of(a[i + 1]), Path.of(a[i + 2])));
        for (Run r : runs) {
            System.out.printf(Locale.ROOT, "== %s: окно %s–%s (%d строк), тик в среднем %.1f мс; образцов потока сервера %d, в тике %d (%.0f%%)%n",
                    r.name, r.from.atZone(ZoneId.systemDefault()).toLocalTime(), r.to.atZone(ZoneId.systemDefault()).toLocalTime(), r.rows, r.mspt,
                    r.samples, r.inTick, 100.0 * r.inTick / Math.max(1, r.samples));
            double sec = Math.max(1, (r.to.toEpochMilli() - r.from.toEpochMilli()) / 1000.0);
            System.out.printf(Locale.ROOT, "   сборка мусора: пауз %d, сумма %.0f мс (%.2f мс на тик при 20 тиках/с); процессор: машина %.0f%%, JVM %.0f%%, поток сервера %.0f%%%n",
                    r.gcCount, r.gcMs, r.gcMs / (sec * 20), r.cpuMachine * 100, r.cpuJvm * 100, r.serverCpu * 100);
        }
        table("части тика, мс на тик", runs, Run::parts, 20, false);
        table("владельцы кода в тике (включительно: есть в стеке), мс на тик", runs, Run::owners, 30, true);
        table("сущности: кадр над tickNonPassenger, мс на тик", runs, Run::ents, 15, true);
        table("блок-сущности: первый кадр не LevelChunk, мс на тик", runs, Run::bes, 15, true);
        table("собственное время методов в тике, мс на тик", runs, Run::self, 25, true);
    }

    interface Pick { Map<String, Integer> get(Run r); }

    static void table(String title, List<Run> runs, Pick pick, int top, boolean byDiff) {
        StringBuilder head = new StringBuilder();
        for (Run r : runs) head.append(head.length() == 0 ? "" : " | ").append(r.name);
        for (int i = 1; i < runs.size(); i++) head.append(" | ").append(runs.get(i).name).append("−").append(runs.get(i - 1).name);
        System.out.println("-- " + title + " (" + head + ")");
        Set<String> keys = new LinkedHashSet<>();
        for (Run r : runs) keys.addAll(pick.get(r).keySet());
        List<String> order = new ArrayList<>(keys);
        Comparator<String> cmp = runs.size() >= 2
                ? Comparator.comparingDouble(k -> -Math.abs(ms(runs.get(1), pick, k) - ms(runs.get(0), pick, k)))
                : Comparator.comparingDouble(k -> -runs.stream().mapToDouble(r -> ms(r, pick, k)).max().orElse(0));
        order.sort(byDiff ? cmp : Comparator.comparingInt(JfrServer::partIndex));
        int n = 0;
        for (String k : order) {
            if (n++ >= top) break;
            StringBuilder sb = new StringBuilder("   ");
            for (Run r : runs) sb.append(String.format(Locale.ROOT, "%6.2f | ", ms(r, pick, k)));
            for (int i = 1; i < runs.size(); i++) sb.append(String.format(Locale.ROOT, "%+6.2f | ", ms(runs.get(i), pick, k) - ms(runs.get(i - 1), pick, k)));
            sb.append(k.length() > 150 ? k.substring(0, 150) : k);
            System.out.println(sb);
        }
    }

    static int partIndex(String k) {
        for (int i = 0; i < PARTS.length; i++) if (PARTS[i][0].equals(k)) return i;
        return PARTS.length;
    }

    static double ms(Run r, Pick pick, String k) { return r.perTick(pick.get(r).getOrDefault(k, 0)); }

    static Run read(String name, Path log, Path jfr) throws Exception {
        List<Instant> times = new ArrayList<>();
        double sum = 0;
        for (String l : Files.readAllLines(log, java.nio.charset.StandardCharsets.ISO_8859_1)) {
            Matcher m = MSPT.matcher(l);
            if (!m.find() || times.size() >= ROWS) continue;
            times.add(LocalDateTime.parse(m.group(1), LOGTIME).atZone(ZoneId.systemDefault()).toInstant());
            sum += Double.parseDouble(m.group(2));
        }
        if (times.isEmpty()) throw new IllegalStateException(name + ": в логе нет строк salvo-bench mspt");
        // строка — средний тик за 100 тиков до неё: окно начинается за 5 с до первой строки
        Instant from = times.get(0).minusSeconds(5), to = times.get(times.size() - 1);
        Map<String, Integer> parts = new HashMap<>(), owners = new HashMap<>(), self = new HashMap<>(), ents = new HashMap<>(), bes = new HashMap<>();
        int samples = 0, inTick = 0, gcCount = 0, cpuN = 0, thrN = 0;
        double gcMs = 0, cpuM = 0, cpuJ = 0, thr = 0;
        try (RecordingFile f = new RecordingFile(jfr)) {
            while (f.hasMoreEvents()) {
                RecordedEvent e = f.readEvent();
                Instant t = e.getStartTime();
                if (t.isBefore(from) || t.isAfter(to)) continue;
                String type = e.getEventType().getName();
                switch (type) {
                    case "jdk.GarbageCollection" -> { gcCount++; gcMs += e.getDuration("sumOfPauses").toNanos() / 1e6; }
                    case "jdk.CPULoad" -> { cpuN++; cpuM += e.getFloat("machineTotal"); cpuJ += e.getFloat("jvmUser") + e.getFloat("jvmSystem"); }
                    case "jdk.ThreadCPULoad" -> {
                        RecordedThread th = e.getThread("eventThread");
                        if (th != null && "Server thread".equals(th.getJavaName())) { thrN++; thr += e.getFloat("user") + e.getFloat("system"); }
                    }
                    case "jdk.ExecutionSample" -> {
                        RecordedThread th = e.getThread("sampledThread");
                        if (th == null || !"Server thread".equals(th.getJavaName()) || e.getStackTrace() == null) continue;
                        samples++;
                        List<RecordedFrame> frames = e.getStackTrace().getFrames();
                        List<String> names = new ArrayList<>(frames.size());
                        for (RecordedFrame fr : frames) {
                            String cls = fr.getMethod().getType().getName();
                            names.add(cls + "." + fr.getMethod().getName() + "\t" + fr.getMethod().getDescriptor());
                        }
                        boolean tick = names.stream().anyMatch(n -> n.startsWith(MS + "tickServer\t"));
                        if (!tick) continue;
                        inTick++;
                        add(parts, part(names));
                        Set<String> own = new HashSet<>();
                        for (int i = 0; i < frames.size(); i++) own.add(owner(names.get(i)));
                        own.remove("minecraft");
                        own.remove("jdk");
                        for (String o : own) add(owners, o);
                        add(self, names.get(0).replace('\t', ' ').replaceAll(" \\(.*", ""));
                        for (int i = 1; i < names.size(); i++) {
                            String n = names.get(i);
                            if (n.startsWith("net.minecraft.server.level.ServerLevel.tickNonPassenger\t") || n.startsWith("net.minecraft.server.level.ServerLevel.tickPassenger\t")) {
                                add(ents, names.get(i - 1).split("\t")[0]);
                                break;
                            }
                            if (n.startsWith("net.minecraft.world.level.Level.tickBlockEntities\t")) {
                                String up = "?";
                                for (int j = i - 1; j >= 0; j--) {
                                    String c = names.get(j).split("\t")[0];
                                    if (!c.startsWith("net.minecraft.world.level.chunk.LevelChunk")) { up = c; break; }
                                }
                                add(bes, up);
                                break;
                            }
                        }
                    }
                    default -> { }
                }
            }
        }
        return new Run(name, sum / times.size(), times.size(), from, to, parts, owners, self, ents, bes, samples, inTick, gcCount, gcMs,
                cpuN == 0 ? 0 : cpuM / cpuN, cpuN == 0 ? 0 : cpuJ / cpuN, thrN == 0 ? 0 : thr / thrN);
    }

    static String part(List<String> names) {
        for (String[] p : PARTS)
            for (int i = 1; i < p.length; i++)
                for (String n : names) {
                    String key = p[i];
                    boolean withDesc = key.contains("(");
                    String have = withDesc ? n.replace("\t", "") : n.substring(0, n.indexOf('\t'));
                    if (have.equals(key)) return p[0];
                }
        return "тик сервера: остальное";
    }

    static String owner(String frame) {
        String full = frame.substring(0, frame.indexOf('\t'));
        int dot = full.lastIndexOf('.');
        String cls = full.substring(0, dot), method = full.substring(dot + 1);
        Matcher m = MIXIN.matcher(method);
        if (m.find()) return "миксин " + m.group(1);
        if (cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("com.sun.")) return "jdk";
        if (cls.startsWith("net.minecraft.") || cls.startsWith("com.mojang.")) return "minecraft";
        if (cls.startsWith("net.neoforged.")) return "neoforge";
        String[] p = cls.split("\\.");
        int n = p.length - 1; // без имени класса
        int keep = Math.min(n, 3);
        if (keep == 3 && n > 3 && Set.of("mods", "mod", "common", "core", "fabric", "neoforge").contains(p[2])) keep = 4;
        return String.join(".", Arrays.copyOf(p, Math.max(1, keep)));
    }

    static void add(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }
}
