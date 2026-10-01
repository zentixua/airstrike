package ua.zentix.airstrike.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.client.ClientWeaponSpec;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarLook;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarTrail;
import ua.zentix.airstrike.client.ClientWeaponSpec.Flame;
import ua.zentix.airstrike.client.flight.FlightTrack;
import ua.zentix.airstrike.client.flight.FlightTracks;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Снаряды вдали, которых у клиента нет (сущность дальше прорисовки или летит вне мира): путь — по пакетам сервера
 * ({@link FlightTracks}), вид — из клиентского паспорта ({@link FarLook}).
 * <ul>
 * <li>Корпус — мягкая тёмная точка не меньше своего размера; мельче пикселя — точка в полтора пикселя, бледнее во
 * столько же раз: сколько неба закрывает корпус, столько и точка. Цвет — смешан с дымкой по Кошмидеру.</li>
 * <li>Факел — свет ({@link Sight#point}) позади корпуса: днём — искра, ночью глаз привык к темноте — блик на километры.</li>
 * <li>Шлейф — лента через точки пути, записанные по пакетам ({@link TrailPoints}): расплывается, сносится ветром мода
 * и тает своим сроком, поэтому висит и после того, как снаряд пролетел или взорвался. У сущности вблизи шлейф —
 * частицы {@code Exhaust}, по её тикам точек нет.</li>
 * <li>За рельефом дальше прорисовки (ближе закрывает глубина) — луч {@link Sightline} раз в {@link #SIGHT_PERIOD}
 * тика: корпус и факел плавно гаснут.</li>
 * </ul>
 * Кадр — O(снарядов + точек шлейфов), без выделений.
 */
public final class FarFlightView {
    /**
     * Во сколько раз полуразмер мягкой точки ({@code nuke/flare.png}: ядро и ореол) больше радиуса круга, который она
     * заменяет: текстура покрывает 0,084 своего квадрата, круг — π/4 описанного.
     */
    static final double SOFT_DOT = 3.05;
    /** То же для ленты: поперёк неё середина той же текстуры покрывает 0,254 ширины. */
    static final double SOFT_RIBBON = 3.94;
    /** Луч по рельефу у каждого снаряда — раз в столько тиков. */
    static final int SIGHT_PERIOD = 4;
    /** Рельеф закрывает источник: видимое с глаза начинается выше него на столько блоков. */
    static final double HIDDEN = 0.5;
    /** Видимость за рельефом меняется за тик не больше, чем на столько. */
    private static final float FADE = 0.25f;
    /** Место конца ленты у корпуса в кэше кадра (после мест точек). */
    private static final int HEAD = TrailPoints.CAPACITY;

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
    private static final double[] POS = new double[3], BACK = new double[3], OUT = new double[2];
    /** Концы лент в кадре: где (от камеры), полуширина, непрозрачность, цвет; номер кадра, в котором посчитан. */
    private static final double[] EX = new double[HEAD + 1], EY = new double[HEAD + 1], EZ = new double[HEAD + 1], EH = new double[HEAD + 1];
    private static final float[] EA = new float[HEAD + 1], ER = new float[HEAD + 1], EG = new float[HEAD + 1], EB = new float[HEAD + 1];
    private static final int[] DRAWN = new int[HEAD + 1];
    private static int frame;

    // ---------------------------------------------------------------- сводка последнего кадра (describe)
    /** Место B-2 в счёте по оружию (у его бомбы — своё оружие). */
    private static final int B2 = WeaponType.values().length;
    private static final int[] COUNT = new int[B2 + 1];
    private static int flights, points;
    @Nullable
    private static Far nearest;
    private static double nearD, nearT, nearBody, nearBodyAlpha, nearFlame, nearFlameAlpha;

    private FarFlightView() {}

    /** Один путь снаряда: точки его шлейфа, видимость за рельефом. */
    static final class Far {
        final FlightTrack track;
        final FarLook look;
        /** Чьи точки в {@link TrailPoints}. */
        final int owner;
        long seen;
        /** До какого тика пути точки уже поставлены. */
        long cursor = -1;
        /** Последняя точка нынешней ленты или {@link TrailPoints#NONE}; её тик и вид. */
        long chain = TrailPoints.NONE;
        long chainTick;
        @Nullable
        FarTrail style;
        /** Не закрыт рельефом: 1 — виден; прошлый тик, нынешний и куда идёт. */
        float visPrev = 1, vis = 1, visTarget = 1;
        boolean sighted;

        Far(FlightTrack track, int owner) {
            this.track = track;
            this.owner = owner;
            this.look = ClientWeaponSpec.of(track.weapon).airframe(!track.bomber).far();
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
            sample(f, Math.min(f.track.lastTick(), now - 1), POINTS);
            sight(f, now, eye, world);
            if (f.track.fromServer() && !f.track.isDead()) active++;
        }
        POINTS.expire(now - 1);
    }

    /**
     * Точки шлейфа по пути до тика horizon включительно (дальше путь ещё может поправить пакет этого тика): только тики,
     * записанные по пакетам сервера, раз в шаг вида шлейфа своей фазы. Вид сменился (ускоритель догорел, сущность
     * появилась у клиента) — лента этого вида кончается точкой в тике смены, новая начинается там же; разрыв в пути
     * (история начата заново) — лента рвётся.
     */
    static void sample(Far f, long horizon, TrailPoints points) {
        FlightTrack track = f.track;
        long t = f.cursor + 1, start = (long) Math.ceil(track.start());
        if (start > t) {
            t = start;
            f.chain = TrailPoints.NONE;
        }
        for (; t <= horizon; t++) {
            FarTrail style = track.fromServer(t) ? f.look.stage(FlightPhase.byId(track.phase(t))).trail() : null;
            if (style != f.style) {
                if (f.style != null && f.chain != TrailPoints.NONE && t > f.chainTick) put(f, points, t, f.style);
                f.style = style;
                f.chain = TrailPoints.NONE;
            }
            if (style != null && (f.chain == TrailPoints.NONE || t - f.chainTick >= style.step())) put(f, points, t, style);
        }
        f.cursor = Math.max(f.cursor, horizon);
    }

    private static void put(Far f, TrailPoints points, long t, FarTrail style) {
        f.track.at(t, POS);
        f.chain = points.add(POS[0], POS[1], POS[2], t, style, f.owner, f.chain);
        f.chainTick = t;
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
        double t = FlightTracks.now() - 1 + view.partial();
        Vec3 cam = view.camera();
        for (long s = POINTS.first(), end = POINTS.end(); s < end; s++) {
            int i = TrailPoints.index(s);
            FarTrail style = POINTS.style[i];
            if (style == null) continue;
            double age = Math.max(0, t - POINTS.birth[i]);
            if (style.expired(age)) continue;
            double drift = style.drift(age);
            end(style, age, POINTS.x[i] + Fx.WIND_X * drift - cam.x, POINTS.y[i] - cam.y, POINTS.z[i] + Fx.WIND_Z * drift - cam.z, view, i);
            points++;
            long p = POINTS.prev[i];
            if (POINTS.holds(p) && DRAWN[TrailPoints.index(p)] == frame) segment(out, TrailPoints.index(p), i);
        }
        for (int k = 0, n = FARS.size(); k < n; k++) {
            Far f = FARS.get(k);
            FlightTrack track = f.track;
            if (!track.fromServer() || track.isDead() && t >= track.deathTick() || track.drilling() || !track.predict(t, POS)) continue;
            double dx = POS[0] - cam.x, dy = POS[1] - cam.y, dz = POS[2] - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            ClientWeaponSpec.Stage stage = f.look.stage(FlightPhase.byId(track.phase(t)));
            FarTrail trail = stage.trail();
            if (trail != null && trail == f.style && POINTS.holds(f.chain) && DRAWN[TrailPoints.index(f.chain)] == frame) {
                // лента доходит до корпуса: у сопла она ещё не проявилась
                end(trail, 0, dx, dy, dz, view, HEAD);
                segment(out, TrailPoints.index(f.chain), HEAD);
            }
            double tr = Sight.transmittance(d, view.range());
            float vis = Mth.lerp(view.partial(), f.visPrev, f.vis);
            body(f.look.size(), f.look.area(), d, view.pixel(), OUT);
            int c = f.look.color();
            float a = (float) (OUT[1] * vis * contrast(tr));
            out.dot(dx, dy, dz, OUT[0], view.hazed(channel(c, 0), 0, tr), view.hazed(channel(c, 1), 1, tr), view.hazed(channel(c, 2), 2, tr), a);
            double bodyPx = 2 * OUT[0] / SOFT_DOT / (d * view.pixel());
            double flamePx = 0, flameAlpha = 0;
            Flame flame = stage.flame();
            if (flame != null && vis > 0) {
                flameAt(track, t, flame, dx, dy, dz, OUT);
                double bd = OUT[0];
                Sight.point(flame.radius(), adapted(flame.brightness(), view.ambient()), Sight.transmittance(bd, view.range()), bd, view.pixel(), OUT);
                int fc = flame.color();
                out.glow(BACK[0], BACK[1], BACK[2], OUT[0] * SOFT_DOT, channel(fc, 0), channel(fc, 1), channel(fc, 2), (float) (OUT[1] * vis));
                flamePx = 2 * OUT[0] / (bd * view.pixel());
                flameAlpha = OUT[1] * vis;
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
            }
        }
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

    /** Конец ленты в кэш кадра под номером slot: где (от камеры), полуширина с полом в пиксель, непрозрачность, цвет в дымке. */
    private static void end(FarTrail s, double age, double dx, double dy, double dz, FarView view, int slot) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double t = Sight.transmittance(d, view.range());
        ribbon(s.width(age), d, view.pixel(), OUT);
        float shade = s.shade(age);
        EX[slot] = dx;
        EY[slot] = dy;
        EZ[slot] = dz;
        EH[slot] = OUT[0];
        EA[slot] = (float) (s.opacity(age) * OUT[1] * contrast(t));
        ER[slot] = view.hazed(Mth.lerp(shade, channel(s.color0(), 0), channel(s.color1(), 0)), 0, t);
        EG[slot] = view.hazed(Mth.lerp(shade, channel(s.color0(), 1), channel(s.color1(), 1)), 1, t);
        EB[slot] = view.hazed(Mth.lerp(shade, channel(s.color0(), 2), channel(s.color1(), 2)), 2, t);
        DRAWN[slot] = frame;
    }

    /** Отрезок ленты между концами a и b из кэша кадра; цвет — середина между их цветами. */
    private static void segment(FarSprites out, int a, int b) {
        out.ribbon(EX[a], EY[a], EZ[a], EH[a], EA[a], EX[b], EY[b], EZ[b], EH[b], EA[b],
                (ER[a] + ER[b]) * 0.5f, (EG[a] + EG[b]) * 0.5f, (EB[a] + EB[b]) * 0.5f);
    }

    // ---------------------------------------------------------------- чистая геометрия видимости (юнит-тесты)

    /**
     * Корпус на дальности d: полуразмер мягкой точки и её непрозрачность. Тень — круг той же площади, что средний
     * силуэт {@code area} ({@link Sight#point}: мельче пикселя — точка в полтора пикселя, бледнее), в мягкой точке
     * с тем же покрытием; крупнее — не меньше своего размера {@code size}, с тем же покрытием (бледнее).
     */
    static void body(double size, double area, double d, double pixel, double[] out) {
        Sight.point(Math.sqrt(area / Math.PI), 1, 1, d, pixel, out);
        double soft = out[0] * SOFT_DOT, half = Math.max(soft, size / 2), k = soft / half;
        out[0] = half;
        out[1] *= k * k;
    }

    /**
     * Лента шириной width на дальности d: полуширина для {@link FarSprites#ribbon} и доля непрозрачности. Уже пикселя
     * она не пропадает, а рисуется в полтора пикселя, бледнее во столько же раз: сколько неба закрывает след, столько
     * и лента.
     */
    static void ribbon(double width, double d, double pixel, double[] out) {
        double half = width / 2, floor = 0.5 * Sight.MIN_PIXELS * pixel * d;
        out[0] = Math.max(half, floor) * SOFT_RIBBON;
        out[1] = half >= floor ? 1 : half / floor;
    }

    /**
     * Яркость факела против белого экрана: днём глаз (и экспозиция) настроены на небо, ночью — на темноту, и тот же
     * факел во столько раз ярче, во сколько темнее свет неба ({@link FarView#ambient}, в квадрате: зрачок и экспозиция).
     */
    static double adapted(double brightness, double ambient) {
        return brightness / (ambient * ambient);
    }

    /**
     * Доля тёмного (корпус, дым), которая ещё видна сквозь дымку прозрачности t: у порога глаза ({@link Sight#THRESHOLD})
     * — ноль, втрое дальше от него — вся. Цвет к порогу и так становится цветом дымки, но небо за снарядом бывает
     * другого цвета, чем дымка у горизонта, — без этого там висела бы бледная тень.
     */
    static double contrast(double t) {
        return Mth.clamp((t - Sight.THRESHOLD) / (3 * Sight.THRESHOLD), 0, 1);
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
     * прозрачность воздуха, корпус и факел в пикселях с непрозрачностью, закрыт ли рельефом; точек шлейфов в кадре.
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
