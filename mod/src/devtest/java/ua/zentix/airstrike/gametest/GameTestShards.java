package ua.zentix.airstrike.gametest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * GameTest по частям на нескольких машинах CI ({@code ./gradlew runGameTestServer -PgametestShard=i/n},
 * {@code .github/workflows/build.yml}): каждая машина гоняет свою часть партий, вместе — все тесты ровно по разу.
 * Партия целиком в одной части: её тесты идут одновременно и могут быть на это рассчитаны. Части равняются по времени
 * партий в прошлых прогонах CI ({@code gametest-durations.json} в ресурсах devtest, пишет
 * {@code tools/gametest_durations.py} по логам GameTest); партии, которой в таблице нет, — медиана таблицы. Без
 * свойства {@code airstrike.gametest.shard} — все тесты.
 * <p>
 * Список тестов сервер GameTest получает до любых событий мода ({@code GameTestRegistry.getAllTestFunctions()} в
 * {@code Main}) и копирует себе, а партии из этой копии собирает в {@code initServer} после
 * {@link ServerAboutToStartEvent} — здесь копия и сужается до своей части. В jar мода не входит (devtest).
 */
@Mod(Airstrike.MOD_ID)
public final class GameTestShards {
    private static final String RESOURCE = "/gametest-durations.json";

    public GameTestShards() {
        String spec = System.getProperty("airstrike.gametest.shard", "");
        if (!spec.isBlank()) NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> select(e, spec));
    }

    private static void select(ServerAboutToStartEvent e, String spec) {
        if (!(e.getServer() instanceof GameTestServer server)) return;
        String[] parts = spec.split("/");
        int part = parts.length == 2 ? Integer.parseInt(parts[0].trim()) : 0;
        int count = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 0;
        if (count < 1 || part < 1 || part > count) throw new IllegalArgumentException("airstrike.gametest.shard=" + spec + ": ожидается i/n, 1 ≤ i ≤ n");

        List<TestFunction> tests = ObfuscationReflectionHelper.getPrivateValue(GameTestServer.class, server, "testFunctions");
        int total = tests.size();
        Map<String, Double> weights = weights(tests.stream().map(TestFunction::batchName).toList(), durations());
        Map<String, Integer> plan = assign(weights, count);
        tests.removeIf(t -> plan.get(t.batchName()) != part - 1);

        Map<String, Double> mine = new TreeMap<>();
        for (TestFunction t : tests) mine.put(t.batchName(), weights.get(t.batchName()));
        Airstrike.LOG.info("GameTest: часть {} из {} — тестов {} из {}, партий {}, по прошлым прогонам ~{} с; партии: {}",
                part, count, tests.size(), total, mine.size(),
                String.format(Locale.ROOT, "%.0f", mine.values().stream().mapToDouble(Double::doubleValue).sum()), String.join(", ", mine.keySet()));
    }

    /**
     * Время каждой партии: из таблицы прошлых прогонов, а партии без времени — медиана таблицы (новая партия обычно
     * короткая); пустая таблица — по секунде.
     *
     * @param batches имена партий тестов (с повторами — по тесту на имя)
     */
    static Map<String, Double> weights(Collection<String> batches, Map<String, Double> seconds) {
        double[] known = seconds.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double fallback = known.length == 0 ? 1 : known[known.length / 2];
        Map<String, Double> out = new TreeMap<>();
        for (String b : batches) out.put(b, seconds.getOrDefault(b, fallback));
        return out;
    }

    /**
     * Раскладка партий по частям: самые долгие — первыми, каждая — в часть, где пока меньше всего времени (при
     * равенстве — в меньшую по номеру). Итог не зависит от порядка партий на входе: каждая машина считает его сама
     * и получает тот же.
     *
     * @param weights партия → время
     * @param count   сколько частей
     * @return партия → номер части от 0
     */
    static Map<String, Integer> assign(Map<String, Double> weights, int count) {
        List<String> names = new ArrayList<>(weights.keySet());
        names.sort(Comparator.comparingDouble((String b) -> weights.get(b)).reversed().thenComparing(Comparator.naturalOrder()));
        double[] load = new double[count];
        Map<String, Integer> plan = new HashMap<>();
        for (String b : names) {
            int min = 0;
            for (int i = 1; i < count; i++) if (load[i] < load[min]) min = i;
            load[min] += weights.get(b);
            plan.put(b, min);
        }
        return plan;
    }

    private static Map<String, Double> durations() {
        try (InputStream in = GameTestShards.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Airstrike.LOG.warn("GameTest: нет {} — части равняются по числу партий", RESOURCE);
                return Map.of();
            }
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, Double>>() {}.getType());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
