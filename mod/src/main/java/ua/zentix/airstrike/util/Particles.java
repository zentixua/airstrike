package ua.zentix.airstrike.util;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Частицы на клиенте с той же семантикой, что у команды {@code particle <тип> x y z dx dy dz speed count force}:
 * при count = 0 — одна частица со скоростью (dx, dy, dz)·speed, иначе count частиц с разбросом
 * по Гауссу (dx, dy, dz) и случайной скоростью·speed. «force» — видны издалека (дальше 32 блоков).
 * Так эффекты датапака переносятся без подбора заново. На сервере ничего не делает.
 */
public final class Particles {
    private Particles() {}

    public static void burst(Level level, ParticleOptions type, double x, double y, double z,
                             double dx, double dy, double dz, double speed, int count) {
        if (!level.isClientSide) return;
        RandomSource r = level.random;
        if (count == 0) {
            level.addAlwaysVisibleParticle(type, true, x, y, z, dx * speed, dy * speed, dz * speed);
            return;
        }
        for (int i = 0; i < count; i++) {
            double ox = r.nextGaussian() * dx, oy = r.nextGaussian() * dy, oz = r.nextGaussian() * dz;
            double vx = r.nextGaussian() * speed, vy = r.nextGaussian() * speed, vz = r.nextGaussian() * speed;
            level.addAlwaysVisibleParticle(type, true, x + ox, y + oy, z + oz, vx, vy, vz);
        }
    }

    public static void burst(Level level, ParticleOptions type, Vec3 p, double dx, double dy, double dz, double speed, int count) {
        burst(level, type, p.x, p.y, p.z, dx, dy, dz, speed, count);
    }
}
