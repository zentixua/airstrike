package ua.zentix.incoming.client.nuclear;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Функция GLSL из чисел в число: объявления {@code float a = …, b = …;}, {@code if (условие) return …;},
 * {@code return …;}; в выражениях — числа, переменные (у vec2 — {@code n.x}), + − * /, сравнения, встроенные
 * {@code smoothstep, clamp, min, max, exp, log2, pow, abs, sqrt, sign} и такие же функции того же файла.
 */
record GlslFunction(List<String> tokens, List<String> params, String source) {
    private static final Pattern TOKEN = Pattern.compile("\\s*(\\d+\\.?\\d*(?:[eE][-+]?\\d+)?|\\.\\d+|[A-Za-z_]\\w*(?:\\.[xyzw]+)?|<=|>=|==|[-+*/(),;{}<>=])");

    static GlslFunction parse(String source, String name) {
        Matcher m = Pattern.compile("float\\s+" + name + "\\s*\\(([^)]*)\\)\\s*\\{").matcher(source);
        if (!m.find()) throw new AssertionError("в шейдере нет float " + name);
        List<String> params = new ArrayList<>();
        for (String p : m.group(1).split(",")) {
            String[] w = p.strip().split("\\s+");
            if (!w[0].isEmpty()) params.add(w[w.length - 1]);
        }
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
        return new GlslFunction(tokens, params, source);
    }

    double call(Map<String, Double> args) {
        return new Run(tokens, new HashMap<>(args), source).body();
    }

    private static final class Run {
        private static final Set<String> BUILTIN = Set.of("smoothstep", "clamp", "min", "max", "exp", "log2", "pow", "abs", "sqrt", "sign");
        private final List<String> t;
        private final Map<String, Double> vars;
        private final String source;
        private int i;

        Run(List<String> tokens, Map<String, Double> vars, String source) {
            this.t = tokens;
            this.vars = vars;
            this.source = source;
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
                return function(w, a);
            }
            Double v = vars.get(w);
            if (v == null) throw new AssertionError("нет переменной " + w);
            return v;
        }

        /** Встроенная функция или функция того же файла (её параметры — по порядку). */
        private double function(String name, List<Double> a) {
            if (BUILTIN.contains(name)) return builtin(name, a);
            GlslFunction f = GlslFunction.parse(source, name);
            if (f.params.size() != a.size()) throw new AssertionError(name + ": параметров " + f.params.size() + ", а дано " + a.size());
            Map<String, Double> args = new HashMap<>();
            for (int k = 0; k < a.size(); k++) args.put(f.params.get(k), a.get(k));
            return f.call(args);
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
                case "log2" -> Math.log(a.get(0)) / Math.log(2);
                case "pow" -> Math.pow(a.get(0), a.get(1));
                case "abs" -> Math.abs(a.get(0));
                case "sqrt" -> Math.sqrt(a.get(0));
                case "sign" -> Math.signum(a.get(0));
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
