package ua.zentix.replayscan;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Повтор для человека ({@code -Dreplayscan.view=<zip>}, README.md, «Просмотр»): после загрузки игра сама открывает
 * повтор и ставит его на тик {@code -Dreplayscan.at} на паузе; дальше — обычное окно Flashback. Строки
 * {@code Replay view: tick N} в журнале раз в 10 секунд показывают, что повтор идёт.
 */
final class View {
    private enum Phase { WAIT, OPENING, OPEN }

    private final FlashbackLink link;
    private final Path zip;
    private final int at;
    private final Logger log;
    private Phase phase = Phase.WAIT;
    private long lastLog;

    private View(FlashbackLink link, Path zip, int at, Logger log) {
        this.link = link;
        this.zip = zip;
        this.at = at;
        this.log = log;
    }

    static void start(Path zip, int at, Logger log) {
        try {
            View view = new View(FlashbackLink.find(), zip, at, log);
            NeoForge.EVENT_BUS.addListener(view::clientTick);
            log.info("Replay view: {} at tick {}", zip, at);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.error("Replay view: Flashback 0.39 is required ({})", e.toString());
        }
    }

    private void clientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        try {
            switch (phase) {
                case WAIT -> {
                    // заглавие на месте и загрузка кончилась
                    if (mc.getOverlay() != null || mc.screen == null || mc.level != null) return;
                    link.open(zip);
                    phase = Phase.OPENING;
                }
                case OPENING -> {
                    MinecraftServer s = link.server();
                    if (s == null || mc.level == null || !link.isReplayServer(s)) return;
                    if (at > 0) link.goTo(s, at);
                    link.setPaused(s, true);
                    phase = Phase.OPEN;
                }
                case OPEN -> {
                    MinecraftServer s = link.server();
                    long now = System.nanoTime();
                    if (s == null || !link.isReplayServer(s) || now - lastLog < 10_000_000_000L) return;
                    lastLog = now;
                    log.info("Replay view: tick {}{}", link.tick(s), link.paused(s) ? " (paused)" : "");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.error("Replay view failed", ex);
            phase = Phase.OPEN;
        }
    }
}
