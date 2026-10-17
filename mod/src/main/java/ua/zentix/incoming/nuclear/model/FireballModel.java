package ua.zentix.incoming.nuclear.model;

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

    /** Видимая температура шара во втором максимуме, K (G&D 2.110: ~7 500–8 000 K почти без зависимости от мощности). */
    public static final double PEAK_K = 7700;
    /** Температура воздуха вокруг, K. */
    public static final double AMBIENT_K = 288;
    /** Показатель остывания после второго максимума: ΔT ∝ (t/t_max)^−0,35 (ключи хроники — {@link #temperature}). */
    static final double COOLING = 0.35;
    /** Горячее шар не показывается (первый импульс короче кадра): край строки света чёрного тела у клиента. */
    static final double HOTTEST_K = 30_000;

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

    /**
     * Видимая температура поверхности шара, K, пока он шар. До второго максимума — по Стефану–Больцману из его яркости
     * и радиуса: свет шара P ∝ T⁴·R², и T = T₂·(P/P₂)^¼·(R₂/R)^½ — первый импульс (маленький горячий шар) голубовато-белый,
     * в минимуме темнее, ко второму максимуму — T₂ ({@link #PEAK_K}). Дальше шар остывает к воздуху:
     * T_окр + (T₂ − T_окр)·(t/t_max)^−0,35 — у 15 кт бело-жёлтый ~3 900 K через секунду, ~1 950 K через девять (по
     * хронике). Это температура ядра: свернувшись в кольцо (у 15 кт — с ~3 с), шар остывает снаружи быстрее, край
     * смешан с подхваченным воздухом — через девять секунд ~1 250 K ({@link MushroomModel#edgeTemperature}).
     */
    public static double temperature(double t, double yieldKt) {
        if (t <= 0) return AMBIENT_K;
        double tm = secondMaximumSeconds(yieldKt);
        if (t >= tm) return AMBIENT_K + (PEAK_K - AMBIENT_K) * Math.pow(t / tm, -COOLING);
        // радиус до максимума — max·(t/t_max)^0,4 ({@link #radius}): (R₂/R)^½ = (t_max/t)^0,2
        double k = PEAK_K * Math.pow(brightness(t, yieldKt) / brightness(tm, yieldKt), 0.25) * Math.pow(tm / t, 0.2);
        return Solve.clamp(k, AMBIENT_K, HOTTEST_K);
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
}
