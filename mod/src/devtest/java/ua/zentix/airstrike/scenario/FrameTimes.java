package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Время кадров клиента сценария ({@code -Dairstrike.frametimes=true}): каждый кадр в игре — строка «тик мс» в
 * logs/frametimes.txt (тик — игровое время мира, по нему видно, что шло в кадре). Сводку (средний FPS, 1% худших,
 * худший кадр) считают по файлу, например за время залпа.
 */
final class FrameTimes {
    private final Writer out;
    private long last;

    private FrameTimes(Writer out) {
        this.out = out;
    }

    static void startIfRequested() {
        if (!Boolean.getBoolean("airstrike.frametimes")) return;
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("frametimes.txt");
        try {
            Files.createDirectories(file.getParent());
            FrameTimes f = new FrameTimes(Files.newBufferedWriter(file));
            NeoForge.EVENT_BUS.addListener(f::onFrame);
            Runtime.getRuntime().addShutdownHook(new Thread(f::close));
        } catch (IOException e) {
            Airstrike.LOG.warn("SCENARIO frametimes: не открыть {}", file, e);
        }
    }

    private void onFrame(RenderFrameEvent.Post e) {
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (last != 0 && mc.level != null) {
            try {
                out.write(mc.level.getGameTime() + " " + (now - last) / 1000 / 1000.0 + "\n");
            } catch (IOException ignored) {
                // запись по кадрам — проверка, не игра: пропущенная строка не важна
            }
        }
        last = now;
    }

    private void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // выход из игры
        }
    }
}
