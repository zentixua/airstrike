package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.grid.GridLights;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Проверка отключения света в кадре по данным игры: лампы сети ({@link GridLights}) перед камерой плана — в поле зрения,
 * ближе {@code range} и не за блоками (луч по видимой форме) — ищутся один раз в начале плана, их состояние — каждый
 * тик. Первый тик с погасшей лампой в кадре — отметка {@code dark} для монтажа. Итог — строка {@code SCENARIO film grid}
 * и {@code <папка записи>/grid.jsonl}: план прошёл, если в кадре было не меньше {@link #MIN} горящих ламп и к концу
 * погасла доля {@code need}. Камера плана — его собственная ({@link Coverage}: {@code Rig.own}), вывод из повтора
 * снимает тем же местом.
 */
final class LampsQa {
    /** Меньше ламп в кадре — отключения в нём не видно. */
    static final int MIN = 12;
    private static final Predicate<BlockState> LAMP = s -> GridLights.isLit(s) || GridLights.isUnlit(s);

    private final String shot;
    private final double need;
    private final List<BlockPos> lamps = new ArrayList<>();
    private int litStart, lit, unlit, darkAt = -1, t;

    LampsQa(String shot, double need) {
        this.shot = shot;
        this.need = need;
    }

    /** Лампы в кадре камеры {@code pose}: поле зрения по вертикали — {@code pose.fov()}, по горизонтали — по окну. */
    void scan(Minecraft mc, CineCamera.Pose pose, double range) {
        ClientLevel level = mc.level;
        Vec3 eye = pose.pos(), f = Vec3.directionFromRotation(pose.pitch(), pose.yaw());
        Vec3 side = f.cross(new Vec3(0, 1, 0)).normalize(), up = side.cross(f);
        double ty = Math.tan(Math.toRadians(pose.fov() / 2)), tx = ty * mc.getWindow().getWidth() / mc.getWindow().getHeight();
        // сфера секции (радиус 14) против граней пирамиды кадра: секции вне кадра не читаются
        double my = 14 * Math.sqrt(1 + ty * ty), mx = 14 * Math.sqrt(1 + tx * tx);
        int r = (int) Math.ceil(range / 16) + 1, cx0 = SectionPos.blockToSectionCoord(eye.x), cz0 = SectionPos.blockToSectionCoord(eye.z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
            for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
                LevelChunk c = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (c == null) continue;
                LevelChunkSection[] sections = c.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s.hasOnlyAir() || !s.maybeHas(LAMP)) continue;
                    int by = SectionPos.sectionToBlockCoord(c.getSectionYFromSectionIndex(i));
                    Vec3 d = new Vec3(cx * 16 + 8, by + 8, cz * 16 + 8).subtract(eye);
                    double z = d.dot(f);
                    if (z < -14 || d.length() > range + 14 || Math.abs(d.dot(up)) > z * ty + my || Math.abs(d.dot(side)) > z * tx + mx) continue;
                    for (int y = 0; y < 16; y++) {
                        for (int bz = 0; bz < 16; bz++) {
                            for (int bx = 0; bx < 16; bx++) {
                                if (!LAMP.test(s.getBlockState(bx, y, bz))) continue;
                                p.set(cx * 16 + bx, by + y, cz * 16 + bz);
                                if (inFrame(level, eye, f, side, up, tx, ty, range, p)) lamps.add(p.immutable());
                            }
                        }
                    }
                }
            }
        }
        count(level);
        litStart = lit;
        Incoming.LOG.info("SCENARIO film grid {}: в кадре ламп сети {}, горят {}", shot, lamps.size(), litStart);
    }

    /** Место и направление камеры, горящих ламп сети и людей в её кадре ({@link #bestView}). */
    record View(Vec3 eye, float yaw, float pitch, int lamps, int people) {
        Vec3 forward() {
            return Vec3.directionFromRotation(pitch, yaw);
        }
    }

    /**
     * Камера у людей: место на полу под крышей ближе {@code reach} по горизонтали к {@code near} и на виду у неё, направление
     * — через 10° по курсу, взгляд прямо или вверх. Лампы и люди в кадре — как у {@link #scan} (поле зрения {@code fov} по
     * вертикали, ближе {@code range}, не за блоками). Из видов, где горящих ламп вдвое больше {@link #MIN}, — где больше людей
     * (дальше 2 блоков), таких нет — где больше ламп. Мест нет — {@code null}.
     */
    @Nullable
    static View bestView(Minecraft mc, Vec3 near, int reach, double range, double fov, List<Vec3> people) {
        ClientLevel level = mc.level;
        List<BlockPos> lit = lampsAround(level, near, reach + range);
        double ty = Math.tan(Math.toRadians(fov / 2)), tx = ty * mc.getWindow().getWidth() / mc.getWindow().getHeight();
        View best = null;
        int eyes = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx += 2) {
            for (int dz = -reach; dz <= reach; dz += 2) {
                if (dx * dx + dz * dz > reach * reach) continue;
                int x = Mth.floor(near.x) + dx, z = Mth.floor(near.z) + dz;
                for (int y = Mth.floor(near.y) + 6; y >= Mth.floor(near.y) - 6; y--) {
                    if (!solid(level, p.set(x, y, z)) || solid(level, p.set(x, y + 1, z)) || solid(level, p.set(x, y + 2, z))) continue;
                    Vec3 eye = new Vec3(x + 0.5, y + 1 + 1.6, z + 0.5);
                    if (level.clip(new ClipContext(eye, eye.add(0, 40, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                            CollisionContext.empty())).getType() == HitResult.Type.MISS || !clear(level, eye, near)) continue;
                    eyes++;
                    List<Vec3> lamps = new ArrayList<>(), seen = new ArrayList<>();
                    for (BlockPos l : lit) {
                        Vec3 c = Vec3.atCenterOf(l);
                        if (c.distanceTo(eye) > range) continue;
                        BlockHitResult hit = level.clip(new ClipContext(eye, c, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
                        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(l)) lamps.add(c.subtract(eye));
                    }
                    for (Vec3 man : people) {
                        if (man.distanceTo(eye) > 2 && clear(level, eye, man)) seen.add(man.subtract(eye));
                    }
                    for (int pitch = 0; pitch >= -20; pitch -= 10) {
                        for (int yaw = 0; yaw < 360; yaw += 10) {
                            Vec3 f = Vec3.directionFromRotation(pitch, yaw);
                            Vec3 side = f.cross(new Vec3(0, 1, 0)).normalize(), up = side.cross(f);
                            View v = new View(eye, yaw, pitch, count(lamps, f, side, up, tx, ty), count(seen, f, side, up, tx, ty));
                            if (best == null || better(v, best)) best = v;
                        }
                    }
                }
            }
        }
        Incoming.LOG.info("SCENARIO film grid: камера у {} — мест {}, ламп сети вокруг горят {}; лучший вид {}", near, eyes, lit.size(), best);
        return best;
    }

    /** Ламп с запасом вдвое — где больше людей; иначе — где больше ламп. */
    private static boolean better(View a, View b) {
        int ta = a.lamps >= 2 * MIN ? 1 : 0, tb = b.lamps >= 2 * MIN ? 1 : 0;
        if (ta != tb) return ta > tb;
        return ta == 1 ? a.people > b.people || a.people == b.people && a.lamps > b.lamps
                : a.lamps > b.lamps || a.lamps == b.lamps && a.people > b.people;
    }

    private static int count(List<Vec3> ds, Vec3 f, Vec3 side, Vec3 up, double tx, double ty) {
        int n = 0;
        for (Vec3 d : ds) {
            double z = d.dot(f);
            if (z > 0.5 && Math.abs(d.dot(up)) <= z * ty && Math.abs(d.dot(side)) <= z * tx) n++;
        }
        return n;
    }

    private static boolean solid(ClientLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private static boolean clear(ClientLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
                == HitResult.Type.MISS;
    }

    /** Горящие лампы сети ближе {@code radius} к точке (чанки клиента). */
    private static List<BlockPos> lampsAround(ClientLevel level, Vec3 at, double radius) {
        List<BlockPos> out = new ArrayList<>();
        int r = (int) Math.ceil(radius / 16) + 1, cx0 = SectionPos.blockToSectionCoord(at.x), cz0 = SectionPos.blockToSectionCoord(at.z);
        for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
            for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
                LevelChunk c = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (c == null) continue;
                LevelChunkSection[] sections = c.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s.hasOnlyAir() || !s.maybeHas(GridLights::isLit)) continue;
                    int by = SectionPos.sectionToBlockCoord(c.getSectionYFromSectionIndex(i));
                    for (int y = 0; y < 16; y++) {
                        for (int bz = 0; bz < 16; bz++) {
                            for (int bx = 0; bx < 16; bx++) {
                                if (!GridLights.isLit(s.getBlockState(bx, y, bz))) continue;
                                BlockPos p = new BlockPos(cx * 16 + bx, by + y, cz * 16 + bz);
                                if (Vec3.atCenterOf(p).distanceTo(at) <= radius) out.add(p);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean inFrame(ClientLevel level, Vec3 eye, Vec3 f, Vec3 side, Vec3 up, double tx, double ty, double range, BlockPos p) {
        Vec3 c = Vec3.atCenterOf(p), d = c.subtract(eye);
        double z = d.dot(f);
        if (z <= 0.5 || d.length() > range || Math.abs(d.dot(up)) > z * ty || Math.abs(d.dot(side)) > z * tx) return false;
        BlockHitResult hit = level.clip(new ClipContext(eye, c, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(p);
    }

    private void count(ClientLevel level) {
        lit = unlit = 0;
        for (BlockPos p : lamps) {
            BlockState s = level.getBlockState(p);
            if (GridLights.isLit(s)) lit++;
            else if (GridLights.isUnlit(s)) unlit++;
        }
    }

    /** Тик плана: первая погасшая лампа в кадре — отметка {@code dark}. */
    void tick(Film film) {
        count(film.mc.level);
        if (darkAt < 0 && unlit > 0) {
            darkAt = t;
            film.mark("dark");
        }
        t++;
    }

    void close(Path dir) {
        int n = lit + unlit;
        double share = n == 0 ? 0 : (double) unlit / n;
        String why = litStart < MIN ? String.format(Locale.ROOT, "горящих ламп в кадре %d (нужно %d)", litStart, MIN)
                : share < need ? String.format(Locale.ROOT, "погасло %.0f%% (нужно %.0f%%)", 100 * share, 100 * need) : "";
        boolean pass = why.isEmpty();
        Incoming.LOG.info("SCENARIO film grid {}: {} — горели {}, к концу погасло {} из {} ({}), первая — на тике {}{}", shot,
                pass ? "PASS" : "FAIL", litStart, unlit, n, String.format(Locale.ROOT, "%.0f%%", 100 * share), darkAt, why.isEmpty() ? "" : "; " + why);
        String row = String.format(Locale.ROOT, "{\"shot\":\"%s\",\"verdict\":\"%s\",\"why\":\"%s\",\"lit_start\":%d,\"unlit_end\":%d,\"lamps_end\":%d,\"dark_at\":%d}\n",
                shot, pass ? "PASS" : "FAIL", why, litStart, unlit, n, darkAt);
        try {
            Files.writeString(dir.resolve("grid.jsonl"), row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
