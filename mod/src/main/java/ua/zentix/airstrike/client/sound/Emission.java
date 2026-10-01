package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.flight.FlightTrack;

/**
 * Что слушатель слышит от снаряда в этот тик: «запаздывающий» момент излучения {@code time} и всё, что из него
 * выводят слои звука ({@link EngineSound.Layer#tone}) — положение, расстояние, направление на ухо, скорость, Доплер,
 * фаза полёта, сколько до цели и идёт ли он на неё ({@link #towardAim}). Считается раз в тик на снаряд, общий для всех
 * его слоёв.
 *
 * @param nx      единичный вектор от источника к уху
 * @param radial  проекция скорости на него (больше нуля — снаряд идёт на слушателя)
 * @param onboard слушатель сидит на самом снаряде (камера снаряда)
 */
record Emission(double time, double x, double y, double z, double distance, double nx, double ny, double nz, Vec3 velocity,
                double radial, double doppler, int phase, double phaseAge, double aimDistance, boolean towardAim, boolean onboard) {
    /**
     * Снаряд идёт на цель, если его курс по горизонтали не дальше этого угла от направления на неё: заход на цель
     * по прямой, а не обход маршрута сбоку.
     */
    static final double TOWARD_AIM_DEG = 30;
    private static final double TOWARD_AIM_COS = Math.cos(Math.toRadians(TOWARD_AIM_DEG));


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

    /** Курс (vx, vz) — на точку (ax, az) от снаряда, по горизонтали; над самой целью — всегда на неё. */
    static boolean toward(double vx, double vz, double ax, double az) {
        double a = Math.sqrt(ax * ax + az * az), v = Math.sqrt(vx * vx + vz * vz);
        if (a < 1) return true;
        return v > 1.0e-6 && (vx * ax + vz * az) >= TOWARD_AIM_COS * v * a;
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
