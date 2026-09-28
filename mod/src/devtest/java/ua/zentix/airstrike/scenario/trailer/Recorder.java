package ua.zentix.airstrike.scenario.trailer;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Запись плана трейлера: 60 кадров в секунду видео, кадр k — ровно момент {@code k·step()} тиков от начала плана
 * (часы игры ведёт {@link FrameClock}), поэтому видео плавное при любой скорости отрисовки. Звук не пишется с устройства (игра идёт
 * замедленно), а журналируется: какой файл, где, громкость, тон, дальность — {@code tools/trailer/edit.py}
 * собирает из этого дорожку заново. Всё — в {@code timeline.jsonl} рядом с кадрами.
 */
final class Recorder implements SoundEventListener {
    static final double VIDEO_FPS = 60, TPS = 20;
    /** Сколько PNG может ждать записи на диск, прежде чем рендер подождёт. */
    private static final int MAX_PENDING = 12;

    private final Path root;
    private final Writer log;
    private final AtomicInteger pending = new AtomicInteger();
    /** Звуки, которые ещё звучат: громкость и тон у них меняются (моторы, Доплер) — пишем каждый кадр. */
    private final Map<SoundInstance, Integer> playing = new IdentityHashMap<>();
    private int nextSoundId;

    private String shot;
    private double speed;
    private int nextFrame;
    private double time;

    Recorder(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
            log = Files.newBufferedWriter(root.resolve("timeline.jsonl"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean recording() {
        return shot != null;
    }

    double speed() {
        return speed;
    }

    /** Начать план: кадры пойдут в {@code frames/<name>/}. {@code speed} < 1 — замедление, > 1 — ускорение. */
    void start(String name, double speed) {
        this.shot = name;
        this.speed = speed;
        this.nextFrame = 0;
        this.time = 0;
        playing.clear(); // звуки прошлого плана в этот не тянем
        try {
            Files.createDirectories(root.resolve("frames").resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write(String.format(Locale.ROOT, "{\"type\":\"shot\",\"shot\":\"%s\",\"speed\":%.4f}", name, speed));
        Airstrike.LOG.info("TRAILER shot {} x{}", name, speed);
    }

    void stop() {
        if (shot == null) return;
        write(String.format(Locale.ROOT, "{\"type\":\"end\",\"shot\":\"%s\",\"frames\":%d}", shot, nextFrame));
        Airstrike.LOG.info("TRAILER shot {} done: {} frames", shot, nextFrame);
        shot = null;
        flush();
    }

    /** Отметка для монтажа (удар, подрыв…) в текущий момент плана. */
    void mark(String what) {
        if (shot == null) return;
        write(String.format(Locale.ROOT, "{\"type\":\"mark\",\"shot\":\"%s\",\"t\":%.3f,\"what\":\"%s\"}", shot, time, what));
    }

    /** Тиков игры на кадр видео в текущем плане. */
    double step() {
        return TPS * speed / VIDEO_FPS;
    }

    int frames() {
        return nextFrame;
    }

    /** Снять кадр плана (момент {@code t} тиков от начала плана — ровно {@code frames()·step()}). */
    void frame(double t, Camera camera) {
        if (shot == null) return;
        time = t;
        int k = nextFrame++;
        var cam = camera.getPosition();
        write(String.format(Locale.ROOT, "{\"type\":\"frame\",\"shot\":\"%s\",\"k\":%d,\"t\":%.3f,\"cam\":[%.2f,%.2f,%.2f,%.2f,%.2f]}",
                shot, k, t, cam.x, cam.y, cam.z, camera.getYRot(), camera.getXRot()));
        soundUpdates(t);
        grab(root.resolve("frames").resolve(shot).resolve(String.format(Locale.ROOT, "%05d.png", k)));
    }

    private void grab(Path file) {
        NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        if (pending.get() >= MAX_PENDING) {
            save(image, file);
            return;
        }
        pending.incrementAndGet();
        Util.ioPool().execute(() -> {
            save(image, file);
            pending.decrementAndGet();
        });
    }

    private static void save(NativeImage image, Path file) {
        try (image) {
            image.writeToFile(file);
        } catch (IOException e) {
            Airstrike.LOG.warn("TRAILER кадр {} не записан", file, e);
        }
    }

    /** Звук начал играть (движок уже выбрал файл). Музыку и фоновые звуки игры не берём — у трейлера своя музыка. */
    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents accessor, float range) {
        if (shot == null) return;
        SoundSource source = sound.getSource();
        if (source == SoundSource.MUSIC || source == SoundSource.RECORDS) return;
        Sound s = sound.getSound();
        if (s == null) return;
        int id = nextSoundId++;
        playing.put(sound, id);
        write(String.format(Locale.ROOT,
                "{\"type\":\"sound\",\"shot\":\"%s\",\"t\":%.3f,\"id\":%d,\"event\":\"%s\",\"file\":\"%s\",\"pos\":[%.2f,%.2f,%.2f],"
                        + "\"volume\":%.4f,\"pitch\":%.4f,\"range\":%.1f,\"relative\":%b,\"linear\":%b,\"loop\":%b,\"stream\":%b,\"lp\":%s}",
                shot, time, id, sound.getLocation(), s.getPath(), sound.getX(), sound.getY(), sound.getZ(), sound.getVolume(),
                sound.getPitch(), Math.max(sound.getVolume(), 1f) * s.getAttenuationDistance(), sound.isRelative(),
                sound.getAttenuation() == SoundInstance.Attenuation.LINEAR, sound.isLooping(), s.shouldStream(), lowpass(sound)));
    }

    /** Фильтр пути мода (воздух, преграды): [громкость, доля верхов]; без фильтра — [1, 1]. */
    private static String lowpass(SoundInstance s) {
        if (s instanceof ua.zentix.airstrike.client.sound.SoundFilters.Muffled m) {
            return String.format(Locale.ROOT, "[%.3f,%.3f]", m.lowpassGain(), m.lowpassHighs());
        }
        return "[1,1]";
    }

    /** Меняющиеся громкость, тон и место звучащих звуков; конец — когда движок их отпустил. */
    private void soundUpdates(double t) {
        var manager = Minecraft.getInstance().getSoundManager();
        for (Iterator<Map.Entry<SoundInstance, Integer>> it = playing.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            SoundInstance s = e.getKey();
            if (!manager.isActive(s)) {
                write(String.format(Locale.ROOT, "{\"type\":\"stop\",\"shot\":\"%s\",\"t\":%.3f,\"id\":%d}", shot, t, e.getValue()));
                it.remove();
                continue;
            }
            if (s instanceof net.minecraft.client.resources.sounds.TickableSoundInstance) {
                write(String.format(Locale.ROOT, "{\"type\":\"update\",\"shot\":\"%s\",\"t\":%.3f,\"id\":%d,\"pos\":[%.2f,%.2f,%.2f],\"volume\":%.4f,\"pitch\":%.4f,\"lp\":%s}",
                        shot, t, e.getValue(), s.getX(), s.getY(), s.getZ(), s.getVolume(), s.getPitch(), lowpass(s)));
            }
        }
    }

    private void write(String line) {
        try {
            log.write(line);
            log.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void flush() {
        try {
            log.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Дождаться записи всех кадров (перед выходом из игры). */
    void finish() {
        stop();
        while (pending.get() > 0) Thread.onSpinWait();
        try {
            log.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
