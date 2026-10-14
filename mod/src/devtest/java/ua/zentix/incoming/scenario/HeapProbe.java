package ua.zentix.incoming.scenario;

import net.minecraft.world.entity.Entity;
import ua.zentix.incoming.Incoming;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Живые объекты в памяти — для поиска утечек миров: гистограмма классов (MBean {@code DiagnosticCommand}, операция
 * {@code gcClassHistogram} — то же, что {@code jcmd GC.class_histogram}) с ключевыми классами мира ({@link #KEYS}) и выборка
 * старых объектов JFR ({@link OldObjects}) с путями до корней GC и держателями. Замеряют сценарий {@code leak}
 * ({@link LeakCheck}: в меню после выхода из мира) и вывод трейлера из повторов ({@code trailer.ReplayFilm}: после
 * открытия каждого повтора).
 */
public final class HeapProbe {
    /** Ключевые классы мира: после выхода из мира их экземпляров быть не должно, в повторе — по одному на мир. */
    public static final List<String> KEYS = List.of(
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.client.multiplayer.ClientLevel",
            "net.minecraft.client.server.IntegratedServer",
            "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.world.level.chunk.ProtoChunk",
            "net.minecraft.world.level.chunk.LevelChunkSection",
            "net.minecraft.server.level.ChunkHolder",
            "net.minecraft.world.entity.Entity");
    /** Сэмпл выборки JFR «через мир»: тип объекта, звено пути до корня или корень содержат одно из этих имён. */
    static final List<String> WORLD_MARKS = List.of("LevelChunk", "ServerLevel", "ClientLevel", "ChunkHolder", "LevelChunkSection",
            "PalettedContainer", "IntegratedServer");
    private static final int TOP = 15;
    /**
     * Пути до корней GC ищет поток VM обходом в глубину (JDK 21): на его стеке по умолчанию (1 МБ) глубокая цепочка
     * ссылок переполняет стек, и JVM падает по SIGSEGV без hs_err (игра — на первом же сбросе; список в 2 млн
     * звеньев: с 1 МБ падает, с 1,5 МБ и больше — нет). Со стеком меньше этого (КБ)
     * пути не ищутся.
     */
    public static final long PATH_STACK_KB = 4096;
    /** Сколько разбирать имена гистограммы в классы (подклассы ключевых), не дольше, мс. */
    private static final long RESOLVE_LIMIT_MS = 10_000;
    private static final long NANOS = 1_000_000_000L;
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
    private static final Pattern TOTAL = Pattern.compile("^Total\\s+(\\d+)\\s+(\\d+)");

    /** {@code -XX:VMThreadStackSize} в КБ (по умолчанию на Linux x64 — 1024), −1 — не узнать. */
    public static long vmThreadStackKb() {
        try {
            CompositeData d = (CompositeData) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption", new Object[] {"VMThreadStackSize"}, new String[] {String.class.getName()});
            return Long.parseLong(String.valueOf(d.get("value")));
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO heap: VMThreadStackSize не узнать ({})", ex.toString());
            return -1;
        }
    }

    public static void gc() {
        try {
            System.gc();
        } catch (RuntimeException ex) {
            Incoming.LOG.warn("SCENARIO heap: System.gc() не прошёл", ex);
        }
    }

    public static long heapUsedMb() {
        try {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        } catch (RuntimeException ex) {
            Incoming.LOG.warn("SCENARIO heap: MemoryMXBean не ответил", ex);
            return -1;
        }
    }

    /** Пулы кучи после последней сборки каждого (у поколенческого ZGC — старое и молодое поколение). */
    public static String pools() {
        try {
            List<String> out = new ArrayList<>();
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage u = p.getType() == MemoryType.HEAP ? p.getCollectionUsage() : null;
                if (u != null) out.add(p.getName() + " " + (u.getUsed() >> 20) + " МБ");
            }
            return out.isEmpty() ? "" : " (после сборки по пулам: " + String.join(", ", out) + ")";
        } catch (RuntimeException ex) {
            return " (пулы: " + ex + ")";
        }
    }

    private record Row(String name, long count, long bytes) {}

    /** Итог гистограммы: всего живых, по ключевым классам {@link #KEYS} — с подклассами ({@code [шт., Б]}), файл гистограммы. */
    public record Histogram(long count, long bytes, Map<String, long[]> keys, Path file) {
        /** Экземпляров ключевого класса с подклассами, −1 — нет в гистограмме. */
        public long count(String key) {
            long[] a = keys.get(key);
            return a == null ? -1 : a[0];
        }
    }

    /**
     * Гистограмма живых объектов (сама делает полную сборку): файл {@code <dir>/histo-<name>.txt} целиком; {@code log} —
     * в лог строкой {@code tag} итог, ключевые классы (точное имя и с подклассами) и первые {@link #TOP} по байтам.
     */
    public static Histogram histogram(String tag, Path dir, String name, boolean log) throws Exception {
        long t = System.nanoTime();
        String text = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
        long took = (System.nanoTime() - t) / 1_000_000;
        Path file = dir.resolve("histo-" + name + ".txt");
        try {
            Files.writeString(file, text);
        } catch (IOException ex) {
            Incoming.LOG.warn("{}: {} не записан", tag, file, ex);
        }
        List<Row> rows = new ArrayList<>();
        long total = -1, totalBytes = -1;
        for (String line : text.split("\n")) {
            Matcher m = ROW.matcher(line);
            if (m.find()) {
                rows.add(new Row(m.group(3), Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
                continue;
            }
            m = TOTAL.matcher(line);
            if (m.find()) {
                total = Long.parseLong(m.group(1));
                totalBytes = Long.parseLong(m.group(2));
            }
        }
        if (log) {
            Incoming.LOG.info("{} histogram: всего {} шт., {} МБ, строк {} за {} мс → {}", tag, total, totalBytes >> 20, rows.size(), took,
                    dir.getParent().relativize(file));
        }
        Map<String, long[]> exact = new LinkedHashMap<>();
        for (String k : KEYS) exact.put(k, new long[2]);
        for (Row row : rows) {
            long[] a = exact.get(row.name);
            if (a != null) {
                a[0] += row.count;
                a[1] += row.bytes;
            }
        }
        Map<String, Map<String, long[]>> sub = subclasses(tag, rows);
        Map<String, long[]> keys = new LinkedHashMap<>();
        for (String k : KEYS) {
            long[] a = exact.get(k);
            long n = a[0], b = a[1];
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%d шт., %d Б", a[0], a[1]));
            Map<String, long[]> s = sub.get(k);
            if (s != null && !s.isEmpty()) {
                for (long[] v : s.values()) {
                    n += v[0];
                    b += v[1];
                }
                line.append(String.format(Locale.ROOT, "; с подклассами %d шт., %d Б: ", n, b));
                line.append(s.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, long[]> en) -> en.getValue()[1]).reversed()).limit(6)
                        .map(en -> en.getKey() + " " + en.getValue()[0]).collect(Collectors.joining(", ")));
                if (s.size() > 6) line.append(", … ещё ").append(s.size() - 6);
            }
            keys.put(k, new long[] {n, b});
            if (log) Incoming.LOG.info("{} class {}: {}", tag, k, line);
        }
        if (log) {
            for (int i = 0; i < Math.min(TOP, rows.size()); i++) {
                Row row = rows.get(i);
                Incoming.LOG.info("{} top {}: {} {} шт., {} Б ({} МБ)", tag, i + 1, row.name, row.count, row.bytes,
                        String.format(Locale.ROOT, "%.1f", row.bytes / 1048576.0));
            }
        }
        return new Histogram(total, totalBytes, keys, file);
    }

    /**
     * Подклассы ключевых классов среди строк гистограммы: у {@code Entity} (абстрактный) своих экземпляров нет, у
     * {@code LevelChunk} и {@code ProtoChunk} есть подклассы ({@code EmptyLevelChunk}, {@code ImposterProtoChunk}),
     * у {@code IntegratedServer} — сервер повтора Flashback. Имя — в класс загрузчиком игры без инициализации (у строк
     * гистограммы классы уже загружены); классы JDK, массивы и скрытые классы не разбираются.
     */
    private static Map<String, Map<String, long[]>> subclasses(String tag, List<Row> rows) {
        Map<String, Map<String, long[]>> out = new HashMap<>();
        ClassLoader loader = Entity.class.getClassLoader();
        Map<String, Class<?>> keys = new LinkedHashMap<>();
        for (String k : KEYS) {
            try {
                keys.put(k, Class.forName(k, false, loader));
            } catch (ClassNotFoundException | LinkageError ex) {
                Incoming.LOG.warn("{}: класса {} нет ({})", tag, k, ex.toString());
            }
        }
        long deadline = System.nanoTime() + RESOLVE_LIMIT_MS * 1_000_000;
        int resolved = 0, skipped = 0;
        for (Row row : rows) {
            String n = row.name;
            if (n.startsWith("[") || n.indexOf('/') >= 0 || n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                    || n.startsWith("sun.") || n.startsWith("com.sun.")) continue;
            if (System.nanoTime() > deadline) {
                skipped++;
                continue;
            }
            Class<?> c;
            try {
                c = Class.forName(n, false, loader);
            } catch (VirtualMachineError ex) {
                throw ex;
            } catch (Throwable ex) {
                continue; // не видно загрузчику игры (свой загрузчик у библиотеки) — не подкласс классов игры
            }
            resolved++;
            for (Map.Entry<String, Class<?>> k : keys.entrySet()) {
                if (c != k.getValue() && k.getValue().isAssignableFrom(c)) {
                    long[] a = out.computeIfAbsent(k.getKey(), x -> new LinkedHashMap<>()).computeIfAbsent(n, x -> new long[2]);
                    a[0] += row.count;
                    a[1] += row.bytes;
                }
            }
        }
        if (skipped > 0) Incoming.LOG.warn("{}: подклассы — разобрано {} имён, {} не успели за {} мс", tag, resolved, skipped, RESOLVE_LIMIT_MS);
        return out;
    }

    /** Снимок кучи живых объектов {@code <dir>/heap.hprof} (есть — с отметкой времени). */
    public static void dumpHeap(Path dir) {
        Path file = dir.resolve("heap.hprof");
        if (Files.exists(file)) file = dir.resolve("heap-" + System.currentTimeMillis() + ".hprof");
        long t = System.nanoTime();
        try {
            ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"), "dumpHeap",
                    new Object[] {file.toString(), true}, new String[] {String.class.getName(), boolean.class.getName()});
            Incoming.LOG.info("SCENARIO leak heap dump: {} МБ за {} с → {}", Files.size(file) >> 20, (System.nanoTime() - t) / NANOS, dir.getParent().relativize(file));
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO leak heap dump: снимок кучи не снят ({})", file, ex);
        }
    }

    /**
     * Выборка старых объектов JFR: запись в этом же процессе с {@code jdk.OldObjectSample} (стек выделения и
     * {@code cutoff} «infinity»). Сэмплы — выделения с начала записи, которые ещё живы; пути до корней GC считаются,
     * когда событие пишется — при остановке записи и при каждом {@link jdk.jfr.Recording#dump сбросе} (копия записи
     * берёт её настройки), как у {@code jcmd JFR.dump path-to-gc-roots=true}; с {@code cutoff} по умолчанию («0 ns»)
     * у сэмплов только тип и стек (проверено на JDK 21 с поколенческим ZGC: с «infinity» — цепочка полей и корень).
     * Ищет их поток VM на своём стеке — нужен {@code -XX:VMThreadStackSize} (см. {@link #PATH_STACK_KB}).
     * Сброс пишет в общий поток записи, поэтому файл следующего сброса содержит и события прошлых: выжимка берёт
     * события последнего (у всех событий одного сброса одно время начала).
     */
    public static final class OldObjects {
        private static final int DETAILS = 40, PER_HOLDER = 2, HOLDERS = 25, LOG_HOLDERS = 5, HOLDER_LINKS = 4, STACK = 8, CHAIN_HEAD = 8, CHAIN_TAIL = 14;
        private final jdk.jfr.Recording recording = new jdk.jfr.Recording();

        /** {@code paths} — искать пути до корней при сбросе (нужен стек потока VM не меньше {@link #PATH_STACK_KB}). */
        public OldObjects(boolean paths) {
            recording.setName("incoming-leak");
            recording.enable("jdk.OldObjectSample").withStackTrace().with("cutoff", paths ? "infinity" : "0 ns");
            recording.setToDisk(true);
            recording.start();
        }

        public void close() {
            recording.close();
        }

        private record Sample(String object, long size, long ageS, String root, List<String> chain, List<String> stack) {
            boolean world() {
                for (String m : WORLD_MARKS) {
                    if (object.contains(m) || root.contains(m)) return true;
                    for (String l : chain) if (l.contains(m)) return true;
                }
                return false;
            }

            /**
             * Кто держит: ближайшее к объекту статическое поле (звено «… : java.lang.Class Class Name: X» — поле класса X;
             * дальше к корню у него всегда загрузчик классов) и тип, на который оно ссылается; без статического поля —
             * последние звенья пути у корня и корень.
             */
            String holder() {
                for (int i = 1; i < chain.size(); i++) {
                    if (chain.get(i).contains("java.lang.Class Class Name: ")) return typeOf(chain.get(i - 1)) + " ← " + chain.get(i) + " (статическое поле)";
                }
                List<String> tail = new ArrayList<>();
                for (String l : chain.subList(Math.max(0, chain.size() - HOLDER_LINKS), chain.size())) tail.add(plain(l));
                return String.join(" ← ", tail) + " | корень " + root.replaceAll("\\d+", "#");
            }

            /** Номера в массивах и размеры — без чисел, иначе у каждого сэмпла свой держатель. */
            private static String plain(String link) {
                return link.replaceAll("\\[\\d+]", "[]").replaceAll("\\(\\d+\\)", "()").replaceAll("Size: \\d+", "Size: N");
            }

            /** Тип объекта в звене «поле : тип описание». */
            private static String typeOf(String link) {
                int k = link.indexOf(" : ");
                String t = k < 0 ? link : link.substring(k + 3);
                int s = t.indexOf(' ');
                return plain(s < 0 ? t : t.substring(0, s));
            }
        }

        /** Сброс в {@code <dir>/old-<name>.jfr}, выжимка — в {@code old-<name>.txt}, держатели — строками {@code tag} в лог. */
        public void dump(String tag, Path dir, String name) throws IOException {
            Path file = dir.resolve("old-" + name + ".jfr");
            long t = System.nanoTime();
            recording.dump(file);
            long took = (System.nanoTime() - t) / 1_000_000;
            List<jdk.jfr.consumer.RecordedEvent> all = new ArrayList<>();
            Instant last = Instant.MIN;
            for (jdk.jfr.consumer.RecordedEvent e : jdk.jfr.consumer.RecordingFile.readAllEvents(file)) {
                if (!e.getEventType().getName().equals("jdk.OldObjectSample")) continue;
                all.add(e);
                if (e.getStartTime().isAfter(last)) last = e.getStartTime();
            }
            List<Sample> samples = new ArrayList<>();
            int broken = 0;
            for (jdk.jfr.consumer.RecordedEvent e : all) {
                if (!e.getStartTime().equals(last)) continue;
                try {
                    samples.add(sample(e));
                } catch (RuntimeException ex) {
                    broken++;
                }
            }
            List<Sample> world = samples.stream().filter(Sample::world).toList();
            long rooted = samples.stream().filter(s -> !s.root.equals("N/A")).count();
            Map<String, List<Sample>> holders = new LinkedHashMap<>();
            for (Sample s : world) holders.computeIfAbsent(s.holder(), k -> new ArrayList<>()).add(s);
            List<Map.Entry<String, List<Sample>>> byCount = holders.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, List<Sample>> en) -> en.getValue().size()).reversed()).toList();
            Map<String, Integer> roots = new LinkedHashMap<>();
            for (Sample s : samples) roots.merge(s.root, 1, Integer::sum);

            List<String> out = new ArrayList<>();
            out.add(String.format(Locale.ROOT, "Выборка старых объектов JFR, замер %s: сэмплов %d (сброс %d мс), с путём до корня %d, через мир %d (%s)%s",
                    name, samples.size(), took, rooted, world.size(), String.join(", ", WORLD_MARKS), broken > 0 ? ", не разобрано " + broken : ""));
            out.add("");
            out.add("Корни, все сэмплы:");
            roots.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(en -> out.add(String.format(Locale.ROOT, "  %4d × %s", en.getValue(), en.getKey())));
            out.add("");
            out.add("Держатели сэмплов через мир (ближайшее статическое поле и тип, на который оно ссылается, иначе последние " + HOLDER_LINKS
                    + " звена пути и корень), по числу сэмплов, всего " + byCount.size() + ":");
            for (int i = 0; i < Math.min(HOLDERS, byCount.size()); i++) {
                out.add(String.format(Locale.ROOT, "  %4d × %s", byCount.get(i).getValue().size(), byCount.get(i).getKey()));
            }
            out.add("");
            out.add("Сэмплы через мир — по " + PER_HOLDER + " на держателя, до " + DETAILS + " (путь — от объекта к корню, как у jfr print):");
            int shown = 0;
            for (Map.Entry<String, List<Sample>> en : byCount) {
                for (Sample s : en.getValue().subList(0, Math.min(PER_HOLDER, en.getValue().size()))) {
                    if (shown == DETAILS) break;
                    out.add(String.format(Locale.ROOT, "#%d %s, %d Б, возраст %d с, корень %s", ++shown, s.object, s.size, s.ageS, s.root));
                    List<String> chain = s.chain;
                    if (chain.size() > CHAIN_HEAD + CHAIN_TAIL) {
                        List<String> cut = new ArrayList<>(chain.subList(0, CHAIN_HEAD));
                        cut.add("… ещё " + (chain.size() - CHAIN_HEAD - CHAIN_TAIL) + " звеньев");
                        cut.addAll(chain.subList(chain.size() - CHAIN_TAIL, chain.size()));
                        chain = cut;
                    }
                    for (String l : chain) out.add("    " + l);
                    if (!s.stack.isEmpty()) out.add("    стек выделения: " + String.join(" ← ", s.stack));
                }
            }
            if (shown < world.size()) out.add("… показано " + shown + " из " + world.size());
            Path txt = dir.resolve("old-" + name + ".txt");
            Files.write(txt, out);
            Incoming.LOG.info("{} jfr: сэмплов {}, с путём до корня {}, через мир {}, держателей {} (сброс {} мс) → {}, {}", tag, samples.size(),
                    rooted, world.size(), byCount.size(), took, dir.getParent().relativize(file), dir.getParent().relativize(txt));
            for (int i = 0; i < Math.min(LOG_HOLDERS, byCount.size()); i++) {
                Incoming.LOG.info("{} holder {} × {}", tag, byCount.get(i).getValue().size(), byCount.get(i).getKey());
            }
        }

        private static Sample sample(jdk.jfr.consumer.RecordedEvent e) {
            jdk.jfr.consumer.RecordedObject object = e.getValue("object");
            Number elements = e.getValue("arrayElements");
            List<String> chain = new ArrayList<>();
            String type = object == null ? "?" : name(object, elements == null ? -1 : elements.longValue());
            chain.add(type);
            jdk.jfr.consumer.RecordedObject ref = object == null ? null : object.getValue("referrer");
            while (ref != null) {
                Number skip = ref.getValue("skip");
                if (skip != null && skip.longValue() > 0) chain.add("… (" + skip + " звеньев пропущено)");
                String via = "?";
                long size = -1;
                jdk.jfr.consumer.RecordedObject array = ref.getValue("array");
                if (array != null) {
                    via = "[" + array.getValue("index") + "]";
                    Number n = array.getValue("size");
                    size = n == null ? -1 : n.longValue();
                }
                jdk.jfr.consumer.RecordedObject field = ref.getValue("field");
                if (field != null) via = field.getString("name");
                jdk.jfr.consumer.RecordedObject holder = ref.getValue("object");
                chain.add(via + " : " + (holder == null ? "?" : name(holder, size)));
                ref = holder == null ? null : holder.getValue("referrer");
            }
            jdk.jfr.consumer.RecordedObject root = e.getValue("root");
            String rootText = "N/A";
            if (root != null) {
                String d = root.getString("description");
                rootText = root.getValue("system") + " / " + root.getValue("type") + (d == null ? "" : " «" + d + "»");
            }
            List<String> stack = new ArrayList<>();
            jdk.jfr.consumer.RecordedStackTrace st = e.getStackTrace();
            if (st != null) {
                for (jdk.jfr.consumer.RecordedFrame f : st.getFrames()) {
                    if (stack.size() == STACK) break;
                    jdk.jfr.consumer.RecordedMethod m = f.getMethod();
                    stack.add(m.getType().getName() + "." + m.getName() + ":" + f.getLineNumber());
                }
            }
            return new Sample(type, e.hasField("objectSize") ? e.getLong("objectSize") : -1, e.getDuration("objectAge").toSeconds(), rootText, chain, stack);
        }

        /** Тип объекта пути и описание (у класса — имя класса: держит статическое поле; у потока — имя потока). */
        private static String name(jdk.jfr.consumer.RecordedObject o, long arraySize) {
            jdk.jfr.consumer.RecordedClass c = o.getClass("type");
            String n = c == null ? "?" : c.getName();
            if (arraySize >= 0 && n.startsWith("[")) n = n + "(" + arraySize + ")";
            String d = o.getString("description");
            return d == null ? n : n + " " + d;
        }
    }

    private HeapProbe() {
    }
}
