package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

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
     * градус поворота за тик), лёгкая тряска ({@code shake} блоков). Путь кончился — камера висит в последней точке и
     * смотрит, куда летела ({@link Spline#beyond}): взгляд на саму точку крутило бы тряской (p32 — кадр на каждый тик в
     * другую сторону, пока «Град» ложится после конца пути). Камера не входит в блоки ({@link Clear}): точки пути — по
     * плану места, а ступени, потолок спуска и углы домов — какие есть в мире (p15, p18: FPV по лестнице метро задевал
     * ступени, потолок и забор).
     */
    static Path fpv(java.util.List<Vec3> points, double speed, double lookAhead, double bank, double shake, double fov) {
        Spline s = new Spline(points);
        Clear clear = new Clear(t -> s.at(t * speed).add(new Vec3(Math.sin(t * 1.7) + Math.sin(t * 3.1) * 0.5,
                Math.sin(t * 2.3 + 1) + Math.sin(t * 4.3) * 0.4, Math.cos(t * 1.9) * 0.6).scale(shake)));
        final double[] roll = {0, -1};
        return t -> {
            double d = t * speed;
            Vec3 p = s.beyond(d), ahead = s.beyond(d + lookAhead);
            // поворот за тик — по направлениям пути на шаг вперёд
            Vec3 d0 = s.beyond(d + speed).subtract(p), d1 = s.beyond(d + 2 * speed).subtract(s.beyond(d + speed));
            double turn = Math.toDegrees(Mth.atan2(d0.x * d1.z - d0.z * d1.x, d0.x * d1.x + d0.z * d1.z));
            double want = Mth.clamp(turn * bank, -40, 40);
            // крен сглажен по времени плана (камера не дёргается на стыках отрезков)
            double k = roll[1] < 0 ? 1 : Mth.clamp(1 - Math.pow(0.8, Math.max(0, t - roll[1])), 0, 1);
            roll[0] = Mth.lerp(k, roll[0], want);
            roll[1] = t;
            return Pose.look(clear.at(t), ahead, (float) roll[0], fov);
        };
    }

    // ---------------------------------------------------------------- камера не в блоках

    /** Дальше этого камеру из блоков не выталкивает, блоков; шаг поиска — восьмая блока. */
    static final double PUSH = 1.5;
    /** Сдвиги поиска свободного места, от ближних к дальним. */
    private static final java.util.List<Vec3> NUDGES = nudges();

    private static java.util.List<Vec3> nudges() {
        java.util.List<Vec3> out = new java.util.ArrayList<>();
        int n = (int) Math.round(PUSH * 8);
        for (int x = -n; x <= n; x++) {
            for (int y = -n; y <= n; y++) {
                for (int z = -n; z <= n; z++) {
                    Vec3 v = new Vec3(x / 8.0, y / 8.0, z / 8.0);
                    if (v.lengthSqr() <= PUSH * PUSH) out.add(v);
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(Vec3::lengthSqr));
        return out;
    }

    /**
     * Ближайшее к {@code p} место камеры, где вокруг неё полблока воздуха ({@link Rig.World#blocking}), не дальше
     * {@link #PUSH}: свободное {@code p} — как есть; сдвинутое — откуда виден {@code from} (камера не уходит сквозь потолок
     * спуска на улицу и сквозь стену к голове статиста), а нет такого — ближайшее свободное. null — свободного нет.
     */
    @Nullable
    static Vec3 nearestFree(Level level, Vec3 p, @Nullable Vec3 from) {
        if (Rig.World.blocking(level, p) == null) return p;
        Vec3 loose = null;
        for (Vec3 n : NUDGES) {
            Vec3 q = p.add(n);
            if (Rig.World.blocking(level, q) != null) continue;
            if (from == null || open(level, from, q)) return q;
            if (loose == null) loose = q;
        }
        return loose;
    }

    /** Между точками нет блоков (формы столкновения). */
    static boolean open(Level level, Vec3 a, Vec3 b) {
        return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
                == HitResult.Type.MISS;
    }

    /**
     * Путь камеры не в блоках: место пути {@code place} (без состояния — тот же тик даёт то же место) в целый тик
     * сдвигается к ближайшему свободному ({@link #nearestFree}, видно место прошлого тика), сдвиг сглажен по соседним
     * тикам ±{@link #EASE} — камера отходит от стены плавно, а не прыжком; где сглаженного мало, — сдвиг самого тика.
     * Между тиками — по прямой. Мир — клиента; сдвиг тика считается, когда чанк его места у клиента есть, и помнится:
     * запись, журнал и вывод видят один путь.
     */
    static final class Clear {
        static final int EASE = 3;
        private final java.util.function.DoubleFunction<Vec3> place;
        private final java.util.Map<Integer, Vec3> raw = new java.util.HashMap<>(), eased = new java.util.HashMap<>();
        private boolean warned;

        Clear(java.util.function.DoubleFunction<Vec3> place) {
            this.place = place;
        }

        Vec3 at(double t) {
            int i = Mth.floor(t);
            Vec3 a = eased(i), b = eased(i + 1);
            Vec3 p = place.apply(t);
            return a == null || b == null ? p : p.add(a.lerp(b, t - i));
        }

        /** Сглаженный сдвиг тика: из сдвигов соседей — с наибольшим весом (вес убывает к краю окна). */
        @Nullable
        private Vec3 eased(int i) {
            Vec3 got = eased.get(i);
            if (got != null) return got;
            Level level = Minecraft.getInstance().level;
            if (level == null) return null;
            Vec3 best = Vec3.ZERO;
            double most = 0;
            // по порядку тиков: место прошлого тика — для проверки, что сдвиг его видит
            for (int j = i - EASE; j <= i + EASE; j++) {
                Vec3 r = raw(level, j);
                if (r == null) return null;
                double w = 1 - Math.abs(i - j) / (EASE + 1.0);
                if (r.length() * w > most) {
                    most = r.length() * w;
                    best = r.scale(w);
                }
            }
            if (Rig.World.blocking(level, place.apply(i).add(best)) != null) best = raw(level, i);
            eased.put(i, best);
            return best;
        }

        @Nullable
        private Vec3 raw(Level level, int i) {
            Vec3 got = raw.get(i);
            if (got != null) return got;
            Vec3 p = place.apply(i);
            if (level.getChunkSource().getChunkNow(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4) == null) return null;
            Vec3 before = raw.get(i - 1);
            Vec3 q = nearestFree(level, p, before == null ? null : place.apply(i - 1).add(before));
            if (q == null) {
                if (!warned) Incoming.LOG.warn("SCENARIO film camera: у {} в {} блоках нет места без блоков", War.xyz(p), PUSH);
                warned = true;
                q = p;
            }
            Vec3 off = q.subtract(p);
            raw.put(i, off);
            return off;
        }
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

        /** Как {@link #at}, а за концами пути — по прямой его последнего (первого) блока: направление там то же. */
        Vec3 beyond(double dist) {
            double len = length();
            if (dist <= len && dist >= 0) return at(dist);
            boolean end = dist > len;
            Vec3 edge = at(end ? len : 0), in = at(end ? len - 1 : 1);
            Vec3 dir = edge.subtract(in);
            return dir.lengthSqr() < 1e-9 ? edge : edge.add(dir.normalize().scale(end ? dist - len : -dist));
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
            // чуть впереди глаз: изнутри головы видна изнанка лица (второй слой скина рисуется без отсечения)
            Vec3 look = Vec3.directionFromRotation(e.getViewXRot(pt), e.getViewYRot(pt));
            return new Pose(e.getEyePosition(pt).add(look.scale(0.4)), e.getViewYRot(pt), e.getViewXRot(pt), 0, fov);
        };
    }

    /**
     * Из-за плеча: позади головы по её курсу ({@code back}), выше ({@code up}), сбоку ({@code side}, плюс — правое
     * плечо), взгляд — туда же, куда смотрит сущность, или на точку {@code at}, если она есть. Место в блоках (стена за
     * спиной, перила) — ближайшее свободное, откуда видны глаза ({@link #nearestFree}).
     */
    static Path shoulder(Supplier<Entity> who, double back, double up, double side, @Nullable Supplier<Vec3> at, double fov) {
        final Vec3[] last = {null, null};
        return t -> {
            Entity e = who.get();
            if (e == null) return pose != null ? pose : new Pose(Vec3.ZERO, 0, 0, 0, fov);
            float pt = partial();
            Vec3 eye = e.getEyePosition(pt);
            Vec3 look = Vec3.directionFromRotation(e.getViewXRot(pt), e.getViewYRot(pt));
            Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
            Vec3 right = new Vec3(-flat.z, 0, flat.x);
            Vec3 from = eye.subtract(flat.scale(back)).add(0, up, 0).add(right.scale(side));
            // статист стоит: место то же — сдвиг тот же
            if (from.equals(last[0])) {
                from = last[1];
            } else {
                Vec3 free = nearestFree(e.level(), from, eye);
                last[0] = from;
                last[1] = from = free != null ? free : from;
            }
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
