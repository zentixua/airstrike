package ua.zentix.airstrike.client.fx.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxParticleTest {
    /**
     * Клуб из цепочки виден по тому, перекрывает ли он соседей на экране: вдоль следа — всегда, сбоку молодой клуб
     * мельче шага (цепочка точек на ленте) — нет, разросшийся до двух шагов — целиком; между ними — не убывая.
     */
    @Test
    void chainPuffShowsOnlyWhereItOverlapsNeighboursOnScreen() {
        // клуб РСЗО у сопла: квадрат 2 × 0,7, плотная часть 0,7 его; шаг — путь ракеты за тик на разгоне
        float young = 2 * FxParticle.DENSE * 0.7f, gap = 3;
        assertEquals(1, FxParticle.chainShare(young, gap, 0), "вдоль следа");
        assertEquals(1, FxParticle.chainShare(young, gap, 0.1f), "почти вдоль следа");
        assertEquals(0, FxParticle.chainShare(young, gap, 1), "сбоку молодой клуб — точка на ленте");
        assertEquals(0, FxParticle.chainShare(gap, gap, 1), "ширина в шаг — между клубами просвет");
        assertEquals(1, FxParticle.chainShare(2 * gap, gap, 1), "ширина в два шага — сплошь");
        assertEquals(1, FxParticle.chainShare(young, 0, 1), "без шага — не цепочка");
        float last = 0;
        for (float w = 0; w <= 3 * gap; w += 0.05f) {
            float share = FxParticle.chainShare(w, gap, 1);
            assertTrue(share >= last && share <= 1, w + ": " + share);
            last = share;
        }
    }
}
