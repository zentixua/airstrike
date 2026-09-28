package ua.zentix.airstrike.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;
import ua.zentix.airstrike.util.Particles;

import java.util.UUID;

/**
 * Межконтинентальная баллистическая ракета — только участок разгона (DESIGN-nuke §7): вертикальный старт
 * из земли у запустившего, разгон 0.5 → 25 блоков/тик, доворот на курс цели и уход за потолок мира.
 * Дальше полёт — таймер запланированного удара; сама ракета ничего не взрывает.
 */
public class IcbmEntity extends StrikeProjectile {
    private static final double MAX_SPEED = 25;

    /** Клиент: где стоял стол (там клубится облако старта). */
    @Nullable
    private Vec3 pad;

    public IcbmEntity(EntityType<? extends IcbmEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.NUKE;
    }

    @Override
    protected double noseLength() {
        return 9;
    }

    @Override
    protected int maxAge() {
        return 600;
    }

    /** Поставить на стартовую площадку носом вверх. */
    public void prepare(Vec3 pad, Vec3 target, @Nullable UUID owner) {
        super.launch(pad.add(0, 9, 0), new Target.Point(target), target, owner);
        float[] a = FlightController.anglesTo(pad, target);
        flight.set(a[0], -89);
        setYRot(a[0]);
        setXRot(-89);
        speed = 0.5;
    }

    @Override
    protected void serverTick(ServerLevel level) {
        // разгон: сначала медленно отрывается от стола, потом всё быстрее
        speed = Math.min(MAX_SPEED, speed + (age < 40 ? 0.08 : 0.35));
        double climbed = getY() - level.getMinBuildHeight();
        if (age > 60 && climbed > 0) {
            // доворот на курс цели: к пологим 45° над горизонтом
            flight.holdPitch(-45, 0.02, 0.6, 0.05);
        }
        Vec3 dir = flight.forward();
        moveAlong(level, position().add(dir.scale(speed)), dir);
        if (getY() > level.getMaxBuildHeight() + 256 || age >= maxAge()) discard();
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

    /** Столб огня из сопла и густой дымный след; на старте — облако у площадки. */
    @Override
    protected void clientTick() {
        Level l = level();
        Vec3 p = position();
        float yr = getYRot(), xr = getXRot();
        Vec3 nozzle = Local.at(p, yr, xr, 0, 0, -9.5);
        Particles.burst(l, ParticleTypes.FLAME, nozzle, 0.3, 0.3, 0.3, 0.15, 30);
        Particles.burst(l, ParticleTypes.LAVA, nozzle, 0.2, 0.2, 0.2, 0, 4);
        for (double z = -11; z >= -40; z -= 3) {
            Particles.burst(l, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, Local.at(p, yr, xr, 0, 0, z), 0.6, 0.6, 0.6, 0.01, 2);
        }
        Particles.burst(l, ParticleTypes.CLOUD, Local.at(p, yr, xr, 0, 0, -14), 0.8, 0.8, 0.8, 0.02, 6);
        if (pad == null) pad = p.add(0, -9, 0);
        if (age < 80) {
            Particles.burst(l, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, pad, 6, 1, 6, 0.04, 20);
            Particles.burst(l, ParticleTypes.LARGE_SMOKE, pad, 8, 1.5, 8, 0.1, 30);
            if (age < 30) Particles.burst(l, ParticleTypes.FLAME, pad, 3, 0.5, 3, 0.2, 40);
        }
    }
}
