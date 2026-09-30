package ua.zentix.airstrike.gametest.scenario;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Режим прогона сценариев (системные свойства задач Gradle).
 * <ul>
 *     <li>{@code airstrike.scenario.only} ({@code -PscenarioOnly=…}) — только сценарии, имя которых подходит под
 *     регулярное выражение (без обратной косой черты: файл аргументов MDG её удваивает): так повторяют одно падение;</li>
 *     <li>{@code airstrike.scenario.realChunks} ({@code -PscenarioRealChunks}) — настоящая загрузка чанков: без
 *     {@link InstantChunks}, сервер в темпе игры (20 тиков/с), свойства те же, эталон не сверяется (полёт зависит
 *     от того, как быстро машина генерирует).</li>
 * </ul>
 */
final class ScenarioMode {
    static final boolean REAL_CHUNKS = Boolean.getBoolean("airstrike.scenario.realChunks");
    private static final Pattern ONLY = compile(System.getProperty("airstrike.scenario.only", ""));

    private static boolean paced;
    private static long lastTick;

    private ScenarioMode() {}

    private static Pattern compile(String regex) {
        return regex.isBlank() ? null : Pattern.compile(regex);
    }

    static boolean selected(Scenario s) {
        return ONLY == null || ONLY.matcher(s.id()).find();
    }

    /** Как повторить сценарий одним прогоном — к каждому сообщению о падении. */
    static String reproduce(Scenario s) {
        // точки не экранировать: файл аргументов MDG удваивает обратную косую черту, а точка и так совпадает с точкой
        return "повторить: ./gradlew runScenarioSweep -PscenarioOnly='^" + s.id() + "$' -PscenarioSeeds="
                + (s.seed() + 1) + (REAL_CHUNKS ? " -PscenarioRealChunks" : "");
    }

    /**
     * Темп игры для всего сервера сразу, а не на тест: сценарии партии идут одновременно, и пауза в тике каждого
     * сложилась бы. Сервер GameTest иначе тикает без пауз, и фоновая генерация за полётом не успевает никогда.
     */
    static synchronized void paceServer() {
        if (paced) return;
        paced = true;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            // parkNanos просыпается раньше (задачи чанков будят поток сервера): ждать до срока в цикле
            long deadline = lastTick + 50_000_000L, left;
            while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
            lastTick = System.nanoTime();
        });
    }
}
