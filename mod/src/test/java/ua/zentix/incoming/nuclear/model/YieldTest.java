package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YieldTest {
    @Test
    void presetsAndBurstHeight() {
        assertEquals(15, Yield.HIROSHIMA.kt(), 0);
        assertEquals("incoming.yield.hiroshima", Yield.HIROSHIMA.key());
        assertEquals(590, Yield.optimalBurstHeight(15), 10);
        assertEquals(2400, Yield.optimalBurstHeight(1000), 1e-9);
    }
}
