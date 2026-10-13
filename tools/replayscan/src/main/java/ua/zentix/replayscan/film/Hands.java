package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * Камера FPV «как снимал человек» — слой трейлера ({@code Operator}, стиль FPV, 13.10: замечания Артёма к черновику —
 * камеры слишком ровные, после удара не трясутся) поверх пути пилота ({@link Pilot}). Путь пересчитывается заранее на всё
 * окно, одинаково для оценки и вывода:
 * <ul>
 * <li>место и взгляд — сглаживание по Гауссу без запаздывания (окно известно целиком);</li>
 * <li>руки — дрожь дрона: сумма синусов 0,4–4 Гц (своё зерно у кандидата), в градусах — по углу кадра;</li>
 * <li>взрывы — толчок, когда до камеры дошла волна (17,15 блока за тик, как звук мода): размах по расстоянию и
 * огненному шару, 4–6 Гц с затуханием, взгляд вскидывает вверх, место толкает от взрыва. Тряску самого мода (5° с
 * частотой ~10 Гц) съёмка выключает в его настройке клиента ({@code cameraShake}).</li>
 * </ul>
 */
final class Hands {
    private Hands() {}

    /** Сглаживание места и взгляда, тиков (σ). */
    static final double POS_SIGMA = 1.5, LOOK_SIGMA = 1.5;
    /** Дрожь при угле кадра 60°: курс, тангаж, крен (°), место (блоков); полоса частот, Гц. */
    static final double YAW = 0.35, PITCH = 0.35, ROLL = 0.9, POS = 0.05, F_LO = 0.4, F_HI = 4;
    /** Толчок от взрыва: размах взгляда — {@code K}·огненный шар / (расстояние + 8), не больше {@code MAX}, °; доля у FPV. */
    static final double K = 6, MAX = 3.5, SHAKE = 1.1;
    /** Скорость волны взрыва, блоков за тик (как у звука Incoming). */
    static final double FRONT = 17.15;

    /** Взрыв: тик, место, поперечник огненного шара. */
    record Blast(int t, Vec3 pos, double size) {}

    static Cameras.Path humanize(Cameras.Path in, long seed, List<Blast> blasts, int from, int to) {
        int n = to - from + 1;
        double[] x = new double[n], y = new double[n], z = new double[n], yaw = new double[n], pitch = new double[n],
                roll = new double[n];
        double fov = in.at(from).fov();
        for (int i = 0; i < n; i++) {
            Cameras.Pose p = in.at(from + i);
            x[i] = p.pos().x;
            y[i] = p.pos().y;
            z[i] = p.pos().z;
            // курс без скачка через ±180°
            yaw[i] = i == 0 ? p.yaw() : yaw[i - 1] + Mth.wrapDegrees(p.yaw() - (float) yaw[i - 1]);
            pitch[i] = p.pitch();
            roll[i] = in.roll(from + i);
        }
        x = gauss(x, POS_SIGMA);
        y = gauss(y, POS_SIGMA);
        z = gauss(z, POS_SIGMA);
        yaw = gauss(yaw, LOOK_SIGMA);
        pitch = gauss(pitch, LOOK_SIGMA);
        Random r = new Random(seed);
        double[] ny = noise(r, n, YAW, F_LO, F_HI), np = noise(r, n, PITCH, F_LO, F_HI), nr = noise(r, n, ROLL, F_LO, F_HI);
        double[] nx = noise(r, n, POS, F_LO * 0.5, F_HI * 0.5), nyy = noise(r, n, POS, F_LO * 0.5, F_HI * 0.5),
                nz = noise(r, n, POS, F_LO * 0.5, F_HI * 0.5);
        // в градусах дрожь мельче у длинного фокуса: на экране — одинаково
        double q = Mth.clamp(fov / 60, 0.25, 1.3);
        for (int i = 0; i < n; i++) {
            yaw[i] += ny[i] * q;
            pitch[i] += np[i] * q;
            roll[i] += nr[i];
            x[i] += nx[i];
            y[i] += nyy[i];
            z[i] += nz[i];
        }
        shake(r, blasts, from, x, y, z, yaw, pitch, roll);
        double[] fx = x, fy = y, fz = z, fyaw = yaw, fpitch = pitch, froll = roll;
        return new Cameras.Path() {
            @Override
            public Cameras.Pose at(double t) {
                double u = Mth.clamp(t - from, 0, n - 1);
                int i = (int) Math.floor(u), j = Math.min(n - 1, i + 1);
                double k = u - i;
                return new Cameras.Pose(new Vec3(Mth.lerp(k, fx[i], fx[j]), Mth.lerp(k, fy[i], fy[j]), Mth.lerp(k, fz[i], fz[j])),
                        (float) Mth.lerp(k, fyaw[i], fyaw[j]), (float) Mth.clamp(Mth.lerp(k, fpitch[i], fpitch[j]), -90, 90), fov);
            }

            @Override
            public double roll(double t) {
                double u = Mth.clamp(t - from, 0, n - 1);
                int i = (int) Math.floor(u), j = Math.min(n - 1, i + 1);
                return Mth.lerp(u - i, froll[i], froll[j]);
            }
        };
    }

    /**
     * Толчки от взрывов: волна доходит до камеры через расстояние / {@link #FRONT} тиков после удара; размах — по огненному
     * шару и расстоянию, затухает за 5–15 тиков; взгляд вскидывает вверх и возвращает, место толкает от взрыва.
     */
    static void shake(Random r, List<Blast> blasts, int from, double[] x, double[] y, double[] z, double[] yaw, double[] pitch,
                      double[] roll) {
        int n = x.length;
        double[] sy = new double[n], sp = new double[n], sr = new double[n];
        for (Blast f : blasts) {
            if (f.t() > from + n || f.t() < from - 80) continue;
            int at = Mth.clamp(f.t() - from, 0, n - 1);
            Vec3 cam = new Vec3(x[at], y[at], z[at]);
            double dist = cam.distanceTo(f.pos());
            double amp = Math.min(MAX, K * f.size() / (dist + 8)) * SHAKE;
            if (amp < 0.08) continue;
            double arrive = f.t() + dist / FRONT - from;
            double tau = 5 + amp * 2.5;
            double p1 = r.nextDouble() * 6.3, p2 = r.nextDouble() * 6.3, p3 = r.nextDouble() * 6.3, p4 = r.nextDouble() * 6.3,
                    p5 = r.nextDouble() * 6.3;
            Vec3 away = cam.subtract(f.pos());
            away = away.lengthSqr() < 1e-6 ? Vec3.ZERO : away.normalize();
            double jolt = Math.min(0.35, amp * 0.1);
            for (int i = Math.max(0, (int) Math.ceil(arrive)); i < n; i++) {
                double u = i - arrive;
                if (u > tau * 5) break;
                double rise = 1 - Math.exp(-u / 0.8), env = rise * Math.exp(-u / tau);
                double w = 2 * Math.PI / 20;
                sy[i] += amp * env * (0.7 * Math.sin(w * 5.3 * u + p1) + 0.3 * Math.sin(w * 2.9 * u + p2));
                sp[i] += amp * env * (0.7 * Math.sin(w * 4.7 * u + p3) + 0.3 * Math.sin(w * 3.3 * u + p4))
                        // вскинуть взгляд и вернуть (тангаж Minecraft: меньше — выше)
                        - amp * 0.6 * rise * Math.exp(-u / (tau * 1.6));
                sr[i] += amp * 0.8 * env * Math.sin(w * 4.1 * u + p5);
                double push = jolt * (1 - Math.exp(-u / 0.8)) * Math.exp(-u / 3);
                x[i] += away.x * push;
                y[i] += away.y * push;
                z[i] += away.z * push;
            }
        }
        for (int i = 0; i < n; i++) {
            yaw[i] += Mth.clamp(sy[i], -5, 5);
            pitch[i] += Mth.clamp(sp[i], -5, 5);
            roll[i] += Mth.clamp(sr[i], -5, 5);
        }
    }

    /** Сглаживание по Гауссу без запаздывания; за краями — крайние значения. σ ≤ 0 — как есть. */
    static double[] gauss(double[] v, double sigma) {
        if (sigma <= 0) return v;
        int h = (int) Math.ceil(sigma * 3), n = v.length;
        double[] k = new double[2 * h + 1];
        double sum = 0;
        for (int i = -h; i <= h; i++) sum += k[i + h] = Math.exp(-i * i / (2 * sigma * sigma));
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = -h; j <= h; j++) s += k[j + h] * v[Mth.clamp(i + j, 0, n - 1)];
            out[i] = s / sum;
        }
        return out;
    }

    /** Гладкий шум: пять синусов в полосе {@code fLo}–{@code fHi} Гц, размах ~1/f, средний квадрат — {@code rms}. */
    static double[] noise(Random r, int n, double rms, double fLo, double fHi) {
        double[] out = new double[n];
        if (rms <= 0) return out;
        int m = 5;
        double[] f = new double[m], ph = new double[m], amp = new double[m];
        double power = 0;
        for (int j = 0; j < m; j++) {
            f[j] = fLo * Math.pow(fHi / fLo, (j + r.nextDouble()) / m);
            ph[j] = r.nextDouble() * Math.PI * 2;
            amp[j] = 1 / f[j];
            power += amp[j] * amp[j] / 2;
        }
        double scale = rms / Math.sqrt(power);
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < m; j++) s += amp[j] * Math.sin(2 * Math.PI * f[j] * i / 20 + ph[j]);
            out[i] = s * scale;
        }
        return out;
    }
}
