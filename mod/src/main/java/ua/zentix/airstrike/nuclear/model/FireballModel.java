package ua.zentix.airstrike.nuclear.model;

/**
 * Огненный шар: размер, двойная вспышка, цвет, всплытие. Время t — секунды после подрыва,
 * мощность Y — килотонны, расстояния — метры.
 */
public final class FireballModel {
    /** Во сколько раз второй максимум тусклее первого импульса (для нормированной яркости). */
    private static final double SECOND_PEAK = 0.85;
    /** Показатель спада второго импульса: при 3.75 за 10·t_max излучается ~80% его энергии. */
    private static final double PULSE_EXP = 3.75;
    /** Шар дорастает до этой доли от максимума, дальше растёт медленно, остывая. */
    private static final double LATE_GROWTH_CAP = 1.3;

    /**
     * Ключевые точки цвета: t / t_max (второго максимума) и цвет RGB. Шар светится всем объёмом ~100 t_max:
     * 15 кт — жёлто-оранжевый к 2 с, оранжевый к 7 с, красно-бурый к 16 с.
     */
    private static final double[] COLOR_T = {0, 0.5, 2, 16, 60, 130, 400};
    private static final int[] COLOR_RGB = {
            0xC8DCFF, // бело-голубой первый импульс
            0xFFFFFF, // белый
            0xFFFADC, // бледно-жёлтый
            0xFFBE50, // жёлто-оранжевый
            0xE66E28, // оранжевый
            0x8C3C23, // красно-бурый (оксиды азота)
            0x96908A, // серое облако
    };

    private FireballModel() {
    }

    /** Максимальный радиус светящегося шара, м (Glasstone 2.12): 0.07·Y^0.4 км воздушный, 0.088·Y^0.4 км наземный. */
    public static double maxRadius(double yieldKt, boolean surfaceBurst) {
        return (surfaceBurst ? 88 : 70) * Math.pow(yieldKt, 0.4);
    }

    /** Минимум яркости между импульсами, с. */
    public static double firstMinimumSeconds(double yieldKt) {
        return 0.0025 * Math.sqrt(yieldKt);
    }

    /** Второй (главный тепловой) максимум, с. */
    public static double secondMaximumSeconds(double yieldKt) {
        return 0.032 * Math.sqrt(yieldKt);
    }

    /**
     * Нормированная яркость 0..1: резкий первый импульс (спад к минимуму за t_min), затем широкий второй
     * максимум в t_max: f(τ) = (p/2)·τ² / (1 + (p−2)/2·τ^p), τ = t/t_max, дальше медленный спад к нулю.
     */
    public static double brightness(double t, double yieldKt) {
        if (t <= 0) return 0;
        double tMin = firstMinimumSeconds(yieldKt);
        double first = (1 - Math.exp(-t / (tMin * 0.001))) * Math.exp(-t / (tMin / 3));
        double tau = t / secondMaximumSeconds(yieldKt);
        double second = PULSE_EXP / 2 * tau * tau / (1 + (PULSE_EXP - 2) / 2 * Math.pow(tau, PULSE_EXP));
        return Math.min(1, first + SECOND_PEAK * second);
    }

    /**
     * Радиус шара, м: рост ~t^0.4 до максимума ко второму максимуму яркости,
     * затем медленный рост при остывании (+10% за каждое e-кратное время, не больше ×1.3).
     */
    public static double radius(double t, double yieldKt, boolean surfaceBurst) {
        if (t <= 0) return 0;
        double max = maxRadius(yieldKt, surfaceBurst);
        double tg = secondMaximumSeconds(yieldKt);
        if (t <= tg) return max * Math.pow(t / tg, 0.4);
        return max * Math.min(LATE_GROWTH_CAP, 1 + 0.1 * Math.log(t / tg));
    }

    /** Цвет ARGB (непрозрачный): бело-голубой → белый → жёлто-оранжевый → красно-бурый → серый. */
    public static int colorArgb(double t, double yieldKt) {
        double tau = Math.max(0, t) / secondMaximumSeconds(yieldKt);
        int n = COLOR_T.length;
        if (tau >= COLOR_T[n - 1]) return 0xFF000000 | COLOR_RGB[n - 1];
        int i = 1;
        while (COLOR_T[i] < tau) i++;
        // между ключами — по логарифму времени (первый отрезок — линейно, от нуля)
        double f = i == 1 ? tau / COLOR_T[1]
                : Math.log(tau / COLOR_T[i - 1]) / Math.log(COLOR_T[i] / COLOR_T[i - 1]);
        return 0xFF000000 | lerpRgb(COLOR_RGB[i - 1], COLOR_RGB[i], Solve.clamp(f, 0, 1));
    }

    /** Скорость всплытия шара, м/с: ~95 для 15 кт, слабо растёт с мощностью. */
    public static double riseSpeed(double yieldKt) {
        return 95 * Math.pow(yieldKt / 15, 0.04);
    }

    /**
     * Высота центра шара (потом — центра шапки), м: стартует с высоты подрыва со скоростью
     * {@link #riseSpeed} и экспоненциально тормозит к 0.8 высоты верха стабилизированного облака.
     */
    public static double centreHeight(double t, double hobM, double yieldKt) {
        double target = Math.max(hobM, 0.8 * CloudModel.stabilizedTop(yieldKt));
        double span = target - hobM;
        if (span <= 0 || t <= 0) return hobM;
        return hobM + span * (1 - Math.exp(-t * riseSpeed(yieldKt) / span));
    }

    private static int lerpRgb(int a, int b, double f) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + f * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) Math.round(((a >> 8) & 0xFF) + f * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) Math.round((a & 0xFF) + f * ((b & 0xFF) - (a & 0xFF)));
        return r << 16 | g << 8 | bl;
    }
}
