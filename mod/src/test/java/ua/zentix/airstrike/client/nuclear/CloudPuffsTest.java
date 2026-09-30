package ua.zentix.airstrike.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPuffsTest {
    /** Ночное небо: столько света неба остаётся на дыме (нижний предел в рендерере). */
    private static final float NIGHT = 0.12f;
    /** Дым ножки (цвет клуба ножки) и самый тусклый её клуб (низ, край): доля полного накала. */
    private static final int STEM = 0x8E7F70;
    private static final double STEM_EDGE = 0.75 * 0.85;

    private static double luminance(float[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** Гриб 15 кт светится изнутри больше минуты: к 70 с — почти в полную силу, к 90 с — ещё больше половины. */
    @Test
    void glowsForMoreThanAMinute() {
        assertTrue(CloudPuffs.glow(30, 15) > 0.99);
        assertTrue(CloudPuffs.glow(70, 15) > 0.75, "70 с: " + CloudPuffs.glow(70, 15));
        assertTrue(CloudPuffs.glow(90, 15) > 0.5, "90 с: " + CloudPuffs.glow(90, 15));
        assertTrue(CloudPuffs.glow(200, 15) < 0.01);
        assertTrue(CloudPuffs.glow(50, 1) < CloudPuffs.glow(50, 15) - 0.3, "у 1 кт заметно короче");
    }

    /** Цвет накала тёплый и не тёмно-красный: зелёного не меньше трети красного, синий не выше зелёного. */
    @Test
    void glowColourStaysWarmAndBright() {
        for (double t = 0; t <= 400; t += 2.5) {
            int c = CloudPuffs.glowRgb(t, 15);
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            assertTrue(r >= g && g >= b, t + " с: " + Integer.toHexString(c));
            assertTrue(g >= r / 3, t + " с: тёмно-красный " + Integer.toHexString(c));
            assertTrue(r >= 0xA0, t + " с: тёмный " + Integer.toHexString(c));
        }
    }

    /**
     * Ночью ножка гриба (самый тусклый её клуб) — никогда не чёрный дым: первые 70 с — светящаяся оранжевая,
     * потом — освещённая серо-бурая.
     */
    @Test
    void stemIsNeverBlackAtNight() {
        for (double t = 0.5; t <= 1500; t += 0.5) {
            double heat = Math.max(CloudPuffs.glow(t, 15), CloudPuffs.EMBER) * STEM_EDGE;
            float[] c = CloudPuffs.smoke(STEM, 0.7, NIGHT, CloudPuffs.glowRgb(t, 15), heat);
            assertTrue(luminance(c) > 0.15, t + " с: яркость " + luminance(c));
            if (t <= 70) {
                assertTrue(c[0] > 0.4 && c[0] > 1.4 * c[2], t + " с: не светится оранжевым " + c[0] + " " + c[1] + " " + c[2]);
            }
        }
    }

    /** Днём отсвет не заливает шапку: она светлее, но цвет дыма виден. */
    @Test
    void daylightKeepsSmokeColour() {
        float[] c = CloudPuffs.smoke(0x8C5A46, 1, 1f, CloudPuffs.glowRgb(60, 15), CloudPuffs.EMBER);
        assertTrue(c[2] < 0.6, "синий " + c[2]);
    }
}
