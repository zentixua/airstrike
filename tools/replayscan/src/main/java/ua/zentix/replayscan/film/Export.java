package ua.zentix.replayscan.film;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Flashback для съёмки: повтор (открыть, перемотать, шаг), вывод видео по ключевым кадрам или глазами игрока. Flashback —
 * мод Fabric (через Sinytra Connector), API для модов у него нет: открытые члены — отражением, имена — Flashback 0.39
 * (ветка 1.21); их же зовёт окно Flashback (перемотку и очередь вывода — из потока клиента).
 */
final class Export {
    private static final String P = "com.moulberry.flashback.";
    /** Ключи камеры — с запасом до начала: вывод ставит камеру и чуть раньше. */
    private static final int LEAD = 40;

    private Export() {}

    private static Class<?> c(String name) throws ClassNotFoundException {
        return Class.forName(P + name);
    }

    private static IllegalStateException fail(String what, Throwable e) {
        return new IllegalStateException("Flashback: " + what, e);
    }

    static void open(Path zip) {
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

    @Nullable
    static net.minecraft.server.MinecraftServer server() {
        try {
            return (net.minecraft.server.MinecraftServer) c("Flashback").getMethod("getReplayServer").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw fail("getReplayServer", e);
        }
    }

    static int tick() {
        try {
            Object s = server();
            return s == null ? -1 : (int) s.getClass().getMethod("getReplayTick").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getReplayTick", e);
        }
    }

    static int total() {
        try {
            Object s = server();
            return s == null ? -1 : (int) s.getClass().getMethod("getTotalReplayTicks").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getTotalReplayTicks", e);
        }
    }

    /** id сущности записавшего игрока на сервере повтора ({@code −1} — её нет). */
    static int localPlayerId() {
        try {
            Object s = server();
            return s == null ? -1 : (int) s.getClass().getMethod("getLocalPlayerId").invoke(s);
        } catch (ReflectiveOperationException e) {
            throw fail("getLocalPlayerId", e);
        }
    }

    /**
     * Повтор встаёт на тик {@code tick} (на паузе). На следующий тик — шаг: сервер повтора читает один тик, как при
     * проигрывании, события этого тика у клиента живые; дальше — перемотка.
     */
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
     * Члены Flashback, которые зовёт съёмка, на месте: иначе съёмка упала бы посреди повтора.
     */
    static void check() throws ReflectiveOperationException {
        Class<?> flashback = peek("Flashback");
        flashback.getMethod("openReplayWorld", Path.class);
        flashback.getMethod("isInReplay");
        flashback.getMethod("getReplayServer");
        flashback.getField("EXPORT_JOB");
        Class<?> server = peek("playback.ReplayServer");
        server.getField("replayPaused");
        for (String m : List.of("getReplayTick", "getTotalReplayTicks", "getLocalPlayerId")) server.getMethod(m);
        server.getMethod("goToReplayTick", int.class);
        Class<?> queue = peek("exporting.ExportJobQueue");
        queue.getField("queuedJobs");
        queue.getField("drainingQueue");
        peek("state.EditorState").getMethod("load", Path.class);
    }

    /** Класс Flashback без инициализации: проверка идёт при создании мода, до клиента. */
    private static Class<?> peek(String name) throws ClassNotFoundException {
        return Class.forName(P + name, false, Export.class.getClassLoader());
    }

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
     * Задание вывода в очередь и запуск очереди — как кнопки «Add to queue» и «Start» окна вывода Flashback: камера и угол
     * кадра — из состояния редактора {@code state} (JSON, как его хранит Flashback), видео — MKV, H264 первым кодером
     * Flashback (его окно выбирает его же), стерео AAC, одинаковые случайные числа частиц в каждом выводе; {@code gui} —
     * с интерфейсом игрока (окно вывода начинает задание только без него, вернуть можно потом).
     */
    static void queue(String name, Path state, Vec3 camera, float yaw, float pitch, int width, int height, int start, int end, double fps,
                      int bitrate, boolean gui, Path video) {
        try {
            Object editor = c("state.EditorState").getMethod("load", Path.class).invoke(null, state);
            if (editor == null) throw new IllegalStateException("editor state not read: " + state);
            Object h264 = constant("combo_options.VideoCodec", "H264");
            String[] encoders = (String[]) h264.getClass().getMethod("getEncoders").invoke(h264);
            Constructor<?> ctor = Arrays.stream(c("exporting.ExportSettings").getConstructors()).filter(k -> k.getParameterCount() == 23).findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("ExportSettings(23)"));
            Object job = ctor.newInstance(name, editor, camera, yaw, pitch, width, height, start, end, fps, true,
                    constant("combo_options.VideoContainer", "MKV"), h264, encoders[0], bitrate, false, false, !gui, true, true,
                    constant("combo_options.AudioCodec", "AAC"), video, null);
            jobs().add(job);
            c("exporting.ExportJobQueue").getField("drainingQueue").setBoolean(null, true);
        } catch (ReflectiveOperationException e) {
            throw fail("ExportSettings", e);
        }
    }

    private static Object constant(String type, String name) throws ClassNotFoundException {
        for (Object k : c(type).getEnumConstants()) {
            if (((Enum<?>) k).name().equals(name)) return k;
        }
        throw new IllegalArgumentException(type + "." + name);
    }

    /**
     * Состояние редактора Flashback (как его хранит Flashback): одна сцена; у камеры по ключевым кадрам — дорожки камеры и
     * угла кадра с ключом на каждый тик окна (курс без скачка через ±180°; место ключа — ноги зрителя, на {@code eye} ниже
     * глаза); глазами игрока — без дорожек. Интерфейс повтора (чат, табло, имена) скрыт, панель предметов — только
     * с {@code gui}, зрители повтора не видны.
     */
    static String editorState(@Nullable Cameras.Path path, int a, int b, double eye, boolean gui) {
        JsonArray tracks = new JsonArray();
        if (path != null) {
            JsonObject cam = new JsonObject(), fov = new JsonObject();
            float yaw = Float.NaN;
            for (int t = a - LEAD - 2; t <= b + 2; t++) {
                Cameras.Pose p = path.at(t);
                yaw = Float.isNaN(yaw) ? p.yaw() : yaw + Mth.wrapDegrees(p.yaw() - yaw);
                JsonObject k = new JsonObject();
                JsonArray pos = new JsonArray();
                pos.add(p.pos().x);
                pos.add(p.pos().y - eye);
                pos.add(p.pos().z);
                k.add("position", pos);
                k.addProperty("yaw", yaw);
                k.addProperty("pitch", p.pitch());
                k.addProperty("roll", 0);
                k.addProperty("type", "camera");
                k.addProperty("interpolation_type", "SMOOTH");
                cam.add(Integer.toString(t), k);
                JsonObject f = new JsonObject();
                f.addProperty("fov", p.fov());
                f.addProperty("type", "fov");
                f.addProperty("interpolation_type", "SMOOTH");
                fov.add(Integer.toString(t), f);
            }
            tracks.add(track("CAMERA", cam));
            tracks.add(track("FOV", fov));
        }
        JsonObject visuals = new JsonObject();
        for (String k : List.of("showChat", "showBossBar", "showTitleText", "showScoreboard", "showActionBar")) visuals.addProperty(k, false);
        visuals.addProperty("showHotbar", gui);
        visuals.addProperty("renderNametags", false);
        JsonObject scene = new JsonObject();
        scene.addProperty("name", "film");
        scene.add("keyframeTracks", tracks);
        scene.addProperty("exportStartTicks", a);
        scene.addProperty("exportEndTicks", b);
        JsonObject history = new JsonObject();
        history.add("entries", new JsonArray());
        history.addProperty("position", 0);
        scene.add("history", history);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        JsonObject state = new JsonObject();
        state.add("replayVisuals", visuals);
        state.addProperty("hideAllSpectators", true);
        state.add("scenes", scenes);
        state.addProperty("sceneIndex", 0);
        return new GsonBuilder().create().toJson(state);
    }

    private static JsonObject track(String type, JsonObject keys) {
        JsonObject t = new JsonObject();
        t.addProperty("enabled", true);
        t.add("customName", null);
        t.addProperty("customColour", 0);
        t.addProperty("keyframeType", type);
        t.add("keyframesByTick", keys);
        return t;
    }

    @Nullable
    private static Consumer<Boolean> flawless;
    private static boolean flawlessSearched;

    /**
     * Кадр — только когда мир в нём построен: «flawless frames» Sodium (открытый {@code FlawlessFrames.getProvider()};
     * Flashback включает его точкой входа Fabric, а на NeoForge Sodium её не читает — включаем сами). Без Sodium —
     * ничего: недостроенные кадры видит проверка.
     */
    @SuppressWarnings("unchecked")
    static void flawless(boolean on) {
        if (!flawlessSearched) {
            flawlessSearched = true;
            try {
                var provider = (Function<String, Consumer<Boolean>>) Class.forName("net.caffeinemc.mods.sodium.client.util.FlawlessFrames")
                        .getMethod("getProvider").invoke(null);
                flawless = provider.apply("replayscan film");
            } catch (ReflectiveOperationException | RuntimeException e) {
                flawless = null;
            }
        }
        if (flawless != null) flawless.accept(on);
    }
}
