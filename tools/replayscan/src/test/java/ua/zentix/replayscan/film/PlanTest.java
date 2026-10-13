package ua.zentix.replayscan.film;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** План съёмки в том виде, как его пишет {@code tools/clips/plan.py}, объект плана по тикам, кадр и ключи камеры. */
class PlanTest {
    private static final String PLAN = """
            {"replay": "r.zip", "out": "film", "width": 1080, "height": 1920, "fps": 60, "bitrate": 40000000, "tries": 3,
             "incoming": "2.10.3", "previews": {"rockets-0": ["rockets-0-impact"]},
             "shots": [{"name": "rockets-0-impact", "a": 100, "b": 260, "dim": "minecraft:overworld", "warm": 200, "hold": 20,
               "far": false, "why": "test",
               "cameras": [{"kind": "pov", "player": "player:ZentixUA", "fov": 70},
                           {"kind": "tripod", "around": [0, 64, 100], "dist": [60, 90], "elev": [6, 16], "share": 0.3, "follow": true, "size": 8},
                           {"kind": "fixed", "eye": [0, 80, 0], "aim": "subject", "fov": 12}],
               "subject": {"size": 3, "uuid": "00000000-0000-0000-0000-000000000001",
                           "path": [[120, 0, 80, 0], [160, 0, 72, 80], [140, 0, 76, 40]],
                           "keys": [{"t": 170, "pos": [0, 64, 100], "size": 8}]},
               "smoke": [[50, 30, 64, 100]],
               "need": {"subject": 0.4, "near": 0.5, "keys": 1, "share": 0.004}}]}
            """;

    private static Plan plan() throws Exception {
        Path f = Files.createTempFile("plan", ".json");
        Files.writeString(f, PLAN);
        return Plan.read(f);
    }

    @Test
    void readsPlanOfPlanPy() throws Exception {
        Plan p = plan();
        assertEquals(1080, p.width());
        assertEquals(1, p.shots().size());
        Plan.Shot s = p.shots().get(0);
        assertEquals(3, s.cameras().size());
        assertEquals("player:ZentixUA", s.cameras().get(0).player());
        assertTrue(s.cameras().get(1).follow());
        assertTrue(s.cameras().get(2).follow(), "aim: subject — камера ведёт объект");
        assertEquals(0.5, s.need().near());
        assertEquals(1, s.need().loud(), "громкого за кадром не больше, чем в кадре — по умолчанию");
        assertTrue(p.loud().matcher("incoming:blast.near").find());
        assertFalse(p.loud().matcher("incoming:drone.engine").find());
    }

    @Test
    void subjectFollowsPathThenEvent() throws Exception {
        Subject s = plan().shots().get(0).subject();
        assertEquals(170, s.firstKey());
        // путь отсортирован: 150 — между 140 и 160
        assertEquals(60, s.aim(150).z, 1e-9);
        assertEquals(3, s.aimSize(150));
        assertTrue(s.onProjectile(150));
        // до появления — место первой точки пути
        assertEquals(0, s.aim(100).z, 1e-9);
        // событие: смотрим на него, шар поднимается
        assertEquals(100, s.aim(175).z, 1e-9);
        assertTrue(s.aim(210).y > 64);
        assertEquals(8, s.aimSize(175));
        // после событий и пути — место последнего события
        assertEquals(100, s.aim(400).z, 1e-9);
        // подлёт — с севера на юг
        assertEquals(1, s.heading().z, 1e-6);
    }

    @Test
    void lensFitsEventWidth() {
        // огненный шар 18 блоков в 500 блоках — 12 % ширины вертикального кадра
        double fov = Cameras.fovFor(18, 500, 0.12, 9 / 16.0);
        double tanX = Math.tan(Math.toRadians(fov / 2)) * 9 / 16.0;
        assertEquals(0.12, 18 / (2 * 500 * tanX), 1e-6);
        assertEquals(70, Cameras.fovFor(100, 10, 0.5, 9 / 16.0), "не шире 70°");
    }

    @Test
    void inFrameUsesAspect() {
        Cameras.Pose p = Cameras.Pose.look(Vec3.ZERO, new Vec3(0, 0, 100), 70);
        assertEquals(0, p.yaw(), 1e-6, "взгляд на юг — yaw 0");
        // на 30 блоков вбок в 100 блоках: в широком кадре есть, в вертикальном (tan 0.7·0.5625 = 0.39 > 0.3) — тоже
        assertTrue(Cameras.inFrame(p, new Vec3(30, 0, 100), 1, 9 / 16.0));
        // на 50 вбок — только в широком
        assertFalse(Cameras.inFrame(p, new Vec3(50, 0, 100), 1, 9 / 16.0));
        assertTrue(Cameras.inFrame(p, new Vec3(50, 0, 100), 1, 16 / 9.0));
    }

    @Test
    void editorStateKeysEveryTickAtFeet() {
        Cameras.Path path = t -> Cameras.Pose.look(new Vec3(0, 80, 0), new Vec3(Math.sin(t / 10.0) * 10, 64, 50), 30);
        JsonObject st = JsonParser.parseString(Export.editorState(path, 100, 120, 1.62, false)).getAsJsonObject();
        JsonObject scene = st.getAsJsonArray("scenes").get(0).getAsJsonObject();
        assertEquals(100, scene.get("exportStartTicks").getAsInt());
        JsonObject cam = scene.getAsJsonArray("keyframeTracks").get(0).getAsJsonObject().getAsJsonObject("keyframesByTick");
        assertNotNull(cam.get("58"), "ключи — с запасом до начала");
        assertEquals(80 - 1.62, cam.getAsJsonObject("110").getAsJsonArray("position").get(1).getAsDouble(), 1e-9);
        // глазами игрока — без дорожек
        JsonObject pov = JsonParser.parseString(Export.editorState(null, 100, 120, 1.62, true)).getAsJsonObject();
        assertEquals(0, pov.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("keyframeTracks").size());
    }
}
