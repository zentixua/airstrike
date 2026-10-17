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
        for (int t = from; t <= to; t++) p.daylight(t, day0 + t);
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
        for (int t = 0; t <= 9000; t++) stopped.daylight(t, 14000);
        assertTrue(haze(stopped, 8000) > 0, "часы стоят — мгла до утра по прошедшим тикам");
        assertEquals(0, haze(stopped, 10100), 1e-12);
    }

    /** Сон: часы прыгают к восходу — мгла гаснет за минуту, даже до 5 минут (подрыв в 5 утра ждал следующего утра). */
    @Test
    void sleepingThroughTheNightEndsTheHaze() {
        PallField evening = new PallField(GROUND, 14000);
        clock(evening, 14000, 0, 3000);
        assertTrue(haze(evening, 3000) > 0);
        evening.daylight(3001, 24000);
        assertTrue(haze(evening, 3100) > 0, "гаснет не скачком");
        assertEquals(0, haze(evening, 3001 + PallField.HAZE_FADE_TICKS + 1), 1e-12);

        PallField dawn = new PallField(GROUND, 23000);
        clock(dawn, 23000, 0, 300);
        dawn.daylight(301, 24000);
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
        set.daylight(401, 1000);
        assertEquals(0, haze(set, 401 + PallField.HAZE_FADE_TICKS + 1), 1e-12);

        PallField add = new PallField(GROUND, NOON);
        clock(add, NOON, 0, 100);
        add.daylight(101, NOON + 100 + 6000);
        clock(add, NOON + 6000, 102, 11000);
        assertTrue(haze(add, 10000) > 0);
        assertEquals(0, haze(add, 12100), 1e-12, "утро на 6000 тиков ближе");

        PallField lag = new PallField(GROUND, NOON);
        clock(lag, NOON, 0, 6100);
        lag.daylight(6101, NOON + 5500);
        clock(lag, NOON - 600, 6102, 7000);
        assertTrue(haze(lag, 17000) > 0, "поправка часов в сумерки — не утро");
    }

    /** Перемотка повтора: утро — снова по времени суток в миг подрыва. */
    @Test
    void rewindForgetsTheSkippedNight() {
        PallField p = new PallField(GROUND, 14000);
        clock(p, 14000, 0, 3000);
        p.daylight(3001, 24000);
        assertEquals(0, haze(p, 5000), 1e-12);
        p.rewound();
        assertTrue(haze(p, 5000) > 0);
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
