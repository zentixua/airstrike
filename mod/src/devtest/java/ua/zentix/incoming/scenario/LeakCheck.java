package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Утечка памяти мира (сценарий {@code leak}: {@code tools/prod_client.py leak --world …}, задание ноутбуку
 * {@code tools/laptop-jobs/leak-heap.md}). Утечка видна так: после выхода из мира в меню живого больше, чем
 * в меню без мира, второй мир в том же запуске начинается выше, рост идёт и от одних телепортов; кто
 * держит — не видно. Мир после выхода должен уходить из памяти целиком, поэтому замер — в меню: держатель виден
 * за один прогон.
 *
 * <p>Заходы ({@code incoming.leak.rounds}, по умолчанию 2): открыть мир → через {@link #START} тиков после входа
 * команды раунда ({@code incoming.leak.commands}, через «;», как у сценария {@code commands}; от имени игрока
 * с правами оператора — в копии мира бывают выключены читы) через {@code incoming.leak.gap} тиков (200) и столько же
 * после последней → выйти в главное меню, как кнопка «Сохранить и выйти» ({@code PauseScreen.onDisconnect}) → в меню
 * подождать {@code incoming.leak.settle} секунд (20), две сборки мусора через {@link #GC_PAUSE_S} с и замер.
 * Замер 0 — меню до первого захода (база того же запуска). Мир — копия мира игрока ({@code incoming.world}) или мир
 * сценария: первый заход создаёт его заново, следующие открывают тот же.
 *
 * <p>Замер в меню: строка {@code SCENARIO leak menu N: куча живых …}; гистограмма классов живых объектов
 * (MBean {@code DiagnosticCommand}, операция {@code gcClassHistogram} — то же, что {@code jcmd GC.class_histogram})
 * в {@code <каталог игры>/leak/histo-N.txt}, в лог — ключевые классы мира ({@link HeapProbe#KEYS}: точное имя и с подклассами)
 * и первые 15 по байтам ({@link HeapProbe}); сброс выборки старых объектов JFR ({@code jdk.OldObjectSample}) с путями до корней
 * GC в {@code leak/old-N.jfr} и выжимка сэмплов, чей путь проходит через мир ({@code HeapProbe.WORLD_MARKS}), в
 * {@code leak/old-N.txt}. После последнего замера, если {@code incoming.leak.heapdump=true}, — снимок кучи живых
 * объектов {@code leak/heap.hprof}. Перед каждым выходом — {@code SCENARIO leak world N: куча живых …} (мир ещё
 * в памяти). Ошибка замера — WARN, сценарий идёт дальше до {@code SCENARIO done}.
 */
final class LeakCheck {
    /** Тиков в мире до первой команды. */
    private static final int START = 100;
    /** Пауза между двумя сборками в меню и самое долгое открытие мира, с. */
    private static final long GC_PAUSE_S = 5, OPEN_LIMIT_S = 900;
    private static final long NANOS = 1_000_000_000L;

    private enum Phase { TITLE, MENU, OPENING, WORLD, LEAVING, DONE }

    private final int rounds = Math.max(1, Integer.getInteger("incoming.leak.rounds", 2));
    private final int gap = Math.max(1, Integer.getInteger("incoming.leak.gap", 200));
    private final long settleNanos = Math.max(0, Integer.getInteger("incoming.leak.settle", 20)) * NANOS;
    private final boolean heapDump = Boolean.getBoolean("incoming.leak.heapdump");
    private final List<String> commands = ScenarioCommands.split(System.getProperty("incoming.leak.commands", ""));
    /** Копия мира игрока; без неё — мир сценария. */
    private final String world = System.getProperty("incoming.world");

    private Phase phase = Phase.TITLE;
    /** Номер захода: 0 — меню до первого захода, N — меню после выхода из захода N. */
    private int round;
    /** Начало ожидания в меню, открытия мира или выхода ({@link System#nanoTime()}). */
    private long since;
    private boolean firstGc;
    private int worldTicks;
    private Path dir;
    private HeapProbe.OldObjects jfr;

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
            Incoming.LOG.warn("SCENARIO leak: папка {} не создана — файлов замеров не будет", dir, ex);
        }
        long stack = HeapProbe.vmThreadStackKb();
        boolean paths = stack >= HeapProbe.PATH_STACK_KB;
        if (!paths) {
            Incoming.LOG.warn("SCENARIO leak: стек потока VM {} КБ — пути до корней GC выключены (поиск на стеке по умолчанию роняет JVM по SIGSEGV), "
                    + "нужно -XX:VMThreadStackSize={} или больше", stack, HeapProbe.PATH_STACK_KB);
        }
        try {
            jfr = new HeapProbe.OldObjects(paths);
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO leak: запись JFR не началась — выборки старых объектов не будет", ex);
        }
        Incoming.LOG.info("SCENARIO leak start: заходов {}, мир {}, команд {} через {} тиков, в меню {} с, снимок кучи {}; Java {}, сборщик {}, куча до {} МБ, JFR {}",
                rounds, world == null ? ClientScenario.WORLD + " (мир сценария)" : world, commands.size(), gap, settleNanos / NANOS, heapDump,
                Runtime.version(), ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", ")),
                Runtime.getRuntime().maxMemory() >> 20, jfr == null ? "нет" : paths ? "пишет, с путями до корней" : "пишет, без путей до корней");
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
            HeapProbe.gc();
            firstGc = true;
            since = now;
            return;
        }
        if (now - since < GC_PAUSE_S * NANOS) return;
        HeapProbe.gc();
        firstGc = false;
        measure();
        if (round < rounds) {
            open(mc, ++round);
        } else {
            if (heapDump) HeapProbe.dumpHeap(dir);
            finish();
        }
    }

    private void measure() {
        int r = round;
        Incoming.LOG.info("SCENARIO leak menu {}: куча живых {} МБ{}", r, HeapProbe.heapUsedMb(), HeapProbe.pools());
        try {
            HeapProbe.histogram("SCENARIO leak menu " + r, dir, String.valueOf(r), true);
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO leak menu {}: гистограммы нет", r, ex);
        }
        if (jfr != null) {
            try {
                jfr.dump("SCENARIO leak menu " + r, dir, String.valueOf(r));
            } catch (Exception | LinkageError ex) {
                Incoming.LOG.warn("SCENARIO leak menu {}: выборка JFR не сброшена", r, ex);
            }
        }
    }

    // --- заход в мир ---

    private void open(Minecraft mc, int r) {
        phase = Phase.OPENING;
        since = System.nanoTime();
        String name = world != null ? world : ClientScenario.WORLD;
        Incoming.LOG.info("SCENARIO leak round {}: открываю мир {}{}", r, name, world == null && r == 1 ? " (создаю заново)" : "");
        // вне тика: загрузка мира крутит свой цикл кадров, как кнопка в меню
        mc.tell(() -> {
            try {
                if (world == null && r == 1) ClientScenario.createWorld(mc.screen != null ? mc.screen : new TitleScreen());
                else mc.createWorldOpenFlows().openWorld(name, () -> fail("мир " + name + " не открылся (захода " + r + ")"));
            } catch (RuntimeException ex) {
                Incoming.LOG.error("SCENARIO leak round {}: мир не открылся", r, ex);
                fail("мир не открылся");
            }
        });
    }

    private void openingTick(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null) {
            phase = Phase.WORLD;
            worldTicks = 0;
            Incoming.LOG.info("SCENARIO leak round {} entered after {} s: {} at {}", round, (now - since) / NANOS,
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
        HeapProbe.gc();
        Incoming.LOG.info("SCENARIO leak world {}: куча живых {} МБ{}, чанков у клиента {}", round, HeapProbe.heapUsedMb(), HeapProbe.pools(),
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
        Incoming.LOG.info("SCENARIO leak round {} /{}", r, c);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                Incoming.LOG.warn("SCENARIO leak round {}: игрока нет на сервере — «{}» пропущена", r, c);
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
        Incoming.LOG.info("SCENARIO leak round {} left world: выход {} мс", round, (System.nanoTime() - t) / 1_000_000);
    }

    private void fail(String why) {
        if (phase == Phase.DONE) return;
        Incoming.LOG.error("SCENARIO leak failed: {}", why);
        finish();
    }

    private void finish() {
        phase = Phase.DONE;
        if (jfr != null) {
            try {
                jfr.close();
            } catch (Exception | LinkageError ex) {
                Incoming.LOG.warn("SCENARIO leak: запись JFR не закрылась", ex);
            }
        }
        Incoming.LOG.info("SCENARIO done");
        Minecraft.getInstance().stop();
    }
}
