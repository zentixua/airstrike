package ua.zentix.airstrike.target;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;

import java.util.Optional;

/**
 * Слежение за целью в полёте: текущая мировая точка цели. Цель дальше {@link #MAX_TRACK_DISTANCE} или пропавшая не преследуется — снаряд идёт в последнюю точку.
 */
public final class TargetTracker {
    public static final double MAX_TRACK_DISTANCE = 3000;

    private final Target target;
    private Vec3 point;
    private boolean lost;

    public TargetTracker(Target target, Vec3 initialPoint) {
        this.target = target;
        this.point = initialPoint;
    }

    public void tick(ServerLevel level, Vec3 from) {
        if (lost) return;
        Optional<Vec3> now = target.resolve(level);
        if (now.isEmpty() || now.get().distanceToSqr(from) > MAX_TRACK_DISTANCE * MAX_TRACK_DISTANCE) {
            lost = true;
            return;
        }
        point = now.get();
    }

    /** Текущая точка цели. */
    public Vec3 point() {
        return point;
    }

    public Target target() {
        return target;
    }

    public boolean isLost() {
        return lost;
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
