package ua.zentix.airstrike.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.fx.layer.FxAtlas;
import ua.zentix.airstrike.client.fx.layer.FxLights;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.sound.Acoustics;
import ua.zentix.airstrike.client.sound.BlastSounds;
import ua.zentix.airstrike.client.sound.Outdoor;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.warhead.BunkerCover;
import ua.zentix.airstrike.warhead.GroundMaterial;

import java.util.ArrayDeque;
import java.util.Arrays;
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
 * приходит сразу: блик и вуаль вокруг вспышки и шара — здесь на любой дальности, и рядом (они в глазу, а не в воздухе;
 * ночью ещё зарево на облаках над местом — {@link #cloudGlow}, на любой дальности, кроме самой близкой); звук — когда до
 * уха дойдёт фронт; дальше {@link #NEAR} он здесь ({@link BlastSounds#far}), ближе — ракурсы ближней модели
 * ({@code BlastMix}). Огненный шар вдали и вблизи — одна анимация ({@code fx/particle/fireball}) одного размера и времени.
 * <p>
 * Числа — по замерам и оценкам (исследование 01.10.2026): огненный шар ВВ — диаметр 3,2–3,65·W^⅓ м (W — кг ТНТ), светит
 * 0,2·W^0,35 с, начинает с ~2000 K и остывает; днём ярче неба лишь первую половину жизни. Столб — по Чёрчу (1969): верх
 * облака через 2 минуты — 92,6·W^¼ м, к 30 с — половина. Дым свежий — чёрный (сажа ТНТ), за минуту — серо-бурый.
 */
public final class FarBlasts {
    /** Край ближней картинки и ближнего звука, блоков. */
    public static final double NEAR = Outdoor.NEAR;
    /** Событий не больше: старые уходят первыми. */
    static final int CAP = 256;
    /** Луч по рельефу — не чаще раза в столько тиков на событие; лучей за тик — не больше (кроме лучей новых). */
    static final int RETRACE = 20, TRACES_PER_TICK = 16;
    /**
     * Шаров в прорисовке, к которым за тик идут лучи по блокам ({@link #clear}), сверх лучей по рельефу, не больше: пока
     * шар светит — каждый тик по очереди, потом — с лучом по рельефу.
     */
    static final int CLEARS_PER_TICK = 8;
    /** Лучей к шару по кругу вокруг середины ({@link #clear}) и радиус круга, радиусов шара. */
    static final int RING_RAYS = 6;
    static final double RING = 0.7;
    private static final double[] DISC = new double[3];
    /** Звук, до уха которого фронт так и не дошёл (слушатель уходит быстрее звука), дольше не ждём, тиков. */
    static final int SOUND_WAIT = 2400;
    /** Источник для луча и звука — над точкой удара, блоков (как вспышка ближней картинки). */
    static final double LIFT = 1.5;
    /** Дрейф клубов: скорость, на которой ветер {@link Fx} уравновешен сопротивлением дыма столба (drag 0,95). */
    static final double DRIFT = 1 / (1 - 0.95);
    /** Плотность клуба; разброс клубов от оси столба, радиусов. */
    static final double OPACITY = 0.85, SPREAD = 0.4;
    /**
     * Столб проступает из шара, пока тот тает ({@link #smokeFrom}), но не быстрее стольких тиков. Проступавший с первого
     * тика, он за 8 тиков закрывал яркий шар десятком клубов: вдали вместо шара было бурое пятно, ночью — кольцо
     * зарева с чёрной серединой.
     */
    static final double FADE_IN = 8;
    /**
     * Яркость огненного шара и пожара в воронке против белого экрана днём (шар в ~2000 K — в десятки раз ярче неба;
     * здесь меньше: экран не ярче белого, пересвет — блик и белое ядро); доля света шара, что светит заревом, когда шар
     * закрыт: воздух рассеивает свет шара радиуса R на расстоянии r — яркость σ·ℓ·R²/(4r²) от яркости шара (σ ясного
     * воздуха ~2·10⁻⁴ на метр, путь ℓ ~100 м, r — 2–3 R), тысячная. Днём это едва видно (при 0,05 за краем площадки
     * вставало белое пятно в пол-экрана), ночью зарево упирается в яркость вуали ({@link #halo}).
     */
    static final double BALL = 8, BURN = 2, BEHIND = 0.001;
    /** Радиус светящегося воздуха вокруг закрытого шара, радиусов шара: дальше рассеянный свет слабеет как 1/r. */
    static final double HALO_SIZE = 3;
    /**
     * Центр огненного шара над точкой взрыва (радиусов): наземный шар — полусфера, низ в земле; за жизнь он всплывает ещё
     * на {@link #BALL_RISE} (как частицы вблизи: скорость 0,012 радиуса за тик, сопротивление 0,94). Пожар в воронке —
     * пламя радиусом {@link #BURN_SIZE} шара.
     */
    public static final double BALL_LIFT = 0.45, BALL_RISE = 0.2, BURN_SIZE = 0.3;
    /** С какой доли жизни шар тает (дальше — дым столба; так же у частиц вблизи — {@code Fx#fireball}). */
    public static final double BALL_FADE = 0.55;
    /**
     * Зарево на облаках: низ облаков над местом освещён шаром (освещённость E = I/h², яркость ≈ альбедо·E/π) — пятно
     * радиуса {@link #SKY_SPREAD} высот облаков над местом (на одной высоте — треть пика, на двух — десятая); пик против
     * белого — не больше SKY_PEAK (в дождь, облака сплошные), в ясную ночь — {@link #SKY_CLEAR} от него. Облака ниже
     * {@link #SKY_LOW} блоков над местом — считаем на этой высоте.
     */
    static final double CLOUD_ALBEDO = 0.7, SKY_SPREAD = 1, SKY_PEAK = 0.45, SKY_CLEAR = 0.45, SKY_LOW = 40;

    /** Дым: свежий чёрный и старый серый (как у столба ближней картинки), цвет пыли тянет к этому серому. */
    private static final float[] DARK = {0.18f, 0.165f, 0.15f}, AGED = {0.48f, 0.455f, 0.43f}, DUST_GREY = {0.6f, 0.57f, 0.54f};
    /** Свет вспышки, шара (раскалён → оранжевый → тёмно-красный), пожара: цвет блика и вуали и далёкой точки. */
    private static final float[] FLASH = {1, 0.95f, 0.82f}, BALL_HOT = {1, 0.9f, 0.7f}, BALL_MID = {1, 0.5f, 0.15f},
            BALL_COLD = {0.6f, 0.15f, 0.04f}, FIRE = {1, 0.5f, 0.15f};

    /**
     * Как выглядит и слышится взрыв вида издалека (ТНТ-эквивалент: снаряд РСЗО ~6 кг, шахед ~30, крылатая ракета ~300):
     * шар — радиус 1,7·W^⅓ (РСЗО 3,5, шахед 5 — как у ближней картинки {@code BlastEffects}, ракета 11), светит
     * 0,2·W^0,35 с (8, 13 и 30 тиков); столб — верх облака по Чёрчу (центр верхнего клуба и его радиус: РСЗО ~150 м,
     * шахед ~220, ракета ~390), растёт ~2 минуты; пожар — у шахеда горит топливо (~70 л), у ракеты — то, во что попала.
     * Бетонобойная бомба под землёй — столб пыли без вспышки; прорвалась наружу — шар и столб ниже, чем у наземного.
     *
     * @param fireball   радиус огненного шара, блоков; 0 — ни шара, ни вспышки
     * @param flash      яркость вспышки против белого экрана
     * @param flashTicks сколько тиков вспышка
     * @param ballTicks  сколько тиков шар светит (ночью виден весь, днём — первую половину)
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
    public record Look(float fireball, double flash, int flashTicks, int ballTicks, int burnTicks, double column, int riseTicks, double base,
                       double top, int puffs, int life, float dust, double audible) {}

    public static final Look DRONE = new Look(5, 40, 3, 13, 1200, 150, 2400, 10, 40, 12, 3600, 0.25f, 20_000);
    public static final Look MISSILE = new Look(11, 60, 4, 30, 600, 270, 2400, 18, 70, 14, 4800, 0.2f, 40_000);
    public static final Look ROCKET = new Look(3.5f, 30, 2, 8, 0, 105, 2400, 7, 26, 10, 3000, 0.45f, 15_000);
    public static final Look BUNKER_BREACH = new Look(9, 60, 3, 20, 0, 210, 2400, 18, 55, 14, 4200, 0.5f, 30_000);
    public static final Look BUNKER_DEEP = new Look(0, 0, 0, 0, 0, 110, 500, 14, 32, 12, 3000, 1, 30_000);
    /** Размер клуба — от и сколько сверху (доля номинального); разброс места клуба по высоте — доля шага. */
    static final double SIZE_MIN = 0.85, SIZE_SPAN = 0.3, JITTER = 0.3;

    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    /** Числа на кадр без выделения: точка {@link Sight#point}, свет {@link Sight#light} и цвет шара. */
    private static final double[] POINT = new double[2], LIGHT = new double[5];
    /** Самое яркое тело последнего кадра (для лога): ядро, блик и вуаль в пикселях, пересвет, доля над рельефом. */
    private static final double[] BRIGHTEST = new double[5];
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
        /** Зерно взрыва: столб и вариант записи звука. */
        final long seed;
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
        /** Шар в прорисовке не закрыт блоками (доля двух лучей, {@link #clear}): блик и вуаль у глаза глубина не режет. */
        float open = 1;

        Event(ClientLevel level, int kind, Look look, double x, double y, double z, GroundMaterial ground, long seed, boolean near) {
            this.level = level;
            this.kind = kind;
            this.look = look;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ground = ground;
            this.seed = seed;
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
        clear(e, camera());
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
        int traces = 0, clears = 0;
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
                clear(e, ear);
                traces++;
            } else if (age < e.look.ballTicks() && clears < CLEARS_PER_TICK) {
                clear(e, ear);
                clears++;
            }
        }
    }

    private static void hear(Event e, double d, long age) {
        Outdoor.Heard heard = BlastSounds.far(e.kind, new Vec3(e.x, e.y + LIFT, e.z), e.look.audible(), Math.max(1, 0.4 * e.look.fireball()),
                e.ground, d, e.path, e.seed);
        if (heard == null) return;
        lastHeard = heard;
        lastDistance = d;
        lastDelay = age;
    }

    /**
     * Шар в прорисовке закрывают блоки (дом, стена, свод пещеры), а блик и вуаль у глаза глубина не режет: лучи по
     * блокам к середине шара и к кольцу вокруг неё ({@link #disc}), открытая доля — доля блика. Два луча к оси шара
     * у угла высотки оба упирались в угол, и видимая половина шара светила без блика. Часть диска в земле закрыта
     * всем и в счёт не идёт. Дальше прорисовки шар закрывает рельеф ({@link #trace}).
     */
    private static void clear(Event e, Vec3 eye) {
        double r = e.look.fireball();
        if (r <= 0) return;
        if (e.distanceTo(eye) > Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0) {
            e.open = 1;
            return;
        }
        double mid = e.y + r * (BALL_LIFT + 0.5 * BALL_RISE);
        int seen = 0, counted = 0;
        for (int i = 0; i <= RING_RAYS; i++) {
            disc(eye, e.x, mid, e.z, RING * r, i, DISC);
            if (i > 0 && DISC[1] < e.y + 0.5) continue;
            counted++;
            if (!FarFlightView.blocked(eye, DISC[0], DISC[1], DISC[2])) seen++;
        }
        e.open = seen / (float) counted;
    }

    /**
     * Точка i диска шара с центром (cx, cy, cz), каким его видит глаз: 0 — центр, 1…{@link #RING_RAYS} — по кругу
     * радиуса ring в плоскости поперёк луча зрения, первая — сбоку, по кругу вверх.
     */
    static void disc(Vec3 eye, double cx, double cy, double cz, double ring, int i, double[] out) {
        out[0] = cx;
        out[1] = cy;
        out[2] = cz;
        if (i == 0) return;
        double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return;
        dx /= len;
        dy /= len;
        dz /= len;
        // вбок — поперёк луча по горизонтали (луч отвесный — вдоль x), вверх по диску — бок × луч
        double sx = -dz, sz = dx, s = Math.sqrt(sx * sx + sz * sz);
        if (s < 1e-9) {
            sx = 1;
            sz = 0;
        } else {
            sx /= s;
            sz /= s;
        }
        double ux = -sz * dy, uy = sz * dx - sx * dz, uz = sx * dy;
        double a = 2 * Math.PI * (i - 1) / RING_RAYS, c = Math.cos(a) * ring, n = Math.sin(a) * ring;
        out[0] += sx * c + ux * n;
        out[1] += uy * n;
        out[2] += sz * c + uz * n;
    }

    private static void trace(Event e, Sightline.Heights heights, Vec3 eye) {
        Sightline.Result r = Sightline.trace(heights, e.x, e.y + LIFT, e.z, eye.x, eye.y, eye.z);
        e.hidden = r.hidden();
        e.path = r.pathDifference();
        e.traced = clock;
    }

    // ---------------------------------------------------------------- кадр

    static void collect(FarView view, FarSprites out, FxLights lights) {
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 cam = view.camera();
        double now = clock + view.partial();
        Arrays.fill(BRIGHTEST, 0);
        for (Event e : EVENTS) {
            if (e.level != level) continue;
            double age = Math.max(0, now - e.born);
            // основание относительно камеры
            double bx = e.x - cam.x, by = e.y - cam.y, bz = e.z - cam.z;
            double d = e.distanceTo(cam);
            double w = e.near ? view.farShare(d) : 1;
            // луч нужен и рядом, пока светит шар: блик и вуаль рисуются здесь на любой дальности
            e.wanted = w > 0 || age < e.look.ballTicks();
            // линия, ниже которой место взрыва закрыто рельефом, — над основанием (тела в прорисовке закрывает и глубина)
            double line = e.hidden > 0 ? LIFT + e.hidden : 0;
            sky(e, view, out, age, bx, by, bz, d, line);
            light(e, view, out, lights, age, bx, by, bz, d, w, line);
            if (w <= 0) continue;
            column(e, view, out, age, bx, by, bz, w, line);
        }
    }

    /**
     * Вспышка и огненный шар, пожар в воронке: тело — кадр анимации шара (от вспышки до сажи) и клуб пламени поверх
     * того, что за ним (днём шар на светлом небе виден цветом, а не сложением света), мельче нескольких пикселей — круг
     * своего света ({@link FarSprites#shaped}); свет вокруг — бликом и вуалью. Тело вблизи (доля 1 − w) рисуют частицы,
     * блик и вуаль шара — здесь на любой дальности; пожар вблизи — только частицы.
     */
    private static void light(Event e, FarView view, FarSprites out, FxLights lights, double age, double bx, double by, double bz, double d, double w,
                              double line) {
        Look k = e.look;
        double t = Sight.transmittance(d, view.range());
        if (k.fireball() <= 0 || t < Sight.THRESHOLD) return;
        double r = k.fireball();
        if (age < k.ballTicks()) {
            double u = age / k.ballTicks(), fl = flash(k, age), h = r * (BALL_LIFT + BALL_RISE * u);
            double vis = visible(h, r, line), hidden = 1 - vis * e.open;
            float[] c = glare(fl, u);
            // шар светит и на дым, пыль и шлейфы вокруг — закрыт он от глаза или нет; тающий — своей светящейся долей
            lights.add(bx, by + h, bz, r * growth(u), Sight.adapted(fl + ball(u), view.ambient()) * fade(u), c[0], c[1], c[2]);
            if (vis > 0) {
                out.shaped(bx, by + h, bz, r * growth(u), FxAtlas.fireball((float) u), r * FarSprites.FIREBALL, e.phase,
                        Sight.adapted(fl + ball(u), view.ambient()) * t, t, view.pixel(), c[0], c[1], c[2], vis * w * fade(u), vis * e.open, LIGHT);
                brightest(view, bx, by + h, bz, vis);
            }
            // шар закрыт гребнем или постройкой: его свет рассеивает воздух над краем — зарево там, откуда место было бы
            // видно, пока шар светит; на любой дальности, как блик (вблизи за домом одной вспышки на экране не хватало)
            if (hidden > 0) halo(view, out, bx, by + Math.max(line, h), bz, HALO_SIZE * r, (fl + ball(u)) * hidden, t, c);
        }
        if (w > 0 && age < k.burnTicks()) {
            double flicker = 0.75 + 0.25 * Math.sin(age * 1.9 + e.phase) * Math.sin(age * 0.73 + 2 * e.phase);
            double rad = BURN_SIZE * r, h = 0.6 * rad, vis = visible(h, rad, line);
            if (vis <= 0) return;
            // языки пламени меняются: клуб — новый кадр раз в 3 тика
            FxAtlas.Sprite flame = FxAtlas.fire((int) ((age / 3 + (e.seed & 7)) % 8));
            out.shaped(bx, by + h, bz, rad, flame, rad * FarSprites.FIRE, e.phase, Sight.adapted(BURN * (1 - age / k.burnTicks()) * flicker, view.ambient()) * t,
                    t, view.pixel(), FIRE[0], FIRE[1], FIRE[2], vis * w, vis * w * e.open, LIGHT);
            brightest(view, bx, by + h, bz, vis * w);
        }
    }

    /**
     * Радиус светящегося шара на доле u его жизни против полного — как в его кадре ({@code tools/gen_particles.py}:
     * 0,3 + 0,7·(1 − e^(−u/0,05)), к концу +6 %): вспышка — шар в 0,6 радиуса, за первые проценты жизни он почти весь.
     * Свет шара (блик, вуаль, точка вдали) — от этого радиуса.
     */
    static double growth(double u) {
        double f = (Math.floor(Math.min(u, 0.999) * FxAtlas.FIREBALL_FRAMES) + 0.5) / FxAtlas.FIREBALL_FRAMES;
        return (0.3 + 0.7 * (1 - Math.exp(-f / 0.05))) * (1 + 0.06 * f);
    }

    /** Доля шара на доле u его жизни: со второй половины тает (дальше — дым столба), как частицы вблизи. */
    static double fade(double u) {
        return u <= BALL_FADE ? 1 : Math.max(0, 1 - (u - BALL_FADE) / (1 - BALL_FADE));
    }

    /**
     * Яркость вспышки на age-м тике: держится почти всю свою длину и гаснет к концу — глаз складывает свет 50–100 мс
     * (Блох, Брока–Зульцер), и вспышка в тик короче, чем кажется настоящая.
     */
    static double flash(Look k, double age) {
        double f = age / k.flashTicks();
        return f >= 1 ? 0 : k.flash() * (1 - f * f);
    }

    /** Яркость шара на доле u его жизни: остывает от ~2000 K; ярче дневного неба — первую половину жизни. */
    static double ball(double u) {
        double f = 1 - Math.min(1, u);
        return BALL * f * f * f;
    }

    /** Самое яркое тело кадра — для лога: числа {@link Sight#light} последнего нарисованного (в {@link #LIGHT}). */
    private static void brightest(FarView view, double x, double y, double z, double share) {
        if (LIGHT[2] * share <= BRIGHTEST[3] * BRIGHTEST[4]) return;
        double px = Math.sqrt(x * x + y * y + z * z) * view.pixel();
        BRIGHTEST[0] = LIGHT[0] / px;
        BRIGHTEST[1] = LIGHT[3] / px;
        BRIGHTEST[2] = LIGHT[4] / px;
        BRIGHTEST[3] = LIGHT[2];
        BRIGHTEST[4] = share;
    }

    /**
     * Мягкий свет без тела (зарево закрытого шара) с центром (gx, gy, gz): ореол радиуса rad — светящийся воздух вокруг
     * шара; что ближе него, зарево закрывает ({@link FarSprites#glow}); мельче точки — бледнее (поток тот же). Яркость —
     * {@link #behind}.
     *
     * @param light яркость шара и вспышки против белого экрана, без привыкания глаза
     */
    private static void halo(FarView view, FarSprites out, double gx, double gy, double gz, double rad, double light, double t, float[] c) {
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double floor = Math.max(rad, 0.5 * Sight.MIN_PIXELS * view.pixel() * d), k = rad / floor;
        out.glow(gx, gy, gz, floor, rad, c[0], c[1], c[2], (float) (behind(light, view.ambient(), t) * k * k));
    }

    /**
     * Яркость зарева закрытого шара яркостью light против белого (с воздухом t и привыканием глаза к свету неба ambient):
     * доля {@link #BEHIND} его света, но не ярче вуали видимого шара ({@link Sight#VEIL_PEAK}) — это тот же рассеянный
     * свет. Ночью свет ×1200 ({@link Sight#adapted}), и зарево в полную силу заливало кремовым пол-кадра шире вуали самого
     * шара: закрытый шар светил ярче открытого.
     */
    static double behind(double light, double ambient, double t) {
        return Math.min(Sight.VEIL_PEAK, Sight.adapted(light * BEHIND, ambient) * t);
    }

    /** Цвет света шара и вспышки: пока вспышка ярче остывающего шара — её почти белый, потом — шара ({@link #tint}). */
    private static float[] glare(double flash, double u) {
        float[] c = tint(u);
        float f = (float) (flash / Math.max(1e-9, flash + ball(u)));
        for (int i = 0; i < 3; i++) c[i] += (FLASH[i] - c[i]) * f;
        return c;
    }

    /** Цвет остывающего шара на доле u его жизни: раскалён → оранжевый → тёмно-красный. */
    private static float[] tint(double u) {
        float[] from = u < 0.35 ? BALL_HOT : BALL_MID, to = u < 0.35 ? BALL_MID : BALL_COLD;
        float s = (float) (u < 0.35 ? u / 0.35 : (u - 0.35) / 0.65);
        TINT[0] = from[0] + (to[0] - from[0]) * s;
        TINT[1] = from[1] + (to[1] - from[1]) * s;
        TINT[2] = from[2] + (to[2] - from[2]) * s;
        return TINT;
    }

    /**
     * Зарево на облаках над местом, пока светят вспышка и шар ({@link #cloudGlow}): днём его не видно, ночью низ облаков
     * над взрывом вспыхивает, в дождь (облака сплошные) — сильнее. Вблизи (ближе трёх радиусов пятна) его заменяет вспышка
     * на экране; облаков у измерения нет — нет и зарева.
     */
    private static void sky(Event e, FarView view, FarSprites out, double age, double bx, double by, double bz, double d, double line) {
        Look k = e.look;
        double r = k.fireball();
        if (r <= 0 || age >= Math.max(k.flashTicks(), k.ballTicks()) || Double.isNaN(view.clouds())) return;
        double h = Math.max(SKY_LOW, view.clouds() - e.y), rs = SKY_SPREAD * h, share = e.near ? smoothstep(rs, 3 * rs, d) : 1;
        double gy = by + h, dc = Math.sqrt(bx * bx + gy * gy + bz * bz), t = Sight.transmittance(dc, view.range());
        if (share <= 0 || t < Sight.THRESHOLD) return;
        double vis = visible(h, rs, line);
        if (vis <= 0) return;
        double peak = SKY_PEAK * (SKY_CLEAR + (1 - SKY_CLEAR) * view.rain()) * t * vis * share;
        if (age < k.flashTicks()) {
            out.glow(bx, gy, bz, rs, rs, FLASH[0], FLASH[1], FLASH[2], (float) (peak * cloudGlow(flash(k, age), r, h, view.ambient())));
        }
        if (age < k.ballTicks()) {
            double u = age / k.ballTicks();
            float[] c = tint(u);
            out.glow(bx, gy, bz, rs, rs, c[0], c[1], c[2], (float) (peak * cloudGlow(ball(u), r, h, view.ambient())));
        }
    }

    /**
     * Яркость низа облаков на высоте h над источником яркости b радиуса r против белого экрана, с привыканием глаза
     * ({@link Sight#adapted}), не ярче белого: шар светит силой b·r² (на единицу π), освещённость облаков — сила / h²,
     * их яркость — {@link #CLOUD_ALBEDO}·E/π. Днём это тысячные доли неба, ночью — в сотни раз ярче него (оценка для
     * крылатой ракеты при облаках в 1 км — 7 кд/м² против 0,006–0,06 у ночного неба над городом).
     */
    static double cloudGlow(double b, double r, double h, double ambient) {
        double k = r / h;
        return Math.min(1, Sight.adapted(CLOUD_ALBEDO * b * k * k, ambient));
    }

    /** Столб дыма и пыли: поднимается, расплывается, уходит по ветру; сначала тает низ. */
    private static void column(Event e, FarView view, FarSprites out, double age, double bx, double by, double bz, double w, double line) {
        Look k = e.look;
        double rise = rise(age, k.riseTicks());
        double grey = smoothstep(0, 0.5 * k.life(), age);
        float sr = (float) (DARK[0] + (AGED[0] - DARK[0]) * grey), sg = (float) (DARK[1] + (AGED[1] - DARK[1]) * grey),
                sb = (float) (DARK[2] + (AGED[2] - DARK[2]) * grey);
        double opacity = OPACITY * emerge(k, age) * w;
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
            // свой цвет при свете неба, дымка — в непрозрачности (за клубом — та же дымка, Кошмидер)
            float share = e.dust[i], lit = e.shade[i] * view.ambient();
            float cr = (sr + (e.dustR - sr) * share) * lit, cg = (sg + (e.dustG - sg) * share) * lit, cb = (sb + (e.dustB - sb) * share) * lit;
            out.puff(px, py, pz, POINT[0], e.rot[i] + (float) (age * e.spin[i]), e.tex[i], cr, cg, cb, (float) (a * vis * POINT[1] * t));
        }
    }

    /**
     * С какого тика дым шара и столба проступает: шар тает со второй половины жизни ({@link #BALL_FADE}), и дым — это он
     * же, остывший; раньше он закрыл бы яркий шар. Так же и вблизи ({@code Explosions}).
     */
    public static int smokeFrom(Look k) {
        return (int) Math.ceil(BALL_FADE * k.ballTicks());
    }

    /** Доля столба на age-м тике: проступает, пока тает шар ({@link #smokeFrom}), не быстрее {@link #FADE_IN} тиков. */
    static double emerge(Look k, double age) {
        double from = smokeFrom(k);
        return smoothstep(from, Math.max(from + FADE_IN, k.ballTicks()), age);
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
        String light = BRIGHTEST[4] <= 0 ? "" : String.format(Locale.ROOT, "; ярче всех: ядро %.1f px, пересвет %.0f, блик %.1f px, вуаль %.1f px, "
                + "видно %.2f", BRIGHTEST[0], BRIGHTEST[3], BRIGHTEST[1], BRIGHTEST[2], BRIGHTEST[4]);
        if (lastHeard == null) return EVENTS.isEmpty() ? "" : "взрывов " + EVENTS.size() + light;
        Outdoor.Heard h = lastHeard;
        return String.format(Locale.ROOT, "взрывов %d; звук: %.0f бл, задержка %d т (фронт %.1f т), громкость %.3f, запас %.1f дБ, преграда %.1f дБ, "
                        + "тень %.1f дБ, дождь %.1f дБ, верха %.1f дБ, из них земля %.1f дБ", EVENTS.size(), lastDistance, lastDelay,
                lastDistance / Acoustics.SPEED, h.volume(), h.margin(), h.barrier(), h.shadow(), h.masking(), 20 * Math.log10(Math.max(1e-6, h.highs())),
                -h.ground()) + light;
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
