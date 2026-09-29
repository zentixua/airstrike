package ua.zentix.airstrike.client.map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Карта «север вверх»: мир (x, z) → экран. Север (−Z) вверх, восток (+X) вправо; {@code (ox, oy)} — точка экрана,
 * где центр вида {@code (cx, cz)}; {@code k} — пикселей на блок.
 */
public record MapProjection(double ox, double oy, double cx, double cz, double k) {
    /** Сетка — не мельче этого, пикселей. */
    private static final int GRID_MIN_PX = 40;
    private static final double[] GRID_STEPS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};

    public int x(double worldX) {
        return (int) Math.round(ox + (worldX - cx) * k);
    }

    public int y(double worldZ) {
        return (int) Math.round(oy + (worldZ - cz) * k);
    }

    public int[] at(Vec3 p) {
        return at(p.x, p.z);
    }

    public int[] at(double worldX, double worldZ) {
        return new int[] {x(worldX), y(worldZ)};
    }

    public double worldX(double screenX) {
        return cx + (screenX - ox) / k;
    }

    public double worldZ(double screenY) {
        return cz + (screenY - oy) / k;
    }

    /** Сетка с шагом не мельче {@link #GRID_MIN_PX} в прямоугольнике экрана и подпись шага внизу слева. */
    public void drawGrid(GuiGraphics g, Font font, int left, int top, int right, int bottom, int lineColor, int textColor) {
        double step = GRID_STEPS[GRID_STEPS.length - 1];
        for (double s : GRID_STEPS) {
            if (s * k >= GRID_MIN_PX) {
                step = s;
                break;
            }
        }
        for (double x = Math.floor(worldX(left) / step) * step; x <= worldX(right); x += step) {
            int px = x(x);
            g.fill(px, top, px + 1, bottom, lineColor);
        }
        for (double z = Math.floor(worldZ(top) / step) * step; z <= worldZ(bottom); z += step) {
            int py = y(z);
            g.fill(left, py, right, py + 1, lineColor);
        }
        Component legend = Component.translatable("airstrike.map.grid", String.format(Locale.ROOT, "%.0f", step));
        g.drawString(font, legend, left + 8, bottom - 14, textColor);
    }
}
