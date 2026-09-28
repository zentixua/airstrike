package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudModelTest {
    @Test
    void stabilizedTop() {
        for (double y : new double[]{5, 15, 30, 100}) {
            double doc = 12000 * Math.pow(y / 15, 0.25);
            assertEquals(doc, CloudModel.stabilizedTop(y), doc * 0.1, () -> y + " кт");
        }
        double mt = CloudModel.stabilizedTop(1000);
        assertTrue(mt >= 20000 && mt <= 22000, "1 Мт: " + mt);
        double prev = 0;
        for (double y = 0.1; y < 1e5; y *= 1.2) {
            double h = CloudModel.stabilizedTop(y);
            assertTrue(h > prev);
            prev = h;
        }
    }

    @Test
    void capRadius() {
        double r15 = CloudModel.capRadius(15);
        assertTrue(r15 >= 1500 && r15 <= 2500);
        assertEquals(10000, CloudModel.capRadius(1000), 1000);
    }

    @Test
    void stabilizationWithinSixToTenMinutes() {
        for (double y : new double[]{15, 100, 1000}) {
            double s = CloudModel.stabilizationSeconds(y);
            assertTrue(s >= 360 && s <= 600, y + " кт: " + s);
        }
    }

    @Test
    void cloudRisesToTop() {
        double y = 15, hob = 580;
        double ts = CloudModel.stabilizationSeconds(y), top = CloudModel.stabilizedTop(y);
        assertTrue(CloudModel.top(0, hob, y) < 1000);
        assertEquals(top, CloudModel.top(ts, hob, y), top * 0.06);
        assertTrue(CloudModel.top(60, hob, y) < CloudModel.top(120, hob, y));
        assertEquals(0.6, CloudModel.capBottom(10 * ts, 0, y) / CloudModel.top(10 * ts, 0, y), 1e-6);
        assertTrue(CloudModel.capBottom(ts, hob, y) < CloudModel.top(ts, hob, y));
    }

    @Test
    void wilson() {
        assertFalse(CloudModel.wilsonCloud(0));
        assertTrue(CloudModel.wilsonCloud(0.4));
        assertTrue(CloudModel.wilsonCloud(1));
    }
}
