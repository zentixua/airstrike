package ua.zentix.airstrike.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.sound.Acoustics;
import ua.zentix.airstrike.client.sound.BlastSounds;
import ua.zentix.airstrike.client.sound.Outdoor;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.warhead.BunkerCover;
import ua.zentix.airstrike.warhead.GroundMaterial;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Взрывы вдали: вспышка, огненный шар, столб дыма и раскат с задержкой по скорости звука.
 * <p>
 * Пакет взрыва — одно событие: всё случайное (клубы столба — высота, разброс, размер, кадр атласа, поворот, оттенок)
 * выбирается по его зерну один раз, кадр по времени только считает, где клуб, какой он и какого цвета: работа кадра —
 * по числу клубов. Рельеф между взрывом и глазом — один луч {@link Sightline} при приходе и не чаще раза в секунду
 * потом: за холмом видна только поднявшаяся над ним часть столба, а вспышка — слабым заревом над гребнем. Числа видов —
 * в одной таблице {@link Look}.
 * <p>
 * Ближе 0,8 прорисовки взрыв рисуют частицы {@code BlastEffects}, дальше картинка переходит сюда ({@link FarView#farShare});
 * пакет, пришедший дальше {@link #NEAR}, ближней картинки не получает — дальняя рисуется на любой дальности. Свет
 * приходит сразу, звук — когда до уха дойдёт фронт; дальше {@link #NEAR} он здесь ({@link BlastSounds#far}), ближе —
 * пояса ближней модели.
 */
public final class FarBlasts {
    /** Край ближней картинки и ближнего звука, блоков. */
    public static final double NEAR = Outdoor.NEAR;
    /** Событий не больше: старые уходят первыми. */
    static final int CAP = 256;
    /** Луч по рельефу — не чаще раза в столько тиков на событие; лучей за тик — не больше (кроме лучей новых). */
    static final int RETRACE = 20, TRACES_PER_TICK = 16;
    /** Звук, до уха которого фронт так и не дошёл (слушатель уходит быстрее звука), дольше не ждём, тиков. */
    static final int SOUND_WAIT = 2400;
    /** Источник для луча и звука — над точкой удара, блоков (как вспышка ближней картинки). */
    static final double LIFT = 1.5;
    /** Дрейф клубов: скорость, на которой ветер {@link Fx} уравновешен сопротивлением дыма столба (drag 0,95). */
    static final double DRIFT = 1 / (1 - 0.95);
    /** Плотность клуба; разброс клубов от оси столба, радиусов. */
    static final double OPACITY = 0.85, SPREAD = 0.4;
    /** За сколько тиков столб проступает из остывающего шара. */
    static final double FADE_IN = 8;
    /** Яркость огненного шара и пожара в воронке против белого экрана; доля вспышки, что светит заревом из-за гребня. */
    static final double BALL = 8, BURN = 2, BEHIND = 0.05;

    /** Дым: свежий чёрный и старый серый (как у столба ближней картинки), цвет пыли тянет к этому серому. */
    private static final float[] DARK = {0.18f, 0.165f, 0.15f}, AGED = {0.48f, 0.455f, 0.43f}, DUST_GREY = {0.6f, 0.57f, 0.54f};
    /** Вспышка, шар (раскалён → оранжевый → тёмно-красный), пожар. */
    private static final float[] FLASH = {1, 0.95f, 0.82f}, BALL_HOT = {1, 0.9f, 0.7f}, BALL_MID = {1, 0.5f, 0.15f},
            BALL_COLD = {0.6f, 0.15f, 0.04f}, FIRE = {1, 0.5f, 0.15f};

    /**
     * Как выглядит и слышится взрыв вида издалека. Шар — как у {@code BlastEffects} ({@code Drone.R}, {@code Missile.R},
     * {@code Bunker.R}; у РСЗО — как у шахеда); столб — по снимкам настоящих ударов:
     * шахед (≈50 кг ВВ) — 100–200 м, крылатая ракета (≈450 кг) — 300–500 м, снаряд РСЗО (≈20 кг) меньше и короче,
     * бетонобойная бомба под землёй — столб пыли без вспышки (прорвалась наружу — шар и столб, как у наземного взрыва).
     *
     * @param fireball   радиус огненного шара, блоков; 0 — ни шара, ни вспышки
     * @param flash      яркость вспышки против белого экрана
     * @param flashTicks сколько тиков вспышка
     * @param ballTicks  за сколько тиков шар остывает
     * @param burnTicks  сколько тиков горит воронка (тусклая точка у основания — видна ночью)
     * @param column     высота столба, блоков
     * @param riseTicks  за сколько тиков столб поднимается на 95 %
     * @param base       радиус клуба у основания, блоков
     * @param top        радиус клуба в шапке, блоков
     * @param puffs      клубов в столбе
     * @param life       сколько тиков столб виден
     * @param dust       доля пыли грунта: 1 — весь столб пыль, меньше — дым, пыль у основания
     * @param audible    докуда слышно в обычных условиях ({@code R0} у {@link Outdoor}), блоков
     */
    record Look(float fireball, double flash, int flashTicks, int ballTicks, int burnTicks, double column, int riseTicks, double base, double top,
                int puffs, int life, float dust, double audible) {}

    static final Look DRONE = new Look(5, 40, 3, 16, 220, 150, 600, 9, 26, 12, 3000, 0.25f, 20_000);
    static final Look MISSILE = new Look(8.5f, 60, 4, 22, 320, 400, 900, 16, 50, 14, 4800, 0.2f, 40_000);
    static final Look ROCKET = new Look(5, 30, 3, 12, 0, 70, 300, 6, 15, 10, 1200, 0.35f, 15_000);
    static final Look BUNKER_BREACH = new Look(11, 70, 4, 26, 0, 450, 1000, 18, 58, 14, 4200, 0.45f, 30_000);
    static final Look BUNKER_DEEP = new Look(0, 0, 0, 0, 0, 110, 500, 14, 32, 12, 3000, 1, 30_000);
    /** Размер клуба — от и сколько сверху (доля номинального); разброс места клуба по высоте — доля шага. */
    static final double SIZE_MIN = 0.85, SIZE_SPAN = 0.3, JITTER = 0.3;

    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    /** Числа на кадр без выделения: точка {@link Sight#point} и цвет шара. */
    private static final double[] POINT = new double[2];
    private static final float[] TINT = new float[3];
    /** Тики клиента (не на паузе). */
    private static long clock;
    /** Последний дальний звук — для лога сценария. */
    private static Outdoor.Heard lastHeard;
    private static double lastDistance;
    private static long lastDelay;

    private FarBlasts() {}

    /** Один взрыв: где, какой, всё случайное его столба и последний луч по рельефу. */
    private static final class Event {
        final ClientLevel level;
        final int kind;
        final Look look;
        /** Основание: точка удара, у бомбы — поверхность над зарядом. */
        final double x, y, z;
        final GroundMaterial ground;
        final long born;
        /** Пакет пришёл ближе {@link #NEAR}: ближняя картинка и звук есть. */
        final boolean near;
        final float dustR, dustG, dustB, phase;
        /** Клубы: доля высоты столба, разброс по x и z (в радиусах), размер, поворот, вращение, оттенок, доля пыли, кадр атласа. */
        final float[] f, ox, oz, size, rot, spin, shade, dust;
        final byte[] tex;
        boolean soundPending;
        /** Рисовался в прошлом кадре: луч стоит обновлять. */
        boolean wanted = true;
        long traced;
        /** По последнему лучу: на сколько блоков выше источника его видно; разность хода через кромку. */
        double hidden, path;

        Event(ClientLevel level, int kind, Look look, double x, double y, double z, GroundMaterial ground, long seed, boolean near) {
            this.level = level;
            this.kind = kind;
            this.look = look;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ground = ground;
            this.born = clock;
            this.near = near;
            this.soundPending = !near;
            // пыль в воздухе светлее и серее грунта
            dustR = ground.r + (DUST_GREY[0] - ground.r) * 0.25f;
            dustG = ground.g + (DUST_GREY[1] - ground.g) * 0.25f;
            dustB = ground.b + (DUST_GREY[2] - ground.b) * 0.25f;
            SplittableRandom rnd = new SplittableRandom(seed);
            phase = (float) (rnd.nextDouble() * Math.PI * 2);
            int n = look.puffs();
            f = new float[n];
            ox = new float[n];
            oz = new float[n];
            size = new float[n];
            rot = new float[n];
            spin = new float[n];
            shade = new float[n];
            dust = new float[n];
            tex = new byte[n];
            for (int i = 0; i < n; i++) {
                f[i] = (float) fraction(i, n, rnd.nextDouble() - 0.5);
                ox[i] = (float) gauss(rnd);
                oz[i] = (float) gauss(rnd);
                size[i] = (float) (SIZE_MIN + SIZE_SPAN * rnd.nextDouble());
                rot[i] = (float) (rnd.nextDouble() * Math.PI * 2);
                spin[i] = (float) ((rnd.nextDouble() - 0.5) * 0.004);
                shade[i] = (float) (0.88 + 0.24 * rnd.nextDouble());
                dust[i] = (float) Math.pow(look.dust(), 0.5 + f[i]);
                tex[i] = (byte) rnd.nextInt(8);
            }
        }

        double distanceTo(Vec3 p) {
            double dx = x - p.x, dy = y + LIFT - p.y, dz = z - p.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Пакет взрыва ближе {@link #NEAR} к камере: его рисуют частицы и озвучивает ближняя модель. */
    public static boolean near(Vec3 pos) {
        return camera().distanceTo(pos) <= NEAR;
    }

    /** Пакет взрыва пришёл (свет — сразу, звук — когда дойдёт). */
    public static void add(S2C.Blast p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 pos = p.pos();
        Look look;
        double y = pos.y;
        if (p.kind() == S2C.Blast.BUNKER) {
            look = BunkerCover.breaches((int) p.surfaceY(), pos.y) ? BUNKER_BREACH : BUNKER_DEEP;
            y = p.surfaceY();
        } else {
            look = switch (p.kind()) {
                case S2C.Blast.MISSILE -> MISSILE;
                case S2C.Blast.ROCKET -> ROCKET;
                default -> DRONE;
            };
        }
        Event e = new Event(level, p.kind(), look, pos.x, y, pos.z, GroundMaterial.byId(p.material()), p.seed(), near(pos));
        trace(e, FarTerrain.of(level), camera());
        if (EVENTS.size() >= CAP) EVENTS.pollFirst();
        EVENTS.addLast(e);
    }

    /** Раз в тик: забыть прошедшее и чужое измерение, звук — когда дошёл фронт, лучи по рельефу — по очереди. */
    static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            EVENTS.clear();
            return;
        }
        clock++;
        if (EVENTS.isEmpty()) return;
        Vec3 ear = camera();
        Sightline.Heights heights = FarTerrain.of(level);
        int traces = 0;
        Iterator<Event> it = EVENTS.iterator();
        while (it.hasNext()) {
            Event e = it.next();
            long age = clock - e.born;
            if (e.level != level || age > e.look.life() && (!e.soundPending || age > SOUND_WAIT)) {
                it.remove();
                continue;
            }
            if (e.soundPending) {
                double d = e.distanceTo(ear);
                if (d <= Acoustics.SPEED * age) {
                    e.soundPending = false;
                    trace(e, heights, ear);
                    hear(e, d, age);
                } else if (age > SOUND_WAIT) {
                    e.soundPending = false;
                }
            }
            if (e.wanted && clock - e.traced >= RETRACE && traces < TRACES_PER_TICK) {
                trace(e, heights, ear);
                traces++;
            }
        }
    }

    private static void hear(Event e, double d, long age) {
        Outdoor.Heard heard = BlastSounds.far(e.kind, new Vec3(e.x, e.y + LIFT, e.z), e.look.audible(), Math.max(1, 0.4 * e.look.fireball()),
                e.ground, d, e.path);
        if (heard == null) return;
        lastHeard = heard;
        lastDistance = d;
        lastDelay = age;
    }

    private static void trace(Event e, Sightline.Heights heights, Vec3 eye) {
        Sightline.Result r = Sightline.trace(heights, e.x, e.y + LIFT, e.z, eye.x, eye.y, eye.z);
        e.hidden = r.hidden();
        e.path = r.pathDifference();
        e.traced = clock;
    }

    // ---------------------------------------------------------------- кадр

    static void collect(FarView view, FarSprites out) {
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 cam = view.camera();
        double now = clock + view.partial();
        for (Event e : EVENTS) {
            if (e.level != level) continue;
            double age = Math.max(0, now - e.born);
            // основание относительно камеры
            double bx = e.x - cam.x, by = e.y - cam.y, bz = e.z - cam.z;
            double d = e.distanceTo(cam);
            double w = e.near ? view.farShare(d) : 1;
            e.wanted = w > 0;
            if (w <= 0) continue;
            // линия, ниже которой место взрыва закрыто рельефом, — над основанием
            double line = e.hidden > 0 ? LIFT + e.hidden : 0;
            light(e, view, out, age, bx, by, bz, d, w, line);
            column(e, view, out, age, bx, by, bz, w, line);
        }
    }

    /** Вспышка, огненный шар и пожар в воронке — свет, складывается. */
    private static void light(Event e, FarView view, FarSprites out, double age, double bx, double by, double bz, double d, double w, double line) {
        Look k = e.look;
        double t = Sight.transmittance(d, view.range());
        if (k.fireball() <= 0 || t < Sight.THRESHOLD) return;
        double r = k.fireball();
        if (age < k.flashTicks()) {
            double f = 1 - age / k.flashTicks();
            double b = k.flash() * f * f;
            double vis = glow(view, out, bx, by, bz, 0.4 * r, 3.5 * r, b, t, w, line, FLASH);
            // за гребнем: свет вспышки рассеивает воздух над ним — слабое зарево там, откуда место было бы видно
            if (vis < 1) glow(view, out, bx, by, bz, line, 7 * r, b * BEHIND * (1 - vis), t, w, 0, FLASH);
        }
        if (age < k.ballTicks()) {
            double u = age / k.ballTicks();
            double radius = r * (0.55 + 0.45 * Math.min(1, age / 3)) * (1 + 0.3 * u);
            float[] from = u < 0.35 ? BALL_HOT : BALL_MID, to = u < 0.35 ? BALL_MID : BALL_COLD;
            float s = (float) (u < 0.35 ? u / 0.35 : (u - 0.35) / 0.65);
            TINT[0] = from[0] + (to[0] - from[0]) * s;
            TINT[1] = from[1] + (to[1] - from[1]) * s;
            TINT[2] = from[2] + (to[2] - from[2]) * s;
            glow(view, out, bx, by, bz, r * (0.4 + 0.5 * u), radius, BALL * Math.pow(1 - u, 1.5), t, w, line, TINT);
        }
        if (age < k.burnTicks()) {
            double flicker = 0.75 + 0.25 * Math.sin(age * 1.9 + e.phase) * Math.sin(age * 0.73 + 2 * e.phase);
            glow(view, out, bx, by, bz, 0.2 * r, 0.6 * r, BURN * (1 - age / k.burnTicks()) * flicker, t, w, line, FIRE);
        }
    }

    /**
     * Светящаяся точка с центром на высоте h над основанием: видимая над линией рельефа часть её света
     * ({@link Sight#point}). Возвращает видимую долю.
     */
    private static double glow(FarView view, FarSprites out, double bx, double by, double bz, double h, double radius, double brightness, double t,
                               double w, double line, float[] c) {
        double vis = visible(h, radius, line);
        if (vis <= 0) return 0;
        double gy = by + h;
        double d = Math.sqrt(bx * bx + gy * gy + bz * bz);
        Sight.point(radius, brightness * vis, t, d, view.pixel(), POINT);
        out.glow(bx, gy, bz, POINT[0], c[0], c[1], c[2], (float) (POINT[1] * w));
        return vis;
    }

    /** Столб дыма и пыли: поднимается, расплывается, уходит по ветру; сначала тает низ. */
    private static void column(Event e, FarView view, FarSprites out, double age, double bx, double by, double bz, double w, double line) {
        Look k = e.look;
        double rise = rise(age, k.riseTicks());
        double grey = smoothstep(0, 0.5 * k.life(), age);
        float sr = (float) (DARK[0] + (AGED[0] - DARK[0]) * grey), sg = (float) (DARK[1] + (AGED[1] - DARK[1]) * grey),
                sb = (float) (DARK[2] + (AGED[2] - DARK[2]) * grey);
        double opacity = OPACITY * smoothstep(0, FADE_IN, age) * w;
        double windX = Fx.WIND_X * DRIFT * age, windZ = Fx.WIND_Z * DRIFT * age;
        double spread = 1 + 0.5 * age / k.life(), grow = 0.35 + 0.65 * rise;
        for (int i = 0; i < e.f.length; i++) {
            double f = e.f[i];
            double a = opacity * (1 - smoothstep(k.life() * (0.35 + 0.45 * f), k.life(), age));
            if (a < 0.004) continue;
            double r = radius(k, f) * e.size[i] * grow * spread;
            double h = height(k, f, r, rise);
            double vis = visible(h, r, line);
            if (vis <= 0) continue;
            // выше — ветер сильнее: столб клонится
            double shear = 0.3 + 0.7 * f * rise;
            double px = bx + e.ox[i] * r * SPREAD + windX * shear, py = by + h, pz = bz + e.oz[i] * r * SPREAD + windZ * shear;
            double d = Math.sqrt(px * px + py * py + pz * pz);
            double t = Sight.transmittance(d, view.range());
            if (t < Sight.THRESHOLD) continue;
            Sight.point(r, 1, 1, d, view.pixel(), POINT);
            float share = e.dust[i], shade = e.shade[i];
            float cr = view.hazed((sr + (e.dustR - sr) * share) * shade, 0, t), cg = view.hazed((sg + (e.dustG - sg) * share) * shade, 1, t),
                    cb = view.hazed((sb + (e.dustB - sb) * share) * shade, 2, t);
            out.puff(px, py, pz, POINT[0], e.rot[i] + (float) (age * e.spin[i]), e.tex[i], cr, cg, cb, (float) (a * vis * POINT[1]));
        }
    }

    /** Доля высоты, на которую столб поднялся за age тиков: быстро вначале, к riseTicks — 95 %. */
    static double rise(double age, double riseTicks) {
        return 1 - Math.exp(-3 * age / riseTicks);
    }

    /**
     * Доля высоты столба i-го из n клубов; jitter −0,5..0,5 — разброс в долю {@link #JITTER} шага. Внизу гуще: там клубы
     * мельче, и столб не рвётся на бусины.
     */
    static double fraction(int i, int n, double jitter) {
        return Math.pow(Math.max(0, Math.min(1, (i + 0.5 + JITTER * jitter) / n)), 1.3);
    }

    /** Радиус клуба на доле высоты f поднявшегося столба (без разброса размера), блоков: от ножки к шапке шире. */
    static double radius(Look k, double f) {
        return k.base() + (k.top() - k.base()) * f;
    }

    /** Высота центра клуба радиуса r над основанием: нижний стоит на земле, остальные — на своей доле подъёма. */
    static double height(Look k, double f, double r, double rise) {
        return 0.6 * r + f * k.column() * rise;
    }

    /**
     * Какая доля клуба (центр на высоте h над основанием, радиус r) видна над линией рельефа {@code line} (0 — место
     * открыто); часть под основанием — в земле и не в счёт. Сглажено: клуб уходит за гребень плавно.
     */
    static double visible(double h, double r, double line) {
        if (line <= 0) return 1;
        double lo = Math.max(0, h - r), hi = h + r;
        if (hi <= lo) return line <= h ? 1 : 0;
        double v = Math.max(0, Math.min(1, (hi - Math.max(line, lo)) / (hi - lo)));
        return v * v * (3 - 2 * v);
    }

    static boolean isEmpty() {
        return EVENTS.isEmpty();
    }

    static void reset() {
        EVENTS.clear();
        lastHeard = null;
    }

    /**
     * Для лога сценария (раз в секунду): сколько взрывов вдали живо и последний дальний звук — до уха, задержка от
     * пакета (и d / 17,15 — когда фронт должен был дойти), громкость, запас слышимости и потери на низах (преграда,
     * тень, дождь), насколько верха глуше низов и сколько из этого от земли. Ничего дальнего ещё не было — пустая строка.
     */
    public static String describe() {
        if (lastHeard == null) return EVENTS.isEmpty() ? "" : "взрывов " + EVENTS.size();
        Outdoor.Heard h = lastHeard;
        return String.format(Locale.ROOT, "взрывов %d; звук: %.0f бл, задержка %d т (фронт %.1f т), громкость %.3f, запас %.1f дБ, преграда %.1f дБ, "
                        + "тень %.1f дБ, дождь %.1f дБ, верха %.1f дБ, из них земля %.1f дБ", EVENTS.size(), lastDistance, lastDelay,
                lastDistance / Acoustics.SPEED, h.volume(), h.margin(), h.barrier(), h.shadow(), h.masking(), 20 * Math.log10(Math.max(1e-6, h.highs())),
                -h.ground());
    }

    private static Vec3 camera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double gauss(SplittableRandom rnd) {
        // сумма трёх равномерных — колокол без хвостов (клуб не улетает от столба)
        return (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 1.15;
    }
}
