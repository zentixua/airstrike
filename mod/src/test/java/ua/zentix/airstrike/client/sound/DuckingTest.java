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
        double quieter = BlastMix.loudness(BlastMix.DRONE, 150);
        Ducking alone = new Ducking();
        alone.blast(quieter);
        assertTrue(alone.factor() < 0.9 && alone.factor() > before, "сам по себе он приглушает, но меньше");
        // взрыв тише, но всё же приглушающий: глубина и отпускание прежние, без новой выдержки
        assertTrue(Math.pow(10, -Ducking.DEPTH / 20) < before && before < 0.99);
        d.blast(quieter);
        assertEquals(before, d.factor(), 1e-6);
        d.tick();
        assertEquals(Ducking.RELEASE, 20 * Math.log10(d.factor() / before), 1e-4, "отпускает дальше, а не держит");
        d.blast(BlastMix.NEAR_LUFS);
        assertEquals(Math.pow(10, -Ducking.DEPTH / 20), d.factor(), 1e-6, "новый близкий взрыв — снова вся глубина");
    }
}
