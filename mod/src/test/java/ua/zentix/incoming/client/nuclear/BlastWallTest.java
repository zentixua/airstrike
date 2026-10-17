package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastWallTest {
    /** 15 кт у земли на высоте 64, без ветра, в масштабе 1 блок = 4 м. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 0.25f, false, Detonation.OLD_WAVE);

    /**
     * Сколько пыли поднимает ветер за фронтом (Гласстон и Долан, табл. 3.07: ветер за фронтом 1 psi — 17 м/с, 5 psi —
     * 72 м/с): штормовой ветер и сильнее — самая густая пыль, у порога срыва (~5 м/с, Бофорт 4 «поднимает пыль») — ничего,
     * между ними — как u² − u₀².
     */
    @Test
    void windLiftsDustAboveThreshold() {
        assertEquals(0, BlastWall.lift(4.9));
        assertEquals(0, BlastWall.lift(BlastWall.LIFT_FROM));
        assertEquals(1, BlastWall.lift(BlastWall.LIFT_FULL));
        assertEquals(1, BlastWall.lift(72));
        assertEquals((17.0 * 17 - 25) / (400 - 25), BlastWall.lift(17), 1e-12);
        double prev = 0;
        for (double u = 5; u <= 25; u += 0.25) {
            assertTrue(BlastWall.lift(u) >= prev, u + " м/с");
            prev = BlastWall.lift(u);
        }
        // ветер модели на 1 и 5 psi — как у Гласстона и Долана
        assertEquals(17, BlastModel.windSpeed(BlastModel.kpa(1)), 2);
        assertEquals(72, BlastModel.windSpeed(BlastModel.kpa(5)), 6);
    }

    /**
     * Голова стены — за фронтом, на его путь за {@link BlastWall#LIFT_S} (пыли нужно время подняться), сам фронт не
     * виден; голова непрозрачна, пока давление на ней не меньше ~1,3 psi, и сходит на нет к 0,3 psi, где ветер больше
     * не срывает пыль.
     */
    @Test
    void headTrailsTheFront() {
        BlastWall w = new BlastWall(GROUND, false);
        assertEquals(BlastWall.Wall.NONE, w.wall(0.1));
        for (double t = 0.3; t <= 60 && w.settle(t) <= 0; t += 0.1) {
            assertEquals(w.shock(t - BlastWall.LIFT_S), w.head(t), 1e-9, t + " с");
            assertTrue(w.head(t) < w.shock(t), t + " с: голова на фронте или впереди");
        }
        double rangeHigh = BlastModel.rangeForOverpressure(BlastModel.kpa(1.5), 15), rangeLow = BlastModel.rangeForOverpressure(BlastModel.kpa(0.32), 15);
        double t = 0.5;
        while (w.head(t) < rangeHigh) t += 0.01;
        assertEquals(1, w.density(t), 1e-9, "у 1,5 psi голова не самая густая");
        while (w.head(t) < rangeLow) t += 0.01;
        assertTrue(w.density(t) < 0.03, "у 0,3 psi голова ещё густая: " + w.density(t));
    }

    /** Высота стены — 300 м у центра, ~155 на 1 км, ~60 на 3 км у 15 кт; у любой мощности та же форма по приведённой дальности. */
    @Test
    void wallHeightFollowsScaledRange() {
        assertEquals(300, BlastWall.wallHeight(0, 1), 1e-9);
        assertEquals(155, BlastWall.wallHeight(1000, 1), 5);
        assertEquals(60, BlastWall.wallHeight(3000, 1), 5);
        double ys = Math.cbrt(1000 / 15.0);
        assertEquals(BlastWall.wallHeight(1000, 1) * ys, BlastWall.wallHeight(1000 * ys, ys), 1e-9);
    }

    /** Вал головы крутится в одну сторону и не быстрее {@link BlastWall#ROLL_MAX}. */
    @Test
    void headRollsSteadily() {
        BlastWall w = new BlastWall(GROUND, false);
        assertEquals(0, w.rollPhase(0));
        double prev = 0;
        for (double t = 0.05; t <= 600; t += 0.05) {
            double phase = w.rollPhase(t);
            assertTrue(phase >= prev - 1e-9, t + " с: вал крутится назад");
            assertTrue(phase - prev <= BlastWall.ROLL_MAX * 0.05 + 1e-6, t + " с: быстрее предела " + (phase - prev) / 0.05);
            prev = phase;
        }
        assertTrue(w.rollPhase(10) > Math.PI, "за 10 с вал не обернулся и наполовину: " + w.rollPhase(10));
    }

    /**
     * Клубы вдоль кольца — две фазы: у каждой окружность в плитках целая (плитка шума без шва на кольце) и постоянна, пока
     * фаза видна; номер и окружность фазы меняются только там, где её вес — ноль, а веса двух фаз в сумме — 1.
     */
    @Test
    void phasesChangeOnlyWhenHidden() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] prev = new float[BlastWall.SLOT], now = new float[BlastWall.SLOT];
        assertTrue(w.slot(0.4, prev));
        int changes = 0;
        for (double t = 0.41; t <= 60; t += 0.01) {
            assertTrue(w.slot(t, now));
            // за шаг вес фазы меняется не больше чем на 2·Δψ (ψ — логарифм головы по основанию роста фазы)
            double step = 2 * Math.abs(Math.log(now[0] / prev[0])) / Math.log(BlastWall.PHASE_GROWTH) + 1e-4;
            for (int j = 0; j < 2; j++) {
                float weight = j == 0 ? now[10] : 1 - now[10];
                assertTrue(weight >= 0 && weight <= 1);
                assertEquals(Math.rint(now[8 + j]), now[8 + j], 0, "окружность не целая");
                assertTrue(now[8 + j] >= 1);
                if (now[12 + j] != prev[12 + j]) {
                    changes++;
                    assertTrue(weight <= step, t + " с: фаза " + j + " сменилась при весе " + weight);
                } else {
                    assertEquals(prev[8 + j], now[8 + j], 0, t + " с: окружность фазы " + j + " сменилась без смены фазы");
                }
            }
            System.arraycopy(now, 0, prev, 0, now.length);
        }
        assertTrue(changes > 4, "фазы почти не менялись: " + changes);
    }

    /** Числа слота — те, что считает шейдер: голова, вал, ослабление, плитка; облако Вильсона — только во влажном воздухе. */
    @Test
    void slotCarriesTheHead() {
        BlastWall dry = new BlastWall(GROUND, false), humid = new BlastWall(GROUND, true);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(dry.slot(3, s));
        BlastWall.Wall wall = dry.wall(3);
        assertEquals(wall.head(), s[0], 1e-3);
        assertEquals(wall.height(), s[2], 1e-3);
        assertEquals(PallField.SIGMA_DENSE / GROUND.scale() * wall.density(), s[3], 1e-6);
        assertTrue(s[1] > 0 && s[1] <= 0.42 * s[2] + 1e-3, "радиус вала " + s[1]);
        assertEquals(BlastWall.TILE * s[2], s[11], 1e-3);
        assertEquals(0, s[18], "облако Вильсона в сухом воздухе");
        assertEquals(0, dry.wilsonReach(1));
        assertTrue(humid.slot(1, s));
        double front = GROUND.frontRadius(1 * 20 * GROUND.scale());
        assertEquals(BlastWall.WILSON_AT * front, s[16], 1e-3 * front);
        assertTrue(s[18] > 0);
        assertTrue(humid.wilsonReach(1) >= s[16] + s[17]);
        // облако Вильсона тает за секунды
        humid.slot(5, s);
        assertEquals(0, s[18]);
    }

    /**
     * Средняя голова ({@link BlastWall#sigma}, копия {@code pallWallAt} и {@code pallWallMean}): спереди у земли — стоячая
     * стена во всю высоту, впереди переднего края пыли и выше головы — ничего, за валом — хвост ниже, к пелене тает.
     */
    @Test
    void meanHeadHasAStandingFront() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(w.slot(4, s));
        double head = s[0], a = s[1], top = s[2], k = s[3];
        assertEquals(0, BlastWall.sigma(s, head + 2, 0, 0.3 * top, 0));
        assertEquals(0, BlastWall.sigma(s, head - 1.5 * a, 0, 1.01 * top, 0));
        assertEquals(k, BlastWall.sigma(s, head - 1.5 * a, 0, 0.5 * top, 0), 1e-9 * k);
        // передний край в нижней половине — стоит: у земли и у середины высоты он одинаково густой
        assertTrue(BlastWall.sigma(s, head - 0.5 * a, 0, 0.05 * top, 0) > 0.5 * k);
        assertEquals(BlastWall.sigma(s, head - 0.5 * a, 0, 0.05 * top, 0), BlastWall.sigma(s, head - 0.5 * a, 0, 0.45 * top, 0), 1e-9 * k);
        // хвост за валом ниже головы и тает к пелене
        assertEquals(0, BlastWall.sigma(s, head - 3 * a, 0, 0.9 * top, 0));
        assertTrue(BlastWall.sigma(s, head - 3 * a, 0, 0.2 * top, 0) > 0);
        assertEquals(0, BlastWall.sigma(s, head - 6 * a, 0, 0.2 * top, 0));
        for (double up = -0.6; up <= 1.2; up += 0.05) {
            for (double back = -0.5; back <= 6; back += 0.05) {
                double v = BlastWall.sigma(s, head - back * a, 0, up * top, 0);
                assertTrue(v >= 0 && v <= k * (1 + 1e-9), up + ", " + back + ": " + v);
                assertEquals(v, BlastWall.sigma(s, 0, head - back * a, up * top, 0), 1e-9 * k, "голова не круглая");
            }
        }
    }

    private static double luminance(float[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /**
     * Свет кадра, как его считает {@code NukePall.light}: в полдень — небо Minecraft (0,47, 0,65, 1 на экране) и солнце
     * над головой, ночью — свет неба на клубах ночью ({@link NukePall#nightSky}, на экране 0,17), солнца нет.
     */
    private static NukePall.Sky sky(boolean day) {
        NukePall.Sky s = new NukePall.Sky();
        float ambient = day ? 1 : 0.17f;
        s.sy = day ? 1 : -1;
        s.day = day ? 1 : 0;
        float floor = (float) PallLight.linear(NukePall.nightSky(ambient, 1 - s.day));
        double[] colour = day ? new double[]{0.47, 0.65, 1} : new double[3];
        for (int c = 0; c < 3; c++) {
            s.sky[c] = NukePall.skyLight(colour[c], floor);
            s.sun[c] = NukePall.sunLight(1, s.sky[c], s.day);
        }
        s.base = ambient;
        s.glowOnSmoke = (float) CloudPuffs.glowOnSmoke(ambient);
        return s;
    }

    /**
     * Пыль у камеры — цвета стены там, как её светит кадр ({@link NukePall#dustLight}). Ночью у пожаров передняя сторона
     * головы не тёмная (её снизу светят пожары; по ночной карте освещения пыль вышла бы почти чёрной) и цвета огня; в глубине
     * головы, внизу, — в тени её толщи, во много раз темнее верха в отсвете шара. Днём сторона от шара — в свете солнца
     * и неба, почти без оранжевого (шар за стеной её не светит), верх головы — бурый (красный, за ним зелёный, синий).
     */
    @Test
    void nearDustTakesWallLight() {
        CloudPuffs puffs = new CloudPuffs(GROUND, 750);
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT], glow = new float[8], c = new float[3], top = new float[3];
        double t = 0.5;
        while (GROUND.metres(w.wall(t).head()) < 1500) t += 0.05;
        assertTrue(w.slot(t, s));
        assertTrue(puffs.pallLight(t, glow));
        double head = s[0], a = s[1], h = s[2];
        assertTrue(head < glow[3], "голова в 1,5 км — не над пожарами");
        for (double up = 0.05; up <= 0.6; up += 0.1) {
            NukePall.dustLight(s, glow, sky(false), head - 0.1 * a, up * h, 0, 0, 0, up * h, c);
            assertTrue(luminance(c) > 0.2, up + " высоты: ночью у пожаров пыль тёмная, " + luminance(c));
            assertTrue(c[0] > c[1] && c[1] > c[2], up + " высоты: ночью у пожаров не цвета огня, " + Arrays.toString(c));
        }
        NukePall.dustLight(s, glow, sky(false), head - 1.5 * a, 0.9 * h, 0, 0, 0, 0.9 * h, top);
        NukePall.dustLight(s, glow, sky(false), head - 1.5 * a, 0.3 * h, 0, 0, 0, 0.3 * h, c);
        assertTrue(luminance(top) > 3 * luminance(c), "в глубине головы не тень: " + luminance(c) + " при верхе " + luminance(top));
        for (double up = 0.05; up <= 0.6; up += 0.1) {
            NukePall.dustLight(s, glow, sky(true), head - 0.1 * a, up * h, 0, 0, 0, up * h, c);
            assertTrue(c[0] - c[2] < 0.1, up + " высоты: днём сторона от шара в его отсвете, " + Arrays.toString(c));
        }
        NukePall.dustLight(s, glow, sky(true), head - 1.5 * a, 0.9 * h, 0, 0, 0, 0.9 * h, top);
        assertTrue(top[0] > top[1] && top[1] > top[2], "днём верх головы не бурый: " + Arrays.toString(top));
        assertTrue(luminance(top) > 0.5 && luminance(top) < 0.85, "днём верх головы " + luminance(top));
    }
}
