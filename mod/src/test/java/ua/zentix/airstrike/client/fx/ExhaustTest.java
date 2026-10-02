package ua.zentix.airstrike.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.RocketEntity;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExhaustTest {
    /**
     * Клубы объёма трёх полных пакетов РСЗО, пока горят двигатели, помещаются в группу шлейфов. Сам след — лента, мест
     * не занимает: и в залпе больше клубы пропадут, а след останется сплошным.
     */
    @Test
    void volumePuffsOfThreeRocketPacksFitTrailGroup() {
        int pack = LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS;
        int puffs = 3 * pack * (RocketEntity.BURN_TICKS + 1) * Exhaust.VOLUME_PUFFS_PER_TICK;
        assertTrue(puffs <= FxBudget.TRAIL.limit(), puffs + " > " + FxBudget.TRAIL.limit());
    }
}
