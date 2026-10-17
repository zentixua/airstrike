package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MushroomModelTest {
    /** Привыкание глаза ночью ({@code Sight.adapted} при свете неба 0,17): во столько раз ярче кажется свет. */
    private static final double NIGHT = 1 / Math.pow(0.17, 4);

    /** Шар сворачивается в кольцо за 2,5·√(R/g): у 15 кт — около 12 с; доля кольца растёт от 0 до 1. */
    @Test
    void ballRollsUpIntoARing() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertTrue(tr > 9 && tr < 14, "15 кт: " + tr + " с");
        assertTrue(MushroomModel.rollUpSeconds(1, false) < tr);
        assertEquals(0, MushroomModel.rollUp(0, 15, false), 0);
        assertEquals(1, MushroomModel.rollUp(tr, 15, false), 1e-12);
        double last = 0;
        for (double t = 0; t <= 2 * tr; t += 0.05) {
            double r = MushroomModel.rollUp(t, 15, false);
            assertTrue(r >= last, t + " с");
            last = r;
        }
    }

    /**
     * Снаружи шар — сам шар (вся доля перепада ядра у края, гладкая сфера), свернувшись, край смешан с воздухом
     * ({@link MushroomModel#EDGE_HEAT}, клубы в полный размах); видимая температура не выше ядра, без скачка, с конца
     * второго максимума только падает.
     */
    @Test
    void edgeCoolsAsTheBallRollsUp() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertEquals(1, MushroomModel.edgeShare(0.2 * tr, 15, false), 0);
        assertEquals(MushroomModel.EDGE_HEAT, MushroomModel.edgeShare(tr, 15, false), 1e-12);
        assertEquals(MushroomModel.BALL_BILLOWS, MushroomModel.billows(0.2 * tr, 15, false), 0);
        assertEquals(1, MushroomModel.billows(tr, 15, false), 1e-12);
        assertEquals(FireballModel.temperature(1, 15), MushroomModel.edgeTemperature(1, 15, false), 1e-9);
        double last = Double.MAX_VALUE, tm = FireballModel.secondMaximumSeconds(15);
        for (double t = tm; t < 600; t *= 1.01) {
            double edge = MushroomModel.edgeTemperature(t, 15, false);
            assertTrue(edge <= MushroomModel.coreTemperature(t, 15, false) + 1e-9, t + " с");
            assertTrue(edge <= last, t + " с: " + edge);
            assertTrue(last == Double.MAX_VALUE || last - edge < 0.02 * last, t + " с: скачок " + last + " → " + edge);
            last = edge;
        }
    }

    /**
     * Свет шара и шапки вокруг ({@link MushroomModel#light}): у поверхности — яркость чёрного тела её видимой температуры,
     * дальше — как (R/d)²; днём через секунду шар 15 кт в 5 км светит ярче солнца, а через 20 с свернувшаяся шапка — уже
     * нет и доли процента дня.
     */
    @Test
    void ballLightsItsSurroundings() {
        MushroomModel.Shape s = MushroomModel.shape(1, 15, 0, true, 300);
        double surface = BlackBody.luminance(MushroomModel.edgeTemperature(1, 15, true));
        assertEquals(surface, MushroomModel.light(s, 15, true, 0), 1e-9 * surface);
        assertEquals(surface / 4, MushroomModel.light(s, 15, true, 2 * s.capR()), 1e-9 * surface);
        assertTrue(MushroomModel.light(s, 15, true, 5_000) > 1, "1 с в 5 км: " + MushroomModel.light(s, 15, true, 5_000));
        MushroomModel.Shape late = MushroomModel.shape(20, 15, 0, true, 3_000);
        assertTrue(MushroomModel.light(late, 15, true, 2_000) < 1e-3, "20 с в 2 км: " + MushroomModel.light(late, 15, true, 2_000));
    }

    /** Ядро шапки — тот же шар до сворачивания и остывает смешением после: температура без скачка и только падает. */
    @Test
    void coreCoolsWithoutAJump() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertEquals(MushroomModel.coreTemperature(tr - 1e-6, 15, false), MushroomModel.coreTemperature(tr + 1e-6, 15, false), 0.01);
        double last = Double.MAX_VALUE;
        for (double t = 0.2; t < 900; t += 0.2) {
            double k = MushroomModel.coreTemperature(t, 15, false);
            assertTrue(k <= last && k > MushroomModel.AMBIENT_K, t + " с: " + k);
            last = k;
        }
    }

    /**
     * Ночью ядро 15 кт светится минуты: через минуту — ярко (ярче белой стены в полдень с привыканием глаза), через две с
     * половиной — ещё заметно, через десять — нет. У 1 кт оно гаснет раньше.
     */
    @Test
    void coreGlowsForMinutesAtNight() {
        assertTrue(night(60, 15) > 1, "60 с: " + night(60, 15));
        assertTrue(night(150, 15) > 0.05, "150 с: " + night(150, 15));
        assertTrue(night(600, 15) < 0.01, "600 с: " + night(600, 15));
        assertTrue(night(60, 1) < 0.05 * night(60, 15), "1 кт через минуту: " + night(60, 1));
    }

    private static double night(double t, double yieldKt) {
        return BlackBody.luminance(MushroomModel.coreTemperature(t, yieldKt, false)) * NIGHT;
    }

    /**
     * Гриб: до подрыва и после рассеяния его нет; шапка — сначала сам огненный шар (сфера его радиуса на его высоте,
     * непрозрачная), кольцо растёт из него; ножка входит в выемку под куполом, но не выше середины кольца (у наземного — с
     * самого начала: она след шара, у воздушного столб пыли догоняет шапку позже); юбка не дальше фронта по земле.
     */
    @Test
    void shapeFollowsTheCloud() {
        assertNull(MushroomModel.shape(0, 15, 0, true, 0));
        assertNull(MushroomModel.shape(MushroomModel.lifeSeconds(15) + 1, 15, 0, true, 10_000));
        MushroomModel.Shape early = MushroomModel.shape(0.5, 15, 0, true, 300);
        assertNotNull(early);
        assertEquals(0, early.ringR(), 0);
        assertEquals(FireballModel.radius(0.5, 15, true), early.capR(), 1e-9);
        assertEquals(FireballModel.radius(0.5, 15, true), early.ringB(), 1e-9);
        assertEquals(FireballModel.centreHeight(0.5, 0, 15), early.capY(), 1e-9);
        assertEquals(MushroomModel.TAU_CAP, early.capTau(), 1e-9);
        assertTrue(early.domeY() + early.domeH() < early.capY() + early.ringB(), "купол внутри шара");
        for (double t = 1; t < MushroomModel.lifeSeconds(15); t += 3) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 2_000);
            assertNotNull(s, t + " с");
            assertTrue(s.stemTop() <= s.capY() + 1e-9, t + " с: ножка выше середины кольца");
            assertTrue(s.stemTop() >= s.capBottom() - 1e-9, t + " с: у наземного ножка не дошла до шапки");
            assertTrue(s.skirtR() + s.skirtW() <= 2_000 + 1e-9, t + " с: юбка дальше фронта");
            assertTrue(s.ringR() + s.ringA() <= s.capR() + 1e-9, t + " с: кольцо шире шапки");
            assertTrue(s.top() >= s.capY(), t + " с");
        }
        // у воздушного столб пыли поднимается с земли и догоняет шапку позже
        double high = 4 * FireballModel.maxRadius(15, false);
        MushroomModel.Shape air = MushroomModel.shape(5, 15, high, false, 2_000);
        assertTrue(air.stemTop() < air.capBottom(), "воздушный: ножка у шапки уже через 5 с");
        MushroomModel.Shape late = MushroomModel.shape(60, 15, 0, true, 5_000);
        assertEquals(MushroomModel.RING * late.capR(), late.ringR(), 1e-9);
        assertTrue(late.capTau() > 10, "шапка через минуту густая: " + late.capTau());
    }

    /**
     * Шар становится шапкой одной формой: после второго максимума (шар уже не растёт рывком) числа формы за сотую долю
     * секунды меняются на доли процента — без скачка на сворачивании.
     */
    @Test
    void ballTurnsIntoTheCapWithoutAJump() {
        for (boolean surface : new boolean[]{true, false}) {
            double hob = surface ? 0 : 2 * FireballModel.maxRadius(15, false), dt = 0.01, from = 2 * FireballModel.secondMaximumSeconds(15);
            MushroomModel.Shape last = MushroomModel.shape(from, 15, hob, surface, 300);
            for (double t = from + dt; t < 60; t += dt) {
                MushroomModel.Shape s = MushroomModel.shape(t, 15, hob, surface, 300);
                double[] a = {last.capY(), last.capR(), last.ringR(), last.ringB(), last.domeY(), last.domeR(), last.domeH(), last.top()};
                double[] b = {s.capY(), s.capR(), s.ringR(), s.ringB(), s.domeY(), s.domeR(), s.domeH(), s.top()};
                for (int i = 0; i < a.length; i++) {
                    assertTrue(Math.abs(b[i] - a[i]) < 0.02 * s.capR() + 2, (surface ? "наземный " : "воздушный ") + t + " с, число " + i + ": " + a[i] + " → " + b[i]);
                }
                last = s;
            }
        }
    }

    /** Ножка шире всего у земли (раструб пыли) или у шапки: рамка гриба берёт её края. */
    @Test
    void stemIsWidestAtItsEnds() {
        for (double dust : new double[]{0, 0.3, 1}) {
            double ends = Math.max(MushroomModel.stemRadius(0, 30, dust), MushroomModel.stemRadius(1, 30, dust));
            for (double w = 0; w <= 1; w += 0.01) assertTrue(MushroomModel.stemRadius(w, 30, dust) <= ends + 1e-9, "w = " + w);
        }
    }

    /**
     * Часы кипения шапки — интеграл скорости обращения от подрыва: с нуля, без скачков, только вперёд, а к стабилизации
     * встают. Оборот шапки 15 кт на 20-й секунде — 15–35 с (вещество обходит сечение кольца со скоростью подъёма), потом
     * медленнее; за всю жизнь — несколько оборотов, у 1 и 15 кт — того же порядка.
     */
    @Test
    void capChurnsWithTheRise() {
        for (double kt : new double[]{1, 2, 15}) {
            MushroomModel.Churn c = MushroomModel.churn(kt, 0, true);
            assertEquals(0, c.at(0), 0);
            double last = 0, step = 0.05, fastest = 0;
            for (double t = 1e-3; t < c.end(); t += 0.01) fastest = Math.max(fastest, rate(kt, t));
            for (double t = step; t < c.end(); t += step) {
                double v = c.at(t);
                assertTrue(v >= last, kt + " кт, " + t + " с");
                // за шаг — не больше, чем при самом быстром обращении
                assertTrue(v - last <= 1.01 * fastest * step, kt + " кт, " + t + " с: " + (v - last));
                last = v;
            }
            assertEquals(c.at(c.end()), c.at(3 * c.end()), 0, kt + " кт: часы после конца таблицы стоят");
            assertTrue(c.at(c.end()) > 10 && c.at(c.end()) < 40, kt + " кт: " + c.at(c.end()) + " рад");
            // таблица — тот же интеграл, что прямое сложение мелкими шагами
            double sum = 0, rate0 = rate(kt, 1e-3);
            for (double t = 0; t < 120; t += 0.01) {
                double r = rate(kt, t + 0.005);
                sum += (t == 0 ? 0.5 * (rate0 + r) : r) * 0.01;
            }
            assertEquals(sum, c.at(120), 0.01 * sum, kt + " кт: часы через 2 минуты");
        }
        double turn20 = 2 * Math.PI / rate(15, 20), turn120 = 2 * Math.PI / rate(15, 120);
        assertTrue(turn20 > 15 && turn20 < 35, "оборот на 20-й секунде: " + turn20 + " с");
        assertTrue(turn120 > 3 * turn20, "оборот ко второй минуте: " + turn120 + " с");
    }

    private static double rate(double kt, double t) {
        MushroomModel.Shape s = MushroomModel.shape(t, kt, 0, true, 0);
        return s == null ? 0 : MushroomModel.churnRate(s, CloudModel.riseSpeed(t, 0, kt));
    }

    /** Скорость подъёма верха облака — производная его высоты. */
    @Test
    void riseSpeedIsTheSlopeOfTheTop() {
        for (double t = 0.5; t < 600; t += 7.3) {
            double slope = (CloudModel.top(t + 1e-3, 0, 15) - CloudModel.top(t - 1e-3, 0, 15)) / 2e-3;
            assertEquals(slope, CloudModel.riseSpeed(t, 0, 15), 1e-4 * Math.max(slope, 1), t + " с");
        }
    }
}
