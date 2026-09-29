package ua.zentix.airstrike.client.hud;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;

/** Простые фигуры HUD из закрашенных пикселей (у {@link GuiGraphics} есть только прямоугольники). */
public final class HudDraw {
    private HudDraw() {}

    /** Пунктир: точка через 6 пикселей. */
    public static void dotted(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.sqrt(dx * dx + dy * dy);
        int steps = (int) Math.min(400, len / 6);
        for (int i = 1; i < steps; i++) {
            int x = x0 + (int) (dx * i / steps), y = y0 + (int) (dy * i / steps);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** Контур ромба радиусом {@code r}. */
    public static void diamond(GuiGraphics g, int x, int y, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(x - half, y + i, x - half + 1, y + i + 1, color);
            g.fill(x + half, y + i, x + half + 1, y + i + 1, color);
        }
    }

    /** Пунктирная окружность: точка через ~6 пикселей дуги. */
    public static void dottedCircle(GuiGraphics g, double cx, double cy, double r, int color) {
        int steps = (int) Math.max(24, Math.min(720, 2 * Math.PI * r / 6));
        for (int i = 0; i < steps; i++) {
            double a = 2 * Math.PI * i / steps;
            int x = (int) Math.round(cx + Math.cos(a) * r), y = (int) Math.round(cy + Math.sin(a) * r);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** Квадратная рамка цели со стороной {@code 2r + 1}. */
    public static void box(GuiGraphics g, int x, int y, int r, int color) {
        g.fill(x - r, y - r, x + r + 1, y - r + 1, color);
        g.fill(x - r, y + r, x + r + 1, y + r + 1, color);
        g.fill(x - r, y - r, x - r + 1, y + r + 1, color);
        g.fill(x + r, y - r, x + r + 1, y + r + 1, color);
    }

    /** Значок летящего по карте «север вверх»: стрелка-самолётик по курсу Minecraft {@code yaw} (0° — на юг, +Z). */
    public static void heading(GuiGraphics g, int x, int y, float yaw, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        // курс Minecraft: 0° — на +Z (вниз по карте), по часовой — к −X; на экране поворот по часовой — положительный
        g.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180));
        g.fill(-1, -7, 1, 6, color);
        g.fill(-6, -1, 6, 1, color);
        g.fill(-3, 4, 3, 6, color);
        g.pose().popPose();
    }
}
