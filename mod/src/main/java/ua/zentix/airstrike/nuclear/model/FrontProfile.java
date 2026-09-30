package ua.zentix.airstrike.nuclear.model;

/**
 * Приход фронта в игре — в блоках и тиках. До {@code slowFrom} блоков — как у модели ({@link ArrivalTable}: сверхзвук,
 * шар и вспышка как в жизни), дальше фронт плавно замедляется до {@link #MIN_SPEED} блоков за тик к {@code slowTo} и
 * дальше идёт с ней: при настоящих 17 блоках за тик прорисовка в 8 чанков проходится за 0.4 с, и стена пыли и огня,
 * которая убивает, приходит мгновенно (Артём 30.09.2026). Давление — по-прежнему функция расстояния, меняется только
 * время прихода; стена пыли, звук, урон, отброс и руины берут время отсюда и приходят вместе.
 */
public final class FrontProfile {
    /** Скорость фронта вдали, блоков за тик (60 блоков в секунду). */
    public static final double MIN_SPEED = 3;
    /** Докуда фронт замедляется, блоки (не ближе трёх {@code slowFrom}). */
    public static final double SLOW_TO = 600;
    private static final int NODES = 2000;

    /** Расстояния, блоки (первый узел — 0), по возрастанию. */
    private final double[] range;
    /** Время прихода в узлы, тики, по возрастанию. */
    private final double[] time;

    private FrontProfile(double[] range, double[] time) {
        this.range = range;
        this.time = time;
    }

    /**
     * @param model    приход фронта модели, м и с
     * @param scale    блоков на метр модели (время фронта в тиках — секунды × 20 × scale)
     * @param slowFrom докуда фронт идёт как у модели, блоки
     * @param maxRange до какого расстояния таблица, блоки (дальше — {@link #MIN_SPEED})
     */
    public static FrontProfile of(ArrivalTable model, double scale, double slowFrom, double maxRange) {
        double slowTo = Math.max(SLOW_TO, slowFrom * 3);
        double max = Math.max(maxRange, slowTo) * 1.05;
        double[] r = new double[NODES + 1], t = new double[NODES + 1];
        double v0 = speed(model, scale, slowFrom);
        for (int i = 1; i <= NODES; i++) {
            // гуще у эпицентра: там фронт меняется быстрее всего
            double f = (double) i / NODES;
            r[i] = max * f * f;
            double dr = r[i] - r[i - 1];
            double dtModel = ticks(model, scale, r[i]) - ticks(model, scale, r[i - 1]);
            double mid = (r[i] + r[i - 1]) * 0.5;
            t[i] = t[i - 1] + Math.max(dtModel, dr / limit(mid, slowFrom, slowTo, v0));
        }
        return new FrontProfile(r, t);
    }

    /** Предел скорости на расстоянии {@code r}, блоков за тик: до {@code from} нет, дальше плавно до {@link #MIN_SPEED}. */
    static double limit(double r, double from, double to, double v0) {
        if (r <= from) return Double.POSITIVE_INFINITY;
        double f = Math.min(1, (r - from) / (to - from));
        double s = f * f * (3 - 2 * f);
        return v0 + (MIN_SPEED - v0) * s;
    }

    private static double ticks(ArrivalTable model, double scale, double blocks) {
        return model.arrivalSeconds(blocks / scale) * 20 * scale;
    }

    /** Скорость фронта модели на расстоянии {@code blocks}, блоков за тик (не меньше {@link #MIN_SPEED}). */
    private static double speed(ArrivalTable model, double scale, double blocks) {
        double h = Math.max(0.5, blocks * 0.01);
        double dt = ticks(model, scale, blocks + h) - ticks(model, scale, Math.max(0, blocks - h));
        return Math.max(MIN_SPEED, (blocks + h - Math.max(0, blocks - h)) / Math.max(dt, 1e-9));
    }

    /** Через сколько тиков фронт дойдёт до расстояния {@code blocks} от точки подрыва. */
    public double arrivalTicks(double blocks) {
        if (blocks <= 0) return 0;
        if (blocks >= range[NODES]) return time[NODES] + (blocks - range[NODES]) / MIN_SPEED;
        return interpolate(range, time, blocks);
    }

    /** Расстояние фронта от точки подрыва через {@code ticks}, блоки (обратная к {@link #arrivalTicks}). */
    public double radiusAt(double ticks) {
        if (ticks <= 0) return 0;
        if (ticks >= time[NODES]) return range[NODES] + (ticks - time[NODES]) * MIN_SPEED;
        return interpolate(time, range, ticks);
    }

    private static double interpolate(double[] xs, double[] ys, double x) {
        int lo = 0, hi = xs.length - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (xs[m] <= x) lo = m;
            else hi = m;
        }
        double f = (x - xs[lo]) / (xs[hi] - xs[lo]);
        return ys[lo] + f * (ys[hi] - ys[lo]);
    }
}
