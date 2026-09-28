package ua.zentix.airstrike.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Кинокамера трейлера: камера — отдельная сущность, которой нет в мире (игрок при этом виден как актёр или спрятан),
 * её место и взгляд каждый кадр берутся из {@link Path} по времени плана с долей тика — движение плавное при любой
 * скорости игры. Крен и фокусное расстояние — через события камеры.
 */
final class CineCamera {
    /** Положение камеры: место, курс и тангаж (как у Minecraft), крен, поле зрения по вертикали. */
    record Pose(Vec3 pos, float yaw, float pitch, float roll, double fov) {
        static Pose look(Vec3 from, Vec3 at, float roll, double fov) {
            Vec3 d = at.subtract(from);
            double h = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
            float pitch = (float) (-Mth.atan2(d.y, h) * Mth.RAD_TO_DEG);
            return new Pose(from, yaw, pitch, roll, fov);
        }
    }

    /** Движение камеры за план: {@code t} — тики от начала плана (с долей). */
    interface Path {
        Pose at(double t);
    }

    @Nullable
    private static Marker eye;
    @Nullable
    private static Path path;
    private static Pose pose;

    private CineCamera() {}

    static boolean active() {
        return path != null;
    }

    static void use(Path p) {
        Minecraft mc = Minecraft.getInstance();
        if (eye == null || eye.level() != mc.level) eye = new Marker(EntityType.MARKER, mc.level);
        path = p;
        apply(0);
        mc.setCameraEntity(eye);
    }

    static void release() {
        path = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    @Nullable
    static Pose pose() {
        return path == null ? null : pose;
    }

    /** Поставить камеру в момент {@code t}: старое положение = новому, так что интерполяция игры не мешает. */
    static void apply(double t) {
        if (path == null || eye == null) return;
        pose = path.at(t);
        eye.setPos(pose.pos());
        eye.xo = eye.xOld = pose.pos().x;
        eye.yo = eye.yOld = pose.pos().y;
        eye.zo = eye.zOld = pose.pos().z;
        eye.setYRot(pose.yaw());
        eye.yRotO = pose.yaw();
        eye.setXRot(pose.pitch());
        eye.xRotO = pose.pitch();
    }

    static void angles(ViewportEvent.ComputeCameraAngles e) {
        if (path != null && pose != null) e.setRoll(e.getRoll() + pose.roll());
    }

    static void fov(ViewportEvent.ComputeFov e) {
        if (path != null && pose != null) e.setFOV(pose.fov());
    }

    // ---------------------------------------------------------------- готовые движения

    static double smooth(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** Проезд от {@code a} к {@code b} за {@code ticks} с плавным разгоном и торможением, взгляд — на {@code at}. */
    static Path dolly(Vec3 a, Vec3 b, double ticks, Supplier<Vec3> at, double fov) {
        return t -> Pose.look(a.lerp(b, smooth(t / ticks)), at.get(), 0, fov);
    }

    /** Проезд с постоянной скоростью (для длинных пролётов, где разгон заметен). */
    static Path glide(Vec3 a, Vec3 b, double ticks, Supplier<Vec3> at, double fov) {
        return t -> Pose.look(a.lerp(b, Mth.clamp(t / ticks, 0, 1)), at.get(), 0, fov);
    }

    /** Облёт точки: радиус, высота над ней, начальный угол (градусы, 0 — к +Z) и скорость (градусы за тик). */
    static Path orbit(Supplier<Vec3> center, double radius, double height, double startDeg, double degPerTick, double fov) {
        return t -> {
            Vec3 c = center.get();
            double a = Math.toRadians(startDeg + degPerTick * t);
            Vec3 from = c.add(Math.sin(a) * radius, height, Math.cos(a) * radius);
            return Pose.look(from, c, 0, fov);
        };
    }

    /**
     * Погоня: камера позади сущности по её курсу ({@code back}), выше ({@code up}) и сбоку ({@code side}), взгляд —
     * чуть впереди неё. Положение сущности — с долей тика, как её рисует игра.
     */
    static Path chase(Supplier<Entity> target, double back, double up, double side, double lead, float roll, double fov) {
        final Vec3[] last = {Vec3.ZERO, new Vec3(0, 0, 1)};
        return t -> {
            Entity e = target.get();
            if (e != null) {
                float pt = partial();
                Vec3 p = e.getPosition(pt);
                Vec3 v = p.subtract(e.xo, e.yo, e.zo);
                if (v.lengthSqr() > 1e-4) last[1] = v.normalize();
                last[0] = p;
            }
            Vec3 dir = last[1];
            Vec3 flat = new Vec3(dir.x, 0, dir.z);
            flat = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
            Vec3 right = new Vec3(-flat.z, 0, flat.x);
            Vec3 from = last[0].subtract(dir.scale(back)).add(0, up, 0).add(right.scale(side));
            return Pose.look(from, last[0].add(dir.scale(lead)), roll, fov);
        };
    }

    /** Неподвижная камера, следящая за точкой (например, за снарядом). */
    static Path track(Vec3 from, Supplier<Vec3> at, double fov) {
        return t -> Pose.look(from, at.get(), 0, fov);
    }

    /** Взгляд плавно переходит с одной точки на другую за {@code ticks}, камера едет от {@code a} к {@code b}. */
    static Path pan(Vec3 a, Vec3 b, Supplier<Vec3> lookA, Supplier<Vec3> lookB, double ticks, double fov) {
        return t -> {
            double s = smooth(t / ticks);
            return Pose.look(a.lerp(b, s), lookA.get().lerp(lookB.get(), s), 0, fov);
        };
    }

    /** Та же траектория с медленным «зумом» (поле зрения от {@code fovA} к {@code fovB}). */
    static Path zoom(Path p, double fovA, double fovB, double ticks) {
        return t -> {
            Pose q = p.at(t);
            return new Pose(q.pos(), q.yaw(), q.pitch(), q.roll(), Mth.lerp(smooth(t / ticks), fovA, fovB));
        };
    }

    static float partial() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }
}
