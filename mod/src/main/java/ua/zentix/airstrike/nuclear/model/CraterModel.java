package ua.zentix.airstrike.nuclear.model;

/**
 * Видимая воронка наземного подрыва: радиус 23·Y^0.3 м, глубина 7.7·Y^0.3 м (сухой грунт),
 * профиль — параболоид, вокруг — вал выброса.
 */
public final class CraterModel {
    /** Грунт под эпицентром. */
    public enum Soil {
        /** Сухой грунт. */
        DRY(1.0),
        /** Скала. */
        ROCK(0.7),
        /** Влажный грунт, песок. */
        WET(1.3);

        final double factor;

        Soil(double factor) {
            this.factor = factor;
        }
    }

    /** Высота вала на кромке — доля глубины. */
    private static final double RIM = 0.25;
    /** Внутренний склон вала начинается с этой доли радиуса. */
    private static final double RIM_INNER = 0.8;

    private CraterModel() {
    }

    public static double radius(double yieldKt, Soil soil) {
        return 23 * Math.pow(yieldKt, 0.3) * soil.factor;
    }

    public static double depth(double yieldKt, Soil soil) {
        return 7.7 * Math.pow(yieldKt, 0.3) * soil.factor;
    }

    /** Глубина чаши на горизонтальном расстоянии r от эпицентра, м (0 за радиусом). */
    public static double profileDepth(double r, double yieldKt, Soil soil) {
        double rc = radius(yieldKt, soil);
        if (r >= rc) return 0;
        double u = r / rc;
        return depth(yieldKt, soil) * (1 - u * u);
    }

    /**
     * Высота вала над исходной поверхностью, м: растёт от 0.8 радиуса к кромке до 0.25 глубины,
     * снаружи квадратично сходит на нет к двум радиусам. Итоговая поверхность = вал − чаша.
     */
    public static double rimHeight(double r, double yieldKt, Soil soil) {
        double rc = radius(yieldKt, soil);
        double peak = RIM * depth(yieldKt, soil);
        if (r <= RIM_INNER * rc || r >= 2 * rc) return 0;
        double u = r < rc ? (r - RIM_INNER * rc) / ((1 - RIM_INNER) * rc) : (2 * rc - r) / rc;
        return peak * u * u;
    }

    /** Будет ли воронка: подрыв не выше 0.1 максимального радиуса шара наземного взрыва. */
    public static boolean formsCrater(double hobM, double yieldKt) {
        return hobM <= 0.1 * FireballModel.maxRadius(yieldKt, true);
    }
}
