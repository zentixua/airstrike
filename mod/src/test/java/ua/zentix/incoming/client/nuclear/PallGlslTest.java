package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.VolumeNoise;
import ua.zentix.incoming.nuclear.Detonation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Законы пелены в шейдере ({@code shaders/include/pall.glsl}) — те же, что в Java ({@link PallField},
 * {@link VolumeNoise#shape}): функции шейдера читаются из файла и считаются маленьким разбором выражений GLSL, числа
 * сравниваются с Java по всей области. Правка закона только в одном месте ломает этот тест.
 */
class PallGlslTest {
    private static final String GLSL = read("include/pall.glsl");
    /** 15 кт у земли, масштаб 1, стена пыли вдали — 0,42 скорости звука. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);

    private static String read(String path) {
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void sceneDistanceKeepsOnlyTheWorldInFront() {
        // расстояние до мира за пикселем (SceneDepth): негодное — NaN, бесконечность, за глазом (чужая глубина LOD) —
        // как небо, 1e9: иначе луч пелены шёл бы сквозь NaN, и пиксель кадра выходил чёрным
        Function f = Function.parse(GLSL, "pallScene");
        for (double d : new double[] {0, 0.5, 64, 3000, 9.9e7}) assertEquals(d, f.call(Map.of("d", d)), 0, "d = " + d);
        for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, -5000, 1e8, 1e9, 3e38}) {
            assertEquals(1e9, f.call(Map.of("d", d)), 0, "d = " + d);
        }
    }

    @Test
    void profileMatchesJava() {
        Function f = Function.parse(GLSL, "pallProfile");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.profile(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void columnMatchesJava() {
        Function f = Function.parse(GLSL, "pallColumn");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.column(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void edgeMatchesJava() {
        Function f = Function.parse(GLSL, "pallEdge");
        for (double u = 0; u <= 1.2; u += 0.002) assertEquals(PallField.edge(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
    }

    @Test
    void shapeMatchesJava() {
        Function f = Function.parse(GLSL, "pallShape");
        for (double r = 0; r <= 1; r += 0.05) {
            for (double g = 0; g <= 1; g += 0.05) {
                for (double fill = 0; fill <= 1; fill += 0.1) {
                    assertEquals(VolumeNoise.shape(r, g, fill), f.call(Map.of("n.x", r, "n.y", g, "fill", fill)), 1e-9,
                            "r = " + r + ", g = " + g + ", fill = " + fill);
                }
            }
        }
    }

    @Test
    void litMatchesJava() {
        Function f = Function.parse(GLSL, "pallLit");
        for (double r = 0; r <= 4000; r += 50) {
            for (double up = 0; up <= 1; up += 0.25) {
                for (double overhead : new double[]{0, 0.5, 1}) {
                    assertEquals(CloudPuffs.lit(r, up, overhead, 1560), f.call(Map.of("r", r, "up", up, "overhead", overhead, "lightR", 1560.0)), 1e-9,
                            "r = " + r + ", up = " + up + ", overhead = " + overhead);
                }
            }
        }
    }

    @Test
    void burnMatchesJava() {
        Function f = Function.parse(GLSL, "pallBurn");
        for (double r = 0; r <= 4000; r += 25) {
            for (double up = 0; up <= 1; up += 0.25) {
                for (double fireR : new double[]{0, 1900}) {
                    assertEquals(CloudPuffs.burn(r, up, 0.8, fireR), f.call(Map.of("r", r, "up", up, "glow", 0.8, "fireR", fireR)), 1e-9,
                            "r = " + r + ", up = " + up + ", fireR = " + fireR);
                }
            }
        }
    }

    /**
     * Пелена за фронтом продолжает стену пыли ночью: свет тонкой пыли за головой стены по законам шейдера (свет стены
     * {@code pallLit}, {@code pallBurn} с числами {@link CloudPuffs#pallLight}, сила {@link CloudPuffs#glowOnSmoke}, свет
     * неба на пыли {@code DUST} из {@code fx_pall.fsh} — не темнее {@link NukePall#nightSky}) — тот же, что у клуба стены там
     * же ({@link CloudPuffs#wallColor}), и не тёмный: у эпицентра пыль в отсвете шара и пожаров. Своё зарево пожаров
     * пелены ({@code PallFireLight}) разгорается позже, с минуту, — его здесь нет.
     */
    @Test
    void pallBehindTheFrontTakesTheWallLight() {
        Function lit = Function.parse(GLSL, "pallLit"), burn = Function.parse(GLSL, "pallBurn");
        double[] dust = vec3(read("core/fx_pall.fsh"), "DUST"), fire = vec3(read("core/fx_pall.fsh"), "FIRE");
        CloudPuffs puffs = new CloudPuffs(GROUND, 600, false);
        float ambient = 0.17f;
        double k = CloudPuffs.glowOnSmoke(ambient), sky = NukePall.nightSky(ambient, 1);
        float[] light = new float[8], wall = new float[3];
        for (double t : new double[]{2, 6, 10, 20, 40}) {
            assertTrue(puffs.pallLight(t, light));
            double head = puffs.wall(t).head();
            for (double share : new double[]{0.2, 0.5, 0.8, 0.97}) {
                double r = head * share;
                for (double up : new double[]{0.05, 0.3, 0.6, 0.9}) {
                    double a = lit.call(Map.of("r", r, "up", up, "overhead", (double) light[0], "lightR", (double) light[2]));
                    double b = burn.call(Map.of("r", r, "up", up, "glow", (double) light[1], "fireR", (double) light[3]));
                    assertTrue(puffs.wallColor(t, r, up, 1, ambient, wall));
                    double sum = 0;
                    for (int c = 0; c < 3; c++) {
                        double hot = b > a ? fire[c] : light[4 + c];
                        double pall = Math.min(1, dust[c] * sky + hot * Math.max(a, b) * k);
                        sum += pall;
                        assertEquals(wall[c], pall, 0.06, t + " с, " + r + " блоков, высота " + up + ", канал " + c);
                    }
                    if (r < 1000) assertTrue(sum / 3 > 0.25, t + " с, " + r + " блоков: пелена у эпицентра тёмная, " + sum / 3);
                }
            }
        }
    }

    /** Константа {@code const vec3 name = vec3(a, b, c);} шейдера. */
    private static double[] vec3(String source, String name) {
        Matcher m = Pattern.compile("const\\s+vec3\\s+" + name + "\\s*=\\s*vec3\\(([^)]*)\\)").matcher(source);
        if (!m.find()) throw new AssertionError("нет const vec3 " + name);
        String[] p = m.group(1).split(",");
        return new double[]{Double.parseDouble(p[0].strip()), Double.parseDouble(p[1].strip()), Double.parseDouble(p[2].strip())};
    }

    /**
     * Функция GLSL из чисел в число: объявления {@code float a = …, b = …;}, {@code if (условие) return …;},
     * {@code return …;}; в выражениях — числа, переменные (у vec2 — {@code n.x}), + − * /, сравнения и встроенные
     * {@code smoothstep, clamp, min, max, exp, pow, abs, sqrt}.
     */
    private record Function(List<String> tokens) {
        private static final Pattern TOKEN = Pattern.compile("\\s*(\\d+\\.?\\d*(?:[eE][-+]?\\d+)?|\\.\\d+|[A-Za-z_]\\w*(?:\\.[xyzw]+)?|<=|>=|==|[-+*/(),;{}<>=])");

        static Function parse(String source, String name) {
            Matcher m = Pattern.compile("float\\s+" + name + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
            if (!m.find()) throw new AssertionError("в pall.glsl нет float " + name);
            int depth = 1, i = m.end();
            for (; depth > 0; i++) {
                if (source.charAt(i) == '{') depth++;
                if (source.charAt(i) == '}') depth--;
            }
            String body = source.substring(m.end(), i - 1);
            List<String> tokens = new ArrayList<>();
            Matcher t = TOKEN.matcher(body);
            int at = 0;
            while (at < body.length() && t.find(at) && t.start() == at) {
                tokens.add(t.group(1));
                at = t.end();
            }
            if (!body.substring(at).isBlank()) throw new AssertionError(name + ": не разобрать «" + body.substring(at).trim() + "»");
            return new Function(tokens);
        }

        double call(Map<String, Double> args) {
            return new Run(tokens, new HashMap<>(args)).body();
        }
    }

    private static final class Run {
        private final List<String> t;
        private final Map<String, Double> vars;
        private int i;

        Run(List<String> tokens, Map<String, Double> vars) {
            this.t = tokens;
            this.vars = vars;
        }

        double body() {
            while (i < t.size()) {
                String w = next();
                switch (w) {
                    case "float" -> {
                        do {
                            String name = next();
                            expect("=");
                            vars.put(name, expr());
                        } while (take(","));
                        expect(";");
                    }
                    case "if" -> {
                        expect("(");
                        boolean c = condition();
                        expect(")");
                        expect("return");
                        double v = expr();
                        expect(";");
                        if (c) return v;
                    }
                    case "return" -> {
                        double v = expr();
                        expect(";");
                        return v;
                    }
                    default -> throw new AssertionError("не знаю инструкцию " + w);
                }
            }
            throw new AssertionError("нет return");
        }

        private boolean condition() {
            double a = expr();
            String op = next();
            double b = expr();
            return switch (op) {
                case "<" -> a < b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                case ">=" -> a >= b;
                case "==" -> a == b;
                default -> throw new AssertionError("не знаю сравнение " + op);
            };
        }

        private double expr() {
            double v = term();
            while (i < t.size() && (t.get(i).equals("+") || t.get(i).equals("-"))) v = next().equals("+") ? v + term() : v - term();
            return v;
        }

        private double term() {
            double v = unary();
            while (i < t.size() && (t.get(i).equals("*") || t.get(i).equals("/"))) v = next().equals("*") ? v * unary() : v / unary();
            return v;
        }

        private double unary() {
            if (take("-")) return -unary();
            if (take("(")) {
                double v = expr();
                expect(")");
                return v;
            }
            String w = next();
            if (Character.isDigit(w.charAt(0)) || w.charAt(0) == '.') return Double.parseDouble(w);
            if (take("(")) {
                List<Double> a = new ArrayList<>();
                if (!take(")")) {
                    do a.add(expr());
                    while (take(","));
                    expect(")");
                }
                return builtin(w, a);
            }
            Double v = vars.get(w);
            if (v == null) throw new AssertionError("нет переменной " + w);
            return v;
        }

        private static double builtin(String name, List<Double> a) {
            return switch (name) {
                case "smoothstep" -> {
                    double f = Math.max(0, Math.min(1, (a.get(2) - a.get(0)) / (a.get(1) - a.get(0))));
                    yield f * f * (3 - 2 * f);
                }
                case "clamp" -> Math.max(a.get(1), Math.min(a.get(2), a.get(0)));
                case "min" -> Math.min(a.get(0), a.get(1));
                case "max" -> Math.max(a.get(0), a.get(1));
                case "exp" -> Math.exp(a.get(0));
                case "pow" -> Math.pow(a.get(0), a.get(1));
                case "abs" -> Math.abs(a.get(0));
                case "sqrt" -> Math.sqrt(a.get(0));
                default -> throw new AssertionError("не знаю функцию " + name);
            };
        }

        private String next() {
            if (i >= t.size()) throw new AssertionError("конец функции раньше времени");
            return t.get(i++);
        }

        private boolean take(String w) {
            if (i < t.size() && t.get(i).equals(w)) {
                i++;
                return true;
            }
            return false;
        }

        private void expect(String w) {
            String got = next();
            if (!got.equals(w)) throw new AssertionError("ждал «" + w + "», а там «" + got + "»");
        }
    }
}
