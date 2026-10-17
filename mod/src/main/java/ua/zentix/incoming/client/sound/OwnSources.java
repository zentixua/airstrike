package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.nuclear.Deafness;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Свои источники OpenAL — для эха от рельефа ({@link EchoMix}): мимо звукового движка Minecraft, чтобы Sound Physics
 * Remastered, который ставит свои фильтры EFX всем источникам движка, не накладывал на отражения свои отражения,
 * и чтобы дорожка из своих отсчётов (а не файла ресурсов) начиналась точно в свой кадр.
 * <p>
 * Источник стоит в 3 блоках от уха в сторону своей стороны света (как {@link ClientSounds#atEar}), без затухания
 * по расстоянию; громкость — категория «Общая» (общая громкость — усиление слушателя, его ставит движок). Фильтр
 * EFX на прямом пути — оглушение ({@link Deafness}), посыл — в свой слот реверберации (хвост поверх дорожек).
 * Без EFX — без оглушения и хвоста.
 * <p>
 * Время голосов — от {@link Anchor}: от того, когда движок на деле запустил прямой звук, а не от тика, в который его
 * попросили.
 * <p>
 * Всё — в потоке клиента (кадр, тик); контекст OpenAL у процесса один, его делает текущим движок. Перезапуск движка
 * (смена устройства, F3+T) удаляет контекст со всеми объектами — источники забываются без вызовов AL
 * ({@link #engineLoaded}).
 */
public final class OwnSources {
    /** Своих источников не больше: остальные — у движка (у OpenAL Soft всего 256). */
    static final int MAX = 12;
    /** Отставание старта больше этого (с) — начать с середины, а не сначала. */
    private static final double LATE = 0.03;
    /** Сколько тиков ждать, что движок запустит звук начала; не запустил — начало по тику. */
    private static final double ANCHOR_WAIT = 40;

    /** Часы, по которым ждёт голос: тики с долей кадра. */
    @FunctionalInterface
    public interface Clock {
        double ticks(float partialTick);
    }

    /**
     * Начало, от которого ждут голоса: когда движок на деле запустил звук {@code sound} — {@link PlaySoundSourceEvent}
     * приходит из потока звука сразу после play. Запись без preload декодируется при первом звуке, а тик клиента без
     * окна — раз в кадр: прямой звук начинается позже своего тика, и эхо по тику опережало бы его. Звук так и не начался
     * (движку не хватило мест, звук заменил чужой мод) — начало по тику {@code tick}.
     */
    public static final class Anchor {
        final Object owner;
        @Nullable
        final SoundInstance sound;
        final Clock clock;
        final double tick;
        /** {@link System#nanoTime()} старта звука, 0 — ещё не начался (пишет поток звука). */
        volatile long started;
        double zero = Double.NaN;

        Anchor(Object owner, @Nullable SoundInstance sound, Clock clock, double tick) {
            this.owner = owner;
            this.sound = sound;
            this.clock = clock;
            this.tick = tick;
        }

        /** Начало по часам (тики), NaN — ещё не знаем. */
        public double zero() {
            return zero;
        }

        /** Насколько начало позже своего тика, с (NaN — ещё не знаем). */
        public double lateSeconds() {
            return (zero - tick) / 20;
        }
    }

    /** Один голос: дорожка, её сторона и время. */
    public static final class Voice {
        final Object owner;
        final Anchor anchor;
        final double after;
        final double azimuth;
        final float gain;
        final short[] pcm;
        final int rate;
        int source, buffer;
        boolean started, done;

        Voice(Object owner, Anchor anchor, double after, double azimuth, float gain, short[] pcm, int rate) {
            this.owner = owner;
            this.anchor = anchor;
            this.after = after;
            this.azimuth = azimuth;
            this.gain = gain;
            this.pcm = pcm;
            this.rate = rate;
        }
    }

    private static final List<Voice> VOICES = new ArrayList<>();
    /** Начала, которых ещё ждут (старт звука пишет поток звука). */
    private static final List<Anchor> ANCHORS = new CopyOnWriteArrayList<>();
    /** Фильтр прямого пути и слот реверберации: 0 — ещё не созданы, −1 — EFX нет. */
    private static int filter, slot, effect;
    private static boolean paused;
    private static int started;

    private OwnSources() {}

    /**
     * Начало для голосов {@code owner}: старт звука {@code sound}, отданного движку в тик {@code tick} по часам
     * {@code clock} (null — начало по тику).
     */
    public static Anchor anchor(Object owner, @Nullable SoundInstance sound, Clock clock, double tick) {
        Anchor a = new Anchor(owner, sound, clock, tick);
        if (sound == null) a.zero = tick;
        else ANCHORS.add(a);
        return a;
    }

    /** Поток звука: движок запустил звук — начало тех, кто его ждёт. */
    public static void onSource(PlaySoundSourceEvent e) {
        if (ANCHORS.isEmpty()) return;
        long now = System.nanoTime();
        for (Anchor a : ANCHORS) if (a.sound == e.getSound() && a.started == 0) a.started = now;
    }

    /**
     * Дорожка в очередь: зазвучит через {@code after} тиков после начала {@code anchor}. {@code owner} — чей голос (снять
     * все его — {@link #stop}).
     *
     * @return false — мест нет или OpenAL не готов
     */
    public static boolean play(Object owner, Anchor anchor, double after, double azimuth, float gain, short[] pcm, int rate) {
        if (pcm.length == 0 || VOICES.size() >= MAX || !ready()) return false;
        Voice v = new Voice(owner, anchor, after, azimuth, gain, pcm, rate);
        try {
            v.buffer = AL10.alGenBuffers();
            ShortBuffer data = BufferUtils.createShortBuffer(pcm.length).put(pcm).flip();
            AL10.alBufferData(v.buffer, AL10.AL_FORMAT_MONO16, data, rate);
            v.source = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                free(v);
                return false;
            }
            AL10.alSourcei(v.source, AL10.AL_BUFFER, v.buffer);
            AL10.alSourcei(v.source, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcei(v.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            // без затухания по расстоянию: громкость — наша (как у Channel.disableAttenuation)
            AL10.alSourcei(v.source, 0xD000, AL10.AL_NONE);
            AL10.alSourcef(v.source, AL10.AL_MAX_GAIN, 16);
            AL10.alSourcef(v.source, AL10.AL_GAIN, gain);
            if (effects() && slot > 0) AL11.alSource3i(v.source, EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, EXTEfx.AL_FILTER_NULL);
        } catch (RuntimeException | LinkageError e) {
            Incoming.LOG.warn("Own OpenAL source failed: {}", e.toString());
            free(v);
            return false;
        }
        VOICES.add(v);
        return true;
    }

    /** Хвост реверберации своего слота: время спада и доля посыла. */
    public static void reverb(double t60, float send) {
        if (!ready() || !effects() || slot <= 0) return;
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, (float) Math.max(0.1, Math.min(20, t60)));
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
        EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, Math.max(0, Math.min(1, send)));
    }

    /** Кадр: начала, старт дорожек, которым пора, место у уха, оглушение, пауза; отзвучавшие — прочь. */
    public static void frame(RenderFrameEvent.Pre e) {
        if (VOICES.isEmpty() && ANCHORS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        boolean pause = mc.isPaused();
        if (!pause) anchors(partial);
        if (VOICES.isEmpty()) return;
        if (!ready()) {
            VOICES.clear();
            return;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        float k = Deafness.depth();
        boolean deaf = k > 0 && effects();
        if (deaf) {
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1 - 0.55f * k);
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Math.max(0.01f, 1 - 0.99f * k));
        }
        for (Iterator<Voice> it = VOICES.iterator(); it.hasNext(); ) {
            Voice v = it.next();
            if (v.started && !pause && !paused && AL10.alGetSourcei(v.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                free(v);
                it.remove();
                continue;
            }
            AL10.alSource3f(v.source, AL10.AL_POSITION, (float) (ear.x + Math.cos(v.azimuth) * 3), (float) ear.y,
                    (float) (ear.z + Math.sin(v.azimuth) * 3));
            if (effects()) AL10.alSourcei(v.source, EXTEfx.AL_DIRECT_FILTER, deaf ? filter : EXTEfx.AL_FILTER_NULL);
            if (pause) {
                if (v.started && !paused) AL10.alSourcePause(v.source);
                continue;
            }
            if (v.started) {
                if (paused) AL10.alSourcePlay(v.source);
                continue;
            }
            double at = v.anchor.zero + v.after;
            if (Double.isNaN(at)) continue;
            double late = (v.anchor.clock.ticks(partial) - at) / 20;
            if (late < 0) continue;
            if (late > v.pcm.length / (double) v.rate) {
                free(v);
                it.remove();
                continue;
            }
            if (late > LATE) AL10.alSourcef(v.source, AL11.AL_SEC_OFFSET, (float) late);
            AL10.alSourcePlay(v.source);
            v.started = true;
            started++;
        }
        paused = pause;
    }

    /** Начала, чей звук уже начался (по часам: сейчас минус сколько он уже звучит), или не начался за срок. */
    private static void anchors(float partial) {
        if (ANCHORS.isEmpty()) return;
        long now = System.nanoTime();
        for (Anchor a : ANCHORS) {
            double clock = a.clock.ticks(partial);
            long started = a.started;
            if (started != 0) a.zero = Math.max(a.tick, clock - (now - started) * 20e-9);
            else if (clock > a.tick + ANCHOR_WAIT) a.zero = a.tick;
            if (!Double.isNaN(a.zero)) ANCHORS.remove(a);
        }
    }

    /** Ждущие начала — по тику: звук, которого они ждали, движок уже не запустит. */
    private static void settle() {
        for (Anchor a : ANCHORS) if (Double.isNaN(a.zero)) a.zero = a.tick;
        ANCHORS.clear();
    }

    /** Снять голоса хозяина (подрыв убран или стал прошлым). */
    public static void stop(Object owner) {
        ANCHORS.removeIf(a -> a.owner == owner);
        for (Iterator<Voice> it = VOICES.iterator(); it.hasNext(); ) {
            Voice v = it.next();
            if (v.owner != owner) continue;
            if (ready()) free(v);
            it.remove();
        }
    }

    /** Перемотка повтора, выход из мира: тишина. */
    public static void reset() {
        if (ready()) for (Voice v : VOICES) free(v);
        VOICES.clear();
        settle();
        paused = false;
    }

    /** Движок звука загружен заново: прежний контекст удалён вместе с источниками и фильтрами. */
    static void engineLoaded() {
        VOICES.clear();
        settle();
        filter = slot = effect = 0;
        paused = false;
    }

    /** Для строки {@code SCENARIO}: сколько голосов ждёт и звучит, сколько начато всего. */
    public static String describe() {
        int playing = 0;
        for (Voice v : VOICES) if (v.started) playing++;
        return String.format(Locale.ROOT, "own %d/%d started %d efx %s", playing, VOICES.size(), started, filter > 0 ? "yes" : filter < 0 ? "no" : "-");
    }

    private static boolean ready() {
        try {
            return ALC10.alcGetCurrentContext() != 0;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static void free(Voice v) {
        try {
            if (v.source != 0) {
                AL10.alSourceStop(v.source);
                AL10.alSourcei(v.source, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(v.source);
            }
            if (v.buffer != 0) AL10.alDeleteBuffers(v.buffer);
        } catch (RuntimeException | LinkageError ignored) {
            // контекст уже ушёл — объекты удалены с ним
        }
        v.source = v.buffer = 0;
        v.done = true;
    }

    /** Фильтр и слот EFX (создаются при первой нужде); false — EFX нет. */
    private static boolean effects() {
        if (filter == 0) create();
        return filter > 0;
    }

    private static void create() {
        try {
            long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
            if (device == 0 || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                filter = -1;
                return;
            }
            filter = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                filter = -1;
                return;
            }
            // хвост: реверберация открытого места — редкие поздние отражения, верха гаснут быстрее
            effect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, 0.6f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 0.8f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, 4);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.5f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, 0.05f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, 1.2f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, 0.08f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF, 0.95f);
            slot = EXTEfx.alGenAuxiliaryEffectSlots();
            EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
            EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, 0.25f);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) slot = -1;
        } catch (RuntimeException | LinkageError ex) {
            Incoming.LOG.warn("OpenAL EFX unavailable for terrain echo: {}", ex.toString());
            filter = -1;
        }
    }
}
