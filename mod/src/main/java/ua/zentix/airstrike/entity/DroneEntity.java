package ua.zentix.airstrike.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;
import ua.zentix.airstrike.util.Particles;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.UUID;

/**
 * Дрон-камикадзе в духе Shahed-136: ≈150 км/ч (2.1 блока/тик), крейсер над рельефом и не ниже цели+30,
 * пикирование по дуге, когда цель уходит на 18° под горизонт; в пике разгон до 3 блоков/тик.
 */
public class DroneEntity extends StrikeProjectile {
    public static final int PHASE_CRUISE = 0;
    public static final int PHASE_DIVE = 1;

    /** Высота крейсера на старте: не спускаемся ниже, даже если рельеф понижается. */
    private double cruiseAlt;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.DRONE;
    }

    @Override
    protected double noseLength() {
        return 3.65;
    }

    @Override
    protected int maxAge() {
        return 900;
    }

    @Override
    protected float maxHealth() {
        return 12;
    }

    /** Высота полёта = max(старт, цель+30, рельеф+20). */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        double y = Math.max(pos.y, targetPoint.y + 30);
        y = Math.max(y, surfaceY(level(), pos.x, pos.z) + 20);
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = 2.1;
        cruiseAlt = y;
        altFilter = y;
        setPhase(PHASE_CRUISE);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        Bearing b = bearingTo(aim);

        if (phase() == PHASE_CRUISE && b.pitch() >= 18) setPhase(PHASE_DIVE);

        if (phase() == PHASE_CRUISE) {
            double terrain = terrainAhead(level, 15, 30, 45);
            double desired = Math.max(Math.max(terrain + 18, cruiseAlt), aim.y + 30);
            holdAltitude(desired, 0.12, 1.2, 0.15);
        } else {
            flight.arcPitch(b.pitch(), speed, b.distance(), 4.0, 0.25);
            speed = Math.min(3.0, speed + 0.04);
        }
        // над самой целью курс не трогаем
        if (b.horizontal() > 8) flight.steerYaw(b.yaw(), 0.15, 3.0, 0.3);

        advance(level, aim, 4.3);
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
        Warheads.detonate(level, WeaponType.DRONE, point, this, ownerId());
    }

    /** Выхлоп: сизый шлейф днём, тусклые искры ночью (как в датапаке, от сопла позади винта). */
    @Override
    protected void clientTick() {
        Level l = level();
        Vec3 p = position();
        float yr = getYRot(), xr = getXRot();
        Particles.burst(l, ParticleTypes.CAMPFIRE_COSY_SMOKE, Local.at(p, yr, xr, 0, 0.05, -3.9), 0.02, 0.02, 0.02, 0.002, 1);
        Particles.burst(l, ParticleTypes.SMALL_FLAME, Local.at(p, yr, xr, 0, 0.05, -3.8), 0.02, 0.02, 0.02, 0.002, 1);
        Particles.burst(l, ParticleTypes.SMOKE, Local.at(p, yr, xr, 0, 0.05, -3.8), 0.05, 0.05, 0.05, 0.01, 2);
        if (phase() == PHASE_DIVE) {
            Particles.burst(l, ParticleTypes.SMOKE, Local.at(p, yr, xr, 0, 0.05, -3.8), 0.08, 0.08, 0.08, 0.02, 3);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        cruiseAlt = tag.getDouble("cruise_alt");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("cruise_alt", cruiseAlt);
    }
}
