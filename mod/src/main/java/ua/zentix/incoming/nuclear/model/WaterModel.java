package ua.zentix.incoming.nuclear.model;

/**
 * Вода под ядерным подрывом и под его волной (DESIGN-nuke §6, «Вода»): рябь и пена под ветром за фронтом (тёмное кольцо
 * на воде), брызги с гребней, а у подрыва на воде или низко над ней — полый столб воды, кольцо тумана (облако конденсации)
 * и базисная волна. Метры, секунды, килотонны; ветер — м/с ({@link BlastModel#windSpeed}).
 * <p>
 * Числа — по шкале Бофорта (рябь от «кошачьих лапок» при 1,5 м/с, срыв брызг с гребней с 7–8 баллов, пена полосами с 10)
 * и по «Бейкеру» (Crossroads, 1946, 23 кт на глубине 27 м; Glasstone, Dolan 1977, §2.47–2.65): столб ~600 м в поперечнике
 * и 1,8 км в высоту за ~10 с, стенки ~90 м; облако конденсации — купол-бублик вокруг столба на 1–4 с; базисная волна —
 * с ~10 с, когда вода столба падает, поначалу ~25–30 м/с, к 4 минутам — около 2,5–3 км по радиусу и ~550 м в высоту.
 * Подрыв на поверхности или над ней выбрасывает меньше воды, чем подводный: столб ниже ({@link #SURFACE_COLUMN}), волна
 * меньше ({@link #SURFACE_SURGE}); здесь — подрыв на поверхности (подводных у мода нет).
 */
public final class WaterModel {
    /** Рябь: с какого ветра вода темнеет и с какого темнее уже некуда, м/с. */
    static final double RIPPLE_FROM = 1.5, RIPPLE_FULL = 18;
    /** Брызги: с какого ветра срывает гребни и с какого над водой сплошная стена брызг, м/с. */
    static final double SPRAY_FROM = 14, SPRAY_FULL = 45;
    /** Пена: с какого ветра вода белеет полосами и с какого — вся, м/с. */
    static final double FOAM_FROM = 25, FOAM_FULL = 65;
    /** Высота стены брызг, м: при слабом срыве и при самом сильном ветре. */
    static final double SPRAY_LOW = 6, SPRAY_HIGH = 45;
    /**
     * На сколько секунд стена брызг отстаёт от фронта: ветер за ударной волной срывает гребни сразу, но облако капель
     * встаёт на свою высоту (десятки метров, капли поднимаются со скоростью в десятки м/с) за доли секунды — тёмное
     * кольцо ряби бежит впереди белой стены.
     */
    public static final double SPRAY_LAG = 0.5;
    /** Масштаб «Бейкера», кт: столб и волна растут как кубический корень мощности. */
    private static final double BAKER_KT = 23;
    /** Столб у «Бейкера», м: радиус, высота, постоянная подъёма (с); у подрыва на поверхности — доля его высоты. */
    static final double COLUMN_RADIUS = 300, COLUMN_TOP = 1800, COLUMN_RISE = 2, SURFACE_COLUMN = 0.6;
    /** Когда вода столба начинает падать, с (у «Бейкера»), и за сколько падает. */
    static final double COLUMN_FALL = 10, COLUMN_FALL_SPAN = 25;
    /** Базисная волна у «Бейкера»: начало (с), предел радиуса (м), постоянная (с), высота (м), за сколько тает (с, e-кратно). */
    static final double SURGE_START = 8, SURGE_RADIUS = 3000, SURGE_TAU = 100, SURGE_HEIGHT = 550, SURGE_FADE = 240;
    /**
     * Базисная волна у подрыва на поверхности — доля «бейкеровской» (рост радиуса и высота): над глубокой водой подрыв на
     * поверхности поднимает меньше воды, и волна у него меньше, чем у подводного (Glasstone, Dolan, гл. II); доля —
     * оценка, как {@link #SURFACE_COLUMN} у столба.
     */
    static final double SURFACE_SURGE = 0.7;
    /** Кольцо тумана: появляется, держится и тает (с, у «Бейкера»). */
    static final double MIST_FROM = 0.3, MIST_FULL = 1, MIST_HOLD = 2.5, MIST_GONE = 5;
    /** Подрыв выше этой доли наибольшего радиуса шара — уже не «водяной»: столба, волны и кольца нет. */
    static final double WET_FULL = 0.15, WET_NONE = 0.5;

    private WaterModel() {}

    /** Сколько вода под ветром темнеет рябью, 0..1. */
    public static double ripple(double windMs) {
        return smooth(RIPPLE_FROM, RIPPLE_FULL, windMs);
    }

    /** Срыв брызг с гребней, 0..1. */
    public static double spray(double windMs) {
        return smooth(SPRAY_FROM, SPRAY_FULL, windMs);
    }

    /** Пена на воде, 0..1. */
    public static double foam(double windMs) {
        return smooth(FOAM_FROM, FOAM_FULL, windMs);
    }

    /** Высота стены брызг над водой, м (0 — ветер брызг не срывает). */
    public static double sprayHeight(double windMs) {
        double s = spray(windMs);
        return s <= 0 ? 0 : SPRAY_LOW + (SPRAY_HIGH - SPRAY_LOW) * s;
    }

    /**
     * Тёмное кольцо фронта: насколько темнее вода (0..1 доля её света) там, где фронт прошёл {@code since} тиков игры
     * назад, а ветер за ним был {@code windMs}. У самого фронта — полоса ряби (воздух за фронтом дует, пока идёт
     * положительная фаза: {@code band} тиков игры), за ней вода ещё долго неспокойна ({@code calm} тиков), впереди
     * фронта — гладкая.
     */
    public static double darkness(double windMs, double since, double band, double calm) {
        if (since < 0) return 0;
        return 0.6 * ripple(windMs) * (0.3 + 0.7 * Math.exp(-since / band)) * Math.exp(-since / calm);
    }

    /** Пена за фронтом 0..1: встаёт за несколько тиков, сходит за {@code calm} / 4. */
    public static double whitecaps(double windMs, double since, double calm) {
        if (since < 0) return 0;
        return 0.7 * foam(windMs) * smooth(0, 8, since) * Math.exp(-since / (calm / 4));
    }

    // ---------------------------------------------------------------- подрыв на воде

    /**
     * Насколько подрыв «водяной», 0..1: на воде или ниже 0,15 наибольшего радиуса шара — весь, выше половины радиуса —
     * нет (шар воды почти не касается). У 15 кт: до ~40 м — полностью, к ~130 м — сходит на нет.
     */
    public static double wetBurst(double hobM, double yieldKt) {
        double rf = FireballModel.maxRadius(yieldKt, true);
        return 1 - smooth(WET_FULL * rf, WET_NONE * rf, hobM);
    }

    /** Масштаб «Бейкера» для мощности: кубический корень. */
    static double bakerScale(double yieldKt) {
        return Math.cbrt(Math.max(yieldKt, 0.01) / BAKER_KT);
    }

    /** Радиус полого столба воды (середина стенки), м. */
    public static double columnRadius(double yieldKt) {
        return COLUMN_RADIUS * bakerScale(yieldKt);
    }

    /** Толщина стенки столба, м. */
    public static double columnWall(double yieldKt) {
        return 0.3 * columnRadius(yieldKt);
    }

    /**
     * Верх столба в момент t, м: растёт к {@link #SURFACE_COLUMN} высоты «Бейкера» (подрыв на поверхности выбрасывает
     * меньше воды, чем подводный) с постоянной {@link #COLUMN_RISE} с, падая — оседает вдвое.
     */
    public static double columnTop(double t, double yieldKt) {
        if (t <= 0) return 0;
        double s = bakerScale(yieldKt);
        double top = COLUMN_TOP * SURFACE_COLUMN * s * (1 - Math.exp(-t / (COLUMN_RISE * s)));
        return top * (1 - 0.5 * columnFall(t, yieldKt));
    }

    /** Сколько воды столба уже упало, 0..1. */
    public static double columnFall(double t, double yieldKt) {
        double s = bakerScale(yieldKt);
        return smooth(COLUMN_FALL * s, (COLUMN_FALL + COLUMN_FALL_SPAN) * s, t);
    }

    /** Когда встаёт базисная волна, с. */
    public static double surgeStart(double yieldKt) {
        return SURGE_START * bakerScale(yieldKt);
    }

    /**
     * Радиус базисной волны подрыва на поверхности в момент t, м (0 — ещё не встала): от стенки столба, поначалу ~20 м/с
     * ({@link #SURFACE_SURGE} от ~30 м/с «Бейкера»).
     */
    public static double surgeRadius(double t, double yieldKt) {
        double s = bakerScale(yieldKt), since = t - surgeStart(yieldKt);
        if (since <= 0) return 0;
        return columnRadius(yieldKt) * 1.2 + SURFACE_SURGE * SURGE_RADIUS * s * (1 - Math.exp(-since / (SURGE_TAU * s)));
    }

    /** Высота базисной волны подрыва на поверхности в момент t, м ({@link #SURFACE_SURGE} от «Бейкера»). */
    public static double surgeHeight(double t, double yieldKt) {
        double s = bakerScale(yieldKt), since = t - surgeStart(yieldKt);
        if (since <= 0) return 0;
        return SURFACE_SURGE * SURGE_HEIGHT * s * (0.25 + 0.75 * smooth(0, 60 * s, since));
    }

    /** Густота базисной волны 0..1: встаёт за несколько секунд, тает минутами. */
    public static double surgeDensity(double t, double yieldKt) {
        double s = bakerScale(yieldKt), since = t - surgeStart(yieldKt);
        if (since <= 0) return 0;
        return smooth(0, 4 * s, since) * Math.exp(-since / (SURGE_FADE * s));
    }

    /** Кольцо тумана (облако конденсации) в момент t, 0..1: на 1–4 с у «Бейкера», дольше у мощного. */
    public static double mistRing(double t, double yieldKt) {
        double s = bakerScale(yieldKt);
        return smooth(MIST_FROM * s, MIST_FULL * s, t) * (1 - smooth(MIST_HOLD * s, MIST_GONE * s, t));
    }

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
