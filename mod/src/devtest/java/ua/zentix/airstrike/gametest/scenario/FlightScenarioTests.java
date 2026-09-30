package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Сценарии полёта (корень 5 плана фундамента): вместо одного примера на свойство — оружие × цель × возмущение × зерно,
 * и у каждого полёта одни и те же свойства ({@link ScenarioRun}). Полёт с одним зерном повторяется точно, поэтому
 * сценарии зерна 0 сверяются с эталоном траекторий ({@code trajectoriesMatchBaseline}, {@link Baseline}).
 * <p>
 * В обычном прогоне GameTest (CI) — зерно 0 всех сочетаний. Больше зёрен — {@code ./gradlew runScenarioSweep}
 * ({@code -PscenarioSeeds=N}, до {@link Scenario#MAX_SEEDS}): отдельное пространство имён шаблона, идут одни сценарии.
 */
@GameTestHolder(Airstrike.MOD_ID)
public final class FlightScenarioTests {
    /** Сколько зёрен на сочетание (обычный прогон — одно). */
    private static final int SEEDS = Math.min(Scenario.MAX_SEEDS, Integer.getInteger("airstrike.scenario.seeds", 1));
    /** Прогон одних сценариев ({@code runScenarioSweep}): шаблон в пространстве имён, которое включено только там. */
    private static final boolean SWEEP = Boolean.getBoolean("airstrike.scenario.sweep");
    /** Сценариев в партии: идут одновременно, каждый на своей площадке. */
    private static final int BATCH = 40;
    /** Предел теста, тиков: самый долгий полёт (шахед 50 с × 1,5 с погоней, B-2 с уходом) с запасом. */
    private static final int TIMEOUT = 8000;

    private FlightScenarioTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> flightScenarios() {
        List<Scenario> all = Scenario.all(SEEDS, ModList.get().isLoaded("sable"));
        String template = (SWEEP ? "airstrike_sweep" : Airstrike.MOD_ID) + ":pad";
        List<TestFunction> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Scenario s = all.get(i);
            out.add(new TestFunction("flight_scenarios_" + i / BATCH, "flightscenario." + s.id(), template, Rotation.NONE, TIMEOUT, 0,
                    !s.knownIssue(), false, 1, 1, true, h -> new ScenarioRun(h, s).start()));
        }
        return out;
    }
}
