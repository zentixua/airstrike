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
