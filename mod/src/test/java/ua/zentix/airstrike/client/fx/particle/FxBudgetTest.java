package ua.zentix.airstrike.client.fx.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxBudgetTest {
    /** Группы слоя вместе меньше его очереди: движок частиц никогда не вытесняет старые клубы. */
    @Test
    void groupsFitLayerQueue() {
        for (boolean additive : new boolean[]{false, true}) {
            int total = FxBudget.layerTotal(additive);
            assertTrue(total <= FxBudget.QUEUE, (additive ? "glow" : "blend") + ": " + total + " > " + FxBudget.QUEUE);
        }
    }

    /** До половины группы рождается всё, дальше — всё реже, у предела — ничего. */
    @Test
    void headroomThinsLinearlyFromHalf() {
        assertEquals(1, FxBudget.headroom(0), 1e-6);
        assertEquals(1, FxBudget.headroom(0.5f), 1e-6);
        assertEquals(0.5f, FxBudget.headroom(0.75f), 1e-6);
        assertEquals(0, FxBudget.headroom(1), 1e-6);
        assertEquals(0, FxBudget.headroom(1.2f), 1e-6);
    }
}
