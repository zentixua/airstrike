package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.nuclear.Deafness;

/**
 * Фильтр нижних частот OpenAL EFX на наши звуки: воздух по дороге «съедает» верха (чем дальше, тем глуше),
 * холм или дом между источником и ухом глушит ещё сильнее, а после близкой ударной волны всё глухо (оглушение).
 * <p>
 * Звук с фильтром — {@link Muffled}: разовый получает фильтр при старте ({@link PlaySoundSourceEvent}),
 * мотор снаряда — каждый тик ({@link #update}). Всё, что трогает OpenAL, идёт в звуковом потоке, где контекст
 * текущий; параметры фильтра копируются в источник при привязке, поэтому один объект фильтра на всех.
 * Если стоит Sound Physics Remastered, он сам глушит звуки по пути — тогда воздух и преграды не наши, только оглушение.
 */
public final class SoundFilters {
    /** Затухание верхов в воздухе, дБ на блок (≈ 35 дБ/км на 4–5 кГц при обычной влажности). */
    private static final double AIR_DB_PER_BLOCK = 0.035;

    /** Номер фильтра OpenAL (создаётся в звуковом потоке при первой нужде), −1 — EFX нет. */
    private static volatile int filter;
    private static Boolean soundPhysics;

    private SoundFilters() {}

    /** Звук, которому нужен фильтр: общая громкость и доля верхов (0..1). */
    public interface Muffled {
        float lowpassGain();

        float lowpassHighs();
    }

    /** Воздух и преграды — наши (нет своего конфига «выкл» и нет Sound Physics). */
    static boolean pathEffects() {
        if (soundPhysics == null) soundPhysics = ModList.get().isLoaded("sound_physics_remastered");
        return !soundPhysics && AirstrikeConfig.CLIENT.soundMuffling.get();
    }

    /** Доля верхов, дошедшая по воздуху за d блоков. */
    static float air(double d) {
        return pathEffects() ? (float) Math.pow(10, -AIR_DB_PER_BLOCK * d / 20) : 1;
    }

    /**
     * Есть ли между ухом и точкой твёрдые блоки (холм, дом): 1 — прямой путь, 0 — закрыто. Листва — не преграда: звук
     * она почти не держит (ISO 9613-2, прил. A: ~1 дБ на 10–20 м густой кроны), а кроны на пути к низко летящему снаряду
     * то закрывали, то открывали его, и мотор вдали дрожал громкостью. Клиентский мир: незагруженные чанки пусты,
     * ничего не грузится.
     */
    static float open(Vec3 from, Vec3 to) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (!pathEffects() || mc.level == null || player == null) return 1;
        return mc.level.clip(new ThroughLeaves(from, to, player)).getType() == HitResult.Type.MISS ? 1 : 0;
    }

    /** Луч по твёрдым блокам, сквозь листву. */
    private static final class ThroughLeaves extends ClipContext {
        ThroughLeaves(Vec3 from, Vec3 to, Player player) {
            super(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
            return state.is(BlockTags.LEAVES) ? Shapes.empty() : super.getBlockShape(state, level, pos);
        }
    }

    /** Громкость и верха за преградой: закрытый путь — тише на 4 дБ и глухо (обход по дифракции). */
    static float blockedGain(float open) {
        return 1 - 0.37f * (1 - open);
    }

    static float blockedHighs(float open) {
        return 1 - 0.8f * (1 - open);
    }

    // ---------------------------------------------------------------- звуковой поток

    public static void onEngineLoad(SoundEngineLoadEvent e) {
        filter = 0;
    }

    public static void onSound(PlaySoundSourceEvent e) {
        start(e.getSound(), e.getChannel().source);
    }

    public static void onStream(PlayStreamingSourceEvent e) {
        start(e.getSound(), e.getChannel().source);
    }

    private static void start(SoundInstance sound, int source) {
        float gain = 1, highs = 1;
        if (sound instanceof Muffled m) {
            gain = m.lowpassGain();
            highs = m.lowpassHighs();
        }
        // оглушение: только короткие звуки — фильтр остаётся на источнике до конца, а петли мы ведём сами
        if (!(sound instanceof Muffled) && sound.isLooping()) return;
        float k = Deafness.depth(sound);
        gain *= 1 - 0.55f * k;
        highs *= Math.max(0.01f, 1 - 0.99f * k);
        if (gain < 0.999f || highs < 0.999f) set(source, gain, highs);
    }

    /** Каждый тик для звука, который играет: новые параметры фильтра (мотор улетает, скрывается за холмом). */
    static void update(SoundInstance sound, float gain, float highs) {
        ChannelAccess.ChannelHandle handle = Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.get(sound);
        if (handle == null) return;
        float k = Deafness.depth(sound);
        float g = gain * (1 - 0.55f * k), h = highs * Math.max(0.01f, 1 - 0.99f * k);
        handle.execute(channel -> set(channel.source, g, h));
    }

    private static void set(int source, float gain, float highs) {
        if (filter == 0) filter = create();
        if (filter < 0) return;
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, Math.max(0, Math.min(1, gain)));
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Math.max(0.01f, Math.min(1, highs)));
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
            Airstrike.LOG.warn("OpenAL EFX недоступен — звуки будут без приглушения расстоянием и оглушения: {}", ex.toString());
            return -1;
        }
    }
}
