package ua.zentix.airstrike.nuclear.model;

/**
 * Ударная волна: избыточное давление по Кинни–Грэхему, скоростной напор, скорость фронта.
 * Дальность наклонная, в метрах (= блоках); мощность в килотоннах.
 */
public final class BlastModel {
    /** Атмосферное давление, кПа. */
    public static final double P0 = 101.325;
    /** Скорость звука, м/с: к ней стремится фронт вдали. */
    public static final double SOUND_SPEED = 343.0;
    /** кПа в одном psi. */
    public static final double KPA_PER_PSI = 6.895;
    /** Доля энергии в волне. */
    private static final double BLAST_FRACTION = 0.5;
    /** Усиление отражением от земли. */
    private static final double GROUND_REFLECTION = 1.8;

    private BlastModel() {
    }

    /** Эквивалент для волны, кг ТНТ: W = 0.5·Y·10⁶·1.8. */
    static double chargeKg(double yieldKt) {
        return BLAST_FRACTION * yieldKt * 1e6 * GROUND_REFLECTION;
    }

    /** Избыточное давление на наклонной дальности R, кПа. */
    public static double overpressureKpa(double slantRangeM, double yieldKt) {
        double z = Math.max(0, slantRangeM) / Math.cbrt(chargeKg(yieldKt));
        double a = z / 4.5, b = z / 0.048, c = z / 0.32, d = z / 1.35;
        return P0 * 808 * (1 + a * a) / Math.sqrt((1 + b * b) * (1 + c * c) * (1 + d * d));
    }

    public static double psi(double kpa) {
        return kpa / KPA_PER_PSI;
    }

    public static double kpa(double psi) {
        return psi * KPA_PER_PSI;
    }

    /** Дальность, где давление падает до {@code kpa} (0, если такого давления нет даже у центра). */
    public static double rangeForOverpressure(double kpa, double yieldKt) {
        if (kpa >= overpressureKpa(0, yieldKt)) return 0;
        return Solve.decreasingRoot(r -> overpressureKpa(r, yieldKt), kpa, 1e-3, 1e8);
    }

    /** Скоростной напор за фронтом, кПа: q = 2.5·P² / (7·P0 + P). */
    public static double dynamicPressureKpa(double overpressureKpa) {
        return 2.5 * overpressureKpa * overpressureKpa / (7 * P0 + overpressureKpa);
    }

    /** Скорость фронта по Ренкину–Гюгонио, м/с: U = 343·√(1 + 6P/(7·P0)). */
    public static double frontSpeed(double overpressureKpa) {
        return SOUND_SPEED * Math.sqrt(1 + 6 * Math.max(0, overpressureKpa) / (7 * P0));
    }

    /** Длительность положительной фазы на средних дальностях, с. */
    public static double positivePhaseSeconds(double yieldKt) {
        return 0.4 * Math.cbrt(yieldKt);
    }
}
