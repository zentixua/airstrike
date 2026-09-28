package ua.zentix.airstrike.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Звуки, собранные tools/build_sounds.py, сходятся с кодом: каждое событие ModSounds есть в sounds.json, каждый
 * файл есть и он моно (Minecraft размещает в пространстве только моно — стерео звучал бы «в голове»),
 * авторы записей лежат рядом со звуками.
 */
class SoundAssetsTest {
    private static final String ASSETS = "/assets/airstrike/";

    @Test
    void everyEventHasMonoFiles() throws IOException {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(open(ASSETS + "sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (var e : json.entrySet()) {
            var sounds = e.getValue().getAsJsonObject().getAsJsonArray("sounds");
            assertTrue(sounds.size() > 0, e.getKey());
            for (JsonElement s : sounds) {
                String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
                String file = ASSETS + "sounds/" + name.substring("airstrike:".length()) + ".ogg";
                try (InputStream in = open(file)) {
                    assertEquals(1, vorbisChannels(in.readNBytes(4096)), file + " должен быть моно");
                }
            }
        }
        Set<String> registered = new HashSet<>();
        Matcher m = Pattern.compile("register\\(\"([a-z0-9_.]+)\"\\)")
                .matcher(Files.readString(Path.of("src/main/java/ua/zentix/airstrike/registry/ModSounds.java")));
        while (m.find()) registered.add(m.group(1));
        assertTrue(registered.size() > 30);
        assertEquals(registered, json.keySet(), "события ModSounds и sounds.json");
        try (InputStream in = open(ASSETS + "sounds/credits.txt")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("freesound.org"));
        }
    }

    private static InputStream open(String path) {
        InputStream in = SoundAssetsTest.class.getResourceAsStream(path);
        assertNotNull(in, path);
        return in;
    }

    /** Число каналов из заголовка Vorbis: «\1vorbis», версия (4 байта), каналы (1 байт). */
    private static int vorbisChannels(byte[] head) {
        byte[] magic = "\u0001vorbis".getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + magic.length + 5 < head.length; i++) {
            for (int j = 0; j < magic.length; j++) if (head[i + j] != magic[j]) continue outer;
            return head[i + magic.length + 4];
        }
        return -1;
    }
}
