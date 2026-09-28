package ua.zentix.airstrike.target;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.util.Nbt;

import java.util.Optional;

/**
 * Слежение за целью в полёте: текущая мировая точка цели и запас на погоню. Снаряд догоняет сдвиг цели
 * (ушла, телепортировалась, перенацелена) не дальше {@link #CHASE_BUDGET} блоков сверх плана полёта: это
 * запас топлива или батареи, и он же — предел, дальше которого цель не преследуется. Пропавшая цель или цель,
 * которая ушла дальше запаса, потеряна — снаряд идёт в последнюю точку. Неподвижная цель ({@link Target.Point})
 * не сдвигается, поэтому не теряется, как бы далеко ни стартовал снаряд.
 */
public final class TargetTracker {
    /** Сколько блоков сдвига цели снаряд догоняет сверх плана полёта. */
    public static final double CHASE_BUDGET = 3000;
    /** Сдвиг меньше этого — не сдвиг (дрожание позиции сущности). */
    private static final double MIN_MOVE = 0.01;

    private Target target;
    private Vec3 point;
    private double chased;
    private boolean lost;

    public TargetTracker(Target target, Vec3 initialPoint) {
        this.target = target;
        this.point = initialPoint;
    }

    /**
     * Пересчитать точку цели.
     *
     * @return на сколько блоков снаряду пришлось догонять цель за этот тик (0 — цель на месте или потеряна)
     */
    public double tick(ServerLevel level) {
        if (lost) return 0;
        Optional<Vec3> now = target.resolve(level);
        if (now.isEmpty()) {
            lost = true;
            return 0;
        }
        double moved = point.distanceTo(now.get());
        if (moved < MIN_MOVE) return 0;
        if (chased + moved > CHASE_BUDGET) {
            lost = true;
            return 0;
        }
        chased += moved;
        point = now.get();
        return moved;
    }

    /**
     * Новая цель (оператор перенацелил снаряд): её снаряд принимает всегда, но на перелёт к ней расходует тот же
     * запас на погоню — сколько его осталось.
     *
     * @return на сколько блоков перелёта к новой цели хватило запаса
     */
    public double retarget(Target target, Vec3 point) {
        double moved = Math.min(this.point.distanceTo(point), CHASE_BUDGET - chased);
        chased += moved;
        this.target = target;
        this.point = point;
        lost = false;
        return moved;
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
        Nbt.putVec(tag, "", point);
        tag.putDouble("chased", chased);
        tag.putBoolean("lost", lost);
        return tag;
    }

    public static TargetTracker load(CompoundTag tag) {
        Vec3 p = Nbt.getVec(tag, "");
        if (p == null) p = Vec3.ZERO;
        Target t = tag.contains("target")
                ? Target.CODEC.parse(NbtOps.INSTANCE, tag.get("target")).resultOrPartial(Airstrike.LOG::error).orElse(new Target.Point(p))
                : new Target.Point(p);
        TargetTracker tracker = new TargetTracker(t, p);
        tracker.chased = tag.getDouble("chased");
        // до 2.1.3 неподвижную цель «теряли» по дальности от снаряда
        tracker.lost = tag.getBoolean("lost") && !(t instanceof Target.Point);
        return tracker;
    }
}
