package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сведение отражений в дорожки по сторонам: время, громкость, направление, без перегруза. */
class EchoMixTest {
    private static final double SECTOR = 2 * Math.PI / EchoMix.SECTORS;

    /** Зерно: щелчок 0.5 в начале и тихий хвост. */
    private static float[] click() {
        float[] g = new float[EchoMix.RATE / 2];
        g[0] = 0.5f;
        for (int i = 1; i < g.length; i++) g[i] = (float) (0.1 * Math.exp(-i / 500.0) * Math.sin(i * 0.05));
        return g;
    }

    private static TerrainEcho.Reflection echo(double azimuth, double delayTicks, double energy, double extraPath) {
        return new TerrainEcho.Reflection(0, 0, 0, azimuth, delayTicks, energy, extraPath, 100);
    }

    private static int peakAt(short[] pcm) {
        int at = 0;
        for (int i = 0; i < pcm.length; i++) if (Math.abs(pcm[i]) > Math.abs(pcm[at])) at = i;
        return at;
    }

    @Test
    void oneEchoIsOneTrackAtItsTimeAndLevel() {
        EchoMix.Mix mix = EchoMix.render(click(), List.of(echo(2 * SECTOR, 47.4, 0.25, 100)));
        assertEquals(1, mix.tracks().size());
        EchoMix.Track t = mix.tracks().get(0);
        assertEquals(2 * SECTOR, t.azimuth(), 1e-9);
        assertEquals(47.4, t.start(), 0.2, "начало — задержка отражения (окно 10 мс = 0,2 тика)");
        assertEquals(1, t.gain(), 1e-6);
        assertEquals(0, peakAt(t.pcm()));
        assertEquals(0.5 * 0.5, t.pcm()[0] / 32767.0, 1e-3, "амплитуда — √энергии × зерно");
    }

    @Test
    void echoBetweenSidesSplitsWithEqualPower() {
        EchoMix.Mix mix = EchoMix.render(click(), List.of(echo(SECTOR / 2, 40, 0.16, 100)));
        assertEquals(2, mix.tracks().size());
        double e = 0;
        for (EchoMix.Track t : mix.tracks()) {
            double a = t.pcm()[0] / 32767.0 / 0.5;
            e += a * a;
            assertEquals(Math.sqrt(0.16 / 2), a, 1e-3, "поровну");
        }
        assertEquals(0.16, e, 1e-3, "мощность сохраняется");
    }

    @Test
    void laterEchoesStartLaterInTheTrack() {
        EchoMix.Mix mix = EchoMix.render(click(), List.of(echo(0, 20, 0.1, 100), echo(0, 60, 0.05, 100)));
        EchoMix.Track t = mix.tracks().get(0);
        assertEquals(20, t.start(), 0.2);
        int second = (int) Math.round((60 - 20) / 20.0 * EchoMix.RATE);
        assertEquals(Math.sqrt(0.05) * 0.5, t.pcm()[second] / 32767.0, 2e-3, "второе эхо через 2 с после первого");
    }

    @Test
    void manyLoudEchoesAreScaledNotClipped() {
        List<TerrainEcho.Reflection> list = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) list.add(echo(0, 10, 0.5, 100));
        EchoMix.Track t = EchoMix.render(click(), list).tracks().get(0);
        int peak = 0;
        for (short s : t.pcm()) peak = Math.max(peak, Math.abs(s));
        assertTrue(peak <= Math.round(EchoMix.PEAK * 32767) + 1, "пик " + peak);
        assertTrue(t.gain() > 1, "громкость возвращена при игре");
    }

    @Test
    void longExtraPathLosesHighs() {
        float[] noise = new float[EchoMix.RATE];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < noise.length; i++) noise[i] = (float) (r.nextGaussian() * 0.1);
        short[] near = EchoMix.render(noise, List.of(echo(0, 20, 0.5, 100))).tracks().get(0).pcm();
        short[] far = EchoMix.render(noise, List.of(echo(0, 20, 0.5, 5000))).tracks().get(0).pcm();
        assertTrue(highs(far) < 0.2 * highs(near), "верха дальнего отражения: " + highs(far) + " против " + highs(near));
    }

    /** Мощность разностей соседних отсчётов — мера верхов. */
    private static double highs(short[] pcm) {
        double s = 0;
        for (int i = 1; i < pcm.length; i++) s += Math.pow(pcm[i] - pcm[i - 1], 2);
        return s;
    }

    @Test
    void tailTimeGrowsWithTheSpreadOfDelays() {
        double tight = EchoMix.render(click(), List.of(echo(0, 20, 0.1, 100), echo(1, 24, 0.1, 100))).t60();
        double wide = EchoMix.render(click(), List.of(echo(0, 20, 0.1, 100), echo(1, 140, 0.1, 100))).t60();
        assertTrue(wide > tight, wide + " против " + tight);
    }

    @Test
    void grainIsDecimatedMonoWithAFade() {
        float[] stereo = new float[44100 * 2 * 4];
        java.util.Arrays.fill(stereo, 0.5f);
        float[] g = EchoMix.grain(stereo, 2, 44100, 3);
        assertEquals(3 * EchoMix.RATE, g.length);
        assertEquals(0.5, g[10], 1e-3);
        assertEquals(0, g[g.length - 1], 1e-3, "конец затухает");
    }

    @Test
    void lowerPitchStretchesTheGrain() {
        float[] ramp = new float[1001];
        for (int i = 0; i < ramp.length; i++) ramp[i] = i / 1000f;
        float[] g = EchoMix.pitch(ramp, 0.75);
        assertEquals(1334, g.length, "на 0,75 — на треть длиннее");
        assertEquals(0.75 * 0.1, g[100], 1e-6, "секунда звука — 0,75 с записи");
        assertEquals(1, g[g.length - 1], 1e-3);
        assertSame(ramp, EchoMix.pitch(ramp, 1));
    }
}
