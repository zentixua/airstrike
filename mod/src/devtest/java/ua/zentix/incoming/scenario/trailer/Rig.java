package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
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
        /**
         * Объект рисует мод после мира (ядерная картинка, {@code AFTER_LEVEL}): дальность прорисовки, её туман и облака
         * шейдерпака его не закрывают, блоки — только загруженные у клиента.
         */
        boolean overlay;

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

        Subject overlay() {
            overlay = true;
            return this;
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
            case "bunker" -> 5;
            case "nuke" -> 18;
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
            case "bunker" -> 24;
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

    /**
     * Полёт снаряда без удара в кадре: тот, что камера сценария держит в кадре дольше всех в окне плана (отметки
     * «start»…«end»; при равенстве — крупнее в кадре). Пусковые выпускают десятки снарядов, повтор начинается посреди
     * полёта прежних, а камера плана смотрит на свои: первый по появлению часто не в кадре. Без камеры в журнале — первый
     * по появлению.
     */
    static Function<EventLog.Data, Subject> flight(String weapon) {
        return d -> {
            List<EventLog.Flight> all = d.of(weapon).stream().filter(f -> !f.at.isEmpty()).sorted(Comparator.comparingInt(EventLog.Flight::first)).toList();
            if (all.isEmpty()) return null;
            CineCamera.Path cam = recorded(d);
            EventLog.Flight f = all.get(0);
            if (cam != null) {
                EventLog.Mark start = d.mark("start"), end = d.mark("end");
                int a = start == null ? 0 : start.t(), b = end == null ? d.ticks - 1 : end.t();
                double clouds = cloudBase();
                // ровно — первый по появлению (max оставляет первый из равных)
                f = all.stream().max(Comparator.comparingDouble((EventLog.Flight x) -> framed(x, cam, a, b, clouds, false))
                        .thenComparingDouble(x -> framed(x, cam, a, b, clouds, true))).orElseThrow();
            }
            return new Subject(weapon, f.id, sizeOf(weapon), f::pos, f.first(), f.last(), -1, null, 0);
        };
    }

    /**
     * Сколько тиков окна снаряд в кадре камеры (с полями, ближе тумана у края прорисовки, не в облаках); {@code size} —
     * сумма его долей высоты кадра за эти тики.
     */
    private static double framed(EventLog.Flight f, CineCamera.Path cam, int a, int b, double clouds, boolean size) {
        double sum = 0;
        double across = sizeOf(f.weapon);
        for (int t = Math.max(a, f.first()); t <= Math.min(b, f.last()); t++) {
            Pose p = cam.at(t);
            Vec3 at = f.pos(t);
            if (at == null || !inFrame(p, at, 0.85) || beyondFog(p.pos(), at) || throughClouds(p.pos(), at, clouds)) continue;
            sum += size ? across / (2 * p.pos().distanceTo(at) * Math.tan(Math.toRadians(p.fov() / 2))) : 1;
        }
        return sum;
    }

    /**
     * Удар снаряда, который камера сценария снимает лучше всех: удар в окне плана (отметки «start»…«end» с запасом
     * {@link Coverage#HIT_MARGIN}), место удара в кадре в его тик, потом — дольше всех в кадре до удара. Залп, где заранее не
     * знать, какой снаряд дойдёт (ЗРК сбивает часть), а камера ведёт ближайший к цели. Без камеры — первый удар в окне.
     */
    static Function<EventLog.Data, Subject> framedImpact(String weapon) {
        return d -> {
            EventLog.Mark start = d.mark("start"), end = d.mark("end");
            int a = start == null ? 0 : start.t(), b = end == null ? d.ticks - 1 : end.t();
            List<EventLog.Flight> hits = d.of(weapon).stream()
                    .filter(f -> f.impact >= a && f.impact <= b + Coverage.HIT_MARGIN && f.impactPos != null)
                    .sorted(Comparator.comparingInt((EventLog.Flight f) -> f.impact)).toList();
            if (hits.isEmpty()) return null;
            CineCamera.Path cam = recorded(d);
            EventLog.Flight f = hits.get(0);
            if (cam != null) {
                double clouds = cloudBase();
                f = hits.stream().max(Comparator.comparingInt((EventLog.Flight x) -> inFrame(cam.at(x.impact), x.impactPos, 0.85) ? 1 : 0)
                        .thenComparingDouble(x -> framed(x, cam, a, x.impact, clouds, false))).orElseThrow();
            }
            return new Subject(weapon + " → удар", f.id, sizeOf(weapon), f::pos, f.first(), f.last(), f.impact, f.impactPos, blastOf(weapon));
        };
    }

    /** Неподвижное место (пейзаж, площадь), без события. */
    static Function<EventLog.Data, Subject> place(Vec3 p, double size) {
        return d -> new Subject("место", null, size, t -> p, 0, d.ticks - 1, -1, null, 0);
    }

    /** План без объекта (цех, улица, пролёт): проверяется только мир — построен и без дыма на месте. */
    static Function<EventLog.Data, Subject> scene() {
        return d -> new Subject("сцена", null, 0, t -> null, 0, d.ticks - 1, -1, null, 0);
    }

    /** Ядерный подрыв журнала: событие — подрыв, объект — точка подрыва; поперечник шара — {@code size} блоков. */
    static Function<EventLog.Data, Subject> nuke(double size) {
        return d -> {
            if (d.nukes.isEmpty()) return null;
            EventLog.Nuke n = d.nukes.get(0);
            return new Subject("ядерный подрыв", null, size, t -> n.burst(), n.t(), n.t(), n.t(), n.burst(), size).overlay();
        };
    }

    // ================================================================ мир повтора

    /**
     * Слой облаков: облака не блоки и не частицы, но закрывают объект так же. Ваниль — высота облаков измерения (4 блока
     * толщиной), шейдерпак хоста — свой слой ({@code INCOMING_FILM_CLOUDS} — его низ; {@code tools/trailer/record.sh}
     * ставит слой Complementary над ним); NaN — облаков нет.
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

    /**
     * Доля дальности прорисовки, дальше которой объект в тумане у её края: у ванили туман — последняя десятая доля, у
     * шейдерпака (Complementary) — плавнее и раньше. Дальше прорисовки обычных чанков нет (у DH там только грубый рельеф
     * без сущностей) — объект там не снят, даже если луч до него свободен.
     */
    static final double FOG = 0.8;

    /** Объект дальше {@link #FOG} прорисовки по горизонтали (туман у ванили — цилиндр вокруг камеры). */
    static boolean beyondFog(Vec3 eye, Vec3 p) {
        double rd = Minecraft.getInstance().gameRenderer.getRenderDistance();
        return Math.hypot(p.x - eye.x, p.z - eye.z) > FOG * rd;
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

        /** Все чанки под отрезком у клиента готовы: колонки чанков, которые он пересекает (обход сетки по порядку). */
        boolean loadedAlong(Vec3 from, Vec3 to) {
            double x0 = from.x / 16, z0 = from.z / 16, dx = to.x / 16 - x0, dz = to.z / 16 - z0;
            int cx = Mth.floor(x0), cz = Mth.floor(z0), steps = Math.abs(Mth.floor(to.x / 16) - cx) + Math.abs(Mth.floor(to.z / 16) - cz);
            int sx = dx > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
            // доля отрезка до следующей границы чанка по x и по z и шаг этой доли на чанк
            double ix = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx), iz = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
            double nx = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? cx + 1 - x0 : x0 - cx) * ix;
            double nz = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? cz + 1 - z0 : z0 - cz) * iz;
            for (int i = 0; ; i++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
                if (i == steps) return true;
                if (nx < nz) {
                    nx += ix;
                    cx += sx;
                } else {
                    nz += iz;
                    cz += sz;
                }
            }
        }

        /**
         * Конец луча видимости из {@code from} к {@code to}: без последних {@code spare} блоков у цели; {@code reach} — не
         * дальше {@link #FOG} прорисовки (объект, который мод рисует поверх мира: дальше блоков у клиента нет). null — цель
         * ближе, луч не нужен.
         */
        @Nullable
        static Vec3 sightEnd(Vec3 from, Vec3 to, double spare, boolean reach) {
            Vec3 d = to.subtract(from);
            if (reach) {
                double h = Math.hypot(d.x, d.z), max = FOG * Minecraft.getInstance().gameRenderer.getRenderDistance();
                if (h > max) return from.add(d.scale(max / h));
            }
            double len = d.length();
            return len < spare + 0.5 ? null : from.add(d.scale((len - spare) / len));
        }

        /** Прямая видимость: блоков между точками нет (луч — {@link #sightEnd}). */
        boolean clear(Vec3 from, Vec3 to, double spare, boolean reach) {
            Vec3 end = sightEnd(from, to, spare, reach);
            if (end == null) return true;
            if (!loaded(from) || !loaded(end)) return false;
            BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
            return hit.getType() == HitResult.Type.MISS;
        }

        /** Камера не в блоке и вокруг неё полблока воздуха ({@link #blocking}). */
        boolean free(Vec3 p) {
            return loaded(p) && blocking(level, p) == null;
        }

        /** Полкуба вокруг камеры: столько воздуха до блоков у неё должно быть. */
        static final double CLEAR = 0.5;

        /**
         * Блок, чья форма столкновения задевает куб ±{@link #CLEAR} вокруг {@code p}; null — куб свободен. Форма, а не
         * клетка блока: ковёр под ногами статиста (1/16 блока) камеру в 0,9 блока над ним не закрывает (p01d).
         */
        @Nullable
        static BlockPos blocking(net.minecraft.world.level.Level level, Vec3 p) {
            AABB box = new AABB(p.x - CLEAR, p.y - CLEAR, p.z - CLEAR, p.x + CLEAR, p.y + CLEAR, p.z + CLEAR);
            for (BlockPos b : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                    Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
                VoxelShape shape = level.getBlockState(b).getCollisionShape(level, b);
                if (shape.isEmpty()) continue;
                for (AABB part : shape.toAabbs()) {
                    if (part.move(b).intersects(box)) return b.immutable();
                }
            }
            return null;
        }
    }

    // ================================================================ кандидаты

    /**
     * Камера плана по тикам повтора (с долей). {@code close} — камера у самого объекта (погоня, вид с борта): его мотор
     * в таком плане должен быть слышен. {@code designed} — камера сценария ({@link #own}): выводится первой, если оценка
     * выше нуля.
     */
    record Candidate(String name, CineCamera.Path path, double mm, boolean close, boolean designed) {
        Candidate(String name, CineCamera.Path path, double mm, boolean close) {
            this(name, path, mm, close, false);
        }
    }

    /** Оценка кандидата по журналу и миру до вывода. */
    record Score(double total, double approach, double atKey, double after, double size, double smoke, boolean free, String why) {
        static final Score REJECT = new Score(-1, 0, 0, 0, 0, 0, false, "");
    }

    /** Тип камеры: из объекта и окна — кандидаты. */
    interface Kind {
        List<Candidate> candidates(Subject s, int a, int b, World w, EventLog.Data d);
    }

    /**
     * Штатив с панорамой: камера стоит в {@code dist} блоках от места события, по кругу (азимут — от встречного курса
     * подлёта: 0 — снаряд летит в камеру, 90 — проходит поперёк кадра), на высоте с углом {@code elev} к месту события
     * (не ниже 2 блоков над землёй), и ведёт взгляд за снарядом и на удар, как оператор на голове штатива. Объектив —
     * по расстоянию: огненный шар в момент удара — около {@code share} высоты кадра (от 24 до 135 мм). Кандидатов много —
     * отбирает оценка ({@link #score}): видно ли подлёт и удар с этого места.
     */
    static Kind tripod(double[] dists, double azStep, double[] elevs, double share) {
        return (s, a, b, w, d) -> {
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
        return (s, a, b, w, d) -> {
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
        return (s, a, b, w, d) -> List.of(new Candidate(name, path, mm, false));
    }

    /**
     * Камера, которую сценарий вёл при записи (кран, FPV, штатив за спиной): из журнала по тикам, между тиками — по прямой
     * и кратчайшим поворотом. Оценка и проверка кадров — те же, что у камер по журналу. {@code close} — камера у самого
     * объекта (FPV за снарядом): его мотор должен быть слышен.
     */
    static Kind own(boolean close) {
        return (s, a, b, w, d) -> {
            CineCamera.Path path = recorded(d);
            if (path == null) return List.of();
            double fov = path.at(a).fov();
            double mm = 12 / Math.tan(Math.toRadians(fov / 2));
            return List.of(new Candidate(String.format(Locale.ROOT, "own-%.0fmm", mm), path, mm, close, true));
        };
    }

    /**
     * Запасная к камере сценария: тот же путь и взгляд, другой объектив {@code mm} (план с длинным фокусом теряет объект —
     * тот же план шире). Оценка и проверка — как у остальных запасных.
     */
    static Kind own(boolean close, double mm) {
        return (s, a, b, w, d) -> {
            CineCamera.Path path = recorded(d);
            if (path == null) return List.of();
            double fov = fov(mm);
            CineCamera.Path wide = t -> {
                Pose p = path.at(t);
                return new Pose(p.pos(), p.yaw(), p.pitch(), p.roll(), fov);
            };
            return List.of(new Candidate(String.format(Locale.ROOT, "own-%.0fmm-wide", mm), wide, mm, close));
        };
    }

    /** Камера сценария по журналу (вне журнала — ближайшая поза); null — сценарий камеру не вёл. */
    @Nullable
    static CineCamera.Path recorded(EventLog.Data d) {
        if (d.camera.isEmpty()) return null;
        return t -> {
            var lo = d.camera.floorEntry((int) Math.floor(t));
            var hi = d.camera.ceilingEntry((int) Math.ceil(t));
            if (lo == null) return hi.getValue();
            if (hi == null || hi.getKey().equals(lo.getKey())) return lo.getValue();
            double k = (t - lo.getKey()) / (hi.getKey() - lo.getKey());
            Pose p = lo.getValue(), q = hi.getValue();
            return new Pose(p.pos().lerp(q.pos(), k), p.yaw() + Mth.wrapDegrees(q.yaw() - p.yaw()) * (float) k,
                    Mth.lerp((float) k, p.pitch(), q.pitch()), Mth.lerp((float) k, p.roll(), q.roll()), Mth.lerp(k, p.fov(), q.fov()));
        };
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
     * (с полями), виден (луч до него свободен, ближе тумана у края прорисовки) и не мельче {@code minShare} высоты кадра; удар в кадре и виден
     * в первые полсекунды; камера в воздухе по всему пути; линия взгляда не идёт сквозь столбы дыма прежних ударов.
     * <p>
     * Образцы — тик через тик окна, и каждый идёт в счёт, только когда у клиента есть все чанки, которые он читает: место
     * камеры и луч до объекта. Мир повтора приходит только вокруг его зрителя, поэтому {@link ReplayFilm} водит зрителя по
     * камерам, чьи образцы ждут мира ({@link #waiting}), пока не решены все. Камера, чей чанк у зрителя рядом так и не
     * пришёл, — вне мира повтора: кадр без мира, кандидат отпадает, как камера в блоках.
     */
    static final class Tally {
        final Candidate c;
        private final Subject s;
        private final List<EventLog.Flight> earlier;
        private final double minShare;
        /** Тики образцов, которые ждут мира. */
        private final java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
        private int n, ok, keyN, keyOk, afterN, afterOk, smokeHits;
        private double sizeSum;
        private boolean inBlocks, outside;
        /** Где камера впервые в блоках: тик, место, блок — в причину оценки. */
        private String blockedAt = "";

        Tally(Candidate c, Subject s, int a, int b, List<EventLog.Flight> earlier, double minShare) {
            this.c = c;
            this.s = s;
            this.earlier = earlier;
            this.minShare = minShare;
            for (int t = a; t <= b; t += 2) pending.add(t);
        }

        /** Камера в блоках или вне мира повтора: кандидат отпал, остальное не важно. */
        boolean rejected() {
            return inBlocks || outside;
        }

        /** Первый образец, который ждёт мира (зрителю — к его камере); −1 — все в счёте. */
        int waiting() {
            return pending.isEmpty() ? -1 : pending.peekFirst();
        }

        /**
         * В счёт — образцы, чей мир уже у клиента. {@code stop} — где стоит зритель, когда мир у него пришёл весь, сколько
         * придёт: чанк ближе {@code radius} к нему, которого нет, — вне мира повтора (камера там — кандидат отпадает,
         * луч туда — объект не виден); null — образцы без мира ждут дальше.
         */
        void evaluate(World w, @Nullable Vec3 stop, double radius) {
            for (var it = pending.iterator(); it.hasNext() && !rejected(); ) {
                if (sample(it.next(), w, stop, radius)) it.remove();
            }
            if (rejected()) pending.clear();
        }

        private static boolean near(@Nullable Vec3 stop, Vec3 p, double radius) {
            return stop != null && Math.hypot(p.x - stop.x, p.z - stop.z) <= radius;
        }

        /** @return образец в счёте; false — нужного чанка у клиента нет, а зритель стоит не рядом с ним */
        private boolean sample(int t, World w, @Nullable Vec3 stop, double radius) {
            Pose p = c.path().at(t);
            Vec3 cam = p.pos();
            if (!w.loaded(cam)) {
                if (!near(stop, cam, radius)) return false;
                outside = true;
                return true;
            }
            Vec3 aim = s.aim(t);
            boolean in = aim != null && inFrame(p, aim, 0.85);
            boolean seen = false;
            double spare = aim == null ? 0 : Math.max(2, s.aimSize(t) * 0.4);
            // луч — к объекту в кадре, что не в облаках и ближе тумана у края прорисовки; к объекту поверх мира — до её края
            if (in && (s.overlay || !throughClouds(cam, aim, w.clouds) && !beyondFog(cam, aim))) {
                Vec3 end = World.sightEnd(cam, aim, spare, s.overlay);
                if (end == null || w.loadedAlong(cam, end)) {
                    seen = w.clear(cam, aim, spare, s.overlay);
                } else if (!near(stop, end, radius)) {
                    return false;
                }
            }
            BlockPos wall = World.blocking(w.level, cam);
            if (wall != null && !inBlocks) {
                inBlocks = true;
                blockedAt = String.format(Locale.ROOT, " (тик %d у %s: %s %s)", t, War.xyz(cam),
                        w.level.getBlockState(wall).getBlock().getName().getString(), War.xyz(Vec3.atLowerCornerOf(wall)));
            }
            if (aim == null) return true;
            double share = s.aimSize(t) / (2 * cam.distanceTo(aim) * Math.tan(Math.toRadians(p.fov() / 2)));
            boolean smoky = smokeOnLine(cam, aim, earlier, t);
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
            return true;
        }

        Score score() {
            double approach = n == 0 ? 1 : (double) ok / n;
            double atKey = keyN == 0 ? 1 : (double) keyOk / keyN;
            double after = afterN == 0 ? 1 : (double) afterOk / afterN;
            double meanShare = n == 0 ? 0 : sizeSum / n;
            double smoke = (double) smokeHits / Math.max(1, n + keyN + afterN);
            StringBuilder why = new StringBuilder();
            if (inBlocks) why.append(" камера в блоках").append(blockedAt).append(';');
            if (outside) why.append(" камера вне мира повтора;");
            if (!rejected() && atKey < 0.5) why.append(" удар не виден;");
            double total = rejected() ? -1 : approach * 0.45 + atKey * 0.35 + after * 0.1 + Math.min(1, meanShare / 0.06) * 0.1 - smoke * 0.3;
            return new Score(total, approach, atKey, after, meanShare, smoke, !rejected(), why.toString().trim());
        }
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
