package ua.zentix.airstrike.client.fx;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.airstrike.warhead.GroundMaterial;

/** Ванильные частицы грунта по материалу и точки по рельефу (свои частицы эффектов — {@link ua.zentix.airstrike.client.fx.particle.Fx}). */
final class FxParticles {
    private FxParticles() {}

    static ParticleOptions block(GroundMaterial m) {
        return new BlockParticleOption(ParticleTypes.BLOCK, m.particleBlock());
    }

    static ParticleOptions fallingDust(GroundMaterial m) {
        return new BlockParticleOption(ParticleTypes.FALLING_DUST, m.particleBlock());
    }

    static ParticleOptions dust(GroundMaterial m, float scale) {
        return new DustParticleOptions(new Vector3f(m.r, m.g, m.b), scale);
    }

    /** Точка на поверхности на расстоянии r по курсу yaw от центра (клиенту известна только карта высот MOTION_BLOCKING). */
    static Vec3 ground(Level level, Vec3 c, double yawDeg, double r) {
        double a = Math.toRadians(yawDeg);
        double x = c.x - Math.sin(a) * r, z = c.z + Math.cos(a) * r;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        return new Vec3(x, y, z);
    }
}
