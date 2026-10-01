package ua.zentix.airstrike.gametest.scenario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Эталон траекторий ({@code scenario-baseline.json} в ресурсах devtest): у каждого сценария эталона — чем кончился
 * полёт, на каком тике, где (от середины площадки) и хеш положений снаряда по тикам. Сценарий сравнивает свой полёт
 * с эталоном точно: любое изменение полёта видно. Менять эталон — только в PR, где сдвиг объяснён по каждому
 * сценарию. Записать заново — {@code ./gradlew runScenarioRecord} (пишет сюда же, в исходники).
 */
final class Baseline {
    private static final String RESOURCE = "/scenario-baseline.json";
    /** Куда писать эталон (задача {@code runScenarioRecord}); без неё — сравнивать. */
    private static final String RECORD = System.getProperty("airstrike.scenario.record");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Итог полёта.
     *
     * @param end  чем кончился: {@code blast} — взрыв снаряда сценария, {@code entry} — бомба вошла в грунт,
     *             {@code gone} — снаряд убран без взрыва
     * @param tick тик от пуска
     * @param x    место от середины площадки, с точностью до 0,01 блока
     * @param hash хеш положений и фаз по тикам ({@link FlightTrace})
     */
    record Entry(String end, int tick, double x, double y, double z, String hash) {}

    private static final Map<String, Entry> EXPECTED = load();
    private static final Map<String, Entry> RECORDED = new TreeMap<>();

    private Baseline() {}

    static boolean recording() {
        return RECORD != null;
    }

    @Nullable
    static Entry expected(String id) {
        return EXPECTED.get(id);
    }

    /** Записать итог сценария (весь файл заново: какой тест кончится последним, заранее не знать). */
    static synchronized void record(String id, Entry entry) {
        RECORDED.put(id, entry);
        JsonObject root = new JsonObject();
        root.add("scenarios", GSON.toJsonTree(RECORDED));
        try {
            Files.writeString(Path.of(RECORD), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Entry> load() {
        Map<String, Entry> out = new TreeMap<>();
        try (InputStream in = Baseline.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Airstrike.LOG.warn("Эталона траекторий {} нет: сценарии эталона упадут — записать ./gradlew runScenarioRecord", RESOURCE);
                return out;
            }
            JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            root.getAsJsonObject("scenarios").entrySet().forEach(e -> out.put(e.getKey(), GSON.fromJson(e.getValue(), Entry.class)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
