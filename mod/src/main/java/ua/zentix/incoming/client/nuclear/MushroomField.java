package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Vector3f;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import java.util.List;

/**
 * Грибы кадра для объёма ({@code mushroom.glsl}, проходы {@code PallPass}): до {@link #MUSHROOMS} самых крупных на экране
 * подрывов, чья рамка в кадре, — числа формы ({@link MushroomModel}) в блоках от глаза, вещество и температура, по
 * строке таблицы на гриб; последняя строка — свет чёрного тела по температуре ({@link BlackBody}). Метры модели →
 * блоки мира через {@link Detonation#blocks}, время — секунды модели подрыва.
 */
public final class MushroomField {
    /** Грибов в кадре, не больше. */
    public static final int MUSHROOMS = 4;
    /** Текселей RGBA на гриб ({@code mushroom.glsl}: таблица грибов). */
    public static final int TEXELS = 13;
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
     * Цвет толстого слоя (на экране; альбедо — по нему, как у пелены): шапка молодая — бурый дым с NO₂, остывшая —
     * белёсый конденсат; ножка — пыль с дымом; юбка — пыль земли (как стена пыли); пар — у подрыва над водой.
     */
    static final int CAP_YOUNG = 0x8C5A46, CAP_OLD = 0xC9C4BE, STEM = 0x8E7F70, SKIRT = 0x9C8A74, STEAM = 0xD9D9D6;
    /** Асимметрия рассеяния пыли и дыма гриба — как у пелены. */
    static final double G = 0.6;

    private static final float[] BB = blackBody();
    private static final double[] RGB = new double[3], DRIFT = new double[2];
    private static final Vector3f LO = new Vector3f(), HI = new Vector3f();
    private static final int[] ORDER = new int[MUSHROOMS];
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
     * плоскости). Возвращает число грибов.
     */
    public static int frame(List<ClientNuclear.Active> all, Vec3 eye, float partial, FrustumIntersection frustum, float[] into) {
        for (int k = 0; k < MUSHROOMS; k++) java.util.Arrays.fill(into, k * WIDTH * 4, k * WIDTH * 4 + TEXELS * 4, 0f);
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            ClientNuclear.Active a = all.get(i);
            MushroomModel.Shape s = shape(a, partial);
            if (s == null) continue;
            Detonation d = a.d;
            double size = d.blocks(s.capR()) / Math.max(1, Math.hypot(Math.hypot(d.burst().x - eye.x, d.burst().z - eye.z), d.groundY() + d.blocks(s.capY()) - eye.y));
            if (!inView(a, s, eye, partial, frustum)) continue;
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
            if (a != null) write(a, shape(a, partial), eye, partial, into, k * WIDTH * 4);
        }
        return n;
    }

    /**
     * Свет пожаров под грибами кадра (после {@link #frame} и света кадра: {@code f.sun}, {@code f.fireLight}) — тексель
     * 12, у земли, на экране, как зарево снизу у пелены: зарево шторма ({@link FireStorms#glow}: доверие модели огня,
     * день и ночь) на среднее пламя зоны пожаров по карте огня (вода и не сгоревшее не светят), а пока карте не доверяют —
     * зарево мглы по таблице, как её строка у слота пелены ({@code FireStorms.slot}: × (1 − доверие)). Выше — свет диска
     * пожаров и дым шторма под точкой ({@code mushAt}).
     */
    static void fire(float partial, PallPass.Frame f) {
        double night = 1 - f.sun.w;
        for (int k = 0; k < MUSHROOMS; k++) {
            ClientNuclear.Active a = ROW[k];
            if (a == null) continue;
            double gate = a.storm.gate(), storm = FireStorms.glow(gate, night) * a.storm.meanFlame();
            double table = (1 - gate) * a.pall.fireBelow(a.ticks(partial));
            put(f.mushrooms, k * WIDTH * 4, 12, storm * FireStorms.FIRE_R + table * f.fireLight.x, storm * FireStorms.FIRE_G + table * f.fireLight.y,
                    storm * FireStorms.FIRE_B + table * f.fireLight.z, 0);
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
        double skirt = s.skirtH() * (1 + REACH_SKIRT * skirtUnit(s) / Math.min(s.skirtW(), s.skirtH()));
        return Math.max(1, Math.max(cap, Math.max(s.stemTop(), skirt)));
    }

    /** Единица клубов шапки, м ({@code mushCapUnit}): меньшая полуось сечения кольца. */
    static double capUnit(MushroomModel.Shape s) {
        return Math.min(s.ringA(), s.ringB());
    }

    /** Единица клубов юбки, м ({@code mushSkirtUnit}): меньшая полуось её сечения. */
    static double skirtUnit(MushroomModel.Shape s) {
        return Math.min(s.skirtW(), s.skirtH());
    }

    private static boolean inView(ClientNuclear.Active a, MushroomModel.Shape s, Vec3 eye, float partial, FrustumIntersection frustum) {
        Detonation d = a.d;
        double[] w = DRIFT;
        drift(d, s.t(), w);
        double r = d.blocks(reach(s)) + 0.5 * Math.hypot(w[0], w[1]);
        float cx = (float) (d.burst().x + 0.5 * w[0] - eye.x), cz = (float) (d.burst().z + 0.5 * w[1] - eye.z), ground = (float) (d.groundY() - eye.y);
        LO.set((float) (cx - r), ground - 4, (float) (cz - r));
        HI.set((float) (cx + r), (float) (ground + d.blocks(top(s))), (float) (cz + r));
        return frustum.intersectAab(LO, HI, SIDES_AND_NEAR) < 0;
    }

    /** Гриб в строку таблицы с {@code o} ({@code mushroom.glsl}: таблица грибов). */
    private static void write(ClientNuclear.Active a, MushroomModel.Shape s, Vec3 eye, float partial, float[] into, int o) {
        Detonation d = a.d;
        double t = s.t(), y = d.yieldKt();
        double[] w = DRIFT;
        drift(d, t, w);
        double boundR = d.blocks(reach(s)) + 0.5 * Math.hypot(w[0], w[1]);
        double ringA = d.blocks(s.ringA()), ringB = d.blocks(s.ringB());
        // ослабление шапки: толща поперёк меньшего поперечника сечения кольца
        double across = 2 * Math.min(ringA, ringB);
        double capSigma = s.capTau() / Math.max(across, 1);
        double skirtSigma = s.skirtTau() / Math.max(2 * d.blocks(Math.min(s.skirtW(), s.skirtH())), 1);
        double tCore = MushroomModel.coreTemperature(t, y, d.surface());
        double wet = a.puffs.burstWet();
        put(into, o, 0, d.burst().x - eye.x, d.burst().z - eye.z, d.groundY() - eye.y, 1);
        put(into, o, 1, w[0], w[1], d.blocks(s.endTop()), boundR);
        put(into, o, 2, d.blocks(s.capY()), d.blocks(s.capR()), d.blocks(s.ringR()), d.blocks(top(s)));
        put(into, o, 3, ringA, ringB, d.blocks(s.domeY()), d.blocks(s.domeR()));
        // плитка клубов шапки — два меньших поперечника сечения: клуб — пузырь в полсечения
        put(into, o, 4, d.blocks(s.domeH()), capSigma, Math.max(across, 1), t);
        int cap = mix(mix(CAP_YOUNG, CAP_OLD, MushroomModel.condensed(t)), STEAM, 0.45 * wet);
        matter(into, o, 5, cap);
        put(into, o, 6, d.blocks(s.stemTop()), d.blocks(s.stemR()), s.lowDust(), s.stemTau());
        matter(into, o, 7, mix(STEM, STEAM, 0.8 * wet));
        put(into, o, 8, d.blocks(s.skirtR()), d.blocks(s.skirtW()), d.blocks(s.skirtH()), skirtSigma);
        matter(into, o, 9, mix(SKIRT, CloudPuffs.SPRAY, a.puffs.burstUnder()));
        // воздух ножки поднимается с подъёмом шара (втягивается кольцом): клубы — вверх
        put(into, o, 10, MushroomModel.AMBIENT_K, tCore - MushroomModel.AMBIENT_K, d.blocks(s.capBottom()), Math.max(1, 3 * d.blocks(s.stemR())));
        // пожары под грибом: верх дыма шторма и радиус зоны пожаров (свет — fire, после света кадра)
        put(into, o, 11, d.blocks(0.3 * FireballModel.riseSpeed(y) * t), a.pall.hazeTop(), a.pall.fireReach(), 0);
    }

    /** Вещество по цвету толстого слоя (на экране): альбедо ω' по каналам и асимметрия g' — как у пелены ({@link PallLight}). */
    private static void matter(float[] into, int o, int texel, int rgb) {
        double g = PallLight.truncated(G);
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
