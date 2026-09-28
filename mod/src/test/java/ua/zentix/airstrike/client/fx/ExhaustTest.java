package ua.zentix.airstrike.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.RocketEntity;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExhaustTest {
    /**
     * Следы всего пакета РСЗО, пока горят двигатели, помещаются в группу шлейфов: иначе группа полна после первой
     * дюжины ракет, и у остальных след не рождается.
     */
    @Test
    void rocketPackTrailsFitTrailGroup() {
        int pack = LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS;
        int puffs = pack * (RocketEntity.BURN_TICKS + 1) * Exhaust.ROCKET_PUFFS_PER_TICK;
        assertTrue(puffs <= FxBudget.TRAIL.limit(), puffs + " > " + FxBudget.TRAIL.limit());
    }
}
