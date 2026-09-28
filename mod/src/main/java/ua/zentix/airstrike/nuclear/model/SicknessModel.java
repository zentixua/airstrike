package ua.zentix.airstrike.nuclear.model;

/**
 * Острая лучевая болезнь по накопленной дозе (Гр) и времени после облучения (игровые часы).
 * Течение: до начала — ничего, затем первичная реакция, скрытый период, разгар; после разгара
 * либо выздоровление ({@link Stage#NONE}), либо смерть ({@link Stage#FATAL}) для смертельных доз.
 * При 4–6 Гр исход решает случай: {@link #deathChance}.
 */
public final class SicknessModel {
    /** Стадия болезни. */
    public enum Stage { NONE, PRODROMAL, LATENT, MANIFEST, FATAL }

    /** Течение для диапазона доз: начало и длительности стадий, игровые часы. */
    private record Course(double onset, double prodromal, double latent, double manifest, boolean fatal) {
    }

    /** Нижние границы диапазонов доз, Гр, и течение в каждом. */
    private static final double[] DOSE_FROM = {1, 2, 6, 10, 50};
    private static final Course[] COURSES = {
            new Course(3, 1, 48, 48, false),     // 1–2: тошнота через 3 ч, скрытый 2 сут, разгар 2 сут
            new Course(1.5, 2, 24, 72, false),   // 2–6: рвота через 1–2 ч, скрытый 1 сут, разгар 3 сут
            new Course(0.5, 4, 6, 48, true),     // 6–10: через 30 мин, скрытый 6 ч, смерть почти наверняка
            new Course(0, 2, 0, 22, true),       // > 10: сразу, без скрытого, смерть в течение суток
            new Course(0, 0.25, 0, 0.75, true),  // > 50: сразу, смерть за час
    };

    private SicknessModel() {
    }

    /** Стадия при дозе doseGy через gameHoursSinceExposure игровых часов после облучения. */
    public static Stage stage(double doseGy, double gameHoursSinceExposure) {
        Course c = course(doseGy);
        if (c == null) return Stage.NONE;
        double t = gameHoursSinceExposure - c.onset;
        if (t < 0) return Stage.NONE;
        if ((t -= c.prodromal) < 0) return Stage.PRODROMAL;
        if ((t -= c.latent) < 0) return Stage.LATENT;
        if (t - c.manifest < 0) return Stage.MANIFEST;
        return c.fatal ? Stage.FATAL : Stage.NONE;
    }

    /** Через сколько игровых часов начинается первичная реакция; бесконечность при дозе < 1 Гр. */
    public static double onsetHours(double doseGy) {
        Course c = course(doseGy);
        return c == null ? Double.POSITIVE_INFINITY : c.onset;
    }

    /** Смерть неизбежна (≥ 6 Гр). */
    public static boolean lethal(double doseGy) {
        return doseGy >= DOSE_FROM[2];
    }

    /** Вероятность смерти к концу разгара: 0 до 4 Гр, линейно до 0.5 к 6 Гр, дальше 1. */
    public static double deathChance(double doseGy) {
        if (lethal(doseGy)) return 1;
        return Solve.clamp((doseGy - 4) / 4, 0, 0.5);
    }

    /** Снижение максимального здоровья в разгаре, единиц (2 = одно сердце). */
    public static double maxHealthPenalty(double doseGy) {
        if (doseGy >= 6) return 10;
        if (doseGy >= 2) return 4;
        return 0;
    }

    /** Множитель расхода сытости в разгаре. */
    public static double hungerMultiplier(double doseGy) {
        if (doseGy >= 2) return 2;
        if (doseGy >= 1) return 1.5;
        return 1;
    }

    /** Нет естественной регенерации в разгаре (≥ 2 Гр). */
    public static boolean noRegeneration(double doseGy) {
        return doseGy >= 2;
    }

    /** Уровень слабости в разгаре (усилитель эффекта): −1 — нет, 0 — I, 1 — II. */
    public static int weaknessAmplifier(double doseGy) {
        if (doseGy >= 6) return 1;
        if (doseGy >= 1) return 0;
        return -1;
    }

    private static Course course(double doseGy) {
        for (int i = DOSE_FROM.length - 1; i >= 0; i--) {
            if (doseGy >= DOSE_FROM[i]) return COURSES[i];
        }
        return null;
    }
}
