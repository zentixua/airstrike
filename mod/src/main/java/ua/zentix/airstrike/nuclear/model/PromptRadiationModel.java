package ua.zentix.airstrike.nuclear.model;

/** Проникающая радиация первой минуты: D = K·Y·exp(−R/λ)/R², бэр. R — наклонная дальность, м. */
public final class PromptRadiationModel {
    /** Длина ослабления в воздухе, м. */
    public static final double LAMBDA = 330;
    /** Коэффициент, бэр·м²/кт: подогнан так, что 15 кт дают ровно 500 бэр на 1.3 км (≈2.895·10⁹). */
    public static final double K = 500.0 * 1300 * 1300 * Math.exp(1300 / LAMBDA) / 15;
    /** Бэр в одном грее. */
    public static final double REM_PER_GY = 100;

    private PromptRadiationModel() {
    }

    public static double doseRem(double slantRangeM, double yieldKt) {
        double r = Math.max(slantRangeM, 1);
        return K * yieldKt * Math.exp(-r / LAMBDA) / (r * r);
    }

    /**
     * Ослабление блоками на луче от взрыва: камень/бетон/металл ×0.1, земля ×0.2, вода ×0.3,
     * дерево ×0.6, стекло/листва ×0.9 за каждый блок.
     */
    public static double shielding(int stone, int earth, int water, int wood, int glassOrLeaves) {
        return Math.pow(0.1, stone) * Math.pow(0.2, earth) * Math.pow(0.3, water)
                * Math.pow(0.6, wood) * Math.pow(0.9, glassOrLeaves);
    }
}
