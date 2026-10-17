package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterModelTest {
    /** Рябь, брызги и пена — по шкале Бофорта: нет до порога, полностью с верхнего, между — растут. */
    @Test
    void windThresholdsFollowBeaufort() {
        assertEquals(0, WaterModel.ripple(1.0));
        assertEquals(1, WaterModel.ripple(20), 1e-9);
        assertEquals(0, WaterModel.spray(10), "7 баллов (до 14 м/с) гребни не срывает");
        assertEquals(1, WaterModel.spray(50), 1e-9);
        assertEquals(0, WaterModel.foam(20), "пена полосами — с 10 баллов");
        assertEquals(1, WaterModel.foam(70), 1e-9);
        double prev = -1;
        for (double w = 0; w <= 80; w += 0.5) {
            double r = WaterModel.ripple(w), s = WaterModel.spray(w), f = WaterModel.foam(w);
            assertTrue(r >= prev, w + " м/с");
            assertTrue(r >= s && s >= f, w + " м/с: рябь раньше брызг, брызги раньше пены");
            prev = r;
        }
    }

    /** Стена брызг: нет, пока гребни не срывает, дальше выше с ветром, не выше 45 м. */
    @Test
    void sprayWallGrowsWithWind() {
        assertEquals(0, WaterModel.sprayHeight(12));
        double prev = 0;
        for (double w = 15; w <= 80; w += 1) {
            double h = WaterModel.sprayHeight(w);
            assertTrue(h >= prev && h <= WaterModel.SPRAY_HIGH, w + " м/с: " + h);
            prev = h;
        }
        assertEquals(WaterModel.SPRAY_HIGH, WaterModel.sprayHeight(60), 1e-9);
    }

    /**
     * Тёмное кольцо: впереди фронта вода гладкая, у самого фронта темнее всего, за ним светлеет — сперва быстро (полоса
     * ряби), потом медленно; к спокойной воде — через несколько минут.
     */
    @Test
    void darkRingIsSharpAtTheFrontAndFadesBehind() {
        double wind = 30, band = 25, calm = 600;
        assertEquals(0, WaterModel.darkness(wind, -1, band, calm));
        double front = WaterModel.darkness(wind, 0, band, calm);
        assertEquals(0.6, front, 1e-9);
        double prev = front;
        for (double since = 1; since <= 3000; since += 1) {
            double k = WaterModel.darkness(wind, since, band, calm);
            assertTrue(k < prev, since + " тиков");
            prev = k;
        }
        assertTrue(WaterModel.darkness(wind, 4 * band, band, calm) < 0.5 * front, "полоса у фронта — заметно темнее");
        assertTrue(WaterModel.darkness(wind, 4 * band, band, calm) > 0.25 * front, "за полосой вода ещё неспокойна");
        assertTrue(WaterModel.darkness(wind, 3000, band, calm) < 0.01, "через 2,5 минуты — спокойная");
        assertTrue(WaterModel.darkness(5, 0, band, calm) < front, "слабый ветер — слабее рябь");
    }

    /** Пена встаёт за фронтом за несколько тиков (не впереди), держится и сходит быстрее ряби. */
    @Test
    void whitecapsRiseBehindTheFront() {
        double wind = 80, calm = 600;
        assertEquals(0, WaterModel.whitecaps(wind, -2, calm));
        assertEquals(0, WaterModel.whitecaps(wind, 0, calm));
        assertTrue(WaterModel.whitecaps(wind, 8, calm) > 0.5);
        assertTrue(WaterModel.whitecaps(wind, 600, calm) < 0.05);
        assertEquals(0, WaterModel.whitecaps(20, 10, calm), "при 20 м/с пены ещё нет");
    }

    /** «Водяной» подрыв: на воде — весь, выше половины наибольшего шара — нет; с высотой — меньше. */
    @Test
    void wetBurstFadesWithHeight() {
        double rf = FireballModel.maxRadius(15, true);
        assertEquals(1, WaterModel.wetBurst(0, 15), 1e-9);
        assertEquals(1, WaterModel.wetBurst(0.1 * rf, 15), 1e-9);
        assertEquals(0, WaterModel.wetBurst(0.5 * rf, 15), 1e-9);
        assertEquals(0, WaterModel.wetBurst(600, 15), 1e-9);
        double prev = 1;
        for (double h = 0; h <= rf; h += 5) {
            double w = WaterModel.wetBurst(h, 15);
            assertTrue(w <= prev, h + " м");
            prev = w;
        }
    }

    /** Столб «Бейкера» (23 кт): радиус 300 м, стенки 90 м; встаёт за секунды, падая — оседает; у 1 кт — меньше. */
    @Test
    void columnMatchesBaker() {
        assertEquals(300, WaterModel.columnRadius(23), 1e-6);
        assertEquals(90, WaterModel.columnWall(23), 1e-6);
        double max = WaterModel.COLUMN_TOP * WaterModel.SURFACE_COLUMN;
        assertEquals(0, WaterModel.columnTop(0, 23));
        assertTrue(WaterModel.columnTop(2, 23) > 0.5 * max, "за 2 с — выше половины");
        assertTrue(WaterModel.columnTop(9, 23) > 0.95 * max, "к 9 с — почти весь");
        assertEquals(0, WaterModel.columnFall(9, 23));
        assertEquals(1, WaterModel.columnFall(40, 23), 1e-9);
        assertEquals(0.5 * max, WaterModel.columnTop(60, 23), 1e-3 * max, "упавший — вдвое ниже");
        assertTrue(WaterModel.columnRadius(1) < 0.4 * WaterModel.columnRadius(23));
    }

    /**
     * Базисная волна: у «Бейкера» встаёт, когда падает вода столба (~8 с), катится поначалу ~30 м/с, к 4 минутам — около
     * 3 км по радиусу и ~550 м в высоту; у подрыва на поверхности — та же, но меньше ({@link WaterModel#SURFACE_SURGE});
     * редеет минутами.
     */
    @Test
    void baseSurgeMatchesBaker() {
        double start = WaterModel.surgeStart(23), k = WaterModel.SURFACE_SURGE, wall = 1.2 * WaterModel.columnRadius(23);
        assertEquals(8, start, 1e-9);
        assertTrue(k > 0.5 && k < 1, "у подрыва на поверхности волна меньше подводной, но того же порядка");
        assertEquals(0, WaterModel.surgeRadius(start - 0.1, 23));
        assertEquals(0, WaterModel.surgeDensity(start, 23));
        double v = (WaterModel.surgeRadius(start + 2, 23) - WaterModel.surgeRadius(start + 1, 23)) / k;
        assertTrue(v > 25 && v < 32, "скорость поначалу у «Бейкера» " + v + " м/с");
        double r4 = (WaterModel.surgeRadius(240, 23) - wall) / k + wall;
        assertTrue(r4 > 2500 && r4 < 3200, "к 4 минутам у «Бейкера» " + r4 + " м");
        assertEquals(550 * k, WaterModel.surgeHeight(240, 23), 1e-6);
        double prev = 0;
        for (double t = start; t < 600; t += 1) {
            double r = WaterModel.surgeRadius(t, 23);
            assertTrue(r >= prev, t + " с");
            prev = r;
        }
        assertTrue(WaterModel.surgeDensity(start + 5, 23) > 0.9);
        assertTrue(WaterModel.surgeDensity(start + 240, 23) < 0.4, "через 4 минуты редеет");
    }

    /** Кольцо тумана (облако конденсации) — на 1–4 с у «Бейкера», дольше у мощного подрыва. */
    @Test
    void mistRingLastsSeconds() {
        assertEquals(0, WaterModel.mistRing(0.1, 23));
        assertEquals(1, WaterModel.mistRing(1.5, 23), 1e-9);
        assertEquals(1, WaterModel.mistRing(2.5, 23), 1e-9);
        assertEquals(0, WaterModel.mistRing(5, 23));
        assertTrue(WaterModel.mistRing(4, 1000) > 0.9, "у 1 Мт ещё стоит");
    }
}
