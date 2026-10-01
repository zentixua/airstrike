package ua.zentix.airstrike.client.nuclear;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.nuclear.model.CloudModel;
import ua.zentix.airstrike.nuclear.model.FireballModel;
import ua.zentix.airstrike.nuclear.model.ThermalModel;

import java.util.function.Consumer;

/**
 * Клубы гриба (DESIGN-nuke §6): шапка — тороидальный вихрь (снаружи опускается, в середине поднимается),
 * ножка, пылевая юбка у основания, пылевая стена — сам фронт ударной волны — со шлейфом оседающей пыли за ней
 * и облако Вильсона. Шапка, ножка и стена светятся изнутри (накал, пожары) — это отдельный складывающийся проход
 * ({@link Sprite#ga}). Каждый клуб — случайные, но постоянные параметры (сид подрыва, у всех клиентов одинаковые),
 * а положение, размер и цвет в каждый момент выводятся из {@link CloudModel} и {@link FireballModel}. Метры
 * модели → блоки мира через {@link Detonation#blocks}.
 */
public final class CloudPuffs {
    enum Kind { CAP, DOME, STEM, SKIRT, RING, CURTAIN, WILSON }

    /**
     * Клуб в мире в этот кадр: центр и размер (сторона квадрата; сам клуб на текстуре занимает ~60% её) в блоках,
     * поворот, цвет дыма, цвет и сила свечения изнутри, номер текстуры в атласе.
     */
    public record Sprite(double x, double y, double z, double size, float rot, float r, float g, float b, float a,
                         float gr, float gg, float gb, float ga, int tex) {}

    private record Puff(Kind kind, float a, float b, float c, float size, int tex, float rot, float spin, float shade) {}

    /** За сколько секунд модели (e-кратно) оседает пыль у земли: воздушный подрыв, наземный. */
    static final double DUST_SETTLE_AIR = 90, DUST_SETTLE_SURFACE = 240;
    private static final int CAP_BASE = 0x8C5A46, CAP_LATE = 0xC9C4BE, STEM = 0x8E7F70, DUST = 0x9C8A74, DUST_BURNT = 0x5E4E40,
            WILSON = 0xF4F4F6;
    /**
     * Цвет накала изнутри по времени (с при 15 кт, у 1 кт короче — {@link #glowScale}): бело-жёлтый у шара,
     * жёлто-оранжевый, оранжевый, к минуте — тёплый буро-оранжевый, потом — дым, освещённый снизу пожарами и небом.
     * Яркость накала — отдельно ({@link #glow}); цвет не темнеет до тёмно-красного: на ночном небе тёмно-красный
     * дым, умноженный на гаснущий накал, выглядел чёрным.
     */
    private static final double[] GLOW_T = {0, 10, 35, 65, 100, 160};
    private static final int[] GLOW_RGB = {0xFFD890, 0xFFB050, 0xFF8C3C, 0xF27838, 0xC87A50, 0xB08C78};
    /** Пожары под стеной — оранжевое пламя. */
    private static final int FIRE = 0xFF8A2E;
    /** Отсвет накала в самом дыме и сила свечения клуба в складывающемся проходе. */
    private static final double GLOW_IN_SMOKE = 0.85, EMISSIVE = 0.3;
    /**
     * Шапка и ножка и после накала не чернеют: их снизу освещают пожары, сверху — небо. Это нижняя граница их
     * отсвета (доля от полного накала) — остывший гриб ночью серо-бурый, а не чёрный.
     */
    static final double EMBER = 0.5;
    /** Под это число клубов подобраны их размеры: клубов больше — они мельче, контур резче. */
    private static final double REFERENCE_PUFFS = 600;

    private final Detonation d;
    private final Puff[] puffs;
    private final boolean humid;
    private final int ringCount, curtainCount;
    /** Множитель размера клубов гриба по их числу. */
    private final double detail;
    /** Докуда по земле световой импульс поджигает (≥ {@link ThermalModel#IGNITE}), м: там стена — пыль и огонь. */
    private final double fireGround;

    public CloudPuffs(Detonation d) {
        this.d = d;
        this.humid = CloudModel.wilsonCloud(humidity(d));
        int n = AirstrikeConfig.CLIENT.nukeCloudQuality.get().puffs;
        // стена пыли на фронте и шлейф за ней — отдельно и плотно: они огромные, редкие клубы в них видны дырами
        int ring = n * 4 / 5, wall = ring * 7 / 10;
        RandomSource r = RandomSource.create(d.seed());
        puffs = new Puff[n + ring];
        for (int i = 0; i < n + ring; i++) {
            float f = (float) i / n;
            Kind k = i >= n + wall ? Kind.CURTAIN : i >= n ? Kind.RING
                    : f < 0.5f ? Kind.CAP : f < 0.61f ? Kind.DOME : f < 0.8f ? Kind.STEM : f < 0.93f ? Kind.SKIRT : Kind.WILSON;
            puffs[i] = new Puff(k, r.nextFloat(), r.nextFloat(), r.nextFloat(), 0.7f + 0.6f * r.nextFloat(), r.nextInt(8),
                    r.nextFloat() * Mth.TWO_PI, (r.nextFloat() - 0.5f) * 0.02f, 0.85f + 0.3f * r.nextFloat());
        }
        ringCount = wall;
        curtainCount = ring - wall;
        detail = Math.cbrt(REFERENCE_PUFFS / n);
        double hob = Math.max(0, d.hobMetres());
        double slant = ThermalModel.rangeForFluence(ThermalModel.IGNITE, d.yieldKt(), d.surface(), d.visibility());
        fireGround = Math.sqrt(Math.max(0, slant * slant - hob * hob));
    }

    /** Влажность в эпицентре (облако Вильсона видно во влажном воздухе): из биома на клиенте. */
    private static double humidity(Detonation d) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return 0.5;
        var pos = net.minecraft.core.BlockPos.containing(d.burst().x, d.groundY(), d.burst().z);
        if (!level.getFluidState(pos).isEmpty()) return 1;
        return level.getBiome(pos).value().getModifiedClimateSettings().downfall();
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
     * глаз привыкает к свету — отсвет виден слабее; ночью он и есть весь цвет гриба. Возвращает r, g, b в 0..1.
     */
    static float[] smoke(int rgb, double shade, float ambient, int hot, double heat) {
        double inner = heat * GLOW_IN_SMOKE * (1 - 0.5 * ambient);
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) {
            int sh = 16 - 8 * i;
            c[i] = (float) Math.min(1, ((rgb >> sh) & 0xFF) / 255.0 * shade * ambient + ((hot >> sh) & 0xFF) / 255.0 * inner);
        }
        return c;
    }

    /**
     * Сколько ещё светится сам шар 0..1: гаснет на 70–130 t_max (15 кт — 9–16 с), дальше его свет — это накал
     * шапки и ножки.
     */
    static double fireballGlow(double t, double yieldKt) {
        return 1 - smooth(70, 130, t / FireballModel.secondMaximumSeconds(yieldKt));
    }

    /**
     * Высота пылевой стены, м: 300 у центра, ~115 на 1 км и ~60 на 3 км у 15 кт — по приведённой дальности
     * (как и давление), поэтому у любой мощности та же форма.
     */
    private static double wallHeight(double groundM, double ys) {
        double x = groundM / (900 * ys);
        return (40 + 260 / (1 + x * x)) * ys;
    }

    /**
     * Все видимые клубы в момент {@code t} (секунды модели).
     *
     * @param ambient освещённость неба 0..1 (ночью гриб тёмный, кроме свечения изнутри)
     */
    public void sprites(double t, float ambient, Consumer<Sprite> out) {
        if (t <= 0) return;
        double y = d.yieldKt();
        double hob = Math.max(0, d.hobMetres());
        double rf = FireballModel.maxRadius(y, d.surface());
        double stab = CloudModel.stabilizationSeconds(y);
        double tMax = FireballModel.secondMaximumSeconds(y);

        double start = hob + FireballModel.maxRadius(y, false);
        double endTop = Math.max(start, CloudModel.stabilizedTop(y));
        double top = CloudModel.top(t, hob, y);
        double capBot = Math.max(hob, CloudModel.capBottom(t, hob, y));
        double rise = Mth.clamp((top - start) / Math.max(1, endTop - start), 0, 1);
        double capR = rf * 1.1 + (CloudModel.capRadius(y) - rf * 1.1) * Math.pow(rise, 0.8);
        capR *= 1 + 0.25 * Mth.clamp((t - stab) / stab, 0, 2); // шапка растекается «наковальней»
        double capH = Math.max(rf, top - capBot);
        double yc = capBot + capH * 0.5;

        double life = Mth.clamp(1 - (t - 2.5 * stab) / (1.5 * stab), 0, 1);
        if (life <= 0) return;
        // шапка проступает, когда остывает шар: он сам и становится шапкой
        double tau = t / tMax;
        double capVis = smooth(50, 110, tau);
        double glow = glow(t, y);
        int glowRgb = glowRgb(t, y);
        // шапку и ножку после накала освещают пожары и небо: отсвет не ниже EMBER
        double ember = Math.max(glow, EMBER);
        // шар освещает всё вокруг себя: облако Вильсона, пыль под ним
        double fireball = fireballGlow(t, y);
        int fireballRgb = FireballModel.colorArgb(t, y) & 0xFFFFFF;
        // свет сверху на пыль у земли: от шара, потом от раскалённой шапки
        double overhead = Math.max(fireball, 0.5 * glow);
        int capRgb = lerpRgb(CAP_BASE, CAP_LATE, Mth.clamp(t / 90, 0, 1));
        // ножка — пыль, которую тянет вверх за шаром: догоняет шапку за десятую часть подъёма;
        // у высокого воздушного подрыва она тоньше и бледнее
        double stemTop = capBot * Mth.clamp(t / (stab * 0.1), 0, 1);
        double stemAlpha = hob <= rf ? 0.8 : Mth.clamp(1.2 - hob / (rf * 6), 0.35, 0.8);
        double stemR = capR * 0.16;
        // пыль у земли (юбка, раструб ножки) оседает: держится, пока ножка догоняет шапку, дальше редеет — у воздушного
        // подрыва за полторы минуты (e-кратно), у наземного (грунт из воронки) — за четыре; сам гриб висит дальше.
        // Без этого юбка (до 1.4 км от эпицентра, до 350 м в высоту у 15 кт) и раструб ножки (до 750 м) стояли
        // сплошной бурой пеленой до конца гриба — 15 минут: руины у эпицентра были не видны ни с какой камеры
        double lowDust = Math.exp(-Math.max(0, t - stab * 0.1) / (hob <= rf ? DUST_SETTLE_SURFACE : DUST_SETTLE_AIR));
        double lowTop = Math.max(1, Math.min(capBot * 0.3, 1000 * Math.cbrt(y / 15)));
        // волна у земли: наклонная дальность фронта → радиус по земле
        double front = d.metres(d.frontRadius(t * 20 * d.scale()));
        double groundFront = Math.sqrt(Math.max(0, front * front - hob * hob));
        double windX = Math.cos(d.windDir()) * d.windSpeed(), windZ = Math.sin(d.windDir()) * d.windSpeed();
        double ys = Math.cbrt(y / 15);
        // стена пыли идёт с фронтом, пока давление не упадёт до 0.3 psi, дальше расплывается и оседает
        double ringStop = BlastModel.rangeForOverpressure(BlastModel.kpa(0.3), y);
        double settle = groundFront >= ringStop ? t - timeTo(ringStop, hob) : 0;
        double head = Math.min(groundFront, ringStop) + 12 * settle;
        double headWall = wallHeight(head, ys);
        double thick = Math.min(head * 0.5, 180 * ys);
        double headK = pressure(head, hob, y);
        // клубы по окружности перекрываются: стена сплошная
        double ringSize = Math.max(headWall * 0.8, 2 * Math.PI * head / Math.max(1, ringCount) * 3);
        double curtainSpacing = Math.sqrt(Math.PI * head * head / Math.max(1, curtainCount)) * 1.5;

        for (Puff p : puffs) {
            double px, py, pz, size, a;
            int rgb;
            int hot = glowRgb;
            double shade = p.shade;
            double heat = 0; // отсвет накала или огня на дыме 0..1
            double emit = -1; // своё свечение клуба 0..1 (складывающийся проход); -1 — столько же, сколько отсвет
            double ang = p.a * Math.PI * 2;
            switch (p.kind) {
                case CAP -> {
                    // тор: θ идёт так, что снаружи клуб опускается, внутри — поднимается
                    double rm = capR * 0.55, rt = capR - rm;
                    double omega = FireballModel.riseSpeed(y) / (Math.PI * Math.max(rt, 1)) * 0.35;
                    double th = p.b * Math.PI * 2 - omega * t;
                    double fill = 0.35 + 0.65 * Math.sqrt(p.c);
                    double rh = rm + rt * Math.cos(th) * fill;
                    px = Math.cos(ang) * rh;
                    pz = Math.sin(ang) * rh;
                    double vert = Math.sin(th) * fill;
                    py = yc + capH * 0.5 * vert;
                    size = rt * 1.25 * p.size * detail;
                    a = 0.9 * capVis;
                    shade *= 0.62 + 0.38 * (vert * 0.5 + 0.5);
                    // жарче всего низ шапки и сердцевина вихря, внешний край и верх — слабее, но светятся и они
                    double core = (0.55 + 0.45 * (0.5 - 0.5 * vert)) * (1 - 0.3 * Math.max(0, Math.cos(th)) * fill);
                    heat = ember * core;
                    emit = glow * core;
                    rgb = capRgb;
                }
                case DOME -> {
                    double rh = capR * 0.55 * Math.sqrt(p.c);
                    px = Math.cos(ang) * rh;
                    pz = Math.sin(ang) * rh;
                    py = yc + capH * (0.2 + 0.25 * p.b);
                    size = capR * 0.5 * p.size * detail;
                    a = 0.85 * capVis;
                    shade *= 1.05;
                    heat = ember * 0.6;
                    emit = glow * 0.6;
                    rgb = capRgb;
                }
                case STEM -> {
                    double h = p.b * capBot;
                    if (h > stemTop) continue;
                    double w = p.b;
                    // шире у шапки и у земли; раструб у земли — пыль, он оседает вместе с юбкой
                    double r = stemR * (0.75 + 0.9 * w * w) + stemR * 1.6 * Math.pow(1 - w, 6) * lowDust;
                    double rh = r * Math.sqrt(p.c);
                    double swirl = ang + t * 0.02;
                    px = Math.cos(swirl) * rh;
                    pz = Math.sin(swirl) * rh;
                    py = h;
                    size = Math.max(stemR * 1.8, r * 1.3) * p.size * detail;
                    a = stemAlpha * smooth(4, 20, tau) * Mth.clamp((stemTop - h) / (capBot * 0.05 + 1), 0, 1)
                            * Mth.lerp(Mth.clamp(h / lowTop, 0, 1), lowDust, 1);
                    shade *= 0.7 + 0.3 * w;
                    // ножка светится по всей высоте (горячий воздух, который тянет шар): верх и сердцевина — ярче
                    double core = (0.75 + 0.25 * w) * (1 - 0.15 * Math.sqrt(p.c));
                    heat = ember * core;
                    emit = glow * core;
                    rgb = STEM;
                }
                case SKIRT -> {
                    double rs = Math.min(groundFront, capR * 0.7) * (0.45 + 0.55 * p.c);
                    px = Math.cos(ang) * rs;
                    pz = Math.sin(ang) * rs;
                    py = Math.min(capBot * 0.12, 350 * ys) * p.b * smooth(0, 30, t);
                    size = Math.max(60 * ys, rs * 0.22) * p.size * detail;
                    a = 0.7 * smooth(0.5, 3, t) * (hob <= rf * 4 ? 1 : 0.5) * lowDust;
                    shade *= 0.8 + 0.2 * p.b;
                    heat = 0.35 * p.b * overhead;
                    rgb = DUST;
                }
                case RING -> {
                    // стена пыли и дыма — это фронт: передний край клубов ровно на радиусе фронта, у центра стена
                    // высокая, к краю ниже; в зоне поджига — стена пыли и огня, сверху её освещает шар
                    if (head <= 0) continue;
                    size = ringSize * p.size;
                    double rr = Math.max(0, head - 0.3 * size - thick * (1 - p.c) * (1 - p.c));
                    px = Math.cos(ang) * rr;
                    pz = Math.sin(ang) * rr;
                    py = 0.3 * size + Math.max(0, headWall - 0.6 * size) * p.b * p.b;
                    a = 0.9 * Math.max(headK, 0.45) * smooth(0, 1.5, t) * Mth.clamp(1 - settle / 150, 0, 1);
                    shade *= 0.75 + 0.35 * p.b;
                    double fire = fireZone(rr);
                    double lit = (0.3 + 0.4 * p.b) * overhead * lightFalloff(rr, rf);
                    double burn = fire * (0.95 - 0.45 * p.b) * glow;
                    heat = Math.max(lit, burn);
                    if (burn > lit) hot = FIRE;
                    rgb = lerpRgb(DUST, DUST_BURNT, fire);
                }
                case CURTAIN -> {
                    // шлейф за фронтом: пыль, поднятая, когда фронт прошёл это место, висит и оседает десятки
                    // секунд; в зоне поджига под ней горит
                    double rr = Math.max(0, head - thick) * Math.sqrt(p.c);
                    double since = t - timeTo(rr, hob);
                    if (since <= 0) continue;
                    double wall = wallHeight(rr, ys);
                    size = Math.max(wall * 0.9, curtainSpacing) * p.size;
                    px = Math.cos(ang) * rr;
                    pz = Math.sin(ang) * rr;
                    py = 0.3 * size + wall * (0.2 + 0.6 * p.b) * (1 - Math.exp(-since / 5)) * Math.exp(-since / 120);
                    a = 0.6 * Math.max(pressure(rr, hob, y), 0.3) * smooth(0, 3, since) * Math.exp(-since / 45);
                    shade *= 0.7 + 0.35 * p.b;
                    double fire = fireZone(rr);
                    double lit = 0.35 * p.b * overhead * lightFalloff(rr, rf);
                    double burn = fire * (0.85 - 0.4 * p.b) * glow;
                    heat = Math.max(lit, burn);
                    if (burn > lit) hot = FIRE;
                    rgb = lerpRgb(DUST, DUST_BURNT, fire);
                }
                default -> { // WILSON
                    if (!humid) continue;
                    double env = smooth(0.15 * ys, 0.5 * ys, t) * (1 - smooth(1.5 * ys, 3.5 * ys, t));
                    if (env <= 0) continue;
                    double el = Math.asin(p.b * 2 - 1);
                    double r = front * (0.96 + 0.04 * p.c);
                    px = Math.cos(ang) * Math.cos(el) * r;
                    pz = Math.sin(ang) * Math.cos(el) * r;
                    py = hob + Math.sin(el) * r;
                    if (py < 0) continue;
                    size = r * 0.8 * p.size;
                    a = 0.3 * env;
                    rgb = WILSON;
                    // конденсат вокруг раскалённого шара освещён им: бело-жёлтый, а не серый
                    heat = 0.9 * fireball;
                    hot = fireballRgb;
                }
            }
            a *= life;
            if (a < 0.01) continue;
            // ветер: верх сносит сильнее низа
            double drift = t * Mth.clamp(py / endTop, 0, 1);
            px += windX * drift;
            pz += windZ * drift;

            // дым: свет неба и отсвет накала изнутри — ночью шапка, ножка и стена оранжевые, а не чёрные
            float[] c = smoke(rgb, shade, ambient, hot, heat);
            float hr = ((hot >> 16) & 0xFF) / 255f, hg = ((hot >> 8) & 0xFF) / 255f, hb = (hot & 0xFF) / 255f;
            double glowA = a * (emit < 0 ? heat : emit) * EMISSIVE;
            out.accept(new Sprite(d.burst().x + d.blocks(px), d.groundY() + d.blocks(py), d.burst().z + d.blocks(pz), d.blocks(size),
                    p.rot + (float) (p.spin * t), c[0], c[1], c[2], (float) Math.min(1, a), hr, hg, hb, (float) glowA, p.tex));
        }
    }

    /** Давление на земле в {@code groundM} от эпицентра — доля от 5 psi (0.1..1): густота пыли. */
    private static double pressure(double groundM, double hob, double yieldKt) {
        return Mth.clamp(BlastModel.psi(BlastModel.overpressureKpa(Math.hypot(groundM, hob), yieldKt)) / 5, 0.1, 1);
    }

    /** Зона поджига на земле 0..1 (с мягким краем). */
    private double fireZone(double groundM) {
        return 1 - smooth(fireGround * 0.92, fireGround * 1.04, groundM);
    }

    /** Отсвет шара и шапки на пыли у земли: слабеет с расстоянием от эпицентра. */
    private static double lightFalloff(double groundM, double fireballM) {
        double x = groundM / (6 * fireballM);
        return 1 / (1 + x * x);
    }

    /** Когда фронт дошёл до радиуса {@code groundM} по земле, с модели (фронт — как у сервера, {@link Detonation#arrivalTicks}). */
    private double timeTo(double groundM, double hob) {
        return d.arrivalTicks(d.blocks(Math.hypot(groundM, hob))) / (20 * d.scale());
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
