package ua.zentix.airstrike.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Огонь кадров шара ({@code fireball.json} из {@code tools/gen_particles.py}): им ограничен свет шара вокруг. */
class FxAtlasTest {
    @Test
    void fireOfFramesFadesFromFlashToSoot() throws IOException {
        float[] fire;
        try (InputStream in = FxAtlasTest.class.getResourceAsStream("/assets/airstrike/textures/fx/particle/fireball.json")) {
            assertNotNull(in, "fireball.json");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                fire = FxAtlas.parseFire(r);
            }
        }
        assertEquals(FxAtlas.FIREBALL_FRAMES, fire.length);
        assertTrue(fire[0] >= 1, "вспышка — белый на экране: свет вокруг не ограничен");
        for (int i = 1; i < fire.length; i++) assertTrue(fire[i] <= fire[i - 1], "остывает: кадр " + i);
        // с 13-го кадра (0,78 жизни) шар — сажа: ни пыль под ним, ни небо над ним он больше не освещает
        for (int i = 12; i < fire.length; i++) assertTrue(fire[i] < 0.01, "кадр " + i + ": " + fire[i]);
    }
}
