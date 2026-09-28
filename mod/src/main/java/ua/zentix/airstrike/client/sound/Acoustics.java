package ua.zentix.airstrike.client.sound;

/**
 * Физика звука без Minecraft (проверяется юнит-тестами): задержка распространения и Доплер.
 * <p>
 * Слушатель в момент {@code now} слышит то, что источник излучил в момент {@code t}, где
 * |p(t) − L| = c·(now − t) — «запаздывающее время». Отсюда и честный Доплер (частота растёт, пока источник
 * приближается), и то, что дальний слушатель ещё слышит мотор, когда снаряд уже взорвался, — ровно до прихода фронта.
 */
public final class Acoustics {
    /** Скорость звука: 343 м/с = 17.15 блока за тик. */
    public static final double SPEED = 17.15;

    private Acoustics() {}

    /** Траектория источника: положение в момент t (тики, дробные). */
    public interface Path {
        /** Положение в момент t; вне известного отрезка — ближайший край. */
        void at(double t, double[] out);

        /** Самый ранний известный момент. */
        double start();
    }

    /**
     * Момент излучения того, что слушатель в точке (lx, ly, lz) слышит сейчас: корень
     * f(t) = |p(t) − L| − c·(now − t). Сначала простая итерация t ← now − |p(t) − L| / c (сходится, пока источник
     * медленнее звука; самая быстрая бомба — 0.73 c), затем несколько шагов Ньютона для точности.
     */
    public static double emissionTime(Path path, double now, double lx, double ly, double lz) {
        double[] p = new double[3];
        double t = now;
        for (int i = 0; i < 6; i++) t = Math.max(path.start(), now - distance(path, t, lx, ly, lz, p) / SPEED);
        for (int i = 0; i < 6; i++) {
            double f = distance(path, t, lx, ly, lz, p) - SPEED * (now - t);
            if (Math.abs(f) < 1.0e-6) break;
            double h = 1.0e-3;
            double df = (distance(path, t + h, lx, ly, lz, p) - distance(path, t - h, lx, ly, lz, p)) / (2 * h) + SPEED;
            if (df < 1.0e-6) break;
            t = Math.max(path.start(), Math.min(now, t - f / df));
        }
        return t;
    }

    private static double distance(Path path, double t, double lx, double ly, double lz, double[] p) {
        path.at(t, p);
        return Math.sqrt(sq(p[0] - lx) + sq(p[1] - ly) + sq(p[2] - lz));
    }

    /**
     * Множитель частоты для движущегося источника и неподвижного слушателя: f' = f · c / (c − v·n),
     * где n — единичный вектор от источника к слушателю, v — скорость источника (блоков/тик).
     * Ограничен, чтобы у самого уха не было скачка до бесконечности.
     */
    public static double doppler(double vx, double vy, double vz, double nx, double ny, double nz) {
        double radial = vx * nx + vy * ny + vz * nz;
        radial = Math.max(-0.9 * SPEED, Math.min(0.9 * SPEED, radial));
        return SPEED / (SPEED - radial);
    }

    /** Громкость по расстоянию: полная ближе ref, дальше ~1/d, но не тише floor; за cutoff плавно гаснет. */
    public static double gain(double d, double ref, double floor, double cutoff) {
        double g = Math.min(1.0, ref / Math.max(d, 1.0e-3));
        g = Math.max(g, floor);
        if (d > cutoff) g *= Math.max(0, 1 - (d - cutoff) / 80.0);
        return g;
    }

    /** Плавная ступенька 0→1 между a и b. */
    public static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double sq(double x) {
        return x * x;
    }
}
