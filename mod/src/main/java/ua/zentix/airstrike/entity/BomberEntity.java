package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;
import ua.zentix.airstrike.util.Particles;

import java.util.UUID;

/**
 * B-2 на эшелоне цель+170: идёт по прямой 12 блоков/тик (240 м/с), сбрасывает бетонобойную бомбу за ~85 блоков
 * до цели по горизонтали (бомба сама доворачивает и входит почти отвесно) и уходит.
 */
public class BomberEntity extends StrikeProjectile {
    public static final double ALTITUDE = 170;
    public static final double RELEASE_DISTANCE = 85;

    private boolean released;
    /** Точка под землёй, к которой бомба пробивается (цель в пещере); null — бурит вниз. */
    @Nullable
    private BlockPos goal;

    public BomberEntity(EntityType<? extends BomberEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.BUNKER;
    }

    @Override
    protected double noseLength() {
        return 8.5;
    }

    @Override
    protected int maxAge() {
        return 120;
    }

    @Override
    protected boolean holdsChunks() {
        return false;
    }

    /**
     * @param surface точка на поверхности над целью (туда падает бомба)
     * @param goal    цель под землёй или null
     */
    public void launch(Vec3 pos, Vec3 surface, @Nullable BlockPos goal, @Nullable UUID owner) {
        Vec3 start = new Vec3(pos.x, surface.y + ALTITUDE, pos.z);
        super.launch(start, new Target.Point(surface), surface, owner);
        this.goal = goal;
        this.speed = 12;
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = tracker.point();
        Bearing b = bearingTo(aim);
        if (!released && b.horizontal() <= RELEASE_DISTANCE) release(level, aim);
        if (age >= maxAge()) {
            discard();
            return;
        }
        Vec3 dir = flight.forward();
        Vec3 ahead = position().add(dir.scale(40));
        if (!level.isLoaded(BlockPos.containing(ahead))) {
            discard();
            return;
        }
        moveAlong(level, position().add(dir.scale(speed)), dir);
    }

    private void release(ServerLevel level, Vec3 aim) {
        released = true;
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        if (bomb == null) return;
        bomb.drop(position().add(0, -4, 0), flight.yaw(), aim, goal, ownerId());
        level.addFreshEntity(bomb);
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

    /** Инверсионные следы на эшелоне. */
    @Override
    protected void clientTick() {
        Level l = level();
        Vec3 p = position();
        float yr = getYRot(), xr = getXRot();
        Particles.burst(l, ParticleTypes.CLOUD, Local.at(p, yr, xr, 3.2, 0.3, -9.5), 0.15, 0.15, 0.15, 0.003, 3);
        Particles.burst(l, ParticleTypes.CLOUD, Local.at(p, yr, xr, -3.2, 0.3, -9.5), 0.15, 0.15, 0.15, 0.003, 3);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        released = tag.getBoolean("released");
        goal = NbtUtils.readBlockPos(tag, "goal").orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("released", released);
        if (goal != null) tag.put("goal", NbtUtils.writeBlockPos(goal));
    }
}
