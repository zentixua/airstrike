package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * Камера плана по журналу его повтора ({@link EventLog}): что снимаем ({@link Subject}), когда (окно вокруг главного
 * события) и откуда ({@link Kind} — тип камеры с объективом). Камера ставится не наугад: каждое место-кандидат
 * проверяется по миру повтора и журналу — снаряд в кадре и не за домом, удар виден, камера не в стене, дым прежних ударов
 * не на линии взгляда, размер в кадре в своих пределах; лучший кандидат идёт в вывод, а вывод проверяет то же по каждому
 * кадру ({@link FrameQa}).
 */
final class Rig {
    private Rig() {}

    /** Поле зрения по вертикали для объектива {@code mm} на полном кадре 36×24 (как считают операторы). */
    static double fov(double mm) {
        return Math.toDegrees(2 * Math.atan(12 / mm));
    }

    static final double ASPECT = 16 / 9.0;

    // ================================================================ что снимаем

    /** Объект плана во времени и главное событие (удар). */
    static final class Subject {
        final String what;
        @Nullable
        final UUID entity;
        /** Поперечник объекта, блоков (шахед ~3, ракета ~6, B-2 ~50). */
        final double size;
        /** Место объекта в тик t (с долей); null — его нет. */
        final DoubleFunction<Vec3> at;
        /** Тик главного события (удар) и его место; −1 — события нет. */
        final int key;
        @Nullable
        final Vec3 keyPos;
        /** Поперечник события (огненный шар), блоков. */
        final double keySize;
        /** Тики, где объект есть в журнале. */
        final int first, last;

        Subject(String what, @Nullable UUID entity, double size, DoubleFunction<Vec3> at, int first, int last, int key,
                @Nullable Vec3 keyPos, double keySize) {
            this.what = what;
            this.entity = entity;
            this.size = size;
            this.at = at;
            this.first = first;
            this.last = last;
            this.key = key;
            this.keyPos = keyPos;
            this.keySize = keySize;
        }

        /**
         * Куда смотрит кадр в тик t: объект; до его появления — место появления (пусковая), после события — место
         * события (шар поднимается).
         */
        @Nullable
        Vec3 aim(double t) {
            if (key >= 0 && t >= key && keyPos != null) return keyPos.add(0, Math.min(1, (t - key) / 40.0) * keySize * 0.4, 0);
            if (t < first) return at.apply(first);
            if (t > last) return key >= 0 && keyPos != null ? keyPos : at.apply(last);
            return at.apply(t);
        }

        /** Поперечник того, на что смотрит кадр в тик t. */
        double aimSize(double t) {
            return key >= 0 && t >= key ? keySize : size;
        }

        /** Курс подлёта к событию по горизонтали (по пути за 40 тиков до него); без события — курс в начале. */
        Vec3 heading() {
            int t1 = key >= 0 ? Math.min(key - 1, last) : last, t0 = Math.max(first, t1 - 40);
            Vec3 a = at.apply(t0), b = at.apply(t1);
            if (a == null || b == null) return new Vec3(1, 0, 0);
            Vec3 d = b.subtract(a).multiply(1, 0, 1);
            return d.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : d.normalize();
        }
    }

    /** Поперечники снарядов мода, блоков (для доли кадра). */
    static double sizeOf(String weapon) {
        return switch (weapon) {
            case "drone" -> 3.5;
            case "missile" -> 6;
            case "rocket" -> 3;
            case "bomber" -> 40;
            case "bunker_buster" -> 5;
            case "icbm" -> 18;
            case "loiter" -> 2.5;
            default -> 4;
        };
    }

    /** Огненный шар удара, блоков. */
    static double blastOf(String weapon) {
        return switch (weapon) {
            case "rocket" -> 8;
            case "drone", "loiter" -> 12;
            case "missile" -> 18;
            case "bunker_buster" -> 24;
            default -> 16;
        };
    }

    /**
     * Удар снаряда {@code weapon}, ближайший к {@code near} (null — первый по времени, {@code nth} — какой по счёту
     * среди подходящих, с нуля). Объект — снаряд до удара, потом место удара.
     */
    static Function<EventLog.Data, Subject> impact(String weapon, @Nullable Vec3 near, int nth) {
        return d -> {
            List<EventLog.Flight> hits = d.of(weapon).stream().filter(f -> f.impact >= 0 && f.impactPos != null)
                    .sorted(near == null ? Comparator.comparingInt((EventLog.Flight f) -> f.impact)
                            : Comparator.comparingDouble((EventLog.Flight f) -> f.impactPos.multiply(1, 0, 1).distanceTo(near.multiply(1, 0, 1))))
                    .toList();
            if (hits.size() <= nth) return null;
            EventLog.Flight f = hits.get(nth);
            return new Subject(weapon + " → удар", f.id, sizeOf(weapon), f::pos, f.first(), f.last(), f.impact, f.impactPos, blastOf(weapon));
        };
    }

    /** Полёт снаряда без удара в кадре: первый по времени появления ({@code nth} — какой по счёту). */
    static Function<EventLog.Data, Subject> flight(String weapon, int nth) {
        return d -> {
            List<EventLog.Flight> all = d.of(weapon).stream().filter(f -> !f.at.isEmpty()).sorted(Comparator.comparingInt(EventLog.Flight::first)).toList();
            if (all.size() <= nth) return null;
            EventLog.Flight f = all.get(nth);
            return new Subject(weapon, f.id, sizeOf(weapon), f::pos, f.first(), f.last(), -1, null, 0);
        };
    }

    /** Неподвижное место (пейзаж, площадь); событие — отметка журнала {@code mark} или нет. */
    static Function<EventLog.Data, Subject> place(Vec3 p, double size) {
        return d -> new Subject("место", null, size, t -> p, 0, d.ticks - 1, -1, null, 0);
    }

    // ================================================================ мир повтора

    /**
     * Слой облаков: облака не блоки и не частицы, но закрывают объект так же. Ваниль — высота облаков измерения (4 блока
     * толщиной), шейдерпак хоста — свой слой около 190 ({@code INCOMING_FILM_CLOUDS} — его низ); NaN — облаков нет.
     */
    static double cloudBase() {
        String env = System.getenv("INCOMING_FILM_CLOUDS");
        if (env != null && !env.isBlank()) return Double.parseDouble(env);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.getCloudsType() == net.minecraft.client.CloudStatus.OFF) return Double.NaN;
        return mc.level.effects().getCloudHeight();
    }

    static final double CLOUD_DEPTH = 6;

    /** Отрезок взгляда проходит слоем облаков (или камера в нём). */
    static boolean throughClouds(Vec3 from, Vec3 to, double base) {
        if (Double.isNaN(base)) return false;
        double lo = Math.min(from.y, to.y), hi = Math.max(from.y, to.y);
        return hi >= base - 1 && lo <= base + CLOUD_DEPTH;
    }

    /** Мир повтора у клиента: лучи и высоты только по готовым чанкам. */
    static final class World {
        final ClientLevel level = Minecraft.getInstance().level;
        final double clouds = cloudBase();

        boolean loaded(Vec3 p) {
            return level.getChunkSource().getChunkNow(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4) != null;
        }

        /** Высота земли (верх блоков, что держат движение, с листвой). */
        double ground(double x, double z) {
            if (!loaded(new Vec3(x, 0, z))) return Double.NaN;
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        }

        /** Прямая видимость: блоков между точками нет (последние {@code spare} блоков у цели не в счёт). */
        boolean clear(Vec3 from, Vec3 to, double spare) {
            Vec3 d = to.subtract(from);
            double len = d.length();
            if (len < spare + 0.5) return true;
            Vec3 end = from.add(d.scale((len - spare) / len));
            if (!loaded(from) || !loaded(end)) return false;
            BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
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

    // ================================================================ кандидаты

    /**
     * Камера плана по тикам повтора (с долей). {@code close} — камера у самого объекта (погоня, вид с борта): его мотор
     * в таком плане должен быть слышен.
     */
    record Candidate(String name, CineCamera.Path path, double mm, boolean close) {}

    /** Оценка кандидата по журналу и миру до вывода. */
    record Score(double total, double approach, double atKey, double after, double size, double smoke, boolean free, String why) {
        static final Score REJECT = new Score(-1, 0, 0, 0, 0, 0, false, "");
    }

    /** Тип камеры: из объекта и окна — кандидаты. */
    interface Kind {
        List<Candidate> candidates(Subject s, int a, int b, World w);
    }

    /**
     * Штатив с панорамой: камера стоит в {@code dist} блоках от места события, по кругу (азимут — от встречного курса
     * подлёта: 0 — снаряд летит в камеру, 90 — проходит поперёк кадра), на высоте с углом {@code elev} к месту события
     * (не ниже 2 блоков над землёй), и ведёт взгляд за снарядом и на удар, как оператор на голове штатива. Объектив —
     * по расстоянию: огненный шар в момент удара — около {@code share} высоты кадра (от 24 до 135 мм). Кандидатов много —
     * отбирает оценка ({@link #score}): видно ли подлёт и удар с этого места.
     */
    static Kind tripod(double[] dists, double azStep, double[] elevs, double share) {
        return (s, a, b, w) -> {
            List<Candidate> out = new ArrayList<>();
            Vec3 k = s.keyPos != null ? s.keyPos : s.aim((a + b) / 2.0);
            if (k == null) return out;
            Vec3 h = s.heading();
            double size = s.keyPos != null ? s.keySize : s.size * 4;
            for (double dist : dists) {
                double mm = Mth.clamp(12 * 2 * dist * share / size, 24, 135);
                for (double side = 0; side < 360; side += azStep) {
                    for (double elev : elevs) {
                        double r = Math.toRadians(side);
                        // от места удара навстречу снаряду, повёрнутое на side
                        Vec3 dir = new Vec3(-h.x * Math.cos(r) + h.z * Math.sin(r), 0, -h.z * Math.cos(r) - h.x * Math.sin(r));
                        Vec3 spot = k.add(dir.scale(dist));
                        double g = w.ground(spot.x, spot.z);
                        if (Double.isNaN(g)) continue;
                        Vec3 cam = new Vec3(spot.x, Math.max(g + 2, k.y + dist * Math.tan(Math.toRadians(elev))), spot.z);
                        out.add(new Candidate(String.format(Locale.ROOT, "tripod-%.0fmm-d%.0f-a%.0f-e%.0f", mm, dist, side, elev),
                                follow(t -> cam, s, a, b, 6, mm), mm, false));
                    }
                }
            }
            return out;
        };
    }

    /**
     * Погоня: камера позади снаряда ({@code back}), выше ({@code up}), сбоку ({@code side}) по сглаженному курсу; за
     * {@code release} тиков до удара камера отпускает снаряд — тормозит на своём ходу и смотрит, как он входит в цель.
     */
    static Kind chase(double mm, double back, double up, double side, int release) {
        return (s, a, b, w) -> {
            int rel = s.key >= 0 ? s.key - release : Integer.MAX_VALUE;
            Vec3[] cam = new Vec3[b - a + 61];
            Vec3 dir = s.heading();
            Vec3 vel = Vec3.ZERO;
            for (int t = a - 60; t <= b; t++) {
                int i = t - (a - 60);
                Vec3 p = s.at.apply(Mth.clamp(Math.min(t, rel), s.first, s.last));
                if (p == null) p = s.keyPos;
                Vec3 v = s.at.apply(t + 1) != null && s.at.apply(t - 1) != null ? s.at.apply(t + 1).subtract(s.at.apply(t - 1)) : Vec3.ZERO;
                if (t < rel && v.lengthSqr() > 1e-4) dir = dir.scale(0.85).add(v.normalize().scale(0.15)).normalize();
                Vec3 flat = new Vec3(dir.x, 0, dir.z);
                flat = flat.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : flat.normalize();
                Vec3 right = new Vec3(-flat.z, 0, flat.x);
                Vec3 want = p.subtract(dir.scale(back)).add(0, up, 0).add(right.scale(side));
                if (i == 0 || t < rel) {
                    if (i > 0) vel = want.subtract(cam[i - 1]);
                    cam[i] = want;
                } else {
                    // отпущена: катится по инерции и тормозит
                    vel = vel.scale(0.88);
                    cam[i] = cam[i - 1].add(vel);
                }
            }
            CineCamera.Path pos = t -> {
                double u = Mth.clamp(t - (a - 60), 0, cam.length - 1);
                int i = (int) Math.floor(u);
                return new Pose(cam[i].lerp(cam[Math.min(cam.length - 1, i + 1)], u - i), 0, 0, 0, 0);
            };
            return List.of(new Candidate(String.format(Locale.ROOT, "chase-%.0fmm-b%.0f-u%.0f-s%.0f-r%d", mm, back, up, side, release),
                    follow(t -> pos.at(t).pos(), s, a, b, 2, mm), mm, true));
        };
    }

    /** Свой путь (пейзаж, кран): тот же разбор и проверка кадров. */
    static Kind fixed(String name, double mm, CineCamera.Path path) {
        return (s, a, b, w) -> List.of(new Candidate(name, path, mm, false));
    }

    /**
     * Камера в {@code place(t)} ведёт взгляд за объектом, как оператор: взгляд догоняет цель с запаздыванием
     * {@code lag} тиков (сглаживание по таблице с шагом в тик, считается заранее от окна с запасом — одинаково
     * в оценке и выводе).
     */
    static CineCamera.Path follow(DoubleFunction<Vec3> place, Subject s, int a, int b, double lag, double mm) {
        int from = a - 60;
        Vec3[] look = new Vec3[b - from + 2];
        Vec3 cur = null;
        double k = 1 - Math.exp(-1 / Math.max(0.01, lag));
        for (int t = from; t <= b + 1; t++) {
            Vec3 want = s.aim(t);
            if (want == null) want = s.keyPos != null ? s.keyPos : place.apply(t).add(1, 0, 0);
            cur = cur == null ? want : cur.lerp(want, k);
            look[t - from] = cur;
        }
        double fov = fov(mm);
        return t -> {
            double u = Mth.clamp(t - from, 0, look.length - 1);
            int i = (int) Math.floor(u);
            Vec3 l = look[i].lerp(look[Math.min(look.length - 1, i + 1)], u - i);
            return Pose.look(place.apply(t), l, 0, fov);
        };
    }

    // ================================================================ оценка до вывода

    /**
     * Насколько кадр кандидата снимет план, по журналу и миру повтора: доля времени до удара, когда снаряд в кадре
     * (с полями), виден (луч до него свободен) и не мельче {@code minShare} высоты кадра; удар в кадре и виден
     * в первые полсекунды; камера в воздухе по всему пути; линия взгляда не идёт сквозь столбы дыма прежних ударов.
     */
    static Score score(Candidate c, Subject s, int a, int b, World w, List<EventLog.Flight> earlier, double minShare) {
        int n = 0, ok = 0, keyN = 0, keyOk = 0, afterN = 0, afterOk = 0;
        double sizeSum = 0, smokeHits = 0;
        boolean free = true;
        StringBuilder why = new StringBuilder();
        for (int t = a; t <= b; t += 2) {
            Pose p = c.path().at(t);
            Vec3 aim = s.aim(t);
            if (!w.free(p.pos())) free = false;
            if (aim == null) continue;
            boolean in = inFrame(p, aim, 0.85);
            double d = p.pos().distanceTo(aim);
            double share = s.aimSize(t) / (2 * d * Math.tan(Math.toRadians(p.fov() / 2)));
            boolean seen = in && w.clear(p.pos(), aim, Math.max(2, s.aimSize(t) * 0.4)) && !throughClouds(p.pos(), aim, w.clouds);
            boolean smoky = smokeOnLine(p.pos(), aim, earlier, t);
            if (smoky) smokeHits++;
            if (s.key < 0 || t < s.key) {
                n++;
                if (seen && share >= minShare && !smoky) ok++;
                sizeSum += share;
            } else if (t <= s.key + 12) {
                keyN++;
                if (seen && !smoky) keyOk++;
            } else {
                afterN++;
                if (seen) afterOk++;
            }
        }
        double approach = n == 0 ? 1 : (double) ok / n;
        double atKey = keyN == 0 ? 1 : (double) keyOk / keyN;
        double after = afterN == 0 ? 1 : (double) afterOk / afterN;
        double meanShare = n == 0 ? 0 : sizeSum / n;
        double smoke = smokeHits / Math.max(1, n + keyN + afterN);
        if (!free) why.append(" камера в блоках;");
        if (atKey < 0.5) why.append(" удар не виден;");
        double total = !free ? -1 : approach * 0.45 + atKey * 0.35 + after * 0.1 + Math.min(1, meanShare / 0.06) * 0.1 - smoke * 0.3;
        return new Score(total, approach, atKey, after, meanShare, smoke, free, why.toString().trim());
    }

    /** Точка в кадре позы (доля полукадра {@code safe}: 1 — до края). */
    static boolean inFrame(Pose p, Vec3 at, double safe) {
        Vec3 fwd = Vec3.directionFromRotation(p.pitch(), p.yaw());
        Vec3 d = at.subtract(p.pos());
        double z = d.dot(fwd);
        if (z <= 0.1) return false;
        Vec3 right = fwd.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(fwd).normalize();
        double ty = Math.tan(Math.toRadians(p.fov() / 2)), tx = ty * ASPECT;
        return Math.abs(d.dot(right) / z) <= tx * safe && Math.abs(d.dot(up) / z) <= ty * safe;
    }

    /**
     * Дым прежних ударов: столб над местом удара (последние 60 с записи) — 20 блоков в поперечнике и 50 вверх; линия
     * взгляда сквозь него — кадр в дыму.
     */
    static boolean smokeOnLine(Vec3 from, Vec3 to, List<EventLog.Flight> earlier, int t) {
        for (EventLog.Flight f : earlier) {
            if (f.impact < 0 || f.impact >= t - 10 || f.impact < t - 1200 || f.impactPos == null) continue;
            Vec3 base = f.impactPos;
            for (int k = 0; k <= 50; k += 10) {
                if (distToSegment(base.add(0, k, 0), from, to) < 10 && base.distanceTo(to) > 12) return true;
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
