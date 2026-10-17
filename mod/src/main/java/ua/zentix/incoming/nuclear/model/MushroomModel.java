package ua.zentix.incoming.nuclear.model;

/**
 * Гриб объёмом (DESIGN-nuke, шаги M1, M2): форма шара и шапки, купола, ножки и юбки, их вещество и температура по
 * времени. Метры, секунды, килотонны; высоты — над землёй под эпицентром. Шапка — сам огненный шар ({@link FireballModel}:
 * радиус, всплытие), который сворачивается в вихревое кольцо под куполом ({@link #rollUpSeconds}) — одной формой; её
 * верх, низ и радиус после этого — {@link CloudModel}, растекание после стабилизации и снос ветром — как у прежних клубов
 * гриба. Клиент (поле в шейдере) берёт числа отсюда раз в кадр.
 */
public final class MushroomModel {
    /** Температура воздуха вокруг, K. */
    public static final double AMBIENT_K = FireballModel.AMBIENT_K;
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
     * @param top       верх облака (у шара — его верх)
     * @param capBottom низ шапки (у шара — его низ)
     * @param capY      середина шапки по высоте (у шара — его середина)
     * @param capR      радиус шапки по горизонтали, до края кольца (у шара — его радиус)
     * @param ringR     большой радиус кольца (0 — ещё шар)
     * @param ringA     полуось сечения кольца по горизонтали
     * @param ringB     полуось сечения по вертикали
     * @param domeY     середина купола над кольцом
     * @param domeR     полуось купола по горизонтали
     * @param domeH     полуось купола по вертикали
     * @param capTau    толща шапки поперёк меньшего поперечника сечения (с долей гриба): шар и шапка непрозрачны
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

    /**
     * Нагрев края свернувшейся шапки — доля перепада температуры ядра: край смешан с подхваченным воздухом (термик
     * Мортона–Тейлора–Тёрнера: горячее всего — ядро кольца).
     */
    public static final double EDGE_HEAT = 0.35;
    /**
     * Языки клубов на краю: нагрев края ± эта доля его же, на размах клубов ({@code MUSH_SKIN} в {@code mushroom.glsl}):
     * выступ клуба — свежий горячий газ изнутри, ложбина — уже смешанный. У кольца — ±9 % перепада ядра, у шара — его
     * «кипение»: шар не ровный диск, а пятна ярче и темнее.
     */
    public static final double SKIN = 0.09 / EDGE_HEAT;
    /**
     * Размах клубов края у шара — доля их размаха у кольца: пока шар растёт, его край — гладкая сфера (фронт излучения
     * и ударной волны); клубы растут с подхваченным воздухом, когда он всплывает и сворачивается.
     */
    static final double BALL_BILLOWS = 0.3;

    /**
     * Доля перепада температуры ядра у видимого края 0..1: у шара — вся (он почти изотермичен: свет внутри выравнивает
     * температуру), сворачиваясь в кольцо, край смешивается с воздухом — до {@link #EDGE_HEAT}.
     */
    public static double edgeShare(double t, double yieldKt, boolean surface) {
        return 1 - (1 - EDGE_HEAT) * rollUp(t, yieldKt, surface);
    }

    /** Размах клубов края 0..1 против кольца: у шара — {@link #BALL_BILLOWS}, растёт со сворачиванием. */
    public static double billows(double t, double yieldKt, boolean surface) {
        return BALL_BILLOWS + (1 - BALL_BILLOWS) * rollUp(t, yieldKt, surface);
    }

    /**
     * Видимая температура шара и шапки снаружи, K: перепад ядра × {@link #edgeShare}. Пока шар — его температура
     * ({@link FireballModel#temperature}); свернувшись, он темнеет снаружи (край смешан с воздухом), а горячее ядро
     * светит из глубины — в ложбинах клубов (шейдер).
     */
    public static double edgeTemperature(double t, double yieldKt, boolean surface) {
        return AMBIENT_K + (coreTemperature(t, yieldKt, surface) - AMBIENT_K) * edgeShare(t, yieldKt, surface);
    }

    /**
     * Температура ядра шапки, K: до конца сворачивания шапка — сам шар ({@link FireballModel#temperature}), дальше кольцо
     * остывает смешением с воздухом: T_окр + ΔT_св·(t/t_св)^−0,27. Непрерывна на сворачивании.
     */
    public static double coreTemperature(double t, double yieldKt, boolean surface) {
        double tr = rollUpSeconds(yieldKt, surface);
        if (t <= tr) return FireballModel.temperature(t, yieldKt);
        double rolled = FireballModel.temperature(tr, yieldKt) - AMBIENT_K;
        return AMBIENT_K + rolled * Math.pow(t / tr, -CORE_COOLING);
    }

    /**
     * Свет шара и шапки на белой стене в {@code distanceM} метрах от середины шапки, в долях белой стены в полдень (без
     * привыкания глаза): ламбертов шар радиуса шапки с яркостью чёрного тела её видимой температуры освещает как
     * L·R²/d² (у поверхности — L). Источник света вокруг: на ножке, юбке, пыли, дыме, небе.
     */
    public static double light(Shape s, double yieldKt, boolean surface, double distanceM) {
        double r = s.capR();
        return BlackBody.luminance(edgeTemperature(s.t(), yieldKt, surface)) * r * r / Math.max(distanceM * distanceM, r * r);
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
     * {@code groundFrontM} — докуда по земле дошёл фронт (у клиента — по его профилю фронта). Шапка — огненный шар
     * ({@link FireballModel#radius}, {@link FireballModel#centreHeight}), который по {@link #rollUp} сворачивается в кольцо
     * шапки: числа формы — от шара к шапке одной долей, без скачка. Законы шапки, ножки и юбки — прежних клубов гриба.
     * null — гриба ещё нет или он уже рассеялся.
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
        // Шапка — огненный шар, свернувшийся в кольцо: до четверти времени сворачивания — сфера шара (кольца нет, купол
        // внутри), дальше каждое число формы идёт от шара к шапке той же долей roll — одна форма, без смены картинки.
        // Шар для наземного — полусфера: середина на земле, низ под ней
        double roll = rollUp(t, yieldKt, surface);
        double ballR = FireballModel.radius(t, yieldKt, surface), ballY = FireballModel.centreHeight(t, hob, yieldKt);
        capR = lerp(ballR, capR, roll);
        capBot = lerp(ballY - ballR, capBot, roll);
        capH = lerp(2 * ballR, capH, roll);
        top = capBot + capH;
        double capY = lerp(ballY, capBot + 0.45 * capH, roll);
        double ringR = RING * capR * roll, ringA = capR - ringR, ringB = lerp(ballR, 0.4 * capH, roll);
        double domeR = 0.6 * capR * Math.sqrt(Math.max(roll, 0.05)), domeH = 0.35 * capH, domeY = lerp(ballY, capBot + 0.6 * capH, roll);
        double tau = t / FireballModel.secondMaximumSeconds(yieldKt);
        // толща поперёк сечения постоянна, пока шапка поднимается (вещество растёт с подхваченным воздухом); растекаясь
        // наковальней, она редеет как площадь. Шар — непрозрачное излучающее тело: та же толща
        double capTau = TAU_CAP * life / (spread * spread);
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

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    static double smooth(double e0, double e1, double x) {
        double f = clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
