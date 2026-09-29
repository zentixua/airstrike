package ua.zentix.airstrike.scenario.trailer;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.function.ToDoubleBiFunction;

/**
 * Путь истребителя по тикам: разворот с креном по физике координированного виража (tg крена = v²·κ / g) —
 * самолёт кренится, и от крена поворачивает, а не наоборот. Строится назад от конечной точки, чтобы разворот
 * кончался там, где нужно кадру; высота — не ниже {@code clearance} над крышами (плавно, заранее, как огибание
 * рельефа), тангаж — по набору высоты.
 * <p>
 * Minecraft: курс 0 — юг (+Z), 90 — запад; крен плюс — правое крыло вниз (как у {@link Aircraft#fly}).
 */
final class FlightPath {
    /** g в блоках за тик² (9.8 м/с² при 20 тиках в секунду). */
    static final double G = 9.8 / 400;

    private final Vec3[] pos;
    private final float[] yaw, pitch, roll;
    final double speed;

    private FlightPath(Vec3[] pos, float[] yaw, float[] pitch, float[] roll, double speed) {
        this.pos = pos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.speed = speed;
    }

    /**
     * Прямая, вираж и снова прямая, в конце — точка {@code end} курсом {@code endYaw}.
     *
     * @param ticks   длина пути в тиках
     * @param bank    крен на вираже, градусы (минус — влево, разворот влево)
     * @param rollIn  тик начала ввода в крен, от начала пути
     * @param rollOut тик конца вывода из крена
     * @param ease    за сколько тиков крен вводится и выводится
     */
    static FlightPath turn(Vec3 end, float endYaw, double speed, int ticks, float bank, int rollIn, int rollOut, int ease) {
        float[] roll = new float[ticks + 1];
        for (int i = 0; i <= ticks; i++) {
            double k = Math.min(smooth((i - rollIn) / (double) ease), smooth((rollOut - i) / (double) ease));
            roll[i] = (float) (bank * k);
        }
        // курс назад от конца: поворот за тик ω = v·κ = g·tg(крен)/v; крен вправо (плюс) — поворот вправо, а у
        // Minecraft это рост курса (с юга направо — на запад, 0 → 90)
        float[] yaw = new float[ticks + 1];
        yaw[ticks] = endYaw;
        for (int i = ticks; i > 0; i--) {
            double rate = G * Math.tan(Math.toRadians(roll[i])) / speed;
            yaw[i - 1] = (float) (yaw[i] - Math.toDegrees(rate));
        }
        Vec3[] pos = new Vec3[ticks + 1];
        pos[ticks] = end;
        for (int i = ticks; i > 0; i--) pos[i - 1] = pos[i].subtract(heading(yaw[i], 0).scale(speed));
        return new FlightPath(pos, yaw, new float[ticks + 1], roll, speed);
    }

    /**
     * Тот же путь, поднятый над крышами: {@code height(x, z)} — высота рельефа с постройками. Подъём начинается
     * за {@code look} тиков до препятствия и сглажен, чтобы самолёт не прыгал; тангаж — по наклону пути.
     */
    FlightPath clear(ToDoubleBiFunction<Double, Double> height, double clearance, int look) {
        int n = pos.length;
        double[] need = new double[n];
        for (int i = 0; i < n; i++) {
            double h = Double.NEGATIVE_INFINITY;
            // под крыльями и чуть в стороны
            for (int dx = -12; dx <= 12; dx += 6) {
                for (int dz = -12; dz <= 12; dz += 6) h = Math.max(h, height.applyAsDouble(pos[i].x + dx, pos[i].z + dz));
            }
            need[i] = Math.max(pos[i].y, h + clearance);
        }
        double[] env = new double[n];
        for (int i = 0; i < n; i++) {
            double m = need[i];
            for (int j = Math.max(0, i - look); j <= Math.min(n - 1, i + look); j++) m = Math.max(m, need[j]);
            env[i] = m;
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            int c = 0;
            for (int j = Math.max(0, i - look); j <= Math.min(n - 1, i + look); j++, c++) s += env[j];
            y[i] = Math.max(need[i], s / c);
        }
        Vec3[] p = new Vec3[n];
        float[] pitch = new float[n];
        for (int i = 0; i < n; i++) p[i] = new Vec3(pos[i].x, y[i], pos[i].z);
        for (int i = 0; i < n; i++) {
            double dy = y[Math.min(n - 1, i + 1)] - y[Math.max(0, i - 1)];
            double dh = 2 * speed;
            pitch[i] = (float) -Math.toDegrees(Math.atan2(dy, dh));
        }
        return new FlightPath(p, yaw, pitch, roll, speed);
    }

    int ticks() {
        return pos.length - 1;
    }

    Vec3 start() {
        return pos[0];
    }

    /** Место на тике {@code t} (с долей; до начала и за концом — по прямой). */
    Vec3 at(double t) {
        int n = pos.length - 1;
        if (t >= n) return pos[n].add(heading(yaw[n], pitch[n]).scale((t - n) * speed));
        if (t <= 0) return pos[0].add(heading(yaw[0], pitch[0]).scale(t * speed));
        int i = (int) t;
        return pos[i].lerp(pos[i + 1], t - i);
    }

    float yaw(double t) {
        return sample(yaw, t);
    }

    float pitch(double t) {
        return sample(pitch, t);
    }

    float roll(double t) {
        return sample(roll, t);
    }

    /** Все точки пути (для чанков коридора). */
    Vec3[] points() {
        return pos.clone();
    }

    /** Нос по курсу и тангажу (тангаж плюс — нос вниз). */
    static Vec3 heading(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static float sample(float[] a, double t) {
        int n = a.length - 1;
        if (t >= n) return a[n];
        if (t <= 0) return a[0];
        int i = (int) t;
        return (float) Mth.lerp(t - i, a[i], a[i + 1]);
    }

    private static double smooth(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
