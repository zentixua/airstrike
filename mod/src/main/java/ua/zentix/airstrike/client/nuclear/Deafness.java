package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import ua.zentix.airstrike.registry.ModSounds;

/**
 * Оглушение после близкой ударной волны (DESIGN-nuke §5): 10–20 с все звуки игры глухие — фильтр нижних частот
 * OpenAL EFX ({@link ua.zentix.airstrike.client.sound.SoundFilters}), — и звенит в ушах. Громкость категорий
 * игрока не трогается.
 */
public final class Deafness {
    private static volatile int left, total;
    private static volatile float depth;

    private Deafness() {}

    /** @param psi давление у слушателя: чем больше, тем глуше и дольше */
    static void start(int ticks, double psi) {
        total = left = Math.max(left, ticks);
        depth = (float) Math.min(1, 0.55 + psi / 20);
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(ModSounds.NUKE_TINNITUS.get().getLocation(), SoundSource.MASTER,
                0.35f + 0.4f * depth, 1, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    static void tick() {
        if (left > 0) left--;
    }

    static void reset() {
        left = 0;
    }

    /**
     * Насколько глух этот звук сейчас: 0 — слышно как обычно, 1 — совсем глухо. Первые 60% оглушения — глухо,
     * потом слух возвращается. Музыку, пластинки и сам звон в ушах не трогаем. Зовут из звукового потока.
     */
    public static float depth(SoundInstance sound) {
        int l = left;
        if (l <= 0 || sound.getSource() == SoundSource.MUSIC || sound.getSource() == SoundSource.RECORDS
                || sound.getLocation().equals(ModSounds.NUKE_TINNITUS.get().getLocation())) return 0;
        return depth * Math.min(1, l / (total * 0.4f));
    }
}
