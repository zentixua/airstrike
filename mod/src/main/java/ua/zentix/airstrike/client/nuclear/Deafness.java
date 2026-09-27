package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.registry.ModSounds;

/**
 * Оглушение после близкой ударной волны (DESIGN-nuke §5): 10–20 с все новые звуки игры глухие — фильтр нижних
 * частот OpenAL EFX на каждый новый источник, — и звенит в ушах. Громкость категорий игрока не трогается.
 * Фильтр ставится в {@link PlaySoundSourceEvent}, который звуковой движок шлёт из своего потока, где контекст
 * OpenAL текущий; поле {@code Channel.source} открыто access transformer'ом.
 */
public final class Deafness {
    private static volatile int left, total;
    private static volatile float depth;
    /** Номер фильтра OpenAL (создаётся в звуковом потоке при первой нужде), −1 — EFX нет. */
    private static volatile int filter;

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

    /** Звуковой движок перезапущен (F3+T, смена устройства): старый фильтр пропал вместе с контекстом. */
    public static void onEngineLoad(SoundEngineLoadEvent e) {
        filter = 0;
    }

    public static void onSound(PlaySoundSourceEvent e) {
        apply(e.getSound(), e.getChannel().source);
    }

    public static void onStream(PlayStreamingSourceEvent e) {
        apply(e.getSound(), e.getChannel().source);
    }

    /** Звуковой поток: новому источнику — фильтр нижних частот, сила спадает к концу оглушения. */
    private static void apply(SoundInstance sound, int source) {
        int l = left;
        if (l <= 0 || sound.getLocation().equals(ModSounds.NUKE_TINNITUS.get().getLocation())) return;
        if (filter == 0) filter = create();
        if (filter < 0) return;
        float k = depth * Math.min(1, l / (total * 0.4f)); // первые 60% — глухо, потом слух возвращается
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1 - 0.55f * k);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Math.max(0.01f, 1 - 0.99f * k));
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
    }

    private static int create() {
        try {
            long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
            if (device == 0 || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) return -1;
            int f = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(f, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) return -1;
            return f;
        } catch (RuntimeException | LinkageError ex) {
            Airstrike.LOG.warn("OpenAL EFX недоступен — оглушение будет без глухоты: {}", ex.toString());
            return -1;
        }
    }
}
