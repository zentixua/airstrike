package ua.zentix.airstrike.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.cam.ProjectileCamera;
import ua.zentix.airstrike.client.nuclear.NukeView;
import ua.zentix.airstrike.entity.FlightPhase;

import java.util.List;
import java.util.Locale;

/**
 * Свои снаряды в полёте — у того, кто пустил: справа сверху список (что, фаза полёта, время до удара), а в мире —
 * метки: ромб на снаряде с номером и временем и крестик на его цели. Время знает сервер (маршрут в обход и полёт
 * вне загруженного мира), клиент лишь досчитывает его между пакетами.
 */
public final class StrikesHud {
    private static final int MAX_LINES = 6;

    private StrikesHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null || mc.player == null || !AirstrikeConfig.CLIENT.hud.get()) return;
        List<ClientFlights.Tracked> flights = ClientFlights.all();
        if (flights.isEmpty()) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        if (!ProjectileCamera.isActive()) markers(g, mc.font, flights, pt);

        Font font = mc.font;
        int x = g.guiWidth() - 6, y = 6;
        Component title = Component.translatable("airstrike.hud.in_flight", flights.size()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        g.drawString(font, title, x - font.width(title), y, 0xFFFFFFFF);
        y += 11;
        for (int i = 0; i < Math.min(MAX_LINES, flights.size()); i++) {
            Component t = line(flights.get(i), pt);
            g.drawString(font, t, x - font.width(t), y, 0xFFFFFFFF);
            y += 10;
        }
        if (flights.size() > MAX_LINES) {
            Component more = Component.translatable("airstrike.hud.more", flights.size() - MAX_LINES).withStyle(ChatFormatting.GRAY);
            g.drawString(font, more, x - font.width(more), y, 0xFFFFFFFF);
            y += 10;
        }
        Component hint = Component.translatable("airstrike.hud.camera_hint", ua.zentix.airstrike.client.Keys.CAMERA.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.DARK_GRAY);
        g.drawString(font, hint, x - font.width(hint), y + 2, 0xFFFFFFFF);
    }

    /** «Шахед №2 · маршрут · 0:41». */
    static Component line(ClientFlights.Tracked f, float pt) {
        MutableComponent name = Component.translatable("airstrike.hud.flight", f.weapon().displayName(), f.number);
        if (f.nuclear()) name = Component.literal("☢ ").withStyle(ChatFormatting.YELLOW).append(name);
        Component phase = Component.translatable("airstrike.phase." + f.phase().getSerializedName());
        ChatFormatting color = switch (f.phase()) {
            case READY, IGNITION, BOOST -> ChatFormatting.GOLD;
            case TERMINAL, POP_UP -> ChatFormatting.RED;
            default -> ChatFormatting.YELLOW;
        };
        return Component.translatable("airstrike.hud.flight_line", name, phase, clock(f.etaSeconds(pt))).withStyle(color);
    }

    public static String clock(double seconds) {
        int s = (int) Math.ceil(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Метки в мире: ромб на снаряде (номер и время), крестик на цели. За спиной — не рисуются. */
    private static void markers(GuiGraphics g, Font font, List<ClientFlights.Tracked> flights, float pt) {
        int w = g.guiWidth(), h = g.guiHeight();
        for (ClientFlights.Tracked f : flights) {
            if (f.phase() == FlightPhase.READY) continue;
            float[] s = NukeView.project(f.position(pt));
            if (s != null) {
                int sx = (int) (s[0] * w), sy = (int) (s[1] * h);
                if (sx > -20 && sx < w + 20 && sy > -20 && sy < h + 20) {
                    int c = f.phase() == FlightPhase.TERMINAL ? 0xFFFF4030 : 0xFFFFC040;
                    diamond(g, sx, sy, 4, c);
                    String label = "№" + f.number + " " + clock(f.etaSeconds(pt));
                    g.drawString(font, label, sx - font.width(label) / 2, sy - 14, c);
                }
            }
            float[] t = NukeView.project(f.target());
            if (t != null) {
                int tx = (int) (t[0] * w), ty = (int) (t[1] * h);
                if (tx > 0 && tx < w && ty > 0 && ty < h) {
                    int c = 0xC0FF3030;
                    for (int i = -4; i <= 4; i++) {
                        g.fill(tx + i, ty + i, tx + i + 1, ty + i + 1, c);
                        g.fill(tx + i, ty - i, tx + i + 1, ty - i + 1, c);
                    }
                }
            }
        }
    }

    private static void diamond(GuiGraphics g, int x, int y, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(x - half, y + i, x - half + 1, y + i + 1, color);
            g.fill(x + half, y + i, x + half + 1, y + i + 1, color);
        }
    }
}
