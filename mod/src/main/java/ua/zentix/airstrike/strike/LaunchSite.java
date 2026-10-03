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
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.flight.ProximityFuse;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Autopilot;
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
 * в дом, пропадал молча (ноутбук 30.09.2026, Greenfield: 0 ударов из 30, каждый второй прогон). И не между высотками,
 * выше которых снаряд после взведения набрать не успевает: место без такого подъёма не годится, берётся другое
 * или заход издалека.
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
    /** Шаг ломаной по дуге РСЗО, тиков: 2 тика — до ~10 блоков на ускорителе. */
    private static final int ARC_STEP = 2;
    /** Ближе к цели по горизонтали дуга РСЗО не проверяется: крыша самой цели не закрывает сектор, блоков. */
    private static final double NEAR_AIM = 8;
    /** Корпус снаряда ниже оси на столько блоков: над блоками должен пройти и он. */
    private static final double HULL = 1;

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
            Pick pick = pickOn(level, site, weapon, yaws.apply(site), target, maxOff);
            if (pick != null) return pick;
        }
        return null;
    }

    /**
     * Курс на месте {@code site}: первый свободный из предложенных {@code want}, потом повороты от первого не дальше
     * {@code maxOff}° от курса на цель {@code target}; null — все заняты.
     */
    @Nullable
    public static Pick pickOn(ServerLevel level, Vec3 site, WeaponType weapon, float[] want, Vec3 target, float maxOff) {
        float toTarget = FlightController.anglesTo(site, target)[0];
        for (int i = 0; i < want.length; i++) {
            if (clearAhead(level, site, want[i], weapon, target)) return new Pick(site, want[i], i);
        }
        for (float t : TURNS) {
            float yaw = Mth.wrapDegrees(want[0] + t);
            if (Math.abs(Mth.wrapDegrees(yaw - toTarget)) > maxOff) continue;
            if (clearAhead(level, site, yaw, weapon, target)) return new Pick(site, yaw, -1);
        }
        return null;
    }

    /** Сектор пуска стоящей пусковой (с её нынешним курсом) по цели {@code target} свободен. */
    public static boolean clearAhead(ServerLevel level, LauncherEntity launcher, Vec3 target) {
        return clearAhead(level, launcher.position(), launcher.getYRot(), launcher.weapon(), target);
    }

    /**
     * Сектор пуска пусковой, стоящей в {@code site} с курсом {@code yaw}, свободен — из каждой ячейки пакета: залп идёт
     * со всех, и снаряд, которому блок стоит только на пути его ячейки, разбивается до взведения. Путь снаряда от
     * направляющей до взведения взрывателя ({@link ProximityFuse#ARM_DISTANCE} по горизонтали) не упирается в блоки.
     * У снаряда с разгоном (паспорт, {@code LaunchProfile}) — и его путь носа на разгоне ({@link #boostSweep}: тот же
     * закон, что в полёте), и луч под углом набора: меньшим из угла направляющей и тангажа к концу разгона
     * ({@code boostEndPitch}) — ниже него снаряд до взведения не опускается, а разгон идёт выше, под навес, крону или
     * край дома над лучом. До 03.10.2026 проверялись только луч и только из первой ячейки (02.10.2026, Newisle: из залпа
     * 20 ракет разбилась ровно каждая вторая — все из одного контейнера, об один и тот же блок). У РСЗО — его настоящая дуга из трубы на цель {@code target} (скорость
     * задаёт дальность, {@link RocketEntity}): до конца работы двигателя (дальше он взведён) и не ближе {@link #NEAR_AIM}
     * к цели — у самой цели блоки — это цель. Два луча: ось и на {@link #HULL} ниже (корпус). У шахеда и ракеты ещё
     * и подъём после взведения по силам их автопилоту ({@link #climbOut}). Неготовые чанки не читаются: путь по ним
     * считается свободным (там снаряд уйдёт в полёт вне мира).
     */
    public static boolean clearAhead(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, Vec3 target) {
        float elevation = LauncherEntity.elevation(weapon);
        WeaponSpec.Airframe air = weapon.spec().airframe();
        WeaponSpec.LaunchProfile lp = air.launchProfile();
        int slots = LauncherEntity.slots(weapon);
        List<List<Vec3[]>> boost = new ArrayList<>(slots), paths = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            Vec3 rail = LauncherEntity.railPoint(site, yaw, weapon, slot);
            List<Vec3> path = new ArrayList<>();
            path.add(rail);
            if (lp != null) {
                boost.add(boostSweep(rail, yaw, elevation, lp, air.noseLength()));
                double climb = Math.toRadians(Math.min(elevation, -lp.boostEndPitch()));
                double reach = ProximityFuse.ARM_DISTANCE;
                path.add(rail.add(Local.horizontal(yaw).scale(reach)).add(0, reach * Math.tan(climb), 0));
            } else {
                Vec3 v0 = Ballistics.launchVelocity(rail, target, Ballistics.ticksFor(rail, target, elevation, RocketEntity.MIN_FLIGHT));
                double reach = Math.min(ProximityFuse.ARM_DISTANCE, horizontal(rail, target) - NEAR_AIM);
                for (int k = ARC_STEP; k <= RocketEntity.BURN_TICKS; k += ARC_STEP) {
                    Vec3 at = Ballistics.at(rail, v0, k);
                    if (horizontal(rail, at) > reach) break;
                    path.add(at);
                }
            }
            List<Vec3[]> legs = new ArrayList<>(path.size());
            for (int i = 1; i < path.size(); i++) legs.add(new Vec3[]{path.get(i - 1), path.get(i)});
            paths.add(legs);
        }
        if (!clear(level, boost) || !clear(level, paths)) return false;
        return weapon.spec().launch() != WeaponSpec.Launch.GUIDED || climbOut(level, paths.getFirst().getLast()[1], yaw, air);
    }

    /**
     * Путь носа на разгоне из ячейки {@code rail} — по отрезку за тик, как его заметает снаряд в полёте: закон разгона
     * паспорта ({@link WeaponSpec.LaunchProfile#boost}) и шаг носа ({@link StrikeProjectile#noseSweep}).
     */
    private static List<Vec3[]> boostSweep(Vec3 rail, float yaw, float elevation, WeaponSpec.LaunchProfile lp, double noseLength) {
        FlightController flight = new FlightController(yaw, -elevation);
        List<Vec3[]> sweep = new ArrayList<>(lp.boostTicks());
        Vec3 pos = rail;
        double speed = 0;
        for (int tick = 1; tick <= lp.boostTicks(); tick++) {
            speed = lp.boost(flight, speed, tick);
            Vec3 dir = flight.forward();
            sweep.add(StrikeProjectile.noseSweep(pos, dir, speed, noseLength));
            pos = pos.add(dir.scale(speed));
        }
        return sweep;
    }

    /**
     * Пути ячеек {@code cells} (у каждой — отрезки по порядку) не упираются в блоки ни осью, ни корпусом ({@link #HULL}
     * ниже); дальше первого неготового чанка путь не смотрим. Отрезки с одним номером у всех ячеек, которые целиком
     * выше верхнего блока своих колонок ({@link #aboveBlocks}), лучами не проверяются: там лучу задеть нечего, а у пакета
     * РСЗО это 80 лучей на отрезок — над открытой местностью дуга почти вся такая. Блоки аппарата Sable в карте высот
     * мира не стоят — рядом с аппаратом лучи идут везде.
     */
    private static boolean clear(ServerLevel level, List<List<Vec3[]>> cells) {
        AABB all = null;
        int legs = 0;
        for (List<Vec3[]> path : cells) {
            legs = Math.max(legs, path.size());
            for (Vec3[] leg : path) all = all == null ? swept(leg) : all.minmax(swept(leg));
        }
        if (all == null) return true;
        boolean crafts = SubLevels.mayHaveCraftNear(level, all.getCenter(), Math.max(all.getXsize(), Math.max(all.getYsize(), all.getZsize())) / 2);
        boolean[] stopped = new boolean[cells.size() * 2];
        for (int k = 0; k < legs; k++) {
            if (!crafts && aboveBlocks(level, cells, k)) continue;
            for (int c = 0; c < cells.size(); c++) {
                if (k >= cells.get(c).size()) continue;
                Vec3[] leg = cells.get(c).get(k);
                for (int ray = 0; ray < 2; ray++) {
                    if (stopped[2 * c + ray]) continue;
                    double below = ray * HULL;
                    Vec3 from = leg[0].subtract(0, below, 0), end = leg[1].subtract(0, below, 0);
                    Vec3 to = Terrain.readyUntil(level, from, end);
                    if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()))
                            .getType() != HitResult.Type.MISS) return false;
                    if (to.distanceToSqr(end) > 1e-6) stopped[2 * c + ray] = true;
                }
            }
        }
        return true;
    }

    /** Коробка, которую заметают ось и корпус на отрезке {@code leg}. */
    private static AABB swept(Vec3[] leg) {
        return new AABB(leg[0], leg[1]).expandTowards(0, -HULL, 0);
    }

    /**
     * Отрезки номер {@code k} всех ячеек целиком выше верхнего блока (любого, и без столкновений: карта высот
     * {@code WORLD_SURFACE}) каждой колонки под ними, чанки колонок готовы. Луч {@code Level.clip} проверяет только
     * клетки, через которые идёт, — выше карты высот это воздух, и ответ тот же, что у лучей.
     */
    private static boolean aboveBlocks(ServerLevel level, List<List<Vec3[]>> cells, int k) {
        AABB box = null;
        for (List<Vec3[]> path : cells) {
            if (k < path.size()) box = box == null ? swept(path.get(k)) : box.minmax(swept(path.get(k)));
        }
        if (box == null) return true;
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                if (!Terrain.ready(level, x >> 4, z >> 4) || Terrain.height(level, Heightmap.Types.WORLD_SURFACE, x, z) > box.minY) return false;
            }
        }
        return true;
    }

    /**
     * Подъём после взведения по силам автопилоту шахеда и ракеты: прямо по курсу пусковой от точки взведения {@code gate}
     * (конец проверенного луча — снаряд не ниже неё) на {@link WeaponSpec.Airframe#reliefLookahead} блоков — столько
     * впереди видит его датчик рельефа — снаряд, набирая высоту по закону автопилота ({@link Autopilot#climbOver}) на
     * маршевой скорости, проходит корпусом над рельефом полосы. Дальше рельеф ведёт сам автопилот, но только с места,
     * откуда набор успевает: пусковая на улице между высотками проходила проверку до взведения, а шахед в 40 блоках после
     * неё врезался в башню выше своего набора (ноутбук, город Greenfield, 01.10.2026).
     */
    private static boolean climbOut(ServerLevel level, Vec3 gate, float yaw, WeaponSpec.Airframe air) {
        Vec3 end = gate.add(Local.horizontal(yaw).scale(air.reliefLookahead()));
        double over = Autopilot.climbOver(new double[]{gate.x, gate.z, end.x, end.z}, air.cruiseSpeed(),
                (x, z) -> StrikeProjectile.surfaceY(level, x, z));
        return over + HULL <= gate.y;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
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
