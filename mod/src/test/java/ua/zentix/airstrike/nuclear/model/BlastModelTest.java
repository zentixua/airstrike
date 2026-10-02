package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastModelTest {
    /** Таблица 1.1 документа: мощность, psi, дальность в метрах; допуск 10%. */
    @ParameterizedTest
    @CsvSource({
            "1, 20, 230", "1, 5, 440", "1, 3, 590", "1, 1, 1300", "1, 0.5, 2400",
            "15, 20, 570", "15, 5, 1090", "15, 3, 1450", "15, 1, 3200", "15, 0.5, 6000",
            "100, 20, 1070", "100, 5, 2050", "100, 3, 2700", "100, 1, 6000", "100, 0.5, 11000",
            "1000, 20, 2300", "1000, 5, 4400", "1000, 3, 5900", "1000, 1, 13000", "1000, 0.5, 24000",
    })
    void pressureTable(double yieldKt, double psi, double rangeM) {
        double r = BlastModel.rangeForOverpressure(BlastModel.kpa(psi), yieldKt);
        assertEquals(rangeM, r, rangeM * 0.1, () -> yieldKt + " кт, " + psi + " psi");
        assertEquals(psi, BlastModel.psi(BlastModel.overpressureKpa(r, yieldKt)), psi * 1e-6);
    }

    @Test
    void pressureFallsWithRange() {
        double prev = Double.MAX_VALUE;
        for (double r = 1; r < 1e5; r *= 1.3) {
            double p = BlastModel.overpressureKpa(r, 15);
            assertTrue(p < prev);
            prev = p;
        }
    }

    @Test
    void frontSlowsToSoundSpeed() {
        assertEquals(343, BlastModel.frontSpeed(0), 1e-9);
        assertEquals(343, BlastModel.frontSpeed(BlastModel.overpressureKpa(1e6, 15)), 0.1);
        assertTrue(BlastModel.frontSpeed(BlastModel.kpa(20)) > 450);
    }

    @Test
    void dynamicPressure() {
        // q при 5 psi ≈ 0.6 psi (Glasstone, табл. 3.07)
        double q = BlastModel.psi(BlastModel.dynamicPressureKpa(BlastModel.kpa(5)));
        assertEquals(0.58, q, 0.1);
        assertEquals(0, BlastModel.dynamicPressureKpa(0), 0);
    }

    @Test
    void positivePhase() {
        assertEquals(0.4 * Math.cbrt(15), BlastModel.positivePhaseSeconds(15), 1e-12);
        assertEquals(4, BlastModel.positivePhaseSeconds(1000), 1e-9);
    }

    @Test
    void noSuchPressureGivesZeroRange() {
        assertEquals(0, BlastModel.rangeForOverpressure(1e9, 15), 0);
        assertEquals(0, BlastModel.rangeForSurfaceOverpressure(1e9, 50), 0);
        assertEquals(0, BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(1), 0), 0);
    }

    /** Обычный заряд у земли — та же волна, что у подрыва с тем же эквивалентом: W кг ТНТ = W / (0,5·10⁶) кт. */
    @Test
    void surfaceChargeIsNuclearEquivalent() {
        for (double kg : new double[]{3, 50, 450}) {
            for (double r : new double[]{5, 30, 150}) {
                double nuclear = BlastModel.overpressureKpa(r, kg / 5e5);
                assertEquals(nuclear, BlastModel.surfaceOverpressureKpa(r, kg), nuclear * 1e-9, () -> kg + " кг, " + r + " м");
            }
        }
    }

    /** Стекло (0,8 psi) у наземного взрыва шахеда (50 кг) вылетает до ~73 м, ракеты (450 кг) — до ~150 м. */
    @Test
    void surfaceRangeForGlass() {
        double kpa = BlastModel.kpa(0.8);
        double drone = BlastModel.rangeForSurfaceOverpressure(kpa, 50);
        assertEquals(73, drone, 2);
        assertEquals(0.8, BlastModel.psi(BlastModel.surfaceOverpressureKpa(drone, 50)), 1e-6);
        assertEquals(152, BlastModel.rangeForSurfaceOverpressure(kpa, 450), 3);
    }
}
