package ua.zentix.replayscan.film;

import com.google.gson.JsonObject;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Проверка каждого кадра вывода по данным игры, а не на глаз: в момент отрисовки кадра (конец мира) — где объект на
 * экране и какую долю ширины кадра он занимает, закрыт ли он блоками (лучи к середине и краям), дымом слоя эффектов
 * ({@link Smoke}), облаками или туманом у края прорисовки, нарисован ли он (секция сущности построена; снаряд без
 * сущности у клиента рисует Incoming по пути от сервера) и построен ли весь мир у камеры, слышен ли его мотор, видно ли
 * каждое событие в первые 12 тиков; у плана глазами игрока — камера ли это игрок; со своей ли глубиной Distant Horizons
 * рисует LOD ({@link DhDepth}); какая доля кадра свободной камеры на земле за чанками повтора ({@link FrameOut}).
 * Лучи и кадр — те же, что у оценки камер ({@link Cameras.View}, {@link Cameras#open},
 * {@link Cameras#keySeen}). Строка JSON на кадр — {@code qa.jsonl}; итог ({@link #verdict}) — по порогам плана, без
 * просмотра кадров.
 */
final class FrameLog {
    /** Кадр в дыму: столько лучей сетки кадра закрыто дымом у камеры ({@link Smoke#smothered}). */
    static final double SMOTHERED = 0.6;
    /** Сколько тиков после события оно проверяется: вспышка и огненный шар. */
    static final int KEY_CHECK = 12;
    /** Скорость звука, блоков за тик: мотор мода звучит из точки, где снаряд был, когда звук вышел. */
    static final double SOUND = 17.15;
    /** Distant Horizons стоит: глубину его LOD в кадре проверяет {@link DhDepth}. */
    static final boolean DH = net.neoforged.fml.ModList.get().isLoaded("distanthorizons");

    private final Subject s;
    private final Plan.Shot shot;
    private final double ready, clouds;
    @Nullable
    private final UUID pov;
    private final Smoke smoke;
    /** Путь камеры плана (у глаз игрока — нет): крен кадра. */
    @Nullable
    private final Cameras.Path path;
    private final Sounds sounds;
    /** Земля кадра у клиента повтора (NaN — чанка нет) — та же, что у пилота ({@link FrameOut}). */
    private final FrameOut.Column ground;
    /** Дыра в мире в точках лучей «вне» по глубине DH; null — DH нет. */
    @Nullable
    private final DhHole hole;
    private final Writer out;
    private int frames, readyN, firstReady, povN, pre, preOk, near, nearOk, eng, dhWorld, inside, lowOut, holed, smothered;
    private final int[] keyN, keyOk;
    private int entityId = -1;

    FrameLog(Subject s, Plan.Shot shot, Plan plan, @Nullable UUID pov, @Nullable Cameras.Path path, Smoke smoke, Path dir) throws IOException {
        this.s = s;
        this.shot = shot;
        this.ready = plan.ready();
        this.pov = pov;
        this.smoke = smoke;
        this.path = path;
        Minecraft mc = Minecraft.getInstance();
        clouds = Cameras.cloudHeight(plan);
        ground = (x, z) -> {
            LevelChunk c = mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return c == null ? Double.NaN : c.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        };
        keyN = new int[s.keys.size()];
        keyOk = new int[s.keys.size()];
        hole = DhHole.create();
        out = Files.newBufferedWriter(dir.resolve("qa.jsonl"), StandardCharsets.UTF_8);
        sounds = new Sounds(dir.resolve("sounds.jsonl"), plan.loud(), plan.engine(), Export::exportTick);
        mc.getSoundManager().addListener(sounds);
        // проходы LOD до плана — не его
        if (DH) DhDepth.take();
    }

    /** Кадр вывода нарисован: мир и слой эффектов уже в нём. */
    void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        // глубина LOD этого кадра (DhDepth.take: 1 — своя, −1 — мира); снимается и в кадрах не вывода
        int dh = DH ? DhDepth.take() : 0;
        if (!Export.exporting()) return;
        double t = Export.exportTick();
        if (Double.isNaN(t)) return;
        Minecraft mc = Minecraft.getInstance();
        Camera cam = e.getCamera();
        Vec3 eye = cam.getPosition();
        // копии: векторы камеры — её живые поля; крен ключа Flashback кладёт поверх них (Cameras.View.rolled)
        Matrix4f proj = e.getProjectionMatrix();
        double tanY = 1 / proj.m11(), tanX = 1 / proj.m00();
        Cameras.View view = new Cameras.View(eye, vec(cam.getLookVector()), vec(cam.getLeftVector()).scale(-1), vec(cam.getUpVector()), tanX, tanY)
                .rolled(path == null ? 0 : path.roll(t));
        Vec3 fwd = view.fwd(), up = view.up(), right = view.right();
        sounds.camera(eye, fwd, right, up, tanX, tanY);
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        int first = s.firstKey();
        boolean before = first < 0 || t < first;

        Vec3 aim = s.aim(t);
        double size = s.aimSize(t);
        boolean fromEntity = false;
        double speed = s.speed(t);
        if (s.uuid != null && before) {
            Entity ent = entity(mc);
            if (ent != null && !ent.isRemoved()) {
                aim = ent.getPosition(partial).add(0, ent.getBbHeight() / 2, 0);
                speed = Math.max(speed, ent.getDeltaMovement().length());
                fromEntity = true;
            }
        }
        boolean in = false, cloud = false, fog = false, drawn = false;
        double x = 9, y = 9, share = 0, seen = 0, clear = 1, unknown = -1;
        if (aim != null) {
            Vec3 d = aim.subtract(eye);
            double z = d.dot(fwd);
            if (z > 0.1) {
                x = d.dot(right) / z / tanX;
                y = d.dot(up) / z / tanY;
                in = Math.abs(x) <= 0.92 && Math.abs(y) <= 0.92;
                share = size / (2 * z * tanX);
            }
            if (in) {
                seen = Cameras.open(mc.level, eye, aim, right, up, size, 5);
                cloud = Cameras.throughClouds(clouds, eye, aim);
                fog = !shot.far() && Cameras.beyondFog(eye, aim);
                BlockPos bp = BlockPos.containing(aim);
                // сущность в непостроенной секции игра не рисует (isSectionCompiled), стену цели — тоже; снаряд, которого
                // у клиента нет (ушёл из сущностей записавшего, за чанки повтора), рисует Incoming по пути от сервера
                // (дальние строки пути) — секции ему не нужны
                boolean byServer = s.uuid != null && before && !fromEntity && t >= s.pathFirst() && t <= s.pathLast();
                drawn = shot.far() || byServer || mc.level.isOutsideBuildHeight(bp) || mc.levelRenderer.isSectionCompiled(bp);
                // дым — только до события: после него в кадре и должны быть огонь и дым самого удара
                if (before) clear = smoke.transmittance(eye.x, eye.y, eye.z, aim.x, aim.y, aim.z, size * 2 + 4);
                if (shot.far()) unknown = unknown(mc, eye, aim);
            }
        }
        // источник мотора отстаёт от снаряда на путь, пройденный, пока звук шёл к камере
        double engine = s.uuid != null && before && aim != null
                ? sounds.engine(aim, Math.max(8, 2 * s.size) + 1.2 * speed * aim.distanceTo(eye) / SOUND) : 0;
        boolean isPov = pov != null && mc.getCameraEntity() != null && pov.equals(mc.getCameraEntity().getUUID());
        boolean built = mc.levelRenderer.hasRenderedAllSections();
        // свободная камера в блоке (путь плана идёт и там, где при оценке чанков ещё не было)
        boolean blocked = pov == null && Cameras.inBlocks(mc.level, eye);
        // доля кадра свободной камеры на земле за чанками повтора (лучи сетки по всему кадру) и доля лучей «вне»,
        // где DH не нарисовал землю на ожидаемом расстоянии (дыра в мире: сквозь неё видно пещеры LOD или ничего)
        double[] frameHole = {0};
        double frameOut = pov == null ? FrameOut.share(view, ground, hole == null ? null
                : (sx, sy, d, expected) -> {
                    if (hole.ray(sx, sy, expected)) frameHole[0]++;
                }) : 0;
        if (hole != null) hole.fit();
        boolean low = frameOut > 0, holedFrame = frameHole[0] > 0;
        // кадр свободной камеры почти весь в дыму у самой камеры
        double smother = pov == null ? smoke.smothered(view) : 0;

        // события в первые тики: каждое — в кадре и не закрыто
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < s.keys.size(); i++) {
            Subject.Key k = s.keys.get(i);
            if (t < k.t() || t > k.t() + KEY_CHECK) continue;
            boolean vis = Cameras.keySeen(mc.level, clouds, shot.far(), view, Cameras.keyPoint(k, t), k.size());
            keyN[i]++;
            if (vis) keyOk[i]++;
            keys.append(keys.isEmpty() ? "" : ",").append('[').append(i).append(',').append(vis ? 1 : 0).append(']');
        }

        frames++;
        if (built) readyN++;
        if (frames <= 10 && built) firstReady++;
        if (isPov) povN++;
        if (dh < 0) dhWorld++;
        if (blocked) inside++;
        if (low) lowOut++;
        if (holedFrame) holed++;
        if (smother >= SMOTHERED) smothered++;
        if (aim != null && before) {
            boolean good = in && seen >= 0.4 && clear >= 0.5 && !cloud && !fog && (drawn || ready <= 0) && share >= shot.need().share();
            pre++;
            if (good) preOk++;
            if (engine >= 0.02) eng++;
            if (first >= 0 && t >= first - 20) {
                near++;
                if (good) nearOk++;
            }
        }
        try {
            out.write(String.format(Locale.ROOT,
                    "{\"t\":%.3f,\"ready\":%b,\"pov\":%b,\"inside\":%b,\"out\":%.4f,\"hole\":%.4f,\"low_out\":%b,\"in\":%b,\"x\":%.3f,\"y\":%.3f,\"share\":%.4f,\"seen\":%.2f,\"clear\":%.2f,\"cloud\":%b,"
                            + "\"smother\":%.2f,\"fog\":%b,\"drawn\":%b,\"entity\":%b,\"engine\":%.3f,\"unknown\":%.0f,\"keys\":[%s],\"dh\":%d,\"cam\":[%.2f,%.2f,%.2f,%.1f,%.1f],\"fov\":%.2f}\n",
                    t, built, isPov, blocked, frameOut, frameHole[0] / (FrameOut.W * FrameOut.H), low, in, x, y, share, seen, clear, cloud, smother, fog, drawn, fromEntity, engine, unknown, keys, dh, eye.x, eye.y, eye.z,
                    cam.getYRot(), cam.getXRot(), Math.toDegrees(2 * Math.atan(tanY))));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    /** Сущность объекта у клиента повтора (UUID те же, что в записи); id запоминается. */
    @Nullable
    private Entity entity(Minecraft mc) {
        if (entityId >= 0) {
            Entity e = mc.level.getEntity(entityId);
            if (e != null && s.uuid.equals(e.getUUID())) return e;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (s.uuid.equals(e.getUUID())) {
                entityId = e.getId();
                return e;
            }
        }
        return null;
    }

    /** Где на луче к дальнему объекту начинается чанк, которого у клиента нет (дальше — только рельеф DH); −1 — нет. */
    private static double unknown(Minecraft mc, Vec3 eye, Vec3 at) {
        Vec3 d = at.subtract(eye);
        double len = d.length();
        for (double k = 0; k < len; k += 8) {
            Vec3 p = eye.add(d.scale(k / len));
            if (mc.level.getChunkSource().getChunkNow(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4) == null) return k;
        }
        return -1;
    }

    void close() {
        Minecraft.getInstance().getSoundManager().removeListener(sounds);
        sounds.close();
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Итог проверки: годится ли план, почему нет и числа. */
    record Verdict(boolean pass, String why, double rank, JsonObject numbers) {}

    /**
     * Итог по кадрам: мир построен (не меньше {@code ready} кадров и первые 10 — все); до первого события объект в кадре,
     * виден, не в дыму, облаках и тумане, в построенной секции и не мельче порога — не меньше долей плана за всё время
     * и за последнюю секунду; каждое событие видно в первые {@link #KEY_CHECK} тиков (доля событий — по плану);
     * мотор слышен; громкого за кадром не больше, чем в кадре; у плана глазами игрока камера — этот игрок; LOD Distant
     * Horizons с глубиной мира и свободная камера в блоке — ни в одном кадре; земля вне чанков повтора в кадре свободной
     * камеры — не больше чем в 2 % кадров, дыра в мире (DH не нарисовал землю, где она должна быть, — видно пещеры
     * LOD или пустоту) — тоже, и числа этих кадров вычитаются из rank: из не прошедших кандидатов лучший — где их
     * меньше; кадров в дыму у самой камеры — не больше доли {@code smother} плана.
     */
    Verdict verdict(boolean isPovShot) {
        Plan.Need need = shot.need();
        double rReady = frames == 0 ? 0 : (double) readyN / frames, approach = pre == 0 ? 1 : (double) preOk / pre;
        double nearHit = near == 0 ? 1 : (double) nearOk / near, engine = pre == 0 ? 1 : (double) eng / pre;
        double povShare = frames == 0 ? 0 : (double) povN / frames;
        // события плана — те, что в его тиках (план может начаться после события: продолжение другого плана)
        int keysOk = 0, keysIn = 0;
        for (int i = 0; i < keyN.length; i++) {
            Subject.Key k = s.keys.get(i);
            if (k.t() < shot.a() || k.t() > shot.b()) continue;
            keysIn++;
            if (keyN[i] > 0 && (double) keyOk[i] / keyN[i] >= 0.5) keysOk++;
        }
        double keys = keysIn == 0 ? 1 : (double) keysOk / keysIn;
        boolean loudOk = need.loud() < 0 || sounds.loudOff <= need.loud() * sounds.loudOn;
        StringBuilder why = new StringBuilder();
        if (frames == 0) why.append("no frames; ");
        if (ready > 0 && (rReady < ready || firstReady < Math.min(10, frames)))
            why.append(String.format(Locale.ROOT, "world still building in %.0f%% of frames; ", 100 * (1 - rReady)));
        if (isPovShot && povShare < 0.98) why.append(String.format(Locale.ROOT, "camera is the player in %.0f%% of frames; ", 100 * povShare));
        if (approach < need.subject()) why.append(String.format(Locale.ROOT, "subject shown in %.0f%% of frames before the event; ", 100 * approach));
        if (nearHit < need.near()) why.append(String.format(Locale.ROOT, "in the last second before the event: %.0f%%; ", 100 * nearHit));
        if (keys < need.keys()) why.append(String.format(Locale.ROOT, "events seen: %d of %d; ", keysOk, keysIn));
        if (engine < need.engine()) why.append(String.format(Locale.ROOT, "engine heard in %.0f%% of frames; ", 100 * engine));
        if (!loudOk) why.append(String.format(Locale.ROOT, "loud sounds off screen %d, on screen %d; ", sounds.loudOff, sounds.loudOn));
        if (inside > 0) why.append(String.format(Locale.ROOT, "camera inside blocks in %d of %d frames; ", inside, frames));
        if (lowOut > 0.02 * frames)
            why.append(String.format(Locale.ROOT, "frame on ground outside the replay's chunks in %d of %d frames; ", lowOut, frames));
        if (holed > 0.02 * frames)
            why.append(String.format(Locale.ROOT, "hole in the world (no Distant Horizons ground where it must be) in %d of %d frames; ", holed, frames));
        if (need.smother() >= 0 && smothered > need.smother() * frames)
            why.append(String.format(Locale.ROOT, "frame covered by smoke at the camera in %d of %d frames; ", smothered, frames));
        if (dhWorld > 0) why.append(String.format(Locale.ROOT, "Distant Horizons LODs drawn with the world's depth in %d of %d frames; ", dhWorld, frames));
        JsonObject n = new JsonObject();
        n.addProperty("frames", frames);
        n.addProperty("ready", rReady);
        if (isPovShot) n.addProperty("pov", povShare);
        n.addProperty("approach", approach);
        n.addProperty("near", nearHit);
        n.addProperty("keys_ok", keysOk);
        n.addProperty("keys", keysIn);
        n.addProperty("engine", engine);
        n.addProperty("loud_on", sounds.loudOn);
        n.addProperty("loud_off", sounds.loudOff);
        n.addProperty("smoke_checked", smoke.available());
        if (DH) n.addProperty("dh_world", dhWorld);
        n.addProperty("inside", inside);
        n.addProperty("low_out", lowOut);
        if (hole != null) n.addProperty("hole", holed);
        n.addProperty("smothered", smothered);
        n.addProperty("smoke_columns", smoke.columns());
        // кадры на земле за чанками и кадры с дырой в мире вычитаются из rank числом: из кандидатов, не прошедших
        // проверку (например, трёх полётов пилота), лучший — тот, где таких кадров меньше
        double rank = rReady + approach + nearHit + keys + 0.5 * engine + (loudOk ? 0.5 : 0) + (isPovShot ? povShare : 1) - lowOut - holed;
        return new Verdict(why.isEmpty(), why.toString().trim(), rank, n);
    }
}
