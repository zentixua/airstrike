package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * вызова; внутри выражения — {@code if} или {@code mix}.
 */
class ShaderSourceTest {
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z_]\\w*|\\d+\\.?\\d*(?:[eE][-+]?\\d+)?|\\.\\d+(?:[eE][-+]?\\d+)?|[-+*/%<>=!&|^]=|&&|\\|\\||\\+\\+|--|\\S");
    /** Где кончается условное выражение слева: оно здесь целое. */
    private static final Set<String> WHOLE = Set.of(",", ";", "{", "}", "=", "+=", "-=", "*=", "/=", "return", "?", ":");

    @Test
    void noConditionalInsideAnExpression() throws IOException, URISyntaxException {
        Path root = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders").toURI());
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().matches(".*\\.(fsh|vsh|glsl)")).toList()) {
                files++;
                for (int line : nested(Files.readString(p))) bad.add(root.relativize(p) + ":" + line);
            }
        }
        assertTrue(files > 5, "шейдеров " + files);
        assertTrue(bad.isEmpty(), "«?:» внутри выражения (Veil потеряет скобки): " + bad);
    }

    @Test
    void findsTheConditionalsVeilBreaks() {
        assertEquals(List.of(1, 2, 3), nested("""
                wall += (a + b) * (burn > lit ? FIRE : H.rgb) * heat;
                float t = max((burn > lit ? x : y) + clump, 0.0);
                float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
                float d = depth >= 1.0 ? 1e9 : along(InvProj, ndc, depth * 2.0 - 1.0);
                float z = along(DhInvProj, ndc, DhDepth.y > 0.5 ? lod * 2.0 - 1.0 : lod);
                vec4 tex = n > 0.5 ? a : n < -0.5 ? b : c; // (x ? y : z) в комментарии
                return ok ? v : vec4(0.0);
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
            boolean group = i >= 0 && tokens.get(i).equals("(") && (i == 0 || !tokens.get(i - 1).matches("[A-Za-z_]\\w*"));
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
