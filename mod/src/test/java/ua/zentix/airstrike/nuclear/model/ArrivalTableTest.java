package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalTableTest {
    private static final ArrivalTable T15 = ArrivalTable.of(15, 20000);

    /** Время прихода фронта для 15 кт из раздела 1.1, допуск 15%. */
    @ParameterizedTest
    @CsvSource({"200, 0.09", "1000, 1.6", "2000, 4.3", "5000, 12.8", "10000, 27"})
    void arrivalTimes15kt(double rangeM, double seconds) {
        assertEquals(seconds, T15.arrivalSeconds(rangeM), seconds * 0.15);
    }

    @Test
    void inverseMatches() {
        for (double r = 0.5; r < 40000; r *= 1.7) {
            assertEquals(r, T15.radiusAt(T15.arrivalSeconds(r)), r * 1e-3 + 1e-6);
        }
        assertEquals(0, T15.radiusAt(0), 0);
        assertEquals(0, T15.arrivalSeconds(0), 0);
    }

    @Test
    void beyondTableAtSoundSpeed() {
        double t = T15.arrivalSeconds(20000);
        assertEquals(t + 10, T15.arrivalSeconds(20000 + 3430), 1e-9);
        assertEquals(20000 + 343 * 5, T15.radiusAt(t + 5), 1e-6);
    }

    @Test
    void monotonic() {
        double prev = -1;
        for (double s = 0; s < 60; s += 0.05) {
            double r = T15.radiusAt(s);
            assertTrue(r > prev || s == 0);
            prev = r;
        }
    }
}
