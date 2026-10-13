package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.scenario.Mouse;

/**
 * Курсор в планах с экраном мода (карта, пульт): стрелка идёт от точки к точке GUI с плавным разгоном, место — по доле
 * тика кадра (запись идёт 60 кадров в секунду), щелчок — нажатие и отпускание экрану, как у мыши. Мышь игры и стрелка —
 * {@link Mouse}: экран подсвечивает кнопку под ней.
 */
final class Cursor {
    private static boolean on;
    private static double fromX, fromY, toX, toY;
    private static int t, len;

    private Cursor() {}

    static void show(double[] at) {
        on = true;
        fromX = toX = at[0];
        fromY = toY = at[1];
        t = len = 0;
    }

    /** Из нынешнего места в {@code to} за {@code ticks} тиков. */
    static void moveTo(double[] to, int ticks) {
        double[] now = at(0);
        fromX = now[0];
        fromY = now[1];
        toX = to[0];
        toY = to[1];
        t = 0;
        len = Math.max(1, ticks);
    }

    static void hide() {
        on = false;
    }

    static void click(Minecraft mc) {
        if (mc.screen == null) return;
        double[] p = at(0);
        mc.screen.mouseClicked(p[0], p[1], 0);
        mc.screen.mouseReleased(p[0], p[1], 0);
    }

    static void tick() {
        if (on && t < len) t++;
    }

    private static double[] at(float partial) {
        double s = len == 0 ? 1 : CineCamera.smooth(Mth.clamp((t - 1 + partial) / len, 0, 1));
        return new double[]{fromX + (toX - fromX) * s, fromY + (toY - fromY) * s};
    }

    static void render(ScreenEvent.Render.Post e) {
        if (!on) return;
        Minecraft mc = Minecraft.getInstance();
        double[] p = at(e.getPartialTick());
        Mouse.at(mc, p[0], p[1]);
        GuiGraphics g = e.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        Mouse.drawArrow(g, (int) Math.round(p[0]), (int) Math.round(p[1]));
        g.pose().popPose();
    }

    /** Середина кнопки открытого экрана, чья подпись начинается с {@code text}; нет такой — null. */
    @Nullable
    static double[] widget(Minecraft mc, String text) {
        if (mc.screen == null) return null;
        for (var c : mc.screen.children()) {
            if (c instanceof AbstractWidget w && w.getMessage().getString().startsWith(text)) {
                return new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
            }
        }
        return null;
    }
}
