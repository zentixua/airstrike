package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Vector3f;
import org.joml.Vector4f;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Грибы кадра для объёма ({@code mushroom.glsl}, проходы {@code PallPass}): до {@link #MUSHROOMS} самых крупных на экране
 * подрывов, чья рамка или (днём) тень в кадре, — числа формы ({@link MushroomModel}; у подрыва на воде — и столба воды,
 * {@link WaterModel#column}) в блоках от глаза, вещество и температура, плитка карты тени от солнца, по строке таблицы
 * на гриб; последняя строка — свет чёрного тела по температуре ({@link BlackBody}). Метры модели → блоки мира через
 * {@link Detonation#blocks}, время — секунды модели подрыва.
 */
public final class MushroomField {
    /** Грибов в кадре, не больше. */
    public static final int MUSHROOMS = 4;
    /** Текселей RGBA на гриб ({@code mushroom.glsl}: таблица грибов). */
    public static final int TEXELS = 19;
    /** Узлов света чёрного тела (строка {@link #BB_ROW}): температуры от {@link #BB_FROM} до {@link #BB_TO} K по логарифму. */
    public static final int BB_NODES = 256;
    static final double BB_FROM = 500, BB_TO = 30_000;
    /** Ширина и строки таблицы: грибы и свет чёрного тела. */
    public static final int WIDTH = BB_NODES, ROWS = MUSHROOMS + 1, BB_ROW = MUSHROOMS;
    /**
     * Размах сдвига края клубами — шапка, ножка, юбка ({@code MUSH_*_LOBES}), в единицах клубов части: у шапки — меньшая
     * полуось сечения кольца, у ножки — её радиус на этой высоте, у юбки — меньшая полуось её сечения. Клубы шапки —
     * пузыри в половину её сечения, ножки и юбки — рыхлее. Сдвиг — до ±{@link #LOBES_MAX} размаха, в блоках (одинаково
     * во все стороны: клуб круглый, а не вытянут вдоль большей полуоси).
     */
    static final double LOBES_CAP = 0.4, LOBES_STEM = 0.4, LOBES_SKIRT = 0.8;
    /** Мягкость края — шапка, ножка, юбка ({@code MUSH_*_EDGE}), в единицах клубов. */
    static final double EDGE_CAP = 0.07, EDGE_STEM = 0.2, EDGE_SKIRT = 0.25;
    /** Наибольший сдвиг края клубами, доля размаха: крупный шум ±0,5 и мелкий ±0,175 ({@code mushLobes}). */
    static final double LOBES_MAX = 0.68;
    /** Сглаживание объединения кольца и купола ({@code MUSH_BLEND}), в единицах клубов шапки: мягкий минимум опускает край не больше чем на четверть его. */
    static final double BLEND = 0.25;
    /**
     * Докуда за край без клубов доходит вещество — шапка (с мягким минимумом), ножка, юбка ({@code MUSH_*_REACH}), в
     * единицах клубов: сдвиг клубами и мягкость края. Рамка гриба — по ним.
     */
    static final double REACH_CAP = LOBES_MAX * LOBES_CAP + EDGE_CAP + BLEND / 4, REACH_STEM = LOBES_MAX * LOBES_STEM + EDGE_STEM,
            REACH_SKIRT = LOBES_MAX * LOBES_SKIRT + EDGE_SKIRT;
    /**
     * Столб воды ({@code MUSH_COLUMN_*}): размах клубов и мягкость края в единицах толщины стенки — струи и купола
     * короны сдвигают её поверхности на ±0,4 толщины, не пробивая стенку; докуда доходит вещество — с мягким минимумом
     * стенки и короны.
     */
    static final double LOBES_COLUMN = 0.6, EDGE_COLUMN = 0.15, REACH_COLUMN = LOBES_MAX * LOBES_COLUMN + EDGE_COLUMN + BLEND / 4;
    /**
     * Толща стенки столба поперёк (σ' после дельта-Эддингтона, как у пелены): у «Бейкера» в стенке ~10⁶ т воды на
     * ~3·10⁸ м³ — килограммы капель на кубометр, толща в сотни. Здесь — как у зрелой шапки: стенка непрозрачна, а свет
     * в ней считается без переполнения. Убывает с водой, что ещё не упала.
     */
    static final double COLUMN_TAU = 40;
    /** Асимметрия рассеяния капель столба — как у водяной пыли пелены. */
    static final double G_WATER = 0.85;
    /** Плитка клубов столба — доля его радиуса до сужения: струи стенки и купола короны шириной в толщину стенки. */
    static final double COLUMN_TILE = 1.2;
    /**
     * Цвет толстого слоя (на экране; альбедо — по нему, как у пелены): шапка молодая — бурый дым с NO₂, остывшая —
     * белёсый конденсат; ножка — пыль с дымом; юбка — пыль земли (как стена пыли); пар — у подрыва над водой.
     */
    static final int CAP_YOUNG = 0x8C5A46, CAP_OLD = 0xC9C4BE, STEM = 0x8E7F70, SKIRT = 0x9C8A74, STEAM = 0xD9D9D6;
    /** Асимметрия рассеяния пыли и дыма гриба — как у пелены. */
    static final double G = 0.6;
    /**
     * Карта тени от солнца ({@code mushroom.glsl}: {@code MUSH_SHADOW}): плитка {@link #SHADOW}² текселей на гриб в плоскости
     * поперёк солнца; ниже высоты {@link #SHADOW_LOW} (синус) солнце кладёт тень как с неё ({@code MUSH_SHADOW_LOW}).
     */
    public static final int SHADOW = 128;
    static final double SHADOW_LOW = 0.05;
    /** Запас плитки тени за проекцию рамки, текселей с каждой стороны: крайние прямые проходят мимо гриба. */
    static final int SHADOW_PAD = 2;
    /** Тексель строки гриба с плиткой карты тени (сторона 0 — тени нет) и с верхом мира над землёй под эпицентром. */
    public static final int SHADOW_RECT = 17, SHADOW_TOP = 18;

    private static final float[] BB = blackBody();
    private static final double[] RGB = new double[3], DRIFT = new double[2], RAYS = new double[3], AXIS_A = new double[3],
            AXIS_B = new double[3], RECT = new double[4];
    private static final Vector3f LO = new Vector3f(), HI = new Vector3f();
    private static final int[] ORDER = new int[MUSHROOMS];
    private static final Map<Detonation, MushroomModel.Churn> CHURN = new WeakHashMap<>();
    /** Подрывы строк кадра ({@link #frame}): свет пожаров под ними пишет {@link #fire} после света кадра. */
    private static final ClientNuclear.Active[] ROW = new ClientNuclear.Active[MUSHROOMS];
    private static final double[] SIZE = new double[MUSHROOMS];
    /**
     * Плоскости пирамиды видимости, кроме дальней (дальняя плоскость — 4 прорисовки, а гриб виден за десятки километров).
     */
    private static final int SIDES_AND_NEAR = FrustumIntersection.PLANE_MASK_NX | FrustumIntersection.PLANE_MASK_PX
            | FrustumIntersection.PLANE_MASK_NY | FrustumIntersection.PLANE_MASK_PY | FrustumIntersection.PLANE_MASK_NZ;

    private MushroomField() {}

    /** Свет чёрного тела по узлам таблицы: rgb и яркость Y — в единицах кадра (белая стена в полдень — 1). */
    private static float[] blackBody() {
        float[] row = new float[BB_NODES * 4];
        double[] c = new double[4];
        for (int i = 0; i < BB_NODES; i++) {
            BlackBody.light(nodeKelvin(i), c);
            for (int k = 0; k < 4; k++) row[4 * i + k] = (float) c[k];
        }
        return row;
    }

    /** Температура узла i таблицы, K. */
    static double nodeKelvin(int i) {
        return BB_FROM * Math.pow(BB_TO / BB_FROM, i / (double) (BB_NODES - 1));
    }

    /** Строка света чёрного тела в таблицу {@code into} (WIDTH × ROWS × 4): она не меняется, грузится раз. */
    public static void blackBodyRow(float[] into) {
        System.arraycopy(BB, 0, into, BB_ROW * WIDTH * 4, BB.length);
    }

    /**
     * Грибы кадра в строки таблицы {@code into} (строки 0..{@link #MUSHROOMS}−1, по {@link #TEXELS} текселей; строка без
     * гриба — нули): самые крупные на экране, чья рамка в пирамиде видимости ({@code frustum} — от глаза, без дальней
     * плоскости) или, днём, тень на мире в ней. {@code airSigma} — ослабление воздуха по погоде мира, 1/м ({@code Sight}:
     * дымка вдали); {@code sun} — направление на солнце и сила дня ({@code PallSun}), {@code bottom}, {@code top} — низ
     * и верх мира (y): тень ложится на всё, что между ними. Возвращает число грибов.
     */
    public static int frame(List<ClientNuclear.Active> all, Vec3 eye, float partial, FrustumIntersection frustum, double airSigma, Vector4f sun,
                            double bottom, double top, float[] into) {
        for (int k = 0; k < MUSHROOMS; k++) java.util.Arrays.fill(into, k * WIDTH * 4, k * WIDTH * 4 + TEXELS * 4, 0f);
        shadowSun(sun.x, sun.y, sun.z, RAYS);
        shadowAxes(RAYS, AXIS_A, AXIS_B);
        boolean day = sun.w > 0;
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            ClientNuclear.Active a = all.get(i);
            MushroomModel.Shape s = shape(a, partial);
            if (s == null) continue;
            Detonation d = a.d;
            double size = d.blocks(s.capR()) / Math.max(1, Math.hypot(Math.hypot(d.burst().x - eye.x, d.burst().z - eye.z), d.groundY() + d.blocks(s.capY()) - eye.y));
            if (!inView(a, s, eye, day ? RAYS : null, bottom, frustum)) continue;
            // по угловому размеру: на месте самого мелкого, если этот крупнее
            int at = n < MUSHROOMS ? n++ : -1;
            if (at < 0) {
                int min = 0;
                for (int k = 1; k < MUSHROOMS; k++) if (SIZE[k] < SIZE[min]) min = k;
                if (SIZE[min] >= size) continue;
                at = min;
            }
            ORDER[at] = i;
            SIZE[at] = size;
        }
        for (int k = 0; k < MUSHROOMS; k++) {
            ClientNuclear.Active a = k < n ? all.get(ORDER[k]) : null;
            ROW[k] = a;
            if (a != null) write(a, shape(a, partial), eye, partial, airSigma, day, top, into, k * WIDTH * 4);
        }
        return n;
    }

    /**
     * Свет пожаров под грибами кадра (после {@link #frame} и света кадра: {@code f.sun}, {@code f.fireLight}) — тексель
     * 12 (rgb, часы кипения в нём остаются), у земли, на экране, как зарево снизу у пелены: зарево шторма
     * ({@link FireStorms#glow}: доверие модели огня, день и ночь) на среднее пламя зоны пожаров по карте огня (вода и не
     * сгоревшее не светят), а пока карте не доверяют — зарево мглы по таблице, как её строка у слота пелены
     * ({@code FireStorms.slot}: × (1 − доверие)). Выше — свет диска пожаров и дым шторма под точкой ({@code mushAt}).
     */
    static void fire(float partial, PallPass.Frame f) {
        double night = 1 - f.sun.w;
        for (int k = 0; k < MUSHROOMS; k++) {
            ClientNuclear.Active a = ROW[k];
            if (a == null) continue;
            // карта огня на этот кадр: шторм без слота пелены (третий подрыв) сам её не обновляет
            a.storm.refresh(a.ticks(partial));
            double gate = a.storm.gate(), storm = FireStorms.glow(gate, night) * a.storm.meanFlame();
            double table = (1 - gate) * a.pall.fireBelow(a.ticks(partial));
            // w текселя — часы кипения шапки (write): их не трогать, иначе шапка встанет
            int o = k * WIDTH * 4;
            float churn = f.mushrooms[o + 4 * 12 + 3];
            put(f.mushrooms, o, 12, storm * FireStorms.FIRE_R + table * f.fireLight.x, storm * FireStorms.FIRE_G + table * f.fireLight.y,
                    storm * FireStorms.FIRE_B + table * f.fireLight.z, churn);
        }
    }

    /** Гриб подрыва в момент кадра; null — его ещё нет или он рассеялся. */
    static MushroomModel.Shape shape(ClientNuclear.Active a, float partial) {
        Detonation d = a.d;
        double t = a.seconds(partial);
        // докуда по земле дошёл фронт (юбка — пыль у основания ножки, не дальше него)
        double front = d.metres(d.frontRadius(a.ticks(partial)));
        double hob = Math.max(0, d.hobMetres());
        return MushroomModel.shape(t, d.yieldKt(), hob, d.surface(), Math.sqrt(Math.max(0, front * front - hob * hob)));
    }

    /**
     * Середина шапки (у шара — его середина) в мире, блоки: x, y, z — в {@code out}; ось гриба на её высоте снесена
     * ветром на свою долю ({@code mushLocal}). Источник света шара ({@link MushroomModel#light}).
     */
    static void centre(ClientNuclear.Active a, MushroomModel.Shape s, double[] out) {
        Detonation d = a.d;
        double[] w = DRIFT;
        drift(d, s.t(), w);
        double share = MushroomModel.driftShare(d.blocks(s.capY()), Math.max(d.blocks(s.endTop()), 1));
        out[0] = d.burst().x + w[0] * share;
        out[1] = d.groundY() + d.blocks(s.capY());
        out[2] = d.burst().z + w[1] * share;
    }

    /** Снос ветром на всю долю, блоки: x, z — в {@code out}. */
    private static void drift(Detonation d, double t, double[] out) {
        double w = d.blocks(d.windSpeed() * t);
        out[0] = Math.cos(d.windDir()) * w;
        out[1] = Math.sin(d.windDir()) * w;
    }

    /**
     * Радиус рамки гриба вокруг его оси, м (без сноса): докуда доходит вещество шапки, ножки и юбки с клубами. Край части
     * с клубами — где её мера не больше 1 + {@code REACH}·единица/меньшая полуось ({@code mushGap}: мера растёт не
     * быстрее расстояния, делённого на меньшую полуось). Ножка шире всего у земли или у шапки ({@link MushroomModel#stemRadius}
     * выпукла).
     */
    static double reach(MushroomModel.Shape s) {
        double cap = 0;
        if (s.capTau() > 0) {
            double u = capUnit(s);
            cap = Math.max(s.ringR() + s.ringA() * (1 + REACH_CAP * u / Math.min(s.ringA(), s.ringB())),
                    s.domeR() * (1 + REACH_CAP * u / Math.min(s.domeR(), s.domeH())));
        }
        double stem = s.stemTau() > 0
                ? (1 + REACH_STEM) * Math.max(MushroomModel.stemRadius(0, s.stemR(), s.lowDust()), MushroomModel.stemRadius(1, s.stemR(), s.lowDust())) : 0;
        double skirt = s.skirtTau() > 0 ? s.skirtR() + s.skirtW() * (1 + REACH_SKIRT * skirtUnit(s) / Math.min(s.skirtW(), s.skirtH())) : 0;
        return Math.max(1, Math.max(cap, Math.max(stem, skirt)));
    }

    /** Верх рамки гриба над землёй под эпицентром, м: шапка с куполом, ножка, юбка. */
    static double top(MushroomModel.Shape s) {
        double u = capUnit(s);
        double cap = Math.max(s.capY() + s.ringB() * (1 + REACH_CAP * u / Math.min(s.ringA(), s.ringB())),
                s.domeY() + s.domeH() * (1 + REACH_CAP * u / Math.min(s.domeR(), s.domeH())));
        double skirt = s.skirtTau() > 0 ? s.skirtH() * (1 + REACH_SKIRT * skirtUnit(s) / Math.min(s.skirtW(), s.skirtH())) : 0;
        return Math.max(1, Math.max(cap, Math.max(s.stemTop(), skirt)));
    }

    /** Столб воды подрыва в момент гриба; null — подрыв не «водяной», столб ещё не встал или упал. */
    @Nullable
    static WaterModel.Column column(ClientNuclear.Active a, MushroomModel.Shape s) {
        return a.water.wet() > 0 ? WaterModel.column(s.t(), a.d.yieldKt()) : null;
    }

    /**
     * Радиус рамки со столбом воды, м: стенка и корона с клубами — как у частей гриба, по мере эллипса сечения кольца
     * короны ({@code mushGap}); стенка — по расстоянию.
     */
    static double reach(MushroomModel.Shape s, @Nullable WaterModel.Column c) {
        if (c == null) return reach(s);
        double r = REACH_COLUMN * columnUnit(c), a = c.crownA();
        return Math.max(reach(s), Math.max(c.mid() + c.half() + r, c.mid() + a * (1 + r / Math.min(a, c.crownH()))));
    }

    /** Верх рамки со столбом воды над водой, м. */
    static double top(MushroomModel.Shape s, @Nullable WaterModel.Column c) {
        if (c == null) return top(s);
        double r = REACH_COLUMN * columnUnit(c);
        return Math.max(top(s), c.wallTop() + c.crownH() * (1 + r / Math.min(c.crownA(), c.crownH())));
    }

    /** Единица клубов столба, м ({@code mushColumnUnit}): толщина стенки. */
    static double columnUnit(WaterModel.Column c) {
        return 2 * c.half();
    }

    /**
     * Радиус рамки ({@code mushSpan}): цилиндр вдоль оси, наклонённой сносом {@code drift} на конец подъёма {@code end}.
     * Доля сноса растёт с высотой только до {@code end} ({@code mushShare}), выше верха и под землёй (до 0,05 верха) ось
     * гриба не наклоняется дальше, а наклонная — уходит: рамка на столько шире. Все величины — в одних единицах.
     */
    static double boundRadius(double reach, double drift, double top, double end) {
        return reach + drift * Math.max(0.05 * top, top - end) / end;
    }

    /** Единица клубов шапки, м ({@code mushCapUnit}): меньшая полуось сечения кольца. */
    static double capUnit(MushroomModel.Shape s) {
        return Math.min(s.ringA(), s.ringB());
    }

    /** Единица клубов юбки, м ({@code mushSkirtUnit}): меньшая полуось её сечения. */
    static double skirtUnit(MushroomModel.Shape s) {
        return Math.min(s.skirtW(), s.skirtH());
    }

    /**
     * Рамка гриба в пирамиде видимости; с {@code rays} (днём: направление тени, {@link #shadowSun}) — и его тень: рамка,
     * протянутая от солнца до низа мира {@code bottom} (y) — тень гриба лежит на земле и тогда, когда сам он за кадром
     * (смотришь вниз, стоя в тени).
     */
    private static boolean inView(ClientNuclear.Active a, MushroomModel.Shape s, Vec3 eye, double[] rays, double bottom, FrustumIntersection frustum) {
        Detonation d = a.d;
        double[] w = DRIFT;
        drift(d, s.t(), w);
        WaterModel.Column c = column(a, s);
        double r = d.blocks(reach(s, c)) + 0.5 * Math.hypot(w[0], w[1]);
        float cx = (float) (d.burst().x + 0.5 * w[0] - eye.x), cz = (float) (d.burst().z + 0.5 * w[1] - eye.z), ground = (float) (d.groundY() - eye.y);
        LO.set((float) (cx - r), ground - 4, (float) (cz - r));
        HI.set((float) (cx + r), (float) (ground + d.blocks(top(s, c))), (float) (cz + r));
        if (rays != null) {
            // верх рамки опускается вдоль солнца до низа мира: по горизонтали — на высоту, делённую на тангенс высоты солнца
            double fall = (HI.y - (bottom - eye.y)) / rays[1];
            float sx = (float) (-rays[0] * fall), sz = (float) (-rays[2] * fall);
            LO.set(Math.min(LO.x, LO.x + sx), (float) Math.min(LO.y, bottom - eye.y), Math.min(LO.z, LO.z + sz));
            HI.set(Math.max(HI.x, HI.x + sx), HI.y, Math.max(HI.z, HI.z + sz));
        }
        return frustum.intersectAab(LO, HI, SIDES_AND_NEAR) < 0;
    }

    /**
     * Направление на солнце для тени ({@code mushShadowSun}), единичное: высота (синус) не ниже {@link #SHADOW_LOW},
     * по горизонтали — как у солнца. В {@code out}.
     */
    static void shadowSun(double x, double y, double z, double[] out) {
        double e = Math.clamp(y, SHADOW_LOW, 1), h = Math.hypot(x, z);
        if (h < 1e-6) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        double c = Math.sqrt(1 - e * e) / h;
        out[0] = x * c;
        out[1] = e;
        out[2] = z * c;
    }

    /** Оси плоскости поперёк направления {@code s} ({@code mushShadowAxes}): a — поперёк s и оси z мира, b — s × a. */
    static void shadowAxes(double[] s, double[] a, double[] b) {
        double cx = s[1], cy = -s[0], cz = 0;
        if (cx * cx + cy * cy < 0.01) {
            cx = 0;
            cy = s[2];
            cz = -s[1];
        }
        double l = Math.sqrt(cx * cx + cy * cy + cz * cz);
        a[0] = cx / l;
        a[1] = cy / l;
        a[2] = cz / l;
        b[0] = s[1] * a[2] - s[2] * a[1];
        b[1] = s[2] * a[0] - s[0] * a[2];
        b[2] = s[0] * a[1] - s[1] * a[0];
    }

    /**
     * Свет солнца над точкой на высоте h над землёй под эпицентром по трём слоям карты тени ({@code mushShadowLayer}):
     * над землёй под эпицентром t0, над серединой мира t1, над его верхом t2 ({@code top} — верх мира над той же землёй).
     */
    static double shadowLayer(double h, double top, double t0, double t1, double t2) {
        double x = Math.clamp(h / Math.max(top, 1), 0, 1);
        return x < 0.5 ? t0 + (t1 - t0) * 2 * x : t1 + (t2 - t1) * (2 * x - 1);
    }

    /**
     * Полуширина облака поперёк солнца ({@code mushAcross}): радиус круга той же площади, что тень столба радиуса r
     * и высоты h на плоскость поперёк солнца на высоте {@code mu} (синус) — πr²·μ сверху и 2rh·√(1 − μ²) сбоку.
     */
    static double across(double r, double h, double mu) {
        return Math.sqrt(r * (r * mu + 2 / Math.PI * h * Math.sqrt(Math.max(1 - mu * mu, 0))));
    }

    /**
     * Доля рассеянного облаком вперёд света, что доходит до горизонтальной земли, в долях пучка на неё (косинус пучка —
     * {@code mu}), против слоя без края ({@code mushSeen}): облако полушириной w поперёк на расстоянии d по прямой
     * к солнцу видно кругом с долей v = w²/(w² + d²) потока полушария; на землю — v·(μ + (1 − μ)·v/2) (у бесконечного
     * слоя (1 + μ)/2, у узкого — как пучок, v·μ).
     */
    static double seen(double w, double d, double mu) {
        double v = w * w / Math.max(w * w + d * d, 1e-6);
        return v * (mu + (1 - mu) * 0.5 * v) / mu;
    }

    /**
     * Доля света солнца, что вещество гриба пропускает к слою карты тени ({@code through} в {@code fx_mush_shadow}):
     * прямой пучок сквозь толщу {@code tau} и рассеянный ею вперёд (два потока в столбе вдоль солнца, альбедо {@code w},
     * асимметрия {@code g} после усечения пика) с долей {@link #seen} — облако полушириной {@code width} с серединой
     * в {@code d} от слоя.
     */
    static double shadowThrough(double tau, double d, double width, double w, double g, double mu) {
        if (tau < 1e-4) return 1;
        double scattered = Math.max(PallLight.diffuse(w, g, tau, tau, 0, 1, 1, 1, 0, 0, 0, -2.0 / 3), 0);
        return Math.exp(-tau) + scattered * seen(width, d, mu);
    }

    /**
     * Плитка карты тени гриба в плоскости поперёк солнца (оси {@code a}, {@code b}, {@link #shadowAxes}) от эпицентра:
     * проекция рамки ({@code mushLine}: цилиндр радиуса {@code boundR} вокруг оси, наклонённой сносом {@code dx}, {@code dz}
     * на высоту {@code end}, от −0,05 верха {@code top} до верха) с запасом {@link #SHADOW_PAD} текселей с каждой стороны.
     * В {@code out}: начало u, v и стороны. Все величины — в блоках.
     */
    static void shadowRect(double[] a, double[] b, double boundR, double dx, double dz, double top, double end, double[] out) {
        double kx = dx / Math.max(end, 1), kz = dz / Math.max(end, 1), lo = -0.05 * top;
        for (int i = 0; i < 2; i++) {
            double[] e = i == 0 ? a : b;
            // ось — по прямой от низа к верху: её проекция крайняя на концах; круг сечения — ± радиус × горизонталь оси плоскости
            double p0 = e[0] * kx * lo + e[1] * lo + e[2] * kz * lo, p1 = e[0] * kx * top + e[1] * top + e[2] * kz * top;
            double r = boundR * Math.hypot(e[0], e[2]);
            double min = Math.min(p0, p1) - r, size = Math.max(Math.abs(p1 - p0) + 2 * r, 1);
            double padded = size * SHADOW / (SHADOW - 2.0 * SHADOW_PAD);
            out[i] = min - SHADOW_PAD * padded / SHADOW;
            out[2 + i] = padded;
        }
    }

    /**
     * Гриб в строку таблицы с {@code o} ({@code mushroom.glsl}: таблица грибов); днём ({@code day}) — и плитка его карты
     * тени, верхний слой которой — верх мира {@code worldTop} (y).
     */
    private static void write(ClientNuclear.Active a, MushroomModel.Shape s, Vec3 eye, float partial, double airSigma, boolean day, double worldTop,
                              float[] into, int o) {
        Detonation d = a.d;
        double t = s.t(), y = d.yieldKt();
        double[] w = DRIFT;
        drift(d, t, w);
        WaterModel.Column c = column(a, s);
        double top = d.blocks(top(s, c)), end = Math.max(d.blocks(s.endTop()), 1);
        double boundR = boundRadius(d.blocks(reach(s, c)), Math.hypot(w[0], w[1]), top, end);
        double ringA = d.blocks(s.ringA()), ringB = d.blocks(s.ringB());
        // ослабление шапки: толща поперёк меньшего поперечника сечения кольца
        double across = 2 * Math.min(ringA, ringB);
        double capSigma = s.capTau() / Math.max(across, 1);
        double skirtSigma = s.skirtTau() / Math.max(2 * d.blocks(Math.min(s.skirtW(), s.skirtH())), 1);
        double tCore = MushroomModel.coreTemperature(t, y, d.surface());
        double wet = a.water.wet();
        put(into, o, 0, d.burst().x - eye.x, d.burst().z - eye.z, d.groundY() - eye.y, 1);
        put(into, o, 1, w[0], w[1], d.blocks(s.endTop()), boundR);
        put(into, o, 2, d.blocks(s.capY()), d.blocks(s.capR()), d.blocks(s.ringR()), top);
        put(into, o, 3, ringA, ringB, d.blocks(s.domeY()), d.blocks(s.domeR()));
        // плитка клубов шапки — два меньших поперечника сечения: клуб — пузырь в полсечения
        put(into, o, 4, d.blocks(s.domeH()), capSigma, Math.max(across, 1), t);
        int cap = mix(mix(CAP_YOUNG, CAP_OLD, MushroomModel.condensed(t)), STEAM, 0.45 * wet);
        matter(into, o, 5, cap);
        put(into, o, 6, d.blocks(s.stemTop()), d.blocks(s.stemR()), s.lowDust(), s.stemTau());
        matter(into, o, 7, mix(STEM, STEAM, 0.8 * wet));
        put(into, o, 8, d.blocks(s.skirtR()), d.blocks(s.skirtW()), d.blocks(s.skirtH()), skirtSigma);
        matter(into, o, 9, mix(SKIRT, CloudPuffs.SPRAY, a.water.under()));
        // воздух ножки поднимается с подъёмом шара (втягивается кольцом): клубы — вверх
        put(into, o, 10, MushroomModel.AMBIENT_K, tCore - MushroomModel.AMBIENT_K, d.blocks(s.capBottom()), Math.max(1, 3 * d.blocks(s.stemR())));
        // пожары под грибом: верх дыма шторма и радиус зоны пожаров (свет — fire, после света кадра); дымка воздуха —
        // по настоящей дальности, как у дальнего (в блоках гриб меньше в effects_scale раз)
        put(into, o, 11, d.blocks(0.3 * FireballModel.riseSpeed(y) * t), a.pall.hazeTop(), a.pall.fireReach(), airSigma / d.scale());
        // часы кипения шапки (свет пожаров под грибом — fire, после света кадра)
        put(into, o, 12, 0, 0, 0, churn(d).at(t));
        // столб воды: ослабление стенки — её толща поперёк на её толщину, × вода, что ещё не упала и уже не пар, × насколько
        // подрыв «водяной»; струи стенки идут с водой — шум по высоте сдвинут на верх столба (вверх, пока растёт, вниз,
        // когда падает)
        if (c != null) {
            double half = d.blocks(c.half()), water = c.water() * WaterModel.columnShow(t, y) * wet;
            put(into, o, 13, d.blocks(c.mid()), half, d.blocks(c.wallTop()), COLUMN_TAU * water / Math.max(2 * half, 1));
            matter(into, o, 14, CloudPuffs.SPRAY, G_WATER);
            put(into, o, 15, d.blocks(c.crownR()), d.blocks(c.crownH()), d.blocks(c.top()), d.blocks(COLUMN_TILE * WaterModel.columnRadius(y)));
        }
        // шар и шапка снаружи: доля перепада ядра у края (у шара — вся) и размах клубов края (у шара — гладкая сфера)
        put(into, o, 16, MushroomModel.edgeShare(t, y, d.surface()), MushroomModel.billows(t, y, d.surface()), 0, 0);
        if (day) {
            // тень от солнца: плитка карты — проекция рамки поперёк солнца; слои — над землёй под эпицентром, серединой
            // и верхом мира
            shadowRect(AXIS_A, AXIS_B, boundR, w[0], w[1], top, end, RECT);
            put(into, o, SHADOW_RECT, RECT[0], RECT[1], RECT[2], RECT[3]);
            put(into, o, SHADOW_TOP, Math.max(worldTop - d.groundY(), 16), 0, 0, 0);
        }
    }

    /** Часы кипения шапки подрыва ({@link MushroomModel#churn}): таблица — одна на подрыв, пока он жив. */
    static MushroomModel.Churn churn(Detonation d) {
        return CHURN.computeIfAbsent(d, k -> MushroomModel.churn(k.yieldKt(), k.hobMetres(), k.surface()));
    }

    /** Вещество по цвету толстого слоя (на экране): альбедо ω' по каналам и асимметрия g' — как у пелены ({@link PallLight}). */
    private static void matter(float[] into, int o, int texel, int rgb) {
        matter(into, o, texel, rgb, G);
    }

    /** То же с асимметрией рассеяния g вещества. */
    private static void matter(float[] into, int o, int texel, int rgb, double asymmetry) {
        double g = PallLight.truncated(asymmetry);
        for (int i = 0; i < 3; i++) RGB[i] = PallLight.albedo(PallLight.linear(((rgb >> (16 - 8 * i)) & 0xFF) / 255.0), g);
        put(into, o, texel, RGB[0], RGB[1], RGB[2], g);
    }

    private static void put(float[] into, int o, int texel, double x, double y, double z, double w) {
        int i = o + 4 * texel;
        into[i] = (float) x;
        into[i + 1] = (float) y;
        into[i + 2] = (float) z;
        into[i + 3] = (float) w;
    }

    /** Смесь цветов RGB: доля f второго. */
    static int mix(int a, int b, double f) {
        double t = Math.max(0, Math.min(1, f));
        int r = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            double c = ((a >> sh) & 0xFF) * (1 - t) + ((b >> sh) & 0xFF) * t;
            r |= (int) Math.round(c) << sh;
        }
        return r;
    }
}
