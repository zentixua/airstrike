package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные съёмки Newisle из {@code tools/trailer/scenes}: сценарий ({@code newisle.json}: места, планы, пути залпа), дубли
 * ({@code newisle-takes.json}: что ставит каждый дубль и какие планы пишет) и позы камер разведки
 * ({@code newisle-poses.json}, выход {@code scout.py}). Без мира: высоты колонок — через {@link Heights}, поэтому
 * разбор точек, путь камеры по кадрам разведки и расписание пусков проверяются юнит-тестами.
 */
final class NewisleData {
    /** Высоты колонки: {@code top} — первый воздух над твёрдым верхом (крыша, без листвы), {@code ground} — над землёй под домом. */
    interface Heights {
        double top(double x, double z);

        double ground(double x, double z);
    }

    /** Высоты без мира (0): место пуска крылатых снарядов и точки пути — только по x и z, далёкие чанки не грузятся. */
    static final Heights FLAT = new Heights() {
        @Override
        public double top(double x, double z) {
            return 0;
        }

        @Override
        public double ground(double x, double z) {
            return 0;
        }
    };

    /** Кадр разведки: секунда плана, глаз, курс, тангаж, угол кадра по вертикали. */
    record Frame(double t, Vec3 eye, float yaw, float pitch, double fov) {}

    /**
     * Удар плана: пуск из {@code from} через точки {@code via} в {@code to}; {@code pass} — точка пролёта (тогда {@code eta}
     * — секунда плана, когда снаряд у неё, а не удар).
     */
    record Strike(WeaponType weapon, Vec3 from, List<Vec3> via, Vec3 to, @Nullable Vec3 pass, double eta) {
        /** Путь пуска: место, точки, цель. */
        List<Vec3> path() {
            List<Vec3> p = new ArrayList<>();
            p.add(from);
            p.addAll(via);
            p.add(to);
            return p;
        }

        /** Сколько секунд снаряд идёт от пуска до момента плана (удар или точка пролёта) — по маршевой скорости паспорта. */
        double flight(Map<String, Double> speeds) {
            if (weapon == WeaponType.ROCKET) {
                return Ballistics.ticksFor(from, to, LauncherEntity.elevation(WeaponType.ROCKET), RocketEntity.MIN_FLIGHT) / 20.0;
            }
            double v = speed(speeds);
            return lengthTo(path(), pass) / v / 20;
        }

        /** От точки пролёта до удара, секунд (без точки — 0). */
        double afterPass(Map<String, Double> speeds) {
            if (pass == null) return 0;
            return (lengthTo(path(), null) - lengthTo(path(), pass)) / speed(speeds) / 20;
        }

        private double speed(Map<String, Double> speeds) {
            Double v = speeds.get(weapon.getSerializedName());
            return v != null ? v : weapon.spec().airframe().cruiseSpeed();
        }
    }

    /**
     * Пуски плана по одному времени: {@code launchAt[i]} — секунда плана, когда пускать удар {@code i} (меньше нуля — до
     * начала плана), {@code reference} — удар, пущенный первым: по его времени до удара ({@code ClientFlights}) идут пуски
     * остальных и начало плана. {@code waits} — план ждёт момента (иначе пуски — репликами плана с его начала).
     */
    record Schedule(int reference, double[] launchAt, double[] flight, boolean waits) {
        /** Пускать удар {@code i}, когда у опорного осталось столько секунд до его момента (удар или точка пролёта). */
        double threshold(int i, List<Strike> strikes) {
            return strikes.get(reference).eta() - launchAt[i];
        }
    }

    /** Действия, которые идут сразу (реплика записи, {@code ready}, {@code end}; в сцене — тоже). */
    static final java.util.Set<String> INSTANT = java.util.Set.of("campfire", "lamps", "crowd.watch", "crowd.cower", "crowd.flee",
            "crowd.follow", "crowd.remove", "launchers.fire", "launcher.redstone", "sam", "sam.clear", "gunners.fire", "gunners.stop",
            "fight.arm", "fight.start", "fight.stop", "fight.remove", "silo.open", "silo.close", "nuke.order", "nuke.key", "salvo", "scope",
            "map.open", "map.route", "map.close", "retarget");
    /** Действия сцены — шагами (ждут, ставят людей и постройки): только в {@code stage} и {@code after} дубля. */
    static final java.util.Set<String> STEPS = java.util.Set.of("wait", "settle", "flights.wait", "crowd", "gunners", "fight",
            "launchers", "silo.build", "commander", "player", "hidden", "boat", "airship", "grid.node", "station.wire");
    /** Моменты записи ({@code when.kind}). */
    static final java.util.Set<String> MOMENTS = java.util.Set.of("none", "siren", "lit", "interceptor", "onboard", "warning", "front",
            "icbm");

    final JsonObject scenario, takes;
    /** Кадры камеры плана по разведке; нет плана — нет его позы (конвейер останавливается до съёмки). */
    final Map<String, List<Frame>> poses;

    /** Что нашли поиски P1 ({@code newisle-found.json}, пишет {@code produce.py search}): места, размер залпа «Града». */
    final JsonObject found;

    NewisleData(JsonObject scenario, JsonObject takes, Map<String, List<Frame>> poses) {
        this(scenario, takes, poses, new JsonObject());
    }

    NewisleData(JsonObject scenario, JsonObject takes, Map<String, List<Frame>> poses, JsonObject found) {
        this.scenario = scenario;
        this.takes = takes;
        this.poses = poses;
        this.found = found;
    }

    /** Папка сцен: от каталога игры вверх до {@code tools/trailer/scenes} репозитория; {@code INCOMING_NEWISLE_SCENES} — своя. */
    static Path scenes(Path from) {
        String env = System.getenv("INCOMING_NEWISLE_SCENES");
        if (env != null && !env.isBlank()) return Path.of(env);
        for (Path p = from.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            Path s = p.resolve("tools/trailer/scenes");
            if (Files.isRegularFile(s.resolve("newisle-takes.json"))) return s;
        }
        throw new IllegalStateException("нет tools/trailer/scenes над " + from);
    }

    static NewisleData load(Path dir) throws IOException {
        JsonObject scenario = read(dir.resolve("newisle.json")).getAsJsonObject();
        JsonObject takes = read(dir.resolve("newisle-takes.json")).getAsJsonObject();
        Map<String, List<Frame>> poses = new LinkedHashMap<>();
        Path posesFile = dir.resolve("newisle-poses.json");
        if (Files.isRegularFile(posesFile)) {
            for (JsonElement e : read(posesFile).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                if (!o.has("frames") || o.has("ok") && !o.get("ok").getAsBoolean()) continue;
                List<Frame> frames = new ArrayList<>();
                for (JsonElement f : o.getAsJsonArray("frames")) {
                    JsonObject fo = f.getAsJsonObject();
                    frames.add(new Frame(fo.get("t").getAsDouble(), vec(fo.getAsJsonArray("eye")), fo.get("yaw").getAsFloat(),
                            fo.get("pitch").getAsFloat(), fo.has("fov") ? fo.get("fov").getAsDouble() : 70));
                }
                if (!frames.isEmpty()) poses.put(o.get("id").getAsString(), frames);
            }
        }
        Path foundFile = dir.resolve("newisle-found.json");
        JsonObject found = Files.isRegularFile(foundFile) ? read(foundFile).getAsJsonObject() : new JsonObject();
        return new NewisleData(scenario, takes, poses, found);
    }

    private static JsonElement read(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
    }

    static Vec3 vec(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    // ================================================================ дубли и планы

    List<JsonObject> takeList() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : takes.getAsJsonArray("takes")) out.add(e.getAsJsonObject());
        return out;
    }

    @Nullable
    JsonObject take(String id) {
        for (JsonObject t : takeList()) if (t.get("id").getAsString().equals(id)) return t;
        return null;
    }

    @Nullable
    JsonObject plan(String id) {
        for (JsonElement e : scenario.getAsJsonArray("plans")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("id").getAsString().equals(id)) return p;
        }
        return null;
    }

    JsonObject limits() {
        return takes.getAsJsonObject("limits");
    }

    /** Маршевые скорости снарядов для расписания (блоков за тик), если замеры их поправили: {@code limits.speeds}. */
    Map<String, Double> speeds() {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonObject l = limits();
        if (l.has("speeds")) for (var e : l.getAsJsonObject("speeds").entrySet()) out.put(e.getKey(), e.getValue().getAsDouble());
        return out;
    }

    /** Время суток дубля для {@code /time set}. */
    long clock(String name) {
        return takes.getAsJsonObject("clock").get(name).getAsLong();
    }

    /** Планы, которые камера дубля пишет: все {@code plans} его записей (и план дубля целиком, как {@code n18}). */
    static List<String> plansOf(JsonObject take) {
        List<String> out = new ArrayList<>();
        for (JsonElement s : take.getAsJsonArray("shots")) {
            for (JsonElement p : s.getAsJsonObject().getAsJsonArray("plans")) out.add(p.getAsString());
        }
        if (take.has("cover")) for (String p : take.getAsJsonObject("cover").keySet()) if (!out.contains(p)) out.add(p);
        return out;
    }

    /** Сдвиг плана от начала записи, секунд ({@code offsets} записи; первый план — 0). */
    static double offset(JsonObject shot, String plan) {
        return shot.has("offsets") && shot.getAsJsonObject("offsets").has(plan) ? shot.getAsJsonObject("offsets").get(plan).getAsDouble() : 0;
    }

    // ================================================================ точки

    /** Место по имени: сперва найденное поисками, потом места дублей, потом сценария. */
    @Nullable
    JsonElement place(String name) {
        if (found.has("places") && found.getAsJsonObject("places").has(name)) return found.getAsJsonObject("places").get(name);
        JsonObject tp = takes.getAsJsonObject("places");
        if (tp.has(name)) return tp.get(name);
        JsonObject sp = scenario.getAsJsonObject("places");
        return sp.has(name) ? sp.get(name) : null;
    }

    /**
     * Точка сценария → место мира, как у {@code scout.py}: {@code [x, z]} — над верхом колонки на {@code above}, {@code [x, y, z]}
     * — как есть, {@code {at, above}} — над верхом, {@code {at, ground}} — над землёй (под домом тоже), имя места, а
     * {@code camera:<план>} — глаз первого кадра разведки плана.
     */
    Vec3 point(JsonElement spec, double above, Heights h) {
        if (spec.isJsonPrimitive()) {
            String name = spec.getAsString();
            if (name.startsWith("camera:")) {
                List<Frame> f = poses.get(name.substring("camera:".length()));
                if (f == null) throw new IllegalArgumentException("нет позы камеры " + name);
                return f.get(0).eye();
            }
            JsonElement p = place(name);
            if (p == null) throw new IllegalArgumentException("нет места " + name);
            return point(p, above, h);
        }
        if (spec.isJsonArray()) {
            JsonArray a = spec.getAsJsonArray();
            if (a.size() == 3) return vec(a);
            double x = a.get(0).getAsDouble(), z = a.get(1).getAsDouble();
            return new Vec3(x, h.top(x, z) + above, z);
        }
        JsonObject o = spec.getAsJsonObject();
        if (o.has("place")) {
            JsonObject merged = new JsonObject();
            JsonElement base = place(o.get("place").getAsString());
            if (base == null) throw new IllegalArgumentException("нет места " + o.get("place"));
            if (base.isJsonObject()) for (var e : base.getAsJsonObject().entrySet()) merged.add(e.getKey(), e.getValue());
            else merged.add("at", base);
            for (var e : o.entrySet()) if (!e.getKey().equals("place")) merged.add(e.getKey(), e.getValue());
            if (!merged.get("at").isJsonArray() || merged.getAsJsonArray("at").size() == 3) return point(merged.get("at"), above, h);
            o = merged;
        }
        JsonArray at = o.getAsJsonArray("at");
        double x = at.get(0).getAsDouble(), z = at.get(1).getAsDouble();
        if (o.has("ground")) return new Vec3(x, h.ground(x, z) + o.get("ground").getAsDouble(), z);
        return new Vec3(x, h.top(x, z) + (o.has("above") ? o.get("above").getAsDouble() : above), z);
    }

    /** Ряд мест {@code {line: [x, z], step: [dx, dz], n}} (ноги на верху колонки); точка — один ряд из неё. */
    List<Vec3> line(JsonElement spec, Heights h) {
        JsonElement s = spec.isJsonPrimitive() && place(spec.getAsString()) != null ? place(spec.getAsString()) : spec;
        if (!(s.isJsonObject() && s.getAsJsonObject().has("line"))) return List.of(point(s, 0, h));
        JsonObject o = s.getAsJsonObject();
        JsonArray a = o.getAsJsonArray("line"), st = o.getAsJsonArray("step");
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < o.get("n").getAsInt(); i++) {
            double x = a.get(0).getAsDouble() + i * st.get(0).getAsDouble(), z = a.get(1).getAsDouble() + i * st.get(1).getAsDouble();
            out.add(new Vec3(x, h.top(x, z), z));
        }
        return out;
    }

    /** Рамка {@code {min: [x, y, z], max: [x, y, z]}} по имени места. */
    Vec3[] box(JsonElement spec) {
        JsonObject o = (spec.isJsonPrimitive() ? place(spec.getAsString()) : spec).getAsJsonObject();
        return new Vec3[]{vec(o.getAsJsonArray("min")), vec(o.getAsJsonArray("max"))};
    }

    // ================================================================ камера по кадрам разведки

    /**
     * Поза камеры на тике {@code tick} плана по кадрам разведки: между кадрами — место сплайном Катмулла — Рома, курс
     * кратчайшим поворотом, тангаж и угол кадра — по прямой; до первого — первый, после последнего — дальше тем же ходом
     * (путь камеры длиннее кадров разведки, план длиннее её {@code dur}), взгляд — последний.
     */
    static Pose poseAt(List<Frame> frames, double tick) {
        double t = tick / 20.0;
        Frame first = frames.get(0);
        if (frames.size() == 1 || t <= first.t()) return new Pose(first.eye(), first.yaw(), first.pitch(), 0, first.fov());
        Frame last = frames.get(frames.size() - 1);
        if (t >= last.t()) {
            Frame prev = frames.get(frames.size() - 2);
            double dt = last.t() - prev.t();
            Vec3 v = dt <= 0 ? Vec3.ZERO : last.eye().subtract(prev.eye()).scale(1 / dt);
            return new Pose(last.eye().add(v.scale(t - last.t())), last.yaw(), last.pitch(), 0, last.fov());
        }
        int i = 0;
        while (i < frames.size() - 2 && frames.get(i + 1).t() <= t) i++;
        Frame a = frames.get(i), b = frames.get(i + 1);
        double u = (t - a.t()) / Math.max(1e-9, b.t() - a.t());
        Vec3 p0 = frames.get(Math.max(0, i - 1)).eye(), p3 = frames.get(Math.min(frames.size() - 1, i + 2)).eye();
        Vec3 pos = catmull(p0, a.eye(), b.eye(), p3, u);
        float yaw = a.yaw() + Mth.wrapDegrees(b.yaw() - a.yaw()) * (float) u;
        return new Pose(pos, yaw, (float) Mth.lerp(u, a.pitch(), b.pitch()), 0, Mth.lerp(u, a.fov(), b.fov()));
    }

    static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double f) {
        double f2 = f * f, f3 = f2 * f;
        return p1.scale(2).add(p2.subtract(p0).scale(f)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(f2))
                .add(p3.subtract(p0).add(p1.scale(3)).subtract(p2.scale(3)).scale(f3)).scale(0.5);
    }

    // ================================================================ удары и расписание

    /**
     * Удары записи: свои ({@code strikes} записи в дублях) или планов из сценария (их {@code eta} — от начала своего
     * плана, к ним — сдвиг плана в записи).
     */
    List<Strike> strikes(JsonObject shot, Heights h) {
        List<Strike> out = new ArrayList<>();
        if (shot.has("strikes")) {
            for (JsonElement e : shot.getAsJsonArray("strikes")) out.add(strike(e.getAsJsonObject(), 0, null, h));
            return out;
        }
        for (JsonElement p : shot.getAsJsonArray("plans")) {
            JsonObject plan = plan(p.getAsString());
            if (plan == null || !plan.has("strikes")) continue;
            double off = offset(shot, p.getAsString());
            // удар плана без цели — в цель плана
            JsonArray list = plan.getAsJsonArray("strikes");
            // залп «Града», который сервер держит на 20 TPS (поиск grad): первые столько ударов плана
            int n = list.size();
            if (found.has("grad") && found.getAsJsonObject("grad").get("plan").getAsString().equals(p.getAsString())) {
                n = Math.min(n, found.getAsJsonObject("grad").get("size").getAsInt());
            }
            for (int i = 0; i < n; i++) out.add(strike(list.get(i).getAsJsonObject(), off, plan.get("target"), h));
        }
        return out;
    }

    Strike strike(JsonObject s, double offset, @Nullable JsonElement planTarget, Heights h) {
        WeaponType w = WeaponType.parse(s.get("weapon").getAsString());
        if (w == null) throw new IllegalArgumentException("нет оружия " + s.get("weapon"));
        Vec3 from;
        List<Vec3> via = new ArrayList<>();
        JsonElement to = s.get("to");
        if (s.has("route")) {
            JsonObject r = scenario.getAsJsonObject("routes").getAsJsonObject(s.get("route").getAsString());
            if (r == null) throw new IllegalArgumentException("нет пути " + s.get("route"));
            from = point(r.get("from"), 0, FLAT);
            for (JsonElement v : r.getAsJsonArray("via")) {
                JsonArray a = v.getAsJsonArray();
                via.add(new Vec3(a.get(0).getAsDouble(), 0, a.get(1).getAsDouble()));
            }
            if (to == null) to = r.get("to");
        } else {
            from = point(s.get("from"), 0, FLAT);
        }
        if (to == null) to = planTarget;
        if (to == null) throw new IllegalArgumentException("у удара нет цели: " + s);
        Vec3 target = point(to, 0, h);
        // место пуска — только по x и z (команде залпа так и надо, а высота у сервера грузила бы чанк в 600 блоках: там
        // встала бы пусковая, и пуски пошли бы её очередью); баллистике «Града» — на высоте цели
        if (from.y == 0) from = new Vec3(from.x, target.y, from.z);
        Vec3 pass = s.has("pass") ? point(s.get("pass"), 0, h) : null;
        return new Strike(w, from, via, target, pass, s.get("eta").getAsDouble() + offset);
    }

    /**
     * Длина пути по горизонтали до точки пути, ближайшей к {@code pass} (null — до конца): снаряд идёт по местности, и
     * по высоте путь не считается.
     */
    static double lengthTo(List<Vec3> path, @Nullable Vec3 pass) {
        double total = 0, bestD = Double.MAX_VALUE, bestLen = 0;
        for (int i = 1; i < path.size(); i++) {
            Vec3 a = path.get(i - 1), b = path.get(i);
            double dx = b.x - a.x, dz = b.z - a.z, len = Math.hypot(dx, dz);
            if (pass != null && len > 0) {
                double u = Mth.clamp(((pass.x - a.x) * dx + (pass.z - a.z) * dz) / (len * len), 0, 1);
                double d = Math.hypot(a.x + dx * u - pass.x, a.z + dz * u - pass.z);
                if (d < bestD) {
                    bestD = d;
                    bestLen = total + len * u;
                }
            }
            total += len;
        }
        return pass == null ? total : bestLen;
    }

    /**
     * Расписание пусков: удар {@code i} пускается за время своего полёта до своего момента ({@code eta − полёт}); первый
     * пуск — опорный. Хоть один пуск до начала плана (полёт длиннее его момента) — план ждёт, пока опорному останется
     * его {@code eta}, а остальные пускаются по времени опорного; все после начала — план без ожидания, пуски — репликами.
     * {@code explicitWhen} — момент плана задан сам (тревога, поджиг): пуски — по опорному, начало — по нему.
     */
    static Schedule schedule(List<Strike> strikes, Map<String, Double> speeds, boolean explicitWhen) {
        int n = strikes.size();
        double[] at = new double[n], flight = new double[n];
        int ref = 0;
        for (int i = 0; i < n; i++) {
            flight[i] = strikes.get(i).flight(speeds);
            at[i] = strikes.get(i).eta() - flight[i];
            if (at[i] < at[ref]) ref = i;
        }
        boolean waits = n > 0 && (explicitWhen || at[ref] < 0);
        return new Schedule(ref, at, flight, waits);
    }
}
