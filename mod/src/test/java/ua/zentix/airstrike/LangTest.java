package ua.zentix.airstrike;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Переводы ru и en сходятся: те же ключи и те же аргументы в каждой строке (код передаёт одни аргументы на оба
 * языка — лишний «%s» показал бы сам шаблон, пропущенный — потерял бы число или клавишу). Клавиши в строки не
 * зашиваются: их можно переназначить, поэтому подсказка получает клавишу аргументом {@code Component.keybind}.
 */
class LangTest {
    /** Как {@code TranslatableContents.FORMAT_PATTERN}: ванильный разбор строки перевода. */
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

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

    /** Ваниль знает только «%s», «%2$s» и «%%»: на «%d» или «%» в конце строки вместо текста покажется ключ. */
    @Test
    void onlyStringArguments() throws Exception {
        for (String lang : new String[]{"ru_ru", "en_us"}) {
            for (var e : load(lang).entrySet()) {
                Matcher m = ARG.matcher(e.getValue().getAsString());
                while (m.find()) {
                    assertTrue(m.group(2).equals("s") || m.group(2).equals("%"), lang + " " + e.getKey() + ": «" + m.group() + "»");
                }
            }
        }
    }

    /**
     * У каждого типа урона мода есть все три сообщения о смерти, которые может выбрать ваниль
     * ({@code DamageSource.getLocalizedDeathMessage}): основное, «.player» (без сущностей, но с тем, кто последним
     * бил жертву) и «.item» (виновник держит переименованный предмет). Без ключа в чате был бы сам ключ.
     */
    @Test
    void everyDamageTypeHasAllDeathMessages() throws Exception {
        URL dir = LangTest.class.getResource("/data/airstrike/damage_type");
        assertNotNull(dir, "data/airstrike/damage_type");
        List<String> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                ids.add(JsonParser.parseString(Files.readString(f)).getAsJsonObject().get("message_id").getAsString());
            }
        }
        assertFalse(ids.isEmpty(), "типов урона нет");
        for (String lang : new String[]{"ru_ru", "en_us"}) {
            JsonObject strings = load(lang);
            for (String id : ids) {
                for (String suffix : new String[]{"", ".player", ".item"}) {
                    assertTrue(strings.has("death.attack." + id + suffix), lang + ": нет death.attack." + id + suffix);
                }
                // «.player» — только при убийце, «.item» — при виновнике с предметом: их аргументы должны быть в строке
                assertTrue(args(strings.get("death.attack." + id + ".player").getAsString()).contains(2), lang + " " + id + ".player без убийцы");
                assertTrue(args(strings.get("death.attack." + id + ".item").getAsString()).contains(3), lang + " " + id + ".item без предмета");
            }
        }
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
        Matcher m = ARG.matcher(s);
        int next = 1;
        while (m.find()) if (!m.group(2).equals("%")) out.add(m.group(1) != null ? Integer.parseInt(m.group(1)) : next++);
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
