package ua.zentix.airstrike.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Свет огненных шаров кадра: в шейдер идут самые заметные. */
class FxLightsTest {
    @Test
    void keepsTheBrightestBalls() {
        FxLights lights = new FxLights();
        lights.begin();
        // шесть шаров радиуса 1..6 в 100 блоках: заметнее крупные
        for (int r = 1; r <= 6; r++) lights.add(100, 0, 0, r, 8, 1, 0.6f, 0.2f);
        double sum = 0;
        for (int i = 0; i < FxLights.MAX; i++) sum += lights.radius(i);
        assertEquals(3 + 4 + 5 + 6, sum, 1e-6, "остались четыре крупных");
        // шар ближе к глазу заметнее такого же вдали
        lights.add(10, 0, 0, 2, 8, 1, 0.6f, 0.2f);
        sum = 0;
        for (int i = 0; i < FxLights.MAX; i++) sum += lights.radius(i);
        assertEquals(2 + 4 + 5 + 6, sum, 1e-6, "ближний вытеснил самый слабый");
        lights.begin();
        for (int i = 0; i < FxLights.MAX; i++) assertEquals(0, lights.radius(i), "новый кадр — без шаров");
    }
}
