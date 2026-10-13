package ua.zentix.replayscan.film;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Камеры плана: кандидаты по его описанию ({@link Plan.Camera}) и их оценка по миру повтора до вывода — объект в кадре
 * и не за домом, событие видно, камера не в блоках, дым прежних ударов не на линии взгляда, размер в кадре не мельче
 * порога. Вывод потом проверяет то же по каждому кадру ({@link FrameLog}).
 */
final class Cameras {
    private Cameras() {}

    /** Поза камеры: место глаза, поворот как у игрока (yaw 0 — юг, pitch вниз — плюс), угол кадра по вертикали. */
    record Pose(Vec3 pos, float yaw, float pitch, double fov) {
        static Pose look(Vec3 eye, Vec3 at, double fov) {
            Vec3 d = at.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            return new Pose(eye, yaw, pitch, fov);
        }
    }

    /** Камера по тикам повтора (с долей). */
    interface Path {
        Pose at(double t);
    }

    /**
     * Кандидат: камера по ключевым кадрам ({@code path}) или глаза игрока ({@code player}, пути нет — камера Flashback
     * становится этим игроком). {@code designed} — камера задана планом и выводится первой, если не отвергнута.
     */
    record Candidate(String name, @Nullable Path path, @Nullable String player, boolean gui, double fov, boolean designed) {}

    /** Оценка кандидата: итог (меньше нуля — не годится) и из чего он. */
    record Score(double total, double approach, double atKey, double after, double size, double smoke, String why) {
        static final Score DESIGNED = new Score(1, 1, 1, 1, 1, 0, "designed camera");
    }

    /** Толщина слоя облаков, блоков. */
    static final double CLOUD_DEPTH = 6;

    /** Низ облаков в кадре: из плана (слой шейдерпака), иначе облака измерения; облака выключены — NaN. */
    static double cloudHeight(Plan plan) {
        if (!Double.isNaN(plan.clouds())) return plan.clouds();
        Minecraft mc = Minecraft.getInstance();
        return mc.options.getCloudsType() == CloudStatus.OFF ? Double.NaN : mc.level.effects().getCloudHeight();
    }

    /** Отрезок взгляда проходит слоем облаков низом {@code clouds} (или камера в нём). */
    static boolean throughClouds(double clouds, Vec3 from, Vec3 to) {
        if (Double.isNaN(clouds)) return false;
        return Math.max(from.y, to.y) >= clouds - 1 && Math.min(from.y, to.y) <= clouds + CLOUD_DEPTH;
    }

    /** Мир повтора у клиента: лучи и высоты только по готовым чанкам (у клиента чанка нет — нет и блоков), облака. */
    static final class World {
        final ClientLevel level = Minecraft.getInstance().level;
        /** Низ облаков ({@link #cloudHeight}). */
        final double clouds;

        World(double clouds) {
            this.clouds = clouds;
        }

        boolean loaded(Vec3 p) {
            return level.getChunkSource().getChunkNow(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4) != null;
        }

        /** Высота земли (верх блоков, что держат движение, с листвой); NaN — чанка нет. */
        double ground(double x, double z) {
            if (!loaded(new Vec3(x, 0, z))) return Double.NaN;
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        }

        /**
         * Луч свободен до {@code spare} блоков от цели. Чанки на луче без готового чанка: у объекта в прорисовке — закрыт
         * (не знаем); у дальнего ({@code far}) — луч смотрится до первого такого чанка, дальше мира у клиента нет и закрыть
         * нечему.
         */
        boolean clear(Vec3 from, Vec3 to, double spare, boolean far) {
            Vec3 d = to.subtract(from);
            double len = d.length();
            if (len < spare + 0.5) return true;
            double limit = len - spare;
            Vec3 dir = d.scale(1 / len);
            double reach = 0;
            while (reach < limit) {
                double next = Math.min(limit, reach + 8);
                if (!loaded(from.add(dir.scale(next)))) {
                    if (!far) return false;
                    break;
                }
                reach = next;
            }
            if (reach <= 0) return far;
            var hit = level.clip(new ClipContext(from, from.add(dir.scale(reach)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            return hit.getType() == HitResult.Type.MISS;
        }

        /** Камера не в блоке и вокруг неё полблока воздуха. */
        boolean free(Vec3 p) {
            if (!loaded(p)) return false;
            for (double dx : new double[]{-0.5, 0.5}) {
                for (double dy : new double[]{-0.5, 0.5}) {
                    for (double dz : new double[]{-0.5, 0.5}) {
                        BlockPos b = BlockPos.containing(p.x + dx, p.y + dy, p.z + dz);
                        if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * Угол кадра по вертикали, при котором предмет поперечником {@code size} в {@code dist} блоках занимает {@code share}
     * ширины кадра со сторонами {@code aspect} (ширина к высоте), не уже 6° и не шире 70°.
     */
    static double fovFor(double size, double dist, double share, double aspect) {
        double tanX = size / (2 * dist * share);
        return Mth.clamp(Math.toDegrees(2 * Math.atan(tanX / aspect)), 6, 70);
    }

    /** Кандидаты одной камеры плана. */
    static List<Candidate> of(Plan.Camera c, Subject s, int a, int b, double aspect, World w) {
        List<Candidate> out = new ArrayList<>();
        switch (c.kind()) {
            case "pov" -> out.add(new Candidate("pov-" + c.player().replaceAll("[^A-Za-z0-9_.-]", "_"), null, c.player(), c.gui(), c.fov(), true));
            case "fixed" -> {
                Vec3 eye = c.eye(), at = c.aim() != null ? c.aim() : s.anchor(a, b);
                if (eye == null || at == null && !c.follow()) return out;
                Path p = c.follow() ? follow(t -> eye, s, a, b, 3, c.fov()) : t -> Pose.look(eye, at, c.fov());
                out.add(new Candidate(String.format(Locale.ROOT, "fixed-%.0f", c.fov()), p, null, false, c.fov(), true));
            }
            case "tripod" -> {
                Vec3 k = c.around() != null ? c.around() : s.anchor(a, b);
                if (k == null) return out;
                Vec3 h = c.heading() != null ? c.heading().normalize() : s.heading();
                double size = c.size() > 0 ? c.size() : !s.keys.isEmpty() ? s.keys.get(0).size() : s.area != null ? s.areaSize : s.size * 4;
                for (double dist : c.dist()) {
                    double fov = fovFor(size, dist, c.share(), aspect);
                    for (double side = 0; side < 360; side += c.azStep()) {
                        for (double elev : c.elev()) {
                            double r = Math.toRadians(side);
                            // от места назад по курсу подлёта, повёрнутое на side: 0 — камера на пути подлёта, снаряд
                            // летит от неё к месту; 90 — поперёк кадра; 180 — летит в камеру
                            Vec3 dir = new Vec3(-h.x * Math.cos(r) + h.z * Math.sin(r), 0, -h.z * Math.cos(r) - h.x * Math.sin(r));
                            Vec3 spot = k.add(dir.scale(dist));
                            double g = w.ground(spot.x, spot.z);
                            if (Double.isNaN(g)) continue;
                            Vec3 eye = new Vec3(spot.x, Math.max(g + 2, k.y + dist * Math.tan(Math.toRadians(elev))), spot.z);
                            Path p = c.follow() ? follow(t -> eye, s, a, b, 6, fov) : t -> Pose.look(eye, k.add(0, size * 0.25, 0), fov);
                            out.add(new Candidate(String.format(Locale.ROOT, "tripod-f%.0f-d%.0f-a%.0f-e%.0f", fov, dist, side, elev), p, null,
                                    false, fov, false));
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("unknown camera " + c.kind());
        }
        return out;
    }

    /**
     * Камера в {@code place(t)} ведёт взгляд за объектом, как оператор: взгляд догоняет цель с запаздыванием {@code lag}
     * тиков (таблица с шагом в тик от окна с запасом — одинаково в оценке и выводе).
     */
    static Path follow(java.util.function.DoubleFunction<Vec3> place, Subject s, int a, int b, double lag, double fov) {
        int from = a - 60;
        Vec3[] look = new Vec3[b - from + 2];
        Vec3 cur = null;
        double k = 1 - Math.exp(-1 / Math.max(0.01, lag));
        for (int t = from; t <= b + 1; t++) {
            Vec3 want = s.aim(t);
            if (want == null) want = place.apply(t).add(0, 0, 1);
            cur = cur == null ? want : cur.lerp(want, k);
            look[t - from] = cur;
        }
        return t -> {
            double u = Mth.clamp(t - from, 0, look.length - 1);
            int i = (int) Math.floor(u);
            return Pose.look(place.apply(t), look[i].lerp(look[Math.min(look.length - 1, i + 1)], u - i), fov);
        };
    }

    /**
     * Оценка кандидата до вывода: доля времени до события, когда объект в кадре (с полями), виден, не мельче
     * {@code minShare} ширины кадра и не в дыму прежних ударов; события в кадре и видны в первые 12 тиков; камера в
     * воздухе по всему пути.
     */
    static Score score(Candidate c, Subject s, Plan.Shot shot, double aspect, World w) {
        if (c.path() == null) return Score.DESIGNED;
        int n = 0, ok = 0, keyN = 0, keyOk = 0, afterN = 0, afterOk = 0;
        double sizeSum = 0, smokeHits = 0;
        boolean free = true;
        int first = s.firstKey();
        for (int t = shot.a(); t <= shot.b(); t += 2) {
            Pose p = c.path().at(t);
            if (!w.free(p.pos())) free = false;
            Vec3 aim = s.aim(t);
            if (aim == null) continue;
            double size = s.aimSize(t);
            boolean in = inFrame(p, aim, 0.85, aspect);
            double z = p.pos().distanceTo(aim);
            double share = size / (2 * z * Math.tan(Math.toRadians(p.fov() / 2)) * aspect);
            boolean seen = in && w.clear(p.pos(), aim, Math.max(2, size * 0.4), shot.far()) && !throughClouds(w.clouds, p.pos(), aim);
            boolean smoky = smokeOnLine(p.pos(), aim, shot.smoke(), t);
            if (smoky) smokeHits++;
            if (first < 0 || t < first) {
                n++;
                if (seen && share >= shot.need().share() && !smoky) ok++;
                sizeSum += share;
            } else if (s.keyAt(t) != null && t <= s.keyAt(t).t() + 12) {
                keyN++;
                if (seen && !smoky) keyOk++;
            } else {
                afterN++;
                if (seen) afterOk++;
            }
        }
        double approach = n == 0 ? 1 : (double) ok / n, atKey = keyN == 0 ? 1 : (double) keyOk / keyN, after = afterN == 0 ? 1 : (double) afterOk / afterN;
        double meanShare = n == 0 ? 0 : sizeSum / n, smoke = smokeHits / Math.max(1, n + keyN + afterN);
        String why = (free ? "" : "camera inside blocks; ") + (atKey < 0.5 ? "event not seen" : "");
        double total = !free ? -1 : approach * 0.45 + atKey * 0.35 + after * 0.1 + Math.min(1, meanShare / 0.06) * 0.1 - smoke * 0.3;
        return new Score(total, approach, atKey, after, meanShare, smoke, why.trim());
    }

    /** Точка в кадре позы (доля полукадра {@code safe}: 1 — до края). */
    static boolean inFrame(Pose p, Vec3 at, double safe, double aspect) {
        Vec3 fwd = Vec3.directionFromRotation(p.pitch(), p.yaw());
        Vec3 d = at.subtract(p.pos());
        double z = d.dot(fwd);
        if (z <= 0.1) return false;
        Vec3 right = fwd.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(fwd).normalize();
        double ty = Math.tan(Math.toRadians(p.fov() / 2)), tx = ty * aspect;
        return Math.abs(d.dot(right) / z) <= tx * safe && Math.abs(d.dot(up) / z) <= ty * safe;
    }

    /**
     * Дым прежних ударов ({@code [t, x, y, z]}): столб над местом удара последней минуты — 20 блоков в поперечнике и 50
     * вверх; линия взгляда сквозь него — кадр в дыму.
     */
    static boolean smokeOnLine(Vec3 from, Vec3 to, List<double[]> smoke, int t) {
        for (double[] h : smoke) {
            if (h[0] >= t - 10 || h[0] < t - 1200) continue;
            Vec3 base = new Vec3(h[1], h[2], h[3]);
            if (base.distanceTo(to) <= 12) continue;
            for (int k = 0; k <= 50; k += 10) {
                if (distToSegment(base.add(0, k, 0), from, to) < 10) return true;
            }
        }
        return false;
    }

    static double distToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double u = len2 < 1e-9 ? 0 : Mth.clamp(p.subtract(a).dot(ab) / len2, 0, 1);
        return p.distanceTo(a.add(ab.scale(u)));
    }
}
