package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Клубы гриба (DESIGN-nuke §6): шапка — тороидальный вихрь (снаружи опускается, в середине поднимается),
 * ножка и пылевая юбка у основания. Шапка и ножка светятся изнутри (накал) — свет клуба поверх его дыма
 * ({@link Sink}). Каждый клуб — случайные, но постоянные параметры (сид подрыва, у всех клиентов одинаковые),
 * а положение, размер и цвет в каждый момент выводятся из {@link CloudModel} и {@link FireballModel}. Метры
 * модели → блоки мира через {@link Detonation#blocks}.
 * <p>
 * Юбка стоит на рельефе под собой, а не на высоте эпицентра: высоту земли под её клубами берёт тик клиента
 * ({@link #track}), кадр ставит клуб между прошлым и этим тиком. Стена пыли ударной волны и облако Вильсона — объёмом
 * в пелене ({@link BlastWall}); здесь — свет шара, шапки и пожаров на них ({@link #pallLight}): им светятся стена, пелена
 * за ней и пыль у камеры ({@code NukePall.dustColor}).
 */
public final class CloudPuffs {
    enum Kind {
        CAP, DOME, STEM, SKIRT;

        /** Пыль у земли: стоит на рельефе под собой. */
        boolean onGround() {
            return this == SKIRT;
        }
    }

    /** Куда идут клубы кадра. */
    @FunctionalInterface
    public interface Sink {
        /**
         * Клуб в мире: центр и размер (сторона квадрата; сам клуб на текстуре занимает ~60% её) в блоках, поворот,
         * цвет дыма и его непрозрачность, цвет и сила свечения изнутри (свет поверх дыма, ничего не закрывает), номер
         * текстуры в атласе.
         */
        void puff(double x, double y, double z, double size, float rot, float r, float g, float b, float a, float gr, float gg, float gb, float ga,
                  int tex);
    }

    private record Puff(Kind kind, float a, float b, float c, float size, int tex, float rot, float spin, float shade) {}

    /** За сколько секунд модели (e-кратно) оседает пыль у земли: воздушный подрыв, наземный. */
    static final double DUST_SETTLE_AIR = 90, DUST_SETTLE_SURFACE = 240;
    /** Цвета: шапка (молодая, остывшая), ножка, пыль юбки. */
    private static final int CAP_BASE = 0x8C5A46, CAP_LATE = 0xC9C4BE, STEM = 0x8E7F70, DUST = 0x9C8A74;
    /** Над водой: брызги и водяная пыль у земли (её цвет продолжает пелена над водой — {@code MIST} в {@code fx_pall}). */
    static final int SPRAY = 0xE6EAED;
    /** Пар в ножке и шапке подрыва на воде. */
    private static final int STEAM = 0xD9D9D6;
    /**
     * Цвет накала изнутри по времени (с при 15 кт, у 1 кт короче — {@link #glowScale}): бело-жёлтый у шара,
     * жёлто-оранжевый, оранжевый, к минуте — тёплый буро-оранжевый, потом — дым, освещённый снизу пожарами и небом.
     * Яркость накала — отдельно ({@link #glow}); цвет не темнеет до тёмно-красного: на ночном небе тёмно-красный
     * дым, умноженный на гаснущий накал, выглядел бы чёрным.
     */
    private static final double[] GLOW_T = {0, 10, 35, 65, 100, 160};
    private static final int[] GLOW_RGB = {0xFFD890, 0xFFB050, 0xFF8C3C, 0xF27838, 0xC87A50, 0xB08C78};
    /** Пожары под стеной — оранжевое пламя. */
    private static final int FIRE = 0xFF8A2E;
    /** Отсвет накала в самом дыме и сила свечения клуба поверх дыма. */
    private static final double GLOW_IN_SMOKE = 0.85, EMISSIVE = 0.3;
    /**
     * Шапка и ножка и после накала не чернеют: их снизу освещают пожары, сверху — небо. Это нижняя граница их
     * отсвета (доля от полного накала) — остывший гриб ночью серо-бурый, а не чёрный.
     */
    static final double EMBER = 0.5;
    /** Под это число клубов подобраны их размеры: клубов больше — они мельче, контур резче. */
    private static final double REFERENCE_PUFFS = 600;
    /** Доля клубов гриба у юбки и ниже ({@link Kind}); остальные (прежде — облако Вильсона, теперь {@link BlastWall}) не рождаются. */
    private static final float MUSHROOM = 0.93f;
    /** Клуб у камеры замеряет рельеф в пяти точках: середина и на такой доле своего размера в четыре стороны. */
    private static final double SPREAD = 0.3;
    /**
     * Дальний клуб замеряет рельеф раз в столько тиков (каждый в свой тик) и подходит к замеру за несколько тиков: там
     * рельеф — плитки карты ({@code TerrainTiles}), их чтение дороже чанка, а клуб в сотни блоков ступеньки не заметит.
     */
    private static final int FAR_EVERY = 4;
    private static final float FAR_FOLLOW = 0.35f;

    private final Detonation d;
    private final Puff[] puffs;
    /** Множитель размера клубов гриба по их числу. */
    private final double detail;
    /** Докуда по земле световой импульс поджигает (≥ {@link ThermalModel#IGNITE}), м: там стена — пыль и огонь. */
    private final double fireGround;
    /** Высота подрыва над землёй, м; мощность — кубическим корнем от 15 кт (масштаб форм). */
    private final double hob, ys;
    /**
     * Верх рельефа под клубами у земли, блоки: в прошлый тик, в этот (кадр — между ними) и последний замер (дальний
     * клуб подходит к нему за несколько тиков). У остальных клубов не используются.
     */
    private final float[] ground0, ground1, sample;
    /** Клуб у земли стоит на замеренном рельефе; нет — на высоте эпицентра (ещё не замерен или был не виден). */
    private final boolean[] placed;
    private int ticks;
    /**
     * Вода под клубом у земли 0..1 ({@link #water}): над водой юбка — брызги и водяная пыль, а не пыль земли;
     * NaN — ещё не известно (тогда — как под эпицентром, {@link #burstUnder}).
     */
    private final float[] wet;
    /** Вода под эпицентром 0..1 и насколько подрыв «водяной» ({@link WaterModel#wetBurst}): ножка и юбка — пар и брызги. */
    private double burstUnder, burstWet;

    public CloudPuffs(Detonation d) {
        this(d, IncomingConfig.CLIENT.nukeCloudQuality.get().puffs);
    }

    /** @param n клубов гриба (по нему — их размер; рождаются те, что до {@link #MUSHROOM} их доли) */
    CloudPuffs(Detonation d, int n) {
        this.d = d;
        RandomSource r = RandomSource.create(d.seed());
        int count = 0;
        while (count < n && (float) count / n < MUSHROOM) count++;
        puffs = new Puff[count];
        for (int i = 0; i < count; i++) {
            float f = (float) i / n;
            Kind k = f < 0.5f ? Kind.CAP : f < 0.61f ? Kind.DOME : f < 0.8f ? Kind.STEM : Kind.SKIRT;
            puffs[i] = new Puff(k, r.nextFloat(), r.nextFloat(), r.nextFloat(), 0.7f + 0.6f * r.nextFloat(), r.nextInt(8),
                    r.nextFloat() * Mth.TWO_PI, (r.nextFloat() - 0.5f) * 0.02f, 0.85f + 0.3f * r.nextFloat());
        }
        detail = Math.cbrt(REFERENCE_PUFFS / n);
        hob = Math.max(0, d.hobMetres());
        ys = Math.cbrt(d.yieldKt() / 15);
        double slant = ThermalModel.rangeForFluence(ThermalModel.IGNITE, d.yieldKt(), d.surface(), d.visibility());
        fireGround = Math.sqrt(Math.max(0, slant * slant - hob * hob));
        ground0 = new float[puffs.length];
        ground1 = new float[puffs.length];
        sample = new float[puffs.length];
        placed = new boolean[puffs.length];
        wet = new float[puffs.length];
        java.util.Arrays.fill(wet, Float.NaN);
        java.util.Arrays.fill(ground0, (float) d.groundY());
        java.util.Arrays.fill(ground1, (float) d.groundY());
        java.util.Arrays.fill(sample, (float) d.groundY());
    }

    /** Сколько секунд модели гриб виден: стабилизируется, растекается и рассеивается. */
    public static double lifeSeconds(Detonation d) {
        return 4 * CloudModel.stabilizationSeconds(d.yieldKt());
    }

    /**
     * Растяжка времени свечения с мощностью: 1 у 15 кт, у 1 кт — ×0.51 (шар и гриб вдвое ниже, остывают быстрее:
     * накал полный 20 с и гаснет к 75 с).
     */
    static double glowScale(double yieldKt) {
        return Math.pow(Math.max(yieldKt, 0.1) / 15, 0.25);
    }

    /**
     * Накал гриба изнутри 0..1: у 15 кт полный 40 с, к 70 с — 0.8, к 90 с — 0.57, гаснет к 150 с (дальше шапку
     * и ножку освещают пожары и небо, {@link #EMBER}); у 1 кт — вдвое короче ({@link #glowScale}).
     */
    static double glow(double t, double yieldKt) {
        double k = glowScale(yieldKt);
        return 1 - smooth(40 * k, 150 * k, t);
    }

    /** Цвет накала в момент t (RGB): по ключам {@link #GLOW_T}, растянутым с мощностью. */
    static int glowRgb(double t, double yieldKt) {
        double x = t / glowScale(yieldKt);
        int n = GLOW_T.length;
        if (x >= GLOW_T[n - 1]) return GLOW_RGB[n - 1];
        int i = 1;
        while (GLOW_T[i] < x) i++;
        return lerpRgb(GLOW_RGB[i - 1], GLOW_RGB[i], Mth.clamp((x - GLOW_T[i - 1]) / (GLOW_T[i] - GLOW_T[i - 1]), 0, 1));
    }

    /**
     * Цвет клуба дыма: свет неба на нём плюс отсвет накала изнутри ({@code heat} 0..1, цвет {@code hot}). Днём
     * глаз привыкает к свету — отсвет виден слабее; ночью он и есть весь цвет гриба. Пишет r, g, b в 0..1 в {@code into}.
     */
    static void smoke(int rgb, double shade, float ambient, int hot, double heat, float[] into) {
        double inner = heat * GLOW_IN_SMOKE * (1 - 0.5 * ambient);
        for (int i = 0; i < 3; i++) {
            int sh = 16 - 8 * i;
            into[i] = (float) Math.min(1, ((rgb >> sh) & 0xFF) / 255.0 * shade * ambient + ((hot >> sh) & 0xFF) / 255.0 * inner);
        }
    }

    /**
     * Сколько ещё светится сам шар 0..1: гаснет на 70–130 t_max (15 кт — 9–16 с), дальше его свет — это накал
     * шапки и ножки.
     */
    static double fireballGlow(double t, double yieldKt) {
        return 1 - smooth(70, 130, t / FireballModel.secondMaximumSeconds(yieldKt));
    }

    /** Всё, что в момент t (секунды модели) общее для клубов; метры. */
    private static final class Moment {
        double t, life, rf, endTop, capR, capH, yc, capBot, tau, capVis, glow, ember, fireball, overhead, stemTop, stemAlpha, stemR,
                lowDust, lowTop, front, groundFront, windX, windZ;
        int glowRgb, fireballRgb, capRgb;
    }

    /** Клуб в момент: место от эпицентра (высота — над землёй под ним или эпицентром) и размер, м; свет и цвет. */
    private static final class Spot {
        double px, py, pz, size, a, shade, heat, emit;
        int rgb, hot;
    }

    /** Общее для клубов в момент t; null — гриба уже (или ещё) нет. */
    private Moment moment(double t) {
        if (t <= 0) return null;
        double y = d.yieldKt();
        double stab = CloudModel.stabilizationSeconds(y);
        double life = Mth.clamp(1 - (t - 2.5 * stab) / (1.5 * stab), 0, 1);
        if (life <= 0) return null;
        Moment m = new Moment();
        m.t = t;
        m.life = life;
        double rf = m.rf = FireballModel.maxRadius(y, d.surface());
        double tMax = FireballModel.secondMaximumSeconds(y);
        double start = hob + FireballModel.maxRadius(y, false);
        m.endTop = Math.max(start, CloudModel.stabilizedTop(y));
        double top = CloudModel.top(t, hob, y);
        m.capBot = Math.max(hob, CloudModel.capBottom(t, hob, y));
        double rise = Mth.clamp((top - start) / Math.max(1, m.endTop - start), 0, 1);
        m.capR = rf * 1.1 + (CloudModel.capRadius(y) - rf * 1.1) * Math.pow(rise, 0.8);
        m.capR *= 1 + 0.25 * Mth.clamp((t - stab) / stab, 0, 2); // шапка растекается «наковальней»
        m.capH = Math.max(rf, top - m.capBot);
        m.yc = m.capBot + m.capH * 0.5;
        // шапка проступает, когда остывает шар: он сам и становится шапкой
        m.tau = t / tMax;
        m.capVis = smooth(50, 110, m.tau);
        m.glow = glow(t, y);
        m.glowRgb = glowRgb(t, y);
        // шапку и ножку после накала освещают пожары и небо: отсвет не ниже EMBER
        m.ember = Math.max(m.glow, EMBER);
        // шар освещает всё вокруг себя: пыль под ним
        m.fireball = fireballGlow(t, y);
        m.fireballRgb = FireballModel.colorArgb(t, y) & 0xFFFFFF;
        // свет сверху на пыль у земли: от шара, потом от раскалённой шапки
        m.overhead = Math.max(m.fireball, 0.5 * m.glow);
        m.capRgb = lerpRgb(CAP_BASE, CAP_LATE, Mth.clamp(t / 90, 0, 1));
        // ножка — пыль, которую тянет вверх за шаром: догоняет шапку за десятую часть подъёма;
        // у высокого воздушного подрыва она тоньше и бледнее
        m.stemTop = m.capBot * Mth.clamp(t / (stab * 0.1), 0, 1);
        m.stemAlpha = hob <= rf ? 0.8 : Mth.clamp(1.2 - hob / (rf * 6), 0.35, 0.8);
        m.stemR = m.capR * 0.16;
        // пыль у земли (юбка, раструб ножки) оседает: держится, пока ножка догоняет шапку, дальше редеет — у воздушного
        // подрыва за полторы минуты (e-кратно), у наземного (грунт из воронки) — за четыре; сам гриб висит дальше.
        // Без этого юбка (до 1.4 км от эпицентра, до 350 м в высоту у 15 кт) и раструб ножки (до 750 м) стояли бы
        // сплошной бурой пеленой до конца гриба — 15 минут: руины у эпицентра были бы не видны ни с какой камеры
        m.lowDust = Math.exp(-Math.max(0, t - stab * 0.1) / (hob <= rf ? DUST_SETTLE_SURFACE : DUST_SETTLE_AIR));
        m.lowTop = Math.max(1, Math.min(m.capBot * 0.3, 1000 * ys));
        // волна у земли: наклонная дальность фронта → радиус по земле
        m.front = d.metres(d.frontRadius(t * 20 * d.scale()));
        m.groundFront = Math.sqrt(Math.max(0, m.front * m.front - hob * hob));
        m.windX = Math.cos(d.windDir()) * d.windSpeed();
        m.windZ = Math.sin(d.windDir()) * d.windSpeed();
        return m;
    }

    /**
     * Клуб p в момент m — в s (без доли {@link Moment#life}); false — клуба сейчас нет.
     */
    private boolean place(Puff p, Moment m, Spot s) {
        double y = d.yieldKt(), rf = m.rf, t = m.t;
        s.hot = m.glowRgb;
        s.shade = p.shade;
        s.heat = 0; // отсвет накала или огня на дыме 0..1
        s.emit = -1; // своё свечение клуба 0..1; -1 — столько же, сколько отсвет
        double ang = p.a * Math.PI * 2;
        switch (p.kind) {
            case CAP -> {
                // тор: θ идёт так, что снаружи клуб опускается, внутри — поднимается
                double rm = m.capR * 0.55, rt = m.capR - rm;
                double omega = FireballModel.riseSpeed(y) / (Math.PI * Math.max(rt, 1)) * 0.35;
                double th = p.b * Math.PI * 2 - omega * t;
                double fill = 0.35 + 0.65 * Math.sqrt(p.c);
                double rh = rm + rt * Math.cos(th) * fill;
                s.px = Math.cos(ang) * rh;
                s.pz = Math.sin(ang) * rh;
                double vert = Math.sin(th) * fill;
                s.py = m.yc + m.capH * 0.5 * vert;
                s.size = rt * 1.25 * p.size * detail;
                s.a = 0.9 * m.capVis;
                s.shade *= 0.62 + 0.38 * (vert * 0.5 + 0.5);
                // жарче всего низ шапки и сердцевина вихря, внешний край и верх — слабее, но светятся и они
                double core = (0.55 + 0.45 * (0.5 - 0.5 * vert)) * (1 - 0.3 * Math.max(0, Math.cos(th)) * fill);
                s.heat = m.ember * core;
                s.emit = m.glow * core;
                s.rgb = m.capRgb;
            }
            case DOME -> {
                double rh = m.capR * 0.55 * Math.sqrt(p.c);
                s.px = Math.cos(ang) * rh;
                s.pz = Math.sin(ang) * rh;
                s.py = m.yc + m.capH * (0.2 + 0.25 * p.b);
                s.size = m.capR * 0.5 * p.size * detail;
                s.a = 0.85 * m.capVis;
                s.shade *= 1.05;
                s.heat = m.ember * 0.6;
                s.emit = m.glow * 0.6;
                s.rgb = m.capRgb;
            }
            case STEM -> {
                double h = p.b * m.capBot;
                if (h > m.stemTop) return false;
                double w = p.b;
                // шире у шапки и у земли; раструб у земли — пыль, он оседает вместе с юбкой
                double r = m.stemR * (0.75 + 0.9 * w * w) + m.stemR * 1.6 * Math.pow(1 - w, 6) * m.lowDust;
                double rh = r * Math.sqrt(p.c);
                double swirl = ang + t * 0.02;
                s.px = Math.cos(swirl) * rh;
                s.pz = Math.sin(swirl) * rh;
                s.py = h;
                s.size = Math.max(m.stemR * 1.8, r * 1.3) * p.size * detail;
                s.a = m.stemAlpha * smooth(4, 20, m.tau) * Mth.clamp((m.stemTop - h) / (m.capBot * 0.05 + 1), 0, 1)
                        * Mth.lerp(Mth.clamp(h / m.lowTop, 0, 1), m.lowDust, 1);
                s.shade *= 0.7 + 0.3 * w;
                // ножка светится по всей высоте (горячий воздух, который тянет шар): верх и сердцевина — ярче
                double core = (0.75 + 0.25 * w) * (1 - 0.15 * Math.sqrt(p.c));
                s.heat = m.ember * core;
                s.emit = m.glow * core;
                s.rgb = STEM;
            }
            case SKIRT -> {
                double rs = Math.min(m.groundFront, m.capR * 0.7) * (0.45 + 0.55 * p.c);
                s.px = Math.cos(ang) * rs;
                s.pz = Math.sin(ang) * rs;
                s.py = Math.min(m.capBot * 0.12, 350 * ys) * p.b * smooth(0, 30, t);
                s.size = Math.max(60 * ys, rs * 0.22) * p.size * detail;
                s.a = 0.7 * smooth(0.5, 3, t) * (hob <= rf * 4 ? 1 : 0.5) * m.lowDust;
                s.shade *= 0.8 + 0.2 * p.b;
                s.heat = 0.35 * p.b * m.overhead;
                s.rgb = DUST;
            }
        }
        // ветер: верх сносит сильнее низа
        double drift = t * Mth.clamp(s.py / m.endTop, 0, 1);
        s.px += m.windX * drift;
        s.pz += m.windZ * drift;
        return true;
    }

    /**
     * Отсвет шара, потом раскалённой шапки сверху на пыли у земли 0..1 ({@code pallLit} в {@code pall.glsl} — то же):
     * на {@code r} от эпицентра по земле и доле высоты пыли {@code up} (низ темнее), {@code overhead} — свет шара и шапки
     * 0..1, {@code lightR} — шесть радиусов шара ({@link #pallLight}); r и lightR — в одних единицах.
     */
    static double lit(double r, double up, double overhead, double lightR) {
        double x = r / Math.max(lightR, 1e-3);
        return (0.3 + 0.4 * up) * overhead / (1 + x * x);
    }

    /**
     * Отсвет пожаров под пылью 0..1 ({@code pallBurn} в {@code pall.glsl} — то же): в зоне поджига до {@code fireR}
     * ({@link #pallLight}), снизу сильнее, с накалом гриба {@code glow}.
     */
    static double burn(double r, double up, double glow, double fireR) {
        double e = Math.max(fireR, 1e-3);
        return (1 - smooth(0.92 * e, 1.04 * e, r)) * (0.95 - 0.45 * up) * glow;
    }

    /** Сила отсвета на дыме ({@link #smoke} и свечение клуба поверх него) при свете неба {@code ambient}. */
    public static double glowOnSmoke(float ambient) {
        return GLOW_IN_SMOKE * (1 - 0.5 * ambient) + EMISSIVE;
    }

    /** Цвет пламени пожаров под стеной, r, g, b в 0..1 — в {@code into}. */
    public static void fireRgb(float[] into) {
        for (int i = 0; i < 3; i++) into[i] = ((FIRE >> (16 - 8 * i)) & 0xFF) / 255f;
    }

    /**
     * Свет шара, шапки и пожаров на стене пыли и пелене за ней в момент t (секунды модели) — им {@code fx_pall} светит
     * пыль ({@link #lit} сверху, {@link #burn} снизу), а {@code NukePall.dustColor} — пыль у камеры. В
     * {@code into}: свет шара и шапки сверху, накал гриба, шесть радиусов шара и край зоны пожаров (блоки), цвет накала
     * (r, g, b в 0..1), высота шара или шапки над землёй под эпицентром (блоки). false — гриба уже нет (в {@code into}
     * нули): нет и отсвета.
     */
    public boolean pallLight(double t, float[] into) {
        java.util.Arrays.fill(into, 0, 8, 0f);
        Moment m = moment(t);
        if (m == null) return false;
        into[0] = (float) m.overhead;
        into[1] = (float) m.glow;
        into[2] = (float) d.blocks(6 * m.rf);
        into[3] = (float) d.blocks(fireGround);
        for (int i = 0; i < 3; i++) into[4 + i] = ((m.glowRgb >> (16 - 8 * i)) & 0xFF) / 255f;
        into[7] = (float) d.blocks(Math.max(m.yc, m.rf));
        return true;
    }

    /**
     * Все видимые клубы в момент {@code t} (секунды модели).
     *
     * @param partial доля тика кадра: пыль у земли стоит между рельефом прошлого и этого тика ({@link #track})
     * @param ambient освещённость неба 0..1 (ночью гриб тёмный, кроме свечения изнутри)
     */
    public void sprites(double t, float partial, float ambient, Sink out) {
        Moment m = moment(t);
        if (m == null) return;
        Spot s = new Spot();
        float[] c = new float[3];
        for (int i = 0; i < puffs.length; i++) {
            Puff p = puffs[i];
            if (!place(p, m, s)) continue;
            if (p.kind.onGround()) wetten(i, s);
            else if (burstWet > 0) s.rgb = lerpRgb(s.rgb, STEAM, burstWet * (p.kind == Kind.STEM ? 0.8 : 0.45));
            double a = s.a * m.life;
            if (a < 0.01) continue;
            // дым: свет неба и отсвет накала изнутри — ночью шапка, ножка и стена оранжевые, а не чёрные
            smoke(s.rgb, s.shade, ambient, s.hot, s.heat, c);
            float hr = ((s.hot >> 16) & 0xFF) / 255f, hg = ((s.hot >> 8) & 0xFF) / 255f, hb = (s.hot & 0xFF) / 255f;
            double glowA = a * (s.emit < 0 ? s.heat : s.emit) * EMISSIVE;
            double base = p.kind.onGround() && placed[i] ? Mth.lerp(partial, ground0[i], ground1[i]) : d.groundY();
            out.puff(d.burst().x + d.blocks(s.px), base + d.blocks(s.py), d.burst().z + d.blocks(s.pz), d.blocks(s.size),
                    p.rot + (float) (p.spin * t), c[0], c[1], c[2], (float) Math.min(1, a), hr, hg, hb, (float) glowA, p.tex);
        }
    }

    /**
     * Тик клиента: верх рельефа под пылью у земли там, где она будет в момент {@code t} (секунды модели) — к концу
     * следующего кадра; кадры до него ставят клуб между прошлым и этим замером. Клуб у камеры (ближе {@code near}
     * блоков с его размером) замеряет каждый тик в пяти точках под собой — встаёт на склон, крыши и кроны целиком, а
     * не прыгает по отдельным домам; дальний — в одной точке раз в {@link #FAR_EVERY} тиков. Где рельефа нет — клуб
     * остаётся на прежней высоте. Невидимый клуб (ещё не встал или осел) не замеряет, а появившись — встаёт на рельеф
     * сразу.
     */
    public void track(double t, Sightline.Heights heights, double eyeX, double eyeZ, double near) {
        Moment m = moment(t);
        ticks++;
        Spot s = new Spot();
        for (int i = 0; i < puffs.length; i++) {
            Puff p = puffs[i];
            if (!p.kind.onGround()) continue;
            ground0[i] = ground1[i];
            if (m == null || !place(p, m, s) || s.a * m.life < 0.01) {
                placed[i] = false;
                continue;
            }
            double x = d.burst().x + d.blocks(s.px), z = d.burst().z + d.blocks(s.pz), size = d.blocks(s.size);
            double dx = x - eyeX, dz = z - eyeZ, reach = near + size;
            if (dx * dx + dz * dz <= reach * reach) {
                double h = under(heights, x, z, size * SPREAD);
                if (!Double.isNaN(h)) sample[i] = (float) h;
                ground1[i] = sample[i];
            } else {
                if (!placed[i] || (i + ticks) % FAR_EVERY == 0) {
                    double h = heights.at(Mth.floor(x), Mth.floor(z));
                    if (!Double.isNaN(h)) sample[i] = (float) h;
                }
                ground1[i] += (sample[i] - ground1[i]) * FAR_FOLLOW;
            }
            if (!placed[i]) {
                ground0[i] = ground1[i] = sample[i];
                placed[i] = true;
            }
        }
    }

    /**
     * Тик клиента: вода под клубами у земли там, где они будут в момент {@code t} (секунды модели); каждый клуб — раз
     * в {@link #FAR_EVERY} тиков (вода под ним меняется только у берега; ещё не замеренный — так же: где нет ни чанка,
     * ни плитки, замер не удаётся минутами). Где не известно — прежнее.
     */
    public void water(double t, FarTerrain.Water water) {
        Moment m = moment(t);
        if (m == null) return;
        Spot s = new Spot();
        for (int i = 0; i < puffs.length; i++) {
            Puff p = puffs[i];
            if (!p.kind.onGround() || (i + ticks) % FAR_EVERY != 0) continue;
            if (!place(p, m, s) || s.a * m.life < 0.01) continue;
            double w = water.at(Mth.floor(d.burst().x + d.blocks(s.px)), Mth.floor(d.burst().z + d.blocks(s.pz)));
            if (!Double.isNaN(w)) wet[i] = (float) w;
        }
    }

    /** Вода под эпицентром 0..1 (где под клубом не известно — она) и насколько подрыв «водяной» ({@code NukeWater}). */
    void burst(double under, double wet) {
        burstUnder = under;
        burstWet = wet;
    }

    /** Сколько клубов у земли сейчас над водой (для строки сценария). */
    int wetGround() {
        int n = 0;
        for (int i = 0; i < puffs.length; i++) {
            if (puffs[i].kind.onGround() && (Float.isNaN(wet[i]) ? burstUnder : wet[i]) > 0.5) n++;
        }
        return n;
    }

    /** Юбка над водой: пыли там нет — пар и брызги подрыва, белые, без огня. */
    private void wetten(int i, Spot s) {
        double w = Float.isNaN(wet[i]) ? burstUnder : wet[i];
        if (w <= 0) return;
        s.rgb = lerpRgb(s.rgb, SPRAY, w);
        s.shade = Mth.lerp(w, s.shade, 0.95 + 0.1 * puffs[i].b);
        if (s.hot == FIRE) s.heat *= 1 - w;
    }

    /** Повтор перемотан: клубы у земли встают на рельеф заново, а не съезжают к нему с прежнего места. */
    void untrack() {
        java.util.Arrays.fill(placed, false);
    }

    /** Средний верх рельефа в середине и в четырёх точках вокруг на {@code spread} блоков; NaN — нигде не известен. */
    private static double under(Sightline.Heights heights, double x, double z, double spread) {
        double sum = 0;
        int n = 0;
        for (int k = 0; k < 5; k++) {
            double ox = k == 1 ? spread : k == 2 ? -spread : 0, oz = k == 3 ? spread : k == 4 ? -spread : 0;
            double h = heights.at(Mth.floor(x + ox), Mth.floor(z + oz));
            if (Double.isNaN(h)) continue;
            sum += h;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }

    static int lerpRgb(int a, int b, double f) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + f * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) Math.round(((a >> 8) & 0xFF) + f * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) Math.round((a & 0xFF) + f * ((b & 0xFF) - (a & 0xFF)));
        return r << 16 | g << 8 | bl;
    }
}
