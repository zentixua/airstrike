package ua.zentix.incoming.client.fx.layer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шейдеры мода — в том виде, который переживает Veil: его несёт Sable, и он пересобирает все шейдеры {@code core} своим
 * разбором GLSL (glsl-processor 0.2.3), печатая их заново. Условное выражение внутри другого он печатает без скобок:
 * {@code a * (c ? x : y)} выходит {@code a * c ? x : y} — другим выражением (свет стены пыли на пелене стал от плотности
 * не зависеть и выходил в тысячи раз ярче: белый кадр). Условие — только целой правой частью, возвратом или аргументом
 * вызова; внутри выражения — {@code if} или {@code mix}. Увеличение отдельной инструкцией ({@code n++;}) он печатает без
 * «;» — шейдер не собирается (ERROR «Failed to recompile vanilla shader», остаётся прежний): только {@code n += 1;}.
 */
class ShaderSourceTest {
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z_]\\w*|\\d+\\.?\\d*(?:[eE][-+]?\\d+)?|\\.\\d+(?:[eE][-+]?\\d+)?|[-+*/%<>=!&|^]=|&&|\\|\\||\\+\\+|--|\\S");
    /** Где кончается условное выражение слева: оно здесь целое. */
    private static final Set<String> WHOLE = Set.of(",", ";", "{", "}", "=", "+=", "-=", "*=", "/=", "return", "?", ":");

    /** Инструкция-увеличение: {@code n++;}, {@code --k;} ({@code i++} в заголовке {@code for} — без «;» следом). */
    private static final Pattern INCREMENT = Pattern.compile("(\\w\\+\\+|\\w--|\\+\\+\\w|--\\w)\\s*;");

    @Test
    void noConditionalInsideAnExpression() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::nested);
        assertTrue(bad.isEmpty(), "«?:» внутри выражения (Veil потеряет скобки): " + bad);
    }

    @Test
    void noIncrementStatement() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::increments);
        assertTrue(bad.isEmpty(), "«n++;» (Veil потеряет «;», пишите «n += 1;»): " + bad);
    }

    @Test
    void findsTheIncrementsVeilBreaks() {
        assertEquals(List.of(1, 3), increments("""
                n++;
                for (int i = 0; i < 4; i++) {
                    --k;
                    n += 1; // n++; в комментарии
                }
                """));
    }

    /**
     * Каждая переменная {@code uniform} программы {@code core} (её вершинный и пиксельный шейдер) объявлена в её JSON:
     * сэмплер — в {@code samplers}, остальное — в {@code uniforms}, и в JSON нет лишнего. Необъявленную ваниль не задаёт
     * и не привязывает: сэмплер читал бы чужую текстуру из своего блока, число — ноль (новая карта или число слоя,
     * добавленные в один проход и забытые в другом). Кроме {@code sampler3D}: их ваниль не привязывает, слой ставит их сам
     * ({@code PallPass}). Переменные файлов {@code include}, которые шейдер берёт {@code #moj_import}, — тоже его
     * ({@code pall_front.glsl}: пелена перед точкой квадратам слоя и пробам).
     */
    @Test
    void everyUniformIsBoundInItsProgram() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        Pattern uniform = Pattern.compile("(?m)^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;");
        List<String> bad = new ArrayList<>();
        int programs = 0;
        try (Stream<Path> list = Files.list(core)) {
            for (Path json : list.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                programs++;
                JsonObject program = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                Set<String> samplers = names(program, "samplers"), numbers = names(program, "uniforms"), used = new HashSet<>();
                String name = json.getFileName().toString();
                for (String[] stage : new String[][]{{"vertex", ".vsh"}, {"fragment", ".fsh"}}) {
                    String file = program.get(stage[0]).getAsString().replaceFirst("^incoming:", "") + stage[1];
                    Matcher m = uniform.matcher(blankComments(withImports(core.resolveSibling("include"), Files.readString(core.resolve(file)), new HashSet<>())));
                    while (m.find()) {
                        String type = m.group(1), u = m.group(2);
                        used.add(u);
                        if (type.equals("sampler3D")) continue;
                        if (!(type.startsWith("sampler") ? samplers : numbers).contains(u)) bad.add(name + ": " + file + " " + type + " " + u);
                    }
                }
                for (String u : samplers) if (!used.contains(u)) bad.add(name + ": лишний сэмплер " + u);
                for (String u : numbers) if (!used.contains(u)) bad.add(name + ": лишнее число " + u);
            }
        }
        assertTrue(programs > 5, "программ " + programs);
        assertTrue(bad.isEmpty(), "uniform без JSON или JSON без uniform: " + bad);
    }

    /** Текст шейдера с файлами {@code #moj_import <incoming:…>} на их местах (каждый раз — один, как у ванили). */
    private static String withImports(Path include, String source, Set<String> seen) throws IOException {
        Matcher m = Pattern.compile("(?m)^\\s*#moj_import\\s*<incoming:([\\w./]+)>").matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            m.appendReplacement(out, "");
            if (seen.add(name)) out.append(withImports(include, Files.readString(include.resolve(name)), seen));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Set<String> names(JsonObject program, String list) {
        Set<String> out = new HashSet<>();
        if (program.has(list)) for (JsonElement e : program.getAsJsonArray(list)) out.add(e.getAsJsonObject().get("name").getAsString());
        return out;
    }

    /**
     * Каждое тело функции закрыто до следующего объявления верхнего уровня (оно пишется с начала строки): незакрытое
     * тело ни драйвер, ни Veil не разбирают, а пелена без шейдера пропадает целиком (слияние потеряло «}» — кадры без
     * гриба, стены и пелены).
     */
    @Test
    void everyBodyIsClosed() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::unclosed);
        assertTrue(bad.isEmpty(), "объявление внутри незакрытого тела (или «{» без пары): " + bad);
    }

    @Test
    void findsAnUnclosedBody() {
        assertEquals(List.of(4), unclosed("""
                float f(float x) {
                    return x;

                float g;
                void main() {
                }
                """));
        assertEquals(List.of(4), unclosed("void main() {\n    if (a) {\n}\n"));
        assertEquals(List.of(), unclosed("struct S {\n    float a;\n};\nvoid main() {\n}\n"));
    }

    /** Строки объявлений верхнего уровня внутри тела, и последняя строка, если «{» осталась без пары. */
    private static List<Integer> unclosed(String source) {
        String[] lines = blankComments(source).split("\n", -1);
        List<Integer> found = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            if (depth > 0 && lines[i].matches("[A-Za-z_].*")) {
                found.add(i + 1);
                depth = 0;
            }
            for (char c : lines[i].toCharArray()) depth += c == '{' ? 1 : c == '}' ? -1 : 0;
        }
        if (depth != 0) found.add(lines.length);
        return found;
    }

    /** Строки всех шейдеров мода, которые нашла проверка, — «файл:строка». */
    private static List<String> everyShader(Function<String, List<Integer>> check) throws IOException, URISyntaxException {
        Path root = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders").toURI());
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().matches(".*\\.(fsh|vsh|glsl)")).toList()) {
                files++;
                for (int line : check.apply(Files.readString(p))) bad.add(root.relativize(p) + ":" + line);
            }
        }
        assertTrue(files > 5, "шейдеров " + files);
        return bad;
    }

    /** Строки с инструкцией-увеличением. */
    private static List<Integer> increments(String source) {
        String[] lines = blankComments(source).split("\n", -1);
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (INCREMENT.matcher(lines[i]).find()) found.add(i + 1);
        return found;
    }

    @Test
    void findsTheConditionalsVeilBreaks() {
        assertEquals(List.of(1, 2, 3, 8), nested("""
                wall += (a + b) * (burn > lit ? FIRE : H.rgb) * heat;
                float t = max((burn > lit ? x : y) + clump, 0.0);
                float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
                float d = depth >= 1.0 ? 1e9 : along(InvProj, ndc, depth * 2.0 - 1.0);
                float z = along(DhInvProj, ndc, DhDepth.y > 0.5 ? lod * 2.0 - 1.0 : lod);
                vec4 tex = n > 0.5 ? a : n < -0.5 ? b : c; // (x ? y : z) в комментарии
                return ok ? v : vec4(0.0);
                return(c ? a : b) * y;
                """));
    }

    /** Строки, где «?» стоит внутри скобок выражения (не вызова и не конструктора). */
    private static List<Integer> nested(String source) {
        String s = blankComments(source);
        List<String> tokens = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            tokens.add(m.group());
            at.add(m.start());
        }
        List<Integer> lines = new ArrayList<>();
        for (int q = 0; q < tokens.size(); q++) {
            if (!tokens.get(q).equals("?")) continue;
            int depth = 0, i = q - 1;
            for (; i >= 0; i--) {
                String x = tokens.get(i);
                if (x.equals(")") || x.equals("]")) {
                    depth++;
                } else if (x.equals("(") || x.equals("[")) {
                    if (depth == 0) break;
                    depth--;
                } else if (depth == 0 && WHOLE.contains(x)) {
                    break;
                }
            }
            boolean group = i >= 0 && tokens.get(i).equals("(")
                    && (i == 0 || tokens.get(i - 1).equals("return") || !tokens.get(i - 1).matches("[A-Za-z_]\\w*"));
            if (group) {
                int line = (int) s.substring(0, at.get(q)).chars().filter(c -> c == '\n').count() + 1;
                if (!lines.contains(line)) lines.add(line);
            }
        }
        return lines;
    }

    /** Комментарии и строки препроцессора — пробелами (строки остаются на месте). */
    private static String blankComments(String source) {
        StringBuilder b = new StringBuilder(source);
        Matcher m = Pattern.compile("//[^\n]*|/\\*.*?\\*/|(?m)^\\s*#[^\n]*", Pattern.DOTALL).matcher(source);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) if (b.charAt(i) != '\n') b.setCharAt(i, ' ');
        }
        return b.toString();
    }
}
