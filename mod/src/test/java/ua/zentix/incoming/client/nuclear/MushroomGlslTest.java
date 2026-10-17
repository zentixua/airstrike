package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Законы гриба в шейдере ({@code shaders/include/mushroom.glsl}) — те же, что в Java ({@link MushroomModel},
 * {@link MushroomField}): функции и константы шейдера читаются из файла и считаются разбором выражений GLSL
 * ({@link GlslFunction}). Правка закона только в одном месте ломает этот тест.
 */
class MushroomGlslTest {
    private static final String GLSL = read("include/mushroom.glsl");

    private static String read(String path) {
        try (InputStream in = MushroomGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Все числовые константы шейдера ({@code const float} и {@code const int}), выражения — по уже прочитанным. */
    private static Map<String, Double> constants() {
        Map<String, Double> out = new HashMap<>();
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(GLSL);
        while (m.find()) {
            GlslFunction f = GlslFunction.parse("float c() { return " + m.group(2) + "; }", "c");
            out.put(m.group(1), f.call(out));
        }
        return out;
    }

    @Test
    void stemRadiusMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "mushStemR");
        for (double dust : new double[]{0, 0.4, 1}) {
            for (double w = -0.2; w <= 1.2; w += 0.01) {
                assertEquals(MushroomModel.stemRadius(w, 37, dust), f.call(Map.of("w", w, "stemR", 37.0, "lowDust", dust)), 1e-9,
                        "w = " + w + ", пыль " + dust);
            }
        }
    }

    @Test
    void driftShareMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "mushShare");
        for (double end : new double[]{0.5, 300, 2500}) {
            for (double h = -100; h <= 4000; h += 7) {
                assertEquals(MushroomModel.driftShare(h, end), f.call(Map.of("h", h, "endTop", end)), 1e-9, "h = " + h);
            }
        }
    }

    /** Строка света чёрного тела: те же узлы, температура узла i — место i в строке. */
    @Test
    void blackBodyNodesMatchJava() {
        Map<String, Double> c = constants();
        assertEquals(MushroomField.BB_NODES, c.get("MUSH_BB_NODES"), 0);
        assertEquals(MushroomField.BB_FROM, c.get("MUSH_BB_FROM"), 0);
        assertEquals(MushroomField.BB_TO, c.get("MUSH_BB_TO"), 0);
        assertEquals(MushroomField.BB_ROW, c.get("MUSH_BB_ROW"), 0);
        assertEquals(MushroomField.MUSHROOMS, c.get("MUSHROOMS"), 0);
        GlslFunction u = GlslFunction.parse(GLSL, "mushBbU");
        for (int i = 0; i < MushroomField.BB_NODES; i++) {
            Map<String, Double> args = new HashMap<>(c);
            args.put("kelvin", MushroomField.nodeKelvin(i));
            assertEquals(i, u.call(args) * (MushroomField.BB_NODES - 1), 1e-6, "узел " + i);
        }
    }

    /** Таблица грибов: шейдер читает тексели 0..TEXELS−1 строки гриба. */
    @Test
    void tableLayoutMatchesJava() {
        Matcher m = Pattern.compile("ivec2\\((\\d+), k\\)").matcher(GLSL);
        int max = -1;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        assertEquals(MushroomField.TEXELS - 1, max);
    }

    /**
     * Клубы и край — те же числа, что у рамки гриба в Java; на расстоянии, до которого рамка берёт вещество (в единицах
     * клубов части), край, сдвинутый наружу самым сильным клубом, уже не даёт вещества, а глубже края, сдвинутого внутрь
     * так же сильно, — вся густота.
     */
    @Test
    void edgeLawsMatchJava() {
        Map<String, Double> c = constants();
        assertEquals(MushroomField.LOBES_CAP, c.get("MUSH_CAP_LOBES"), 0);
        assertEquals(MushroomField.LOBES_STEM, c.get("MUSH_STEM_LOBES"), 0);
        assertEquals(MushroomField.LOBES_SKIRT, c.get("MUSH_SKIRT_LOBES"), 0);
        assertEquals(MushroomField.EDGE_CAP, c.get("MUSH_CAP_EDGE"), 0);
        assertEquals(MushroomField.EDGE_STEM, c.get("MUSH_STEM_EDGE"), 0);
        assertEquals(MushroomField.EDGE_SKIRT, c.get("MUSH_SKIRT_EDGE"), 0);
        assertEquals(MushroomField.BLEND, c.get("MUSH_BLEND"), 0);
        assertEquals(MushroomModel.EDGE_HEAT, c.get("MUSH_EDGE_HEAT"), 0);
        assertEquals(MushroomField.REACH_CAP, c.get("MUSH_CAP_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_STEM, c.get("MUSH_STEM_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_SKIRT, c.get("MUSH_SKIRT_REACH"), 1e-12);
        GlslFunction edge = GlslFunction.parse(GLSL, "mushEdge");
        // у шапки рамка берёт ещё четверть сглаживания объединения кольца и купола: мягкий минимум опускает край
        double[][] parts = {{MushroomField.LOBES_CAP, MushroomField.EDGE_CAP, MushroomField.REACH_CAP - MushroomField.BLEND / 4},
                {MushroomField.LOBES_STEM, MushroomField.EDGE_STEM, MushroomField.REACH_STEM},
                {MushroomField.LOBES_SKIRT, MushroomField.EDGE_SKIRT, MushroomField.REACH_SKIRT}};
        for (double[] p : parts) {
            double lobes = MushroomField.LOBES_MAX * p[0];
            assertEquals(0, edge.call(Map.of("d", p[2] - lobes, "e", p[1])), 1e-12);
            assertEquals(1, edge.call(Map.of("d", -p[1], "e", p[1])), 1e-12);
        }
    }
}
