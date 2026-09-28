package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThermalModelTest {
    private static final double V = ThermalModel.VISIBILITY_CLEAR;

    @Test
    void thirdDegreeBurns15kt() {
        double r = ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, false, V);
        assertTrue(r >= 2100 && r <= 2300, "8 кал/см² на " + r);
        assertEquals(ThermalModel.BURN_3, ThermalModel.fluenceCalPerCm2(r, 15, false, V), 1e-6);
    }

    @Test
    void firstDegreeBurns() {
        assertEquals(3400, ThermalModel.rangeForFluence(ThermalModel.BURN_1, 15, false, V), 3400 * 0.1);
        assertEquals(13000, ThermalModel.rangeForFluence(ThermalModel.BURN_3, 1000, false, V), 13000 * 0.1);
        assertEquals(19000, ThermalModel.rangeForFluence(ThermalModel.BURN_1, 1000, false, V), 19000 * 0.1);
    }

    @Test
    void groundBurstAndWeatherShorten() {
        double air = ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, false, V);
        assertTrue(ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, true, V) < air);
        assertTrue(ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, false, ThermalModel.VISIBILITY_RAIN) < air);
    }
}
