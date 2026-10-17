package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;

/**
 * Свет подрыва на пыли (DESIGN-nuke §6): шар сверху и пожары снизу — им светятся стена пыли и пелена за ней
 * ({@code fx_pall}, числа слота — {@link #pallLight}) и пыль у камеры ({@code NukePall.dustColor}); законы свечения
 * подрыва во времени ({@link #glow}, {@link #fireballGlow}). Своих клубов здесь больше нет (имя осталось от них): гриб
 * и столб воды — объём ({@link MushroomField}), стена пыли, облако Вильсона и базисная волна — объём в пелене
 * ({@link BlastWall}, {@link PallField}).
 */
public final class CloudPuffs {
    /** Над водой: брызги и водяная пыль у земли (её цвет продолжает пелена над водой — {@code MIST} в {@code fx_pall}). */
    static final int SPRAY = 0xE6EAED;
    /** Отсвет накала в самом дыме и сила свечения клуба поверх дыма. */
    private static final double GLOW_IN_SMOKE = 0.85, EMISSIVE = 0.3;

    private final Detonation d;
    /** Докуда по земле световой импульс поджигает (≥ {@link ThermalModel#IGNITE}), м: там под пылью горит. */
    private final double fireGround;
    /** Высота подрыва над землёй, м. */
    private final double hob;

    public CloudPuffs(Detonation d) {
        this.d = d;
        hob = Math.max(0, d.hobMetres());
        double slant = ThermalModel.rangeForFluence(ThermalModel.IGNITE, d.yieldKt(), d.surface(), d.visibility());
        fireGround = Math.sqrt(Math.max(0, slant * slant - hob * hob));
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
     * Отсвет пожаров под пылью стены во времени 0..1 ({@link #burn}): у 15 кт полный 40 с, к 90 с — 0.57, гаснет к 150 с;
     * у 1 кт — вдвое короче ({@link #glowScale}).
     */
    static double glow(double t, double yieldKt) {
        double k = glowScale(yieldKt);
        return 1 - smooth(40 * k, 150 * k, t);
    }

    /**
     * Сколько ещё светится сам шар 0..1: гаснет на 70–130 t_max (15 кт — 9–16 с). Край шапки, смешанный с воздухом,
     * к началу его угасания в миллион раз тусклее ({@code MushroomModel.edgeTemperature}): сверху пыль светит только шар.
     */
    static double fireballGlow(double t, double yieldKt) {
        return 1 - smooth(70, 130, t / FireballModel.secondMaximumSeconds(yieldKt));
    }

    /**
     * Отсвет шара сверху на пыли у земли 0..1 ({@code pallLit} в {@code pall.glsl} — то же):
     * на {@code r} от эпицентра по земле и доле высоты пыли {@code up} (низ темнее), {@code overhead} — свет шара 0..1,
     * {@code lightR} — шесть радиусов шара ({@link #pallLight}); r и lightR — в одних единицах.
     */
    static double lit(double r, double up, double overhead, double lightR) {
        double x = r / Math.max(lightR, 1e-3);
        return (0.3 + 0.4 * up) * overhead / (1 + x * x);
    }

    /**
     * Отсвет пожаров под пылью 0..1 ({@code pallBurn} в {@code pall.glsl} — то же): в зоне поджига до {@code fireR}
     * ({@link #pallLight}), снизу сильнее, с накалом {@code glow}.
     */
    static double burn(double r, double up, double glow, double fireR) {
        double e = Math.max(fireR, 1e-3);
        return (1 - smooth(0.92 * e, 1.04 * e, r)) * (0.95 - 0.45 * up) * glow;
    }

    /**
     * Сила отсвета на дыме при свете неба {@code ambient}: отсвет накала в самом дыме и свечение поверх него. Днём глаз
     * привыкает к свету — отсвет виден слабее.
     */
    public static double glowOnSmoke(float ambient) {
        return GLOW_IN_SMOKE * (1 - 0.5 * ambient) + EMISSIVE;
    }

    /**
     * Свет шара и пожаров на стене пыли и пелене за ней в момент t (секунды модели) — им {@code fx_pall} светит пыль
     * ({@link #lit} сверху, {@link #burn} снизу), а {@code NukePall.dustColor} — пыль у камеры. В {@code into}: свет шара
     * сверху, накал пожаров, шесть радиусов шара и край зоны пожаров (блоки), цвет шара (r, g, b в 0..1), высота шара над
     * землёй под эпицентром (блоки). false — гриба уже (или ещё) нет (в {@code into} нули): нет и отсвета.
     */
    public boolean pallLight(double t, float[] into) {
        java.util.Arrays.fill(into, 0, 8, 0f);
        if (t <= 0 || t >= lifeSeconds(d)) return false;
        double y = d.yieldKt(), rf = FireballModel.maxRadius(y, d.surface());
        double top = CloudModel.top(t, hob, y), capBot = Math.max(hob, CloudModel.capBottom(t, hob, y));
        // середина шапки — там и шар, пока он светит пыль у земли
        double yc = capBot + Math.max(rf, top - capBot) * 0.5;
        int rgb = FireballModel.colorArgb(t, y);
        into[0] = (float) fireballGlow(t, y);
        into[1] = (float) glow(t, y);
        into[2] = (float) d.blocks(6 * rf);
        into[3] = (float) d.blocks(fireGround);
        for (int i = 0; i < 3; i++) into[4 + i] = ((rgb >> (16 - 8 * i)) & 0xFF) / 255f;
        into[7] = (float) d.blocks(Math.max(yc, rf));
        return true;
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }
}
