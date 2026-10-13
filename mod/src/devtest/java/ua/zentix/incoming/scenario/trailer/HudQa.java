package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Проверка кадров плана, снятого вживую (с интерфейсом), по данным игры — как {@link FrameQa} у планов из повторов,
 * но объекты — снаряды и удары дальше прорисовки: путь снаряда — {@link FlightTrack} (его рисует дальняя картинка мода),
 * удар — конец пути. На кадр — где на экране каждый снаряд плана и каждый удар его первых 40 тиков, закрыт ли он рельефом
 * (тот же луч {@link Sightline} по {@link FarTerrain}, что решает, рисовать ли дальнее), построен ли мир у камеры.
 * В бинокле кадр — открытый квадрат окуляра ({@link Designator}: края затемнены). Строка JSON на кадр —
 * {@code <папка записи>/qa/<план>.jsonl}, итог — строка {@code SCENARIO film hud} и {@code <папка записи>/hud.jsonl}
 * (её читает {@code tools/trailer/report.py}). Удар отмечается для монтажа ({@code impact}).
 */
final class HudQa {
    /** Удар снят: в кадре и не закрыт в такой доле кадров своих первых {@link #KEY} тиков. */
    static final double KEY_SHARE = 0.8;
    static final int KEY = 12;
    /** Снаряд снят: в кадре и не закрыт в такой доле кадров последней секунды до удара. */
    static final double NEAR_SHARE = 0.5;
    static final int NEAR = 20;
    /** Удар в кадре дольше этого не смотрим, тиков. */
    static final int AFTER = 40;

    private record Hit(double t, Vec3 at) {}

    /** Объект на кадре: в кадре (с краем), не закрыт рельефом. */
    private record Seen(boolean in, boolean open) {
        boolean ok() {
            return in && open;
        }
    }

    private final String shot;
    private final WeaponType weapon;
    private final List<Vec3> aims;
    private final double radius;
    private final int expect;
    private final Path dir;
    private final Writer out;
    /** Время путей ({@link FlightTracks#now}) минус время плана: оба идут тиками клиента. */
    private final double base;
    private final Map<UUID, FlightTrack> watched = new LinkedHashMap<>();
    private final Map<UUID, Hit> hits = new LinkedHashMap<>();
    private final Map<UUID, List<double[]>> flightFrames = new LinkedHashMap<>(), hitFrames = new LinkedHashMap<>();
    private int frames, readyFrames;
    private boolean firstReady;

    HudQa(String shot, Path dir, WeaponType weapon, List<Vec3> aims, double radius, int expect) {
        this.shot = shot;
        this.weapon = weapon;
        this.aims = aims;
        this.radius = radius;
        this.expect = expect;
        this.dir = dir;
        this.base = FlightTracks.now();
        try {
            Files.createDirectories(dir.resolve("qa"));
            out = Files.newBufferedWriter(dir.resolve("qa").resolve(shot + ".jsonl"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Снаряд плана: этого оружия, целится к одной из точек плана. */
    private boolean ours(FlightTrack f, double t) {
        if (f.weapon != weapon || f.bomber) return false;
        Vec3 aim = f.aim(t);
        if (aim == null) return false;
        for (Vec3 a : aims) if (aim.distanceTo(a) < radius) return true;
        return false;
    }

    /** Тик клиента: новые снаряды плана и удары (путь кончился); удар — отметка монтажа. */
    void tick(Film film) {
        double now = FlightTracks.now();
        for (FlightTrack f : FlightTracks.all()) {
            if (!watched.containsKey(f.id) && !f.isDead() && ours(f, now)) watched.put(f.id, f);
        }
        for (FlightTrack f : watched.values()) {
            if (!f.isDead() || hits.containsKey(f.id)) continue;
            double[] p = new double[3];
            f.at(f.deathTick(), p);
            hits.put(f.id, new Hit(f.deathTick() - base, new Vec3(p[0], p[1], p[2])));
            film.mark("impact");
        }
    }

    /** Кадр видео нарисован (мир и слой эффектов в нём); {@code time} — время плана. */
    void frame(RenderLevelStageEvent e, double time) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera cam = e.getCamera();
        Vec3 eye = cam.getPosition();
        Matrix4f proj = e.getProjectionMatrix();
        double tanY = 1 / proj.m11(), tanX = 1 / proj.m00();
        // в бинокле — открытый квадрат окуляра (Designator.renderHud: сторона — меньшая сторона экрана без 20 точек)
        double limX = 0.92, limY = 0.92;
        if (Designator.isScoping()) {
            int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
            double r = Math.min(w, h) / 2.0 - 10;
            limX = r / (w / 2.0);
            limY = r / (h / 2.0);
        }
        double t = FlightTracks.now() - 1 + e.getPartialTick().getGameTimeDeltaPartialTick(false);
        Sightline.Heights heights = FarTerrain.of(mc.level);
        boolean ready = mc.levelRenderer.hasRenderedAllSections();
        if (frames == 0) firstReady = ready;
        frames++;
        if (ready) readyFrames++;
        StringBuilder flights = new StringBuilder(), impacts = new StringBuilder();
        double[] p = new double[3];
        for (FlightTrack f : watched.values()) {
            if (f.isDead() && t >= f.deathTick() || !f.predict(t, p)) continue;
            Vec3 at = new Vec3(p[0], p[1], p[2]);
            Seen s = seen(cam, eye, at, tanX, tanY, limX, limY, heights);
            flightFrames.computeIfAbsent(f.id, k -> new ArrayList<>()).add(new double[] {time, s.ok() ? 1 : 0});
            append(flights, at, eye, s);
        }
        for (var h : hits.entrySet()) {
            double age = time - h.getValue().t;
            if (age < 0 || age > AFTER) continue;
            Seen s = seen(cam, eye, h.getValue().at, tanX, tanY, limX, limY, heights);
            hitFrames.computeIfAbsent(h.getKey(), k -> new ArrayList<>()).add(new double[] {time, s.ok() ? 1 : 0});
            append(impacts, h.getValue().at, eye, s);
        }
        write(String.format(Locale.ROOT, "{\"t\":%.3f,\"ready\":%b,\"flights\":[%s],\"impacts\":[%s],\"cam\":[%.2f,%.2f,%.2f,%.1f,%.1f],\"fov\":%.1f}",
                time, ready, flights, impacts, eye.x, eye.y, eye.z, cam.getYRot(), cam.getXRot(), Math.toDegrees(2 * Math.atan(tanY))));
    }

    private static Seen seen(Camera cam, Vec3 eye, Vec3 at, double tanX, double tanY, double limX, double limY, Sightline.Heights heights) {
        Vector3f f = cam.getLookVector(), u = cam.getUpVector(), l = cam.getLeftVector();
        Vec3 d = at.subtract(eye);
        double z = d.x * f.x() + d.y * f.y() + d.z * f.z();
        boolean in = false;
        if (z > 0.1) {
            double x = -(d.x * l.x() + d.y * l.y() + d.z * l.z()) / z / tanX;
            double y = (d.x * u.x() + d.y * u.y() + d.z * u.z()) / z / tanY;
            in = Math.abs(x) <= limX && Math.abs(y) <= limY;
        }
        boolean open = Sightline.trace(heights, at.x, at.y + 1, at.z, eye.x, eye.y, eye.z).hidden() <= 0;
        return new Seen(in, open);
    }

    private static void append(StringBuilder sb, Vec3 at, Vec3 eye, Seen s) {
        if (!sb.isEmpty()) sb.append(',');
        sb.append(String.format(Locale.ROOT, "{\"at\":[%.0f,%.0f,%.0f],\"d\":%.0f,\"in\":%b,\"open\":%b}", at.x, at.y, at.z, at.distanceTo(eye), s.in, s.open));
    }

    private void write(String line) {
        try {
            out.write(line);
            out.write('\n');
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Итог плана: снарядов плана ударило не меньше ожидаемого; каждый удар в кадре и открыт в {@link #KEY_SHARE} кадров
     * своих первых {@link #KEY} тиков, каждый снаряд — в {@link #NEAR_SHARE} кадров последней секунды до удара; мир у
     * камеры построен с первого кадра.
     */
    void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        double approach = 1, key = 1;
        StringBuilder why = new StringBuilder();
        if (hits.size() < expect) why.append(String.format(Locale.ROOT, "ударов в плане %d из %d; ", hits.size(), expect));
        for (var h : hits.entrySet()) {
            double at = h.getValue().t;
            double k = share(hitFrames.get(h.getKey()), at, at + KEY), n = share(flightFrames.get(h.getKey()), at - NEAR, at);
            key = Math.min(key, k);
            approach = Math.min(approach, n);
        }
        if (key < KEY_SHARE) why.append(String.format(Locale.ROOT, "удар виден %.0f%%; ", 100 * key));
        if (approach < NEAR_SHARE) why.append(String.format(Locale.ROOT, "снаряд перед ударом виден %.0f%%; ", 100 * approach));
        double ready = frames == 0 ? 0 : (double) readyFrames / frames;
        if (FrameQa.READY > 0 && (!firstReady || ready < FrameQa.READY)) why.append(String.format(Locale.ROOT, "мир достраивается в кадре (%.0f%%); ", 100 * (1 - ready)));
        boolean pass = why.isEmpty() && frames > 0;
        String reason = frames == 0 ? "нет кадров" : why.toString().trim();
        Incoming.LOG.info("SCENARIO film hud {}: {} — ударов {}, удар виден {}, снаряд перед ударом {}, мир {}{}", shot, pass ? "PASS" : "FAIL",
                hits.size(), pct(key), pct(approach), String.format(Locale.ROOT, "%.2f", ready), reason.isEmpty() ? "" : "; " + reason);
        String row = String.format(Locale.ROOT, "{\"shot\":\"%s\",\"candidate\":\"hud\",\"verdict\":\"%s\",\"why\":\"%s\",\"score\":0,\"event\":%d,"
                        + "\"ready\":%.3f,\"approach\":%.3f,\"near_hit\":%.3f,\"at_key\":%.3f,\"engine\":null}\n",
                shot, pass ? "PASS" : "FAIL", reason.replace("\"", "'"), hits.size(), ready, approach, approach, key);
        try {
            Files.writeString(dir.resolve("hud.jsonl"), row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Доля хороших кадров в окне [from, to); кадров нет — 0. */
    private static double share(List<double[]> fs, double from, double to) {
        if (fs == null) return 0;
        int n = 0, ok = 0;
        for (double[] f : fs) {
            if (f[0] < from || f[0] >= to) continue;
            n++;
            if (f[1] > 0) ok++;
        }
        return n == 0 ? 0 : (double) ok / n;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.0f%%", 100 * v);
    }
}
