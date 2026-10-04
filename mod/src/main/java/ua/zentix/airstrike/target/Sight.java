package ua.zentix.airstrike.target;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import ua.zentix.airstrike.util.Terrain;

/**
 * Прямая видимость на сервере: что игрок видит своими глазами. Сервер не знает, что у игрока на экране, поэтому
 * «видит» значит то же, что у самого Minecraft «сущность приходит клиенту» ({@code ChunkMap.TrackedEntity.updatePlayer}),
 * плюс чистая линия от глаз до тела. Ни одна проверка не грузит чанков: отрезок через неготовый чанк — не видно.
 * <p>
 * Преграда — то, что заслоняет взгляд ({@link ClipContext.Block#VISUAL}): стекло и решётки прозрачны, листва,
 * стены и крыши — нет; вода и лава взгляд не останавливают. С Sable блоки летательных аппаратов тоже заслоняют:
 * ванильный {@code Level.clip} у него видит и их.
 */
public final class Sight {
    private Sight() {}

    /**
     * Игрок {@code viewer} видит сущность {@code target}: она в его мире и не невидима для него (зелье невидимости, кроме
     * своих по команде с {@code seeFriendlyInvisibles}), Minecraft отдаёт её его клиенту ({@link Entity#broadcastToPlayer}:
     * наблюдатель не виден), она не дальше по горизонтали, чем клиент её получает ({@link #range}), и от глаз игрока до её
     * глаз или середины тела взгляд ничего не заслоняет ({@link #clear}).
     */
    public static boolean sees(ServerPlayer viewer, Entity target) {
        if (target == viewer || target.level() != viewer.level() || !target.isAlive()) return false;
        if (target.isInvisibleTo(viewer) || !target.broadcastToPlayer(viewer)) return false;
        if (!within(viewer, target.position(), range(viewer, target))) return false;
        ServerLevel level = viewer.serverLevel();
        Vec3 eye = viewer.getEyePosition();
        return clear(level, eye, target.getEyePosition()) || clear(level, eye, target.getBoundingBox().getCenter());
    }

    /**
     * Точка {@code at} в дальности, докуда сервер вообще отдаёт сущности клиенту игрока ({@link #viewRange}), по
     * горизонтали. Мир точки — мир игрока (вызывающий проверяет сам).
     */
    public static boolean within(ServerPlayer viewer, Vec3 at) {
        return within(viewer, at, viewRange(viewer));
    }

    /**
     * Докуда по горизонтали клиент игрока получает сущность — как у Minecraft: дальность отслеживания её типа (с
     * пассажирами — наибольшая; с поправкой сервера {@code entity-broadcast-range-percentage}), но не дальше
     * {@link #viewRange}.
     */
    public static double range(ServerPlayer viewer, Entity target) {
        int tracking = target.getType().clientTrackingRange() * 16;
        for (Entity passenger : target.getIndirectPassengers()) {
            tracking = Math.max(tracking, passenger.getType().clientTrackingRange() * 16);
        }
        return Math.min(viewer.server.getScaledTrackingDistance(tracking), viewRange(viewer));
    }

    /**
     * Дальность прорисовки игрока в блоках: его настройка, зажатая дальностью сервера, — дальше этого сервер
     * сущностей ему не отдаёт ({@code ChunkMap.TrackedEntity.updatePlayer}).
     */
    public static int viewRange(ServerPlayer viewer) {
        int serverView = Mth.clamp(viewer.server.getPlayerList().getViewDistance(), 2, 32);
        return Mth.clamp(viewer.requestedViewDistance(), 2, serverView) * 16;
    }

    private static boolean within(ServerPlayer viewer, Vec3 at, double range) {
        double dx = at.x - viewer.getX(), dz = at.z - viewer.getZ();
        return dx * dx + dz * dz <= range * range;
    }

    /**
     * Взгляд из {@code from} в {@code to} ничем не заслонён. Отрезок, который заходит в неготовый чанк, — «не видно»:
     * чанк ради взгляда не грузится ({@link Terrain#readyAlong}).
     */
    public static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        if (!Terrain.readyAlong(level, from, to)) return false;
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
                .getType() == HitResult.Type.MISS;
    }
}
