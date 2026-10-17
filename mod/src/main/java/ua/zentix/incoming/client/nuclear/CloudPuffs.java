package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;

/**
 * Свет подрыва на пыли (DESIGN-nuke §6): шар сверху и пожары снизу — им светятся стена пыли, облако Вильсона и пелена
 * за ними ({@code fx_pall}, числа слота — {@link #pallLight}) и пыль у камеры ({@code NukePall.dustColor}). Шар — тот же
 * источник, что светит ножку и частицы ({@link MushroomModel#light}: ламбертов шар радиуса шапки, чёрное тело её видимой
 * температуры, L·R²/d²); пожары — {@link #glow}, {@link #burn}. Своих клубов здесь больше нет (имя осталось от них): гриб
 * и столб воды — объём ({@link MushroomField}), стена пыли, облако Вильсона и базисная волна — объём в пелене
 * ({@link BlastWall}, {@link PallField}).
 */
public final class CloudPuffs {
    /** Свет чёрного тела шапки: r, g, b, яркость (поток клиента). */
    private static final double[] BALL = new double[4];
    /** Над водой: брызги и водяная пыль у земли (её цвет продолжает пелена над водой — {@code MIST} в {@code fx_pall}). */
    static final int SPRAY = 0xE6EAED;
    /** Отсвет накала в самом дыме и сила свечения клуба поверх дыма. */
    private static final double GLOW_IN_SMOKE = 0.85, EMISSIVE = 0.3;

    private final Detonation d;
    /** Докуда по земле световой импульс поджигает (≥ {@link ThermalModel#IGNITE}), м: там под пылью горит. */
    private final double fireGround;

    public CloudPuffs(Detonation d) {
        this.d = d;
        double hob = Math.max(0, d.hobMetres());
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
     * Свет шара на белой стене к нему в долях белой стены в полдень (без привыкания глаза) по числам слота
     * ({@link #pallLight}: радиус и яркость чёрного тела) на расстоянии {@code d} от его середины — {@link MushroomModel#light}
     * (у {@code fx_pall} — то же, {@code pallBallShare}, × привыкание глаза); d и радиус — в одних единицах.
     */
    static double ball(double luminance, double radius, double d) {
        return luminance * radius * radius / Math.max(Math.max(d * d, radius * radius), 1e-6);
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
     * Свет шара и пожаров на стене пыли, облаке Вильсона и пелене за ними у гриба {@code s} ({@code MushroomField.shape})
     * — им {@code fx_pall} светит пыль (шар — пучок {@link #ball} от середины шапки, пожары — {@link #burn} снизу), а
     * {@code NukePall.dustColor} — пыль у камеры. В {@code into}: радиус шапки (у шара — его радиус), накал пожаров, снос
     * середины шапки по ветру и край зоны пожаров (блоки), свет чёрного тела видимой температуры шапки
     * ({@link MushroomModel#edgeTemperature}: r, g, b линейные, в долях белой стены в полдень), высота середины шапки над
     * землёй под эпицентром (блоки). Середина — как у тела гриба и света частиц ({@code MushroomField.centre}). false —
     * гриба уже (или ещё) нет (в {@code into} нули): нет и отсвета.
     */
    public boolean pallLight(MushroomModel.Shape s, float[] into) {
        java.util.Arrays.fill(into, 0, 8, 0f);
        if (s == null) return false;
        double t = s.t(), y = d.yieldKt();
        BlackBody.light(MushroomModel.edgeTemperature(t, y, d.surface()), BALL);
        into[0] = (float) d.blocks(s.capR());
        into[1] = (float) glow(t, y);
        into[2] = (float) MushroomField.driftAlong(d, s);
        into[3] = (float) d.blocks(fireGround);
        for (int i = 0; i < 3; i++) into[4 + i] = (float) BALL[i];
        into[7] = (float) d.blocks(s.capY());
        return true;
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }
}
