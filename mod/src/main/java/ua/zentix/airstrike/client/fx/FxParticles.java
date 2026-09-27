package ua.zentix.airstrike.client.fx;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.util.Particles;
import ua.zentix.airstrike.warhead.GroundMaterial;

import java.util.HashMap;
import java.util.Map;

/** Частицы эффектов: грунт по материалу, кольца по рельефу, частицы Supplementaries (если он есть). */
final class FxParticles {
    private static final Map<String, ParticleOptions> OPTIONAL = new HashMap<>();

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

    /** Частица другого мода по имени или null, если мода нет. */
    @Nullable
    static ParticleOptions optional(String id) {
        return OPTIONAL.computeIfAbsent(id, k -> {
            ParticleType<?> t = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(k));
            return t instanceof SimpleParticleType s ? s : null;
        });
    }

    static void optional(Level level, String id, Vec3 p, double dx, double dy, double dz, double speed, int count) {
        ParticleOptions o = optional(id);
        if (o != null) Particles.burst(level, o, p, dx, dy, dz, speed, count);
    }

    /** Точка на поверхности на расстоянии r по курсу yaw от центра (клиенту известна только карта высот MOTION_BLOCKING). */
    static Vec3 ground(Level level, Vec3 c, double yawDeg, double r) {
        double a = Math.toRadians(yawDeg);
        double x = c.x - Math.sin(a) * r, z = c.z + Math.cos(a) * r;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        return new Vec3(x, y, z);
    }

    /** Точка на сфере: курс, угол места (как «rotated yaw pitch positioned ^ ^ ^r»). */
    static Vec3 sphere(Vec3 c, double yawDeg, double pitchDeg, double r) {
        return c.add(Vec3.directionFromRotation((float) pitchDeg, (float) yawDeg).scale(r));
    }
}
