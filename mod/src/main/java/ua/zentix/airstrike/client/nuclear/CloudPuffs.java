package ua.zentix.airstrike.client.nuclear;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.nuclear.model.CloudModel;
import ua.zentix.airstrike.nuclear.model.FireballModel;

import java.util.function.Consumer;

/**
 * Клубы гриба (DESIGN-nuke §6): шапка — тороидальный вихрь (снаружи опускается, в середине поднимается),
 * ножка, пылевая юбка у основания, пылевая стена на фронте ударной волны и облако Вильсона. Каждый клуб —
 * случайные, но постоянные параметры (сид подрыва, у всех клиентов одинаковые), а положение, размер и цвет
 * в каждый момент выводятся из {@link CloudModel} и {@link FireballModel}. Метры модели → блоки мира
 * через {@link Detonation#blocks}.
 */
public final class CloudPuffs {
    enum Kind { CAP, DOME, STEM, SKIRT, RING, WILSON }

    /**
     * Клуб в мире в этот кадр: центр и размер (сторона квадрата; сам клуб на текстуре занимает ~60% её) в блоках,
     * цвет, номер текстуры в атласе, поворот.
     */
    public record Sprite(double x, double y, double z, double size, float rot, float r, float g, float b, float a, int tex) {}

    private record Puff(Kind kind, float a, float b, float c, float size, int tex, float rot, float spin, float shade) {}

    private static final int CAP_BASE = 0x8C5A46, CAP_LATE = 0xC9C4BE, STEM = 0x8E7F70, DUST = 0x9C8A74, WILSON = 0xF4F4F6;

    private final Detonation d;
    private final Puff[] puffs;
    private final boolean humid;
    private final int ringCount;

    public CloudPuffs(Detonation d) {
        this.d = d;
        this.humid = CloudModel.wilsonCloud(humidity(d));
        int n = AirstrikeConfig.CLIENT.nukeCloudQuality.get().puffs;
        // кольцо пыли на фронте — отдельно и плотно: оно огромное, редкие клубы в нём видны дырами
        int ring = n * 4 / 5;
        RandomSource r = RandomSource.create(d.seed());
        puffs = new Puff[n + ring];
        for (int i = 0; i < n + ring; i++) {
            float f = (float) i / n;
            Kind k = i >= n ? Kind.RING : f < 0.5f ? Kind.CAP : f < 0.61f ? Kind.DOME : f < 0.8f ? Kind.STEM : f < 0.93f ? Kind.SKIRT : Kind.WILSON;
            puffs[i] = new Puff(k, r.nextFloat(), r.nextFloat(), r.nextFloat(), 0.7f + 0.6f * r.nextFloat(), r.nextInt(8),
                    r.nextFloat() * Mth.TWO_PI, (r.nextFloat() - 0.5f) * 0.02f, 0.85f + 0.3f * r.nextFloat());
        }
        ringCount = (int) java.util.Arrays.stream(puffs).filter(p -> p.kind == Kind.RING).count();
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
        // шапка проступает, когда гаснет шар; до того внутри него
        double tau = t / tMax;
        double capVis = smooth(8, 40, tau);
        double glow = Math.exp(-t / (tMax * 60)); // накал изнутри (оранжевый), 15 кт — ~7 с
        int fireRgb = FireballModel.colorArgb(t, y) & 0xFFFFFF;
        int capRgb = lerpRgb(CAP_BASE, CAP_LATE, Mth.clamp(t / 90, 0, 1));
        // ножка — пыль, которую тянет вверх за шаром: догоняет шапку за десятую часть подъёма;
        // у высокого воздушного подрыва она тоньше и бледнее
        double stemTop = capBot * Mth.clamp(t / (stab * 0.1), 0, 1);
        double stemAlpha = hob <= rf ? 0.8 : Mth.clamp(1.2 - hob / (rf * 6), 0.35, 0.8);
        double stemR = capR * 0.16;
        // волна у земли: наклонная дальность фронта → радиус по земле
        double front = d.metres(d.frontRadius(t * 20 * d.scale()));
        double groundFront = Math.sqrt(Math.max(0, front * front - hob * hob));
        // кольцо идёт с фронтом, пока давление не упадёт до 0.3 psi, дальше расплывается и оседает
        double ringStop = BlastModel.rangeForOverpressure(BlastModel.kpa(0.3), y);
        double windX = Math.cos(d.windDir()) * d.windSpeed(), windZ = Math.sin(d.windDir()) * d.windSpeed();
        double ys = Math.cbrt(y / 15);

        for (Puff p : puffs) {
            double px, py, pz, size, a;
            int rgb;
            double shade = p.shade;
            double lit = 0; // доля свечения изнутри
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
                    size = rt * 1.25 * p.size;
                    a = 0.9 * capVis;
                    shade *= 0.62 + 0.38 * (vert * 0.5 + 0.5);
                    lit = glow * (0.6 - 0.4 * vert);
                    rgb = capRgb;
                }
                case DOME -> {
                    double rh = capR * 0.55 * Math.sqrt(p.c);
                    px = Math.cos(ang) * rh;
                    pz = Math.sin(ang) * rh;
                    py = yc + capH * (0.2 + 0.25 * p.b);
                    size = capR * 0.5 * p.size;
                    a = 0.85 * capVis;
                    shade *= 1.05;
                    lit = glow * 0.3;
                    rgb = capRgb;
                }
                case STEM -> {
                    double h = p.b * capBot;
                    if (h > stemTop) continue;
                    double w = p.b;
                    double r = stemR * (0.75 + 0.9 * w * w) + stemR * 1.6 * Math.pow(1 - w, 6); // шире у шапки и у земли
                    double rh = r * Math.sqrt(p.c);
                    double swirl = ang + t * 0.02;
                    px = Math.cos(swirl) * rh;
                    pz = Math.sin(swirl) * rh;
                    py = h;
                    size = Math.max(stemR * 1.8, r * 1.3) * p.size;
                    a = stemAlpha * smooth(4, 20, tau) * Mth.clamp((stemTop - h) / (capBot * 0.05 + 1), 0, 1);
                    shade *= 0.7 + 0.3 * w;
                    lit = glow * 0.25 * w;
                    rgb = STEM;
                }
                case SKIRT -> {
                    double rs = Math.min(groundFront, capR * 0.7) * (0.45 + 0.55 * p.c);
                    px = Math.cos(ang) * rs;
                    pz = Math.sin(ang) * rs;
                    py = Math.min(capBot * 0.12, 350 * ys) * p.b * smooth(0, 30, t);
                    size = Math.max(60 * ys, rs * 0.22) * p.size;
                    a = 0.7 * smooth(0.5, 3, t) * (hob <= rf * 4 ? 1 : 0.5);
                    shade *= 0.8 + 0.2 * p.b;
                    rgb = DUST;
                }
                case RING -> {
                    // стена пыли и дыма на фронте ударной волны: катится от эпицентра, высокая у центра,
                    // ниже к краю; за фронтом тянется шлейф поднятой пыли
                    double settle = groundFront >= ringStop ? t - timeTo(ringStop, hob) : 0;
                    double head = Math.min(groundFront, ringStop) + 12 * settle;
                    if (head <= 0) continue;
                    double thick = Math.min(head * 0.5, 180 * ys);
                    double rr = head - thick * (1 - p.c) * (1 - p.c);
                    double k = Mth.clamp(BlastModel.psi(BlastModel.overpressureKpa(Math.hypot(head, hob), y)) / 5, 0.1, 1);
                    double wall = (60 + 260 * k) * ys;
                    // клубы по окружности перекрываются: стена сплошная
                    double spacing = 2 * Math.PI * head / Math.max(1, ringCount) * 3;
                    px = Math.cos(ang) * rr;
                    pz = Math.sin(ang) * rr;
                    py = wall * p.b * p.b;
                    size = Math.max(wall * 0.8, spacing) * p.size;
                    a = 0.85 * Math.max(k, 0.4) * smooth(0, 1.5, t) * Mth.clamp(1 - settle / 150, 0, 1);
                    shade *= 0.75 + 0.35 * p.b;
                    lit = glow * 0.4 * (1 - p.b);
                    rgb = DUST;
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
                }
            }
            a *= life;
            if (a < 0.01) continue;
            // ветер: верх сносит сильнее низа
            double drift = t * Mth.clamp(py / endTop, 0, 1);
            px += windX * drift;
            pz += windZ * drift;

            float cr = (float) Math.min(1, ((rgb >> 16) & 0xFF) / 255.0 * shade * ambient + ((fireRgb >> 16) & 0xFF) / 255.0 * lit);
            float cg = (float) Math.min(1, ((rgb >> 8) & 0xFF) / 255.0 * shade * ambient + ((fireRgb >> 8) & 0xFF) / 255.0 * lit * 0.8);
            float cb = (float) Math.min(1, (rgb & 0xFF) / 255.0 * shade * ambient + (fireRgb & 0xFF) / 255.0 * lit * 0.6);
            out.accept(new Sprite(d.burst().x + d.blocks(px), d.groundY() + d.blocks(py), d.burst().z + d.blocks(pz), d.blocks(size),
                    p.rot + (float) (p.spin * t), cr, cg, cb, (float) Math.min(1, a), p.tex));
        }
    }

    /** Когда фронт дошёл до радиуса {@code groundM} по земле, с. */
    private double timeTo(double groundM, double hob) {
        return d.arrival().arrivalSeconds(Math.hypot(groundM, hob));
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
