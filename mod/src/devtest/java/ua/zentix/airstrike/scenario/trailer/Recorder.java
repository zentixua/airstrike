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
import java.util.List;
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
    /** Момент последнего снятого кадра (тики мира от начала плана) — для отметок и звуков. */
    private double time;
    /** Начало текущего кадра видео: время мира и время камеры (у замершего мира камера идёт дальше). */
    private double worldT, camT;
    /** Скорость, которую просит план (1 — как в игре); текущая — сглаженная, чтобы замедление наступало плавно. */
    private java.util.function.DoubleSupplier speedWant = () -> 1;
    private double speedNow = 1;
    /** Замерший мир: сколько кадров ещё снимать, с какой скоростью идёт камера; момент, когда замереть (тики мира). */
    private int frozenFrames;
    private double frozenCamSpeed, freezeAt = Double.NaN;
    private int freezeFor;
    private double freezeCamSpeed;
    /**
     * Размытие движения: кадр видео — среднее {@code subframes} кадров отрисовки, снятых за долю {@code shutter}
     * интервала кадра («затвор 180°» — 0.5). Сумма копится здесь, на диск идёт готовый кадр.
     */
    private int subframes = 1, sub;
    private double shutter = 0.5;
    private int[] accum;
    private int accumW, accumH;

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

    /**
     * Начать план: кадры пойдут в {@code frames/<name>/}. {@code speed} < 1 — замедление, > 1 — ускорение. {@code hud} —
     * в кадре текст интерфейса игры: такой план монтаж берёт из записи на языке ролика (язык игры — в заголовке плана).
     */
    void start(String name, double speed, boolean hud) {
        start(name, speed, () -> speed, hud);
    }

    /** План с меняющейся скоростью: {@code want} спрашивается каждый кадр (замедление на подлёте и разгон после). */
    void start(String name, double speed, java.util.function.DoubleSupplier want, boolean hud) {
        this.shot = name;
        this.speed = speed;
        this.speedWant = want;
        this.speedNow = want.getAsDouble();
        this.nextFrame = 0;
        this.time = 0;
        this.worldT = this.camT = 0;
        this.sub = 0;
        this.frozenFrames = 0;
        this.freezeAt = Double.NaN;
        playing.clear(); // звуки прошлого плана в этот не тянем
        try {
            Files.createDirectories(root.resolve("frames").resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write(String.format(Locale.ROOT, "{\"type\":\"shot\",\"shot\":\"%s\",\"speed\":%.4f,\"hud\":%b,\"lang\":\"%s\"}", name, speed, hud,
                Minecraft.getInstance().options.languageCode));
        Airstrike.LOG.info("TRAILER shot {} x{}", name, speed);
        // петли, что зазвучали раньше плана и звучат дальше (моторы снарядов в полёте, начатые на пуске), — с начала
        // плана: движок сообщает о звуке только при запуске, и без этого планы погони шли без мотора
        for (SoundInstance sound : List.copyOf(Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.keySet())) {
            if (sound.isLooping()) log(sound);
        }
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

    /** Тиков игры на кадр видео в текущем плане (при замершем мире — 0). */
    double step() {
        if (frozenFrames > 0) return 0;
        double s = TPS * speedNow / VIDEO_FPS;
        return Double.isNaN(freezeAt) ? s : Math.min(s, Math.max(0, freezeAt - worldT));
    }

    /** Тиков камеры на кадр видео: у замершего мира камера облетает сцену со своей скоростью. */
    double camStep() {
        return frozenFrames > 0 ? TPS * frozenCamSpeed / VIDEO_FPS : TPS * speedNow / VIDEO_FPS;
    }

    /** Кадров на кадр видео для размытия движения и доля интервала кадра, за которую они сняты. */
    void motionBlur(int subframes, double shutter) {
        this.subframes = Math.max(1, subframes);
        this.shutter = shutter;
    }

    /** Сдвиг текущего подкадра от начала кадра, в долях шага: подкадры ровно по затвору, по центру кадра. */
    private double subOffset() {
        return subframes == 1 ? 0 : shutter * ((sub + 0.5) / subframes - 0.5);
    }

    /** Момент мира для текущего (под)кадра, тики от начала плана. */
    double worldTime() {
        return worldT + subOffset() * step();
    }

    /** Момент камеры для текущего (под)кадра. */
    double camTime() {
        return camT + subOffset() * camStep();
    }

    /**
     * Замереть, когда мир дойдёт до {@code atTick} (целый тик: у замершего мира игра рисует сущности на границе тика,
     * иначе они прыгнули бы), и снять {@code frames} кадров с неподвижным миром, пока камера идёт со скоростью
     * {@code camSpeed}.
     */
    void freeze(double atTick, int frames, double camSpeed) {
        freezeAt = Math.ceil(atTick);
        freezeFor = frames;
        freezeCamSpeed = camSpeed;
    }

    /** Мир дошёл до момента заморозки: дальше кадры с неподвижным миром. */
    boolean freezeNow() {
        if (Double.isNaN(freezeAt) || worldT < freezeAt - 1e-6 || sub != 0) return false;
        freezeAt = Double.NaN;
        frozenFrames = freezeFor;
        frozenCamSpeed = freezeCamSpeed;
        return true;
    }

    boolean frozen() {
        return frozenFrames > 0;
    }


    int frames() {
        return nextFrame;
    }

    /**
     * Снять (под)кадр плана. Кадр видео готов после последнего подкадра: тогда он пишется в журнал (момент — середина
     * затвора, ровно начало кадра), и часы плана идут к следующему. {@code t} — время мира этого подкадра.
     *
     * @return после этого вызова начнётся новый кадр видео
     */
    boolean frame(double t, Camera camera) {
        if (shot == null) return false;
        NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        if (subframes > 1) {
            accumulate(image);
            if (++sub < subframes) return false;
            sub = 0;
            image = average();
        }
        time = worldT;
        int k = nextFrame++;
        var cam = camera.getPosition();
        write(String.format(Locale.ROOT, "{\"type\":\"frame\",\"shot\":\"%s\",\"k\":%d,\"t\":%.3f,\"ct\":%.3f,\"cam\":[%.2f,%.2f,%.2f,%.2f,%.2f]}",
                shot, k, worldT, camT, cam.x, cam.y, cam.z, camera.getYRot(), camera.getXRot()));
        soundUpdates(worldT);
        grab(image, root.resolve("frames").resolve(shot).resolve(String.format(Locale.ROOT, "%05d.png", k)));
        worldT += step();
        camT += camStep();
        if (frozenFrames > 0) frozenFrames--;
        // скорость плана меняется плавно: за ~8 кадров видео (около 0.13 с) на две трети пути к нужной
        speedNow += (speedWant.getAsDouble() - speedNow) * 0.12;
        return true;
    }

    /** Подкадр в сумму (каналы по отдельности: 8 бит × до 16 подкадров помещаются в int). */
    private void accumulate(NativeImage image) {
        try (image) {
            int[] px = image.getPixelsRGBA();
            if (accum == null || accumW != image.getWidth() || accumH != image.getHeight()) {
                accumW = image.getWidth();
                accumH = image.getHeight();
                accum = new int[px.length * 3];
            }
            if (sub == 0) java.util.Arrays.fill(accum, 0);
            for (int i = 0, j = 0; i < px.length; i++, j += 3) {
                int c = px[i]; // ABGR
                accum[j] += c & 0xFF;
                accum[j + 1] += (c >> 8) & 0xFF;
                accum[j + 2] += (c >> 16) & 0xFF;
            }
        }
    }

    private NativeImage average() {
        NativeImage out = new NativeImage(accumW, accumH, false);
        int n = subframes, half = subframes / 2;
        for (int y = 0, j = 0; y < accumH; y++) {
            for (int x = 0; x < accumW; x++, j += 3) {
                int r = (accum[j] + half) / n, g = (accum[j + 1] + half) / n, b = (accum[j + 2] + half) / n;
                out.setPixelRGBA(x, y, 0xFF000000 | b << 16 | g << 8 | r);
            }
        }
        return out;
    }

    private void grab(NativeImage image, Path file) {
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
        if (shot != null) log(sound);
    }

    private void log(SoundInstance sound) {
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
