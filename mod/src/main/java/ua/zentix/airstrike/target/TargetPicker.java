package ua.zentix.airstrike.target;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModTags;

import java.util.function.Predicate;

/**
 * Что под прицелом: луч от глаз до первого препятствия — аппарат Sable, сущность или блок.
 * Общий для клиента (прицельная марка в бинокле) и сервера (сам пуск), поэтому видишь ровно то, куда ударит.
 */
public final class TargetPicker {
    private TargetPicker() {}

    public enum Kind { BLOCK, ENTITY, PLAYER, AIRCRAFT, SURFACE }

    /**
     * @param target   что преследовать
     * @param point    мировая точка удара сейчас
     * @param kind     что это (для подписи в интерфейсе)
     * @param label    подпись: блок, имя сущности или аппарата
     * @param entity   сущность-цель, если это сущность
     */
    public record Pick(Target target, Vec3 point, Kind kind, Component label, @Nullable Entity entity) {}

    @Nullable
    public static Pick pick(Level level, Entity viewer, Vec3 eye, Vec3 look, double range) {
        Vec3 end = eye.add(look.scale(range));

        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, viewer));
        Vec3 blockWorld = null;
        SubLevelAccess aircraft = null;
        if (block.getType() != HitResult.Type.MISS) {
            aircraft = SubLevels.containing(level, block.getLocation());
            blockWorld = aircraft != null ? SubLevels.toWorld(level, block.getLocation()) : block.getLocation();
        }

        // сущности ищем только до препятствия
        Vec3 entityEnd = blockWorld != null ? blockWorld : end;
        EntityHitResult entityHit = entityAlong(level, viewer, eye, entityEnd);
        if (entityHit != null) {
            Entity e = entityHit.getEntity();
            Kind kind = e instanceof Player ? Kind.PLAYER : Kind.ENTITY;
            return new Pick(Target.OfEntity.of(e, entityHit.getLocation()), entityHit.getLocation(), kind, e.getDisplayName(), e);
        }

        if (blockWorld != null) {
            if (aircraft != null) {
                return new Pick(new Target.OfSubLevel(block.getLocation()), blockWorld, Kind.AIRCRAFT, SubLevels.describe(aircraft), null);
            }
            BlockPos pos = block.getBlockPos();
            Component name = level.getBlockState(pos).getBlock().getName();
            return new Pick(new Target.Point(blockWorld), blockWorld, Kind.BLOCK, name, null);
        }

        // в небо или дальше дальности прицела — поверхность под концом луча, если чанк загружен
        BlockPos col = BlockPos.containing(end);
        if (!level.hasChunkAt(col)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
        Vec3 surface = new Vec3(col.getX() + 0.5, y, col.getZ() + 0.5);
        return new Pick(new Target.Point(surface), surface, Kind.SURFACE, Component.translatable("airstrike.target.surface"), null);
    }

    /**
     * Ближайшая сущность на отрезке. Идём отрезками по 16 блоков: один большой AABB на 400 блоков
     * перебирал бы тысячи секций, а клиент зовёт это каждый тик, пока смотрит в бинокль.
     */
    @Nullable
    private static EntityHitResult entityAlong(Level level, Entity viewer, Vec3 from, Vec3 to) {
        Predicate<Entity> filter = aimable(viewer);
        double length = from.distanceTo(to);
        if (length < 1.0e-3) return null;
        Vec3 dir = to.subtract(from).scale(1.0 / length);
        Entity best = null;
        Vec3 bestHit = null;
        double bestDist = Double.MAX_VALUE;
        for (double s = 0; s < length; s += SEGMENT) {
            if (best != null && s > bestDist + SEGMENT) break;
            Vec3 a = from.add(dir.scale(s));
            Vec3 b = from.add(dir.scale(Math.min(length, s + SEGMENT)));
            for (Entity e : level.getEntities(viewer, new AABB(a, b).inflate(2.0), filter)) {
                AABB box = e.getBoundingBox().inflate(e.getPickRadius() + 0.1);
                Vec3 hit = box.contains(from) ? from : box.clip(from, to).orElse(null);
                if (hit == null) continue;
                double d = from.distanceTo(hit);
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                    bestHit = hit;
                }
            }
        }
        return best == null ? null : new EntityHitResult(best, bestHit);
    }

    private static final double SEGMENT = 16.0;

    public static Predicate<Entity> aimable(Entity viewer) {
        Entity root = viewer.getRootVehicle();
        return e -> e.isAlive()
                && !e.isSpectator()
                && e != viewer
                && e.getRootVehicle() != root
                && !(e instanceof StrikeProjectile)
                && !e.getType().is(ModTags.AIM_IGNORE);
    }
}
