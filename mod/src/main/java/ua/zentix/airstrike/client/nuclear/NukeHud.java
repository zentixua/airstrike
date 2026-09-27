package ua.zentix.airstrike.client.nuclear;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ua.zentix.airstrike.net.S2C;

/**
 * Ядерный HUD вверху экрана: у запустившего — отсчёт до подрыва, у тех, по кому летит, — мигающее
 * «РАКЕТНОЕ НАПАДЕНИЕ» с отсчётом, у остальных в измерении — строка о пуске (первые 10 с). И взведение пуска
 * с пульта: 3 с отсчёта «ПУСК», повторный ЛКМ отменяет.
 */
public final class NukeHud {
    private NukeHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) return;
        Font font = mc.font;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        double now = mc.level.getGameTime() + partial;
        int cx = g.guiWidth() / 2;
        int y = 28;
        float pulse = 0.6f + 0.4f * Mth.sin((float) now * 0.4f);

        if (NukeArming.armed()) {
            int left = (int) Math.ceil(NukeArming.ticksLeft(partial) / 20.0);
            Component c = Component.translatable("airstrike.nuke.arming", left).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            big(g, font, c, cx, g.guiHeight() / 2 - 40, 2f, alpha(pulse));
            Component hint = Component.translatable("airstrike.nuke.arming.cancel").withStyle(ChatFormatting.GRAY);
            g.drawString(font, hint, cx - font.width(hint) / 2, g.guiHeight() / 2 - 16, 0xFFFFFFFF);
        }

        for (S2C.NukeWarning w : ClientNuclear.warnings()) {
            double left = (w.detonateTime() - now) / 20.0;
            if (left < 0) continue;
            String sec = String.format(java.util.Locale.ROOT, "%.0f", Math.ceil(left));
            if (w.alarm()) {
                big(g, font, Component.translatable("airstrike.nuke.alert").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), cx, y, 1.5f, alpha(pulse));
                y += 16;
                Component c = Component.translatable("airstrike.nuke.alert.eta", sec).withStyle(ChatFormatting.GOLD);
                g.drawString(font, c, cx - font.width(c) / 2, y, 0xFFFFFFFF);
                y += 12;
            }
            if (w.mine()) {
                Component c = Component.translatable("airstrike.nuke.countdown", sec, Math.round(w.yieldKt())).withStyle(ChatFormatting.YELLOW);
                g.drawString(font, c, cx - font.width(c) / 2, y, 0xFFFFFFFF);
                y += 12;
            } else if (!w.alarm() && now - w.launchTime() < 200) {
                Component c = Component.translatable("airstrike.nuke.detected").withStyle(ChatFormatting.GRAY);
                g.drawString(font, c, cx - font.width(c) / 2, y, 0xFFFFFFFF);
                y += 12;
            }
        }
    }

    private static int alpha(float a) {
        return (int) (255 * Mth.clamp(a, 0.05f, 1)) << 24;
    }

    private static void big(GuiGraphics g, Font font, Component c, int cx, int y, float scale, int alpha) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, c, -font.width(c) / 2, 0, alpha | 0xFFFFFF);
        g.pose().popPose();
    }
}
