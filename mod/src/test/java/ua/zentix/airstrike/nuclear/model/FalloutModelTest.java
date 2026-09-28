package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FalloutModelTest {
    /** 15 кт наземный, ветер 24 км/ч на восток (+x). */
    private static final FalloutModel GROUND = new FalloutModel(15, 0, 0, FalloutModel.REFERENCE_WIND);

    /** Осевые контуры H+1 против ориентира NUKEMAP, допуск 25%. */
    @ParameterizedTest
    @CsvSource({"1000, 4000", "100, 13000", "10, 40000"})
    void contoursDownwind(double rate, double rangeM) {
        assertEquals(rangeM, contour(GROUND, rate, 0), rangeM * 0.25);
    }

    @Test
    void upwindShorterThanDownwind() {
        for (double rate : new double[]{1000, 100, 10}) {
            assertTrue(contour(GROUND, rate, Math.PI) < contour(GROUND, rate, 0) / 3, "контур " + rate);
        }
        // поперёк следа тоже уже, чем вдоль
        assertTrue(contour(GROUND, 100, Math.PI / 2) < contour(GROUND, 100, 0) / 3);
    }

    @Test
    void windDirectionRotatesTrail() {
        FalloutModel north = new FalloutModel(15, 0, Math.PI / 2, FalloutModel.REFERENCE_WIND);
        assertEquals(GROUND.doseRateAtH1(8000, 300), north.doseRateAtH1(-300, 8000), 1e-6);
    }

    @Test
    void decaySevenTen() {
        double h1 = GROUND.doseRate(5000, 0, 1e6, 1);
        assertEquals(GROUND.doseRateAtH1(5000, 0), h1, 1e-9);
        assertEquals(0.1, GROUND.doseRate(5000, 0, 1e6, 7) / h1, 0.01);
        assertEquals(0.01, GROUND.doseRate(5000, 0, 1e6, 49) / h1, 0.001);
    }

    @Test
    void zeroBeforeArrival() {
        double arrival = GROUND.arrivalSeconds(10000, 0);
        assertEquals(10000 / FalloutModel.REFERENCE_WIND, arrival, 1e-9);
        assertEquals(0, GROUND.doseRate(10000, 0, arrival - 1, 5), 0);
        assertTrue(GROUND.doseRate(10000, 0, arrival + 1, 5) > 0);
        assertEquals(0, GROUND.arrivalSeconds(-3000, 0), 0);
    }

    @Test
    void airBurstHasNoFallout() {
        FalloutModel air = new FalloutModel(15, Yield.optimalBurstHeight(15), 0, FalloutModel.REFERENCE_WIND);
        assertEquals(0, air.surfaceFraction(), 0);
        assertEquals(0, air.doseRateAtH1(2000, 0), 0);
        assertEquals(1, GROUND.surfaceFraction(), 0);
        double low = new FalloutModel(15, 100, 0, FalloutModel.REFERENCE_WIND).surfaceFraction();
        assertTrue(low > 0 && low < 1);
    }

    /** Дальность от эпицентра в направлении angle, где мощность в H+1 падает до rate. */
    private static double contour(FalloutModel m, double rate, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return Solve.decreasingRoot(r -> m.doseRateAtH1(r * c, r * s), rate, 1, 1e6);
    }
}
