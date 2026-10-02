package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Повтор Flashback (сценарий {@code replay}: пуск, как в {@code launch}, с модами записи сборки pack/ и
 * {@code recordingControls.quicksave} в настройках Flashback): клиент выходит из мира, как кнопка «Сохранить и выйти»,
 * Flashback на выходе дописывает запись в zip в своей папке повторов; сценарий открывает этот повтор, ждёт мира
 * повтора, снимает кадры ({@code replay_NNNN.png}) и закрывает игру. Flashback — мод Fabric (через Sinytra Connector),
 * в сборке мода его нет: его методы — отражением. Строки лога: {@code SCENARIO replay saved|opened|frame|failed},
 * в конце {@code SCENARIO done}.
 */
final class ReplayCheck {
    /** Столько тиков ждать файла повтора после выхода и мира повтора после открытия. */
    private static final int SAVE_WAIT = 1200, OPEN_WAIT = 2400;
    /** Кадры повтора: первый — через столько тиков после открытия, дальше с шагом, всего столько. */
    private static final int FIRST_FRAME = 200, FRAME_STEP = 60, FRAMES = 3;

    private Class<?> flashback;
    private int ticks, openedAt = -1, frames;
    private Path saved;
    private boolean finished;

    ReplayCheck() {
        try {
            flashback = Class.forName("com.moulberry.flashback.Flashback");
        } catch (ClassNotFoundException e) {
            fail("Flashback не загружен");
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        // вне тика: выход из мира посреди события тика оставил бы остальным обработчикам мир null
        Minecraft.getInstance().tell(ReplayCheck::leave);
    }

    /** Как {@code PauseScreen.onDisconnect} у одиночной игры: на выходе Flashback кончает запись (quicksave — в zip сразу). */
    private static void leave() {
        Minecraft mc = Minecraft.getInstance();
        mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        Airstrike.LOG.info("SCENARIO replay left world");
    }

    private void onTick(ClientTickEvent.Post e) {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            if (saved == null) {
                if (mc.level != null) return;
                saved = newestReplay();
                if (saved != null) {
                    Airstrike.LOG.info("SCENARIO replay saved {} {} bytes after {} ticks", saved.getFileName(), Files.size(saved), ticks);
                    ticks = 0;
                    Path open = saved;
                    mc.tell(() -> {
                        try {
                            flashback.getMethod("openReplayWorld", Path.class).invoke(null, open);
                        } catch (ReflectiveOperationException ex) {
                            Airstrike.LOG.error("SCENARIO replay failed: openReplayWorld", ex);
                            finish();
                        }
                    });
                } else if (ticks > SAVE_WAIT) {
                    fail("повтора нет в " + replayFolder() + " через " + SAVE_WAIT + " тиков после выхода: " + list(replayFolder().getParent()));
                }
                return;
            }
            if (openedAt < 0) {
                if (mc.level != null && mc.player != null && (boolean) flashback.getMethod("isInReplay").invoke(null)) {
                    openedAt = ticks;
                    int all = 0, ours = 0;
                    for (var en : mc.level.entitiesForRendering()) {
                        all++;
                        if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getNamespace().equals(Airstrike.MOD_ID)) ours++;
                    }
                    Airstrike.LOG.info("SCENARIO replay opened after {} ticks: {} at {}, entities {} (airstrike {})", ticks,
                            mc.level.dimension().location(), mc.player.blockPosition().toShortString(), all, ours);
                } else if (ticks > OPEN_WAIT) {
                    fail("мир повтора не открылся за " + OPEN_WAIT + " тиков, экран " + (mc.screen == null ? "-" : mc.screen.getClass().getName()));
                }
                return;
            }
            int since = ticks - openedAt;
            if (since >= FIRST_FRAME && (since - FIRST_FRAME) % FRAME_STEP == 0) {
                Screenshot.grab(mc.gameDirectory, String.format("replay_%04d.png", since), mc.getMainRenderTarget(), c -> {});
                Airstrike.LOG.info("SCENARIO replay frame {} fps={} screen={}", since, mc.getFps(), mc.screen == null ? "-" : mc.screen.getClass().getSimpleName());
                if (++frames == FRAMES) finish();
            }
        } catch (ReflectiveOperationException | IOException ex) {
            Airstrike.LOG.error("SCENARIO replay failed", ex);
            finish();
        }
    }

    private Path replayFolder() throws ReflectiveOperationException {
        return (Path) flashback.getMethod("getReplayFolder").invoke(null);
    }

    private Path newestReplay() throws ReflectiveOperationException, IOException {
        Path dir = replayFolder();
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".zip")).max(Comparator.comparing(p -> p.toFile().lastModified())).orElse(null);
        }
    }

    /** Что лежит в папке данных Flashback (два уровня): чем кончилась запись, если повтора нет. */
    private static String list(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return "папки нет";
        try (Stream<Path> s = Files.walk(dir, 2)) {
            List<String> names = s.map(p -> dir.relativize(p).toString()).filter(n -> !n.isEmpty()).sorted().toList();
            return names.isEmpty() ? "пусто" : String.join(", ", names);
        }
    }

    private void fail(String why) {
        Airstrike.LOG.error("SCENARIO replay failed: {}", why);
        finish();
    }

    private void finish() {
        finished = true;
        Airstrike.LOG.info("SCENARIO done");
        Minecraft.getInstance().stop();
    }
}
