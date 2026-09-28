package ua.zentix.airstrike.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;

import java.util.UUID;

/**
 * Пуск: одно место для команд, пульта, залпов и тестов. Снаряд появляется позади цели по направлению захода
 * (как в датапаке: шахед в 190/140/90/50 блоках и на 45 выше, ракета в 220/170/120/70, B-2 в 260/200/150/110),
 * в первой из этих точек, где мир уже загружен и тикает.
 */
public final class StrikeService {
    /** Радиус, в котором слышна сирена и видна тревога. */
    public static final double ALERT_RADIUS = 350;

    private StrikeService() {}

    /**
     * @param approachYaw курс захода (обычно — курс взгляда игрока): снаряд приходит «из-за спины» стреляющего
     * @param siren       включить сирену у цели (у залпа сирена одна на весь залп)
     * @param nuke        мощность и подрыв ядерной боеголовки (для остального оружия не используется)
     * @return пуск состоялся
     */
    public static boolean launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                 @Nullable UUID owner, boolean siren, Loadout.Nuke nuke) {
        if (weapon == WeaponType.NUKE) {
            // МБР бьёт по координатам: за движущейся целью не следит; тревогу поднимает сам пуск
            ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
            return NuclearStrikes.launch(level, NuclearStrikes.ground(level, point), nuke.yieldKt(), nuke.airBurst(), player);
        }
        StrikeProjectile p = switch (weapon) {
            case DRONE -> launchDrone(level, target, point, approachYaw, owner);
            case MISSILE -> launchMissile(level, target, point, approachYaw, owner);
            default -> launchBomber(level, point, approachYaw, owner);
        };
        if (p != null && siren) siren(level, weapon, point);
        return p != null;
    }

    /**
     * Первая точка захода, где мир тикает; если ни одна из штатных — ближе к цели шагами по 16 блоков
     * (сущность в нетикающем чанке так и повисла бы в воздухе). Новые чанки ради пуска не грузим.
     */
    private static Vec3 start(ServerLevel level, Vec3 point, float yaw, double up, double... distances) {
        Vec3 back = Local.horizontal(yaw).scale(-1);
        for (double d : distances) {
            Vec3 p = point.add(back.scale(d)).add(0, up, 0);
            if (level.isPositionEntityTicking(BlockPos.containing(p))) return p;
        }
        for (double d = distances[distances.length - 1] - 16; d >= 16; d -= 16) {
            Vec3 p = point.add(back.scale(d)).add(0, up, 0);
            if (level.isPositionEntityTicking(BlockPos.containing(p))) return p;
        }
        return point.add(back.scale(16)).add(0, up, 0);
    }

    private static StrikeProjectile launchDrone(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner) {
        DroneEntity e = ModEntities.DRONE.get().create(level);
        if (e == null) return null;
        e.launch(start(level, point, yaw, 45, 190, 140, 90, 50), target, point, owner);
        return level.addFreshEntity(e) ? e : null;
    }

    private static StrikeProjectile launchMissile(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner) {
        CruiseMissileEntity e = ModEntities.CRUISE_MISSILE.get().create(level);
        if (e == null) return null;
        e.launch(start(level, point, yaw, 0, 220, 170, 120, 70), target, point, owner);
        return level.addFreshEntity(e) ? e : null;
    }

    /**
     * Бомба бьёт по точке на поверхности над целью (с разбросом ±2.5 блока) и за движущейся целью не следит;
     * если цель глубже 4 блоков под поверхностью (пещера, бункер), бомба пробивается к ней.
     */
    private static StrikeProjectile launchBomber(ServerLevel level, Vec3 point, float yaw, @Nullable UUID owner) {
        double jx = (level.random.nextInt(51) - 25) / 10.0, jz = (level.random.nextInt(51) - 25) / 10.0;
        int sx = Mth.floor(point.x + jx), sz = Mth.floor(point.z + jz);
        int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
        Vec3 surface = new Vec3(point.x + jx, sy - 0.5, point.z + jz);
        BlockPos goal = surface.y - point.y >= 4 ? BlockPos.containing(point) : null;
        BomberEntity e = ModEntities.BOMBER.get().create(level);
        if (e == null) return null;
        e.launch(start(level, surface, yaw, 0, 260, 200, 150, 110), surface, goal, owner);
        return level.addFreshEntity(e) ? e : null;
    }

    /** Сирена и «ВОЗДУШНАЯ ТРЕВОГА» у всех в 350 блоках от цели (у каждого своя память, см. клиент). */
    public static void siren(ServerLevel level, WeaponType weapon, Vec3 at) {
        if (!AirstrikeConfig.SERVER.siren.get()) return;
        int kind = weapon == WeaponType.MISSILE ? S2C.Siren.MISSILE : S2C.Siren.AIR_RAID;
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, ALERT_RADIUS, new S2C.Siren(at, kind));
    }

    /** Строка над хотбаром и щелчок пульта у того, кто пустил. */
    /** Строка в лог сервера на каждый приказ (для tools/logscan.py): кто, чем, сколько, куда. */
    public static void log(String who, WeaponType weapon, int count, int spread, Vec3 point) {
        Airstrike.LOG.info("Удар: {} ×{} разброс {} по {} {} {} — {}", weapon.getSerializedName(), count, spread,
                Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z), who);
    }

    public static void confirm(ServerPlayer player, WeaponType weapon) {
        player.displayClientMessage(Component.translatable("airstrike.launched." + weapon.getSerializedName()).withStyle(ChatFormatting.RED), true);
        player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 1.0f, weapon == WeaponType.DRONE ? 0.6f : 0.5f);
    }
}
