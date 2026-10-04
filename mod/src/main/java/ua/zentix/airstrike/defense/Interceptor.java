package ua.zentix.airstrike.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.InterceptorEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Homing;
import ua.zentix.airstrike.util.Terrain;

import java.util.UUID;

/**
 * Полёт зенитной ракеты: сход с направляющей вверх в сторону цели, разгон и погоня — каждый тик курс на нынешнее
 * место цели с пределом поворота по боковому ускорению ({@link Homing}, {@link InterceptorSpec#turnLimit}). Неконтактный взрыватель рвёт её у цели (ближайшее сближение за
 * тик); с вероятностью {@code kill_probability} цель сбита, иначе летит дальше. Цель пропала, на ней кто-то сидит,
 * кончилось топливо — самоликвидация в воздухе. Цель — только снаряд мода ({@link StrikeProjectile}) по его UUID.
 * <p>
 * Полёт ведёт {@link DefenseWorld} — и в загруженном мире, и вне его, одним кодом: в тикающих чанках у полёта есть
 * вид ({@link InterceptorEntity}), вне них — нет. Блоки читаются только в готовых чанках ({@link Terrain#readyUntil}),
 * дальше землёй считается только карта высот готового чанка ({@link Terrain#estimate}); тикетов полёт не берёт и чанков
 * не грузит.
 */
public final class Interceptor {
    /** Чем кончился полёт. */
    public enum End {
        /** Разорвалась у цели и сбила. */
        KILL,
        /** Разорвалась у цели, цель цела. */
        MISS,
        /** Цель пропала (взорвалась, отбой, ушла из мира) или на ней кто-то сидит: самоликвидация. */
        LOST,
        /** Кончилось топливо: самоликвидация. */
        FUEL,
        /** Задела землю или постройку. */
        GROUND
    }

    private final InterceptorSpec spec;
    private final UUID target;
    @Nullable
    private final UUID owner;
    /** Откуда пущена (для лога). */
    private final String launcher;
    private Vec3 pos, dir;
    private double speed;
    private int age;
    @Nullable
    private End end;
    @Nullable
    private InterceptorEntity view;

    Interceptor(InterceptorSpec spec, UUID target, @Nullable UUID owner, String launcher, Vec3 rail, Vec3 dir) {
        this.spec = spec;
        this.target = target;
        this.owner = owner;
        this.launcher = launcher;
        this.pos = rail;
        this.dir = dir.normalize();
        this.speed = spec.launchSpeed();
    }

    public Vec3 position() {
        return pos;
    }

    /** Курс, единичный вектор. */
    public Vec3 direction() {
        return dir;
    }

    /** Скорость, блоков/тик. */
    public double speed() {
        return speed;
    }

    public int age() {
        return age;
    }

    public UUID target() {
        return target;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    /** Полёт кончился. */
    public boolean done() {
        return end != null;
    }

    /** Вид полёта в мире (null — его нет: полёт вне тикающих чанков). */
    @Nullable
    public InterceptorEntity view() {
        return view;
    }

    void setView(@Nullable InterceptorEntity view) {
        this.view = view;
    }

    /** Курс схода с направляющей: вверх под углом паспорта в сторону цели (цель над самой пусковой — на юг). */
    static Vec3 railDirection(Vec3 rail, Vec3 target, InterceptorSpec spec) {
        Vec3 to = target.subtract(rail);
        Vec3 flat = new Vec3(to.x, 0, to.z);
        flat = flat.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        double e = Math.toRadians(spec.elevation());
        return flat.scale(Math.cos(e)).add(0, Math.sin(e), 0);
    }

    /** Тик полёта: вызывает {@link DefenseWorld}. */
    void tick(ServerLevel level, DefenseWorld world) {
        StrikeProjectile t = world.find(level, target);
        if (t == null || !Radar.airborne(t) || t.isVehicle()) {
            world.burst(level, this, pos, null, End.LOST);
            return;
        }
        if (age >= spec.fuelTicks()) {
            world.burst(level, this, pos, null, End.FUEL);
            return;
        }
        // цель в этом тике уже сдвинулась (сущности и полёты вне мира тикают раньше): её отрезок — от прошлого места
        Vec3 tNow = t.position(), tPrev = tNow.subtract(t.getDeltaMovement());
        Vec3 next = steer(tNow);
        double s = fuze(next, tPrev, tNow, t.airframe().noseLength());
        Vec3 hit = obstacle(level, pos, next);
        if (s >= 0 && (hit == null || s * pos.distanceTo(next) <= pos.distanceTo(hit))) {
            boolean kill = level.random.nextDouble() < AirstrikeConfig.SERVER.samKillProbability.get();
            world.burst(level, this, pos.lerp(next, s), t, kill ? End.KILL : End.MISS);
            return;
        }
        if (hit != null) {
            world.burst(level, this, hit, null, End.GROUND);
            return;
        }
        moveTo(next);
        if (pos.y > level.getMaxBuildHeight() + 1024 || pos.y < level.getMinBuildHeight()) world.burst(level, this, pos, null, End.FUEL);
    }

    /**
     * Тик погони без мира: возраст, после направляющей — доворот на нынешнее место цели не круче паспорта на этой
     * скорости ({@link InterceptorSpec#turnLimit}), на разгоне — прирост скорости. Возвращает, где ракета будет в конце
     * тика; сдвигает её {@link #moveTo} после проверки преград.
     */
    Vec3 steer(Vec3 targetNow) {
        age++;
        if (age > spec.railTicks()) {
            Vec3 to = targetNow.subtract(pos);
            if (to.lengthSqr() > 1.0e-6) dir = Homing.turn(dir, to.normalize(), spec.turnLimit(speed));
        }
        if (age <= spec.boostTicks()) speed = Math.min(spec.maxSpeed(), speed + spec.boostAccel());
        return pos.add(dir.scale(speed));
    }

    /**
     * Неконтактный взрыватель на отрезке тика ракеты ({@code position() → next}) против отрезка цели
     * ({@code tPrev → tNow}): доля отрезка ракеты в точке наибольшего сближения, если оно в радиусе взрывателя сверх
     * полудлины цели, иначе −1. На направляющей взрыватель не взведён.
     */
    double fuze(Vec3 next, Vec3 tPrev, Vec3 tNow, double targetHalfLength) {
        if (age <= spec.railTicks()) return -1;
        double s = Homing.closest(pos, next, tPrev, tNow);
        return Homing.distanceAt(pos, next, tPrev, tNow, s) <= spec.fuse() + targetHalfLength ? s : -1;
    }

    void moveTo(Vec3 next) {
        pos = next;
    }

    void finish(End end) {
        this.end = end;
    }

    String launcher() {
        return launcher;
    }

    /**
     * Где отрезок полёта за тик задевает землю или постройку; null — нигде. До первого неготового чанка — луч по блокам,
     * дальше — карта высот, если чанк конца отрезка готов. Уровень моря землёй не считается: это догадка, а цель бывает
     * и ниже него (долина вне загрузки); ракета там идёт за целью в воздухе, и её всё равно кончит топливо.
     */
    @Nullable
    static Vec3 obstacle(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 ready = Terrain.readyUntil(level, from, to);
        if (ready.distanceToSqr(from) > 1.0e-8 && Terrain.ready(level, BlockPos.containing(from))) {
            BlockHitResult hit = level.clip(new ClipContext(from, ready, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) return hit.getLocation();
        }
        if (ready.distanceToSqr(to) > 1.0e-8) {
            Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(to.x), Mth.floor(to.z), Terrain.Allowed.FLIGHT);
            if (ground.source() == Terrain.Source.CHUNK && to.y < ground.y()) return to;
        }
        return null;
    }
}
