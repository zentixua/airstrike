package ua.zentix.airstrike.scenario.trailer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Проверка геометрии плана по каждому снятому кадру — дёшево, в облаке, до долгой съёмки на ноутбуке: камера не в
 * блоках и не вплотную к ним, взгляд не упирается в стену, камера над поверхностью (не под землёй и не под водой),
 * то, что снимаем, в кадре, не мельче порога и не закрыто блоками. В конце плана — строка
 * {@code TRAILER проверка <план>: …} и {@code ПРОВАЛ}, если что-то держится дольше допуска. Вид (засветка, пелена,
 * шейдеры) — только глазами по кадрам ноутбука.
 */
final class ShotCheck {
    /** Что снимаем: сущность, точка или null (пока нет); {@code size} — размер для точки, блоков. */
    record Subject(Supplier<Object> what, double size, double minScreen, boolean mustSee) {}

    /** Какую долю кадров плана можно простить (переходы, первый тик). */
    private static final double TOLERANCE = 0.05;

    private final String shot;
    @Nullable
    private final Subject subject;
    @Nullable
    private final BooleanSupplier video;
    private int frames, nearBlocks, blocked, underground, inFluid, subjectFrames, inFrame, tooSmall, hidden, videoFrames;
    private double minClearance = Double.MAX_VALUE;

    ShotCheck(String shot, @Nullable Subject subject, @Nullable BooleanSupplier video) {
        this.shot = shot;
        this.subject = subject;
        this.video = video;
    }

    /** Кадр снят: камера — как её нарисовали, {@code fov} — вертикальный угол кадра, градусы. */
    void frame(Camera camera, double fov) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        frames++;
        if (video != null && video.getAsBoolean()) videoFrames++;
        Vec3 eye = camera.getPosition();
        // камера у самого экрана игрока (вид от первого лица, видео с борта) — блоки вокруг не в счёт
        boolean ownView = camera.getEntity() == mc.player && !camera.isDetached() || !(camera.getEntity() instanceof net.minecraft.world.entity.Marker);
        if (!ownView) {
            double clear = clearance(level, eye, 2);
            minClearance = Math.min(minClearance, clear);
            if (clear < 1.5) nearBlocks++;
            Vector3f look = camera.getLookVector();
            Vec3 ahead = eye.add(look.x() * 4, look.y() * 4, look.z() * 4);
            if (level.clip(new ClipContext(eye, ahead, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS) blocked++;
            BlockPos at = BlockPos.containing(eye);
            if (!level.getFluidState(at).isEmpty()) inFluid++;
            if (level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4)
                    && eye.y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) - 0.5) underground++;
        }
        if (subject == null) return;
        Object o = subject.what().get();
        Vec3 p;
        double size;
        if (o instanceof Entity e) {
            p = e.getBoundingBox().getCenter();
            size = Math.max(e.getBbWidth(), e.getBbHeight());
        } else if (o instanceof Vec3 v) {
            p = v;
            size = subject.size();
        } else {
            return;
        }
        subjectFrames++;
        Vec3 d = p.subtract(eye);
        Vector3f f = camera.getLookVector(), up = camera.getUpVector(), left = camera.getLeftVector();
        double z = d.x * f.x() + d.y * f.y() + d.z * f.z();
        if (z <= 0.5) return;
        double tanV = Math.tan(Math.toRadians(fov / 2));
        double tanH = tanV * mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double x = (d.x * left.x() + d.y * left.y() + d.z * left.z()) / z;
        double y = (d.x * up.x() + d.y * up.y() + d.z * up.z()) / z;
        if (Math.abs(x) > tanH * 0.95 || Math.abs(y) > tanV * 0.95) return;
        inFrame++;
        if (size / z / (2 * tanV) < subject.minScreen()) tooSmall++;
        if (subject.mustSee()) {
            // от камеры к цели, но не до самой цели: у точки удара луч упирается в землю под ней
            Vec3 stop = eye.add(d.scale(Math.max(0, 1 - size / Math.max(size, d.length()))));
            if (level.clip(new ClipContext(eye, stop, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS) hidden++;
        }
    }

    /** Ближайший твёрдый блок в кубе ±{@code r} вокруг точки, блоков (больше r — нет). */
    private static double clearance(ClientLevel level, Vec3 p, int r) {
        BlockPos c = BlockPos.containing(p);
        double best = r + 1;
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            BlockState s = level.getBlockState(b);
            if (s.isAir() || s.getCollisionShape(level, b).isEmpty() && !s.canOcclude()) continue;
            double dx = Math.max(Math.max(b.getX() - p.x, p.x - (b.getX() + 1)), 0);
            double dy = Math.max(Math.max(b.getY() - p.y, p.y - (b.getY() + 1)), 0);
            double dz = Math.max(Math.max(b.getZ() - p.z, p.z - (b.getZ() + 1)), 0);
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    /** Итог плана в лог; @return план прошёл. */
    boolean report() {
        if (frames == 0) return true;
        StringBuilder bad = new StringBuilder();
        flag(bad, nearBlocks, "камера ближе 1,5 блока к блокам");
        flag(bad, blocked, "взгляд упирается в блок в 4 блоках");
        flag(bad, underground, "камера ниже поверхности");
        flag(bad, inFluid, "камера в воде");
        if (subject != null) {
            int seen = subjectFrames == 0 ? 0 : inFrame - tooSmall - (subject.mustSee() ? hidden : 0);
            if (subjectFrames < frames * 0.5) bad.append(String.format(Locale.ROOT, "; цели нет в %d%% кадров", 100 - 100 * subjectFrames / frames));
            else if (seen < subjectFrames * 0.6) {
                bad.append(String.format(Locale.ROOT, "; цель видна в %d%% кадров (вне кадра %d, мельче %.0f%% кадра %d, закрыта %d)",
                        100 * seen / subjectFrames, subjectFrames - inFrame, subject.minScreen() * 100, tooSmall, hidden));
            }
        }
        if (video != null && videoFrames < frames * 0.2) {
            bad.append(String.format(Locale.ROOT, "; видео с борта в %d%% кадров", 100 * videoFrames / frames));
        }
        String line = String.format(Locale.ROOT, "TRAILER проверка %s: %d кадров, ближе всего к блокам %.1f, цель в кадре %d/%d%s",
                shot, frames, minClearance > 100 ? -1 : minClearance, inFrame, subjectFrames, bad.isEmpty() ? " — OK" : " — ПРОВАЛ" + bad);
        if (bad.isEmpty()) Airstrike.LOG.info(line);
        else Airstrike.LOG.error(line);
        return bad.isEmpty();
    }

    private void flag(StringBuilder bad, int n, String what) {
        if (n > frames * TOLERANCE) bad.append(String.format(Locale.ROOT, "; %s — %d%% кадров", what, 100 * n / frames));
    }
}
