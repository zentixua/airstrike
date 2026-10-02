package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Утечка памяти мира (сценарий {@code leak}: {@code tools/prod_client.py leak --world …}, задание ноутбуку
 * {@code tools/laptop-jobs/leak-heap.md}). Игра 02.10.2026: после выхода из мира в меню живого на ~2 ГБ больше, чем
 * в меню без мира, второй мир в том же запуске начинается на ~0,6 ГБ выше, рост идёт и от одних телепортов; кто
 * держит — не видно. Мир после выхода должен уходить из памяти целиком, поэтому замер — в меню: держатель виден
 * за один прогон.
 *
 * <p>Заходы ({@code airstrike.leak.rounds}, по умолчанию 2): открыть мир → через {@link #START} тиков после входа
 * команды раунда ({@code airstrike.leak.commands}, через «;», как у сценария {@code commands}; от имени игрока
 * с правами оператора — в копии мира бывают выключены читы) через {@code airstrike.leak.gap} тиков (200) и столько же
 * после последней → выйти в главное меню, как кнопка «Сохранить и выйти» ({@code PauseScreen.onDisconnect}) → в меню
 * подождать {@code airstrike.leak.settle} секунд (20), две сборки мусора через {@link #GC_PAUSE_S} с и замер.
 * Замер 0 — меню до первого захода (база того же запуска). Мир — копия мира игрока ({@code airstrike.world}) или мир
 * сценария: первый заход создаёт его заново, следующие открывают тот же.
 *
 * <p>Замер в меню: строка {@code SCENARIO leak menu N: куча живых …}; гистограмма классов живых объектов
 * (MBean {@code DiagnosticCommand}, операция {@code gcClassHistogram} — то же, что {@code jcmd GC.class_histogram})
 * в {@code <каталог игры>/leak/histo-N.txt}, в лог — ключевые классы мира ({@link #KEYS}: точное имя и с подклассами)
 * и первые {@link #TOP} по байтам; сброс выборки старых объектов JFR ({@code jdk.OldObjectSample}) с путями до корней
 * GC в {@code leak/old-N.jfr} и выжимка сэмплов, чей путь проходит через мир ({@link #WORLD_MARKS}), в
 * {@code leak/old-N.txt}. После последнего замера, если {@code airstrike.leak.heapdump=true}, — снимок кучи живых
 * объектов {@code leak/heap.hprof}. Перед каждым выходом — {@code SCENARIO leak world N: куча живых …} (мир ещё
 * в памяти). Ошибка замера — WARN, сценарий идёт дальше до {@code SCENARIO done}.
 */
final class LeakCheck {
    /** Ключевые классы мира: после выхода в меню их экземпляров быть не должно. */
    private static final List<String> KEYS = List.of(
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
    /** Тиков в мире до первой команды. */
    private static final int START = 100;
    /** Пауза между двумя сборками в меню и самое долгое открытие мира, с. */
    private static final long GC_PAUSE_S = 5, OPEN_LIMIT_S = 900;
    /**
     * Пути до корней GC ищет поток VM обходом в глубину (JDK 21): на его стеке по умолчанию (1 МБ) глубокая цепочка
     * ссылок переполняет стек, и JVM падает по SIGSEGV без hs_err (облако 02.10.2026: игра — на первом же сбросе;
     * отдельная проверка — список в 2 млн звеньев: 1 МБ падает, 1,5 МБ и больше — нет). Со стеком меньше этого (КБ)
     * пути не ищутся.
     */
    private static final long PATH_STACK_KB = 4096;
    /** Сколько разбирать имена гистограммы в классы (подклассы ключевых), не дольше, мс. */
    private static final long RESOLVE_LIMIT_MS = 10_000;
    private static final long NANOS = 1_000_000_000L;
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
    private static final Pattern TOTAL = Pattern.compile("^Total\\s+(\\d+)\\s+(\\d+)");

    private enum Phase { TITLE, MENU, OPENING, WORLD, LEAVING, DONE }

    private final int rounds = Math.max(1, Integer.getInteger("airstrike.leak.rounds", 2));
    private final int gap = Math.max(1, Integer.getInteger("airstrike.leak.gap", 200));
    private final long settleNanos = Math.max(0, Integer.getInteger("airstrike.leak.settle", 20)) * NANOS;
    private final boolean heapDump = Boolean.getBoolean("airstrike.leak.heapdump");
    private final List<String> commands = ScenarioCommands.split(System.getProperty("airstrike.leak.commands", ""));
    /** Копия мира игрока; без неё — мир сценария. */
    private final String world = System.getProperty("airstrike.world");

    private Phase phase = Phase.TITLE;
    /** Номер захода: 0 — меню до первого захода, N — меню после выхода из захода N. */
    private int round;
    /** Начало ожидания в меню, открытия мира или выхода ({@link System#nanoTime()}). */
    private long since;
    private boolean firstGc;
    private int worldTicks;
    private Path dir;
    private OldObjects jfr;

    LeakCheck() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        switch (phase) {
            case TITLE -> {
                // главное меню после загрузки ресурсов: выборка JFR — с этого места, загрузка игры в неё не попадает
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) begin(mc);
            }
            case MENU -> menuTick(mc);
            case OPENING -> openingTick(mc);
            case WORLD -> worldTick(mc);
            case LEAVING, DONE -> {}
        }
    }

    private void begin(Minecraft mc) {
        phase = Phase.MENU;
        since = System.nanoTime();
        dir = mc.gameDirectory.toPath().resolve("leak");
        try {
            Files.createDirectories(dir);
        } catch (IOException | RuntimeException ex) {
            Airstrike.LOG.warn("SCENARIO leak: папка {} не создана — файлов замеров не будет", dir, ex);
        }
        long stack = vmThreadStackKb();
        boolean paths = stack >= PATH_STACK_KB;
        if (!paths) {
            Airstrike.LOG.warn("SCENARIO leak: стек потока VM {} КБ — пути до корней GC выключены (поиск на стеке по умолчанию роняет JVM по SIGSEGV), "
                    + "нужно -XX:VMThreadStackSize={} или больше", stack, PATH_STACK_KB);
        }
        try {
            jfr = new OldObjects(paths);
        } catch (Exception | LinkageError ex) {
            Airstrike.LOG.warn("SCENARIO leak: запись JFR не началась — выборки старых объектов не будет", ex);
        }
        Airstrike.LOG.info("SCENARIO leak start: заходов {}, мир {}, команд {} через {} тиков, в меню {} с, снимок кучи {}; Java {}, сборщик {}, куча до {} МБ, JFR {}",
                rounds, world == null ? ClientScenario.WORLD + " (мир сценария)" : world, commands.size(), gap, settleNanos / NANOS, heapDump,
                Runtime.version(), ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", ")),
                Runtime.getRuntime().maxMemory() >> 20, jfr == null ? "нет" : paths ? "пишет, с путями до корней" : "пишет, без путей до корней");
    }

    /** {@code -XX:VMThreadStackSize} в КБ (по умолчанию на Linux x64 — 1024), −1 — не узнать. */
    private static long vmThreadStackKb() {
        try {
            CompositeData d = (CompositeData) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption", new Object[] {"VMThreadStackSize"}, new String[] {String.class.getName()});
            return Long.parseLong(String.valueOf(d.get("value")));
        } catch (Exception | LinkageError ex) {
            Airstrike.LOG.warn("SCENARIO leak: VMThreadStackSize не узнать ({})", ex.toString());
            return -1;
        }
    }

    // --- меню ---

    private void menuTick(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.level != null || mc.getSingleplayerServer() != null) {
            since = now; // выход ещё не кончился
            return;
        }
        if (!firstGc) {
            if (now - since < settleNanos) return;
            gc();
            firstGc = true;
            since = now;
            return;
        }
        if (now - since < GC_PAUSE_S * NANOS) return;
        gc();
        firstGc = false;
        measure();
        if (round < rounds) {
            open(mc, ++round);
        } else {
            if (heapDump) dumpHeap();
            finish();
        }
    }

    private void measure() {
        int r = round;
        Airstrike.LOG.info("SCENARIO leak menu {}: куча живых {} МБ{}", r, heapUsedMb(), pools());
        try {
            histogram(r);
        } catch (Exception | LinkageError ex) {
            Airstrike.LOG.warn("SCENARIO leak menu {}: гистограммы нет", r, ex);
        }
        if (jfr != null) {
            try {
                jfr.dump(r, dir);
            } catch (Exception | LinkageError ex) {
                Airstrike.LOG.warn("SCENARIO leak menu {}: выборка JFR не сброшена", r, ex);
            }
        }
    }

    // --- заход в мир ---

    private void open(Minecraft mc, int r) {
        phase = Phase.OPENING;
        since = System.nanoTime();
        String name = world != null ? world : ClientScenario.WORLD;
        Airstrike.LOG.info("SCENARIO leak round {}: открываю мир {}{}", r, name, world == null && r == 1 ? " (создаю заново)" : "");
        // вне тика: загрузка мира крутит свой цикл кадров, как кнопка в меню
        mc.tell(() -> {
            try {
                if (world == null && r == 1) ClientScenario.createWorld(mc.screen != null ? mc.screen : new TitleScreen());
                else mc.createWorldOpenFlows().openWorld(name, () -> fail("мир " + name + " не открылся (захода " + r + ")"));
            } catch (RuntimeException ex) {
                Airstrike.LOG.error("SCENARIO leak round {}: мир не открылся", r, ex);
                fail("мир не открылся");
            }
        });
    }

    private void openingTick(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null) {
            phase = Phase.WORLD;
            worldTicks = 0;
            Airstrike.LOG.info("SCENARIO leak round {} entered after {} s: {} at {}", round, (now - since) / NANOS,
                    mc.level.dimension().location(), mc.player.blockPosition().toShortString());
        } else if (now - since > OPEN_LIMIT_S * NANOS) {
            fail("мир не открылся за " + OPEN_LIMIT_S + " с, экран " + (mc.screen == null ? "-" : mc.screen.getClass().getName()));
        }
    }

    private void worldTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            fail("мир закрылся сам посреди захода " + round + ", экран " + (mc.screen == null ? "-" : mc.screen.getClass().getName()));
            return;
        }
        worldTicks++;
        for (int i = 0; i < commands.size(); i++) {
            if (worldTicks == START + i * gap) run(mc, commands.get(i));
        }
        if (worldTicks < START + Math.max(1, commands.size()) * gap) return;
        phase = Phase.LEAVING;
        gc();
        Airstrike.LOG.info("SCENARIO leak world {}: куча живых {} МБ{}, чанков у клиента {}", round, heapUsedMb(), pools(),
                mc.level.getChunkSource().getLoadedChunksCount());
        // вне тика: выход посреди события тика оставил бы остальным обработчикам мир null
        mc.tell(() -> {
            leave(mc);
            since = System.nanoTime();
            phase = Phase.MENU;
        });
    }

    /** Команда раунда на сервере от имени игрока с правами оператора ({@code @s}, {@code ~} — игрок). */
    private void run(Minecraft mc, String c) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID id = mc.player.getUUID();
        int r = round;
        Airstrike.LOG.info("SCENARIO leak round {} /{}", r, c);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                Airstrike.LOG.warn("SCENARIO leak round {}: игрока нет на сервере — «{}» пропущена", r, c);
                return;
            }
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4), c);
        });
    }

    /** Как {@code PauseScreen.onDisconnect} у одиночной игры: {@code disconnect} ждёт, пока встроенный сервер остановится. */
    private void leave(Minecraft mc) {
        if (mc.level == null) return;
        long t = System.nanoTime();
        mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        Airstrike.LOG.info("SCENARIO leak round {} left world: выход {} мс", round, (System.nanoTime() - t) / 1_000_000);
    }

    private void fail(String why) {
        if (phase == Phase.DONE) return;
        Airstrike.LOG.error("SCENARIO leak failed: {}", why);
        finish();
    }

    private void finish() {
        phase = Phase.DONE;
        if (jfr != null) {
            try {
                jfr.close();
            } catch (Exception | LinkageError ex) {
                Airstrike.LOG.warn("SCENARIO leak: запись JFR не закрылась", ex);
            }
        }
        Airstrike.LOG.info("SCENARIO done");
        Minecraft.getInstance().stop();
    }

    // --- память ---

    private static void gc() {
        try {
            System.gc();
        } catch (RuntimeException ex) {
            Airstrike.LOG.warn("SCENARIO leak: System.gc() не прошёл", ex);
        }
    }

    private static long heapUsedMb() {
        try {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        } catch (RuntimeException ex) {
            Airstrike.LOG.warn("SCENARIO leak: MemoryMXBean не ответил", ex);
            return -1;
        }
    }

    /** Пулы кучи после последней сборки каждого (у поколенческого ZGC — старое и молодое поколение). */
    private static String pools() {
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

    /** Гистограмма живых объектов (сама делает полную сборку): файл целиком, в лог — ключевые классы, первые по байтам и итог. */
    private void histogram(int r) throws Exception {
        long t = System.nanoTime();
        String text = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
        long took = (System.nanoTime() - t) / 1_000_000;
        Path file = dir.resolve("histo-" + r + ".txt");
        try {
            Files.writeString(file, text);
        } catch (IOException ex) {
            Airstrike.LOG.warn("SCENARIO leak menu {}: {} не записан", r, file, ex);
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
        Airstrike.LOG.info("SCENARIO leak menu {} histogram: всего {} шт., {} МБ, строк {} за {} мс → {}", r, total, totalBytes >> 20, rows.size(), took,
                dir.getParent().relativize(file));
        Map<String, long[]> exact = new LinkedHashMap<>();
        for (String k : KEYS) exact.put(k, new long[2]);
        for (Row row : rows) {
            long[] a = exact.get(row.name);
            if (a != null) {
                a[0] += row.count;
                a[1] += row.bytes;
            }
        }
        Map<String, Map<String, long[]>> sub = subclasses(r, rows);
        for (String k : KEYS) {
            long[] a = exact.get(k);
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%d шт., %d Б", a[0], a[1]));
            Map<String, long[]> s = sub.get(k);
            if (s != null && !s.isEmpty()) {
                long n = a[0], b = a[1];
                for (long[] v : s.values()) {
                    n += v[0];
                    b += v[1];
                }
                line.append(String.format(Locale.ROOT, "; с подклассами %d шт., %d Б: ", n, b));
                line.append(s.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, long[]> en) -> en.getValue()[1]).reversed()).limit(6)
                        .map(en -> en.getKey() + " " + en.getValue()[0]).collect(Collectors.joining(", ")));
                if (s.size() > 6) line.append(", … ещё ").append(s.size() - 6);
            }
            Airstrike.LOG.info("SCENARIO leak menu {} class {}: {}", r, k, line);
        }
        for (int i = 0; i < Math.min(TOP, rows.size()); i++) {
            Row row = rows.get(i);
            Airstrike.LOG.info("SCENARIO leak menu {} top {}: {} {} шт., {} Б ({} МБ)", r, i + 1, row.name, row.count, row.bytes,
                    String.format(Locale.ROOT, "%.1f", row.bytes / 1048576.0));
        }
    }

    /**
     * Подклассы ключевых классов среди строк гистограммы: у {@code Entity} (абстрактный) своих экземпляров нет, у
     * {@code LevelChunk} и {@code ProtoChunk} есть подклассы ({@code EmptyLevelChunk}, {@code ImposterProtoChunk}),
     * у {@code IntegratedServer} — сервер повтора Flashback. Имя — в класс загрузчиком игры без инициализации (у строк
     * гистограммы классы уже загружены); классы JDK, массивы и скрытые классы не разбираются.
     */
    private static Map<String, Map<String, long[]>> subclasses(int r, List<Row> rows) {
        Map<String, Map<String, long[]>> out = new HashMap<>();
        ClassLoader loader = Entity.class.getClassLoader();
        Map<String, Class<?>> keys = new LinkedHashMap<>();
        for (String k : KEYS) {
            try {
                keys.put(k, Class.forName(k, false, loader));
            } catch (ClassNotFoundException | LinkageError ex) {
                Airstrike.LOG.warn("SCENARIO leak menu {}: класса {} нет ({})", r, k, ex.toString());
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
        if (skipped > 0) Airstrike.LOG.warn("SCENARIO leak menu {}: подклассы — разобрано {} имён, {} не успели за {} мс", r, resolved, skipped, RESOLVE_LIMIT_MS);
        return out;
    }

    private void dumpHeap() {
        Path file = dir.resolve("heap.hprof");
        if (Files.exists(file)) file = dir.resolve("heap-" + System.currentTimeMillis() + ".hprof");
        long t = System.nanoTime();
        try {
            ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"), "dumpHeap",
                    new Object[] {file.toString(), true}, new String[] {String.class.getName(), boolean.class.getName()});
            Airstrike.LOG.info("SCENARIO leak heap dump: {} МБ за {} с → {}", Files.size(file) >> 20, (System.nanoTime() - t) / NANOS, dir.getParent().relativize(file));
        } catch (Exception | LinkageError ex) {
            Airstrike.LOG.warn("SCENARIO leak heap dump: снимок кучи не снят ({})", file, ex);
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
    private static final class OldObjects {
        private static final int DETAILS = 40, PER_HOLDER = 2, HOLDERS = 25, LOG_HOLDERS = 5, HOLDER_LINKS = 4, STACK = 8, CHAIN_HEAD = 8, CHAIN_TAIL = 14;
        private final jdk.jfr.Recording recording = new jdk.jfr.Recording();

        /** {@code paths} — искать пути до корней при сбросе (нужен стек потока VM не меньше {@link #PATH_STACK_KB}). */
        OldObjects(boolean paths) {
            recording.setName("airstrike-leak");
            recording.enable("jdk.OldObjectSample").withStackTrace().with("cutoff", paths ? "infinity" : "0 ns");
            recording.setToDisk(true);
            recording.start();
        }

        void close() {
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

        void dump(int r, Path dir) throws IOException {
            Path file = dir.resolve("old-" + r + ".jfr");
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
            out.add(String.format(Locale.ROOT, "Выборка старых объектов JFR, замер %d: сэмплов %d (сброс %d мс), с путём до корня %d, через мир %d (%s)%s",
                    r, samples.size(), took, rooted, world.size(), String.join(", ", WORLD_MARKS), broken > 0 ? ", не разобрано " + broken : ""));
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
            Path txt = dir.resolve("old-" + r + ".txt");
            Files.write(txt, out);
            Airstrike.LOG.info("SCENARIO leak menu {} jfr: сэмплов {}, с путём до корня {}, через мир {}, держателей {} (сброс {} мс) → {}, {}", r, samples.size(),
                    rooted, world.size(), byCount.size(), took, dir.getParent().relativize(file), dir.getParent().relativize(txt));
            for (int i = 0; i < Math.min(LOG_HOLDERS, byCount.size()); i++) {
                Airstrike.LOG.info("SCENARIO leak menu {} holder {} × {}", r, byCount.get(i).getValue().size(), byCount.get(i).getKey());
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
}
