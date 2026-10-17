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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(MushroomField.LOBES_COLUMN, c.get("MUSH_COLUMN_LOBES"), 0);
        assertEquals(MushroomField.EDGE_COLUMN, c.get("MUSH_COLUMN_EDGE"), 0);
        assertEquals(MushroomField.BLEND, c.get("MUSH_BLEND"), 0);
        assertEquals(MushroomModel.SKIN, c.get("MUSH_SKIN"), 1e-12);
        assertEquals(MushroomField.REACH_CAP, c.get("MUSH_CAP_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_STEM, c.get("MUSH_STEM_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_SKIRT, c.get("MUSH_SKIRT_REACH"), 1e-12);
        assertEquals(MushroomField.REACH_COLUMN, c.get("MUSH_COLUMN_REACH"), 1e-12);
        GlslFunction edge = GlslFunction.parse(GLSL, "mushEdge");
        // у шапки и столба рамка берёт ещё четверть сглаживания объединения кольца и купола, стенки и короны: мягкий
        // минимум опускает край
        double[][] parts = {{MushroomField.LOBES_CAP, MushroomField.EDGE_CAP, MushroomField.REACH_CAP - MushroomField.BLEND / 4},
                {MushroomField.LOBES_STEM, MushroomField.EDGE_STEM, MushroomField.REACH_STEM},
                {MushroomField.LOBES_SKIRT, MushroomField.EDGE_SKIRT, MushroomField.REACH_SKIRT},
                {MushroomField.LOBES_COLUMN, MushroomField.EDGE_COLUMN, MushroomField.REACH_COLUMN - MushroomField.BLEND / 4}};
        for (double[] p : parts) {
            double lobes = MushroomField.LOBES_MAX * p[0];
            assertEquals(0, edge.call(Map.of("d", p[2] - lobes, "e", p[1])), 1e-12);
            assertEquals(1, edge.call(Map.of("d", -p[1], "e", p[1])), 1e-12);
        }
    }

    /**
     * Кипение шапки ({@code mushCapLobes}): два слоя узора со сдвигом на полжизни, доли в сумме — 1. Узор слоя сменяется
     * только там, где слоя нет (доля 0), а слой без доли весь ниже самого низкого места живого: смена не видна. Жизнь
     * слоя — подъём и уход клуба в полсечения, по Колмогорову (время оборота вихря ∝ размер^⅔). Поправка на среднее —
     * среднее верхнего из двух слоёв (клубы ±1/2, равномерно) с их сдвигами со знаком минус: шапка за жизнь слоя в
     * среднем не садится и не растёт, а верх клубов не выше {@link MushroomField#LOBES_MAX} (по нему рамка гриба).
     * Обращение у оси гриба сходит на нет, у оси кольца — полное.
     */
    @Test
    void capBoilsWithoutJumps() {
        Map<String, Double> c = constants();
        assertEquals(MushroomModel.RING, c.get("MUSH_RING"), 0);
        double sink = c.get("MUSH_SINK"), cycle = c.get("MUSH_CYCLE");
        double top = c.get("MUSH_LOBES_TOP"), deep = c.get("MUSH_LOBES_DEEP");
        assertEquals(2 * Math.pow(0.5, 2.0 / 3), cycle, 0.005);
        assertEquals(0.5 + 0.35 * 0.5, top, 1e-12, "верх клубов одного слоя (mushLobes)");
        assertTrue(top <= MushroomField.LOBES_MAX);
        assertTrue(top - sink <= -top, "уходящий слой выступает над живым");
        GlslFunction age = GlslFunction.parse(GLSL, "mushLayerAge"), weight = GlslFunction.parse(GLSL, "mushLayerWeight");
        GlslFunction seed = GlslFunction.parse(GLSL, "mushLayerSeed"), rise = GlslFunction.parse(GLSL, "mushRise");
        GlslFunction taper = GlslFunction.parse(GLSL, "mushTaper");
        double[] lastSeed = {-1, -1}, lastW = {0, 0};
        for (double psi = 0; psi < 30; psi += 1e-3) {
            double sum = 0;
            for (int j = 0; j < 2; j++) {
                Map<String, Double> a = new HashMap<>(c);
                a.put("churn", psi);
                a.put("j", (double) j);
                double w = weight.call(Map.of("age", age.call(a))), s = seed.call(a);
                if (psi > 0 && s != lastSeed[j]) {
                    assertTrue(w < 0.01 && lastW[j] < 0.01, "слой " + j + " сменил узор на виду: " + psi + " рад");
                }
                lastSeed[j] = s;
                lastW[j] = w;
                sum += w;
            }
            assertEquals(1, sum, 1e-9, psi + " рад");
        }
        int n = 400;
        for (double low = 0; low <= 0.5 + 1e-9; low += 0.05) {
            double mean = 0;
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) {
                    double x = (i + 0.5) / n - 0.5, y = (k + 0.5) / n - 0.5;
                    mean += Math.max(x - low * sink, y - (1 - low) * sink);
                }
            }
            mean /= (double) n * n;
            Map<String, Double> a = new HashMap<>(c);
            a.put("low", low);
            double r = rise.call(a);
            assertEquals(0, mean + r, 1e-3, "доля " + low);
            // верх: верхний клуб сильного слоя с поправкой — не выше рамки гриба; низ: нижний клуб сильного слоя с поправкой
            assertTrue(top - low * sink + r <= MushroomField.LOBES_MAX + 1e-12, "доля " + low);
            assertTrue(-top - low * sink + r >= -deep - 1e-12, "доля " + low + ": ложбина глубже MUSH_LOBES_DEEP");
        }
        assertEquals(0, taper.call(Map.of("rho", 0.0, "ringR", 300.0)), 0);
        assertEquals(1, taper.call(Map.of("rho", 300.0, "ringR", 300.0)), 0);
        assertEquals(1, taper.call(Map.of("rho", 900.0, "ringR", 300.0)), 0);
    }

    /**
     * Обращение шапки ({@code mushFlow}): вещество, что сейчас на верху сечения кольца, пришло от оси гриба, снаружи —
     * сверху, внутри — снизу, внизу — снаружи (вихрь Тёрнера: внутри вверх, по верху наружу, снаружи вниз). Ядро сечения
     * обращается целиком, снаружи — как 1/r² (вихрь Рэнкина): у края сечения без скачка.
     */
    @Test
    void capRollsUpTheMiddleAndDownTheOutside() {
        GlslFunction wasU = GlslFunction.parse(GLSL, "mushWasU"), wasV = GlslFunction.parse(GLSL, "mushWasV");
        GlslFunction swirl = GlslFunction.parse(GLSL, "mushSwirl");
        double ang = 0.3;
        // {u, v} сейчас → откуда: знак сдвига по u и по v
        double[][] cases = {{0, 1, -1, 0}, {1, 0, 0, 1}, {-1, 0, 0, -1}, {0, -1, 1, 0}};
        for (double[] k : cases) {
            Map<String, Double> a = Map.of("u", k[0], "v", k[1], "ang", ang);
            double du = wasU.call(a) - k[0], dv = wasV.call(a) - k[1];
            if (k[2] != 0) assertEquals(k[2], Math.signum(du), 0, "u = " + k[0] + ", v = " + k[1]);
            if (k[3] != 0) assertEquals(k[3], Math.signum(dv), 0, "u = " + k[0] + ", v = " + k[1]);
            assertEquals(1, Math.hypot(wasU.call(a), wasV.call(a)), 1e-12, "по эллипсу сечения");
        }
        assertEquals(1, swirl.call(Map.of("u", 0.3, "v", -0.4)), 0);
        assertEquals(1, swirl.call(Map.of("u", 0.6, "v", 0.8)), 1e-12);
        assertEquals(0.25, swirl.call(Map.of("u", 0.0, "v", 2.0)), 1e-12);
    }
}
