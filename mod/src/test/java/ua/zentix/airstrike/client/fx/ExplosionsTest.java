package ua.zentix.airstrike.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.entity.LauncherEntity;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionsTest {
    /** Клубов дыма в трёх вторичных подрывах воронки крылатой ракеты ({@link Explosions#cookoff}). */
    private static final int COOKOFF_SMOKE = 3 * 8;

    /**
     * Долгий дым попаданий (шар, остывающий в дым, и ножка столба живут дольше самого залпа) полного пакета РСЗО
     * и пары крылатых ракет помещается в группу облаков: иначе группа полна после первых попаданий, и у остальных
     * дым не рождается — из-за постройки их не видно вовсе.
     */
    @Test
    void salvoSmokeFitsCloudGroup() {
        int pack = LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS;
        int rocket = Explosions.ballSmoke(BlastEffects.Drone.R, 1) + BlastEffects.Drone.COLUMN_TICKS;
        int missile = Explosions.ballSmoke(BlastEffects.Missile.R, 1) + BlastEffects.Missile.COLUMN_TICKS + COOKOFF_SMOKE;
        int smoke = pack * rocket + 2 * missile;
        assertTrue(smoke <= FxBudget.CLOUD.limit(), smoke + " > " + FxBudget.CLOUD.limit());
    }
}
