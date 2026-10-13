package ua.zentix.incoming.scenario.trailer;

import ua.zentix.incoming.entity.ArrivalQueue;

import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Где снаряд у клиента повтора по тикам. Журнал пишет место сущности у записывающего клиента; до очереди мест мода
 * ({@link ArrivalQueue}, 13.10) это место последнего пакета за тик — то стоит, то прыгает на два шага, а повтор с
 * нынешним модом показывает тот же снаряд ровно, по месту на тик. Камера, поставленная по такому журналу (своя камера
 * сценария, погоня), ведёт не то место, где снаряд в кадре: на тиках рывков он уходит по кадру на полшага (FPV p01a в
 * 9 блоках за ракетой — 14 % полукадра). Здесь из журнала восстанавливаются пакеты (сколько шагов в тике — по самому
 * короткому сдвигу соседних тиков: один пакет) и прогоняются той же очередью, что у клиента (на снятом пути рывков —
 * точно). Журнал, записанный уже с очередью, сюда не идёт: по его шагам пакеты не восстановить, и место исказилось бы
 * (до 14 блоков) — {@link EventLog#read} берёт его как есть, а этот путь — только по {@code INCOMING_FILM_SHOWN}.
 */
final class Shown {
    /** Шаг одного пакета — самый короткий ненулевой сдвиг за тик среди стольких тиков с каждой стороны. */
    private static final int SPAN = 5;
    /** Больше пакетов за тик не бывает: дальше — уже не рывок, а другой путь. */
    private static final int MAX_PACKETS = 3;

    private Shown() {}

    /**
     * Места по тикам, как их покажет клиент повтора. {@code server} — тики, записанные по пакетам дальних полётов (сущности
     * у клиента не было): их нет в кадре, и они остаются как есть.
     */
    static TreeMap<Integer, double[]> asShown(NavigableMap<Integer, double[]> raw, Set<Integer> server) {
        TreeMap<Integer, double[]> out = new TreeMap<>();
        Integer[] ticks = raw.keySet().toArray(new Integer[0]);
        int i = 0;
        while (i < ticks.length) {
            if (server.contains(ticks[i])) {
                out.put(ticks[i], raw.get(ticks[i]));
                i++;
                continue;
            }
            // отрезок подряд идущих тиков по сущности
            int j = i;
            while (j + 1 < ticks.length && ticks[j + 1] == ticks[j] + 1 && !server.contains(ticks[j + 1])) j++;
            double[][] run = new double[j - i + 1][];
            for (int k = i; k <= j; k++) run[k - i] = raw.get(ticks[k]);
            double[][] shown = run(run);
            for (int k = i; k <= j; k++) out.put(ticks[k], shown[k - i]);
            i = j + 1;
        }
        return out;
    }

    /** Отрезок подряд идущих тиков: первый — место появления, дальше — пакеты по тикам через очередь. */
    static double[][] run(double[][] a) {
        int n = a.length;
        double[][] r = new double[n][];
        ArrivalQueue q = new ArrivalQueue();
        r[0] = a[0];
        for (int t = 1; t < n; t++) {
            double d = dist(a[t], a[t - 1]);
            double one = Double.MAX_VALUE;
            for (int k = Math.max(1, t - SPAN); k <= Math.min(n - 1, t + SPAN); k++) {
                double s = dist(a[k], a[k - 1]);
                if (s > 1e-4) one = Math.min(one, s);
            }
            int packets = d < 1e-4 ? 0 : (int) Math.max(1, Math.min(MAX_PACKETS, Math.round(d / one)));
            for (int k = 1; k <= packets; k++) {
                double f = (double) k / packets;
                q.add(new ArrivalQueue.Sample(lerp(a[t - 1][0], a[t][0], f), lerp(a[t - 1][1], a[t][1], f), lerp(a[t - 1][2], a[t][2], f), 0, 0));
            }
            ArrivalQueue.Sample s = q.next(r[t - 1][0], r[t - 1][1], r[t - 1][2], 0, 0);
            r[t] = s == null ? r[t - 1] : new double[]{s.x(), s.y(), s.z()};
        }
        return r;
    }

    private static double dist(double[] p, double[] q) {
        double dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
