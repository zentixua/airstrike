package ua.zentix.incoming.nuclear.model;

/**
 * Гриб объёмом (DESIGN-nuke, шаг M1): форма шапки, купола, ножки и юбки, их вещество и температура по времени. Метры,
 * секунды, килотонны; высоты — над землёй под эпицентром. Шапка — сплюснутый пузырь, который из шара сворачивается
 * в вихревое кольцо ({@link #rollUpSeconds}); её верх, низ и радиус — {@link CloudModel}, растекание после
 * стабилизации и снос ветром — как у прежних клубов гриба. Клиент (поле в шейдере) берёт числа отсюда раз в кадр.
 */
public final class MushroomModel {
    /** Температура воздуха вокруг, K. */
    public static final double AMBIENT_K = 288;
    /** Температура шара во втором максимуме, K (G&D: ~7 500–8 000 K почти без зависимости от мощности). */
    public static final double FIREBALL_PEAK_K = 7700;
    /** Показатель остывания шара: T ∝ (t/t_max)^−0,32 — 1 с ~4 000 K, 9 с ~1 950 K у 15 кт (ключи цвета по хронике). */
    static final double FIREBALL_COOLING = 0.32;
    /**
     * Показатель остывания ядра кольца смешением после сворачивания: ΔT ∝ (t/t_roll)^−0,27 — ночью ядро 15 кт светится
     * около двух с половиной минут, у 1 кт — около полуминуты.
     */
    static final double CORE_COOLING = 0.27;
    /** Время сворачивания в кольцо в единицах √(R/g): 2–3 (Turner 1957, Morton–Taylor–Turner 1956). */
    static final double ROLL_UP = 2.5;
    static final double G = 9.81;
    /** Большой радиус кольца шапки — эта доля её радиуса (как у прежних клубов шапки). */
    public static final double RING = 0.55;
    /** Оптическая толща поперёк сечения кольца шапки (по меньшему поперечнику): густое облако, видно на ~5% сечения. */
    static final double TAU_CAP = 40;
    /** Толща поперёк ножки у наземного подрыва; у юбки — у низкого подрыва. */
    static final double TAU_STEM = 4, TAU_SKIRT = 2;
    /** За сколько секунд (e-кратно) оседает пыль у земли: воздушный подрыв, наземный (как у стены пыли). */
    public static final double DUST_SETTLE_AIR = 90, DUST_SETTLE_SURFACE = 240;

    private MushroomModel() {}

    /**
     * Гриб в момент t.
     *
     * @param life      доля гриба 0..1: до 2,5 стабилизаций — весь, к 4 рассеивается
     * @param top       верх облака
     * @param capY      середина шапки по высоте
     * @param capR      радиус шапки по горизонтали (до края кольца)
     * @param ringR     большой радиус кольца (0 — ещё шар)
     * @param ringA     полуось сечения кольца по горизонтали
     * @param ringB     полуось сечения по вертикали
     * @param domeY     середина купола над кольцом
     * @param domeR     полуось купола по горизонтали
     * @param domeH     полуось купола по вертикали
     * @param capTau    толща шапки поперёк меньшего поперечника сечения (с её видимостью и долей гриба)
     * @param stemTop   верх ножки (догоняет низ шапки)
     * @param stemR     радиус ножки в середине (у шапки шире, у земли — раструб пыли)
     * @param stemTau   толща ножки поперёк (с видимостью, оседанием пыли не считая раструба)
     * @param lowDust   доля пыли у земли 0..1 (оседает): раструб ножки и юбка
     * @param skirtR    радиус середины юбки по земле
     * @param skirtW    полуширина юбки по земле
     * @param skirtH    высота юбки
     * @param skirtTau  толща юбки поперёк (с оседанием)
     * @param endTop    верх облака после стабилизации: снос ветром — по доле этой высоты
     */
    public record Shape(double t, double life, double top, double capBottom, double capY, double capR, double ringR, double ringA,
                        double ringB, double domeY, double domeR, double domeH, double capTau, double stemTop, double stemR,
                        double stemTau, double lowDust, double skirtR, double skirtW, double skirtH, double skirtTau, double endTop) {}

    /** Сколько секунд гриб виден: стабилизируется, растекается и рассеивается (4 стабилизации). */
    public static double lifeSeconds(double yieldKt) {
        return 4 * CloudModel.stabilizationSeconds(yieldKt);
    }

    /**
     * За сколько секунд горячий пузырь сворачивается в вихревое кольцо: 2,5·√(R/g), R — радиус шара. Пузырь с перепадом
     * плотности порядка самой плотности становится кольцом, пройдя один-два своих радиуса, — время по плавучести, а не
     * по свечению шара: у 15 кт ~12 с, у 1 кт ~7 с.
     */
    public static double rollUpSeconds(double yieldKt, boolean surface) {
        return ROLL_UP * Math.sqrt(FireballModel.maxRadius(yieldKt, surface) / G);
    }

    /** Доля кольца 0..1: 0 — шар, 1 — кольцо (с четверти времени сворачивания до его конца, плавно). */
    public static double rollUp(double t, double yieldKt, boolean surface) {
        double tr = rollUpSeconds(yieldKt, surface);
        return smooth(0.25 * tr, tr, t);
    }

    /** Температура шара после второго максимума, K: T₂·(t/t_max)^−0,32 (до максимума — T₂). */
    public static double fireballTemperature(double t, double yieldKt) {
        double tm = FireballModel.secondMaximumSeconds(yieldKt);
        return FIREBALL_PEAK_K * Math.pow(Math.max(t, tm) / tm, -FIREBALL_COOLING);
    }

    /**
     * Нагрев края шапки — доля перепада температуры ядра ({@code MUSH_EDGE_HEAT} в {@code fx_pall.fsh}): край смешан
     * с подхваченным воздухом.
     */
    public static final double EDGE_HEAT = 0.35;

    /**
     * Температура видимого края шапки, K ({@link #EDGE_HEAT}): её свет виден у объёма гриба (пыль
     * внизу светит только шар — CloudPuffs). Ядро горячее, но под толщей края его не видно.
     */
    public static double edgeTemperature(double t, double yieldKt, boolean surface) {
        return AMBIENT_K + (coreTemperature(t, yieldKt, surface) - AMBIENT_K) * EDGE_HEAT;
    }

    /**
     * Температура ядра шапки, K: до сворачивания шапка — сам шар ({@link #fireballTemperature}), дальше кольцо остывает
     * смешением с воздухом: T_окр + ΔT_св·(t/t_св)^−0,27. Непрерывна на сворачивании.
     */
    public static double coreTemperature(double t, double yieldKt, boolean surface) {
        double tr = rollUpSeconds(yieldKt, surface);
        if (t <= tr) return fireballTemperature(t, yieldKt);
        double rolled = fireballTemperature(tr, yieldKt) - AMBIENT_K;
        return AMBIENT_K + rolled * Math.pow(t / tr, -CORE_COOLING);
    }

    /**
     * Доля конденсата и остывшего дыма в цвете шапки 0..1: бурый дым с NO₂ к белёсому облаку за минуту (G&D 2.08: бурый
     * цвет оксидов азота уходит, когда остывшая шапка поднимается и в ней конденсируется вода).
     */
    public static double condensed(double t) {
        return clamp(t / 60, 0, 1);
    }

    /**
     * Гриб в момент t (секунды после подрыва) у подрыва мощностью {@code yieldKt} на высоте {@code hobM} над землёй;
     * {@code groundFrontM} — докуда по земле дошёл фронт (у клиента — по его профилю фронта). Законы шапки, ножки и юбки
     * — прежних клубов гриба; шапка сворачивается в кольцо по {@link #rollUp}. null — гриба ещё нет или он уже рассеялся.
     */
    public static Shape shape(double t, double yieldKt, double hobM, boolean surface, double groundFrontM) {
        if (t <= 0) return null;
        double stab = CloudModel.stabilizationSeconds(yieldKt);
        double life = clamp(1 - (t - 2.5 * stab) / (1.5 * stab), 0, 1);
        if (life <= 0) return null;
        double hob = Math.max(0, hobM);
        double rf = FireballModel.maxRadius(yieldKt, surface);
        double start = hob + FireballModel.maxRadius(yieldKt, false);
        double endTop = Math.max(start, CloudModel.stabilizedTop(yieldKt));
        double top = CloudModel.top(t, hob, yieldKt);
        double capBot = Math.max(hob, CloudModel.capBottom(t, hob, yieldKt));
        double rise = clamp((top - start) / Math.max(1, endTop - start), 0, 1);
        double spread = 1 + 0.25 * clamp((t - stab) / stab, 0, 2);
        double capR = (rf * 1.1 + (CloudModel.capRadius(yieldKt) - rf * 1.1) * Math.pow(rise, 0.8)) * spread;
        // шапка не ниже шара: пока верх облака по CloudModel ниже, верх шапки — по её высоте
        double capH = Math.max(rf, top - capBot);
        top = capBot + capH;
        // Шапка — вихревое кольцо термика под куполом. Кольцо: большой радиус растёт от нуля (шар) до доли радиуса шапки,
        // край — на радиусе шапки; по высоте — от низа шапки почти до верха (середина — capY). Купол — над дыркой кольца,
        // до верха шапки; снизу у оси под ним выемка, в которую входит ножка (кольцо тянет воздух вверх сквозь дырку).
        // Клубы выступают за эти края на долю меньшей полуоси сечения кольца (MushroomField.REACH_CAP)
        double capY = capBot + 0.45 * capH;
        double roll = rollUp(t, yieldKt, surface);
        double ringR = RING * capR * roll, ringA = capR - ringR, ringB = 0.4 * capH;
        double domeR = 0.6 * capR * Math.sqrt(Math.max(roll, 0.05)), domeH = 0.35 * capH, domeY = capBot + 0.6 * capH;
        // шапка проступает, когда шар гаснет (он сам ею и становится): 50–110 t_max, как у прежних клубов
        double tau = t / FireballModel.secondMaximumSeconds(yieldKt);
        double show = smooth(50, 110, tau);
        // толща поперёк сечения постоянна, пока шапка поднимается (вещество растёт с подхваченным воздухом); растекаясь
        // наковальней, она редеет как площадь
        double capTau = TAU_CAP * show * life / (spread * spread);
        // ножка входит в выемку под куполом (воздух поднимается сквозь кольцо). У наземного шар касается земли, и пыль
        // уходит вверх за ним сразу: ножка — его след, без разрыва под шапкой. У воздушного пыль поднимают с земли ветры
        // за фронтом, и столб догоняет шапку за десятую часть подъёма; у высокого — тоньше и бледнее
        double catchUp = 0.1 * stab * smooth(rf, 2 * rf, hob);
        double stemTop = (capBot + 0.3 * capH) * (catchUp <= 0 ? 1 : clamp(t / catchUp, 0, 1));
        double stemShare = hob <= rf ? 1 : clamp(1.5 - hob / (rf * 4.8), 0.44, 1);
        double stemTau = TAU_STEM * stemShare * smooth(4, 20, tau) * life;
        double lowDust = Math.exp(-Math.max(0, t - stab * 0.1) / (hob <= rf ? DUST_SETTLE_SURFACE : DUST_SETTLE_AIR));
        // юбка — пыль у основания ножки, поднятая за фронтом: её край без клубов — на 0,85 пути фронта по земле (с клубами
        // — до него), не дальше 0,7 радиуса шапки. Пока фронт не дошёл до земли (воздушный), поднимать её нечему — юбки
        // нет; сечение — не меньше метра (единица клубов юбки делит)
        double outer = Math.min(Math.max(0, groundFrontM), capR * 0.7);
        double skirtR = 0.6 * outer, skirtW = Math.max(1, 0.25 * outer);
        double skirtH = Math.max(1, Math.min(capBot * 0.12, 350 * Math.cbrt(yieldKt / 15)) * smooth(0, 30, t));
        double skirtTau = outer <= 0 ? 0 : TAU_SKIRT * smooth(0.5, 3, t) * (hob <= rf * 4 ? 1 : 0.5) * lowDust * life;
        return new Shape(t, life, top, capBot, capY, capR, ringR, ringA, ringB, domeY, domeR, domeH, capTau, stemTop,
                capR * 0.16, stemTau, lowDust, skirtR, skirtW, skirtH, skirtTau, endTop);
    }

    /**
     * Скорость обращения вещества шапки по её сечению, рад/с: вещество вихревого кольца обходит сечение со скоростью
     * подъёма облака (Turner 1957: жидкость кольца движется со скоростью порядка его подъёма), путь — эллипс сечения
     * a × b, длиной около 2π·√((a² + b²)/2). До сворачивания так же обходит себя пузырь шара. У 15 кт оборот — около
     * 25 с на 20-й секунде и около трёх минут ко второй минуте, к стабилизации шапка стоит.
     */
    public static double churnRate(Shape s, double riseSpeed) {
        double mean = Math.sqrt(0.5 * (s.ringA() * s.ringA() + s.ringB() * s.ringB()));
        return riseSpeed / Math.max(mean, 1);
    }

    /**
     * Часы кипения шапки: ψ(t) — интеграл {@link #churnRate} от подрыва, рад (сколько радиан своего сечения прошло
     * вещество шапки). Функция возраста подрыва: перемотка повтора и вход позже дают ту же шапку. Таблица — по сетке
     * t = T·(i/N)² (гуще в начале, где подъём быстрее), T — три стабилизации: дальше подъём слабее начального в 10⁴ раз,
     * и часы стоят.
     */
    public static Churn churn(double yieldKt, double hobM, boolean surface) {
        double end = 3 * CloudModel.stabilizationSeconds(yieldKt), hob = Math.max(0, hobM);
        double[] turns = new double[CHURN_NODES + 1];
        double last = 0, prev = 0;
        for (int i = 1; i <= CHURN_NODES; i++) {
            double t = end * i * i / ((double) CHURN_NODES * CHURN_NODES);
            Shape s = shape(t, yieldKt, hob, surface, 0);
            double rate = s == null ? 0 : churnRate(s, CloudModel.riseSpeed(t, hob, yieldKt));
            // у подрыва (t = 0) шапки ещё нет, обращение — как в первом узле
            if (i == 1) last = rate;
            turns[i] = turns[i - 1] + 0.5 * (last + rate) * (t - prev);
            last = rate;
            prev = t;
        }
        return new Churn(end, turns);
    }

    /** Узлов таблицы часов кипения. */
    static final int CHURN_NODES = 256;

    /** Часы кипения одного подрыва ({@link #churn}): {@code turns[i]} — в момент end·(i/N)². */
    public record Churn(double end, double[] turns) {
        /** Часы в момент t, рад: между узлами — линейно, после конца таблицы стоят. */
        public double at(double t) {
            int n = turns.length - 1;
            if (t <= 0) return 0;
            if (t >= end) return turns[n];
            double x = n * Math.sqrt(t / end);
            int i = Math.min((int) x, n - 1);
            double t0 = end * i * i / ((double) n * n), t1 = end * (i + 1) * (i + 1) / ((double) n * n);
            return turns[i] + (turns[i + 1] - turns[i]) * (t - t0) / (t1 - t0);
        }
    }

    /**
     * Радиус ножки на доле её высоты w (0 — земля, 1 — низ шапки): шире у шапки (её тянет кольцо) и у земли — раструб пыли,
     * который оседает с {@code lowDust}. {@code mushStemR} в {@code mushroom.glsl} — то же.
     */
    public static double stemRadius(double w, double stemR, double lowDust) {
        double x = clamp(w, 0, 1), base = Math.pow(1 - x, 6);
        return stemR * (0.75 + 0.9 * x * x) + stemR * 1.6 * base * lowDust;
    }

    /** Снос ветром на высоте h, доля пути ветра за время t: верх сносит сильнее низа (как у прежних клубов). */
    public static double driftShare(double h, double endTop) {
        return clamp(h / Math.max(endTop, 1), 0, 1);
    }

    static double smooth(double e0, double e1, double x) {
        double f = clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
