package ua.zentix.airstrike.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.client.ClientWeaponSpec;
import ua.zentix.airstrike.client.ClientWeaponSpec.At;
import ua.zentix.airstrike.client.ClientWeaponSpec.ClientAirframe;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarLook;
import ua.zentix.airstrike.client.ClientWeaponSpec.Flame;
import ua.zentix.airstrike.client.ClientWeaponSpec.Trail;
import ua.zentix.airstrike.client.flight.FlightTrack;
import ua.zentix.airstrike.client.flight.FlightTracks;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.render.FarModels;
import ua.zentix.airstrike.client.render.ProjectilePose;
import ua.zentix.airstrike.client.render.WeaponModels;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Шлейфы всех снарядов и снаряды вдали, которых у клиента нет (сущность дальше прорисовки или летит вне мира): путь —
 * по сущности или по пакетам сервера ({@link FlightTracks}), вид — из клиентского паспорта ({@link FarLook}).
 * <ul>
 * <li>Корпус — настоящая модель ({@link FarModels}: та же, что у сущности вблизи, с теми же анимациями, по позе из пути),
 * пока она на экране длиннее {@link FarModels#MODEL_FROM} пикселей; мельче — круг той же площади, что силуэт, настоящего
 * углового размера, а мельче пятна {@link Sight#MIN_PIXELS} — пятно, бледнее во столько же раз ({@link Sight#body}):
 * закрыто то же небо; между — плавный переход. Свой цвет при свете неба, дымка — в непрозрачности ({@link Sight}).</li>
 * <li>Факел — свет ({@link Sight#light}) позади корпуса: днём — искра, ночью глаз привык к темноте — блик и вуаль на
 * километры. Маршевый турбовентиляторный двигатель ракеты и мотор шахеда ночью не светят — у них факела почти нет.</li>
 * <li>Шлейф — лента через точки пути от сопел ({@link TrailPoints}), одна вблизи и вдали, от пуска до конца следа (там
 * сходит на нет): расплывается, сносится ветром мода, поднимается и тает своим сроком, поэтому висит и после того, как снаряд пролетел или
 * взорвался. Мест частиц она не занимает: залп в сотни ракет не рвёт следы. Свет — карта освещения мира в точке
 * (вне загруженного мира — открытое небо). Вблизи поверх густых шлейфов — редкие клубы {@code Exhaust} для объёма.</li>
 * <li>За рельефом дальше прорисовки (ближе закрывает глубина) — луч {@link Sightline} раз в {@link #SIGHT_PERIOD}
 * тика: корпус и факел плавно гаснут.</li>
 * </ul>
 * Кадр — O(снарядов + точек шлейфов), без выделений.
 */
public final class FarFlightView {
    /** Луч по рельефу у каждого снаряда — раз в столько тиков. */
    static final int SIGHT_PERIOD = 4;
    /** Рельеф закрывает источник: видимое с глаза начинается выше него на столько блоков. */
    static final double HIDDEN = 0.5;
    /** Видимость за рельефом меняется за тик не больше, чем на столько. */
    private static final float FADE = 0.25f;
    /**
     * Тон ленты против её цвета: клуб дыма вблизи ({@code fx/particle/smoke_*}) светит в среднем 0,76 своего цвета —
     * в картинке тень внутри клуба, — а середина ореола ленты — 1. С тем же тоном клубы объёма лежат на ленте как её
     * неровности, а не тёмными бусинами ({@code FarFlightViewTest}).
     */
    static final float TONE = 0.76f;
    /** Сопел со шлейфом у сущности полёта, не больше (у B-2 — четыре). */
    static final int MAX_NOZZLES = 4;
    /** Места концов лент у сопел в кэше кадра (после мест точек). */
    private static final int HEAD = TrailPoints.CAPACITY;
    /** Свет точки вне загруженного мира — открытое небо. */
    private static final Light SKY = (x, y, z) -> LightTexture.FULL_SKY;

    private static final List<Far> FARS = new ArrayList<>();
    private static final Map<FlightTrack, Far> BY_TRACK = new IdentityHashMap<>();
    private static final TrailPoints POINTS = new TrailPoints();
    private static int owners;
    /** Тик обхода путей (чей путь не встретился — забыт). */
    private static long pass;
    /** Снарядов, которые рисуются вдали (путь по пакетам, полёт не кончился). */
    private static int active;
    @Nullable
    private static ClientLevel level;
    @Nullable
    private static Sightline.Heights heights;

    // ---------------------------------------------------------------- кадр: числа живут между кадрами
    private static final double[] POS = new double[3], NOZZLE = new double[3], BACK = new double[3], OUT = new double[2], LIGHT = new double[5],
            AIM = new double[3];
    private static final float[] ANGLES = new float[3];
    private static final ProjectilePose POSE = new ProjectilePose();
    private static final BlockPos.MutableBlockPos PROBE = new BlockPos.MutableBlockPos();
    /**
     * Концы лент в кадре: где (от камеры), полуширина, непрозрачность, цвет, свет мира; поперёк ленты (общий у стыка
     * отрезков); номера кадра, в котором посчитаны конец и поперечник.
     */
    private static final int ENDS = HEAD + MAX_NOZZLES;
    private static final double[] EX = new double[ENDS], EY = new double[ENDS], EZ = new double[ENDS], EH = new double[ENDS];
    private static final float[] EA = new float[ENDS], ER = new float[ENDS], EG = new float[ENDS], EB = new float[ENDS];
    private static final float[] NX = new float[ENDS], NY = new float[ENDS], NZ = new float[ENDS];
    private static final int[] EL = new int[ENDS], DRAWN = new int[ENDS], ACROSS = new int[ENDS];
    private static final float[] JOINT = new float[3];
    private static int frame;

    // ---------------------------------------------------------------- сводка последнего кадра (describe)
    /** Место B-2 в счёте по оружию (у его бомбы — своё оружие). */
    private static final int B2 = WeaponType.values().length;
    private static final int[] COUNT = new int[B2 + 1];
    private static int flights, points;
    @Nullable
    private static Far nearest;
    private static double nearD, nearT, nearBody, nearBodyAlpha, nearFlame, nearFlameAlpha, nearModel;
    private static boolean nearCoarse;

    private FarFlightView() {}

    /** Свет мира в точке, упакованный ({@code LevelRenderer#getLightColor}). */
    @FunctionalInterface
    interface Light {
        int at(double x, double y, double z);
    }

    /** Один путь снаряда: точки его шлейфов, видимость за рельефом. */
    static final class Far {
        final FlightTrack track;
        final ClientAirframe airframe;
        final FarLook look;
        final WeaponModels.Look model;
        /** Чьи точки в {@link TrailPoints}. */
        final int owner;
        long seen;
        /** До какого тика пути точки уже поставлены. */
        long cursor = -1;
        /** Нынешние ленты начаты (у каждого сопла — своя); их последние точки, тик последних точек и вид. */
        boolean started;
        final long[] chain = new long[MAX_NOZZLES];
        long chainTick;
        @Nullable
        Trail style;
        /** Сопла нынешних лент; от ускорителя ли они. */
        List<At> nozzles = List.of();
        boolean booster;
        /** Не закрыт рельефом: 1 — виден; прошлый тик, нынешний и куда идёт. */
        float visPrev = 1, vis = 1, visTarget = 1;
        boolean sighted;
        /** Модель — упрощёнными копиями деталей (мелкая на экране; с запасом против дрожания на границе). */
        boolean coarse = true;

        Far(FlightTrack track, int owner) {
            this.track = track;
            this.owner = owner;
            this.airframe = ClientWeaponSpec.of(track.weapon).airframe(!track.bomber);
            this.look = airframe.far();
            this.model = airframe.model();
            Arrays.fill(chain, TrailPoints.NONE);
        }

        /** Ленты кончились: следующая точка начнёт новые. */
        void restart() {
            started = false;
            Arrays.fill(chain, TrailPoints.NONE);
        }
    }

    /** Раз в тик клиента после {@code FlightTracks.tick}: новые пути, точки шлейфов, лучи по рельефу, забытые пути. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        if (lv == null) {
            reset();
            return;
        }
        if (lv != level) {
            reset();
            level = lv;
            heights = FarTerrain.of(lv);
        }
        long now = FlightTracks.now();
        pass++;
        for (FlightTrack track : FlightTracks.all()) {
            Far f = BY_TRACK.get(track);
            if (f == null) {
                f = new Far(track, ++owners);
                BY_TRACK.put(track, f);
                FARS.add(f);
            }
            f.seen = pass;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double world = mc.options.getEffectiveRenderDistance() * 16.0;
        // мир заморожен: снаряды стоят, шлейфы не растут и не стареют (точки того же места не нужны)
        boolean frozen = frozen();
        active = 0;
        for (int i = FARS.size() - 1; i >= 0; i--) {
            Far f = FARS.get(i);
            if (f.seen != pass) {
                // путь забыт: после взрыва шлейф тает сам, после отбоя (полёт кончился без взрыва) пропадает сразу
                if (!f.track.isDead()) POINTS.kill(f.owner);
                BY_TRACK.remove(f.track);
                FARS.set(i, FARS.getLast());
                FARS.removeLast();
                continue;
            }
            if (frozen) {
                f.cursor = Math.max(f.cursor, Math.min(f.track.lastTick(), now - 1));
            } else {
                sample(f, Math.min(f.track.lastTick(), now - 1), POINTS, FarFlightView::light);
                close(f, now);
            }
            sight(f, now, eye, world);
            if (f.track.fromServer() && !f.track.isDead()) active++;
        }
        if (frozen) POINTS.hold();
        else POINTS.expire(now - 1);
    }

    /** Мир заморожен ({@code /tick freeze}): сущности и частицы стоят. */
    private static boolean frozen() {
        ClientLevel lv = level;
        return lv != null && !lv.tickRateManager().runsNormally();
    }

    /** Точки шлейфов без света мира (юнит-тесты): открытое небо. */
    static void sample(Far f, long horizon, TrailPoints points) {
        sample(f, horizon, points, SKY);
    }

    /**
     * Точки шлейфов по пути до тика horizon включительно (дальше путь ещё может поправить пакет этого тика), по сущности
     * и по пакетам сервера одинаково: у каждого сопла своей фазы ({@link ClientAirframe#trailNozzles}) — раз в шаг вида
     * шлейфа. Вид или сопла сменились (ускоритель догорел) — ленты кончаются точками в тике смены, новые начинаются там
     * же; разрыв в пути (история начата заново) — ленты рвутся.
     */
    static void sample(Far f, long horizon, TrailPoints points, Light light) {
        FlightTrack track = f.track;
        long t = f.cursor + 1, start = (long) Math.ceil(track.start());
        if (start > t) {
            t = start;
            f.restart();
        }
        for (; t <= horizon; t++) {
            FlightPhase phase = FlightPhase.byId(track.phase(t));
            Trail style = f.look.stage(phase).trail();
            boolean booster = phase.boosterLit() && f.airframe.boosterNozzle() != null;
            if (style != f.style || style != null && booster != f.booster) {
                if (f.style != null && f.started) {
                    if (t > f.chainTick) put(f, points, t, f.style, light);
                    finish(f, points);
                }
                f.style = style;
                f.booster = booster;
                f.nozzles = style == null ? List.of() : f.airframe.trailNozzles(phase.boosterLit());
                f.restart();
            }
            if (style != null && (!f.started || t - f.chainTick >= style.step())) put(f, points, t, style, light);
        }
        f.cursor = Math.max(f.cursor, horizon);
    }

    /**
     * Путь кончился (снаряд взорвался или ушёл из вида) — ленты доходят до последней его точки и там сходят на нет:
     * иначе конец следа отскакивал назад на шаг точек (у B-2 — на 48 блоков). Путь продолжится — продолжатся и ленты.
     */
    private static void close(Far f, long now) {
        FlightTrack track = f.track;
        long last = track.lastTick();
        if (!f.started || f.style == null || track.covers(now - 1) || last > f.cursor) return;
        if (last > f.chainTick) put(f, POINTS, last, f.style, FarFlightView::light);
        finish(f, POINTS);
    }

    /** Нынешние ленты кончаются своими последними точками. */
    private static void finish(Far f, TrailPoints points) {
        for (long c : f.chain) points.finish(c);
    }

    /** Точки лент у всех сопел в тике t. */
    private static void put(Far f, TrailPoints points, long t, Trail style, Light light) {
        for (int k = 0, n = Math.min(MAX_NOZZLES, f.nozzles.size()); k < n; k++) {
            f.track.at(t, POS);
            f.track.offset(t, f.nozzles.get(k), POS);
            f.chain[k] = points.add(POS[0], POS[1], POS[2], t, style, light.at(POS[0], POS[1], POS[2]), f.owner, f.chain[k]);
        }
        f.chainTick = t;
        f.started = true;
    }

    /**
     * Свет мира в точке: чанк есть у клиента — как у частицы, нет — открытое небо. Точка у сопла на старте бывает
     * в верхнем блоке земли (сопло МБР на пусковой — на блок ниже её верха): внутри твёрдого блока света нет, и низ
     * столба был бы почти чёрным — свет берётся над ним.
     */
    private static int light(double x, double y, double z) {
        ClientLevel lv = level;
        if (lv == null) return LightTexture.FULL_SKY;
        PROBE.set(x, y, z);
        if (!lv.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(PROBE.getX()), SectionPos.blockToSectionCoord(PROBE.getZ()))) {
            return LightTexture.FULL_SKY;
        }
        for (int k = 0; k < 2 && lv.getBlockState(PROBE).isSolidRender(lv, PROBE); k++) PROBE.move(Direction.UP);
        return LevelRenderer.getLightColor(lv, PROBE);
    }

    /** Закрыт ли снаряд рельефом: луч раз в {@link #SIGHT_PERIOD} тика, только дальше прорисовки; видимость — плавно. */
    private static void sight(Far f, long now, Vec3 eye, double world) {
        f.visPrev = f.vis;
        FlightTrack track = f.track;
        if (!track.fromServer() || track.isDead()) return;
        if (f.sighted && (now + f.owner) % SIGHT_PERIOD != 0 || !track.predict(now - 1, POS)) {
            f.vis += Mth.clamp(f.visTarget - f.vis, -FADE, FADE);
            return;
        }
        double dx = POS[0] - eye.x, dz = POS[2] - eye.z;
        boolean hidden = heights != null && dx * dx + dz * dz > world * world
                && Sightline.trace(heights, POS[0], POS[1], POS[2], eye.x, eye.y, eye.z).hidden() > HIDDEN;
        f.visTarget = hidden ? 0 : 1;
        if (!f.sighted) f.vis = f.visPrev = f.visTarget;
        f.sighted = true;
        f.vis += Mth.clamp(f.visTarget - f.vis, -FADE, FADE);
    }

    /** Шлейфы, корпуса и факелы в кадр. */
    static void collect(FarView view, FarSprites out) {
        frame++;
        flights = 0;
        points = 0;
        nearest = null;
        Arrays.fill(COUNT, 0);
        double t = FlightTracks.now() - 1 + (frozen() ? 0 : view.partial());
        Vec3 cam = view.camera();
        for (long s = POINTS.first(), end = POINTS.end(); s < end; s++) {
            int i = TrailPoints.index(s);
            Trail style = POINTS.style[i];
            if (style == null) continue;
            double age = Math.max(0, t - POINTS.birth[i]);
            if (style.expired(age)) continue;
            double drift = style.drift(age);
            end(style, age, POINTS.x[i] + Fx.WIND_X * drift - cam.x, POINTS.y[i] + style.lift(age) - cam.y, POINTS.z[i] + Fx.WIND_Z * drift - cam.z,
                    POINTS.light[i], view, i);
            if (POINTS.last[i]) EA[i] = 0;
            points++;
            long p = POINTS.prev[i];
            if (POINTS.holds(p) && DRAWN[TrailPoints.index(p)] == frame) segment(out, TrailPoints.index(p), i);
        }
        for (int k = 0, n = FARS.size(); k < n; k++) {
            Far f = FARS.get(k);
            FlightTrack track = f.track;
            if (track.isDead() && t >= track.deathTick() || track.drilling() || !track.predict(t, POS)) continue;
            ClientWeaponSpec.Stage stage = f.look.stage(FlightPhase.byId(track.phase(t)));
            Trail trail = stage.trail();
            if (trail != null && trail == f.style && f.started) {
                // ленты доходят до сопел: там они ещё не проявились
                for (int j = 0, m = Math.min(MAX_NOZZLES, f.nozzles.size()); j < m; j++) {
                    long c = f.chain[j];
                    if (!POINTS.holds(c) || DRAWN[TrailPoints.index(c)] != frame || POINTS.last[TrailPoints.index(c)]) continue;
                    System.arraycopy(POS, 0, NOZZLE, 0, 3);
                    track.offset(t, f.nozzles.get(j), NOZZLE);
                    end(trail, 0, NOZZLE[0] - cam.x, NOZZLE[1] - cam.y, NOZZLE[2] - cam.z, POINTS.light[TrailPoints.index(c)], view, HEAD + j);
                    segment(out, TrailPoints.index(c), HEAD + j);
                }
            }
            // корпус и факел вблизи рисуют модель и PlumeRenderer
            if (!track.fromServer()) continue;
            double dx = POS[0] - cam.x, dy = POS[1] - cam.y, dz = POS[2] - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double tr = Sight.transmittance(d, view.range());
            float vis = Mth.lerp(view.partial(), f.visPrev, f.vis);
            double length = f.look.size() / (d * view.pixel());
            double model = modelShare(length);
            if (model > 0 && vis > 0 && inFrame(view, dx, dy, dz, d, FarModels.TILE_RADIUS * f.look.size())) {
                f.coarse = f.coarse ? length < FarModels.FULL_ABOVE : length < FarModels.COARSE_BELOW;
                pose(track, t, f.coarse);
                if (!out.model(f.model, POSE, dx, dy, dz, f.look.size(), view.pixel(), view.up(), (float) (model * vis * tr))) model = 0;
            } else {
                model = 0;
            }
            body(f.look.area(), d, view.pixel(), OUT);
            int c = f.look.color();
            float a = (float) (OUT[1] * vis * tr * (1 - model)), lit = view.ambient();
            out.disc(dx, dy, dz, OUT[0], channel(c, 0) * lit, channel(c, 1) * lit, channel(c, 2) * lit, a);
            double bodyPx = 2 * OUT[0] / (d * view.pixel());
            double flamePx = 0, flameAlpha = 0;
            Flame flame = stage.flame();
            if (flame != null && vis > 0) {
                flameAt(track, t, flame, dx, dy, dz, OUT);
                double bd = OUT[0], ft = Sight.transmittance(bd, view.range());
                int fc = flame.color();
                out.light(BACK[0], BACK[1], BACK[2], flame.radius(), Sight.adapted(flame.brightness(), view.ambient()) * ft, ft, view.pixel(),
                        channel(fc, 0), channel(fc, 1), channel(fc, 2), vis, LIGHT);
                flamePx = 2 * LIGHT[0] / (bd * view.pixel());
                flameAlpha = Math.min(1, LIGHT[2]) * vis;
            }
            flights++;
            COUNT[track.bomber ? B2 : track.weapon.ordinal()]++;
            if (nearest == null || d < nearD) {
                nearest = f;
                nearD = d;
                nearT = tr;
                nearBody = bodyPx;
                nearBodyAlpha = a;
                nearFlame = flamePx;
                nearFlameAlpha = flameAlpha;
                nearModel = model > 0 ? length : 0;
                nearCoarse = f.coarse;
            }
        }
    }

    /** Поза модели в момент t по пути (место — уже в {@link #POS}). */
    private static void pose(FlightTrack track, double t, boolean coarse) {
        ProjectilePose p = POSE;
        p.x = POS[0];
        p.y = POS[1];
        p.z = POS[2];
        track.angles(t, ANGLES);
        p.yaw = ANGLES[0];
        p.pitch = ANGLES[1];
        p.roll = ANGLES[2];
        p.phase = FlightPhase.byId(track.phase(t));
        p.phaseAge = (float) track.phaseAge(t);
        p.age = (float) track.age(t);
        track.aim(t, AIM);
        p.aimX = AIM[0];
        p.aimY = AIM[1];
        p.aimZ = AIM[2];
        p.coarse = coarse;
    }

    /** Шар радиуса r с центром (dx, dy, dz) на дальности d хоть краем в кадре (конус до угла экрана). */
    static boolean inFrame(FarView view, double dx, double dy, double dz, double d, double r) {
        double angle = view.edge() + Math.asin(Math.min(1, r / d));
        if (angle >= Math.PI) return true;
        Vector3f f = view.forward();
        return (dx * f.x() + dy * f.y() + dz * f.z()) / d >= Math.cos(angle);
    }

    /** Середина факела — позади корпуса по ходу ({@link #BACK}, от камеры); out[0] — её дальность. */
    private static void flameAt(FlightTrack track, double t, Flame flame, double dx, double dy, double dz, double[] out) {
        // ход — от точки тиком раньше к нынешней (за последним пакетом — по прямой к предсказанной)
        double px = POS[0], py = POS[1], pz = POS[2];
        track.at(t - 1, BACK);
        double mx = px - BACK[0], my = py - BACK[1], mz = pz - BACK[2];
        double len = Math.sqrt(mx * mx + my * my + mz * mz), k = len > 1e-6 ? flame.back() / len : 0;
        BACK[0] = dx - mx * k;
        BACK[1] = dy - my * k;
        BACK[2] = dz - mz * k;
        out[0] = Math.max(1e-3, Math.sqrt(BACK[0] * BACK[0] + BACK[1] * BACK[1] + BACK[2] * BACK[2]));
    }

    /**
     * Конец ленты в кэш кадра под номером slot: где (от камеры), полуширина с полом в пиксель, непрозрачность с дымкой,
     * цвет, свет мира.
     */
    private static void end(Trail s, double age, double dx, double dy, double dz, int light, FarView view, int slot) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double t = Sight.transmittance(d, view.range());
        ribbon(s.width(age), d, view.pixel(), OUT);
        float shade = s.shade(age);
        EX[slot] = dx;
        EY[slot] = dy;
        EZ[slot] = dz;
        EH[slot] = OUT[0];
        EA[slot] = (float) (s.opacity(age) * OUT[1] * t);
        ER[slot] = Mth.lerp(shade, channel(s.color0(), 0), channel(s.color1(), 0)) * TONE;
        EG[slot] = Mth.lerp(shade, channel(s.color0(), 1), channel(s.color1(), 1)) * TONE;
        EB[slot] = Mth.lerp(shade, channel(s.color0(), 2), channel(s.color1(), 2)) * TONE;
        EL[slot] = light;
        DRAWN[slot] = frame;
    }

    /**
     * Отрезок ленты между концами a и b из кэша кадра; цвет — середина между их цветами. Поперёк у конца a — тот же, что
     * у прошлого отрезка этой ленты (стык без щели на изгибе), у b — запоминается для следующего.
     */
    private static void segment(FarSprites out, int a, int b) {
        JOINT[0] = ACROSS[a] == frame ? NX[a] : Float.NaN;
        JOINT[1] = NY[a];
        JOINT[2] = NZ[a];
        if (!out.ribbon(EX[a], EY[a], EZ[a], EH[a], EA[a], EL[a], EX[b], EY[b], EZ[b], EH[b], EA[b], EL[b],
                (ER[a] + ER[b]) * 0.5f, (EG[a] + EG[b]) * 0.5f, (EB[a] + EB[b]) * 0.5f, JOINT)) return;
        NX[b] = JOINT[0];
        NY[b] = JOINT[1];
        NZ[b] = JOINT[2];
        ACROSS[b] = frame;
    }

    // ---------------------------------------------------------------- чистая геометрия видимости (юнит-тесты)

    /**
     * Доля модели в виде корпуса при длине length пикселей: до {@link FarModels#DOT_BELOW} — 0 (точка), от
     * {@link FarModels#MODEL_FROM} — 1 (модель), между — плавно; остальное — точка.
     */
    static double modelShare(double length) {
        double k = (length - FarModels.DOT_BELOW) / (FarModels.MODEL_FROM - FarModels.DOT_BELOW);
        k = Math.max(0, Math.min(1, k));
        return k * k * (3 - 2 * k);
    }

    /**
     * Корпус на дальности d: радиус круга и его непрозрачность ({@link Sight#body}) — круг той же площади, что средний
     * силуэт {@code area}.
     */
    static void body(double area, double d, double pixel, double[] out) {
        Sight.body(Math.sqrt(area / Math.PI), d, pixel, out);
    }

    /**
     * Лента шириной width на дальности d: полуширина для {@link FarSprites#ribbon} и доля непрозрачности. Уже пикселя
     * она не пропадает, а рисуется в полтора пикселя, бледнее во столько же раз: сколько неба закрывает след, столько
     * и лента.
     */
    static void ribbon(double width, double d, double pixel, double[] out) {
        double half = width / 2, floor = 0.5 * Sight.MIN_PIXELS * pixel * d;
        out[0] = Math.max(half, floor) * FarSprites.RIBBON;
        out[1] = half >= floor ? 1 : half / floor;
    }

    private static float channel(int rgb, int channel) {
        return ((rgb >> (16 - 8 * channel)) & 255) / 255f;
    }

    // ---------------------------------------------------------------- состояние

    static boolean isEmpty() {
        return active == 0 && POINTS.isEmpty();
    }

    /**
     * Сводка для лога сценария (раз в секунду): сколько снарядов рисуется вдали и каких, у ближнего — дальность,
     * прозрачность воздуха, корпус (точка) и факел в пикселях с непрозрачностью, модель — её длина в пикселях и копия,
     * закрыт ли рельефом; точек шлейфов в кадре.
     * Пусто — вдали ничего нет.
     */
    public static String describe() {
        if (isEmpty() || flights == 0 && points == 0) return "";
        StringBuilder sb = new StringBuilder("снарядов ").append(flights);
        if (flights > 0) {
            sb.append(" (");
            String sep = "";
            for (int i = 0; i < COUNT.length; i++) {
                if (COUNT[i] == 0) continue;
                sb.append(sep).append(i == B2 ? "b2" : WeaponType.byId(i).getSerializedName()).append(' ').append(COUNT[i]);
                sep = ", ";
            }
            sb.append(')');
        }
        Far f = nearest;
        if (f != null) {
            sb.append(String.format(Locale.ROOT, "; ближний %s: %.0f бл, t %.2f, корпус %.1f px α %.2f",
                    f.track.bomber ? "b2" : f.track.weapon.getSerializedName(), nearD, nearT, nearBody, nearBodyAlpha));
            if (nearModel > 0) sb.append(String.format(Locale.ROOT, ", модель %.1f px (%s)", nearModel, nearCoarse ? "упрощённая" : "полная"));
            if (nearFlame > 0) sb.append(String.format(Locale.ROOT, ", факел %.1f px α %.2f", nearFlame, nearFlameAlpha));
            if (f.visTarget < 1) sb.append(", за рельефом");
        }
        return sb.append("; шлейф ").append(points).append(" т").toString();
    }

    /** Выход из мира или смена измерения. */
    static void reset() {
        FARS.clear();
        BY_TRACK.clear();
        POINTS.clear();
        active = 0;
        flights = 0;
        points = 0;
        nearest = null;
        level = null;
        heights = null;
    }
}
