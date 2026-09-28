package ua.zentix.airstrike.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.UUID;

/**
 * Крылатая ракета: 230 м/с (11.5 блока/тик). Ждёт, пока отвоет сирена (100 тиков), затем бреющий полёт
 * в 12 блоках над рельефом; в 160 блоках от цели — горка (если стартовала дальше 185) и пикирование по дуге
 * с разгоном до 12.5 блока/тик.
 */
public class CruiseMissileEntity extends StrikeProjectile {
    public static final int PHASE_CRUISE = 0;
    public static final int PHASE_POP = 1;
    public static final int PHASE_DIVE = 2;
    public static final int PHASE_WAIT = 9;
    public static final int WAIT_TICKS = 100;

    /** Горка перед пикированием — только при длинном заходе. */
    private boolean popUp;

    public CruiseMissileEntity(EntityType<? extends CruiseMissileEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.MISSILE;
    }

    @Override
    protected double noseLength() {
        return 5.88;
    }

    @Override
    protected int maxAge() {
        return 700;
    }

    @Override
    protected float maxHealth() {
        return 8;
    }

    @Override
    public boolean isActive() {
        return phase() != PHASE_WAIT;
    }

    /** Низкий полёт: 12 блоков над рельефом и не ниже цели+12. */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        double y = Math.max(targetPoint.y + 12, surfaceY(level(), pos.x, pos.z) + 12);
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = 11.5;
        altFilter = y;
        double dx = targetPoint.x - start.x, dz = targetPoint.z - start.z;
        popUp = dx * dx + dz * dz >= 185 * 185;
        setPhase(PHASE_WAIT);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (phase() == PHASE_WAIT) {
            if (age >= WAIT_TICKS) {
                Vec3 aim = updateTarget(level);
                float[] a = FlightController.anglesTo(position(), aim);
                flight.set(a[0], 0);
                setYRot(a[0]);
                setXRot(0);
                setPhase(PHASE_CRUISE);
            }
            return;
        }
        Vec3 aim = updateTarget(level);
        Bearing b = bearingTo(aim);

        if (phase() == PHASE_CRUISE && b.horizontal() <= 160) setPhase(popUp ? PHASE_POP : PHASE_DIVE);
        if (phase() == PHASE_POP && (b.pitch() >= 24 || getY() >= aim.y + 32)) setPhase(PHASE_DIVE);

        switch (phase()) {
            case PHASE_CRUISE -> {
                double terrain = terrainAhead(level, 30, 60, 90);
                holdAltitude(Math.max(terrain + 12, aim.y + 12), 0.30, 8, 1.8);
            }
            case PHASE_POP -> flight.holdPitch(-20, 0.30, 8, 1.8);
            default -> {
                flight.arcPitch(b.pitch(), speed, b.distance(), 16, 3.5);
                speed = Math.min(12.5, speed + 0.1);
            }
        }
        if (b.horizontal() > 8) flight.steerYaw(b.yaw(), 0.15, 3.0, 0.3);

        advance(level, aim, 6.5);
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
        Warheads.detonate(level, WeaponType.MISSILE, point, this, ownerId());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        popUp = tag.getBoolean("pop_up");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("pop_up", popUp);
    }
}
