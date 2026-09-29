package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;

/**
 * Что слушатель слышит от снаряда в этот тик: «запаздывающий» момент излучения {@code time} и всё, что из него
 * выводят слои звука ({@link EngineSound.Layer#tone}) — положение, расстояние, направление на ухо, скорость, Доплер
 * и фаза полёта. Считается раз в тик на снаряд, общий для всех его слоёв.
 *
 * @param nx      единичный вектор от источника к уху
 * @param radial  проекция скорости на него (больше нуля — снаряд идёт на слушателя)
 * @param onboard слушатель сидит на самом снаряде (камера снаряда)
 */
record Emission(double time, double x, double y, double z, double distance, double nx, double ny, double nz, Vec3 velocity,
                double radial, double doppler, int phase, double phaseAge, boolean onboard) {

    /** Слышимое из момента te: его данные о снаряде должны быть ({@link SourceTrack#covers}). */
    static Emission at(SourceTrack track, double te, Vec3 ear, boolean onboard) {
        double[] p = new double[3];
        track.at(te, p);
        double dx = ear.x - p[0], dy = ear.y - p[1], dz = ear.z - p[2];
        double d = Math.max(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double nx = dx / d, ny = dy / d, nz = dz / d;
        Vec3 v = track.velocity(te);
        return new Emission(te, p[0], p[1], p[2], d, nx, ny, nz, v, v.x * nx + v.y * ny + v.z * nz,
                Acoustics.doppler(v.x, v.y, v.z, nx, ny, nz), track.phase(te), track.phaseAge(te), onboard);
    }

    Vec3 position() {
        return new Vec3(x, y, z);
    }

    boolean approaching() {
        return radial > 0;
    }
}
