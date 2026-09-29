package ua.zentix.airstrike;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Переводы ru и en сходятся: те же ключи и те же аргументы в каждой строке (код передаёт одни аргументы на оба
 * языка — лишний «%s» показал бы сам шаблон, пропущенный — потерял бы число или клавишу). Клавиши в строки не
 * зашиваются: их можно переназначить, поэтому подсказка получает клавишу аргументом {@code Component.keybind}.
 */
class LangTest {
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?s");

    @Test
    void ruAndEnHaveSameKeysAndArguments() throws Exception {
        JsonObject ru = load("ru_ru"), en = load("en_us");
        assertEquals(new TreeSet<>(ru.keySet()), new TreeSet<>(en.keySet()), "ключи ru_ru и en_us");
        Map<String, String> diff = new TreeMap<>();
        for (String key : ru.keySet()) {
            var a = args(ru.get(key).getAsString());
            var b = args(en.get(key).getAsString());
            if (!a.equals(b)) diff.put(key, a + " ≠ " + b);
        }
        assertEquals(Map.of(), diff, "аргументы строк ru_ru и en_us");
    }

    @Test
    void cameraKeyIsNotHardcoded() throws Exception {
        for (String lang : new String[]{"ru_ru", "en_us"}) {
            for (var e : load(lang).entrySet()) {
                assertFalse(e.getValue().getAsString().matches(".*\\((?:V|N)[,)].*"), lang + " " + e.getKey());
            }
        }
    }

    /** Номера аргументов строки: «%s» идут по порядку, «%2$s» — явный номер. */
    private static TreeSet<Integer> args(String s) {
        TreeSet<Integer> out = new TreeSet<>();
        Matcher m = ARG.matcher(s.replace("%%", ""));
        int next = 1;
        while (m.find()) out.add(m.group(1) != null ? Integer.parseInt(m.group(1)) : next++);
        return out;
    }

    private static JsonObject load(String lang) throws Exception {
        String path = "/assets/airstrike/lang/" + lang + ".json";
        try (InputStream in = LangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
