package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Тишина после вспышки (DESIGN-nuke §5): животные вокруг эпицентра — до {@link ua.zentix.incoming.nuclear.Detonation#radiusMax}
 * — замолкают: их голоса ({@code entity.*.ambient}, категории «Существа» и «Окружение» — летучие мыши) не звучат
 * {@link #FULL} тиков после подрыва, потом понемногу возвращаются к {@link #BACK}. Только голоса без повода — крик
 * от удара, смерть и шаги звучат. Возраст подрыва — его часы на клиенте: подрыв, застанный при входе в мир или после
 * перемотки повтора, тоже тихий в свой срок.
 */
public final class AnimalSilence {
    static final double FULL = 4 * 60 * 20, BACK = 8 * 60 * 20;
    private static final RandomSource RANDOM = RandomSource.create();

    private AnimalSilence() {}

    public static void onPlay(PlaySoundEvent e) {
        SoundInstance s = e.getSound();
        if (s == null || s.getSource() != SoundSource.NEUTRAL && s.getSource() != SoundSource.AMBIENT) return;
        String path = s.getLocation().getPath();
        if (!path.startsWith("entity.") || !path.endsWith(".ambient")) return;
        double share = silence(s.getX(), s.getZ());
        if (share >= 1 || share > 0 && RANDOM.nextDouble() < share) e.setSound(null);
    }

    /** Какую долю голосов животных в этом месте сейчас не слышно (0..1). */
    static double silence(double x, double z) {
        double best = 0;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            Vec3 b = a.d.burst();
            double r = a.d.radiusMax();
            if ((x - b.x) * (x - b.x) + (z - b.z) * (z - b.z) > r * r) continue;
            best = Math.max(best, share(a.ticks(0)));
        }
        return best;
    }

    /** Доля тишины через {@code ticks} после подрыва. */
    static double share(double ticks) {
        if (ticks < 0 || ticks > BACK) return 0;
        return ticks < FULL ? 1 : 1 - (ticks - FULL) / (BACK - FULL);
    }
}
