package ua.zentix.airstrike.client.hud;

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
}
