package ua.zentix.airstrike.nuclear.radiation;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Мощность дозы для людей: Р/ч модели → мкЗв/ч … Зв/ч (1 Р ≈ 10 мЗв), три значащие цифры. */
public final class GeigerFormat {
    /** Естественный фон, мкЗв/ч: счётчик никогда не молчит совсем. */
    public static final double BACKGROUND_USV = 0.15;

    private GeigerFormat() {}

    /** Мощность дозы, мкЗв/ч, с фоном. */
    public static double microSievertsPerHour(double roentgenPerHour) {
        return roentgenPerHour * 10_000 + BACKGROUND_USV;
    }

    public static Component rate(double roentgenPerHour) {
        double usv = microSievertsPerHour(roentgenPerHour);
        if (usv < 1000) return Component.translatable("airstrike.unit.usv_h", number(usv));
        if (usv < 1_000_000) return Component.translatable("airstrike.unit.msv_h", number(usv / 1000));
        return Component.translatable("airstrike.unit.sv_h", number(usv / 1_000_000));
    }

    private static String number(double v) {
        if (v >= 100) return String.format(Locale.ROOT, "%.0f", v);
        if (v >= 10) return String.format(Locale.ROOT, "%.1f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
