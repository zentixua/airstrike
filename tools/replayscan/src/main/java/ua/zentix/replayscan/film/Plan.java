package ua.zentix.replayscan.film;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * План съёмки (README.md, «Съёмка»): повтор, куда выводить, размер кадра и планы. Пишет его {@code tools/clips/plan.py}
 * из данных разбора; съёмка ничего не решает наугад — что снимать, когда и откуда, задано здесь, а камеру на месте
 * выбирает оценка по миру повтора.
 *
 * @param replay  повтор (zip, копия)
 * @param out     каталог вывода: {@code <план>/<камера>/video.mkv}, проверка кадров, отчёт
 * @param ready   доля кадров с построенным миром, ниже которой план не годится (0 — мир не проверяется: облако без
 *                видеокарты строит секции медленнее кадров)
 * @param clouds  низ облаков (шейдерпак ставит свой слой); NaN — ванильные облака измерения
 * @param distance прорисовка, чанков
 * @param loud    id громких звуков (взрывы): какие из них за кадром — считается
 * @param engine  id звуков моторов: слышен ли мотор объекта
 */
record Plan(Path replay, Path out, int width, int height, double fps, int bitrate, int tries, double ready, double clouds, int distance,
            Pattern loud, Pattern engine, List<Shot> shots) {

    /**
     * План ролика: отрезок тиков повтора, объект, камеры по порядку (первые — заданные, дальше — лучшие по оценке) и
     * пороги проверки.
     *
     * @param warm тиков живого повтора перед началом (дым и огонь прежних ударов — в кадре, как в игре)
     * @param hold тиков стоять у камеры после постройки мира (Distant Horizons строит дальний рельеф после перемотки)
     * @param far  объект дальше прорисовки: туман у её края и секции объекта не проверяются, луч — по готовым чанкам
     * @param smoke прежние удары {@code [t, x, y, z]} — столбы дыма для оценки камер до вывода
     */
    record Shot(String name, int a, int b, String dim, int warm, int hold, boolean far, List<Camera> cameras, Subject subject,
                List<double[]> smoke, Need need) {}

    /**
     * Камера: {@code pov} — глазами игрока ({@code player}: {@code recorder}, ник или UUID); {@code fixed} — неподвижная
     * в {@code eye}, смотрит в точку {@code aim} или ведёт объект ({@code follow}); {@code tripod} — штатив по кругу
     * у {@code around}: расстояния {@code dist}, углы над точкой {@code elev}, шаг по кругу {@code azStep}, объектив —
     * так, чтобы событие поперечником {@code size} было {@code share} ширины кадра.
     */
    record Camera(String kind, @Nullable String player, boolean gui, double fov, @Nullable Vec3 eye, @Nullable Vec3 aim, boolean follow,
                  @Nullable Vec3 around, @Nullable Vec3 heading, double[] dist, double[] elev, double azStep, double share, double size) {}

    /**
     * Пороги проверки по кадрам: доля кадров до первого события, где объект снят ({@code subject}), то же за последнюю
     * секунду до него ({@code near}), доля событий, снятых в первые 12 тиков ({@code keys}), наименьшая доля ширины кадра
     * ({@code share}), доля кадров со слышным мотором ({@code engine}), громкого за кадром не больше {@code loud} громкого
     * в кадре.
     */
    record Need(double subject, double near, double keys, double share, double engine, double loud) {}

    static Plan read(Path file) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        List<Shot> shots = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("shots")) shots.add(shot(el.getAsJsonObject()));
        return new Plan(Path.of(o.get("replay").getAsString()).toAbsolutePath(), Path.of(o.get("out").getAsString()).toAbsolutePath(),
                num(o, "width", 1080), num(o, "height", 1920), dbl(o, "fps", 60), num(o, "bitrate", 40_000_000), num(o, "tries", 3),
                dbl(o, "ready", 0.98), o.has("clouds") && !o.get("clouds").isJsonNull() ? o.get("clouds").getAsDouble() : Double.NaN,
                num(o, "distance", 32), Pattern.compile(str(o, "loud", "(?i)blast|explo|boom|detonat|nuke")),
                Pattern.compile(str(o, "engine", "(?i)engine")), shots);
    }

    private static Shot shot(JsonObject s) {
        List<Camera> cams = new ArrayList<>();
        for (JsonElement el : s.getAsJsonArray("cameras")) cams.add(camera(el.getAsJsonObject()));
        List<double[]> smoke = new ArrayList<>();
        if (s.has("smoke")) {
            for (JsonElement el : s.getAsJsonArray("smoke")) smoke.add(arr(el.getAsJsonArray()));
        }
        JsonObject n = s.has("need") ? s.getAsJsonObject("need") : new JsonObject();
        Need need = new Need(dbl(n, "subject", 0.6), dbl(n, "near", 0.6), dbl(n, "keys", 1), dbl(n, "share", 0.01), dbl(n, "engine", 0),
                dbl(n, "loud", 1));
        return new Shot(s.get("name").getAsString(), s.get("a").getAsInt(), s.get("b").getAsInt(), str(s, "dim", "minecraft:overworld"),
                num(s, "warm", 200), num(s, "hold", 20), s.has("far") && s.get("far").getAsBoolean(), cams, subject(s.getAsJsonObject("subject")),
                smoke, need);
    }

    private static Camera camera(JsonObject c) {
        String kind = c.get("kind").getAsString();
        boolean subjectAim = c.has("aim") && c.get("aim").isJsonPrimitive();
        return new Camera(kind, c.has("player") ? c.get("player").getAsString() : null, c.has("gui") && c.get("gui").getAsBoolean(),
                dbl(c, "fov", 70), vec(c, "eye"), subjectAim ? null : vec(c, "aim"), subjectAim || c.has("follow") && c.get("follow").getAsBoolean(),
                vec(c, "around"), c.has("heading") ? new Vec3(arr(c.getAsJsonArray("heading"))[0], 0, arr(c.getAsJsonArray("heading"))[1]) : null,
                c.has("dist") ? arr(c.getAsJsonArray("dist")) : new double[]{60, 90, 130},
                c.has("elev") ? arr(c.getAsJsonArray("elev")) : new double[]{6, 16, 28}, dbl(c, "az_step", 30), dbl(c, "share", 0.25),
                dbl(c, "size", 0));
    }

    private static Subject subject(JsonObject s) {
        List<double[]> path = new ArrayList<>();
        if (s.has("path")) {
            for (JsonElement el : s.getAsJsonArray("path")) path.add(arr(el.getAsJsonArray()));
        }
        List<Subject.Key> keys = new ArrayList<>();
        if (s.has("keys")) {
            for (JsonElement el : s.getAsJsonArray("keys")) {
                JsonObject k = el.getAsJsonObject();
                double[] p = arr(k.getAsJsonArray("pos"));
                keys.add(new Subject.Key(k.get("t").getAsInt(), new Vec3(p[0], p[1], p[2]), dbl(k, "size", 16)));
            }
        }
        JsonObject area = s.has("area") ? s.getAsJsonObject("area") : null;
        return new Subject(s.has("uuid") && !s.get("uuid").isJsonNull() ? s.get("uuid").getAsString() : null, dbl(s, "size", 4), path, keys,
                area == null ? null : vec(area, "pos"), area == null ? 0 : dbl(area, "size", 0));
    }

    @Nullable
    private static Vec3 vec(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return null;
        double[] a = arr(o.getAsJsonArray(k));
        return new Vec3(a[0], a[1], a[2]);
    }

    private static double[] arr(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private static double dbl(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }
}
