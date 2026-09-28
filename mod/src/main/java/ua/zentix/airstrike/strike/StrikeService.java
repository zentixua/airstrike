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
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.world.Terrain;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;

import java.util.UUID;

/**
 * Пуск: одно место для команд, пульта, залпов и тестов.
 * <p>
 * Шахед и крылатая ракета стартуют с мобильной пусковой рядом со стреляющим ({@link LaunchSite}): поджиг,
 * сход с направляющей на ускорителе, сброс ускорителя, выход на маршрут. Маршрут ({@link Route}) — петля в обход
 * и заход на цель с направления взгляда стреляющего, «из-за спины», на всё время полёта из настроек (шахед 50 с,
 * ракета 30 с). Большая часть пути проходит вне загруженного мира ({@link VirtualFlights}). Без стреляющего рядом
 * (консоль, командный блок, игрок в другом мире) или без места под пусковую — заход издалека по той же схеме.
 * B-2 всегда заходит издалека.
 */
public final class StrikeService {
    /** Радиус, в котором слышна сирена и видна тревога. */
    public static final double ALERT_RADIUS = 350;

    private StrikeService() {}

    /**
     * Итог пуска.
     *
     * @param eta через сколько тиков удар (для сообщения стреляющему)
     */
    public record Result(boolean ok, int eta) {
        static final Result FAILED = new Result(false, 0);
    }

    /**
     * @param approachYaw курс захода (обычно — курс взгляда игрока): снаряд приходит «из-за спины» стреляющего
     * @param siren       включить сирену у цели на подлёте (у залпа сирена одна на весь залп)
     * @param nuke        мощность и подрыв ядерной боеголовки
     * @param carrierNuke ядерная БЧ на крылатой ракете или бомбе (МБР — всегда ядерная)
     */
    public static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                @Nullable UUID owner, boolean siren, Loadout.Nuke nuke, boolean carrierNuke) {
        ServerPlayer shooter = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        if (shooter != null && shooter.level() != level) shooter = null;
        if (weapon == WeaponType.NUKE) {
            // МБР бьёт по координатам: за движущейся целью не следит; тревогу поднимает сам пуск
            boolean ok = NuclearStrikes.launch(level, NuclearStrikes.ground(level, point), nuke.yieldKt(), nuke.airBurst(), shooter);
            return new Result(ok, AirstrikeConfig.SERVER.nukeFlightTime.get());
        }
        Loadout.Nuke warhead = carrierNuke && Loadout.carriesNuke(weapon) ? nuke : null;
        StrikeProjectile p = switch (weapon) {
            case DRONE, MISSILE -> launchGuided(level, weapon, target, point, approachYaw, owner, shooter);
            case ROCKET -> launchRocket(level, target, point, approachYaw, owner, shooter);
            case LOITER -> launchLoiter(level, target, point, approachYaw, owner, shooter);
            default -> launchBomber(level, point, approachYaw, owner);
        };
        if (p == null) return Result.FAILED;
        p.setNuclear(warhead);
        if (siren && AirstrikeConfig.SERVER.siren.get()) p.armSiren(sirenLead(weapon));
        int eta = p.etaTicks();
        if (!p.isVirtual() && !level.addFreshEntity(p)) return Result.FAILED;
        return new Result(true, eta);
    }

    /** За сколько до удара цель «видит» снаряд и включается тревога: шахед 25 с, ракета 15 с, РСЗО 8 с, B-2 и барраж 20 с. */
    private static int sirenLead(WeaponType weapon) {
        return switch (weapon) {
            case DRONE -> 500;
            case MISSILE -> 300;
            case ROCKET -> 160;
            case LOITER -> 400;
            default -> 400;
        };
    }

    private static double cruiseSpeed(WeaponType weapon) {
        return weapon == WeaponType.DRONE ? DroneEntity.CRUISE_SPEED : CruiseMissileEntity.CRUISE_SPEED;
    }

    /** Длина маршрута на заданное время полёта. */
    private static double pathLength(WeaponType weapon) {
        int seconds = weapon == WeaponType.DRONE ? AirstrikeConfig.SERVER.droneFlightTime.get() : AirstrikeConfig.SERVER.missileFlightTime.get();
        return cruiseSpeed(weapon) * seconds * 20;
    }

    /** Последний прямой участок перед целью: шахед 300 блоков, ракета 500 — и всегда из-за спины стреляющего. */
    private static double entryDistance(WeaponType weapon, Vec3 point, @Nullable ServerPlayer shooter) {
        double base = weapon == WeaponType.DRONE ? 300 : 500;
        if (shooter == null) return base;
        double dx = point.x - shooter.getX(), dz = point.z - shooter.getZ();
        return Math.max(base, Math.sqrt(dx * dx + dz * dz) + 150);
    }

    @Nullable
    private static StrikeProjectile create(ServerLevel level, WeaponType weapon) {
        return weapon == WeaponType.DRONE ? ModEntities.DRONE.get().create(level) : ModEntities.CRUISE_MISSILE.get().create(level);
    }

    /** Шахед или ракета: с пусковой рядом со стреляющим, иначе издалека. Снаряд ещё не добавлен в мир. */
    @Nullable
    private static StrikeProjectile launchGuided(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float yaw,
                                                 @Nullable UUID owner, @Nullable ServerPlayer shooter) {
        Vec3 dir = Local.horizontal(yaw);
        double length = pathLength(weapon);
        double entry = entryDistance(weapon, point, shooter);
        double side = level.random.nextBoolean() ? 1 : -1;
        if (shooter != null && AirstrikeConfig.SERVER.launchNearPlayer.get()) {
            StrikeProjectile p = fromLauncher(level, weapon, target, point, dir, length, entry, side, shooter);
            if (p != null) return p;
        }
        return fromAfar(level, weapon, target, point, dir, length, entry, side, owner);
    }

    @Nullable
    private static StrikeProjectile fromLauncher(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Vec3 dir,
                                                 double length, double entry, double side, ServerPlayer shooter) {
        LauncherEntity launcher = LaunchSite.existing(level, shooter, weapon);
        if (launcher == null) {
            Vec3 site = LaunchSite.find(level, shooter);
            if (site == null) return null;
            // пакет смотрит на первую точку маршрута
            Route plan = Route.plan(site, point, dir, length, entry, side);
            Vec3 first = plan.current() == null ? point : plan.current();
            launcher = LaunchSite.deploy(level, site, FlightController.anglesTo(site, first)[0], weapon, shooter);
        }
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        int[] slot = launcher.reserve(level.getGameTime(), 12, 24);
        Vec3 rail = launcher.railPoint(slot[0]);
        int hidden = (int) Math.max(0, launcher.deployedAt() + LauncherEntity.DEPLOY_TICKS - level.getGameTime());
        p.placeOnLauncher(rail, launcher.getYRot(), launcher.elevation(), slot[1], hidden, target, point, shooter.getUUID());
        p.setRoute(Route.plan(rail, point, dir, length, entry, side));
        return p;
    }

    /**
     * Заход издалека: снаряд начинает полёт вне мира на прямой захода, на расстоянии времени полёта от цели
     * (а если это место уже загружено — сразу в мире).
     */
    private static StrikeProjectile fromAfar(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Vec3 dir,
                                             double length, double entry, double side, @Nullable UUID owner) {
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        // короткий полёт из настроек: старт не ближе точки входа, иначе первым делом разворот назад
        length = Math.max(length, entry);
        Vec3 start = point.subtract(dir.scale(length)).add(0, weapon == WeaponType.DRONE ? DroneEntity.CRUISE_HEIGHT : 12, 0);
        p.launch(start, target, point, owner);
        p.setRoute(Route.plan(start, point, dir, length, entry, side));
        startVirtual(level, p);
        return p;
    }

    /** Труба пакета РСЗО после пуска перезаряжается минуту. */
    public static final int ROCKET_RELOAD = 1200;
    /** Пусковая РСЗО без стреляющего рядом стоит за столько блоков от цели (по направлению захода). */
    private static final double ROCKET_STANDOFF = 600;

    /**
     * РСЗО: снаряд в трубе пакета у стреляющего (пакет доворачивается на цель, если молчит), иначе с позиции
     * за {@link #ROCKET_STANDOFF} блоков. Неуправляемый: своё рассеивание ~1% дальности, за целью не следит.
     */
    @Nullable
    private static StrikeProjectile launchRocket(ServerLevel level, Target target, Vec3 point, float yaw,
                                                 @Nullable UUID owner, @Nullable ServerPlayer shooter) {
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        if (r == null) return null;
        if (shooter != null && AirstrikeConfig.SERVER.launchNearPlayer.get()) {
            LauncherEntity launcher = LaunchSite.existing(level, shooter, WeaponType.ROCKET);
            if (launcher == null) {
                Vec3 site = LaunchSite.find(level, shooter);
                if (site != null) launcher = LaunchSite.deploy(level, site, FlightController.anglesTo(site, point)[0], WeaponType.ROCKET, shooter);
            } else {
                launcher.turnTo(FlightController.anglesTo(launcher.position(), point)[0], level.getGameTime());
            }
            if (launcher != null) {
                int[] slot = launcher.reserve(level.getGameTime(), 10, ROCKET_RELOAD);
                Vec3 rail = launcher.railPoint(slot[0]);
                int hidden = (int) Math.max(0, launcher.deployedAt() + LauncherEntity.DEPLOY_TICKS - level.getGameTime());
                r.placeInTube(rail, launcher.getYRot(), launcher.elevation(), slot[1], hidden, target, scatter(level, rail, point),
                        shooter.getUUID());
                return r;
            }
        }
        Vec3 from = point.subtract(Local.horizontal(yaw).scale(ROCKET_STANDOFF));
        r.launchFrom(from, target, scatter(level, from, point), owner);
        startVirtual(level, r);
        return r;
    }

    /** Пусковая барражирующих боеприпасов без стреляющего рядом стоит за столько блоков от цели. */
    private static final double LOITER_STANDOFF = 500;

    /**
     * Барражирующий боеприпас: с катапульты у стреляющего (доворачивается на цель, если молчит) прямо к цели,
     * иначе — издалека на высоте круга.
     */
    @Nullable
    private static StrikeProjectile launchLoiter(ServerLevel level, Target target, Vec3 point, float yaw,
                                                 @Nullable UUID owner, @Nullable ServerPlayer shooter) {
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        if (e == null) return null;
        if (shooter != null && AirstrikeConfig.SERVER.launchNearPlayer.get()) {
            LauncherEntity launcher = LaunchSite.existing(level, shooter, WeaponType.LOITER);
            if (launcher == null) {
                Vec3 site = LaunchSite.find(level, shooter);
                if (site != null) launcher = LaunchSite.deploy(level, site, FlightController.anglesTo(site, point)[0], WeaponType.LOITER, shooter);
            } else {
                launcher.turnTo(FlightController.anglesTo(launcher.position(), point)[0], level.getGameTime());
            }
            if (launcher != null) {
                int[] slot = launcher.reserve(level.getGameTime(), 12, 24);
                Vec3 rail = launcher.railPoint(slot[0]);
                int hidden = (int) Math.max(0, launcher.deployedAt() + LauncherEntity.DEPLOY_TICKS - level.getGameTime());
                e.placeOnLauncher(rail, launcher.getYRot(), launcher.elevation(), slot[1], hidden, target, point, shooter.getUUID());
                e.setRoute(null);
                return e;
            }
        }
        Vec3 from = point.subtract(Local.horizontal(yaw).scale(LOITER_STANDOFF)).add(0, LoiterEntity.LOITER_HEIGHT, 0);
        e.launch(from, target, point, owner);
        e.setRoute(null);
        startVirtual(level, e);
        return e;
    }

    /** Рассеивание неуправляемого снаряда: ~1% дальности по нормали (у «Града» на 20 км — сотни метров). */
    private static Vec3 scatter(ServerLevel level, Vec3 from, Vec3 point) {
        double sigma = Math.max(1, Math.sqrt(from.distanceToSqr(point.x, from.y, point.z)) * 0.01);
        return point.add(level.random.nextGaussian() * sigma, 0, level.random.nextGaussian() * sigma);
    }

    /**
     * Бомба бьёт по точке на поверхности над целью (с разбросом ±2.5 блока) и за движущейся целью не следит;
     * если цель глубже 4 блоков под поверхностью (пещера, бункер), бомба пробивается к ней.
     */
    private static StrikeProjectile launchBomber(ServerLevel level, Vec3 point, float yaw, @Nullable UUID owner) {
        double jx = (level.random.nextInt(51) - 25) / 10.0, jz = (level.random.nextInt(51) - 25) / 10.0;
        int sx = Mth.floor(point.x + jx), sz = Mth.floor(point.z + jz);
        // высота поверхности — только из готового чанка (иначе по высоте цели): чанк ради пуска не грузим
        double sy = Terrain.ready(level, new BlockPos(sx, 0, sz))
                ? Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz) : Math.floor(point.y) + 1;
        Vec3 surface = new Vec3(point.x + jx, sy - 0.5, point.z + jz);
        BlockPos goal = surface.y - point.y >= 4 ? BlockPos.containing(point) : null;
        BomberEntity e = ModEntities.BOMBER.get().create(level);
        if (e == null) return null;
        double length = BomberEntity.CRUISE_SPEED * AirstrikeConfig.SERVER.bomberFlightTime.get() * 20 + BomberEntity.RELEASE_DISTANCE;
        e.launch(surface.subtract(Local.horizontal(yaw).scale(length)), surface, goal, owner);
        e.setRoute(null);
        startVirtual(level, e);
        return e;
    }

    /** Полёт начинается вне мира; в загруженном месте снаряд вернётся в мир в ближайшем тике. */
    private static void startVirtual(ServerLevel level, StrikeProjectile p) {
        VirtualFlights.launch(level, p);
    }

    /** Сирена и «ВОЗДУШНАЯ ТРЕВОГА» у всех в 350 блоках от цели (у каждого своя память, см. клиент). */
    public static void siren(ServerLevel level, WeaponType weapon, Vec3 at) {
        if (!AirstrikeConfig.SERVER.siren.get()) return;
        int kind = weapon.siren() == WeaponType.SirenKind.MISSILE ? S2C.Siren.MISSILE : S2C.Siren.AIR_RAID;
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, ALERT_RADIUS, new S2C.Siren(at, kind));
    }

    /** Строка в лог сервера на каждый приказ (для tools/logscan.py): кто, чем, сколько, куда. */
    public static void log(String who, WeaponType weapon, int count, int spread, Vec3 point) {
        Airstrike.LOG.info("Удар: {} ×{} разброс {} по {} {} {} — {}", weapon.getSerializedName(), count, spread,
                Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z), who);
    }

    /** Строка над хотбаром и щелчок пульта у того, кто пустил: что пущено и через сколько удар. */
    public static void confirm(ServerPlayer player, WeaponType weapon, int etaTicks) {
        player.displayClientMessage(Component.translatable("airstrike.launched." + weapon.getSerializedName(), (etaTicks + 19) / 20)
                .withStyle(ChatFormatting.RED), true);
        player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 1.0f, weapon == WeaponType.DRONE ? 0.6f : 0.5f);
    }
}
