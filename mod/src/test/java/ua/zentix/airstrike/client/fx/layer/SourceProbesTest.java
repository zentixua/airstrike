package ua.zentix.airstrike.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Пробы видимости источников: у каждого источника кадра свой номер, с 1 (квадрат блика пишет минус номер в поле мягкости,
 * 0 там — «пробы нет»), и номеров не больше, чем вмещает это 16-битное поле.
 */
class SourceProbesTest {
    @Test
    void numbersFitTheVertexField() {
        SourceProbes probes = new SourceProbes();
        probes.begin();
        assertEquals(1, probes.add(0, 0, -100, 5), "первый источник — проба 1");
        assertEquals(2, probes.add(10, 0, -100, 5));
        // память источников растёт сама
        for (int i = 3; i <= 1000; i++) assertEquals(i, probes.add(i, 0, -100, 1));
        probes.begin();
        assertEquals(1, probes.add(0, 0, -100, 5), "новый кадр — с начала");
        for (int i = 2; i <= SourceProbes.MAX; i++) probes.add(0, 0, -100, 1);
        assertEquals(0, probes.add(0, 0, -100, 1), "сверх поля вершины — без пробы");
    }
}
