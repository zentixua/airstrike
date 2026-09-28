package ua.zentix.airstrike.client.fx.particle;

import org.junit.jupiter.api.Test;

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
}
