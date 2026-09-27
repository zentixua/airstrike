package ua.zentix.airstrike.nuclear.model;

/** Ядерный гриб: высота и размер шапки, время стабилизации, подъём. Метры, секунды, килотонны. */
public final class CloudModel {
    /**
     * Опорные точки высоты верха стабилизированного облака (кт → м), между ними — линейно в log–log.
     * 5–100 кт идут по 12 км·(Y/15)^0.25, выше облако упирается в тропопаузу (1 Мт ≈ 21.5 км),
     * а мегатонны пробивают её (10 Мт ≈ 30 км, Glasstone рис. 2.16).
     */
    private static final double[] TOP_Y = {1, 15, 100, 1000, 10000};
    private static final double[] TOP_M = {6100, 12000, 18000, 21500, 30000};
    /** Радиус шапки 15 кт, м, и показатель роста: 1 Мт → ~10 км. */
    private static final double CAP_RADIUS_15KT = 2000;
    private static final double CAP_RADIUS_EXP = 0.38;
    /** Низ шапки — эта доля пути от подрыва до верха. */
    private static final double CAP_BOTTOM = 0.6;
    /** Влажность биома, начиная с которой видно облако Вильсона. */
    private static final double WILSON_HUMIDITY = 0.3;

    private CloudModel() {
    }

    /** Высота верха облака после стабилизации над точкой подрыва, м. */
    public static double stabilizedTop(double yieldKt) {
        double ly = Math.log(Math.max(yieldKt, 1e-3));
        int i = 1;
        while (i < TOP_Y.length - 1 && ly > Math.log(TOP_Y[i])) i++;
        double x0 = Math.log(TOP_Y[i - 1]), x1 = Math.log(TOP_Y[i]);
        double y0 = Math.log(TOP_M[i - 1]), y1 = Math.log(TOP_M[i]);
        return Math.exp(y0 + (ly - x0) * (y1 - y0) / (x1 - x0));
    }

    /** Радиус шапки после стабилизации, м: 2 км для 15 кт, ~10 км для 1 Мт. */
    public static double capRadius(double yieldKt) {
        return CAP_RADIUS_15KT * Math.pow(yieldKt / 15, CAP_RADIUS_EXP);
    }

    /**
     * Время стабилизации, с: три постоянных времени подъёма верха (95% высоты).
     * Начальная скорость подъёма — {@link FireballModel#riseSpeed}: 15 кт ≈ 6 мин, 1 Мт ≈ 9 мин.
     */
    public static double stabilizationSeconds(double yieldKt) {
        return 3 * riseTau(yieldKt);
    }

    /** Высота верха облака в момент t, м: от верха шара экспоненциально к {@link #stabilizedTop}. */
    public static double top(double t, double hobM, double yieldKt) {
        double start = hobM + FireballModel.maxRadius(yieldKt, false);
        double end = Math.max(start, stabilizedTop(yieldKt));
        return start + (end - start) * (1 - Math.exp(-Math.max(0, t) / riseTau(yieldKt)));
    }

    /** Нижняя кромка шапки в момент t, м (~0.6 от верха после стабилизации). */
    public static double capBottom(double t, double hobM, double yieldKt) {
        double base = Math.max(0, hobM);
        return base + CAP_BOTTOM * (top(t, hobM, yieldKt) - base);
    }

    /** Будет ли облако Вильсона при такой влажности (0..1, из биома; над водой — 1). */
    public static boolean wilsonCloud(double humidity01) {
        return humidity01 >= WILSON_HUMIDITY;
    }

    private static double riseTau(double yieldKt) {
        double span = stabilizedTop(yieldKt) - FireballModel.maxRadius(yieldKt, false);
        return Math.max(span, 1) / FireballModel.riseSpeed(yieldKt);
    }
}
