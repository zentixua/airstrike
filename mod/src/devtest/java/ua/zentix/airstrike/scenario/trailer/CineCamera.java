package ua.zentix.airstrike.scenario.trailer;

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
     * и без этого он уходил к нулю мира — чанки у цели выгружались, и снаряд не возвращался в мир.
     */
    static Path chase(Supplier<Entity> target, Vec3 start, Vec3 heading, double back, double up, double side, double lead,
                      float roll, double fov) {
        return chase(target, () -> null, start, heading, back, up, side, lead, roll, fov);
    }

    /**
     * То же, но пока сущности нет в мире клиента, камера идёт по {@code elsewhere} (где снаряд по данным сервера): за
     * камерой идёт невидимка, вокруг него грузится мир, и снаряд, летящий вне мира, возвращается в мир рядом с камерой.
     */
    static Path chase(Supplier<Entity> target, Supplier<Vec3> elsewhere, Vec3 start, Vec3 heading, double back, double up, double side,
                      double lead, float roll, double fov) {
        final Vec3[] last = {start, heading.normalize()};
        return t -> {
            Entity e = target.get();
            if (e != null) {
                float pt = partial();
                Vec3 p = e.getPosition(pt);
                Vec3 v = p.subtract(e.xo, e.yo, e.zo);
                if (v.lengthSqr() > 1e-4) last[1] = v.normalize();
                last[0] = p;
            } else if (elsewhere.get() instanceof Vec3 p) {
                Vec3 v = p.subtract(last[0]);
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

    // ---------------------------------------------------------------- пути по ключам

    /**
     * Ключ пути: момент плана ({@code t}, тики), место камеры, куда она смотрит ({@code look}; null — по ходу
     * движения), поле зрения и крен. Точка взгляда может двигаться (снаряд): она берётся заново на каждый кадр.
     */
    record Key(double t, Vec3 pos, @Nullable Supplier<Vec3> look, double fov, float roll) {
        static Key at(double t, Vec3 pos, Vec3 look, double fov) {
            return new Key(t, pos, () -> look, fov, 0);
        }

        static Key at(double t, Vec3 pos, Supplier<Vec3> look, double fov) {
            return new Key(t, pos, look, fov, 0);
        }

        Key roll(float r) {
            return new Key(t, pos, look, fov, r);
        }
    }

    /**
     * Проезд по ключам: место — центростремительный сплайн Кэтмелла — Рома (проходит через все точки, без петель
     * и перелётов на неравных отрезках), поле зрения и крен — тот же сплайн по числам, взгляд — сплайн по точкам
     * взгляда. Время внутри отрезка равномерное, первый и последний отрезки — с плавным разгоном и торможением
     * ({@code ease}), чтобы камера не дёргалась на склейке.
     */
    static Path spline(boolean ease, Key... keys) {
        if (keys.length < 2) throw new IllegalArgumentException("нужно хотя бы два ключа");
        int last = keys.length - 1;
        return t -> {
            double tt = Mth.clamp(t, keys[0].t(), keys[last].t());
            int i = 0;
            while (i < last - 1 && tt > keys[i + 1].t()) i++;
            Key a = keys[i], b = keys[i + 1];
            double u = (tt - a.t()) / Math.max(1e-9, b.t() - a.t());
            if (ease && last == 1) u = smooth(u);
            else if (ease && i == 0) u = easeIn(u);
            else if (ease && i == last - 1) u = easeOut(u);
            Key p0 = keys[Math.max(i - 1, 0)], p3 = keys[Math.min(i + 2, last)];
            Vec3 pos = catmullRom(p0.pos(), a.pos(), b.pos(), p3.pos(), u);
            double fov = catmullRom(p0.fov(), a.fov(), b.fov(), p3.fov(), u);
            float roll = (float) catmullRom(p0.roll(), a.roll(), b.roll(), p3.roll(), u);
            Vec3 look;
            if (a.look() != null && b.look() != null) {
                Vec3 la = a.look().get(), lb = b.look().get();
                Vec3 l0 = p0.look() != null ? p0.look().get() : la, l3 = p3.look() != null ? p3.look().get() : lb;
                look = catmullRom(l0, la, lb, l3, u);
            } else {
                // по ходу: точка чуть дальше по тому же сплайну
                double du = 0.01;
                Vec3 ahead = catmullRom(p0.pos(), a.pos(), b.pos(), p3.pos(), Math.min(1, u + du));
                Vec3 back = catmullRom(p0.pos(), a.pos(), b.pos(), p3.pos(), Math.max(0, u - du));
                look = pos.add(ahead.subtract(back).normalize().scale(10));
            }
            return Pose.look(pos, look, roll, fov);
        };
    }

    /** Разгон с места: скорость от нуля до той же, что у равномерного хода в конце отрезка (u²(2 − u)). */
    static double easeIn(double u) {
        u = Mth.clamp(u, 0, 1);
        return u * u * (2 - u);
    }

    /** Торможение до остановки: зеркало {@link #easeIn}. */
    static double easeOut(double u) {
        return 1 - easeIn(1 - u);
    }

    /** Точка центростремительного сплайна Кэтмелла — Рома между {@code p1} и {@code p2} (параметр 0…1). */
    static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        double t0 = 0;
        double t1 = t0 + knot(p0, p1);
        double t2 = t1 + knot(p1, p2);
        double t3 = t2 + knot(p2, p3);
        double t = Mth.lerp(u, t1, t2);
        Vec3 a1 = mix(p0, p1, t0, t1, t), a2 = mix(p1, p2, t1, t2, t), a3 = mix(p2, p3, t2, t3, t);
        Vec3 b1 = mix(a1, a2, t0, t2, t), b2 = mix(a2, a3, t1, t3, t);
        return mix(b1, b2, t1, t2, t);
    }

    private static double knot(Vec3 a, Vec3 b) {
        return Math.max(1e-4, Math.sqrt(a.distanceTo(b))); // α = 0.5
    }

    private static Vec3 mix(Vec3 a, Vec3 b, double ta, double tb, double t) {
        return tb - ta < 1e-9 ? a : a.lerp(b, (t - ta) / (tb - ta));
    }

    /** Сплайн Кэтмелла — Рома по числам (равномерный: у поля зрения и крена нет «расстояний»). */
    static double catmullRom(double p0, double p1, double p2, double p3, double u) {
        double u2 = u * u, u3 = u2 * u;
        return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
    }

    /**
     * Наезд с зумом («эффект Хичкока»): камера едет от {@code a} к {@code b}, поле зрения меняется так, чтобы
     * предмет в {@code at} оставался одного размера, а фон за ним рос или отъезжал.
     */
    static Path dollyZoom(Vec3 a, Vec3 b, Supplier<Vec3> at, double fovA, double ticks) {
        double widthA = a.distanceTo(at.get()) * Math.tan(Math.toRadians(fovA) / 2);
        return t -> {
            Vec3 from = a.lerp(b, smooth(t / ticks));
            Vec3 target = at.get();
            double fov = Math.toDegrees(2 * Math.atan(widthA / Math.max(1, from.distanceTo(target))));
            return Pose.look(from, target, 0, Mth.clamp(fov, 5, 140));
        };
    }

    // ---------------------------------------------------------------- тряска

    /**
     * Тряска камеры от ударов: каждый толчок — затухающий шум поворота (курс, тангаж, крен), место камеры не
     * двигается (как у оператора с камерой на плече). Шум — сумма синусов с несоизмеримыми частотами: одинаков
     * в каждом дубле.
     */
    static final class Shake {
        private record Kick(double t, double deg, double decay) {}

        private final java.util.List<Kick> kicks = new java.util.ArrayList<>();
        /** Постоянная дрожь «с рук», градусы. */
        private final double handheld;

        Shake(double handheld) {
            this.handheld = handheld;
        }

        /** Толчок в момент {@code t} (тики плана): размах в градусах, затухание — за сколько тиков в e раз. */
        void kick(double t, double deg, double decay) {
            kicks.add(new Kick(t, deg, decay));
        }

        double amplitude(double t) {
            double a = handheld;
            for (Kick k : kicks) {
                if (t < k.t()) continue;
                double x = t - k.t();
                a += k.deg() * Math.min(1, x / 0.5) * Math.exp(-x / k.decay());
            }
            return a;
        }

        Path on(Path p) {
            return t -> {
                Pose q = p.at(t);
                double a = amplitude(t);
                if (a < 1e-3) return q;
                float yaw = (float) (a * noise(t, 0.93, 2.31, 5.17));
                float pitch = (float) (a * noise(t + 17, 1.07, 2.83, 4.61));
                float roll = (float) (a * 0.6 * noise(t + 41, 0.71, 1.97, 3.73));
                return new Pose(q.pos(), q.yaw() + yaw, q.pitch() + pitch, q.roll() + roll, q.fov());
            };
        }

        /** Гладкий шум −1…1 из трёх синусов (частоты — в радианах на тик). */
        private static double noise(double t, double f1, double f2, double f3) {
            return (Math.sin(t * f1) * 0.55 + Math.sin(t * f2 + 1.3) * 0.3 + Math.sin(t * f3 + 2.9) * 0.15);
        }
    }

    static float partial() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }
}
