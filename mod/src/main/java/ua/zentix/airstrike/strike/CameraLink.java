package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.target.Sight;
import ua.zentix.airstrike.target.Target;

import java.util.Optional;
import java.util.UUID;

/**
 * Камера снаряда у оператора ({@code client.cam.ProjectileCamera}): с борта какого своего снаряда он смотрит и куда
 * повёрнута камера на подвесе. Клиент шлёт это каждый тик, пока смотрит с борта ({@code net.C2S.CameraView}); вид старше
 * {@link #FRESH} тиков — камеру закрыли или переключили на карту. Направление приходит здесь, а не поворотом игрока:
 * пока камера не на игроке, ванильный клиент поворот на сервер не шлёт ({@code LocalPlayer#sendPosition}).
 * <p>
 * По этому виду снаряд с камерой ({@link WeaponSpec.Tracking#CAMERA}) идёт за замеченной целью
 * ({@link Target.Sighted}): только пока оператор держит её в кадре ({@link #holds}). Вид у игрока один — и ведомый
 * снаряд у него один.
 *
 * @param projectile снаряд, с борта которого смотрит оператор
 * @param look       куда смотрит камера, единичный вектор
 * @param tick       игровое время, когда пришёл вид
 */
public record CameraLink(UUID projectile, Vec3 look, long tick) {
    /** Вид держится столько тиков после пакета: клиент шлёт каждый тик, запас — на задержку сети. */
    private static final int FRESH = 10;
    /**
     * Кадр — конус вокруг направления камеры, полуугол в градусах. Камера с борта сужает угол зрения клиента на
     * четверть: при обычных 70° в кадре ±26° по вертикали и ±41° по горизонтали на экране 16:9 — конус в 25° лежит
     * в кадре и по вертикали.
     */
    private static final double FRAME_DEGREES = 25;
    private static final double FRAME_COS = Math.cos(Math.toRadians(FRAME_DEGREES));
    /** Дальше этого камера снаряда цель не различает: столько же видит камера для перенацеливания ({@link ServerActions#retarget}). */
    public static final double REACH = 1024;

    /** Вид от клиента игрока {@code player}: принят, если снаряд его и в его мире. */
    public static void receive(ServerPlayer player, UUID projectile, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        if (!(player.serverLevel().getEntity(projectile) instanceof StrikeProjectile p) || !player.getUUID().equals(p.ownerId())) return;
        player.setData(ModAttachments.CAMERA_LINK, Optional.of(new CameraLink(projectile, Vec3.directionFromRotation(pitch, yaw),
                player.serverLevel().getGameTime())));
    }

    /**
     * Оператор снаряда {@code p} держит цель {@code subject} в её точке {@code at} в кадре камеры этого снаряда: смотрит
     * с его борта сейчас, точка в кадре и не дальше {@link #REACH}, его клиент её получает ({@link Sight#within}), а
     * камера её видит: сущность — {@link Sight#sees(ServerPlayer, Vec3, Entity)} из камеры, аппарат — луч до точки
     * может упереться только в сам аппарат ({@link SubLevels#visibleFrom}), место — {@link Sight#clear}.
     */
    public static boolean holds(ServerLevel level, StrikeProjectile p, Target subject, Vec3 at) {
        ServerPlayer operator = p.ownerPlayer();
        return operator != null && operator.serverLevel() == level && holds(operator, p, subject, at);
    }

    /** То же для уже найденного оператора {@code operator} в мире снаряда (см. {@link #holds(ServerLevel, StrikeProjectile, Target, Vec3)}). */
    public static boolean holds(ServerPlayer operator, StrikeProjectile p, Target subject, Vec3 at) {
        ServerLevel level = operator.serverLevel();
        Optional<CameraLink> view = operator.getData(ModAttachments.CAMERA_LINK);
        if (view.isEmpty() || !view.get().projectile.equals(p.getUUID()) || level.getGameTime() - view.get().tick > FRESH) return false;
        Vec3 eye = p.getEyePosition();
        Vec3 to = at.subtract(eye);
        double distance = to.length();
        if (distance > REACH || distance > 1e-6 && to.dot(view.get().look) < distance * FRAME_COS) return false;
        if (!Sight.within(operator, at)) return false;
        return switch (subject) {
            case Target.OfEntity e -> {
                Entity entity = level.getEntity(e.uuid());
                yield entity != null && Sight.sees(operator, eye, entity);
            }
            case Target.OfSubLevel s -> SubLevels.visibleFrom(level, eye, s.plotPos());
            default -> Sight.clear(level, eye, at);
        };
    }
}
