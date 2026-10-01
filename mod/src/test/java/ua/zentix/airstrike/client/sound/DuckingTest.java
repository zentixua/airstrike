package ua.zentix.airstrike.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Моторы под громким взрывом: насколько уходят вниз, сколько держатся, как возвращаются. */
class DuckingTest {
    @Test
    void distantBlastLeavesEnginesAlone() {
        Ducking d = new Ducking();
        d.blast(BlastMix.loudness(BlastMix.DRONE, 600));
        assertEquals(1, d.factor(), 1e-6);
    }

    @Test
    void closeBlastDucksFullyThenReleasesInAboutASecondAndAHalf() {
        Ducking d = new Ducking();
        d.blast(BlastMix.NEAR_LUFS);
        assertEquals(Math.pow(10, -Ducking.DEPTH / 20), d.factor(), 1e-6);
        for (int i = 0; i < Ducking.HOLD; i++) d.tick();
        assertEquals(Math.pow(10, -Ducking.DEPTH / 20), d.factor(), 1e-6, "держится");
        float prev = d.factor();
        int ticks = Ducking.HOLD;
        while (d.factor() < 1) {
            d.tick();
            ticks++;
            assertTrue(d.factor() >= prev, "только возвращается");
            assertTrue(20 * Math.log10(d.factor() / prev) <= Ducking.RELEASE + 1e-6, "без скачка");
            prev = d.factor();
        }
        assertTrue(ticks >= 25 && ticks <= 40, "вернулся за " + ticks + " тиков");
    }

    @Test
    void quieterBlastDoesNotExtendALouderOne() {
        Ducking d = new Ducking();
        d.blast(BlastMix.NEAR_LUFS);
        for (int i = 0; i < 10; i++) d.tick();
        float before = d.factor();
        d.blast(BlastMix.loudness(BlastMix.DRONE, 500));
        assertEquals(before, d.factor(), 1e-6);
        d.blast(BlastMix.NEAR_LUFS);
        assertEquals(Math.pow(10, -Ducking.DEPTH / 20), d.factor(), 1e-6, "новый близкий взрыв — снова вся глубина");
    }
}
