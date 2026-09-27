package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ua.zentix.airstrike.nuclear.model.SicknessModel.Stage;

class SicknessModelTest {
    @Test
    void belowOneGrayNothing() {
        assertEquals(Stage.NONE, SicknessModel.stage(0.9, 0));
        assertEquals(Stage.NONE, SicknessModel.stage(0.9, 100));
        assertEquals(Double.POSITIVE_INFINITY, SicknessModel.onsetHours(0.5));
    }

    @Test
    void mildRecovers() {
        double d = 1.5;
        assertEquals(3, SicknessModel.onsetHours(d), 0);
        assertEquals(Stage.NONE, SicknessModel.stage(d, 2));
        assertEquals(Stage.PRODROMAL, SicknessModel.stage(d, 3.5));
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 24));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, 60));
        assertEquals(Stage.NONE, SicknessModel.stage(d, 200));
        assertFalse(SicknessModel.lethal(d));
        assertEquals(1.5, SicknessModel.hungerMultiplier(d), 0);
        assertEquals(0, SicknessModel.deathChance(d), 0);
    }

    @Test
    void moderate() {
        double d = 4;
        assertTrue(SicknessModel.onsetHours(d) >= 1 && SicknessModel.onsetHours(d) <= 2);
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 12));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, 48));
        assertEquals(4, SicknessModel.maxHealthPenalty(d), 0);
        assertEquals(2, SicknessModel.hungerMultiplier(d), 0);
        assertTrue(SicknessModel.noRegeneration(d));
        assertTrue(SicknessModel.deathChance(5) > 0 && SicknessModel.deathChance(5) < 1);
    }

    @Test
    void severeIsFatal() {
        double d = 8;
        assertEquals(0.5, SicknessModel.onsetHours(d), 0);
        assertEquals(Stage.PRODROMAL, SicknessModel.stage(d, 1));
        assertEquals(Stage.LATENT, SicknessModel.stage(d, 6));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(d, 20));
        assertEquals(Stage.FATAL, SicknessModel.stage(d, 100));
        assertTrue(SicknessModel.lethal(d));
        assertEquals(10, SicknessModel.maxHealthPenalty(d), 0);
        assertEquals(1, SicknessModel.weaknessAmplifier(d));
    }

    @Test
    void veryHighDosesKillFast() {
        assertEquals(Stage.PRODROMAL, SicknessModel.stage(20, 0));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(20, 12));
        assertEquals(Stage.FATAL, SicknessModel.stage(20, 24));
        assertEquals(Stage.FATAL, SicknessModel.stage(60, 1));
        assertEquals(Stage.MANIFEST, SicknessModel.stage(60, 0.5));
    }
}
