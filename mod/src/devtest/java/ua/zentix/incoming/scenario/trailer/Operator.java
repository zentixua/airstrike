package ua.zentix.incoming.scenario.trailer;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.warhead.Warheads;

/**
 * Камера «как снимал человек»: путь кандидата ({@link Rig.Candidate}) — то, куда оператор хочет смотреть, а в вывод идёт
 * то, как такой оператор это снял бы (13.10, замечания Артёма к черновику: камеры слишком ровные и геометрические, резко
 * тормозят, после удара не трясутся; погоня за шахедом дёргается — «подтормаживает»). Путь пересчитывается заранее на всё
 * окно с запасом, одинаково для оценки ({@link Rig.Tally}) и вывода, по стилю камеры ({@link Style}):
 * <ul>
 * <li>место — сглаживание без запаздывания (окно известно целиком): у FPV и погони — только рывки журнала (места снаряда
 * у клиента записи то стояли тик, то прыгали на два), у камеры сценария (кран, тележка, проезд) — ещё и разгон и
 * торможение вместо старта и остановки с места;</li>
 * <li>взгляд — то же сглаживание, у ручной камеры и штатива — ещё и пружина: взгляд догоняет цель с запаздыванием и чуть
 * проскакивает, как у человека, а следящая камера пуска (как на пусках NASA) ведёт ровно, с упреждением;</li>
 * <li>руки — медленное покачивание и дрожь (сумма синусов 0,15–4 Гц, по кандидату — своё зерно), в градусах — по углу
 * кадра: у длинного фокуса мельче, чтобы на экране было одинаково;</li>
 * <li>взрывы — толчок, когда до камеры дошла волна (17,15 блока за тик, как звук мода): размах по расстоянию и огненному
 * шару удара, 4–6 Гц с затуханием, взгляд вскидывает вверх, место толкает от взрыва. Тряска самого мода при выводе
 * выключена ({@link ReplayFilm}: 5° с частотой ~10 Гц — «как эпилептика», p26), кроме ядерных планов.</li>
 * </ul>
 */
final class Operator {
    private Operator() {}

    /** Стиль камеры по кандидату. */
    enum Style {
        /** FPV и погоня у самого снаряда: место и взгляд — плотно за ним, дрожь дрона. */
        FPV(1.5, 1.5, 0, 0, 0.35, 0.35, 0.9, 0.05, 0.4, 4.0, 1.1),
        /** Следящая камера пуска (длинный фокус, ровная панорама, как съёмка пусков NASA и SpaceX). */
        TRACKER(0, 2.5, 0, 0, 0.04, 0.04, 0, 0, 0.3, 3.0, 0.4),
        /** Штатив с головой: ведёт с запаздыванием, почти без дрожи. */
        TRIPOD(0, 2, 1.8, 0.85, 0.06, 0.05, 0, 0, 0.3, 3.0, 0.5),
        /** Камера сценария — оператор с камерой в руках или на кране. */
        HAND(7, 1.5, 2.2, 0.6, 0.3, 0.25, 0.35, 0.04, 0.15, 2.5, 1);

        /** Сглаживание места и взгляда, тиков (σ); пружина взгляда — частота, Гц (0 — нет) и затухание. */
        final double posSigma, lookSigma, springHz, damping;
        /** Дрожь рук при угле кадра 60°: курс, тангаж, крен (°), место (блоков); полоса частот, Гц. */
        final double yaw, pitch, roll, pos, fLo, fHi;
        /** Доля толчка от взрыва. */
        final double shake;

        Style(double posSigma, double lookSigma, double springHz, double damping, double yaw, double pitch, double roll, double pos,
              double fLo, double fHi, double shake) {
            this.posSigma = posSigma;
            this.lookSigma = lookSigma;
            this.springHz = springHz;
            this.damping = damping;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.pos = pos;
            this.fLo = fLo;
            this.fHi = fHi;
            this.shake = shake;
        }
    }

    /** Запас до окна: вывод начинается за {@code EXPORT_LEAD} тиков, погоня и штатив считают взгляд с a − 60. */
    static final int LEAD = 120, TAIL = 10;
    /** Толчок от взрыва: размах взгляда — {@code K}·огненный шар / (расстояние + 8), не больше {@code MAX}, °. */
    static final double K = 6, MAX = 3.5;

    /**
     * Стиль по кандидату; камера сценария, что летит быстрее {@link #FLYING} блока за тик в среднем по окну (FPV по метро,
     * над боем), — FPV: сглаживание места на 7 тиков срезало бы её углы в стены.
     */
    static Style style(Rig.Candidate c, int a, int b) {
        if (c.close()) return Style.FPV;
        if (c.name().startsWith("track")) return Style.TRACKER;
        if (c.name().startsWith("tripod")) return Style.TRIPOD;
        double way = 0;
        for (int t = a; t < b; t++) way += c.path().at(t + 1).pos().distanceTo(c.path().at(t).pos());
        return b > a && way / (b - a) > FLYING ? Style.FPV : Style.HAND;
    }

    static final double FLYING = 0.8;

    /** Кандидат, снятый человеком; имя — то же. */
    static Rig.Candidate human(Rig.Candidate c, EventLog.Data d, int a, int b) {
        Style st = style(c, a, b);
        CineCamera.Path path = humanize(c.path(), st, c.name().hashCode(), d, a - LEAD, b + TAIL);
        return new Rig.Candidate(c.name(), path, c.mm(), c.close(), c.designed());
    }

    /** Запасной кандидат — путь как есть (камера сценария без рук): если с руками объект уходит из кадра. */
    static Rig.Candidate raw(Rig.Candidate c) {
        return new Rig.Candidate(c.name() + "-raw", c.path(), c.mm(), c.close(), false);
    }

    static CineCamera.Path humanize(CineCamera.Path in, Style st, long seed, EventLog.Data d, int from, int to) {
        int n = to - from + 1;
        double[] x = new double[n], y = new double[n], z = new double[n], yaw = new double[n], pitch = new double[n],
                roll = new double[n], fov = new double[n];
        for (int i = 0; i < n; i++) {
            Pose p = in.at(from + i);
            x[i] = p.pos().x;
            y[i] = p.pos().y;
            z[i] = p.pos().z;
            // курс без скачка через ±180°
            yaw[i] = i == 0 ? p.yaw() : yaw[i - 1] + Mth.wrapDegrees(p.yaw() - (float) yaw[i - 1]);
            pitch[i] = p.pitch();
            roll[i] = p.roll();
            fov[i] = p.fov();
        }
        x = gauss(x, st.posSigma);
        y = gauss(y, st.posSigma);
        z = gauss(z, st.posSigma);
        yaw = gauss(yaw, st.lookSigma);
        pitch = gauss(pitch, st.lookSigma);
        if (st.springHz > 0) {
            yaw = spring(yaw, st.springHz, st.damping);
            pitch = spring(pitch, st.springHz, st.damping);
        }
        RandomSource r = RandomSource.create(seed);
        double[] ny = noise(r, n, st.yaw, st.fLo, st.fHi), np = noise(r, n, st.pitch, st.fLo, st.fHi), nr = noise(r, n, st.roll, st.fLo, st.fHi);
        double[] nx = noise(r, n, st.pos, st.fLo * 0.5, st.fHi * 0.5), nyy = noise(r, n, st.pos, st.fLo * 0.5, st.fHi * 0.5),
                nz = noise(r, n, st.pos, st.fLo * 0.5, st.fHi * 0.5);
        for (int i = 0; i < n; i++) {
            // в градусах дрожь мельче у длинного фокуса: на экране — одинаково
            double q = Mth.clamp(fov[i] / 60, 0.25, 1.3);
            yaw[i] += ny[i] * q;
            pitch[i] += np[i] * q;
            roll[i] += nr[i];
            x[i] += nx[i];
            y[i] += nyy[i];
            z[i] += nz[i];
        }
        if (d.nukes.isEmpty()) shake(st, r, d, from, x, y, z, yaw, pitch, roll);
        double[] fx = x, fy = y, fz = z, fyaw = yaw, fpitch = pitch, froll = roll, ffov = fov;
        return t -> {
            double u = Mth.clamp(t - from, 0, n - 1);
            int i = (int) Math.floor(u), j = Math.min(n - 1, i + 1);
            double k = u - i;
            return new Pose(new Vec3(Mth.lerp(k, fx[i], fx[j]), Mth.lerp(k, fy[i], fy[j]), Mth.lerp(k, fz[i], fz[j])),
                    (float) Mth.lerp(k, fyaw[i], fyaw[j]), (float) Mth.clamp(Mth.lerp(k, fpitch[i], fpitch[j]), -90, 90),
                    (float) Mth.lerp(k, froll[i], froll[j]), Mth.lerp(k, ffov[i], ffov[j]));
        };
    }

    /**
     * Толчки от взрывов журнала: волна доходит до камеры через расстояние / 17,15 тика после удара; размах — по огненному
     * шару удара и расстоянию, затухает за 5–15 тиков; взгляд вскидывает вверх и возвращает, место толкает от взрыва.
     */
    private static void shake(Style st, RandomSource r, EventLog.Data d, int from, double[] x, double[] y, double[] z, double[] yaw,
                              double[] pitch, double[] roll) {
        int n = x.length;
        double[] sy = new double[n], sp = new double[n], sr = new double[n];
        for (EventLog.Flight f : d.flights) {
            if (f.impact < 0 || f.impactPos == null || f.impact > from + n || f.impact < from - 80) continue;
            int at = Mth.clamp(f.impact - from, 0, n - 1);
            Vec3 cam = new Vec3(x[at], y[at], z[at]);
            double dist = cam.distanceTo(f.impactPos);
            double amp = Math.min(MAX, K * Rig.blastOf(f.weapon) / (dist + 8)) * st.shake;
            if (amp < 0.08) continue;
            double arrive = f.impact + dist / Warheads.FRONT_SPEED - from;
            double tau = 5 + amp * 2.5;
            double p1 = r.nextDouble() * 6.3, p2 = r.nextDouble() * 6.3, p3 = r.nextDouble() * 6.3, p4 = r.nextDouble() * 6.3,
                    p5 = r.nextDouble() * 6.3;
            Vec3 away = cam.subtract(f.impactPos);
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

    /** Сглаживание по Гауссу без запаздывания; за краями — крайние значения (камера там стоит). σ ≤ 0 — как есть. */
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

    /** Пружина: взгляд догоняет цель {@code target} (по тикам) с частотой {@code hz} и затуханием {@code damping}. */
    static double[] spring(double[] target, double hz, double damping) {
        double w = 2 * Math.PI * hz / 20, pos = target[0], vel = 0;
        double[] out = new double[target.length];
        for (int i = 0; i < target.length; i++) {
            for (int s = 0; s < 4; s++) {
                vel += (w * w * (target[i] - pos) - 2 * damping * w * vel) * 0.25;
                pos += vel * 0.25;
            }
            out[i] = pos;
        }
        return out;
    }

    /** Гладкий шум: пять синусов в полосе {@code fLo}–{@code fHi} Гц, размах ~1/f, средний квадрат — {@code rms}. */
    static double[] noise(RandomSource r, int n, double rms, double fLo, double fHi) {
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
