package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.scenario.trailer.NewisleData.Frame;
import ua.zentix.incoming.scenario.trailer.NewisleData.Strike;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Данные съёмки Newisle без игры: точки как у {@code scout.py}, путь камеры по кадрам разведки, расписание пусков и сами
 * дубли ({@code tools/trailer/scenes/newisle-takes.json}): что каждый план монтажа снимает какой-то дубль, что действия,
 * места, пути и моменты дублей постановщик знает.
 */
class NewisleDataTest {
    /** Верх колонки — 70, земля под домом — 64. */
    private static final NewisleData.Heights HEIGHTS = new NewisleData.Heights() {
        @Override
        public double top(double x, double z) {
            return 70;
        }

        @Override
        public double ground(double x, double z) {
            return 64;
        }
    };

    private static NewisleData synthetic() {
        JsonObject scenario = JsonParser.parseString("""
                {"places": {"roof": {"at": [10, 20], "above": 3}, "cellar": {"at": [1, 2], "ground": -4}, "abs": [5, 80, 6],
                            "flat": [7, 8]},
                 "routes": {"hook": {"from": [-1000, 0], "via": [[-500, 300], [-200, 300]], "to": "flat"}},
                 "plans": [{"id": "p1", "strikes": [{"weapon": "missile", "route": "hook", "eta": 4}]}]}
                """).getAsJsonObject();
        JsonObject takes = JsonParser.parseString("""
                {"places": {"roof": [11, 21]}, "limits": {"flights_wait_s": 10}, "takes": []}
                """).getAsJsonObject();
        Map<String, List<Frame>> poses = new LinkedHashMap<>();
        poses.put("p1", List.of(new Frame(0, new Vec3(0, 100, 0), 350, 10, 60), new Frame(1, new Vec3(10, 100, 0), 10, 20, 70)));
        return new NewisleData(scenario, takes, poses);
    }

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    void pointsLikeScout() {
        NewisleData d = synthetic();
        assertEquals(new Vec3(1, 72, 2), d.point(json("[1, 2]"), 2, HEIGHTS), "[x, z] — над верхом на above");
        assertEquals(new Vec3(1, 5, 2), d.point(json("[1, 5, 2]"), 2, HEIGHTS), "[x, y, z] — как есть");
        assertEquals(new Vec3(1, 75, 2), d.point(json("{\"at\": [1, 2], \"above\": 5}"), 2, HEIGHTS));
        assertEquals(new Vec3(1, 60, 2), d.point(json("\"cellar\""), 0, HEIGHTS), "{at, ground} — от земли под домом");
        assertEquals(new Vec3(5, 80, 6), d.point(json("\"abs\""), 0, HEIGHTS));
        // место дублей перекрывает место сценария
        assertEquals(new Vec3(11, 70, 21), d.point(json("\"roof\""), 0, HEIGHTS));
        assertEquals(new Vec3(0, 100, 0), d.point(json("\"camera:p1\""), 0, HEIGHTS), "camera:<план> — глаз первого кадра");
        assertEquals(new Vec3(7, 71, 8), d.point(json("{\"place\": \"flat\", \"above\": 1}"), 0, HEIGHTS));
    }

    @Test
    void lineOfFeet() {
        List<Vec3> l = synthetic().line(json("{\"line\": [0, 0], \"step\": [2, -1], \"n\": 3}"), HEIGHTS);
        assertEquals(List.of(new Vec3(0, 70, 0), new Vec3(2, 70, -1), new Vec3(4, 70, -2)), l);
    }

    @Test
    void poseBetweenFramesTurnsTheShortWay() {
        List<Frame> f = synthetic().poses.get("p1");
        Pose mid = NewisleData.poseAt(f, 10);
        assertEquals(5, mid.pos().x, 1e-6);
        assertEquals(0, Math.floorMod(Math.round(mid.yaw()), 360), "350° → 10° через 0°, не через 180°");
        assertEquals(15, mid.pitch(), 1e-4);
        assertEquals(65, mid.fov(), 1e-6);
        assertEquals(new Vec3(0, 100, 0), NewisleData.poseAt(f, -5).pos(), "до первого кадра — первый кадр");
        // после последнего — дальше с той же скоростью
        assertEquals(20, NewisleData.poseAt(f, 40).pos().x, 1e-6);
    }

    @Test
    void lengthUpToPassPoint() {
        List<Vec3> path = List.of(new Vec3(0, 0, 0), new Vec3(100, 50, 0), new Vec3(100, 0, 100));
        assertEquals(200, NewisleData.lengthTo(path, null), 1e-9);
        assertEquals(150, NewisleData.lengthTo(path, new Vec3(110, 0, 50)), 1e-9, "до ближайшей точки пути, по горизонтали");
    }

    @Test
    void longRoutesLaunchFirstAndArriveTogether() {
        NewisleData d = synthetic();
        Map<String, Double> speeds = Map.of("missile", 4.0);
        Strike near = new Strike(WeaponType.MISSILE, new Vec3(0, 0, 0), List.of(), new Vec3(400, 0, 0), null, 5);
        Strike far = new Strike(WeaponType.MISSILE, new Vec3(0, 0, 0), List.of(), new Vec3(1600, 0, 0), null, 5);
        // 400 блоков — 5 с, 1600 — 20 с: дальний пущен за 15 с до начала плана — он опорный, план ждёт
        NewisleData.Schedule s = NewisleData.schedule(List.of(near, far), speeds, false);
        assertEquals(1, s.reference());
        assertTrue(s.waits());
        assertEquals(0, s.launchAt()[0], 1e-9);
        assertEquals(-15, s.launchAt()[1], 1e-9);
        // ближний — когда опорному до удара 5 с
        assertEquals(5, s.threshold(0, List.of(near, far)), 1e-9);
        // все пуски после начала плана — без ожидания
        Strike late = new Strike(WeaponType.MISSILE, new Vec3(0, 0, 0), List.of(), new Vec3(40, 0, 0), null, 5);
        NewisleData.Schedule l = NewisleData.schedule(List.of(late), speeds, false);
        assertFalse(l.waits());
        assertEquals(4.5, l.launchAt()[0], 1e-9);
        // путь залпа: через точки, цель — из пути; место пуска и точки — по x и z, без мира
        Strike route = d.strikes(json("{\"plans\": [\"p1\"], \"offsets\": {}}").getAsJsonObject(), HEIGHTS).get(0);
        assertEquals(new Vec3(-1000, 70, 0), route.from(), "место пуска — на высоте цели, без мира");
        assertEquals(2, route.via().size());
        assertEquals(new Vec3(7, 70, 8), route.to());
        // свои точки у удара без пути сценария, цель — точкой в мире как есть
        Strike own = d.strike(json("{\"weapon\": \"drone\", \"from\": [-300, 1100], \"via\": [[-250, 640]],"
                + " \"to\": [-200, 90, 0], \"eta\": 9.5}").getAsJsonObject(), 0, null, HEIGHTS);
        assertEquals(List.of(new Vec3(-250, 0, 640)), own.via());
        assertEquals(new Vec3(-200, 90, 0), own.to());
        assertEquals(new Vec3(-300, 90, 1100), own.from(), "место пуска — на высоте цели");
    }

    // ================================================================ дубли Newisle

    private static NewisleData real() throws IOException {
        return NewisleData.load(NewisleData.scenes(Path.of("").toAbsolutePath()));
    }

    @Test
    void everyCutPlanIsShotByATake() throws IOException {
        NewisleData d = real();
        Path dir = NewisleData.scenes(Path.of("").toAbsolutePath());
        JsonObject sheet = JsonParser.parseString(Files.readString(dir.resolve("newisle-animatic.json"))).getAsJsonObject();
        Set<String> shot = new HashSet<>();
        for (JsonObject t : d.takeList()) shot.addAll(NewisleData.plansOf(t));
        List<String> missing = new ArrayList<>();
        for (JsonElement e : sheet.getAsJsonArray("shots")) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("plan") && !shot.contains(s.get("plan").getAsString())) missing.add(s.get("plan").getAsString());
        }
        assertTrue(missing.isEmpty(), "планы монтажа без дубля: " + missing);
    }

    @Test
    void takesUseOnlyKnownActionsPlacesAndMoments() throws IOException {
        NewisleData d = real();
        List<String> bad = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonObject t : d.takeList()) {
            String id = t.get("id").getAsString();
            if (!ids.add(id)) bad.add(id + ": дубль дважды");
            assertNotNull(d.takes.getAsJsonObject("clock").get(t.get("clock").getAsString()), id + ": нет часов " + t.get("clock"));
            for (String key : new String[]{"stage", "after"}) {
                for (JsonElement a : list(t, key)) {
                    String what = a.getAsJsonObject().get("do").getAsString();
                    if (!NewisleData.STEPS.contains(what) && !NewisleData.INSTANT.contains(what)) bad.add(id + " " + key + ": " + what);
                    places(d, id, a.getAsJsonObject(), bad);
                }
            }
            for (JsonElement e : t.getAsJsonArray("shots")) {
                JsonObject s = e.getAsJsonObject();
                for (String key : new String[]{"cues", "ready", "end"}) {
                    for (JsonElement a : list(s, key)) {
                        String what = a.getAsJsonObject().get("do").getAsString();
                        if (!NewisleData.INSTANT.contains(what)) bad.add(id + " " + key + ": шагом нельзя — " + what);
                        JsonElement at = a.getAsJsonObject().get("at");
                        if (key.equals("cues") && (at == null || !at.isJsonPrimitive() || !at.getAsJsonPrimitive().isNumber())) {
                            bad.add(id + " cues: " + what + " — «at» у метки только секунды, место — «look»");
                        }
                        places(d, id, a.getAsJsonObject(), bad);
                    }
                }
                if (s.has("when") && !NewisleData.MOMENTS.contains(s.getAsJsonObject("when").get("kind").getAsString())) {
                    bad.add(id + ": момент " + s.getAsJsonObject("when"));
                }
                try {
                    for (Strike k : d.strikes(s, HEIGHTS)) assertNotNull(k.weapon());
                } catch (RuntimeException ex) {
                    bad.add(id + ": удары — " + ex.getMessage());
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void chainStartsFromTakesBeforeIt() throws IOException {
        List<String> seen = new ArrayList<>(), bad = new ArrayList<>();
        for (JsonObject t : real().takeList()) {
            String from = t.get("from").getAsString(), id = t.get("id").getAsString();
            if (from.startsWith("branch:") && !seen.contains(from.substring(7))) bad.add(id + " ← " + from);
            else if (!from.equals("chain") && !from.equals("intact") && !from.equals("master") && !from.startsWith("branch:")) bad.add(id + ": " + from);
            seen.add(id);
        }
        assertTrue(bad.isEmpty(), "дубль от снимка, которого ещё нет: " + bad);
    }

    @Test
    void coverageTableFromTakes() throws IOException {
        NewisleData d = real();
        Map<String, Coverage.Spec> c = NewisleCoverage.build(d);
        assertEquals("n52", c.get("n52").replay());
        assertEquals("n52", c.get("n43").replay(), "второй план той же записи — её повтор");
        assertTrue(c.get("n43").offset() > 0, "и со своего места в ней");
        assertEquals("n18", c.get("n18").replay(), "сцена дирижабля пишет повтор под именем плана");
        assertEquals("n18", c.get("n18b").replay(), "падение дирижабля — второй план записи n18");
        assertEquals(70, c.get("n18b").offset(), "встык за куском n18: 3,5 с от начала окна");
        for (Coverage.Spec s : c.values()) assertFalse(s.kinds().isEmpty(), s.shot());
    }

    private static JsonArray list(JsonObject o, String key) {
        return o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
    }

    /** Места действия по имени есть в местах дублей или сценария, пути — в путях сценария. */
    private static void places(NewisleData d, String take, JsonObject a, List<String> bad) {
        for (String k : new String[]{"at", "look", "to", "from", "toward", "around", "target", "at_place", "near", "silo"}) {
            if (!a.has(k) || !a.get(k).isJsonPrimitive() || a.get(k).getAsJsonPrimitive().isNumber()) continue;
            String name = a.get(k).getAsString();
            if (name.startsWith("camera:")) continue;
            if (d.place(name) == null) bad.add(take + " " + a.get("do").getAsString() + ": нет места " + name);
        }
        if (a.has("route") && !d.scenario.getAsJsonObject("routes").has(a.get("route").getAsString())) {
            bad.add(take + ": нет пути " + a.get("route"));
        }
    }
}
