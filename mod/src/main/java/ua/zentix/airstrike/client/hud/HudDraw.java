package ua.zentix.airstrike.client.hud;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;

/** Простые фигуры HUD из закрашенных пикселей (у {@link GuiGraphics} есть только прямоугольники). */
public final class HudDraw {
    /** Обводка знаков карты: тёмная кайма, по которой линия читается на любом рельефе (город, снег, вода). */
    public static final int HALO = 0xB0000000;
    /** Штрих и промежуток штриховой линии, пикселей. */
    private static final float DASH = 8, GAP = 5;

    private HudDraw() {}

    /** Отрезок толщиной {@code width} — прямоугольник, повёрнутый вдоль отрезка. */
    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1, int width, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        g.pose().pushPose();
        g.pose().translate(x0, y0, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        int half = width / 2;
        g.fill(0, -half, Math.round(len), width - half, color);
        g.pose().popPose();
    }

    /** Отрезок с обводкой {@link #HALO}. */
    public static void haloLine(GuiGraphics g, float x0, float y0, float x1, float y1, int width, int color) {
        line(g, x0, y0, x1, y1, width + 2, HALO);
        line(g, x0, y0, x1, y1, width, color);
    }

    /** Штриховая линия с обводкой: путь, который ещё предстоит (снаряд → цель). Не больше 200 штрихов. */
    public static void dashed(GuiGraphics g, float x0, float y0, float x1, float y1, int width, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        float period = Math.max(DASH + GAP, len / 200);
        float ux = dx / len, uy = dy / len;
        for (int pass = 0; pass < 2; pass++) {
            for (float s = 0; s < len; s += period) {
                float e = Math.min(len, s + period * DASH / (DASH + GAP));
                if (pass == 0) line(g, x0 + ux * s, y0 + uy * s, x0 + ux * e, y0 + uy * e, width + 2, HALO);
                else line(g, x0 + ux * s, y0 + uy * s, x0 + ux * e, y0 + uy * e, width, color);
            }
        }
    }

    /** Окружность толщиной {@code width} с обводкой: граница района (разброс залпа, дальность видео). */
    public static void ring(GuiGraphics g, float cx, float cy, float r, int width, int color) {
        if (r < 1) return;
        int steps = (int) Math.max(24, Math.min(360, 2 * Math.PI * r / 6));
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < steps; i++) {
                double a0 = 2 * Math.PI * i / steps, a1 = 2 * Math.PI * (i + 1) / steps;
                float x0 = cx + (float) (Math.cos(a0) * r), y0 = cy + (float) (Math.sin(a0) * r);
                float x1 = cx + (float) (Math.cos(a1) * r), y1 = cy + (float) (Math.sin(a1) * r);
                if (pass == 0) line(g, x0, y0, x1, y1, width + 2, HALO);
                else line(g, x0, y0, x1, y1, width, color);
            }
        }
    }

    /** Круг, залитый {@code color} (полупрозрачным — район под ним виден), по строкам в пределах экрана. */
    public static void disc(GuiGraphics g, float cx, float cy, float r, int color) {
        int y0 = Math.max(Math.round(cy - r), 0), y1 = Math.min(Math.round(cy + r), g.guiHeight());
        for (int y = y0; y < y1; y++) {
            float dy = y + 0.5f - cy;
            float half = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fill(Math.round(cx - half), y, Math.round(cx + half), y + 1, color);
        }
    }

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

    /**
     * Значок летящего по карте «север вверх»: стрелка-самолётик по курсу Minecraft {@code yaw} (0° — на юг, +Z),
     * с обводкой {@link #HALO}.
     */
    public static void heading(GuiGraphics g, int x, int y, float yaw, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        // курс Minecraft: 0° — на +Z (вниз по карте), по часовой — к −X; на экране поворот по часовой — положительный
        g.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180));
        g.fill(-2, -8, 2, 7, HALO);
        g.fill(-7, -2, 7, 2, HALO);
        g.fill(-4, 3, 4, 7, HALO);
        g.fill(-1, -7, 1, 6, color);
        g.fill(-6, -1, 6, 1, color);
        g.fill(-3, 4, 3, 6, color);
        g.pose().popPose();
    }
}
