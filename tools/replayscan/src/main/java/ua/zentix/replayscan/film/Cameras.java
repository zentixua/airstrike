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
 * и не за домом, события видны, камера не в блоках, дым прежних ударов не на линии взгляда, размер в кадре не мельче
 * порога. Вывод потом проверяет то же по каждому кадру ({@link FrameLog}) теми же лучами и тем же кадром ({@link View},
 * {@link #open}, {@link #keySeen}): оценка предсказывает его итог, а не догадывается о нём.
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

    /** Камера по тикам повтора (с долей); крен — градусы, плюс — влево (ключ камеры Flashback). */
    interface Path {
        Pose at(double t);

        default double roll(double t) {
            return 0;
        }
    }

    /**
     * Кандидат: камера по ключевым кадрам ({@code path}) или глаза игрока ({@code player}, пути нет — камера Flashback
     * становится этим игроком). {@code designed} — камера задана планом и выводится первой, если не отвергнута;
     * {@code roam} — путь задан планом и идёт и там, где у клиента нет чанков (в блоках камера — только где чанк есть).
     */
    record Candidate(String name, @Nullable Path path, @Nullable String player, boolean gui, double fov, boolean designed, boolean roam) {}

    /**
     * Оценка кандидата: итог (меньше нуля — не годится), из чего он и пройдёт ли вывод пороги плана ({@code pass}: объект
     * до события, за последнюю секунду и события — не меньше долей {@link Plan.Need}).
     */
    record Score(double total, double approach, double near, double keys, double after, double size, double smoke, boolean pass, String why) {
        static final Score DESIGNED = new Score(1, 1, 1, 1, 1, 1, 0, true, "designed camera");
    }

    /** Толщина слоя облаков, блоков. */
    static final double CLOUD_DEPTH = 6;
    /** Доля дальности прорисовки, дальше которой объект в тумане у её края (у шейдерпака туман раньше и плавнее). */
    static final double FOG = 0.8;
    /** Поле кадра, в котором оценка держит объект (вывод — {@link FrameLog}: 0.92): камера ведёт путь, а не сущность. */
    static final double SAFE = 0.85;

    /**
     * Кадр камеры: глаз, оси (вперёд, вправо, вверх) и тангенсы половины угла по ширине и высоте. Вывод строит его по
     * камере и проекции кадра, оценка — по позе и сторонам кадра.
     */
    record View(Vec3 eye, Vec3 fwd, Vec3 right, Vec3 up, double tanX, double tanY) {
        static View of(Pose p, double aspect) {
            Vec3 fwd = Vec3.directionFromRotation(p.pitch(), p.yaw());
            Vec3 right = fwd.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : right.normalize();
            double ty = Math.tan(Math.toRadians(p.fov() / 2));
            return new View(p.pos(), fwd, right, right.cross(fwd).normalize(), ty * aspect, ty);
        }

        /**
         * Кадр с креном (градусы, плюс — влево, как у ключа Flashback): Flashback поворачивает поворот камеры вокруг её
         * оси вперёд ({@code rotateZ}) после того, как камера посчитала свои векторы, — у них крена нет.
         */
        View rolled(double roll) {
            if (roll == 0) return this;
            double c = Math.cos(Math.toRadians(roll)), s = Math.sin(Math.toRadians(roll));
            return new View(eye, fwd, right.scale(c).add(up.scale(s)), up.scale(c).subtract(right.scale(s)), tanX, tanY);
        }

        /** Точка в кадре (доля полукадра {@code safe}: 1 — до края). */
        boolean in(Vec3 at, double safe) {
            Vec3 d = at.subtract(eye);
            double z = d.dot(fwd);
            return z > 0.1 && Math.abs(d.dot(right) / z) <= tanX * safe && Math.abs(d.dot(up) / z) <= tanY * safe;
        }

        /** Доля ширины кадра, которую занимает предмет поперечником {@code size} в точке {@code at}. */
        double share(Vec3 at, double size) {
            double z = at.subtract(eye).dot(fwd);
            return z <= 0.1 ? 0 : size / (2 * z * tanX);
        }
    }

    /**
     * Доля открытых лучей от глаза к предмету поперечником {@code size}: к середине и к краям поперёк взгляда ({@code rays}
     * — 3: середина и бока, 5: ещё верх и низ); последние {@code max(1.5, 0.4·size)} блоков у предмета не в счёт. Лучи —
     * по блокам клиента: где у него нет чанка (за чанками повтора), там воздух, а рельеф дальше рисует Distant Horizons.
     */
    static double open(ClientLevel level, Vec3 eye, Vec3 at, Vec3 right, Vec3 up, double size, int rays) {
        Vec3 r = right.scale(size * 0.35), u = up.scale(size * 0.35);
        Vec3[] pts = rays == 3 ? new Vec3[]{at, at.add(r), at.subtract(r)} : new Vec3[]{at, at.add(r), at.subtract(r), at.add(u), at.subtract(u)};
        double spare = Math.max(1.5, size * 0.4);
        int open = 0;
        for (Vec3 p : pts) {
            Vec3 d = p.subtract(eye);
            double len = d.length();
            if (len <= spare) {
                open++;
                continue;
            }
            var hit = level.clip(new ClipContext(eye, eye.add(d.scale((len - spare) / len)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS) open++;
        }
        return (double) open / pts.length;
    }

    /** Дальше {@link #FOG} прорисовки по горизонтали (туман у ванили — цилиндр вокруг камеры). */
    static boolean beyondFog(Vec3 eye, Vec3 p) {
        return Math.hypot(p.x - eye.x, p.z - eye.z) > FOG * Minecraft.getInstance().gameRenderer.getRenderDistance();
    }

    /**
     * Событие поперечником {@code size} в точке {@code p} видно в кадре {@code v}: в кадре до края, открыты не меньше 0.6
     * лучей к середине и бокам, не сквозь облака низом {@code clouds} и не в тумане у края прорисовки (дальнее — без
     * тумана). Так его считает и вывод, и оценка.
     */
    static boolean keySeen(ClientLevel level, double clouds, boolean far, View v, Vec3 p, double size) {
        return v.in(p, 1) && open(level, v.eye(), p, v.right(), v.up(), size, 3) >= 0.6 && !throughClouds(clouds, v.eye(), p) && (far || !beyondFog(v.eye(), p));
    }

    /** Где событие в тик {@code t}: огненный шар поднимается на 0.4 поперечника за 40 тиков. */
    static Vec3 keyPoint(Subject.Key k, double t) {
        return k.pos().add(0, Math.min(1, (t - k.t()) / 40.0) * k.size() * 0.4, 0);
    }

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

        /** Камера не в блоке и вокруг неё полблока воздуха; чанка нет — не знаем. */
        boolean free(Vec3 p) {
            return loaded(p) && !inBlocks(level, p);
        }

        /** Доля открытых лучей из глаза кадра к предмету ({@link Cameras#open}). */
        double open(View v, Vec3 at, double size, int rays) {
            return Cameras.open(level, v.eye(), at, v.right(), v.up(), size, rays);
        }
    }

    /** Камера в блоке: ближе полублока от неё есть блок (по чанкам клиента: чанка нет — блоков нет). */
    static boolean inBlocks(ClientLevel level, Vec3 p) {
        for (double dx : new double[]{-0.5, 0.5}) {
            for (double dy : new double[]{-0.5, 0.5}) {
                for (double dz : new double[]{-0.5, 0.5}) {
                    BlockPos b = BlockPos.containing(p.x + dx, p.y + dy, p.z + dz);
                    if (level.getChunkSource().getChunkNow(b.getX() >> 4, b.getZ() >> 4) == null) continue;
                    if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) return true;
                }
            }
        }
        return false;
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
    static List<Candidate> of(Plan.Camera c, Plan.Shot shot, double aspect, World w) {
        Subject s = shot.subject();
        int a = shot.a(), b = shot.b();
        List<Candidate> out = new ArrayList<>();
        switch (c.kind()) {
            case "pov" -> out.add(new Candidate("pov-" + c.player().replaceAll("[^A-Za-z0-9_.-]", "_"), null, c.player(), c.gui(), c.fov(), true, false));
            case "fixed" -> {
                Vec3 at = c.aim() != null ? c.aim() : s.anchor(a, b);
                if (c.eye() == null || at == null && !c.follow()) return out;
                // над глазом, где задано: место игрока бывает в доме или метро, а над крышами — горизонт
                for (double rise : c.rise()) {
                    Vec3 eye = settle(lift(c.eye(), rise, w), at, w);
                    Path p = c.follow() ? follow(t -> eye, s, a, b, 3, c.fov()) : t -> Pose.look(eye, at, c.fov());
                    String name = String.format(Locale.ROOT, "fixed-%.0f", c.fov()) + (rise > 0 ? String.format(Locale.ROOT, "-r%.0f", rise) : "");
                    out.add(new Candidate(name, p, null, false, c.fov(), true, false));
                }
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
                            // над облаками событий не видно
                            if (!Double.isNaN(w.clouds) && eye.y >= w.clouds - 2) continue;
                            Path p = c.follow() ? follow(t -> eye, s, a, b, 6, fov) : t -> Pose.look(eye, k.add(0, size * 0.25, 0), fov);
                            out.add(new Candidate(String.format(Locale.ROOT, "tripod-f%.0f-d%.0f-a%.0f-e%.0f", fov, dist, side, elev), p, null,
                                    false, fov, false, false));
                        }
                    }
                }
            }
            case "path" -> {
                // и выше на rise, где задано: путь, проложенный по карте, бывает сквозь высотку
                for (double rise : c.keys().isEmpty() ? new double[0] : c.rise()) {
                    List<Plan.PathKey> keys = c.keys().stream().map(k -> new Plan.PathKey(k.t(), k.eye().add(0, rise, 0), k.aim().add(0, rise, 0), k.roll())).toList();
                    String name = String.format(Locale.ROOT, "path-%.0f", c.fov()) + (rise > 0 ? String.format(Locale.ROOT, "-r%.0f", rise) : "");
                    out.add(new Candidate(name, along(keys, c.fov()), null, false, c.fov(), true, true));
                }
            }
            case "pilot" -> out.addAll(pilot(c, shot, aspect, w));
            default -> throw new IllegalArgumentException("unknown camera " + c.kind());
        }
        return out;
    }

    /** Полётов пилота на план (лучшие разные). */
    static final int PILOT_FLIGHTS = 3;
    /** Рельеф для пилота — квадрат стольких блоков от удара в каждую сторону (проход и лучи низа кадра в нём). */
    static final int PILOT_REACH = 400;

    /**
     * Камера FPV пилота ({@link Pilot}): полёты мимо удара плана по миру повтора у клиента и дыму ударов
     * ({@link Plume}), с руками оператора и толчками взрывов ({@link Hands}); удара в окне нет — кандидатов нет.
     */
    static List<Candidate> pilot(Plan.Camera c, Plan.Shot shot, double aspect, World w) {
        Subject s = shot.subject();
        Subject.Key main = Pilot.mainKey(s, shot.a(), shot.b());
        if (main == null) {
            Film.LOG.warn("Film: {}: pilot: no event in the shot", shot.name());
            return List.of();
        }
        long start = System.nanoTime();
        List<Plume> plumes = new ArrayList<>();
        for (double[] row : shot.smoke()) {
            Plume p = Plume.of(row);
            // удар плана — со своим огненным шаром
            Subject.Key k = s.keys.stream().filter(o -> Math.abs(o.t() - p.t()) <= 2 && o.pos().distanceTo(p.pos()) <= 4).findFirst().orElse(null);
            plumes.add(k == null ? p : new Plume(p.t(), p.pos(), k.size()));
        }
        if (plumes.stream().noneMatch(p -> Math.abs(p.t() - main.t()) <= 2 && p.pos().distanceTo(main.pos()) <= 4))
            plumes.add(new Plume(main.t(), main.pos(), main.size()));
        Pilot.Grid grid = new Pilot.Grid((x, z) -> w.ground(x, z), Mth.floor(main.pos().x), Mth.floor(main.pos().z), PILOT_REACH);
        Pilot pilot = new Pilot(shot.a(), shot.b(), c.fov(), aspect, s, main, plumes, grid, shot.name().hashCode());
        List<Hands.Blast> blasts = plumes.stream().filter(p -> p.t() >= shot.a() - 80 && p.t() <= shot.b())
                .map(p -> new Hands.Blast(p.t(), p.pos(), p.size())).toList();
        List<Candidate> out = new ArrayList<>();
        for (Pilot.Flight f : pilot.plan(PILOT_FLIGHTS)) {
            Film.LOG.info("Film: {}: {}: {}", shot.name(), f.name(), f.why());
            Path p = Hands.humanize(pilot.path(f), shot.name().hashCode() * 31L + f.name().hashCode(), blasts,
                    shot.a() - Pilot.BEFORE, shot.b() + Pilot.AFTER);
            out.add(new Candidate(f.name(), p, null, false, c.fov(), true, false));
        }
        Film.LOG.info("Film: {}: pilot planned {} flights past the hit at tick {} in {} ms", shot.name(), out.size(), main.t(),
                (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    /**
     * Камера летит по ключам плана: глаз и точка взгляда — по прямой между соседними ключами (ровный ход, курс без
     * рывков), до первого ключа — в первом, после последнего — в последнем.
     */
    static Path along(List<Plan.PathKey> keys, double fov) {
        return new Path() {
            @Override
            public Pose at(double t) {
                Plan.PathKey[] k = around(t);
                double u = share(k, t);
                return Pose.look(k[0].eye().lerp(k[1].eye(), u), k[0].aim().lerp(k[1].aim(), u), fov);
            }

            @Override
            public double roll(double t) {
                Plan.PathKey[] k = around(t);
                return Mth.lerp(share(k, t), k[0].roll(), k[1].roll());
            }

            private Plan.PathKey[] around(double t) {
                Plan.PathKey a = keys.get(0), b = a;
                for (Plan.PathKey k : keys) {
                    if (k.t() <= t) a = k;
                    b = k;
                    if (k.t() >= t) break;
                }
                return new Plan.PathKey[]{a, b};
            }

            private double share(Plan.PathKey[] k, double t) {
                return k[1].t() > k[0].t() ? Mth.clamp((t - k[0].t()) / (k[1].t() - k[0].t()), 0, 1) : 0;
            }
        };
    }

    /** Глаз на {@code rise} блоков выше земли под ним (не ниже самого глаза); 0 — сам глаз. */
    static Vec3 lift(Vec3 eye, double rise, World w) {
        if (rise <= 0) return eye;
        double g = w.ground(eye.x, eye.z);
        return new Vec3(eye.x, Math.max(eye.y, Double.isNaN(g) ? eye.y : g) + rise, eye.z);
    }

    /**
     * Ближайшее к глазу место в воздухе ({@link World#free}): глаз игрока стоит у стены или под потолком ближе полублока —
     * вверх до 2 блоков и к цели ({@code toward}; null — только вверх) до 3 по горизонтали; не нашлось — сам глаз (оценка
     * его отвергнет).
     */
    static Vec3 settle(Vec3 eye, @Nullable Vec3 toward, World w) {
        if (w.free(eye)) return eye;
        Vec3 d = toward == null ? Vec3.ZERO : toward.subtract(eye).multiply(1, 0, 1);
        Vec3 dir = d.lengthSqr() < 1e-6 ? Vec3.ZERO : d.normalize();
        for (double up = 0; up <= 2; up += 0.5) {
            for (double fwd = 0; fwd <= 3; fwd += 0.5) {
                Vec3 p = eye.add(dir.scale(fwd)).add(0, up, 0);
                if (w.free(p)) return p;
            }
        }
        return eye;
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
     * Оценка кандидата до вывода теми же лучами и кадром, что у вывода ({@link FrameLog}): до события — доля тиков, когда
     * объект в кадре (с полями {@link #SAFE}), виден (лучи к середине и краям — не меньше 0.4, не сквозь облака и не в
     * тумане), не мельче {@code share} ширины кадра и не в дыму прежних ударов, — за всё время и за последнюю секунду;
     * каждое событие плана — в кадре и видно хотя бы в половине первых 12 тиков ({@link #keySeen}); после событий —
     * объект виден; камера в воздухе по всему пути (путь плана, {@code roam}, — там, где у клиента есть чанк). {@code pass} —
     * эти доли не меньше порогов плана.
     */
    static Score score(Candidate c, Subject s, Plan.Shot shot, double aspect, World w) {
        if (c.path() == null) return Score.DESIGNED;
        Plan.Need need = shot.need();
        int n = 0, ok = 0, nearN = 0, nearOk = 0, afterN = 0, afterOk = 0, samples = 0;
        double sizeSum = 0, smokeHits = 0;
        boolean free = true;
        int first = s.firstKey();
        for (int t = shot.a(); t <= shot.b(); t += 2) {
            Pose p = c.path().at(t);
            if (c.roam() ? w.loaded(p.pos()) && !w.free(p.pos()) : !w.free(p.pos())) free = false;
            Vec3 aim = s.aim(t);
            if (aim == null) continue;
            Subject.Key k = s.keyAt(t);
            boolean before = first < 0 || t < first;
            // первые тики событий — в счёте событий ниже
            if (!before && k != null && t <= k.t() + FrameLog.KEY_CHECK) continue;
            samples++;
            double size = s.aimSize(t);
            View v = View.of(p, aspect).rolled(c.path().roll(t));
            boolean seen = v.in(aim, SAFE) && w.open(v, aim, size, 5) >= 0.4 && !throughClouds(w.clouds, p.pos(), aim)
                    && (shot.far() || !beyondFog(p.pos(), aim));
            boolean smoky = smokeOnLine(p.pos(), aim, shot.smoke(), t);
            if (smoky) smokeHits++;
            if (before) {
                double share = v.share(aim, size);
                boolean good = seen && share >= need.share() && !smoky;
                n++;
                sizeSum += share;
                if (good) ok++;
                if (first >= 0 && t >= first - 20) {
                    nearN++;
                    if (good) nearOk++;
                }
            } else {
                afterN++;
                if (seen) afterOk++;
            }
        }
        // события плана, как их считает вывод: каждое — в кадре и видно хотя бы в половине своих первых тиков
        int keysIn = 0, keysOk = 0;
        for (Subject.Key k : s.keys) {
            if (k.t() < shot.a() || k.t() > shot.b()) continue;
            keysIn++;
            int seen = 0;
            for (int t = k.t(); t <= k.t() + FrameLog.KEY_CHECK; t++) {
                if (keySeen(w.level, w.clouds, shot.far(), View.of(c.path().at(t), aspect).rolled(c.path().roll(t)), keyPoint(k, t), k.size())) seen++;
            }
            if (seen * 2 >= FrameLog.KEY_CHECK + 1) keysOk++;
        }
        double approach = n == 0 ? 1 : (double) ok / n, near = nearN == 0 ? 1 : (double) nearOk / nearN;
        double keys = keysIn == 0 ? 1 : (double) keysOk / keysIn, after = afterN == 0 ? 1 : (double) afterOk / afterN;
        double meanShare = n == 0 ? 0 : sizeSum / n, smoke = smokeHits / Math.max(1, samples);
        boolean pass = free && approach >= need.subject() && near >= need.near() && keys >= need.keys();
        String why = (free ? "" : "camera inside blocks; ")
                + (keys < need.keys() ? String.format(Locale.ROOT, "events seen: %d of %d; ", keysOk, keysIn) : "")
                + (approach < need.subject() ? String.format(Locale.ROOT, "subject shown in %.0f%% before the event; ", 100 * approach) : "")
                + (near < need.near() ? String.format(Locale.ROOT, "in the last second before the event: %.0f%%; ", 100 * near) : "");
        double total = !free ? -1 : approach * 0.45 + keys * 0.35 + after * 0.1 + Math.min(1, meanShare / 0.06) * 0.1 - smoke * 0.3;
        return new Score(total, approach, near, keys, after, meanShare, smoke, pass, why.trim());
    }

    /**
     * Дым ударов плана ({@code [t, x, y, z]} и поперечник шара, где известен) на линии взгляда — по модели дыма удара
     * ({@link Plume}): столб удара, который ещё дымит, и не свой дым цели.
     */
    static boolean smokeOnLine(Vec3 from, Vec3 to, List<double[]> smoke, int t) {
        for (double[] h : smoke) {
            if (h[0] <= t && Plume.of(h).blocks(from, to, t)) return true;
        }
        return false;
    }
}
