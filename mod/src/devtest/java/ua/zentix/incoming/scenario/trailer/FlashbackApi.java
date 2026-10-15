package ua.zentix.incoming.scenario.trailer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Flashback для съёмки: запись повтора плана, повтор, вывод видео по ключевым кадрам. Flashback — мод Fabric (через
 * Sinytra Connector), в сборке мода и в CI его нет, API для модов у него нет: открытые члены — отражением, как
 * в {@code compat/FlashbackReplay} и {@code ReplayCheck}. Имена — Flashback 0.39 (ветка 1.21).
 */
final class FlashbackApi {
    private static final String P = "com.moulberry.flashback.";

    private FlashbackApi() {}

    static boolean present() {
        try {
            Class.forName(P + "Flashback");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Class<?> c(String name) throws ClassNotFoundException {
        return Class.forName(P + name);
    }

    private static RuntimeException fail(String what, Throwable e) {
        return new IllegalStateException("Flashback: " + what, e);
    }

    // ================================================================ запись

    /** Начать запись (снимок мира — в первом тике записи). */
    static void startRecording() {
        try {
            c("Flashback").getMethod("startRecordingReplay").invoke(null);
            if (recorder() == null) throw new IllegalStateException("Flashback не начал запись (несовместимые моды?)");
        } catch (ReflectiveOperationException e) {
            throw fail("startRecordingReplay", e);
        }
    }

    static Object recorder() {
        try {
            return c("Flashback").getField("RECORDER").get(null);
        } catch (ReflectiveOperationException e) {
            throw fail("RECORDER", e);
        }
    }

    static boolean recording() {
        return recorder() != null;
    }

    /**
     * Тиков, записанных в повтор: тик клиента, который идёт сейчас, ляжет в повтор под этим номером (запись кончает
     * тик в конце {@code Minecraft.tick}, после {@code ClientTickEvent.Post}).
     */
    static int writtenTicks() {
        try {
            Object r = recorder();
            if (r == null) return -1;
            Field f = r.getClass().getDeclaredField("writtenTicks");
            f.setAccessible(true);
            return f.getInt(r);
        } catch (ReflectiveOperationException e) {
            throw fail("Recorder.writtenTicks", e);
        }
    }

    /**
     * Кончить запись и сохранить повтор в {@code out} (как «quicksave» Flashback, только с нашим именем файла):
     * дописать последний тик, дождаться записи кусков и собрать zip.
     */
    static void finishRecording(Path out, String name) {
        try {
            Class<?> fb = c("Flashback");
            Object r = recorder();
            if (r == null) throw new IllegalStateException("запись не идёт");
            fb.getField("RECORDER").set(null, null);
            r.getClass().getMethod("endTick", boolean.class).invoke(r, true);
            Path folder = (Path) r.getClass().getMethod("finish").invoke(r);
            c("record.ReplayExporter").getMethod("export", Path.class, Path.class, String.class).invoke(null, folder, out, name);
        } catch (ReflectiveOperationException e) {
            throw fail("finishRecording", e);
        }
    }

    // ================================================================ повтор

    static void openReplay(Path zip) {
        try {
            c("Flashback").getMethod("openReplayWorld", Path.class).invoke(null, zip);
        } catch (ReflectiveOperationException e) {
            throw fail("openReplayWorld", e);
        }
    }

    static boolean inReplay() {
        try {
            return (boolean) c("Flashback").getMethod("isInReplay").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw fail("isInReplay", e);
        }
    }

    static Object server() {
        try {
            return c("Flashback").getMethod("getReplayServer").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw fail("getReplayServer", e);
        }
    }

    static int replayTick() {
        try {
            Object s = server();
            return s == null ? -1 : (int) s.getClass().getMethod("getReplayTick").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getReplayTick", e);
        }
    }

    static int totalTicks() {
        try {
            Object s = server();
            return (int) s.getClass().getMethod("getTotalReplayTicks").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getTotalReplayTicks", e);
        }
    }

    /** Повтор стоит на месте {@code tick} (перемотка — в потоке сервера повтора, клиент получает мир следом). */
    static void seek(int tick) {
        try {
            Object s = server();
            s.getClass().getField("replayPaused").setBoolean(s, true);
            s.getClass().getMethod("goToReplayTick", int.class).invoke(s, tick);
        } catch (ReflectiveOperationException e) {
            throw fail("goToReplayTick", e);
        }
    }

    /**
     * Шаг повтора на {@code tick} (следующий тик) без перемотки: сервер повтора читает один тик, как при проигрывании,
     * и встаёт — события этого тика у клиента живые.
     */
    static void step(int tick) {
        seek(tick);
    }

    // ================================================================ вывод

    static boolean exporting() {
        try {
            return c("Flashback").getField("EXPORT_JOB").get(null) != null;
        } catch (ReflectiveOperationException e) {
            throw fail("EXPORT_JOB", e);
        }
    }

    /** Место вывода: тик повтора с долей ({@code startTick} задания + пройденное); не выводит — NaN. */
    static double exportTick() {
        try {
            Object job = c("Flashback").getField("EXPORT_JOB").get(null);
            if (job == null) return Double.NaN;
            Object settings = job.getClass().getMethod("getSettings").invoke(job);
            int start = (int) settings.getClass().getMethod("startTick").invoke(settings);
            return start + (double) job.getClass().getMethod("getCurrentTickDouble").invoke(job);
        } catch (ReflectiveOperationException e) {
            throw fail("EXPORT_JOB.getCurrentTickDouble", e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> jobs() {
        try {
            return (List<Object>) c("exporting.ExportJobQueue").getField("queuedJobs").get(null);
        } catch (ReflectiveOperationException e) {
            throw fail("ExportJobQueue.queuedJobs", e);
        }
    }

    /**
     * Задание вывода в очередь и запуск очереди — как кнопки «Add to queue» и «Start» окна вывода Flashback. Камера и
     * поле зрения — из состояния редактора {@code state} (JSON, как его хранит Flashback); кадры — PNG
     * {@code out/00001.png…} (кадр k — тик {@code start + (k−1)·20/fps}) или, с {@code mov}, видео QuickTime RLE без
     * потерь в файл {@code out} (кадры раскладывает {@link FrameUnpack}). Без GUI, случайные числа частиц одинаковые
     * в каждом выводе.
     */
    static void queue(String name, Path state, double[] camera, int width, int height, int start, int end, double fps, Path out, boolean mov) {
        // PNG кадров Flashback пишет stb_image_write в одном потоке (zlib 8 и перебор фильтров) — узкое место вывода
        // при простаивающей видеокарте; уровень 5 и фильтр 2 дают те же пиксели (без потерь) на 30 % быстрее, +3 % места
        org.lwjgl.stb.STBImageWrite.stbi_write_png_compression_level.put(0, 5);
        org.lwjgl.stb.STBImageWrite.stbi_write_force_png_filter.put(0, 2);
        try {
            Object editor = c("state.EditorState").getMethod("load", Path.class).invoke(null, state);
            if (editor == null) throw new IllegalStateException("состояние редактора не прочиталось: " + state);
            Class<?> settings = c("exporting.ExportSettings");
            Constructor<?> ctor = Arrays.stream(settings.getConstructors()).filter(k -> k.getParameterCount() == 23).findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("ExportSettings(23)"));
            Object job = ctor.newInstance(name, editor, new net.minecraft.world.phys.Vec3(camera[0], camera[1], camera[2]),
                    (float) camera[3], (float) camera[4], width, height, start, end, fps, true,
                    constant("combo_options.VideoContainer", mov ? "MOV" : "PNG_SEQUENCE"),
                    constant("combo_options.VideoCodec", mov ? "QUICK_TIME" : "H264"), mov ? "qtrle" : "libx264",
                    0, false, false, true, false, false, constant("combo_options.AudioCodec", "AAC"), out, "%05d");
            jobs().add(job);
            c("exporting.ExportJobQueue").getField("drainingQueue").setBoolean(null, true);
        } catch (ReflectiveOperationException e) {
            throw fail("ExportSettings", e);
        }
    }

    /** У ffmpeg Flashback есть кодировщик QuickTime RLE (без него задание MOV упало бы посреди тика клиента). */
    static boolean qtrle() {
        try {
            Object codec = constant("combo_options.VideoCodec", "QUICK_TIME");
            String[] encoders = (String[]) codec.getClass().getMethod("getEncoders").invoke(codec);
            return Arrays.asList(encoders).contains("qtrle");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Задание вывода, которое идёт (его счётчики — {@link #stats}), или null. */
    @org.jetbrains.annotations.Nullable
    static Object job() {
        try {
            return c("Flashback").getField("EXPORT_JOB").get(null);
        } catch (ReflectiveOperationException e) {
            throw fail("EXPORT_JOB", e);
        }
    }

    /** Кадров, записанных заданием {@code job} (-1 — не прочиталось). */
    static int writtenFrames(Object job) {
        try {
            return (int) field(job, "writtenFrames");
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /**
     * Куда ушло время задания (счётчики самого {@code ExportJob}): тики сервера и клиента, отрисовка, ожидание кадра с
     * видеокарты, сдача кадра писателю (при полной очереди писателя — ожидание кодирования) и всё время с начала вывода.
     */
    static String stats(Object job) {
        try {
            int frames = (int) field(job, "writtenFrames");
            long wall = System.currentTimeMillis() - (long) field(job, "renderStartTime");
            StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "%d кадров за %.1f с", frames, wall / 1e3));
            for (String[] k : new String[][]{{"renderTimeNanos", "отрисовка"}, {"encodeTimeNanos", "сдача писателю"},
                    {"downloadTimeNanos", "кадр с видеокарты"}, {"clientTickTimeNanos", "тики клиента"}, {"serverTickTimeNanos", "тики сервера"}}) {
                long ns = (long) field(job, k[0]);
                b.append(String.format(java.util.Locale.ROOT, ", %s %.1f с (%.0f мс/кадр)", k[1], ns / 1e9, frames > 0 ? ns / 1e6 / frames : 0.0));
            }
            return b.toString();
        } catch (ReflectiveOperationException e) {
            return "счётчики не прочитались: " + e;
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object constant(String type, String name) throws ClassNotFoundException {
        for (Object k : c(type).getEnumConstants()) {
            if (((Enum<?>) k).name().equals(name)) return k;
        }
        throw new IllegalArgumentException(type + "." + name);
    }

    /** Методы и поля, которые зовёт съёмка, на месте (иначе — исключение с именем недостающего). */
    static void check() throws ReflectiveOperationException {
        Class<?> fb = c("Flashback");
        for (String m : List.of("startRecordingReplay", "openReplayWorld", "isInReplay", "getReplayServer")) {
            if (Arrays.stream(fb.getMethods()).noneMatch(x -> x.getName().equals(m))) throw new NoSuchMethodException("Flashback." + m);
        }
        fb.getField("RECORDER");
        fb.getField("EXPORT_JOB");
        Class<?> rec = c("record.Recorder");
        rec.getDeclaredField("writtenTicks");
        rec.getMethod("endTick", boolean.class);
        rec.getMethod("finish");
        c("record.ReplayExporter").getMethod("export", Path.class, Path.class, String.class);
        Class<?> server = c("playback.ReplayServer");
        server.getField("replayPaused");
        server.getMethod("goToReplayTick", int.class);
        c("exporting.ExportJobQueue").getField("queuedJobs");
        Method load = c("state.EditorState").getMethod("load", Path.class);
        if (load == null) throw new NoSuchMethodException("EditorState.load");
    }
}
