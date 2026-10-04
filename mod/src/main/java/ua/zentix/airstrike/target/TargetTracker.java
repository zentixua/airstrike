package ua.zentix.airstrike.target;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.util.Nbt;

import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Слежение за целью в полёте: текущая мировая точка цели и запас на погоню. Снаряд догоняет сдвиг цели
 * (ушла, телепортировалась, перенацелена) не дальше {@link #CHASE_BUDGET} блоков сверх плана полёта: это
 * запас топлива или батареи, и он же — предел, дальше которого цель не преследуется. Пропавшая цель или цель,
 * которая ушла дальше запаса, потеряна — снаряд идёт в последнюю точку. Неподвижная цель ({@link Target.Point})
 * не сдвигается, поэтому не теряется, как бы далеко ни стартовал снаряд. Замеченная цель ({@link Target.Sighted})
 * сдвигается, только пока её видно ({@link #tick}): иначе её точка — где её видели в последний раз.
 */
public final class TargetTracker {
    /** Сколько блоков сдвига цели снаряд догоняет сверх плана полёта. */
    public static final double CHASE_BUDGET = 3000;
    /** Быстрее этого (блоков за тик) цель не движется — это скачок. */
    private static final double MAX_SPEED = 10;
    /** Сдвиг меньше этого — не сдвиг (дрожание позиции сущности). */
    private static final double MIN_MOVE = 0.01;

    /** Сглаживание скорости цели: доля нового сдвига за тик. */
    private static final double VELOCITY_SMOOTHING = 0.3;

    private Target target;
    private Vec3 point;
    /** Скорость цели, блоков за тик (сглаженная; не сохраняется — за несколько тиков набирается заново). */
    private Vec3 velocity = Vec3.ZERO;
    private double chased;
    private boolean lost;
    /** Замеченную цель в этот тик было видно и снаряд шёл за ней (не сохраняется: видно ли, решается каждый тик). */
    private boolean inSight;
    /** Потеряна, потому что ушла дальше запаса на погоню, а не пропала (для строки в лог; не сохраняется). */
    private boolean outOfReach;
    /**
     * Сущность-цель, за которой шли в прошлый тик (не сохраняется: после загрузки — первая найденная). Другая сущность
     * с тем же UUID — это уже не она: игрок, умерший и возрождённый между двумя тиками снаряда ({@code doImmediateRespawn}),
     * — новый {@code ServerPlayer}, и смерти снаряд иначе не заметил бы.
     */
    @Nullable
    private WeakReference<Entity> followed;

    public TargetTracker(Target target, Vec3 initialPoint) {
        this.target = target;
        this.point = initialPoint;
    }

    /**
     * Пересчитать точку цели. Замеченную цель ({@link Target.Sighted}) снаряд ведёт, только пока {@code visible}
     * принимает её точку; не принимает — точка остаётся, где цель видели в последний раз, а пропавшая цель
     * теряется так же, как незамеченная.
     *
     * @param visible видна ли замеченная цель в своей точке сейчас (у остальных целей не спрашивается)
     * @return на сколько блоков снаряду пришлось догонять цель за этот тик (0 — цель на месте, не видна или потеряна)
     */
    public double tick(ServerLevel level, Predicate<Vec3> visible) {
        inSight = false;
        if (lost) return 0;
        Target subject = target.subject();
        Optional<Vec3> now;
        if (subject instanceof Target.OfEntity e) {
            Entity entity = level.getEntity(e.uuid());
            if (followed != null && followed.get() != entity) {
                loseTarget();
                return 0;
            }
            if (followed == null && entity != null) followed = new WeakReference<>(entity);
            now = e.resolve(level, entity);
        } else {
            now = subject.resolve(level);
        }
        if (now.isEmpty()) {
            loseTarget();
            return 0;
        }
        if (target instanceof Target.Sighted) {
            if (!visible.test(now.get())) {
                velocity = velocity.scale(1 - VELOCITY_SMOOTHING);
                return 0;
            }
            inSight = true;
        }
        Vec3 step = now.get().subtract(point);
        double moved = step.length();
        if (moved < MIN_MOVE) {
            velocity = velocity.scale(1 - VELOCITY_SMOOTHING);
            return 0;
        }
        if (chased + moved > CHASE_BUDGET) {
            loseTarget();
            outOfReach = true;
            return 0;
        }
        chased += moved;
        point = now.get();
        // скачок (телепорт, смена измерения) — не скорость
        velocity = moved > MAX_SPEED ? Vec3.ZERO : velocity.lerp(step, VELOCITY_SMOOTHING);
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
        velocity = Vec3.ZERO;
        lost = false;
        outOfReach = false;
        followed = null;
        return moved;
    }

    /**
     * Точка цели уточнилась, цель та же (место с карты, чья высота стала известна): дальше это неподвижная точка
     * {@code point}. Не перенацеливание — запас на погоню не тратится.
     */
    public void settle(Vec3 point) {
        this.target = new Target.Point(point);
        this.point = point;
        velocity = Vec3.ZERO;
    }

    private void loseTarget() {
        lost = true;
        velocity = Vec3.ZERO;
    }

    /** Текущая точка цели. */
    public Vec3 point() {
        return point;
    }

    /** Скорость цели, блоков за тик: для упреждения на подлёте (у неподвижной и потерянной — ноль). */
    public Vec3 velocity() {
        return velocity;
    }

    public Target target() {
        return target;
    }

    public boolean isLost() {
        return lost;
    }

    /** Замеченную цель в последний тик было видно, и снаряд шёл за ней ({@link #tick}). */
    public boolean inSight() {
        return inSight;
    }

    /** Цель потеряна, потому что ушла дальше запаса на погоню {@link #CHASE_BUDGET} (а не пропала). */
    public boolean outOfReach() {
        return outOfReach;
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
