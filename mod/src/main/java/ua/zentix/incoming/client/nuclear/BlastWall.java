package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Стена пыли ударной волны (DESIGN-nuke §6): что поднимает ветер за фронтом. Сам фронт не виден — это скачок давления
 * в прозрачном воздухе; за ним воздух дует от эпицентра (Ренкин–Гюгонио, {@link BlastModel#windSpeed}: 72 м/с при 5 psi,
 * 17 м/с при 1 psi) и срывает то, что лежит на земле: пыль и грунт, обломки, дым того, что уже горит от светового
 * импульса, над водой — брызги с гребней ({@link WaterModel#spray}). Своего вещества у волны нет.
 * <p>
 * Пыль встаёт не на самом фронте: ветру нужно время её сорвать и поднять ({@link #LIFT_S}), передний край пыли идёт за
 * фронтом на его путь за это время, брызги — ещё дальше ({@link WaterModel#SPRAY_LAG}). Сколько пыли — по ветру
 * ({@link #lift}): густота растёт как квадрат скорости сверх порога срыва, стена непрозрачна от штормового ветра, а у
 * порога (~5 м/с, ~0,3 psi) её нет. Голова стены — вал, как у пыльной бури (хабуба) или струи вдоль стенки: у земли
 * воздух за фронтом дует быстрее, чем выше, у переднего края пыль идёт вверх, поверху — назад ({@link #rollPhase}).
 * Голова — то, где пыль поднимается сейчас, она идёт с фронтом; поднятая пыль за ней остаётся хвостом головы и пеленой
 * ({@link PallField}: покрытие по давлению на земле).
 * <p>
 * Высота и вал — законы прежних клубов стены ({@code CloudPuffs}; шаг 4 плана «гриб и шар — объёмом»); стена — объём
 * в проходе пелены ({@code PallPass}: {@code pallWall} в {@code pall.glsl}, числа слота — {@link #slot}). Метры и секунды
 * модели; в мир — через {@link Detonation#blocks}.
 */
public final class BlastWall {
    /**
     * Голова стены в момент: передний край пыли (блоки от эпицентра по земле), её высота (блоки) и густота 0..1 (0 — стены
     * нет: ещё не встала или осела).
     */
    public record Wall(double head, double height, double density) {
        static final Wall NONE = new Wall(0, 0, 0);
    }

    /**
     * За сколько секунд модели после фронта ветер поднимает пыль: передний край пыли идёт за фронтом на его путь за это
     * время. Оценка: сальтация на сухом грунте разгоняется за доли секунды, а видимой стене нужна толща запылённого
     * воздуха; вдали (фронт в игре — 0,42 скорости звука) это ~35 м.
     */
    static final double LIFT_S = 0.25;
    /** Ветер, с которого он срывает пыль с сухого грунта (по шкале Бофорта 4 — «поднимает пыль»), м/с. */
    static final double LIFT_FROM = 5;
    /** Ветер, при котором стена — самая густая пыль пелены (видно на 40 м, как в хабубе), м/с. */
    static final double LIFT_FULL = 20;
    /** Радиус вала — доля высоты стены; у эпицентра — не больше четверти радиуса головы. */
    static final double ROLL = 0.42, ROLL_NEAR = 0.25;
    /** Самый быстрый оборот вала, рад/с: у эпицентра воздух за фронтом — сотни метров в секунду, вал мельтешил бы. */
    static final double ROLL_MAX = 1.5;
    /** Голова отстаёт от фронта (там, где ветер уже не срывает пыль) и расплывается со скоростью столько м/с. */
    static final double SETTLE_SPEED = 12;
    /** Плитка шума клубов головы — доля её высоты: клубы (четверть плитки) — около пятой части высоты. */
    static final double TILE = 0.9;
    /**
     * Клубы головы — две фазы шума (Neyret 2003): у каждой окружность головы в плитках постоянна, пока голова не вырастет
     * в столько раз, потом фаза начинается с новыми клубами, а другая в это время — в полную силу. Иначе клубы на
     * растущем кольце либо скользили бы вдоль него со скоростью фронта, либо растягивались в разы.
     */
    static final double PHASE_GROWTH = 1.25;
    /** Узлов таблицы угла вала по времени. */
    private static final int ROLL_NODES = 256;
    /** Облако Вильсона: середина оболочки — доля радиуса фронта (за фронтом, в фазе разрежения), полутолщина — доля. */
    static final double WILSON_AT = 0.93, WILSON_WIDTH = 0.06;
    /** Толща облака Вильсона поперёк оболочки в самой густой его поре. */
    static final double WILSON_TAU = 0.5;
    /** Чисел слота стены для шейдера ({@link #slot}): голова (mat4) и облако Вильсона (vec4). */
    public static final int SLOT = 20;

    private final Detonation d;
    /** Высота подрыва над землёй, м; мощность — кубическим корнем от 15 кт (масштаб форм). */
    private final double hob, ys;
    /** Докуда голова идёт с фронтом — ветер за ним ещё срывает пыль ({@link #LIFT_FROM}, ~0,3 psi), м по земле. */
    private final double ringStop;
    /** Влажный воздух — облако Вильсона есть ({@link CloudModel#wilsonCloud}). */
    private final boolean humid;
    /** Угол вала по времени ({@link #rollPhase}): секунды модели и угол, рад. */
    private final double[] rollT = new double[ROLL_NODES + 1], rollAngle = new double[ROLL_NODES + 1];

    public BlastWall(Detonation d) {
        this(d, CloudModel.wilsonCloud(humidity(d)));
    }

    BlastWall(Detonation d, boolean humid) {
        this.d = d;
        this.humid = humid;
        hob = Math.max(0, d.hobMetres());
        ys = Math.cbrt(d.yieldKt() / 15);
        double stop = BlastModel.rangeForOverpressure(BlastModel.kpa(0.3), d.yieldKt());
        ringStop = Math.sqrt(Math.max(0, stop * stop - hob * hob));
        // угол вала — интеграл скорости оборота по времени (средней точкой), узлы гуще в начале
        double end = CloudPuffs.lifeSeconds(d);
        for (int i = 1; i <= ROLL_NODES; i++) {
            double f = (double) i / ROLL_NODES;
            rollT[i] = end * f * f;
            rollAngle[i] = rollAngle[i - 1] + rollSpeed((rollT[i] + rollT[i - 1]) * 0.5) * (rollT[i] - rollT[i - 1]);
        }
    }

    /** Влажность в эпицентре (облако Вильсона видно во влажном воздухе): из биома на клиенте, над водой — 1. */
    static double humidity(Detonation d) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return 0.5;
        // блок поверхности: у суши — под первым воздухом, у воды с прицела (гладь, 62,889) — сама вода
        var pos = new net.minecraft.core.BlockPos(Mth.floor(d.burst().x), NukeWater.surfaceBlock(d.groundY()), Mth.floor(d.burst().z));
        if (!level.getFluidState(pos).isEmpty()) return 1;
        return level.getBiome(pos).value().getModifiedClimateSettings().downfall();
    }

    /**
     * Высота стены пыли, м: 300 у центра, ~155 на 1 км и ~60 на 3 км у 15 кт — по приведённой дальности (как и давление),
     * поэтому у любой мощности та же форма.
     */
    static double wallHeight(double groundM, double ys) {
        double x = groundM / (900 * ys);
        return (40 + 260 / (1 + x * x)) * ys;
    }

    /** Радиус вала головы, м, у переднего края пыли на {@code head} м от эпицентра. */
    double rollRadius(double head) {
        return Math.max(1, Math.min(ROLL * wallHeight(head, ys), ROLL_NEAR * head));
    }

    /** Фронт по земле в момент t (секунды модели), м: наклонная дальность фронта в игре — по земле. */
    double shock(double t) {
        if (t <= 0) return 0;
        double front = d.metres(d.frontRadius(t * 20 * d.scale()));
        return Math.sqrt(Math.max(0, front * front - hob * hob));
    }

    /**
     * Передний край пыли по земле в момент t (секунды модели), м: фронт {@link #LIFT_S} назад, пока ветер за ним срывает
     * пыль; дальше голова отстаёт и расплывается ({@link #SETTLE_SPEED}).
     */
    double head(double t) {
        return Math.min(shock(t - LIFT_S), ringStop) + SETTLE_SPEED * settle(t);
    }

    /** Сколько секунд голова уже отстаёт (фронт за ней прошёл место, где ветер ещё срывает пыль). */
    double settle(double t) {
        return shock(t - LIFT_S) >= ringStop ? t - LIFT_S - timeTo(ringStop) : 0;
    }

    /**
     * Скорость оборота вала, рад/с: воздух за фронтом (давление у головы) на радиус вала, не быстрее {@link #ROLL_MAX}.
     * Фронт в игре вдали медленнее настоящего ({@code FrontProfile}), а вал крутит сам воздух у головы — его скорость
     * настоящая.
     */
    double rollSpeed(double t) {
        double head = head(t);
        return Math.min(ROLL_MAX, wind(head) / rollRadius(head));
    }

    /** Ветер сразу за фронтом на {@code groundM} от эпицентра по земле, м/с. */
    double wind(double groundM) {
        return BlastModel.windSpeed(BlastModel.overpressureKpa(Math.max(1, Math.hypot(groundM, hob)), d.yieldKt()));
    }

    /**
     * Сколько пыли поднимает ветер {@code u} (м/с) — доля самой густой 0..1, от {@link #LIFT_FROM} до {@link #LIFT_FULL}:
     * вынос пыли с земли растёт как куб динамической скорости сверх порога срыва (Gillette; Marticorena &amp; Bergametti),
     * а густота в голове, через которую ветер её несёт, — как вынос на скорость: u² − u₀².
     */
    static double lift(double u) {
        return Mth.clamp((u * u - LIFT_FROM * LIFT_FROM) / (LIFT_FULL * LIFT_FULL - LIFT_FROM * LIFT_FROM), 0, 1);
    }

    /** Угол вала в момент t (секунды модели), рад: растёт — у переднего края пыль идёт вверх, поверху — назад. */
    double rollPhase(double t) {
        if (t <= 0) return 0;
        if (t >= rollT[ROLL_NODES]) return rollAngle[ROLL_NODES] + rollSpeed(t) * (t - rollT[ROLL_NODES]);
        int i = Math.min(ROLL_NODES, Math.max(1, (int) Math.ceil(Math.sqrt(t / rollT[ROLL_NODES]) * ROLL_NODES)));
        while (i > 1 && rollT[i - 1] > t) i--;
        while (i < ROLL_NODES && rollT[i] < t) i++;
        double f = (t - rollT[i - 1]) / Math.max(1e-12, rollT[i] - rollT[i - 1]);
        return rollAngle[i - 1] + f * (rollAngle[i] - rollAngle[i - 1]);
    }

    /**
     * Густота головы 0..1 в момент t: сколько пыли поднимает ветер у головы ({@link #lift}); встаёт за полторы секунды
     * после того, как пыль начала подниматься.
     */
    double density(double t) {
        double head = head(t);
        if (head <= 0) return 0;
        return lift(wind(head)) * smooth(LIFT_S, LIFT_S + 1.5, t);
    }

    /** Голова стены в момент t (секунды модели): блоки и густота. */
    public Wall wall(double t) {
        double head = head(t), k = density(t);
        if (head <= 0 || k <= 0) return Wall.NONE;
        return new Wall(d.blocks(head), d.blocks(wallHeight(head, ys)), k);
    }

    /**
     * Числа слота стены для {@code pall.glsl} в момент t (секунды модели), блоки и 1/блок; false — ни головы, ни облака
     * Вильсона (в {@code into} нули). Голова — mat4 по столбцам ({@code pallWall}):
     * <ul>
     *     <li>[0..3] — передний край пыли от эпицентра, радиус вала, верх головы, ослабление в её самой густой части;</li>
     *     <li>[4..7] — угол вала (рад); брызги над водой: отставание от края пыли, доля высоты, ослабление;</li>
     *     <li>[8..11] — две фазы клубов: окружность головы в плитках у каждой, вес первой, плитка шума;</li>
     *     <li>[12..15] — номера фаз (у каждой свои клубы), 0, 0.</li>
     * </ul>
     * [16..19] — облако Вильсона ({@code pallWilson}): радиус середины оболочки от точки подрыва, полутолщина, ослабление в
     * середине (0 — облака нет), высота точки подрыва над землёй под эпицентром.
     */
    public boolean slot(double t, float[] into) {
        java.util.Arrays.fill(into, 0, SLOT, 0f);
        boolean any = false;
        double head = head(t), k = density(t);
        if (head > 0 && k > 0) {
            any = true;
            double a = rollRadius(head), height = wallHeight(head, ys);
            into[0] = (float) d.blocks(head);
            into[1] = (float) d.blocks(a);
            into[2] = (float) d.blocks(height);
            into[3] = (float) (PallField.SIGMA_DENSE / d.scale() * k);
            into[4] = (float) (rollPhase(t) % (2 * Math.PI));
            // над водой — брызги: ниже стены пыли, позади тёмного кольца на путь головы за SPRAY_LAG
            double u = wind(head);
            into[5] = (float) d.blocks(Math.max(0, head - head(Math.max(0, t - WaterModel.SPRAY_LAG))));
            into[6] = (float) Mth.clamp(WaterModel.sprayHeight(u) / Math.max(1, height), 0.15, 1);
            into[7] = (float) (PallField.SIGMA_SPRAY / d.scale() * WaterModel.spray(u) * smooth(LIFT_S, LIFT_S + 1.5, t));
            double psi = Math.log(Math.max(head, 1)) / Math.log(PHASE_GROWTH);
            for (int j = 0; j < 2; j++) {
                double phase = psi + 0.5 * j, n = Math.floor(phase);
                into[8 + j] = (float) around(Math.pow(PHASE_GROWTH, n - 0.5 * j));
                into[12 + j] = (float) (n % 64);
                if (j == 0) into[10] = (float) (1 - Math.abs(2 * (phase - n) - 1));
            }
            into[11] = (float) d.blocks(TILE * height);
        }
        double wilson = wilson(t);
        if (wilson > 0) {
            any = true;
            double front = d.metres(d.frontRadius(t * 20 * d.scale())), w = WILSON_WIDTH * front;
            into[16] = (float) d.blocks(WILSON_AT * front);
            into[17] = (float) d.blocks(w);
            // толща поперёк оболочки — WILSON_TAU в самой густой поре: у гауссовой оболочки ∫ = √π · полутолщина · σ
            into[18] = (float) (WILSON_TAU * wilson / (Math.sqrt(Math.PI) * Math.max(1e-3, d.blocks(w))));
            into[19] = (float) d.blocks(hob);
        }
        return any;
    }

    /**
     * Окружность головы радиуса {@code head} м в плитках шума — целое: плитка повторяется, шва на кольце нет. Фаза клубов
     * держит её, пока голова растёт.
     */
    double around(double head) {
        return Math.max(1, Math.round(2 * Math.PI * head / (TILE * wallHeight(head, ys))));
    }

    /**
     * Облако Вильсона 0..1 в момент t (секунды модели): во влажном воздухе в фазе разрежения за фронтом воздух
     * расширяется и остывает ниже точки росы — оболочка тумана. Встаёт с 0,15·∛(Y/15) с, тает к 3,5·∛(Y/15) с (у 15 кт —
     * 0,15–3,5 с); в сухом воздухе его нет.
     */
    double wilson(double t) {
        if (!humid) return 0;
        return smooth(0.15 * ys, 0.5 * ys, t) * (1 - smooth(1.5 * ys, 3.5 * ys, t));
    }

    /** Докуда по земле от эпицентра (без сноса) может быть стена в момент t, блоки; 0 — стены нет. */
    public double reach(double t) {
        return density(t) > 0 ? d.blocks(head(t)) : 0;
    }

    /** Докуда от точки подрыва может быть облако Вильсона в момент t, блоки; 0 — облака нет. */
    public double wilsonReach(double t) {
        if (wilson(t) <= 0) return 0;
        return d.blocks((WILSON_AT + 2 * WILSON_WIDTH) * d.metres(d.frontRadius(t * 20 * d.scale())));
    }

    /** Клубы выедают огибающую головы с края на эту долю ({@code WALL_BITE} в {@code pall.glsl}). */
    static final double BITE = 0.45;

    /**
     * Насколько точка внутри огибающей головы: 1 — ось вала, 0 — край, меньше — снаружи. {@code s} — за передним краем
     * пыли (блоки; впереди — меньше нуля), {@code z} — над рельефом (блоки), {@code a} — радиус вала, {@code top} — верх
     * головы. Вал — полушириной 1,5·a за краем, во всю высоту: спереди у земли — стоячая стена (пыль встаёт с земли сразу
     * во всю высоту вала), сверху скруглён; за серединой вала хвост ниже, к 0,55 высоты — поднятая пыль, что осталась за
     * головой. Копия {@code pallWallInside}.
     */
    static double inside(double s, double z, double a, double top) {
        double w = 1.5 * a, b = 0.5 * top;
        double x = (s - w) / Math.max(w, 0.001), y = (z - b) / Math.max(b, 0.001);
        if (x >= 0) return 1 - Math.max(y / (1 - 0.9 * smooth(0, 2, x)), 0);
        if (y <= 0) return 1 + x;
        return 1 - Math.sqrt(x * x + y * y);
    }

    /**
     * Хвост и низ головы 0..1: хвост тает с 0,6 до 2 полуширин вала за его серединой (дальше поднятая пыль — пелена,
     * {@link PallField}), под рельефом карты — глубже четверти высоты (карта грубее мира). Копия {@code pallWallFade}.
     */
    static double fade(double s, double z, double a, double top) {
        double w = 1.5 * a;
        return (1 - smooth(0.6, 2, (s - w) / Math.max(w, 0.001))) * smooth(-0.5 * top, -0.25 * top, z);
    }

    /** Доля густоты головы без шума (среднее по клубам). Копия {@code pallWallMean}. */
    static double mean(double s, double z, double a, double top) {
        return Mth.clamp(inside(s, z, a, top) / BITE, 0, 1) * fade(s, z, a, top);
    }

    /**
     * Ослабление средней головы (без клубов) в точке, 1/блок — как {@code pallWallAt} и {@code pallWallMean}: {@code slot}
     * — числа {@link #slot}, {@code relX}, {@code relZ} — точка от эпицентра со сносом пелены (блоки), {@code up} — над
     * рельефом под ней, {@code wet} — доля воды под ней (над водой голова — брызги).
     */
    static double sigma(float[] slot, double relX, double relZ, double up, double wet) {
        if (slot[3] <= 0) return 0;
        double s = slot[0] - Math.hypot(relX, relZ);
        if (s < -1 || s > 4.5 * slot[1] + slot[5] || up > slot[2] || up < -0.5 * slot[2]) return 0;
        double f = 1 + (slot[6] - 1) * wet;
        double a = slot[1] * f, top = slot[2] * f, back = slot[5] * wet, k = slot[3] + (slot[7] - slot[3]) * wet;
        return k * mean(s - back, up, a, top);
    }

    /** Когда фронт дошёл до радиуса {@code groundM} по земле, с модели (фронт — как у сервера, {@link Detonation#arrivalTicks}). */
    private double timeTo(double groundM) {
        return d.arrivalTicks(d.blocks(Math.hypot(groundM, hob))) / (20 * d.scale());
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }
}
