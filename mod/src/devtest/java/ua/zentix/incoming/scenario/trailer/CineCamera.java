package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.DoubleSupplier;
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
    /** Угол кадра для вида игрока (NaN — как в настройках). */
    static double viewFov = Double.NaN;

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
        viewFov = Double.NaN;
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
        if (path != null && pose != null) {
            e.setFOV(pose.fov());
        } else if (!Double.isNaN(viewFov) && e.usedConfiguredFov()) {
            // вид игрока: угол плана поверх настроек, приближение бинокля (модификатор) сохраняется
            e.setFOV(e.getFOV() * viewFov / Minecraft.getInstance().options.fov().get());
        }
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
        return chase(target, Vec3.ZERO, new Vec3(0, 0, 1), back, up, side, lead, roll, fov);
    }

    /**
     * То же, но пока цели нет, камера стоит у {@code start} (курс {@code heading}): за камерой ходит невидимка,
     * и без этого он уходил бы к нулю мира — чанки у цели выгружались бы, и снаряд не вернулся бы в мир.
     */
    static Path chase(Supplier<Entity> target, Vec3 start, Vec3 heading, double back, double up, double side, double lead,
                      float roll, double fov) {
        final Vec3[] last = {start, heading.normalize()};
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

    /** То же с переменным полем зрения (например, «зум» за далёким снарядом). */
    static Path track(Vec3 from, Supplier<Vec3> at, DoubleSupplier fov) {
        return t -> Pose.look(from, at.get(), 0, fov.getAsDouble());
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

    /**
     * FPV: полёт по точкам (сплайн Катмулла — Рома через все) с постоянной скоростью {@code speed} блоков за тик,
     * взгляд — на путь через {@code lookAhead} блоков, крен — в поворот, как у дрона ({@code bank} — градусов крена на
     * градус поворота за тик), лёгкая тряска ({@code shake} блоков). Путь кончился — камера висит в последней точке.
     */
    static Path fpv(java.util.List<Vec3> points, double speed, double lookAhead, double bank, double shake, double fov) {
        Spline s = new Spline(points);
        final double[] roll = {0, -1};
        return t -> {
            double d = t * speed;
            Vec3 p = s.at(d), ahead = s.at(d + lookAhead);
            // поворот за тик — по направлениям пути на шаг вперёд
            Vec3 d0 = s.at(d + speed).subtract(p), d1 = s.at(d + 2 * speed).subtract(s.at(d + speed));
            double turn = Math.toDegrees(Mth.atan2(d0.x * d1.z - d0.z * d1.x, d0.x * d1.x + d0.z * d1.z));
            double want = Mth.clamp(turn * bank, -40, 40);
            // крен сглажен по времени плана (камера не дёргается на стыках отрезков)
            double k = roll[1] < 0 ? 1 : Mth.clamp(1 - Math.pow(0.8, Math.max(0, t - roll[1])), 0, 1);
            roll[0] = Mth.lerp(k, roll[0], want);
            roll[1] = t;
            Vec3 jitter = new Vec3(Math.sin(t * 1.7) + Math.sin(t * 3.1) * 0.5, Math.sin(t * 2.3 + 1) + Math.sin(t * 4.3) * 0.4,
                    Math.cos(t * 1.9) * 0.6).scale(shake);
            return Pose.look(p.add(jitter), ahead, (float) roll[0], fov);
        };
    }

    /** Сплайн по точкам, параметр — пройденный путь в блоках (таблица длин по отрезкам). */
    static final class Spline {
        private final java.util.List<Vec3> pts;
        private final double[] len;
        private static final int STEPS = 32;

        Spline(java.util.List<Vec3> points) {
            pts = new java.util.ArrayList<>(points);
            len = new double[(pts.size() - 1) * STEPS + 1];
            Vec3 prev = pts.get(0);
            for (int i = 1; i < len.length; i++) {
                Vec3 q = raw((double) i / STEPS);
                len[i] = len[i - 1] + q.distanceTo(prev);
                prev = q;
            }
        }

        double length() {
            return len[len.length - 1];
        }

        Vec3 at(double dist) {
            dist = Mth.clamp(dist, 0, length());
            int lo = 0, hi = len.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (len[mid] <= dist) lo = mid; else hi = mid;
            }
            double f = len[hi] == len[lo] ? 0 : (dist - len[lo]) / (len[hi] - len[lo]);
            return raw((lo + f) / STEPS);
        }

        private Vec3 raw(double u) {
            int n = pts.size() - 1;
            int i = Mth.clamp((int) Math.floor(u), 0, n - 1);
            double f = u - i;
            Vec3 p0 = pts.get(Math.max(0, i - 1)), p1 = pts.get(i), p2 = pts.get(i + 1), p3 = pts.get(Math.min(n, i + 2));
            double f2 = f * f, f3 = f2 * f;
            return p1.scale(2).add(p2.subtract(p0).scale(f)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(f2))
                    .add(p3.subtract(p0).add(p1.scale(3)).subtract(p2.scale(3)).scale(f3)).scale(0.5);
        }
    }

    /** Глазами сущности (бота): камера в глазах, взгляд — его голова; {@code fov} — угол кадра. */
    static Path eyes(Supplier<Entity> who, double fov) {
        return t -> {
            Entity e = who.get();
            if (e == null) return pose != null ? pose : new Pose(Vec3.ZERO, 0, 0, 0, fov);
            float pt = partial();
            return new Pose(e.getEyePosition(pt), e.getViewYRot(pt), e.getViewXRot(pt), 0, fov);
        };
    }

    /**
     * Из-за плеча: позади головы по её курсу ({@code back}), выше ({@code up}), сбоку ({@code side}, плюс — правое
     * плечо), взгляд — туда же, куда смотрит сущность, или на точку {@code at}, если она есть.
     */
    static Path shoulder(Supplier<Entity> who, double back, double up, double side, @Nullable Supplier<Vec3> at, double fov) {
        return t -> {
            Entity e = who.get();
            if (e == null) return pose != null ? pose : new Pose(Vec3.ZERO, 0, 0, 0, fov);
            float pt = partial();
            Vec3 eye = e.getEyePosition(pt);
            Vec3 look = Vec3.directionFromRotation(e.getViewXRot(pt), e.getViewYRot(pt));
            Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
            Vec3 right = new Vec3(-flat.z, 0, flat.x);
            Vec3 from = eye.subtract(flat.scale(back)).add(0, up, 0).add(right.scale(side));
            return Pose.look(from, at != null ? at.get() : eye.add(look.scale(30)), 0, fov);
        };
    }

    /**
     * На борту: камера закреплена на сущности (самолёт, лодка) в её осях — {@code right} вправо, {@code up} вверх,
     * {@code fwd} вперёд от её места с учётом курса и тангажа — и смотрит по её носу, на {@code tilt} градусов ниже
     * (капот и полоса в кадре). Место и поворот — с долей тика, как их рисует игра; крена у сущности Minecraft нет.
     */
    static Path mount(Supplier<Entity> vehicle, double right, double up, double fwd, float tilt, double fov) {
        return t -> {
            Entity e = vehicle.get();
            if (e == null) return pose != null ? pose : new Pose(Vec3.ZERO, 0, 0, 0, fov);
            float pt = partial();
            float yaw = e.getViewYRot(pt), pitch = e.getViewXRot(pt);
            Vec3 f = Vec3.directionFromRotation(pitch, yaw), u = Vec3.directionFromRotation(pitch - 90, yaw);
            Vec3 from = e.getPosition(pt).add(f.cross(u).scale(right)).add(u.scale(up)).add(f.scale(fwd));
            return new Pose(from, yaw, pitch + tilt, 0, fov);
        };
    }

    static float partial() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }
}
