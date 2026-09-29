package ua.zentix.airstrike.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Каналы моторов: звучит только слышимое, не больше предела, самое громкое; без дребезга на границах. */
class VoiceBudgetTest {
    private static final float LOUD = 0.5f, SILENT = 0;

    private static int count(boolean[] keep) {
        int n = 0;
        for (boolean k : keep) if (k) n++;
        return n;
    }

    @Test
    void silentLayerDoesNotStart() {
        boolean[] keep = VoiceBudget.select(new float[]{SILENT, VoiceBudget.AUDIBLE / 2, LOUD}, new boolean[3], new int[]{99, 99, 0}, 48);
        assertArrayEquals(new boolean[]{false, false, true}, keep);
    }

    @Test
    void silentLayerKeepsChannelForHoldTicksThenReleases() {
        boolean[] live = {true};
        assertTrue(VoiceBudget.select(new float[]{SILENT}, live, new int[]{1}, 48)[0], "смена слоёв на тик — не рвать петлю");
        assertTrue(VoiceBudget.select(new float[]{SILENT}, live, new int[]{VoiceBudget.HOLD - 1}, 48)[0]);
        assertFalse(VoiceBudget.select(new float[]{SILENT}, live, new int[]{VoiceBudget.HOLD}, 48)[0], "смолк надолго — канал свободен");
    }

    @Test
    void capKeepsLoudest() {
        int n = 100;
        float[] gain = new float[n];
        for (int i = 0; i < n; i++) gain[i] = 0.01f + i * 0.001f;
        boolean[] keep = VoiceBudget.select(gain, new boolean[n], new int[n], 48);
        assertEquals(48, count(keep));
        for (int i = 0; i < n; i++) assertEquals(i >= n - 48, keep[i], "слой " + i);
    }

    @Test
    void playingLayerYieldsOnlyToMuchLouder() {
        boolean[] live = {true, false};
        // новый чуть громче звучащего — места не занимает (иначе на границе предела слои дёргались бы каждый тик)
        assertArrayEquals(new boolean[]{true, false}, VoiceBudget.select(new float[]{0.1f, 0.15f}, live, new int[2], 1));
        // вдвое громче и больше — занимает
        assertArrayEquals(new boolean[]{false, true}, VoiceBudget.select(new float[]{0.1f, 0.21f}, live, new int[2], 1));
    }

    @Test
    void quietHeldLayersGiveWayFirst() {
        // предел занят смолкшими, но ещё держащими канал слоями: слышимый новый вытесняет их
        boolean[] keep = VoiceBudget.select(new float[]{SILENT, SILENT, LOUD}, new boolean[]{true, true, false}, new int[]{3, 3, 0}, 2);
        assertTrue(keep[2]);
        assertEquals(2, count(keep));
    }
}
