package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.WaterModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PallFieldTest {
    /** 15 кт у земли в полдень, ветер 8 м/с, масштаб 1, стена пыли вдали — 0,42 скорости звука. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    private static final Detonation SMALL = new Detonation(2, new Vec3(0, 64, 0), 64, 1, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    /** Те же 15 кт, но сервер не поджигал (вода, высокий подрыв): ни мглы, ни её пепла — только пыль и брызги. */
    private static final Detonation NO_FIRES = new Detonation(4, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f,
            Detonation.NO_DAY_TIME, false);
    private static final long NOON = 6000;

    @Test
    void tableFitsTheLayer() {
        assertEquals(PallPass.WIDTH, PallField.BINS);
    }

    /** Покрытие пылью растёт с давлением: слабее 0,6 psi — нет, с 6 psi — полное. */
    @Test
    void coverRisesWithPressure() {
        assertEquals(0, PallField.cover(0.3));
        assertEquals(0, PallField.cover(0.6));
        assertEquals(1, PallField.cover(6), 1e-9);
        assertEquals(1, PallField.cover(40), 1e-9);
        double prev = 0;
        for (double psi = 0.6; psi <= 7; psi += 0.05) {
            double c = PallField.cover(psi);
            assertTrue(c >= prev - 1e-12, psi + " psi");
            prev = c;
        }
    }

    /** Столб над точкой (тень, свет неба) — интеграл профиля по высоте до верха, с ошибкой меньше 0,01. */
    @Test
    void columnIsIntegralOfProfile() {
        for (double x = -1.4; x <= 1.2; x += 0.05) {
            double sum = 0, step = 1e-4;
            for (double y = x + step / 2; y < 1.5; y += step) sum += PallField.profile(y) * step;
            assertEquals(sum, PallField.column(x), 0.01, "x = " + x);
        }
    }

    /** Пелена густа у земли, к верху тает; в долине под эпицентром — тоже, а в пещере глубже — нет. */
    @Test
    void profileThinsUpward() {
        assertEquals(1, PallField.profile(0), 1e-9);
        assertEquals(0, PallField.profile(1), 1e-9);
        assertEquals(0, PallField.profile(-1), 1e-9);
        assertEquals(1, PallField.profile(-0.3), 1e-9);
        double prev = 1;
        for (double x = 0; x <= 1; x += 0.02) {
            assertTrue(PallField.profile(x) <= prev + 1e-12, "x = " + x);
            prev = PallField.profile(x);
        }
    }

    /** У 15 кт пелена над центром — 150 м, к краю — десятки метров; у 1 кт — ниже. */
    @Test
    void heightAtCentreAndEdge() {
        assertEquals(150, PallField.topMetres(0, 1), 1e-9);
        assertTrue(PallField.topMetres(1000, 1) > 80 && PallField.topMetres(1000, 1) < 110);
        assertTrue(PallField.topMetres(5000, 1) < 35);
        assertTrue(PallField.topMetres(0, Math.cbrt(1 / 15.0)) < 70);
    }

    /** Пыль встаёт только там, где прошёл фронт волны: до него — ничего. */
    @Test
    void pallRisesOnlyBehindTheFront() {
        PallField p = new PallField(GROUND, NOON);
        double[] v = new double[4];
        for (double ticks : new double[]{20, 100, 300, 600, 1200}) {
            double front = GROUND.frontRadius(ticks);
            for (int i = 0; i < PallField.BINS; i++) {
                double r = p.radius(i);
                p.dust(i, ticks, v);
                if (r > front * 1.01) assertEquals(0, v[0], "r = " + r + " впереди фронта " + front + " через " + ticks + " тиков");
            }
            int behind = (int) (Math.min(front * 0.5, p.reach() * 0.5) / p.reach() * PallField.BINS);
            p.dust(behind, ticks, v);
            if (ticks >= 100) assertTrue(v[0] > 0, "за фронтом через " + ticks + " тиков пыли нет");
        }
    }

    /**
     * Верх пыли за фронтом растёт со скоростью, а не встаёт сразу: в 400 м от 15 кт сразу ~25 м (пыль, поднятая напором),
     * через 5 с — ниже 50, через 20 с — до 100, к минуте — выше 120 (камера в 120 м над городом тонет в пыли), а выше
     * своей высоты с клубами ({@link PallField#topMetres} × 1,35) — никогда; всё время растёт.
     */
    @Test
    void dustTopClimbsBehindTheFront() {
        PallField p = new PallField(GROUND, NOON);
        double[] v = new double[4];
        int i = (int) (400 / p.reach() * PallField.BINS);
        double r = p.radius(i), arrival = GROUND.arrivalTicks(r), top = PallField.topMetres(r, 1);
        double prev = 0;
        for (double age = 0.05; age <= 600; age += 0.25) {
            p.dust(i, arrival + 20 * age, v);
            assertTrue(v[1] >= prev - 1e-9, age + " с: верх пыли опустился");
            assertTrue(v[1] <= top * 1.35 + 1e-9, age + " с: выше своей высоты " + v[1]);
            prev = v[1];
        }
        p.dust(i, arrival + 1, v);
        assertEquals(PallField.RISE_FROM_M, v[1], 1);
        p.dust(i, arrival + 20 * 5, v);
        assertTrue(v[1] > 30 && v[1] < 50, "через 5 с верх на " + v[1] + " м");
        p.dust(i, arrival + 20 * 20, v);
        assertTrue(v[1] > 60 && v[1] < 100, "через 20 с верх на " + v[1] + " м");
        p.dust(i, arrival + 20 * 60, v);
        assertTrue(v[1] > 120, "через минуту верх на " + v[1] + " м");
    }

    /** У центра в самой густой пыли видно на ~40 м; через 5 минут — в разы дальше, через полчаса пыли нет. */
    @Test
    void denseForMinutesThenSettles() {
        PallField p = new PallField(GROUND, NOON);
        double[] v = new double[4];
        int centre = 2;
        double arrival = GROUND.arrivalTicks(p.radius(centre));
        p.dust(centre, arrival + 60, v);
        double visibility = Sight.KOSCHMIEDER / v[0];
        assertTrue(visibility > 35 && visibility < 60, "видно на " + visibility + " м через 3 с");
        p.dust(centre, arrival + 20 * 300, v);
        assertTrue(Sight.KOSCHMIEDER / v[0] > 200, "через 5 минут пыль всё ещё густая");
        p.dust(centre, arrival + 20 * 1800, v);
        assertEquals(0, v[0]);
    }

    /** Мгла над горящим городом держится до утра (время суток 0 — 6:00), но не меньше 5 минут. */
    @Test
    void hazeUntilMorning() {
        assertEquals(18000, PallField.hazeEndTicks(6000));
        assertEquals(6000, PallField.hazeEndTicks(18000));
        assertEquals(25000, PallField.hazeEndTicks(23000));
        assertEquals(24000, PallField.hazeEndTicks(0));
        assertEquals(24000 + 18000, PallField.hazeEndTicks(24000 * 3 + 6000) + 24000);
        PallField p = new PallField(GROUND, 18000);
        assertTrue(p.visible(5000));
        double[] v = new double[4];
        p.haze(1, 4000, v);
        assertTrue(v[0] > 0 && v[2] > 0, "в полночь город горит");
        p.haze(1, 6100, v);
        assertEquals(0, v[0], 1e-12);
        assertFalse(p.visible(40000));
    }

    /** Часы мира идут ровно с тиков {@code from} до {@code to}: время суток — {@code day0} плюс тики. */
    private static void clock(PallField p, long day0, int from, int to) {
        for (int t = from; t <= to; t++) p.daylight(t, t, day0 + t);
    }

    /** Мгла узла у центра в момент {@code ticks}, 1/блок. */
    private static double haze(PallField p, double ticks) {
        double[] v = new double[4];
        p.haze(1, ticks, v);
        return v[0];
    }

    /** Часы идут ровно — мгла гаснет к утру по прошедшим тикам, как без часов; часы стоят — тоже. */
    @Test
    void hazeEndsAtMorningWithSteadyOrStoppedClock() {
        PallField p = new PallField(GROUND, 18000);
        clock(p, 18000, 0, 6200);
        assertTrue(haze(p, 4000) > 0);
        assertEquals(0, haze(p, 6100), 1e-12);
        PallField stopped = new PallField(GROUND, 14000);
        for (int t = 0; t <= 9000; t++) stopped.daylight(t, t, 14000);
        assertTrue(haze(stopped, 8000) > 0, "часы стоят — мгла до утра по прошедшим тикам");
        assertEquals(0, haze(stopped, 10100), 1e-12);
    }

    /** Сон: часы прыгают к восходу — мгла гаснет за минуту, даже до 5 минут (подрыв в 5 утра ждал следующего утра). */
    @Test
    void sleepingThroughTheNightEndsTheHaze() {
        PallField evening = new PallField(GROUND, 14000);
        clock(evening, 14000, 0, 3000);
        assertTrue(haze(evening, 3000) > 0);
        evening.daylight(3001, 3001, 24000);
        assertTrue(haze(evening, 3100) > 0, "гаснет не скачком");
        assertEquals(0, haze(evening, 3001 + PallField.HAZE_FADE_TICKS + 1), 1e-12);

        PallField dawn = new PallField(GROUND, 23000);
        clock(dawn, 23000, 0, 300);
        dawn.daylight(301, 301, 24000);
        assertFalse(dawn.visible(301 + PallField.HAZE_FADE_TICKS + 20 * 60 * 30), "мгла ждала бы следующего утра");
        // без сна — правило 5 минут: восход через минуту не гасит её, гаснет к следующему утру
        PallField natural = new PallField(GROUND, 23000);
        clock(natural, 23000, 0, 3000);
        assertTrue(haze(natural, 3000) > 0);
        assertTrue(natural.visible(24000));
    }

    /** /time set day ночью — день: мгла гаснет; /time add — утро ближе; сервер отводит часы назад в сумерки — нет. */
    @Test
    void timeCommandsMoveTheMorning() {
        PallField set = new PallField(GROUND, 18000);
        clock(set, 18000, 0, 400);
        set.daylight(401, 401, 1000);
        assertEquals(0, haze(set, 401 + PallField.HAZE_FADE_TICKS + 1), 1e-12);

        PallField add = new PallField(GROUND, NOON);
        clock(add, NOON, 0, 100);
        add.daylight(101, 101, NOON + 100 + 6000);
        clock(add, NOON + 6000, 102, 11000);
        assertTrue(haze(add, 10000) > 0);
        assertEquals(0, haze(add, 12100), 1e-12, "утро на 6000 тиков ближе");

        PallField lag = new PallField(GROUND, NOON);
        clock(lag, NOON, 0, 6100);
        lag.daylight(6101, 6101, NOON + 5500);
        clock(lag, NOON - 600, 6102, 7000);
        assertTrue(haze(lag, 17000) > 0, "поправка часов в сумерки — не утро");
    }

    /**
     * Мир после первых суток: часы мира — 85000 (вечер четвёртых суток), {@code /time set 18000} ставит их как есть, на
     * полночь первых. Утро — ближайший восход по времени суток (через 6000 тиков), а не утро четвёртых суток по числу
     * часов; {@code /time set day} — уже утро.
     */
    @Test
    void timeSetInALaterDayFollowsTheTimeOfDay() {
        long evening = 3 * PallField.DAY + 13000;
        PallField set = new PallField(GROUND, evening);
        clock(set, evening, 0, 100);
        set.daylight(101, 101, 18000);
        clock(set, 18000 - 101, 102, 6300);
        assertTrue(haze(set, 4800) > 0, "до восхода мгла держится");
        assertEquals(0, haze(set, 101 + 6000 + 1), 1e-12, "восход через 6000 тиков после /time set — мгла погасла");
        assertTrue(haze(new PallField(GROUND, evening), 101 + 6000 + 1) > 0, "без прыжка часов — до утра через 11000");

        PallField day = new PallField(GROUND, evening);
        clock(day, evening, 0, 100);
        day.daylight(101, 101, 1000);
        assertEquals(0, haze(day, 101 + PallField.HAZE_FADE_TICKS + 1), 1e-12, "/time set day — уже утро");
    }

    /** {@code /time add} до самого рассвета: мгла держится 5 минут после подрыва (ещё не день), а не гаснет за минуту. */
    @Test
    void timeAddToDawnKeepsFiveMinutes() {
        PallField p = new PallField(GROUND, NOON);
        clock(p, NOON, 0, 100);
        p.daylight(101, 101, 23600);
        clock(p, 23600 - 101, 102, 7000);
        assertTrue(haze(p, PallField.HAZE_MIN_TICKS - PallField.HAZE_FADE_TICKS - 1) > 0, "через 4 минуты мгла ещё есть");
        assertTrue(haze(p, PallField.HAZE_MIN_TICKS - 300) > 0, "гаснет последнюю минуту, не раньше");
        assertEquals(0, haze(p, PallField.HAZE_MIN_TICKS + 1), 1e-12, "через 5 минут погасла");
        // часы потом сами проходят восход — ничего не меняется
        assertTrue(haze(p, 2000) > 0);
    }

    /**
     * Перемотка повтора: утро — снова от мига подрыва, и первый замер часов места записи сравнивается с ним: до сна мгла
     * снова есть, после сна её нет сразу (как и было в записи), а не гаснет заново минуту.
     */
    @Test
    void rewindFollowsTheClockOfThePlace() {
        PallField p = new PallField(GROUND, 14000);
        clock(p, 14000, 0, 3000);
        p.daylight(3001, 3001, 24000);
        assertEquals(0, haze(p, 5000), 1e-12);
        p.rewound();
        p.daylight(2000, 2000, 16000);
        assertTrue(haze(p, 2000) > 0, "до сна");
        p.rewound();
        p.daylight(5000, 5000, 26000);
        assertEquals(0, haze(p, 5000), 1e-12, "после сна");
    }

    /**
     * Вошедший позже (вход в мир, смена измерения): время суток подрыва — с сервера, и сон, которого он не видел, — тот
     * же прыжок: ночной удар, игроки проспали до утра — мглы нет, а не ждёт следующего утра (время суток подрыва по
     * нынешним часам вышло бы 5:30 — меньше чем за 5 минут до восхода).
     */
    @Test
    void lateJoinerAfterSleepSeesTheMorning() {
        PallField late = new PallField(GROUND, 14000);
        late.daylight(2000, 2000, 24000 + 1500);
        assertEquals(0, haze(late, 2000), 1e-12);
        PallField guessed = new PallField(GROUND, 24000 + 1500 - 2000);
        guessed.daylight(2000, 2000, 24000 + 1500);
        assertTrue(haze(guessed, 2000) > 0, "по нынешним часам — до следующего утра");

        // /time set ночью на вечер: тот же восход, что у видевших прыжок
        PallField seen = new PallField(GROUND, NOON);
        clock(seen, NOON, 0, 1000);
        seen.daylight(1001, 1001, 13000);
        clock(seen, 13000 - 1001, 1002, 2000);
        PallField joined = new PallField(GROUND, NOON);
        joined.daylight(2000, 2000, 13000 + 999);
        for (double t : new double[]{2000, 9000, 11000, 12000}) assertEquals(haze(seen, t), haze(joined, t), 1e-12, "тик " + t);
    }

    /** Часы стоят ({@code doDaylightCycle false}): время суток отстаёт от игрового — это не прыжок и у вошедшего позже. */
    @Test
    void lateJoinerWithStoppedClock() {
        PallField late = new PallField(GROUND, 23000);
        late.daylight(9000, 9000, 23000);
        assertTrue(haze(late, 9000) > 0, "утро по прошедшим тикам — через 25000");
        assertEquals(haze(new PallField(GROUND, 23000), 9000), haze(late, 9000), 1e-12);
    }

    /**
     * Часы клиента убежали вперёд отстающего сервера (вместе игровое время и время суток) — не прыжок: подрыв за 10 секунд
     * до восхода, пакет пришёл, когда часы клиента на 15 секунд впереди, уже за восходом, — мгла ждёт следующего утра,
     * как у всех; поправка часов назад потом — тоже не прыжок.
     */
    @Test
    void clientClockLeadIsNotAJump() {
        PallField p = new PallField(GROUND, 23800);
        p.daylight(0, 300, 24100);
        p.daylight(1, 1, 23801);
        clock(p, 23800, 2, 3000);
        assertTrue(haze(p, 3000) > 0, "до следующего утра");
        assertTrue(p.visible(20000));
    }

    /** Без пожаров (высокий подрыв: поджига на земле нет) мглы нет — пелена кончается с пылью, а не утром. */
    @Test
    void withoutFiresNoHazeUntilMorning() {
        Detonation high = new Detonation(3, new Vec3(0, 64 + 3000, 0), 64, 15, false, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
        PallField p = new PallField(high, 23000);
        assertTrue(p.reach() > 100, "пыль есть");
        assertEquals(0, haze(p, 20 * 300), 1e-12);
        assertTrue(p.visible(200));
        assertFalse(p.visible(24000), "утро через 25000 тиков, а пыль уже осела");
        assertTrue(new PallField(GROUND, 23000).visible(24000));
    }

    /** Ослабление в точке — то же, что даёт таблица (по ней считает шейдер), и снос ветром уносит пыль. */
    @Test
    void sigmaFollowsTheTable() {
        PallField p = new PallField(GROUND, NOON);
        double ticks = 20 * 120;
        float[] row = new float[8 * PallField.BINS];
        p.row(ticks, true, false, row);
        double[] w = new double[2];
        p.drift(ticks, w);
        int i = 30;
        double r = p.radius(i);
        // по ветру от эпицентра, у земли: пыль узла i (мгла сюда тоже дотягивается)
        double ux = Math.cos(GROUND.windDir()), uz = Math.sin(GROUND.windDir());
        double s = p.sigma(w[0] + ux * r, w[1] + uz * r, 0, ticks, PallField.Water.DRY);
        assertTrue(s >= row[4 * i] * 0.98, "пыль у земли " + s + " < таблицы " + row[4 * i]);
        assertTrue(s <= row[4 * i] + row[4 * (PallField.BINS + 2)] * 1.01 + 1e-9);
        // выше пелены — ничего
        assertEquals(0, p.sigma(w[0] + ux * r, w[1] + uz * r, row[4 * i + 1] * 2 + 400, ticks, PallField.Water.DRY), 1e-12);
        assertTrue(w[0] > 0 && w[1] > 0, "ветер на 0,5 рад — снос по +x и +z");
    }

    /** Меньший удар — меньше пелена. */
    @Test
    void smallerYieldSmallerPall() {
        assertTrue(new PallField(SMALL, NOON).reach() < 0.6 * new PallField(GROUND, NOON).reach());
        double reach = new PallField(GROUND, NOON).reach();
        assertTrue(reach > 4500 && reach < 6000, "пелена 15 кт до " + reach + " блоков");
    }

    /** Пепел сыплется не раньше волны, у центра — через минуту есть, бумага — только где рушились дома. */
    @Test
    void ashAfterTheWave() {
        PallField p = new PallField(GROUND, NOON);
        assertEquals(0, p.ashRate(800, 0, 10, PallField.Water.DRY));
        assertTrue(p.ashRate(800, 0, 20 * 90, PallField.Water.DRY) > 0.1);
        assertTrue(p.paperRate(600, 0, 20 * 30, PallField.Water.DRY) > 0.3);
        assertEquals(0, p.paperRate(4500, 0, 20 * 30, PallField.Water.DRY), 1e-9);
        assertTrue(p.ashRate(1000, 0, 20 * 60 * 8, PallField.Water.DRY) > 0, "мгла над пожарами сыплет пепел");
    }
    /**
     * Над водой пыли нет — брызги, сорванные ветром за фронтом: встают за стеной брызг, редеют за полминуты и через три
     * минуты выпадают совсем (пыль там же ещё висит); у края пелены ветер за фронтом слабый — брызг не срывает.
     */
    @Test
    void sprayOverWaterGoneInMinutes() {
        assertEquals(0, PallField.sprayEnvelope(WaterModel.SPRAY_LAG), 1e-12, "до стены брызг");
        assertTrue(PallField.sprayEnvelope(WaterModel.SPRAY_LAG + 3) > 0.85);
        assertEquals(0, PallField.sprayEnvelope(200), 1e-12, "через три минуты над водой чисто");
        PallField p = new PallField(GROUND, NOON);
        double[] v = new double[4];
        int i = 20;
        p.dust(i, 20 * 30, v);
        assertTrue(v[3] > 0.2 * PallField.SIGMA_SPRAY && v[3] <= PallField.SIGMA_SPRAY, "брызги через полминуты " + v[3]);
        p.dust(i, 20 * 240, v);
        assertEquals(0, v[3], 1e-12, "брызги выпали");
        assertTrue(v[0] > 0, "а пыль ещё висит");
        // фронт у края пелены — к 33 с: через минуту он прошёл, а ветра за ним на брызги мало
        p.dust(PallField.BINS - 1, 20 * 60, v);
        assertEquals(0, v[3], 1e-12, "у края пелены брызг нет");
        // брызги реже самой густой пыли и белые (их цвет и рассеяние — в шейдере)
        assertTrue(PallField.SIGMA_SPRAY < PallField.SIGMA_DENSE);
    }

    /**
     * Чего под пеленой нет ({@link PallGround#hasLand}, {@link PallGround#hasWater}), того нет и в таблице: подрыв посреди
     * моря — без пыли и мглы, над сушей — без брызг; высота слоя остаётся (брызги — в слое пыли).
     */
    @Test
    void tableHoldsOnlyWhatIsUnderThePall() {
        PallField p = new PallField(GROUND, NOON);
        float[] row = new float[8 * PallField.BINS];
        p.row(20 * 120, true, false, row);
        boolean dust = false, haze = false;
        for (int i = 0; i < PallField.BINS; i++) {
            assertEquals(0, row[4 * i + 3], 0, "брызги над сушей, узел " + i);
            dust |= row[4 * i] > 0;
            haze |= row[4 * (PallField.BINS + i)] > 0;
        }
        assertTrue(dust && haze);
        p.row(20 * 30, false, true, row);
        boolean spray = false;
        for (int i = 0; i < PallField.BINS; i++) {
            assertEquals(0, row[4 * i], 0, "пыль над морем, узел " + i);
            assertEquals(0, row[4 * (PallField.BINS + i)], 0, "мгла над морем, узел " + i);
            assertTrue(row[4 * i + 1] > 0, "высота слоя, узел " + i);
            spray |= row[4 * i + 3] > 0;
        }
        assertTrue(spray);
    }

    /**
     * Вещество — по месту, откуда его подняло: пыль, поднятая над сушей и снесённая ветром на воду, остаётся пылью; брызги,
     * сорванные с воды и снесённые на берег, — брызгами. Доля воды берётся у точки за вычетом сноса — там же, где таблица.
     */
    @Test
    void materialComesFromWhereItWasLifted() {
        PallField p = new PallField(NO_FIRES, NOON);
        double ticks = 20 * 60;
        double[] w = new double[2];
        p.drift(ticks, w);
        assertTrue(w[0] > 150, "снос по +x " + w[0]);
        // море к +x от x = 400: поднято в (300, 0) над сушей, снесено на воду
        PallField.Water east = (dx, dz) -> dx > 400 ? 1 : 0;
        double qx = w[0] + 300, qz = w[1];
        assertTrue(qx > 400, "точка над водой");
        double dust = p.sigma(qx, qz, 0, ticks, PallField.Water.DRY);
        assertTrue(dust > 0);
        assertEquals(dust, p.sigma(qx, qz, 0, ticks, east), 1e-12, "пыль, снесённая на воду, — пыль");
        assertEquals(0, p.mist(qx, qz, 0, ticks, east), 1e-12);
        // поднято в (600, 0) над водой — брызги, пыли нет
        double mx = w[0] + 600;
        assertTrue(p.mist(mx, w[1], 0, ticks, east) > 0);
        assertEquals(p.mist(mx, w[1], 0, ticks, east), p.sigma(mx, w[1], 0, ticks, east), 1e-12, "над водой пыли нет");
        // море к −x от x = 100: поднято в (50, 0) над водой, снесено на берег
        PallField.Water west = (dx, dz) -> dx < 100 ? 1 : 0;
        double bx = w[0] + 50;
        assertTrue(bx > 100, "точка над сушей");
        double mist = p.mist(bx, w[1], 0, ticks, west);
        assertTrue(mist > 0, "брызги, снесённые на берег, — брызги");
        assertEquals(mist, p.sigma(bx, w[1], 0, ticks, west), 1e-12);
    }

    /**
     * Базисная волна — только у «водяного» подрыва: встаёт через секунды, растёт и тает минутами по законам её клубов
     * ({@link WaterModel}, часы модели), ослабление — по видимости в ней; катится и над берегом (это вода, поднятая у
     * эпицентра), рамка пелены её накрывает.
     */
    /** В мире меньшего масштаба волна — по часам и меркам её клубов: секунды модели — тики / (20·scale), блоки — × scale. */
    @Test
    void surgeFollowsTheWorldScale() {
        Detonation quarter = new Detonation(5, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 0.25f, false, 0.42f);
        PallField p = new PallField(quarter, NOON);
        p.water(1, WaterModel.wetBurst(0, 15));
        double[] s = new double[3];
        assertTrue(p.surge(20 * 30 * 0.25, s));
        assertEquals(0.25 * WaterModel.surgeRadius(30, 15), s[0], 1e-6, "радиус на 30-й секунде модели");
        assertEquals(PallField.SIGMA_SURGE / 0.25 * WaterModel.surgeDensity(30, 15), s[2], 1e-9);
    }

    @Test
    void surgeOnlyForAWetBurst() {
        PallField p = new PallField(NO_FIRES, NOON);
        double[] s = new double[3];
        assertFalse(p.surge(20 * 60, s), "на суше волны нет");
        double wet = WaterModel.wetBurst(0, 15);
        assertEquals(1, wet, 1e-9);
        p.water(1, wet);
        assertFalse(p.surge(20 * 5, s), "волна встаёт через ~7 с");
        assertTrue(p.surge(20 * 30, s));
        double r30 = s[0], sigma30 = s[2];
        assertEquals(WaterModel.surgeRadius(30, 15), r30, 1e-6, "радиус — как у клубов");
        assertEquals(PallField.SIGMA_SURGE * WaterModel.surgeDensity(30, 15), sigma30, 1e-9);
        assertTrue(p.surge(20 * 300, s));
        assertTrue(s[0] > r30 + 500 && s[2] < 0.5 * sigma30, "через 5 минут шире и реже: " + s[0] + " " + s[2]);
        assertFalse(p.surge(20 * 60 * 30, s), "через полчаса растаяла");
        assertTrue(p.surge(20 * 120, s));
        double r = s[0];
        assertTrue(p.extent(20 * 120) >= 1.05 * r, "рамка накрывает волну");
        assertTrue(p.visible(20 * 120));
        // над сушей у края волны (вода под пеленой не важна) — туман; за краем — нет
        double[] w = new double[2];
        p.drift(20 * 120, w);
        assertTrue(p.mist(w[0] + 0.9 * r, w[1], 0, 20 * 120, PallField.Water.DRY) > 0.5 * s[2]);
        assertEquals(0, p.mist(w[0] + 1.1 * r, w[1], 0, 20 * 120, PallField.Water.DRY), 1e-12);
        // наполовину «водяной» — вдвое реже
        p.water(1, 0.5);
        assertTrue(p.surge(20 * 120, s));
        assertEquals(0.5 * PallField.SIGMA_SURGE * WaterModel.surgeDensity(120, 15), s[2], 1e-9);
    }

    /** Над водой нет ни пепла, ни бумаги, ни мглы (пожаров на воде нет): пелена над морем — только брызги. */
    @Test
    void noAshPaperOrHazeOverWater() {
        PallField p = new PallField(GROUND, NOON);
        PallField.Water sea = (dx, dz) -> 1;
        assertEquals(0, p.ashRate(800, 0, 20 * 90, sea), 1e-12);
        assertEquals(0, p.paperRate(600, 0, 20 * 30, sea), 1e-12);
        assertEquals(0, p.ashRate(1000, 0, 20 * 60 * 8, sea), 1e-12);
        double late = 20 * 60 * 8;
        assertTrue(p.sigma(1000, 0, 0, late, PallField.Water.DRY) > 0, "над сушей мгла и пыль");
        assertEquals(0, p.sigma(1000, 0, 0, late, sea), 1e-12, "над морем брызги давно выпали");
    }
}
