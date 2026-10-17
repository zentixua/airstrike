package ua.zentix.incoming.nuclear.model;

/**
 * Вода под ядерным подрывом и под его волной (DESIGN-nuke §6, «Вода»): рябь и пена под ветром за фронтом (тёмное кольцо
 * на воде), брызги с гребней, а у подрыва на воде или низко над ней — полый столб воды с короной брызг наверху и
 * базисная волна. Метры, секунды, килотонны; ветер — м/с ({@link BlastModel#windSpeed}).
 * <p>
 * Числа — по шкале Бофорта (рябь от «кошачьих лапок» при 1,5 м/с, срыв брызг с гребней с 7–8 баллов, пена полосами с 10)
 * и по Glasstone, Dolan 1977, §2.47–2.65: «Бейкер» (Crossroads, 1946, 23 кт на глубине 27 м) — столб ~600 м
 * в поперечнике и 1,8 км в высоту, стенки ~90 м, базисная волна — с ~10 с, когда вода столба падает, поначалу
 * ~25–30 м/с, к 4 минутам — около 2,5–3 км по радиусу и ~550 м в высоту; мелкий подводный подрыв 100 кт — столб выше
 * 1,5 км за 2 с, к 12 с 1 км в поперечнике со стенками 150 м, к 20 с оседает и сужается до 600 м. Время и размеры — по
 * кубическому корню мощности от «Бейкера». Облако конденсации (купол тумана на секунду-две) — облако Вильсона за
 * фронтом, как у всякого подрыва во влажном воздухе ({@code BlastWall}).
 * Подрыв на поверхности или над ней выбрасывает меньше воды, чем подводный: столб ниже ({@link #SURFACE_COLUMN}), волна
 * меньше ({@link #SURFACE_SURGE}); здесь — подрыв на поверхности (подводных у мода нет), и у него в Glasstone, Dolan
 * своих чисел нет: доли — оценка.
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
    /**
     * Столб у «Бейкера»: наружный радиус (м; 2000 футов в поперечнике), высота (м), постоянная подъёма (с; у 100 кт
     * столб выше 5000 футов за 2 с, а начальная скорость — 2500 футов в секунду); у подрыва на поверхности — доля его
     * высоты.
     */
    static final double COLUMN_RADIUS = 300, COLUMN_TOP = 1800, COLUMN_RISE = 1.6, SURFACE_COLUMN = 0.6;
    /** Толщина стенки столба — доля его наружного радиуса (у 100 кт — 500 футов при 3300 футах в поперечнике). */
    static final double COLUMN_WALL = 0.3;
    /** Когда вода столба начинает падать, с (у «Бейкера»), и за сколько падает. */
    static final double COLUMN_FALL = 10, COLUMN_FALL_SPAN = 25;
    /**
     * Столб сужается, оседая: у 100 кт — с 3300 футов в поперечнике на 12-й секунде до 2000 футов на 20-й (наружная
     * завеса брызг падает первой и уходит в базисную волну); доля радиуса и время у «Бейкера», с.
     */
    static final double COLUMN_SHRINK = 0.4, SHRINK_FROM = 7.4, SHRINK_TO = 12.3;
    /**
     * Корона — верх столба, где струи останавливаются и раскрываются брызгами: радиус — доля наружного радиуса столба,
     * полувысота — доля его радиуса до сужения (оценка: у «Бейкера» «цветная капуста» наверху втрое шире столба, но
     * большая её часть — пар и газ пузыря, вышедшие сквозь полый столб, — у подрыва на поверхности это шапка гриба).
     * Корона — самые наружные брызги и падает с наружной завесой ({@link #COLUMN_SHRINK}): оседающий столб — без неё.
     */
    static final double CROWN_R = 1.4, CROWN_H = 0.35;
    /** Базисная волна у «Бейкера»: начало (с), предел радиуса (м), постоянная (с), высота (м), за сколько тает (с, e-кратно). */
    static final double SURGE_START = 8, SURGE_RADIUS = 3000, SURGE_TAU = 100, SURGE_HEIGHT = 550, SURGE_FADE = 240;
    /**
     * Базисная волна у подрыва на поверхности — доля «бейкеровской» (рост радиуса и высота): над глубокой водой подрыв на
     * поверхности поднимает меньше воды, и волна у него меньше, чем у подводного (Glasstone, Dolan, гл. II); доля —
     * оценка, как {@link #SURFACE_COLUMN} у столба.
     */
    static final double SURFACE_SURGE = 0.7;
    /** Подрыв выше этой доли наибольшего радиуса шара — уже не «водяной»: столба и волны нет. */
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

    /** Наружный радиус полого столба воды до сужения, м. */
    public static double columnRadius(double yieldKt) {
        return COLUMN_RADIUS * bakerScale(yieldKt);
    }

    /** Толщина стенки столба, м. */
    public static double columnWall(double yieldKt) {
        return COLUMN_WALL * columnRadius(yieldKt);
    }

    /**
     * Верх столба в момент t, м: растёт к {@link #SURFACE_COLUMN} высоты «Бейкера» (подрыв на поверхности выбрасывает
     * меньше воды, чем подводный) с постоянной {@link #COLUMN_RISE} с, падая — оседает до воды.
     */
    public static double columnTop(double t, double yieldKt) {
        return columnRise(t, yieldKt) * (1 - columnFall(t, yieldKt));
    }

    /** Докуда поднялась бы вода столба к моменту t без падения, м. */
    private static double columnRise(double t, double yieldKt) {
        if (t <= 0) return 0;
        double s = bakerScale(yieldKt);
        return COLUMN_TOP * SURFACE_COLUMN * s * (1 - Math.exp(-t / (COLUMN_RISE * s)));
    }

    /** Сколько воды столба уже упало, 0..1. */
    public static double columnFall(double t, double yieldKt) {
        double s = bakerScale(yieldKt);
        return smooth(COLUMN_FALL * s, (COLUMN_FALL + COLUMN_FALL_SPAN) * s, t);
    }

    /**
     * Столб в момент t, м: середина стенки и её полутолщина по радиусу, верх стенки (середина короны), полуоси короны
     * (по радиусу и высоте), доля воды, что ещё не упала. Стенка — от воды до середины короны.
     */
    public record Column(double mid, double half, double wallTop, double crownR, double crownH, double water) {
        /** Верх короны над водой, м. */
        public double top() {
            return wallTop + crownH;
        }

        /** Наружный край короны или стенки, м. */
        public double outer() {
            return Math.max(mid + half, crownR);
        }
    }

    /**
     * Столб воды в момент t; null — ещё не встал или упал. Корона раскрывается, когда струи тормозят (доля подъёма), и
     * падает с наружной завесой, пока столб сужается ({@link #COLUMN_SHRINK}): дальше верх стенки — скруглённый край
     * в её толщину; не выше четверти столба, пока он низкий. Толщина стенки — та же.
     */
    public static Column column(double t, double yieldKt) {
        double top = columnTop(t, yieldKt), fall = columnFall(t, yieldKt);
        if (top <= 0 || fall >= 1) return null;
        double s = bakerScale(yieldKt), r = columnRadius(yieldKt), half = 0.5 * columnWall(yieldKt);
        double shrink = smooth(SHRINK_FROM * s, SHRINK_TO * s, t), outer = r * (1 - COLUMN_SHRINK * shrink);
        double open = (1 - Math.exp(-t / (COLUMN_RISE * s))) * (1 - shrink);
        double crownH = Math.min(Math.max(CROWN_H * r * open, half), 0.25 * top), crownR = outer * (1 + (CROWN_R - 1) * open);
        return new Column(outer - half, half, top - crownH, crownR, crownH, 1 - fall);
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

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
