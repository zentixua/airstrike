package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Луч по карте высот: прямая видимость, холм между — разность хода и высота, с которой источник виден. */
class SightlineTest {
    @Test
    void flatGroundIsOpen() {
        Sightline.Result r = Sightline.trace((x, z) -> 64, 0, 65, 0, 2000, 66, 0);
        assertEquals(0, r.pathDifference(), 1e-9);
        assertEquals(0, r.hidden(), 1e-9);
    }

    @Test
    void unknownColumnsAreNoObstacle() {
        assertEquals(Sightline.Result.OPEN, Sightline.trace((x, z) -> Double.NaN, 0, 65, 0, 2000, 66, 0));
    }

    @Test
    void hillInTheMiddleHidesTheBaseButNotTheColumn() {
        // холм 40 блоков над равниной посередине пути в 2 км: источник у земли, глаз на высоте 2 блока
        Sightline.Heights hill = (x, z) -> x >= 960 && x < 1040 ? 104 : 64;
        Sightline.Result r = Sightline.trace(hill, 0, 65, 0, 2000, 66, 0);
        double a = Math.hypot(1000, 104 - 65), b = Math.hypot(1000, 66 - 104), d = Math.hypot(2000, 1);
        assertEquals(a + b - d, r.pathDifference(), 0.5, "кромка холма — разность хода");
        // прямая из глаза через вершину на полпути поднимается над источником на 2·(104 − 66) + 66 − 65
        assertEquals(2 * (104 - 66) + 1, r.hidden(), 3);
    }

    @Test
    void raySamplesAreBounded() {
        int[] calls = {0};
        Sightline.trace((x, z) -> {
            calls[0]++;
            return 64;
        }, 0, 65, 0, 8000, 66, 0);
        assertTrue(calls[0] < Sightline.MAX_SAMPLES, "точек " + calls[0]);
    }
}
