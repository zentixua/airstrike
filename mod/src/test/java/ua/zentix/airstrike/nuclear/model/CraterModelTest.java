package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ua.zentix.airstrike.nuclear.model.CraterModel.Soil;

class CraterModelTest {
    @Test
    void sizes() {
        assertEquals(52, CraterModel.radius(15, Soil.DRY), 1);
        assertEquals(17, CraterModel.depth(15, Soil.DRY), 0.5);
        assertEquals(183, CraterModel.radius(1000, Soil.DRY), 2);
        assertEquals(61, CraterModel.depth(1000, Soil.DRY), 1);
        assertEquals(0.7 * CraterModel.radius(15, Soil.DRY), CraterModel.radius(15, Soil.ROCK), 1e-9);
        assertEquals(1.3 * CraterModel.depth(15, Soil.DRY), CraterModel.depth(15, Soil.WET), 1e-9);
    }

    @Test
    void profile() {
        double r = CraterModel.radius(15, Soil.DRY), d = CraterModel.depth(15, Soil.DRY);
        assertEquals(d, CraterModel.profileDepth(0, 15, Soil.DRY), 1e-9);
        assertEquals(0, CraterModel.profileDepth(r, 15, Soil.DRY), 1e-9);
        assertEquals(0, CraterModel.profileDepth(2 * r, 15, Soil.DRY), 0);
        assertEquals(0.25 * d, CraterModel.rimHeight(r, 15, Soil.DRY), 1e-9);
        assertEquals(0, CraterModel.rimHeight(0, 15, Soil.DRY), 0);
        assertEquals(0, CraterModel.rimHeight(2 * r, 15, Soil.DRY), 1e-9);
        assertTrue(CraterModel.rimHeight(1.5 * r, 15, Soil.DRY) > 0);
        // поверхность без скачков: шаг 0.1 м меняет высоту меньше чем на 0.2 м
        double prev = -d;
        for (double x = 0; x < 2.5 * r; x += 0.1) {
            double h = CraterModel.rimHeight(x, 15, Soil.DRY) - CraterModel.profileDepth(x, 15, Soil.DRY);
            assertTrue(Math.abs(h - prev) < 0.2, "скачок на " + x);
            prev = h;
        }
    }

    @Test
    void craterOnlyForLowBursts() {
        assertTrue(CraterModel.formsCrater(0, 15));
        assertTrue(CraterModel.formsCrater(20, 15));
        assertFalse(CraterModel.formsCrater(30, 15));
        assertFalse(CraterModel.formsCrater(Yield.optimalBurstHeight(15), 15));
    }
}
