package ua.zentix.airstrike.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.entity.LauncherEntity;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionsTest {
    /**
     * Клубов дыма во вторичных подрывах крылатой ракеты ({@link Explosions#cookoff}): три догорания в воронке и подрывы
     * сервера ({@link ua.zentix.airstrike.warhead.Warheads#MISSILE_SECONDARIES}).
     */
    private static final int COOKOFF_SMOKE = (3 + ua.zentix.airstrike.warhead.Warheads.MISSILE_SECONDARIES.size()) * 8;

    /**
     * Долгий дым попаданий (шар, остывающий в дым, живёт дольше самого залпа) полного пакета РСЗО и пары крылатых ракет
     * помещается в группу облаков: иначе группа полна после первых попаданий, и у остальных дым не рождается. Столб
     * над ними — дальней картинки ({@code FarBlasts}), мест в группе он не занимает.
     */
    @Test
    void salvoSmokeFitsCloudGroup() {
        int pack = LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS;
        int rocket = Explosions.ballSmoke(BlastEffects.Drone.R, 1);
        int missile = Explosions.ballSmoke(BlastEffects.Missile.R, 1) + COOKOFF_SMOKE;
        int smoke = pack * rocket + 2 * missile;
        assertTrue(smoke <= FxBudget.CLOUD.limit(), smoke + " > " + FxBudget.CLOUD.limit());
    }
}
