package ua.zentix.airstrike.target;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;

import java.util.Optional;

/**
 * Слежение за целью в полёте: текущая мировая точка и сглаженная скорость цели (блоков/тик) для упреждения.
 * Цель дальше {@link #MAX_TRACK_DISTANCE} или пропавшая не преследуется — снаряд идёт в последнюю точку.
 */
public final class TargetTracker {
    public static final double MAX_TRACK_DISTANCE = 3000;
    /** Скорость сглаживается, иначе дрожание позиции игрока (прыжки, шаги) дёргает упреждение. */
    private static final double VELOCITY_SMOOTHING = 0.25;

    private Target target;
    private Vec3 point;
    private Vec3 velocity = Vec3.ZERO;
    private boolean lost;

    public TargetTracker(Target target, Vec3 initialPoint) {
        this.target = target;
        this.point = initialPoint;
    }

    public void tick(ServerLevel level, Vec3 from) {
        if (lost) {
            velocity = velocity.scale(0.9);
            return;
        }
        Optional<Vec3> now = target.resolve(level);
        if (now.isEmpty() || now.get().distanceToSqr(from) > MAX_TRACK_DISTANCE * MAX_TRACK_DISTANCE) {
            lost = true;
            return;
        }
        Vec3 p = now.get();
        Vec3 v = p.subtract(point);
        // скачок больше 40 блоков за тик — телепорт цели, а не движение
        if (v.lengthSqr() > 1600) v = Vec3.ZERO;
        velocity = velocity.lerp(v, VELOCITY_SMOOTHING);
        point = p;
    }

    /** Текущая точка цели. */
    public Vec3 point() {
        return point;
    }

    /** Сглаженная скорость цели, блоков/тик. */
    public Vec3 velocity() {
        return velocity;
    }

    public Target target() {
        return target;
    }

    public boolean isMoving() {
        return velocity.lengthSqr() > 0.0025;
    }

    public boolean isLost() {
        return lost;
    }

    /** Цель переключили на неподвижную точку (например, бомба после сброса бьёт по точке). */
    public void freeze() {
        target = new Target.Point(point);
        velocity = Vec3.ZERO;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        Target.CODEC.encodeStart(NbtOps.INSTANCE, target).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("target", t));
        tag.putDouble("x", point.x);
        tag.putDouble("y", point.y);
        tag.putDouble("z", point.z);
        tag.putBoolean("lost", lost);
        return tag;
    }

    public static TargetTracker load(CompoundTag tag) {
        Vec3 p = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
        Target t = tag.contains("target")
                ? Target.CODEC.parse(NbtOps.INSTANCE, tag.get("target")).resultOrPartial(Airstrike.LOG::error).orElse(new Target.Point(p))
                : new Target.Point(p);
        TargetTracker tracker = new TargetTracker(t, p);
        tracker.lost = tag.getBoolean("lost");
        return tracker;
    }
}
