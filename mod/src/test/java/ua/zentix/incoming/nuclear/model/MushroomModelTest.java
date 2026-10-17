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

    /** Шар остывает по хронике: 15 кт — около 4 000 K через секунду и около 1 950 K через девять. */
    @Test
    void fireballCoolsLikeTheFootage() {
        double t1 = MushroomModel.fireballTemperature(1, 15), t9 = MushroomModel.fireballTemperature(9, 15);
        assertTrue(t1 > 3_600 && t1 < 4_400, "1 с: " + t1);
        assertTrue(t9 > 1_750 && t9 < 2_150, "9 с: " + t9);
        assertEquals(MushroomModel.FIREBALL_PEAK_K, MushroomModel.fireballTemperature(0.01, 15), 1e-9);
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
     * Гриб: до подрыва и после рассеяния его нет; кольцо растёт из шара; шапка проступает, когда шар гаснет; ножка
     * входит в выемку под куполом, но не выше середины кольца; юбка не дальше фронта по земле.
     */
    @Test
    void shapeFollowsTheCloud() {
        assertNull(MushroomModel.shape(0, 15, 0, true, 0));
        assertNull(MushroomModel.shape(MushroomModel.lifeSeconds(15) + 1, 15, 0, true, 10_000));
        MushroomModel.Shape early = MushroomModel.shape(0.5, 15, 0, true, 300);
        assertNotNull(early);
        assertEquals(0, early.ringR(), 0);
        assertEquals(0, early.capTau(), 1e-9);
        for (double t = 1; t < MushroomModel.lifeSeconds(15); t += 3) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 2_000);
            assertNotNull(s, t + " с");
            assertTrue(s.stemTop() <= s.capY() + 1e-9, t + " с: ножка выше середины кольца");
            assertTrue(s.skirtR() + s.skirtW() <= 2_000 + 1e-9, t + " с: юбка дальше фронта");
            assertTrue(s.ringR() + s.ringA() <= s.capR() + 1e-9, t + " с: кольцо шире шапки");
            assertTrue(s.top() >= s.capY(), t + " с");
        }
        MushroomModel.Shape late = MushroomModel.shape(60, 15, 0, true, 5_000);
        assertEquals(MushroomModel.RING * late.capR(), late.ringR(), 1e-9);
        assertTrue(late.capTau() > 10, "шапка через минуту густая: " + late.capTau());
    }

    /** Ножка шире всего у земли (раструб пыли) или у шапки: рамка гриба берёт её края. */
    @Test
    void stemIsWidestAtItsEnds() {
        for (double dust : new double[]{0, 0.3, 1}) {
            double ends = Math.max(MushroomModel.stemRadius(0, 30, dust), MushroomModel.stemRadius(1, 30, dust));
            for (double w = 0; w <= 1; w += 0.01) assertTrue(MushroomModel.stemRadius(w, 30, dust) <= ends + 1e-9, "w = " + w);
        }
    }
}
