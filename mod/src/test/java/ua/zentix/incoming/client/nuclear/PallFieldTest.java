package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PallFieldTest {
    /** 15 кт у земли в полдень, ветер 8 м/с, масштаб 1, стена пыли вдали — 0,42 скорости звука. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    private static final Detonation SMALL = new Detonation(2, new Vec3(0, 64, 0), 64, 1, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
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
        p.row(ticks, row);
        double[] w = new double[2];
        p.drift(ticks, w);
        int i = 30;
        double r = p.radius(i);
        // по ветру от эпицентра, у земли: пыль узла i (мгла сюда тоже дотягивается)
        double ux = Math.cos(GROUND.windDir()), uz = Math.sin(GROUND.windDir());
        double s = p.sigma(w[0] + ux * r, w[1] + uz * r, 0, ticks);
        assertTrue(s >= row[4 * i] * 0.98, "пыль у земли " + s + " < таблицы " + row[4 * i]);
        assertTrue(s <= row[4 * i] + row[4 * (PallField.BINS + 2)] * 1.01 + 1e-9);
        // выше пелены — ничего
        assertEquals(0, p.sigma(w[0] + ux * r, w[1] + uz * r, row[4 * i + 1] * 2 + 400, ticks), 1e-12);
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
        assertEquals(0, p.ashRate(800, 0, 10));
        assertTrue(p.ashRate(800, 0, 20 * 90) > 0.1);
        assertTrue(p.paperRate(600, 0, 20 * 30) > 0.3);
        assertEquals(0, p.paperRate(4500, 0, 20 * 30), 1e-9);
        assertTrue(p.ashRate(1000, 0, 20 * 60 * 8) > 0, "мгла над пожарами сыплет пепел");
    }
}
