package ua.zentix.airstrike.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Список команд сценария {@code commands} ({@code airstrike.commands}): пункты через «;». Точка с запятой внутри
 * скобок и кавычек — часть команды, а не разделитель: в NBT она разделяет тип и элементы массива
 * ({@code UUID:[I;1,2,3,4]}), в строке — просто знак. Двойная кавычка открывает строку везде, одинарная — только
 * там, где SNBT ждёт значение или ключ (после {@code { [ , :}): апостроф в тексте ({@code say it's}) — просто знак.
 */
public final class ScenarioCommands {
    private ScenarioCommands() {}

    /** Пункты по «;» верхнего уровня, без пробелов по краям и без пустых. */
    public static List<String> split(String spec) {
        List<String> out = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < spec.length()) {
                    item.append(c).append(spec.charAt(++i));
                    continue;
                }
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'' && opensValue(item)) {
                quote = c;
            } else if (c == '[' || c == '{' || c == '(') {
                depth++;
            } else if ((c == ']' || c == '}' || c == ')') && depth > 0) {
                depth--;
            } else if (c == ';' && depth == 0) {
                add(out, item);
                continue;
            }
            item.append(c);
        }
        add(out, item);
        return out;
    }

    /** Последний непробельный знак пункта — начало значения или ключа SNBT. */
    private static boolean opensValue(StringBuilder item) {
        for (int i = item.length() - 1; i >= 0; i--) {
            char c = item.charAt(i);
            if (!Character.isWhitespace(c)) return c == '{' || c == '[' || c == ',' || c == ':';
        }
        return false;
    }

    private static void add(List<String> out, StringBuilder item) {
        String s = item.toString().strip();
        if (!s.isEmpty()) out.add(s);
        item.setLength(0);
    }
}
