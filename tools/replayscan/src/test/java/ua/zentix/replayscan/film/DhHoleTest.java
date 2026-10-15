package ua.zentix.replayscan.film;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Калибровка глубины Distant Horizons ({@link DhHole#robustLine}): пары «ожидаемая земля → глубина» ложатся на прямую
 * (формат глубины DH не документирован — своя проекция), дыры — выбросы, которых меньше половины.
 */
class DhHoleTest {
    @Test
    void lineSurvivesUpToHalfOutliers() {
        // перспективная нормализация с far в тысячи блоков: наклон мал, но различим
        double a = 2.5e-4, b = 0.62;
        ArrayDeque<double[]> pairs = new ArrayDeque<>();
        Random r = new Random(1);
        for (int i = 0; i < 400; i++) {
            double d = 40 + 160 * r.nextDouble();
            if (i % 3 == 0) pairs.addLast(new double[]{d, 0.9999999});   // дыра: сквозь неё небо
            else pairs.addLast(new double[]{d, a * d + b});
        }
        double[] line = DhHole.robustLine(pairs);
        assertEquals(a, line[0], 1e-6, "slope");
        assertEquals(b, line[1], 1e-3, "offset");
    }

    @Test
    void tooFewPairsIsUnknown() {
        ArrayDeque<double[]> pairs = new ArrayDeque<>();
        for (int i = 0; i < 59; i++) pairs.addLast(new double[]{i, i});
        assertTrue(Double.isNaN(DhHole.robustLine(pairs)[0]));
    }
}
