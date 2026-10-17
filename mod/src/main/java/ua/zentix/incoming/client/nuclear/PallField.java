package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;

/**
 * Пелена после ударной волны (план «ядерка уровня кино», шаг 2): поле плотности пыли и дыма одного подрыва по радиусу
 * от эпицентра и времени. Его рисует объём слоя эффектов ({@code fx/layer/PallPass}: лучи по полю, умноженному на
 * трёхмерный шум), по нему же видимость, звук, пепел и тень у камеры ({@link NukePall}). Чистая математика по модели
 * подрыва, без мира и без нового пакета: что волна рушит и валит, клиент знает из давления ({@link BlastModel}) и
 * прихода фронта ({@link Detonation#arrivalTicks}), как и сервер.
 * <p>
 * <b>Пыль.</b> Где прошёл фронт, земля и обломки поднимают пыль: покрытие {@link #cover} растёт с давлением на земле
 * от {@link #COVER_FROM_PSI} (сдувает пыль и сухую землю) до {@link #COVER_FULL_PSI} (рушатся дома). Пелена встаёт за
 * фронтом сразу на {@link #RISE_FROM_M} м и растёт к высоте {@link #topMetres} (у центра 150 м у 15 кт, к краю — десятки метров)
 * со скоростью вначале {@link #RISE_SPEED} м/с, за минуты клубится ещё на треть выше ({@link #rise}), и густа несколько минут ({@link #DENSE_S}, e-кратно; в самой густой видно на {@link #DENSE_VISIBILITY_M} м), её
 * сносит ветер подрыва ({@link #DRIFT}).
 * <p>
 * <b>Мгла.</b> Над зоной поджига (световой импульс от {@link ThermalModel#IGNITE}) горит город: дымная мгла
 * (видно на {@link #HAZE_VISIBILITY_M} м) встаёт за пару минут и держится до утра ({@link #hazeEndTicks}; утро — по часам
 * мира, {@link #daylight}: сон и {@code /time} его приближают), тянется по ветру шлейфом; снизу её подсвечивают пожары.
 * <p>
 * Время: высота и густота — по настоящим секундам (тики клиента / 20): Артём просил «несколько минут реального
 * времени»; где фронт — по часам фронта в игре. Длины — в метрах модели и блоках через масштаб подрыва.
 * Таблица по радиусу ({@link #row}) — то, что получает шейдер: всё дорогое (давление, приход фронта) считается здесь.
 */
public final class PallField {
    /** Узлов таблицы по радиусу (ширина строки текстуры слоя). */
    public static final int BINS = 128;
    /** Покрытие пылью: от этого давления на земле, psi (сдувает пыль, сухую землю, листья). */
    static final double COVER_FROM_PSI = 0.6;
    /** …и полное с этого (рушатся дома, валится лес, сдирается дёрн). */
    static final double COVER_FULL_PSI = 6;
    /** Видимость в самой густой пыли, м (по Кошмидеру — ослабление 3,912 / V). */
    static final double DENSE_VISIBILITY_M = 40;
    /** Видимость в дымной мгле над горящим городом, м. */
    static final double HAZE_VISIBILITY_M = 900;
    /**
     * Пыль за фронтом сразу стоит на столько метров (у 15 кт): её поднял сам напор — пыль у фронта выше 30 м
     * (Glasstone &amp; Dolan, §3.81: «precursor» пыли выше 100 футов).
     */
    static final double RISE_FROM_M = 25;
    /**
     * …и растёт к своей высоте ({@link #topMetres}) со скоростью вначале столько м/с, потом всё медленнее — оценка по
     * базисной волне, которая клубится вверх десятки секунд и минуты (G&amp;D §2.73: у «Бейкера» быстро 270 м и 550 м
     * к 4-й минуте; §2.84: у «Уаху» её ход за 10 с падает со 120 до 30 км/ч): камера в сотне метров над городом в первые
     * секунды видит волну и город и только потом тонет в пыли.
     */
    static final double RISE_SPEED = 4;
    /** Густая пыль оседает e-кратно за столько секунд (реальных). */
    static final double DENSE_S = 150;
    /** Пыль тоньше этой доли густоты не рисуется (≈ 6,5 {@link #DENSE_S}: 16 минут). */
    static final double GONE = 0.0015;
    /** Мгла встаёт над пожарами: от стольких секунд после фронта до стольких. */
    static final double HAZE_FROM_S = 15, HAZE_FULL_S = 150;
    /** Мгла держится не меньше стольких игровых тиков (5 минут) и гаснет за последнюю минуту. */
    static final long HAZE_MIN_TICKS = 6000, HAZE_FADE_TICKS = 1200;
    /** Тиков в игровых сутках; утро (6:00) — время суток, кратное им, день — их первая половина. */
    static final long DAY = 24000;
    /**
     * Время суток ушло вперёд игрового времени больше чем на столько (сон, {@code /time add}) — это прыжок. Поправки
     * сервера ({@code ClientNuclear.ClockLead}: отставая, он отводит часы клиента назад и на сотни тиков) двигают оба
     * вместе и прыжком не выходят.
     */
    static final long SKIP_TICKS = 100;
    /** Назад — больше чем на столько ({@code /time set day}). */
    static final long SET_TICKS = 1200;
    /** Пыль сносит долей ветра подрыва: у земли ветер слабее, тяжёлое оседает. */
    static final double DRIFT = 0.5;
    /** Шлейф мглы по ветру — не длиннее стольких радиусов зоны пожаров. */
    static final double PLUME_MAX = 2;
    /** Высота мглы над пожарами, м (у 15 кт): дым поднимается выше пыли. */
    static final double HAZE_TOP_M = 260;

    /** Ослабление света в самой густой пыли и в мгле, 1/м. */
    static final double SIGMA_DENSE = Sight.KOSCHMIEDER / DENSE_VISIBILITY_M, SIGMA_HAZE = Sight.KOSCHMIEDER / HAZE_VISIBILITY_M;

    private final Detonation d;
    /** Докуда таблица по земле (где покрытие кончается), блоки. */
    private final double reach;
    /** Радиус зоны пожаров по земле, блоки (0 — пожаров нет). */
    private final double fireReach;
    /** По узлам: приход фронта (тики), покрытие 0..1, высота пелены (блоки), доля зоны пожаров 0..1. */
    private final double[] arrival = new double[BINS], cover = new double[BINS], top = new double[BINS], fire = new double[BINS];
    /** Пыль за фронтом сразу стоит на этой высоте (блоки) и растёт со скоростью вначале столько блоков в секунду. */
    private final double riseFrom, riseSpeed;
    /** Когда мгла гаснет по прошедшим тикам — тики после подрыва (утро, если часы мира идут ровно). */
    private final long hazeEnd;
    /**
     * Когда мгла гаснет на самом деле — тики после подрыва: не позже {@link #hazeEnd} и утра по часам мира
     * ({@link #daylight}). Пишет тик клиента, читает и кадр — один поток.
     */
    private double end;
    /** Часы мира в миг подрыва: игровое время и время суток. */
    private final long burstGame, burstDay;
    /** Прошлый замер часов мира ({@link #daylight}): игровое время и время суток; до первого — миг подрыва. */
    private long lastGame, lastDay;
    /** Прошлый замер — тиком раньше на глазах у клиента, а не миг подрыва (вход в мир, перемотка повтора). */
    private boolean watched;

    /**
     * @param dayTime время суток мира в миг подрыва (тики, как {@code Level.getDayTime}): мгла держится до утра
     */
    public PallField(Detonation d, long dayTime) {
        this.d = d;
        this.hazeEnd = hazeEndTicks(dayTime);
        this.end = hazeEnd;
        this.burstGame = d.gameTime();
        this.burstDay = dayTime;
        this.lastGame = burstGame;
        this.lastDay = burstDay;
        double hob = Math.max(0, d.hobMetres()), ys = Math.cbrt(d.yieldKt() / 15);
        double coverM = groundRange(BlastModel.rangeForOverpressure(BlastModel.kpa(COVER_FROM_PSI), d.yieldKt()), hob);
        double ignite = groundRange(ThermalModel.rangeForFluence(ThermalModel.IGNITE, d.yieldKt(), d.surface(), d.visibility()), hob);
        this.fireReach = d.blocks(ignite);
        this.reach = Math.max(1, d.blocks(Math.max(coverM, ignite)) * 1.02);
        this.riseFrom = Math.max(MIN_TOP_BLOCKS, d.blocks(RISE_FROM_M * ys));
        this.riseSpeed = d.blocks(RISE_SPEED);
        for (int i = 0; i < BINS; i++) {
            double rb = radius(i), rm = d.metres(rb);
            double slant = Math.hypot(rm, hob);
            arrival[i] = d.arrivalTicks(d.blocks(slant));
            cover[i] = cover(BlastModel.psi(BlastModel.overpressureKpa(Math.max(1, slant), d.yieldKt())));
            top[i] = Math.max(MIN_TOP_BLOCKS, d.blocks(topMetres(rm, ys)));
            fire[i] = ignite <= 0 ? 0 : 1 - smooth(ignite * 0.85, ignite * 1.05, rm);
        }
    }

    /** Ниже стольких блоков пелена не бывает (малый масштаб удара): иначе её не видно. */
    static final double MIN_TOP_BLOCKS = 6;

    private static double groundRange(double slant, double hob) {
        return Math.sqrt(Math.max(0, slant * slant - hob * hob));
    }

    /** Подрыв поля. */
    public Detonation detonation() {
        return d;
    }

    /** Докуда по земле от эпицентра (до сноса ветром) есть пыль или мгла, блоки. */
    public double reach() {
        return reach;
    }

    /** Радиус зоны поджига по земле, блоки (0 — пожаров нет): над ней мгла, в ней огненный шторм ({@link FireStorm}). */
    double fireReach() {
        return fireReach;
    }

    /** Когда мгла гаснет (утро) — тики после подрыва; к нему гаснет и огненный шторм. */
    double end() {
        return end;
    }

    /** Верх мглы над пожарами, блоки (у 15 кт — 260 м): до него встают столбы дыма шторма. */
    double hazeTop() {
        return d.blocks(HAZE_TOP_M * Math.cbrt(d.yieldKt() / 15));
    }

    /** Радиус узла i, блоки (середины узлов от 0 до {@link #reach}). */
    double radius(int i) {
        return (i + 0.5) / BINS * reach;
    }

    /**
     * Тик клиента: часы мира — игровое время {@code gameTime} и время суток {@code dayTime}; {@code ticks} — тики после
     * подрыва. Пока часы идут ровно, мгла ждёт утра, выбранного в миг подрыва ({@link #hazeEndTicks}: подрыв на рассвете —
     * до следующего). Прыгнули (сон, {@code /time add}, {@code /time set}) — утро заново по времени суток после прыжка:
     * уже день, а был ночью или часы прошли через восход, — мгла гаснет; иначе — к ближайшему восходу, но не раньше
     * 5 минут после подрыва ({@link #HAZE_MIN_TICKS}). Позже, чем ждала до прыжка, мгла не гаснет. Часы стоят
     * ({@code doDaylightCycle false}) — гаснет по прошедшим тикам.
     * <p>
     * Прыжок — сдвиг времени суток против игрового времени: оба приходят одним пакетом времени и вместе идут тиками
     * клиента. Первый замер после входа в мир и перемотки повтора сравнивается с мигом подрыва (время суток подрыва —
     * с сервера, {@link Detonation#dayTime}): прыжок, которого клиент не видел, — тот же, что видели все, только уже без
     * минуты угасания (он был раньше). Кроме {@code /time set} назад на время суток позже подрыва: для вошедшего оно
     * отстало от игрового, как у стоявших часов, и отличить их нельзя — мгла у него ждёт утра от подрыва, дольше, чем
     * у видевших прыжок.
     */
    public void daylight(double ticks, long gameTime, long dayTime) {
        long shift = (dayTime - gameTime) - (lastDay - lastGame);
        // назад — только если ушло назад и время суток: у стоящих часов оно отстаёт от игрового, но не прыгает
        if (shift > SKIP_TICKS || shift < -SET_TICKS && dayTime - lastDay < -SET_TICKS) {
            // по времени суток, а не по числу часов: /time set ставит их как есть, и в мире после первых суток
            // «назад» бывает и на ночь позже прежней (85000 → 18000 — с вечера на полночь)
            boolean dawn = day(dayTime) && (!day(lastDay) || Math.floorDiv(dayTime, DAY) > Math.floorDiv(lastDay, DAY));
            double by = dawn ? ticks : Math.max(ticks + Math.floorMod(-dayTime, DAY), HAZE_MIN_TICKS);
            // на глазах — раньше не скачком: гаснет не быстрее чем за минуту (уже гаснет — как гасла)
            if (by < end) end = Math.min(end, watched ? Math.max(by, ticks + HAZE_FADE_TICKS) : by);
        }
        lastGame = gameTime;
        lastDay = dayTime;
        watched = true;
    }

    /** День — первая половина суток (с 6:00 до 18:00). */
    private static boolean day(long dayTime) {
        return Math.floorMod(dayTime, DAY) < DAY / 2;
    }

    /**
     * Перемотка повтора: часы мира — с другого места записи; утро — снова от мига подрыва, и первый замер сравнивается
     * с ним (прыжок часов до этого места записи — как у вошедшего в мир).
     */
    public void rewound() {
        end = hazeEnd;
        lastGame = burstGame;
        lastDay = burstDay;
        watched = false;
    }

    // ---------------------------------------------------------------- законы (общие с шейдером fx_pall)

    /** Покрытие пылью 0..1 по давлению на земле, psi: гладко по логарифму от {@link #COVER_FROM_PSI} до {@link #COVER_FULL_PSI}. */
    static double cover(double psi) {
        if (psi <= COVER_FROM_PSI) return 0;
        return smooth(0, 1, Math.log(psi / COVER_FROM_PSI) / Math.log(COVER_FULL_PSI / COVER_FROM_PSI));
    }

    /**
     * Высота пелены над землёй, м, на {@code groundM} от эпицентра: у центра 150 м у 15 кт, к 1 км — 90, дальше —
     * к 25 м; по приведённой дальности, как давление ({@code ys} — кубический корень мощности от 15 кт).
     */
    static double topMetres(double groundM, double ys) {
        double x = groundM / (1100 * ys);
        return (25 + 125 / (1 + x * x)) * ys;
    }

    /**
     * Густота пыли по возрасту (секунды после фронта): встаёт за пару секунд, оседает e-кратно за {@link #DENSE_S};
     * 0 — до фронта и после {@link #GONE}.
     */
    static double dustEnvelope(double ageS) {
        if (ageS <= 0) return 0;
        double e = smooth(0, 2.5, ageS) * Math.exp(-ageS / DENSE_S);
        return e < GONE ? 0 : e;
    }

    /**
     * Высота пелены по возрасту (секунды после фронта), в единицах {@code top} и {@code from}: сразу {@code from}, дальше
     * растёт к {@code top} со скоростью вначале {@code speed} (те же единицы в секунду), всё медленнее (e-кратно), и за
     * минуты клубится ещё на треть выше; 0 — до фронта.
     */
    static double rise(double top, double from, double speed, double ageS) {
        if (ageS <= 0) return 0;
        double base = Math.min(from, top), span = top - base;
        double h = span <= 0 ? top : base + span * (1 - Math.exp(-speed * ageS / span));
        return h * (1 + 0.35 * (1 - Math.exp(-ageS / 80)));
    }

    /**
     * Профиль по высоте: доля густоты у земли на высоте {@code x} пелены над землёй под эпицентром (0 — земля, 1 — верх):
     * гуще у земли, к верху мягко тает. Ниже (долина) — как у земли, глубже 0,4 высоты пелены тает к нулю (на всей
     * высоте ниже земли её нет): пыль стекает в долины, но в пещерах под эпицентром её нет. Где за пикселем нет мира
     * (пустота за дальностью прорисовки без Distant Horizons), луч пелены кончается у земли под эпицентром
     * ({@code fx_pall.fsh}).
     */
    static double profile(double x) {
        if (x <= 0) return smooth(-1, -0.4, x);
        return (1 - smooth(0.45, 1.0, x)) * (0.55 + 0.45 * Math.exp(-2.5 * x));
    }

    /**
     * Столб над точкой на высоте {@code x} пелены — интеграл {@link #profile} от x до верха в долях высоты: им шейдер
     * считает тень и свет неба внутри пелены. Приближение (с интегралом его сверяет тест: ошибка меньше 0,01).
     */
    static double column(double x) {
        double xa = Math.min(1, Math.max(0, x));
        double above = 0.548 * Math.pow(1 - xa, 2.45) * (1 + 0.83 * xa);
        if (x >= 0) return above;
        // ниже земли: до −0,4 профиль 1, дальше к −1 тает (интеграл гладкой ступени — точно)
        double f = Math.max(0, Math.min(1, (-x - 0.4) / 0.6));
        return above + Math.min(-x, 0.4) + 0.6 * (f - f * f * f + 0.5 * f * f * f * f);
    }

    /** Мгла по времени: встаёт над пожарами за пару минут после фронта, к утру ({@link #end}) гаснет. */
    double hazeEnvelope(double ageS, double ticks) {
        if (ageS <= HAZE_FROM_S) return 0;
        return smooth(HAZE_FROM_S, HAZE_FULL_S, ageS) * (1 - smooth(end - HAZE_FADE_TICKS, end, ticks));
    }

    /**
     * Сколько игровых тиков после подрыва до утра (время суток 0 — 6:00), но не меньше {@link #HAZE_MIN_TICKS}: город
     * горит до утра; подрыв на рассвете — до следующего.
     */
    static long hazeEndTicks(long dayTime) {
        long toMorning = Math.floorMod(-dayTime, 24000L);
        while (toMorning < HAZE_MIN_TICKS) toMorning += 24000;
        return toMorning;
    }

    // ---------------------------------------------------------------- таблица для шейдера

    /** Ослабление 1/м → 1/блок (блок — 1/scale метра). */
    private double perBlock(double sigmaM) {
        return sigmaM / d.scale();
    }

    /** Секунды пыли узла i в момент {@code ticks} после подрыва: от прихода фронта, настоящие. */
    private double age(int i, double ticks) {
        return (ticks - arrival[i]) / 20.0;
    }

    /**
     * Строка таблицы в момент {@code ticks} после подрыва (тики клиента): по {@link #BINS} узлам 8 чисел — пыль
     * ({@link #dust}) и мгла ({@link #haze}). Пишет в {@code out} (длина ≥ 8·BINS): сначала все узлы пыли, потом мглы.
     */
    public void row(double ticks, float[] out) {
        double[] v = new double[4];
        for (int i = 0; i < BINS; i++) {
            dust(i, ticks, v);
            for (int k = 0; k < 4; k++) out[4 * i + k] = (float) v[k];
            haze(i, ticks, v);
            for (int k = 0; k < 4; k++) out[4 * (BINS + i) + k] = (float) v[k];
        }
    }

    /**
     * Пыль узла i: ослабление у земли (1/блок), высота пелены (блоки), доля дыма в ней 0..1 (над пожарами пыль темнеет
     * от сажи: пожары разгораются минуты — первые полминуты пелена бурая от пыли, к пятой минуте над огнём серая), 0.
     */
    void dust(int i, double ticks, double[] out) {
        double age = age(i, ticks);
        out[0] = cover[i] <= 0 ? 0 : perBlock(SIGMA_DENSE) * Math.pow(cover[i], 1.3) * dustEnvelope(age);
        out[1] = Math.max(rise(top[i], riseFrom, riseSpeed, age), 0.05 * top[i]);
        out[2] = fire[i] * smooth(30, 300, age);
        out[3] = 0;
    }

    /** Мгла узла i (по расстоянию от оси шлейфа): ослабление (1/блок), высота (блоки), свет пожаров снизу 0..1, 0. */
    void haze(int i, double ticks, double[] out) {
        double age = age(i, ticks);
        out[0] = perBlock(SIGMA_HAZE) * fire[i] * hazeEnvelope(age, ticks);
        out[1] = Math.max(top[i] * 1.4, d.blocks(HAZE_TOP_M * Math.cbrt(d.yieldKt() / 15)) * (0.5 + 0.5 * smooth(HAZE_FROM_S, HAZE_FULL_S, age)));
        out[2] = fire[i] * fireLight(age, ticks);
        out[3] = 0;
    }

    /** Свет пожаров снизу 0..1: разгораются за минуту после волны, к утру гаснут. */
    double fireLight(double ageS, double ticks) {
        if (ageS <= 0) return 0;
        return smooth(5, 60, ageS) * (1 - smooth(end - HAZE_FADE_TICKS, end, ticks));
    }

    /**
     * Есть ли в момент {@code ticks} хоть что-то от пелены: мгла (только над пожарами) или пыль — пока фронт идёт по
     * таблице и пока не осела у края (туда фронт пришёл позже всего).
     */
    public boolean visible(double ticks) {
        return ticks > 0 && (fireReach > 0 && ticks < end || ticks <= arrival[BINS - 1] || dustEnvelope(age(BINS - 1, ticks)) > 0);
    }

    /** Снос пыли ветром к моменту {@code ticks}, блоки (x, z) — в {@code out}. */
    public void drift(double ticks, double[] out) {
        double s = d.blocks(d.windSpeed()) * DRIFT * ticks / 20.0;
        out[0] = Math.cos(d.windDir()) * s;
        out[1] = Math.sin(d.windDir()) * s;
    }

    /** Длина шлейфа мглы по ветру к моменту {@code ticks}, блоки: растёт с ветром, не длиннее {@link #PLUME_MAX} зон пожаров. */
    public double plume(double ticks) {
        return Math.min(PLUME_MAX * fireReach, d.blocks(d.windSpeed()) * ticks / 20.0);
    }

    /** Мгла вдоль шлейфа редеет: в конце шлейфа длины {@code len} — вдвое ({@code along} — от эпицентра по ветру). */
    static double plumeThin(double along, double len) {
        return 1 - 0.5 * along / Math.max(len, 1);
    }

    /** Край таблицы (u — доля {@link #reach}): к нему поле тает, дальше — ноль. */
    static double edge(double u) {
        return 1 - smooth(0.96, 1, u);
    }

    /**
     * Ослабление в точке без шума (среднее по шуму — 1), 1/блок: пыль (снесённая ветром) и мгла (шлейф по ветру) —
     * так же, как {@code pallField} в {@code pall.glsl} по таблице. Точка — от эпицентра по земле (dx, dz, блоки)
     * и высота над землёй под эпицентром (блоки). Для того, что у камеры: звук, пепел.
     */
    public double sigma(double dx, double dz, double above, double ticks) {
        double[] w = new double[2], v = new double[4];
        drift(ticks, w);
        double sum = 0;
        double ud = Math.hypot(dx - w[0], dz - w[1]) / reach;
        if (ud < 1) {
            dust(bin(ud * reach), ticks, v);
            sum += v[0] * edge(ud) * profile(above / Math.max(v[1], 1));
        }
        double len = plume(ticks), ux = Math.cos(d.windDir()), uz = Math.sin(d.windDir());
        double along = Math.max(0, Math.min(len, dx * ux + dz * uz));
        double uh = Math.hypot(dx - ux * along, dz - uz * along) / reach;
        if (uh < 1) {
            haze(bin(uh * reach), ticks, v);
            sum += v[0] * plumeThin(along, len) * edge(uh) * profile(above / Math.max(v[1], 1));
        }
        return sum;
    }

    /**
     * Сколько пепла сыплется на землю в точке 0..1 ({@code NukeAsh}): из оседающей пыли — с 15–60 с после волны, пока
     * пыль густа, из мглы над пожарами — пока горит город (вдоль шлейфа реже).
     */
    public double ashRate(double dx, double dz, double ticks) {
        double[] w = new double[2];
        drift(ticks, w);
        double ash = 0;
        double ud = Math.hypot(dx - w[0], dz - w[1]) / reach;
        if (ud < 1) {
            int i = bin(ud * reach);
            double age = age(i, ticks);
            ash = cover[i] * dustEnvelope(age) * smooth(15, 60, age) * edge(ud);
        }
        double len = plume(ticks), ux = Math.cos(d.windDir()), uz = Math.sin(d.windDir());
        double along = Math.max(0, Math.min(len, dx * ux + dz * uz));
        double uh = Math.hypot(dx - ux * along, dz - uz * along) / reach;
        if (uh < 1) {
            int i = bin(uh * reach);
            ash = Math.max(ash, 0.8 * fire[i] * hazeEnvelope(age(i, ticks), ticks) * plumeThin(along, len) * edge(uh));
        }
        return Math.min(1, ash);
    }

    /** Доля клочков бумаги в пепле 0..1: где волна рушила дома (покрытие от 0,3), первые минуты после неё. */
    public double paperRate(double dx, double dz, double ticks) {
        double r = Math.hypot(dx, dz);
        if (r >= reach) return 0;
        int i = bin(r);
        double age = age(i, ticks);
        return smooth(0.3, 0.7, cover[i]) * smooth(5, 20, age) * (1 - smooth(120, 300, age));
    }

    private int bin(double r) {
        return Math.min(BINS - 1, Math.max(0, (int) (r / reach * BINS)));
    }

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
