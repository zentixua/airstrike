package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.VolumeNoise;

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

/**
 * Законы пелены в шейдере ({@code shaders/include/pall.glsl}) — те же, что в Java ({@link PallField},
 * {@link VolumeNoise#shape}): функции шейдера читаются из файла и считаются маленьким разбором выражений GLSL, числа
 * сравниваются с Java по всей области. Правка закона только в одном месте ломает этот тест.
 */
class PallGlslTest {
    private static final String GLSL = read();

    private static String read() {
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/shaders/include/pall.glsl")) {
            assertNotNull(in, "pall.glsl");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
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
