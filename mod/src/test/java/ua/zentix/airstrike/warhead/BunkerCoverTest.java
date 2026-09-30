package ua.zentix.airstrike.warhead;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BunkerCoverTest {
    /**
     * Бомба пробила крышу в 1 блок (верх 75, карта высот 76) и рвётся в комнате под ней: свой ход рядом с зарядом
     * (ниже крыши до дна комнаты) и край стены выше верх не сдвигают — заряд неглубоко, взрыв прорывается наружу.
     */
    @Test
    void shaftUnderThinRoofBreaches() {
        int[] ring = {76, 76, 66, 76, 76, 66, 76, 80};
        int top = BunkerCover.surface(ring);
        assertEquals(76, top);
        assertEquals(6, BunkerCover.depth(top, 70));
        assertTrue(BunkerCover.breaches(top, 70));
    }

    /** Ход в открытом грунте на 24 блока: верх — грунт вокруг, не дно хода; взрыв под землёй, прорыва нет. */
    @Test
    void shaftInOpenSoilStaysUnderground() {
        int[] ring = {64, 64, 65, 64, 63, 64, 64, 64};
        int top = BunkerCover.surface(ring);
        assertEquals(64, top);
        assertEquals(24, BunkerCover.depth(top, 40));
        assertFalse(BunkerCover.breaches(top, 40));
    }

    /** Граница прорыва: ровно BREACH_DEPTH — прорыв, на блок глубже — нет. */
    @Test
    void breachBoundary() {
        assertTrue(BunkerCover.breaches(64, 64 - BunkerCover.BREACH_DEPTH));
        assertFalse(BunkerCover.breaches(64, 64 - BunkerCover.BREACH_DEPTH - 1));
    }

    /** Кольцо не меняется (сортируется копия). */
    @Test
    void ringIsNotModified() {
        int[] ring = {80, 60, 70, 64, 64, 64, 64, 64};
        BunkerCover.surface(ring);
        assertEquals(80, ring[0]);
        assertEquals(60, ring[1]);
    }
}
