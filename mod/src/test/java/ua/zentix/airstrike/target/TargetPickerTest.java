package ua.zentix.airstrike.target;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetPickerTest {
    @Test
    void allReadyReachesTheEnd() {
        assertEquals(1, TargetPicker.readyFraction(1, 1, 900, -300, (x, z) -> true));
    }

    @Test
    void stopsJustBeforeFirstUnreadyChunk() {
        // вдоль +x: чанки 0..3 готовы, 4 (x = 64..79) — нет
        double t = TargetPicker.readyFraction(8, 8, 108, 8, (x, z) -> x < 4);
        double x = 8 + t * 100;
        assertTrue(x < 64 && x > 63.9, "обрезано у границы: " + x);
    }

    @Test
    void unreadyStartGivesZero() {
        assertEquals(0, TargetPicker.readyFraction(8, 8, 100, 100, (x, z) -> false));
    }

    @Test
    void visitsEveryColumnTheSegmentCrosses() {
        Set<Long> seen = new HashSet<>();
        TargetPicker.readyFraction(-5.5, 3.25, 70.5, 47.75, (x, z) -> {
            seen.add(((long) x << 32) | (z & 0xFFFFFFFFL));
            return true;
        });
        // проверка по мелким шагам: каждая колонка, через которую проходит отрезок, была спрошена
        for (int i = 0; i <= 10000; i++) {
            double k = i / 10000.0;
            int cx = (int) Math.floor((-5.5 + k * 76) / 16), cz = (int) Math.floor((3.25 + k * 44.5) / 16);
            assertTrue(seen.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL)), "пропущена колонка " + cx + "," + cz);
        }
    }

    @Test
    void negativeDirectionStopsBeforeBoundary() {
        // вдоль −z из чанка z = 0: чанк z = −1 не готов, граница z = 0
        double t = TargetPicker.readyFraction(8, 12, 8, -40, (x, z) -> z >= 0);
        double z = 12 - t * 52;
        assertTrue(z >= 0 && z < 0.1, "обрезано у границы: " + z);
    }
}
