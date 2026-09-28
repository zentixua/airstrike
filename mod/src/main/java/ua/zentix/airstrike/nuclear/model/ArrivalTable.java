package ua.zentix.airstrike.nuclear.model;

/**
 * Время прихода фронта ударной волны: интеграл dR/U по логарифмической сетке (1000 узлов от 1 м),
 * между узлами — линейная интерполяция, за таблицей — скорость звука.
 */
public final class ArrivalTable {
    private static final int NODES = 1000;
    private static final double FIRST_NODE_M = 1.0;

    /** Дальности, м (первый узел — 0), по возрастанию. */
    private final double[] range;
    /** Время прихода в узлы, с, по возрастанию. */
    private final double[] time;

    private ArrivalTable(double[] range, double[] time) {
        this.range = range;
        this.time = time;
    }

    public static ArrivalTable of(double yieldKt, double maxRangeM) {
        double max = Math.max(maxRangeM, FIRST_NODE_M * 2);
        double[] r = new double[NODES + 1];
        double[] t = new double[NODES + 1];
        double step = Math.log(max / FIRST_NODE_M) / (NODES - 1);
        double prevSlowness = 1 / BlastModel.frontSpeed(BlastModel.overpressureKpa(0, yieldKt));
        for (int i = 1; i <= NODES; i++) {
            r[i] = FIRST_NODE_M * Math.exp(step * (i - 1));
            double slowness = 1 / BlastModel.frontSpeed(BlastModel.overpressureKpa(r[i], yieldKt));
            t[i] = t[i - 1] + (r[i] - r[i - 1]) * 0.5 * (slowness + prevSlowness);
            prevSlowness = slowness;
        }
        return new ArrivalTable(r, t);
    }

    /** Дальность последнего узла, м. */
    public double maxRange() {
        return range[NODES];
    }

    /** Время прихода фронта на дальность R, с. */
    public double arrivalSeconds(double rangeM) {
        if (rangeM <= 0) return 0;
        if (rangeM >= range[NODES]) return time[NODES] + (rangeM - range[NODES]) / BlastModel.SOUND_SPEED;
        return interpolate(range, time, rangeM);
    }

    /** Радиус фронта в момент t, с (обратная к {@link #arrivalSeconds}). */
    public double radiusAt(double seconds) {
        if (seconds <= 0) return 0;
        if (seconds >= time[NODES]) return range[NODES] + (seconds - time[NODES]) * BlastModel.SOUND_SPEED;
        return interpolate(time, range, seconds);
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
