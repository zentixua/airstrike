package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.client.sound.EngineSound;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Проверка каждого кадра вывода по данным игры, а не на глаз: в момент отрисовки кадра (конец мира) — где объект плана
 * на экране и какой он долей высоты кадра, закрыт ли он блоками (лучи к середине и краям) и дымом слоя эффектов
 * ({@link FxPool#transmittance}), все ли секции мира у камеры построены, слышен ли мотор объекта. Строка JSON на кадр —
 * {@code qa.jsonl}; итог плана ({@link #verdict}) — по порогам, без просмотра кадров.
 */
final class FrameQa {
    /**
     * Кадр: время (тик повтора с долей), всё построено, объект в кадре (с полями), где он (−1…1), доля высоты, видимая доля,
     * свет сквозь дым, облака, туман у края прорисовки, секция объекта построена, громкость мотора.
     */
    record Frame(double t, boolean ready, boolean in, double x, double y, double share, double seen, double clear, boolean cloud,
                 boolean far, boolean drawn, double engine, boolean subjectFromEntity) {
        /** Объект снят: в кадре, виден, не в облаках и не в тумане, его секция построена (если мир проверяется). */
        boolean visible() {
            return in && seen >= 0.4 && !cloud && !far && (READY <= 0 || drawn);
        }
    }

    record Verdict(boolean pass, String why, double ready, double approach, double nearHit, double atKey, double engine) {}

    /**
     * Доля кадров с построенным миром, ниже которой план не годится. Облако (llvmpipe, без Sodium) строит секции
     * медленнее кадров — там проверку мира снимают ({@code INCOMING_FILM_READY=0}), чтобы проверять остальное.
     */
    static final double READY = Double.parseDouble(System.getenv().getOrDefault("INCOMING_FILM_READY", "0.98"));

    private final Rig.Subject s;
    private final double clouds = Rig.cloudBase();
    private final Writer out;
    private final List<Frame> frames = new ArrayList<>();
    private final Recorder sound;
    private final int start;
    /** Отметки журнала плана (тик повтора) — в журнал монтажа, когда кадр до них дошёл. */
    private final java.util.ArrayDeque<EventLog.Mark> marks;

    FrameQa(Rig.Subject s, Path dir, String shot, int start, List<EventLog.Mark> marks) {
        this.s = s;
        this.start = start;
        this.marks = new java.util.ArrayDeque<>(marks.stream().sorted(java.util.Comparator.comparingInt(EventLog.Mark::t)).toList());
        try {
            Files.createDirectories(dir);
            out = Files.newBufferedWriter(dir.resolve("qa.jsonl"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // журнал звуков плана (как у живой записи): монтаж собирает из него дорожку
        sound = new Recorder(dir, false);
        Minecraft.getInstance().getSoundManager().addListener(sound);
        sound.start(shot, 1, false);
    }

    List<Frame> frames() {
        return frames;
    }

    /** Сущность объекта у клиента повтора (UUID те же, что в записи); id запоминается. */
    private int entityId = -1;

    @org.jetbrains.annotations.Nullable
    private Entity entity(Minecraft mc) {
        if (entityId >= 0) {
            Entity e = mc.level.getEntity(entityId);
            if (e != null && s.entity.equals(e.getUUID())) return e;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (s.entity.equals(e.getUUID())) {
                entityId = e.getId();
                return e;
            }
        }
        return null;
    }

    /** Кадр вывода нарисован: мир и слой эффектов уже в нём. */
    void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !FlashbackApi.exporting()) return;
        Minecraft mc = Minecraft.getInstance();
        double t = FlashbackApi.exportTick();
        if (Double.isNaN(t)) return;
        Camera cam = e.getCamera();
        Vec3 eye = cam.getPosition();
        if (frames.isEmpty()) {
            // люди у камеры в первом кадре: рисует ли их игра и куда смотрят (статисты плана должны быть в кадре)
            var disp = mc.getEntityRenderDispatcher();
            List<String> people = new ArrayList<>();
            for (Player pl : mc.level.players()) {
                if (pl.isSpectator() || pl.position().distanceTo(eye) > 24) continue;
                people.add(String.format(Locale.ROOT, "%s at %s: drawn %b, body %.0f, head %.0f",
                        pl.getName().getString(), War.xyz(pl.position()), disp.shouldRender(pl, e.getFrustum(), eye.x, eye.y, eye.z),
                        pl.yBodyRot, pl.getYHeadRot()));
            }
            ua.zentix.incoming.Incoming.LOG.info("SCENARIO film people in first frame (camera yaw {}): {}",
                    String.format(Locale.ROOT, "%.0f", cam.getYRot()), people);
        }
        Matrix4f proj = e.getProjectionMatrix();
        double tanY = 1 / proj.m11(), tanX = 1 / proj.m00();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);

        // объект: сущность у клиента (так её и рисует игра), иначе — по журналу
        Vec3 aim = s.aim(t);
        double size = s.aimSize(t);
        boolean fromEntity = false;
        if (s.entity != null && (s.key < 0 || t < s.key)) {
            Entity ent = entity(mc);
            if (ent != null && !ent.isRemoved()) {
                aim = ent.getPosition(partial).add(0, ent.getBbHeight() / 2, 0);
                fromEntity = true;
            }
        }
        boolean in = false, cloud = false, far = false, drawn = false;
        double x = 9, y = 9, share = 0, seen = 0, clear = 1;
        if (aim != null) {
            Vector3f f = cam.getLookVector(), u = cam.getUpVector(), l = cam.getLeftVector();
            Vec3 d = aim.subtract(eye);
            double z = d.x * f.x() + d.y * f.y() + d.z * f.z();
            if (z > 0.1) {
                x = -(d.x * l.x() + d.y * l.y() + d.z * l.z()) / z / tanX;
                y = (d.x * u.x() + d.y * u.y() + d.z * u.z()) / z / tanY;
                in = Math.abs(x) <= 0.92 && Math.abs(y) <= 0.92;
                share = size / (2 * z * tanY);
            }
            if (in) {
                // пять лучей: к середине и к краям объекта поперёк взгляда
                Vec3 right = new Vec3(-l.x(), -l.y(), -l.z()).scale(size * 0.35), up = new Vec3(u.x(), u.y(), u.z()).scale(size * 0.35);
                Vec3[] pts = {aim, aim.add(right), aim.subtract(right), aim.add(up), aim.subtract(up)};
                int open = 0;
                double spare = Math.max(1.5, size * 0.4);
                for (Vec3 p : pts) {
                    Vec3 dd = p.subtract(eye);
                    double len = dd.length();
                    if (len <= spare) {
                        open++;
                        continue;
                    }
                    Vec3 end = eye.add(dd.scale((len - spare) / len));
                    var hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
                    if (hit.getType() == HitResult.Type.MISS) open++;
                }
                seen = open / 5.0;
                cloud = Rig.throughClouds(eye, aim, clouds);
                far = Rig.beyondFog(eye, aim);
                // сущность в непостроенной секции игра не рисует (LevelRenderer: isSectionCompiled), стену цели — тоже
                BlockPos bp = BlockPos.containing(aim);
                drawn = mc.level.isOutsideBuildHeight(bp) || mc.levelRenderer.isSectionCompiled(bp);
                // дым — только до удара: после него в кадре и должен быть огонь и дым самого удара
                if (s.key < 0 || t < s.key) clear = FxPool.INSTANCE.transmittance(eye.x, eye.y, eye.z, aim.x, aim.y, aim.z, size * 2 + 4);
            }
        }
        double engine = 0;
        if (s.entity != null) {
            for (SoundInstance si : mc.getSoundManager().soundEngine.instanceToChannel.keySet()) {
                if (si instanceof EngineSound es && s.entity.equals(es.trackId())) engine = Math.max(engine, es.getVolume());
            }
        }
        boolean ready = mc.levelRenderer.hasRenderedAllSections();
        Frame fr = new Frame(t, ready, in, x, y, share, seen, clear, cloud, far, drawn, engine, fromEntity);
        frames.add(fr);
        sound.frame(t - start, cam);
        while (!marks.isEmpty() && marks.peekFirst().t() <= t) sound.mark(marks.pollFirst().what());
        try {
            out.write(String.format(Locale.ROOT,
                    "{\"t\":%.3f,\"ready\":%b,\"in\":%b,\"x\":%.3f,\"y\":%.3f,\"share\":%.4f,\"seen\":%.2f,\"clear\":%.2f,\"cloud\":%b,\"far\":%b,"
                            + "\"drawn\":%b,\"engine\":%.3f,\"entity\":%b,\"cam\":[%.2f,%.2f,%.2f,%.1f,%.1f],\"fov\":%.1f}\n",
                    t, ready, in, x, y, share, seen, clear, cloud, far, drawn, engine, fromEntity, eye.x, eye.y, eye.z, cam.getYRot(), cam.getXRot(),
                    Math.toDegrees(2 * Math.atan(tanY))));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    void close() {
        Minecraft.getInstance().getSoundManager().removeListener(sound);
        sound.finish();
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Итог по кадрам: мир построен (≥ {@link #READY} кадров и первые 10 — все); до удара объект в кадре, виден, не в дыму,
     * облаках и тумане у края прорисовки, в построенной секции и не мельче {@code minShare} высоты кадра — не меньше долей {@code need} за всё время до удара и за последнюю
     * секунду; удар в кадре и виден в первые 12 тиков; мотор (если нужен и {@code close}) слышен до удара.
     */
    Verdict verdict(double minShare, Coverage.Need need, boolean close) {
        int n = frames.size();
        if (n == 0) return new Verdict(false, "нет кадров", 0, 0, 0, 0, 0);
        int ready = 0, firstReady = 0;
        int pre = 0, preOk = 0, near = 0, nearOk = 0, key = 0, keyOk = 0, eng = 0;
        for (int i = 0; i < n; i++) {
            Frame f = frames.get(i);
            if (f.ready) ready++;
            if (i < 10 && f.ready) firstReady++;
            boolean good = f.visible() && f.clear >= 0.5 && f.share >= minShare;
            if (s.key < 0 || f.t < s.key) {
                pre++;
                if (good) preOk++;
                if (f.engine >= 0.02) eng++;
                if (s.key >= 0 && f.t >= s.key - 20) {
                    near++;
                    if (good) nearOk++;
                }
            } else if (f.t <= s.key + 12) {
                key++;
                if (f.visible()) keyOk++;
            }
        }
        double rReady = (double) ready / n, approach = pre == 0 ? 1 : (double) preOk / pre, nearHit = near == 0 ? 1 : (double) nearOk / near;
        double atKey = key == 0 ? (s.key < 0 ? 1 : 0) : (double) keyOk / key, engine = pre == 0 ? 1 : (double) eng / pre;
        StringBuilder why = new StringBuilder();
        if (READY > 0 && (rReady < READY || firstReady < Math.min(10, n))) why.append(String.format(Locale.ROOT, "мир достраивается в кадре (%.0f%%); ", 100 * (1 - rReady)));
        if (approach < need.approach()) why.append(String.format(Locale.ROOT, "объект виден %.0f%% кадров до удара; ", 100 * approach));
        if (nearHit < need.near()) why.append(String.format(Locale.ROOT, "в последнюю секунду до удара виден %.0f%%; ", 100 * nearHit));
        if (atKey < need.key()) why.append(String.format(Locale.ROOT, "удар виден %.0f%%; ", 100 * atKey));
        if (close && engine < need.engine()) why.append(String.format(Locale.ROOT, "мотор слышен %.0f%%; ", 100 * engine));
        return new Verdict(why.isEmpty(), why.toString().trim(), rReady, approach, nearHit, atKey, engine);
    }
}
