package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphics;

import java.lang.reflect.Field;

/**
 * Мышь в съёмке без окна (кадры руководства, трейлер): событий мыши нет, поэтому поле мыши игры ставится прямо — экран
 * рисует наведение по нему, — а стрелка рисуется поверх экрана своим кодом (курсор системы в кадр не попадает).
 */
public final class Mouse {
    /** Стрелка: «X» — контур, «.» — заливка. */
    private static final String[] ARROW = {
            "X", "XX", "X.X", "X..X", "X...X", "X....X", "X.....X", "X......X", "X.......X", "X........X",
            "X.....XXXXX", "X..X..X", "X.X X..X", "XX  X..X", "X    X..X", "     X..X", "      XX"};
    private static Field xpos, ypos;

    private Mouse() {}

    /** Мышь игры — в точку GUI. */
    public static void at(Minecraft mc, double guiX, double guiY) {
        var w = mc.getWindow();
        try {
            if (xpos == null) {
                xpos = MouseHandler.class.getDeclaredField("xpos");
                ypos = MouseHandler.class.getDeclaredField("ypos");
                xpos.setAccessible(true);
                ypos.setAccessible(true);
            }
            xpos.setDouble(mc.mouseHandler, guiX * w.getScreenWidth() / w.getGuiScaledWidth());
            ypos.setDouble(mc.mouseHandler, guiY * w.getScreenHeight() / w.getGuiScaledHeight());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Стрелка остриём в точке GUI. */
    public static void drawArrow(GuiGraphics g, int x, int y) {
        for (int row = 0; row < ARROW.length; row++) {
            String line = ARROW[row];
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (c == 'X') g.fill(x + col, y + row, x + col + 1, y + row + 1, 0xFF000000);
                else if (c == '.') g.fill(x + col, y + row, x + col + 1, y + row + 1, 0xFFFFFFFF);
            }
        }
    }
}
