package ua.zentix.airstrike.nuclear.model;

/**
 * Световой импульс: Q = f·Y·10¹²·τ / (4π·(100·R)²) кал/см², τ = exp(−R/V).
 * f — доля энергии в свете: 0.35 воздушный, 0.18 наземный. R, V — метры.
 */
public final class ThermalModel {
    /** Ожог 1-й степени, кал/см². */
    public static final double BURN_1 = 3;
    /** Ожог 2-й степени. */
    public static final double BURN_2 = 5;
    /** Ожог 3-й степени. */
    public static final double BURN_3 = 8;
    /** Воспламенение листвы, травы, шерсти. */
    public static final double IGNITE = 5;
    /** Видимость, м: ясно, дождь, гроза. */
    public static final double VISIBILITY_CLEAR = 20000;
    public static final double VISIBILITY_RAIN = 5000;
    public static final double VISIBILITY_THUNDER = 1000;

    private ThermalModel() {
    }

    /** Плотность световой энергии на наклонной дальности R, кал/см². */
    public static double fluenceCalPerCm2(double slantRangeM, double yieldKt, boolean surfaceBurst, double visibilityM) {
        double r = Math.max(slantRangeM, 1);
        double f = surfaceBurst ? 0.18 : 0.35;
        double rCm = 100 * r;
        return f * yieldKt * 1e12 * Math.exp(-r / visibilityM) / (4 * Math.PI * rCm * rCm);
    }

    /** Дальность, где плотность энергии падает до q, м. */
    public static double rangeForFluence(double q, double yieldKt, boolean surfaceBurst, double visibilityM) {
        return Solve.decreasingRoot(r -> fluenceCalPerCm2(r, yieldKt, surfaceBurst, visibilityM), q, 1, 1e8);
    }
}
