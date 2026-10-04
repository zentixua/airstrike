package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Где поставить пусковую: позади и сбоку от стреляющего, а у приказа с местом пуска — на нём самом ({@link Post}),
 * на ровной твёрдой земле под открытым небом, только в готовых чанках (ничего не грузим ради пуска). Своя пусковая
 * того же оружия рядом ({@link #REUSE_RADIUS} от стреляющего, {@link #PLACE_REUSE} от места пуска) — берём её: залп
 * идёт с одной установки. Больше {@link #MAX_PER_OWNER} установок, что идут за игроком, у него не бывает — старая
 * убирается; на местах пуска из приказа они стоят до «Отбоя».
 * <p>
 * Пакет шахедов и ракет ставится только туда и так, чтобы сектор пуска был свободен ({@link #clearAhead}): на разгоне и
 * наборе до взведения взрывателя снаряд, встретив дом, разбивается без подрыва — в городе весь залп с пусковой, смотрящей
 * в дом, пропадал молча (ноутбук 30.09.2026, Greenfield: 0 ударов из 30, каждый второй прогон). И не между высотками,
 * выше которых снаряд после взведения набрать не успевает: место без такого подъёма не годится, берётся другое
 * или заход издалека.
 */
public final class LaunchSite {
    public static final double REUSE_RADIUS = 96;
    /** Своя пусковая не дальше этого от места пуска из приказа — его: дальше места вокруг него не ищутся. */
    public static final double PLACE_REUSE = 40;
    public static final int MAX_PER_OWNER = 3;
    /** Ближе этого к другой пусковой новую не ставим. */
    private static final double CLEARANCE = 9;

    /** Места относительно игрока: [назад, влево] в блоках, по порядку предпочтения. */
    private static final double[][] CANDIDATES = {
            {18, 11}, {18, -11}, {24, 0}, {13, 17}, {13, -17}, {30, 13}, {30, -13}, {4, 22}, {4, -22}, {38, 0}, {24, 24}, {24, -24}
    };
    /** Места относительно места пуска из приказа: оно само, а там не ровно или занято — те же, что у игрока. */
    private static final double[][] AT_PLACE = Stream.concat(Stream.<double[]>of(new double[]{0, 0}), Arrays.stream(CANDIDATES))
            .toArray(double[][]::new);

    private LaunchSite() {}

    /**
     * Огневая позиция: вокруг чего искать место пусковой и чьи пусковые там свои.
     *
     * @param center  где стоит стреляющий; у места пуска из приказа — оно само, на земле
     * @param yaw     «вперёд» для мест вокруг: взгляд стреляющего, у места пуска — курс на цель (запасные места —
     *                позади него)
     * @param owner   чьи пусковые (null — консоли и командного блока)
     * @param ordered место пуска задано приказом ({@code /airstrike salvo … from x z}): пусковая — на нём, а нет там
     *                ровного места — рядом; у стреляющего она встаёт позади и сбоку, а не на нём
     */
    public record Post(Vec3 center, float yaw, @Nullable UUID owner, boolean ordered) {
        /** У стреляющего игрока: позади и сбоку от него по взгляду. */
        public static Post of(ServerPlayer player) {
            return new Post(player.position(), player.getYRot(), player.getUUID(), false);
        }

        /**
         * Место пуска {@code place} (по горизонтали) из приказа по цели {@code target}; null — чанк места не готов:
         * пусковую там никто не увидит, а грузить его ради пуска нельзя.
         */
        @Nullable
        public static Post ordered(ServerLevel level, Vec3 place, Vec3 target, @Nullable UUID owner) {
            int x = Mth.floor(place.x), z = Mth.floor(place.z);
            if (!Terrain.ready(level, x >> 4, z >> 4)) return null;
            Vec3 center = new Vec3(place.x, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), place.z);
            return new Post(center, FlightController.anglesTo(center, target)[0], owner, true);
        }

        /** Места пусковой: [назад, влево] от центра в блоках, по порядку предпочтения. */
        private double[][] candidates() {
            return ordered ? AT_PLACE : CANDIDATES;
        }

        /** Своя пусковая не дальше этого — её. */
        private double reuse() {
            return ordered ? PLACE_REUSE : REUSE_RADIUS;
        }

        public ChunkPos chunk() {
            return new ChunkPos(BlockPos.containing(center));
        }
    }

    /**
     * Ближайшая своя пусковая этого оружия у огневой позиции {@code post}, которая годится ({@code fits}: например,
     * сектор пуска свободен). Проверяются от ближней, до первой годной: проверка сектора недешёвая.
     */
    @Nullable
    public static LauncherEntity existing(ServerLevel level, Post post, WeaponType weapon, Predicate<LauncherEntity> fits) {
        AABB box = new AABB(post.center(), post.center()).inflate(post.reuse(), 64, post.reuse());
        return level.getEntitiesOfClass(LauncherEntity.class, box, l -> l.isAlive() && l.weapon() == weapon && Objects.equals(post.owner(), l.ownerId()))
                .stream().sorted(Comparator.comparingDouble(l -> l.distanceToSqr(post.center()))).filter(fits).findFirst().orElse(null);
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
     * Предел цены одного выбора пусковой (проверка своей и поиск места) в клетках мира: клетка луча {@code Level.clip}
     * ({@link #traversed}), колонка карты высот, колонка полосы подъёма ({@link #climbOut}). Без предела поиск в плотном
     * городе — до 12 мест по нескольку курсов, по тысячам клеток каждый, — стоил сотни миллисекунд в одном тике. Вышел
     * предел — «места нет»: удар идёт издалека, как без места (раз на приказ, {@link StrikeWorld#noLaunchSite}). Одна
     * проверка без единого отсева по карте высот: шахед и ракета — до ~6 000 клеток с подъёмом, РСЗО по цели в 400 блоках —
     * ~15 000; дальше 600 блоков косым курсом — до ~22 000, и тогда предел (только среди башен выше всей дуги).
     */
    public static final class Budget {
        /**
         * Предел по умолчанию, клеток: ≈8–16 мс (клетка луча — как шаг луча взрыва, у хоста ~0,4 мкс и больше).
         */
        public static final int CELLS = 20_000;
        /**
         * Колонок полосы подъёма на блок пути: полоса шириной 3 блока — до 5 проходов сетки, на косом курсе ~1,4 колонки
         * на блок каждый.
         */
        static final double CLIMB_COLUMNS_PER_BLOCK = 8;

        private final int limit;
        private int spent;
        private boolean out;

        public Budget() {
            this(CELLS);
        }

        public Budget(int limit) {
            this.limit = limit;
        }

        /** Списать {@code cells} клеток; не помещаются — предел вышел: false, и дальше ничего не берётся. */
        public boolean take(int cells) {
            if (out || cells > limit - spent) {
                out = true;
                return false;
            }
            spent += cells;
            return true;
        }

        /** Предел вышел: проверка, которой он не хватил, ответила «занято», не досмотрев. */
        public boolean out() {
            return out;
        }

        /** Сколько клеток списано. */
        public int spent() {
            return spent;
        }
    }

    /**
     * Место под новую пусковую у огневой позиции {@code post} с свободным сектором пуска: на каждом месте по порядку
     * ({@link Post#candidates}) — предложенные курсы ({@code yaws} от места), потом повороты от первого, не дальше
     * {@code maxOff}° от направления на цель {@code target} (дальше — залп уходил бы от цели, это выглядит поломкой).
     * Null — нигде или вышел предел {@code budget}: снаряд заходит без пусковой.
     */
    @Nullable
    public static Pick findClear(ServerLevel level, Post post, WeaponType weapon, Vec3 target, Function<Vec3, float[]> yaws,
                                 float maxOff, Budget budget) {
        for (double[] c : post.candidates()) {
            Vec3 off = Local.offset(post.yaw(), 0, c[1], 0, -c[0]);
            Vec3 p = post.center().add(off);
            Vec3 site = check(level, Mth.floor(p.x), Mth.floor(p.z));
            if (site == null || taken(level, site)) continue;
            Pick pick = pickOn(level, site, weapon, yaws.apply(site), target, maxOff, budget);
            if (pick != null) return pick;
            if (budget.out()) return null;
        }
        return null;
    }

    /** {@link #pickOn(ServerLevel, Vec3, WeaponType, float[], Vec3, float, Budget)} со своим пределом по умолчанию. */
    @Nullable
    public static Pick pickOn(ServerLevel level, Vec3 site, WeaponType weapon, float[] want, Vec3 target, float maxOff) {
        return pickOn(level, site, weapon, want, target, maxOff, new Budget());
    }

    /**
     * Курс на месте {@code site}: первый свободный из предложенных {@code want}, потом повороты от первого не дальше
     * {@code maxOff}° от курса на цель {@code target}; null — все заняты или вышел предел {@code budget}.
     */
    @Nullable
    public static Pick pickOn(ServerLevel level, Vec3 site, WeaponType weapon, float[] want, Vec3 target, float maxOff, Budget budget) {
        float toTarget = FlightController.anglesTo(site, target)[0];
        for (int i = 0; i < want.length; i++) {
            if (clearAhead(level, site, want[i], weapon, target, budget)) return new Pick(site, want[i], i);
            if (budget.out()) return null;
        }
        for (float t : TURNS) {
            float yaw = Mth.wrapDegrees(want[0] + t);
            if (Math.abs(Mth.wrapDegrees(yaw - toTarget)) > maxOff) continue;
            if (clearAhead(level, site, yaw, weapon, target, budget)) return new Pick(site, yaw, -1);
            if (budget.out()) return null;
        }
        return null;
    }

    /** Сектор пуска стоящей пусковой (с её нынешним курсом) по цели {@code target} свободен. */
    public static boolean clearAhead(ServerLevel level, LauncherEntity launcher, Vec3 target, Budget budget) {
        return clearAhead(level, launcher.position(), launcher.getYRot(), launcher.weapon(), target, budget);
    }

    /** {@link #clearAhead(ServerLevel, Vec3, float, WeaponType, Vec3, Budget)} со своим пределом по умолчанию. */
    public static boolean clearAhead(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, Vec3 target) {
        return clearAhead(level, site, yaw, weapon, target, new Budget());
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
     * <p>
     * Цена — из предела {@code budget} ({@link Budget}): не помещается — false. Сначала крайние ячейки пакета (путь после
     * разгона, потом разгон), потом средние: в городе чаще упирается длинный луч, а крайние обычно закрывают и средние.
     */
    public static boolean clearAhead(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, Vec3 target, Budget budget) {
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
        if (!clear(level, edges(paths), budget) || !clear(level, edges(boost), budget)
                || !clear(level, middle(paths), budget) || !clear(level, middle(boost), budget)) return false;
        if (weapon.spec().launch() != WeaponSpec.Launch.GUIDED) return true;
        return budget.take((int) Math.ceil(air.reliefLookahead() * Budget.CLIMB_COLUMNS_PER_BLOCK))
                && climbOut(level, paths.getFirst().getLast()[1], yaw, air);
    }

    /** Первая и последняя ячейки пакета. */
    private static <T> List<T> edges(List<T> cells) {
        return cells.size() <= 2 ? cells : List.of(cells.getFirst(), cells.getLast());
    }

    /** Ячейки пакета между первой и последней. */
    private static <T> List<T> middle(List<T> cells) {
        return cells.size() <= 2 ? List.of() : cells.subList(1, cells.size() - 1);
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
     * мира не стоят — рядом с аппаратом лучи идут везде. Каждый луч и каждая колонка карты высот списываются с предела
     * {@code budget} до того, как их прочесть; не поместились — false.
     */
    private static boolean clear(ServerLevel level, List<List<Vec3[]>> cells, Budget budget) {
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
            int rays = 0;
            for (int c = 0; c < cells.size(); c++) {
                if (k >= cells.get(c).size()) continue;
                Vec3[] leg = cells.get(c).get(k);
                for (int ray = 0; ray < 2; ray++) {
                    if (!stopped[2 * c + ray]) rays += traversed(leg[0], leg[1]);
                }
            }
            if (rays == 0) break;
            if (!crafts && aboveBlocks(level, cells, k, rays, budget)) continue;
            for (int c = 0; c < cells.size(); c++) {
                if (k >= cells.get(c).size()) continue;
                Vec3[] leg = cells.get(c).get(k);
                for (int ray = 0; ray < 2; ray++) {
                    if (stopped[2 * c + ray]) continue;
                    double below = ray * HULL;
                    Vec3 from = leg[0].subtract(0, below, 0), end = leg[1].subtract(0, below, 0);
                    if (!budget.take(traversed(from, end))) return false;
                    Vec3 to = Terrain.readyUntil(level, from, end);
                    if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()))
                            .getType() != HitResult.Type.MISS) return false;
                    if (to.distanceToSqr(end) > 1e-6) stopped[2 * c + ray] = true;
                }
            }
        }
        return true;
    }

    /**
     * Клеток, через которые идёт луч {@code Level.clip} от {@code from} до {@code to}: он обходит сетку блоков, по клетке
     * на каждую пересечённую грань. Сдвиг на целое число блоков (корпус, {@link #HULL}) их не меняет.
     */
    private static int traversed(Vec3 from, Vec3 to) {
        return 1 + Math.abs(Mth.floor(to.x) - Mth.floor(from.x)) + Math.abs(Mth.floor(to.y) - Mth.floor(from.y))
                + Math.abs(Mth.floor(to.z) - Mth.floor(from.z));
    }

    /** Коробка, которую заметают ось и корпус на отрезке {@code leg}. */
    private static AABB swept(Vec3[] leg) {
        return new AABB(leg[0], leg[1]).expandTowards(0, -HULL, 0);
    }

    /**
     * Отрезки номер {@code k} всех ячеек целиком выше верхнего блока (любого, и без столкновений: карта высот
     * {@code WORLD_SURFACE}) каждой колонки под ними, чанки колонок готовы. Луч {@code Level.clip} проверяет только
     * клетки, через которые идёт, — выше карты высот это воздух, и ответ тот же, что у лучей. Колонок коробки больше,
     * чем клеток у самих лучей {@code rays} (длинный косой луч: коробка 68×68 — 4 600 колонок против ~600 клеток), или
     * не помещаются в предел {@code budget} — false без чтения карты: дешевле лучи.
     */
    private static boolean aboveBlocks(ServerLevel level, List<List<Vec3[]>> cells, int k, int rays, Budget budget) {
        AABB box = null;
        for (List<Vec3[]> path : cells) {
            if (k < path.size()) box = box == null ? swept(path.get(k)) : box.minmax(swept(path.get(k)));
        }
        if (box == null) return true;
        int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX), z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
        long columns = (long) (x1 - x0 + 1) * (z1 - z0 + 1);
        if (columns > rays || !budget.take((int) columns)) return false;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
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

    /**
     * Новая пусковая огневой позиции {@code post}; у стреляющего лишние старые установки, что шли за ним, убираются
     * (на местах пуска из приказа они стоят до «Отбоя»).
     */
    public static LauncherEntity deploy(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, Post post) {
        if (!post.ordered()) {
            List<LauncherEntity> mine = new ArrayList<>();
            for (var e : level.getEntities(ModEntities.LAUNCHER.get(), l -> !l.ordered() && Objects.equals(post.owner(), l.ownerId()))) mine.add(e);
            mine.sort(Comparator.comparingLong(LauncherEntity::deployedAt));
            for (int i = 0; i <= mine.size() - MAX_PER_OWNER; i++) mine.get(i).discard();
        }
        LauncherEntity l = LauncherEntity.create(level, site, yaw, weapon, post.owner(), post.ordered());
        level.addFreshEntity(l);
        return l;
    }
}
