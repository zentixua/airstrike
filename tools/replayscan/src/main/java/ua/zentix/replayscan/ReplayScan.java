package ua.zentix.replayscan;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import ua.zentix.replayscan.film.Film;

import java.nio.file.Path;

/**
 * Разбор повтора Flashback без окна (README.md): после загрузки клиент открывает повтор, пускает его с
 * {@link Settings#speed} и не рисует; сервер повтора в конце каждого тика отдаёт мир {@link Scan}; дошёл до конца —
 * таблицы закрыты, игра выходит. С {@code -Dreplayscan.film} — съёмка по плану ({@link Film}); без этих свойств мод
 * ничего не делает.
 */
@Mod(value = "replayscan", dist = Dist.CLIENT)
public final class ReplayScan {
    static final Logger LOG = LogUtils.getLogger();
    /** Сколько секунд без хода повтора — конец с ошибкой (meta.json: complete — false). */
    private static final int STALL_SECONDS = 300;

    private enum Phase { WAIT, OPENING, PLAYING, CLOSING }

    private final Settings settings;
    private FlashbackLink link;
    private volatile Phase phase = Phase.WAIT;
    private volatile Scan scan;
    private volatile Throwable failure;
    private long lastProgress = System.nanoTime(), lastLog = System.nanoTime();
    private int lastTick = -1;

    public ReplayScan() {
        // съёмка по плану (README.md, «Съёмка») — вместо разбора
        String film = System.getProperty("replayscan.film");
        if (film != null && !film.isBlank()) {
            settings = null;
            Film.start(Path.of(film));
            return;
        }
        settings = Settings.read(FMLPaths.GAMEDIR.get());
        if (settings == null) return;
        try {
            link = FlashbackLink.find();
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.error("Replay scan: Flashback 0.39 is required ({})", e.toString());
            return;
        }
        LOG.info("Replay scan: {} -> {} at {} ticks/s", settings.replay(), settings.out(), settings.speed());
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
    }

    private void clientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        try {
            switch (phase) {
                case WAIT -> {
                    // загрузка кончилась: заглавие или экран поверх него (первый запуск — экран специальных
                    // возможностей, у модов — свои), повтор их сменит
                    if (mc.getOverlay() != null || mc.screen == null || mc.level != null) {
                        stall(mc, "title screen did not appear");
                        if (failure != null) {
                            phase = Phase.CLOSING;
                            finish();
                        }
                        return;
                    }
                    // без паузы при потере фокуса (окна под xvfb нет), прорисовка — как у записи и не меньше
                    mc.options.onboardAccessibility = false;
                    mc.options.pauseOnLostFocus = false;
                    mc.options.renderDistance().set(32);
                    mc.options.save();
                    link.open(settings.replay());
                    phase = Phase.OPENING;
                    lastProgress = System.nanoTime();
                }
                case OPENING -> {
                    MinecraftServer s = link.server();
                    if (s == null || mc.level == null || !link.isReplayServer(s)) {
                        stall(mc, "replay did not open");
                        return;
                    }
                    link.setSpeed(s, settings.speed());
                    if (settings.from() > 0) link.goTo(s, Math.max(0, settings.from() - 1));
                    link.setPaused(s, false);
                    phase = Phase.PLAYING;
                    lastProgress = System.nanoTime();
                }
                case PLAYING -> {
                    mc.noRender = true;
                    if (mc.screen instanceof PauseScreen) mc.setScreen(null);
                    MinecraftServer s = link.server();
                    Scan sc = scan;
                    if (failure != null || (sc != null && sc.done()) || s == null) {
                        if (failure != null) LOG.error("Replay scan failed", failure);
                        phase = Phase.CLOSING;
                        if (s != null) s.execute(this::finish);
                        else finish();
                        return;
                    }
                    if (link.paused(s)) link.setPaused(s, false);
                    int tick = sc == null ? -1 : sc.last();
                    long now = System.nanoTime();
                    if (tick != lastTick) {
                        lastTick = tick;
                        lastProgress = now;
                    } else {
                        stall(mc, "replay stopped at tick " + tick);
                    }
                    if (now - lastLog > 30_000_000_000L && sc != null) {
                        lastLog = now;
                        LOG.info("Replay scan: tick {} of {}", tick, sc.total());
                    }
                }
                case CLOSING -> {
                    if (scan == null) mc.stop();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            failure = ex;
            LOG.error("Replay scan failed", ex);
            phase = Phase.CLOSING;
            finish();
        }
    }

    private void stall(Minecraft mc, String what) {
        if (System.nanoTime() - lastProgress > STALL_SECONDS * 1_000_000_000L) {
            failure = new IllegalStateException(what + " for " + STALL_SECONDS + " s");
            LOG.error("Replay scan: {}", failure.getMessage());
        }
    }

    private void serverTick(ServerTickEvent.Post e) {
        MinecraftServer s = e.getServer();
        if (phase != Phase.PLAYING || !link.isReplayServer(s) || failure != null) return;
        try {
            if (scan == null) scan = new Scan(settings, link, LOG);
            if (!scan.done()) scan.tick(s, link.tick(s));
        } catch (Exception | StackOverflowError ex) {
            failure = ex;
        }
    }

    /** Таблицы и meta.json — в потоке сервера повтора (там же их писал разбор); потом клиент выходит. */
    private synchronized void finish() {
        Scan sc = scan;
        if (sc != null) {
            try {
                sc.close();
            } catch (Exception ex) {
                LOG.error("Replay scan: cannot write tables", ex);
            }
        }
        scan = null;
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }
}
