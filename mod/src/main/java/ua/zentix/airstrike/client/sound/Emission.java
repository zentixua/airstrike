package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.flight.FlightTrack;

/**
 * Что слушатель слышит от снаряда в этот тик: «запаздывающий» момент излучения {@code time} и всё, что из него
 * выводят слои звука ({@link EngineSound.Layer#tone}) — положение, расстояние, направление на ухо, скорость, Доплер,
 * фаза полёта, сколько до цели и насколько он идёт на неё ({@link #towardAim}). Считается раз в тик на снаряд, общий
 * для всех его слоёв.
 *
 * @param nx        единичный вектор от источника к уху
 * @param radial    проекция скорости на него (больше нуля — снаряд идёт на слушателя)
 * @param towardAim насколько снаряд идёт на цель, 0..1 ({@link #toward})
 * @param onboard   слушатель сидит на самом снаряде (камера снаряда)
 */
record Emission(double time, double x, double y, double z, double distance, double nx, double ny, double nz, Vec3 velocity,
                double radial, double doppler, int phase, double phaseAge, double aimDistance, double towardAim, boolean onboard) {
    /**
     * Курс по горизонтали не дальше этого угла от направления на цель — снаряд идёт на неё (заход по прямой), дальше
     * {@link #ASIDE_DEG} — обходит маршрут сбоку; между — плавно. Первое плечо обхода ({@code Route.plan}) уходит
     * от направления на цель на 38° и больше — оно за краем.
     */
    static final double TOWARD_AIM_DEG = 15, ASIDE_DEG = 35;
    /** Слушатель впереди снаряда, если тот идёт на него не дальше этого угла от курса; позади — с {@link #BEHIND_DEG}. */
    static final double AHEAD_DEG = 45, BEHIND_DEG = 100;
    private static final double TOWARD_AIM_COS = Math.cos(Math.toRadians(TOWARD_AIM_DEG)), ASIDE_COS = Math.cos(Math.toRadians(ASIDE_DEG));
    private static final double AHEAD_COS = Math.cos(Math.toRadians(AHEAD_DEG)), BEHIND_COS = Math.cos(Math.toRadians(BEHIND_DEG));


    /** Слышимое из момента te: его данные о снаряде должны быть ({@link FlightTrack#covers}). */
    static Emission at(FlightTrack track, double te, Vec3 ear, boolean onboard) {
        double[] p = new double[3];
        track.at(te, p);
        double dx = ear.x - p[0], dy = ear.y - p[1], dz = ear.z - p[2];
        double d = Math.max(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double nx = dx / d, ny = dy / d, nz = dz / d;
        Vec3 v = track.velocity(te);
        Vec3 aim = track.aim(te);
        double ax = aim.x - p[0], az = aim.z - p[2];
        return new Emission(te, p[0], p[1], p[2], d, nx, ny, nz, v, v.x * nx + v.y * ny + v.z * nz,
                Acoustics.doppler(v.x, v.y, v.z, nx, ny, nz), track.phase(te), track.phaseAge(te),
                Math.sqrt(ax * ax + sq(aim.y - p[1]) + az * az), toward(v.x, v.z, ax, az), onboard);
    }

    /**
     * Насколько курс (vx, vz) идёт на точку (ax, az) от снаряда, по горизонтали: 1 — не дальше {@link #TOWARD_AIM_DEG},
     * 0 — дальше {@link #ASIDE_DEG}, между — плавно (на повороте маршрута свист подлёта не включается рывком); над самой
     * целью — 1.
     */
    static double toward(double vx, double vz, double ax, double az) {
        double a = Math.sqrt(ax * ax + az * az), v = Math.sqrt(vx * vx + vz * vz);
        if (a < 1) return 1;
        if (v < 1.0e-6) return 0;
        return Acoustics.smoothstep(ASIDE_COS, TOWARD_AIM_COS, (vx * ax + vz * az) / (v * a));
    }

    /**
     * Насколько слушатель впереди снаряда: 1 — снаряд идёт на него не дальше {@link #AHEAD_DEG} от курса, 0 — слушатель
     * сбоку-сзади (дальше {@link #BEHIND_DEG}), между — плавно. Шум вентилятора и рассекаемого воздуха уносит вперёд:
     * на пролёте он стихает за доли секунды, а не обрывается в точке, где снаряд ближе всего.
     */
    double ahead() {
        double v = velocity.length();
        return v < 1.0e-6 ? 0 : Acoustics.smoothstep(BEHIND_COS, AHEAD_COS, radial / v);
    }

    private static double sq(double x) {
        return x * x;
    }

    Vec3 position() {
        return new Vec3(x, y, z);
    }

    boolean approaching() {
        return radial > 0;
    }
}
