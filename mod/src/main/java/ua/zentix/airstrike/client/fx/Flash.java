package ua.zentix.airstrike.client.fx;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import ua.zentix.airstrike.AirstrikeConfig;

/**
 * Вспышка взрыва на экране (вместо блоков света и ночного зрения датапака): свет приходит мгновенно,
 * яркость зависит от расстояния и прямой видимости, затухает за несколько тиков.
 */
public final class Flash {
    private static float intensity;
    private static float decay = 0.75f;
    private static int color = 0xFFF4D0;

    private Flash() {}

    /** @param strength 0..1, @param decayPerTick множитель за тик (0.75 — быстрая вспышка, 0.97 — долгое сияние) */
    public static void trigger(float strength, float decayPerTick, int rgb) {
        float s = (float) (strength * AirstrikeConfig.CLIENT.flash.get());
        if (s > intensity) {
            intensity = Math.min(1, s);
            decay = decayPerTick;
            color = rgb;
        }
    }

    public static void tick() {
        intensity *= decay;
        if (intensity < 0.01f) intensity = 0;
    }

    public static void reset() {
        intensity = 0;
    }

    public static float intensity() {
        return intensity;
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        if (intensity <= 0) return;
        int a = Math.min(255, (int) (intensity * 255));
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | color);
    }
}
