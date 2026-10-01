package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Ballistics;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.util.Local;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Где поставить пусковую: позади и сбоку от стреляющего, на ровной твёрдой земле под открытым небом, только
 * в готовых чанках (ничего не грузим ради пуска). Своя пусковая того же оружия в {@link #REUSE_RADIUS} — берём её:
 * залп идёт с одной установки. Больше {@link #MAX_PER_OWNER} установок у игрока не бывает — старая убирается.
 * <p>
 * Пакет шахедов и ракет ставится только туда и так, чтобы сектор пуска был свободен ({@link #clearAhead}): на разгоне и
 * наборе до взведения взрывателя снаряд, встретив дом, разбивается без подрыва — в городе весь залп с пусковой, смотрящей
 * в дом, пропадал молча (ноутбук 30.09.2026, Greenfield: 0 ударов из 30, каждый второй прогон).
 */
public final class LaunchSite {
    public static final double REUSE_RADIUS = 96;
    public static final int MAX_PER_OWNER = 3;
    /** Ближе этого к другой пусковой новую не ставим. */
    private static final double CLEARANCE = 9;

    /** Места относительно игрока: [назад, влево] в блоках, по порядку предпочтения. */
    private static final double[][] CANDIDATES = {
            {18, 11}, {18, -11}, {24, 0}, {13, 17}, {13, -17}, {30, 13}, {30, -13}, {4, 22}, {4, -22}, {38, 0}, {24, 24}, {24, -24}
    };

    private LaunchSite() {}

    /** Своя пусковая этого оружия рядом с игроком. */
    @Nullable
    public static LauncherEntity existing(ServerLevel level, ServerPlayer player, WeaponType weapon) {
        return existing(level, player, weapon, l -> true);
    }

    /** Своя пусковая этого оружия рядом с игроком, которая годится ({@code fits}: например, сектор пуска свободен). */
    @Nullable
    public static LauncherEntity existing(ServerLevel level, ServerPlayer player, WeaponType weapon, Predicate<LauncherEntity> fits) {
        UUID id = player.getUUID();
        AABB box = player.getBoundingBox().inflate(REUSE_RADIUS, 64, REUSE_RADIUS);
        return level.getEntitiesOfClass(LauncherEntity.class, box, l -> l.isAlive() && l.weapon() == weapon && id.equals(l.ownerId()) && fits.test(l))
                .stream().min(Comparator.comparingDouble(l -> l.distanceToSqr(player))).orElse(null);
    }

    /**
     * Место и курс пусковой.
     *
     * @param preferred номер курса из предложенных, или −1 — пришлось повернуть: предложенные упирались в постройку
     */
    public record Pick(Vec3 site, float yaw, int preferred) {}

    /** Повороты от первого предложенного курса, если ни один предложенный не свободен, °. */
    private static final float[] TURNS = {30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};

    /**
     * Место под новую пусковую с свободным сектором пуска: на каждом месте по порядку ({@link #find}) — предложенные
     * курсы ({@code yaws} от места), потом повороты от первого, не дальше {@code maxOff}° от направления на цель
     * {@code target} (дальше — залп уходил бы от цели, это выглядит поломкой). Null — нигде: снаряд заходит издалека.
     */
    @Nullable
    public static Pick findClear(ServerLevel level, ServerPlayer player, WeaponType weapon, Vec3 target, Function<Vec3, float[]> yaws,
                                 float maxOff) {
        float yaw = player.getYRot();
        for (double[] c : CANDIDATES) {
            Vec3 off = Local.offset(yaw, 0, c[1], 0, -c[0]);
            Vec3 p = player.position().add(off);
            Vec3 site = check(level, Mth.floor(p.x), Mth.floor(p.z));
            if (site == null || taken(level, site)) continue;
            Pick pick = pickOn(level, site, weapon, yaws.apply(site), FlightController.anglesTo(site, target)[0], maxOff);
            if (pick != null) return pick;
        }
        return null;
    }

    /**
     * Курс на месте {@code site}: первый свободный из предложенных {@code want}, потом повороты от первого не дальше
     * {@code maxOff}° от курса на цель {@code toTarget}; null — все заняты.
     */
    @Nullable
    public static Pick pickOn(ServerLevel level, Vec3 site, WeaponType weapon, float[] want, float toTarget, float maxOff) {
        for (int i = 0; i < want.length; i++) {
            if (clearAhead(level, site, want[i], weapon)) return new Pick(site, want[i], i);
        }
        for (float t : TURNS) {
            float yaw = Mth.wrapDegrees(want[0] + t);
            if (Math.abs(Mth.wrapDegrees(yaw - toTarget)) > maxOff) continue;
            if (clearAhead(level, site, yaw, weapon)) return new Pick(site, yaw, -1);
        }
        return null;
    }

    /** Сектор пуска стоящей пусковой (с её нынешним курсом) свободен. */
    public static boolean clearAhead(ServerLevel level, LauncherEntity launcher) {
        return clearAhead(level, launcher.position(), launcher.getYRot(), launcher.weapon());
    }

    /**
     * Путь снаряда от нижней направляющей пусковой, стоящей в {@code site} с курсом {@code yaw}, до взведения взрывателя
     * ({@link StrikeProjectile#ARM_DISTANCE} по горизонтали) не упирается в блоки: луч под углом набора на разгоне —
     * меньшим из угла направляющей и тангажа к концу разгона (паспорт, {@code LaunchProfile#boostEndPitch}); у снаряда
     * без разгона (РСЗО) — угол трубы, конец луча опущен на падение по баллистике на маршевой скорости ({@link Ballistics#GRAVITY}).
     * Два луча: ось и на блок ниже (корпус). Неготовые чанки не читаются: путь по ним считается свободным (там снаряд
     * уйдёт в полёт вне мира).
     */
    public static boolean clearAhead(ServerLevel level, Vec3 site, float yaw, WeaponType weapon) {
        Vec3 rail = LauncherEntity.railPoint(site, yaw, weapon, 0);
        float elevation = LauncherEntity.elevation(weapon);
        WeaponSpec.Airframe air = weapon.spec().airframe();
        WeaponSpec.LaunchProfile lp = air.launchProfile();
        double climb = Math.toRadians(lp == null ? elevation : Math.min(elevation, -lp.boostEndPitch()));
        double reach = StrikeProjectile.ARM_DISTANCE;
        double drop = 0;
        if (lp == null) {
            double t = reach / (air.cruiseSpeed() * Math.cos(climb));
            drop = Ballistics.GRAVITY * t * t / 2;
        }
        Vec3 path = Local.horizontal(yaw).scale(reach).add(0, reach * Math.tan(climb) - drop, 0);
        for (double below : new double[]{0, 1}) {
            Vec3 from = rail.subtract(0, below, 0);
            Vec3 to = Terrain.readyUntil(level, from, from.add(path));
            if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()))
                    .getType() != HitResult.Type.MISS) return false;
        }
        return true;
    }

    /** Место под новую пусковую или null (игрок в воде, в пещере без неба над ним поблизости, мир не готов). */
    @Nullable
    public static Vec3 find(ServerLevel level, ServerPlayer player) {
        float yaw = player.getYRot();
        for (double[] c : CANDIDATES) {
            Vec3 off = Local.offset(yaw, 0, c[1], 0, -c[0]);
            Vec3 p = player.position().add(off);
            Vec3 site = check(level, Mth.floor(p.x), Mth.floor(p.z));
            if (site != null && !taken(level, site)) return site;
        }
        return null;
    }

    /** Рядом уже стоит пусковая (своя другого оружия или чужая): прицеп 7.6 м, пакеты выше 4 м — не ставить внахлёст. */
    private static boolean taken(ServerLevel level, Vec3 site) {
        return !level.getEntitiesOfClass(LauncherEntity.class, new AABB(site, site).inflate(CLEARANCE, 8, CLEARANCE), LauncherEntity::isAlive).isEmpty();
    }

    /** Ровно (±1 блок в квадрате 5×5), твёрдо, не вода, над головой пусто (листва тоже мешает). */
    @Nullable
    private static Vec3 check(ServerLevel level, int x, int z) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (!Terrain.ready(level, new BlockPos(x + dx, 0, z + dz))) return null;
            }
        }
        int y = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() + 1) return null;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                int h = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                if (Math.abs(h - y) > 1) return null;
                if (Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz) > h + 1) return null;
            }
        }
        BlockPos below = new BlockPos(x, y - 1, z);
        BlockState ground = level.getBlockState(below);
        if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level, below, Direction.UP)) return null;
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    /** Новая пусковая; лишние старые установки игрока убираются. */
    public static LauncherEntity deploy(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, ServerPlayer owner) {
        List<LauncherEntity> mine = new ArrayList<>();
        for (var e : level.getEntities(ModEntities.LAUNCHER.get(), l -> owner.getUUID().equals(l.ownerId()))) mine.add(e);
        mine.sort(Comparator.comparingLong(LauncherEntity::deployedAt));
        for (int i = 0; i <= mine.size() - MAX_PER_OWNER; i++) mine.get(i).discard();
        LauncherEntity l = LauncherEntity.create(level, site, yaw, weapon, owner.getUUID());
        level.addFreshEntity(l);
        return l;
    }
}
