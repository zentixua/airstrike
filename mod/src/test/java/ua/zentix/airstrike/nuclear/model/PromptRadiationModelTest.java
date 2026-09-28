package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptRadiationModelTest {
    @Test
    void lethalDoseRange15kt() {
        assertEquals(500, PromptRadiationModel.doseRem(1300, 15), 1e-6);
        assertTrue(PromptRadiationModel.doseRem(1200, 15) > 500);
        assertTrue(PromptRadiationModel.doseRem(1400, 15) < 500);
        assertEquals(2.9e9, PromptRadiationModel.K, 0.01e9);
    }

    @Test
    void smallYieldRadiationOutrangesBlast() {
        // 1 кт: 500 бэр на ~760 м, дальше, чем 5 psi (440 м); у 1 Мт — наоборот
        assertEquals(500, PromptRadiationModel.doseRem(760, 1), 25);
        assertTrue(PromptRadiationModel.doseRem(BlastModel.rangeForOverpressure(BlastModel.kpa(5), 1), 1) > 500);
        assertTrue(PromptRadiationModel.doseRem(BlastModel.rangeForOverpressure(BlastModel.kpa(5), 1000), 1000) < 500);
    }

    @Test
    void shielding() {
        assertEquals(0.001, PromptRadiationModel.shielding(3, 0, 0, 0, 0), 1e-12);
        assertEquals(1, PromptRadiationModel.shielding(0, 0, 0, 0, 0), 0);
        assertEquals(0.2 * 0.3 * 0.6 * 0.9, PromptRadiationModel.shielding(0, 1, 1, 1, 1), 1e-12);
    }
}
