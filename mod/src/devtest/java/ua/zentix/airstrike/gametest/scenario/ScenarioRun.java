package ua.zentix.airstrike.gametest.scenario;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.gametest.StrikeGameTests;
import ua.zentix.airstrike.gametest.TicketProbe;
import ua.zentix.airstrike.guidance.BombDrop;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.stress.StressDirector;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Один сценарий полёта от площадки до проверки свойств. Снаряд пускается напрямую ({@code launch},
 * {@code placeOnLauncher}, {@code setRoute}) с геометрией боевого пуска ({@code StrikeService}), но всё случайное —
 * из зерна сценария: у пуска из пульта сторону обхода, разброс РСЗО и точку бомбы выбирает {@code level.random}.
 * <p>
 * Свойства, которые сценарий проверяет у любого полёта:
 * <ul>
 *     <li>полёт кончается в пределах плана × 1,5 с запасом на погоню ({@link #checkDuration});</li>
 *     <li>нет кружения: вне круга барража управляемый снаряд в радиусе своего разворота от точки цели поворачивает
 *         в сумме не больше чем на {@link #MAX_TURN}° ({@link Track#turn});</li>
 *     <li>неподвижная цель поражена в пределах дальности взрывателя ({@link #checkHit});</li>
 *     <li>после конца полёта ни тикетов с UUID снаряда, ни записи в {@link VirtualFlights};</li>
 *     <li>мод не читал неготовых чанков ({@link SyncLoadWatch});</li>
 *     <li>бюджет работы за тик (корень 1): полоса попаданий не больше общего срока и единицы, с блэкаутом — единицы
 *         на полосу; очередь попаданий пустеет; всё, что выбрали взрывы снаряда, снесено ({@link #checkWork}). Часы
 *         общие на сервер: превышение засчитывается всем сценариям, чей полёт шёл в том тике;</li>
 *     <li>полёт совпадает с эталоном ({@link Baseline}), если сценарий в нём.</li>
 * </ul>
 */
final class ScenarioRun {
    /** Больше этого за полёт в радиусе разворота от цели — кружение (два полных круга). */
    static final double MAX_TURN = 720;
    /** Запас к сроку, тиков: после плана × 1,5 снаряд у мода живёт ещё 600 тиков — кружащий их выберет. */
    private static final int DURATION_SLACK = 200;
    /** Сколько ждать сборки аппарата Sable. */
    private static final int CRAFT_WAIT = 100;
    /** Сколько ждать после конца полёта, пока отпустятся районы (очередь районов тикает в конце тика мира). */
    private static final int SETTLE_TICKS = 5;
    /** Сколько после конца полёта ждать, пока работа попаданий снесёт всё, что выбрали взрывы снаряда, тиков. */
    private static final int IMPACT_LIMIT = 600;
    // TODO(корень 2): брать из WeaponSpec; сейчас — копия силы взрыва входа из Warheads.bunkerEntry
    /** Сила взрыва входа бомбы в грунт: заряд бомбы сильнее. */
    private static final float BUNKER_ENTRY_POWER = 4;
    /** Амплитуда хода моба по рельсу, блоков. */
    private static final double RAIL_HALF = 20;
    /**
     * Перенацеливание вбок ({@code BESIDE}): когда до цели столько блоков, новая точка — на столько сбоку и впереди
     * снаряда. Внутри круга разворота ракеты (≈ 400 блоков), дальше её порога отмены атаки (64 блока).
     */
    private static final double BESIDE_RANGE = 120, BESIDE_ACROSS = 80, BESIDE_AHEAD = 20;

    private final GameTestHelper h;
    private final ServerLevel level;
    private final Scenario s;
    private final Scenario.Params p;
    private final ScenarioSite site;
    private final UUID owner;
    private final UUID projectileId;

    // цель
    @Nullable
    private Entity targetEntity;
    private Vec3 targetBase;
    @Nullable
    private Target target;
    private Vec3 lastTargetPoint;
    /** Точка, в которую целит снаряд при пуске. */
    private Vec3 aim;
    private double targetTravel;
    /** Точка неподвижной цели, куда снаряд должен попасть (null — цель движется или пропала). */
    @Nullable
    private Vec3 stationaryAim;
    private double retargetShift;

    // ход сценария
    private int tick;
    private int craftSince = -1;
    private int launchTick = -1;
    private long launchGameTime;
    private int eta0;
    private int disturbAt = -1;
    private boolean disturbed;
    private int endTick = -1;
    private boolean checked;

    // наблюдение
    private final Map<UUID, Track> tracks = new LinkedHashMap<>();
    private final FlightTrace trace = new FlightTrace();
    @Nullable
    private Outcome outcome;
    private final Consumer<ExplosionEvent.Start> onBlast = this::onBlast;
    private final Consumer<ExplosionEvent.Detonate> onDetonate = this::onDetonate;
    /** Блоки мира (не воздух), которые выбрали взрывы снаряда сценария ({@code Detonate}): работа попаданий сносит их. */
    private final List<BlockPos> blasted = new ArrayList<>();
    /**
     * Взорвался ли снаряд сценария ({@code ExplosionEvent.Start} с его UUID). У бомбы B-2 — только сам заряд: взрыв входа
     * в грунт ({@code Warheads.bunkerEntry}, сила {@link #BUNKER_ENTRY_POWER}) подрывом не считается.
     */
    private boolean detonated;
    /** Тик сервера при пуске и в конце полёта (граница бюджета, очередь попаданий). */
    private int launchServerTick, endServerTick;

    /** Итог полёта: чем кончился, на каком тике от пуска и где. */
    private record Outcome(String end, int tick, Vec3 at, WeaponType weapon) {}

    /** Снаряд сценария (или бомба B-2), замеченный в полёте. */
    private static final class Track {
        final int order;
        final WeaponType weapon;
        final double turnRate;
        /** Бетонобойная бомба B-2 (а не сам B-2). */
        final boolean bomb;
        Vec3 last;
        double lastStep;
        double heading = Double.NaN;
        @Nullable
        Vec3 aim;
        /** Поворот курса в радиусе разворота от неподвижной точки цели вне круга барража, °. */
        double turn;

        Track(int order, StrikeProjectile p) {
            this.order = order;
            this.weapon = p.weapon();
            this.bomb = p instanceof BunkerBusterEntity;
            this.turnRate = turnRate(p);
            this.last = p.position();
        }
    }

    ScenarioRun(GameTestHelper h, Scenario s) {
        this.h = h;
        this.level = h.getLevel();
        this.s = s;
        this.p = s.params();
        this.site = new ScenarioSite(level, s.cell());
        this.owner = new UUID(0x5CE7A41000000000L, s.cell());
        this.projectileId = new UUID(0x5CE7A41000000001L, s.cell());
        this.targetBase = site.origin;
    }

    void start() {
        SyncLoadWatch.ensureRegistered();
        if (ScenarioMode.REAL_CHUNKS) ScenarioMode.paceServer();
        else InstantChunks.acquire();
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(onDetonate);
        WorkBudgetWatch.acquire(level.getServer());
        StrikeGameTests.afterTest(h, this::cleanup);
        h.onEachTick(this::tick);
        h.succeedWhen(() -> h.assertTrue(checked, "полёт ещё идёт: " + status() + " — " + ScenarioMode.reproduce(s)));
    }

    // ---------------------------------------------------------------- ход

    private void tick() {
        tick++;
        if (tick == 1) setUp();
        if (launchTick < 0) {
            if (!ready()) return;
            launch();
            return;
        }
        moveTarget();
        if (!disturbed && s.disturbance().inFlight() && (s.disturbance() == Scenario.Disturbance.BESIDE ? onApproach() : tick >= disturbAt)) disturb();
        observe();
        if (endTick >= 0 && !checked && tick >= endTick + SETTLE_TICKS && (impactsDone() || tick >= endTick + IMPACT_LIMIT)) {
            check();
            checked = true;
        }
    }

    private void setUp() {
        Vec3 o = site.origin;
        site.zone(o, ScenarioSite.TARGET_ZONE);
        if (s.launch().rail) site.zone(launchSite(), ScenarioSite.LAUNCH_ZONE);
        if (s.disturbance().inFlight() && s.disturbance() != Scenario.Disturbance.TARGET_GONE && s.disturbance() != Scenario.Disturbance.BESIDE) {
            site.zone(shifted(), ScenarioSite.TARGET_ZONE);
        }
        if (s.disturbance() == Scenario.Disturbance.HILL) site.corridor(o, o.subtract(p.approach().scale(p.place() + p.size() * 1.5 + 64)));
        site.load();
        if (s.disturbance() == Scenario.Disturbance.HILL) site.hill(p.approach(), p.place(), p.size());
        if (s.disturbance() == Scenario.Disturbance.QUARRY) site.quarry(p.place(), p.size());
        switch (s.target()) {
            case POINT -> target = new Target.Point(o);
            case MOB -> targetEntity = spawn(EntityType.PIG, o);
            case PLAYER -> targetEntity = spawn(EntityType.ARMOR_STAND, playerAt(0));
            case CRAFT -> assembleCraft(o);
        }
        if (targetEntity != null) target = Target.OfEntity.center(targetEntity);
    }

    private Entity spawn(EntityType<?> type, Vec3 at) {
        Entity e = type.create(level);
        if (e == null) throw new GameTestAssertException("не создалась цель " + type);
        e.moveTo(at.x, at.y, at.z, 0, 0);
        e.setNoGravity(true);
        if (e instanceof Mob m) m.setNoAi(true);
        level.addFreshEntity(e);
        return e;
    }

    /** Настил 3×3 из досок на земле у цели — аппарат Sable после сборки. */
    private void assembleCraft(Vec3 o) {
        BlockPos c = BlockPos.containing(o);
        BlockPos a = c.offset(-1, 0, -1), b = c.offset(1, 0, 1);
        BlockPos.betweenClosed(a, b).forEach(q -> level.setBlockAndUpdate(q, Blocks.OAK_PLANKS.defaultBlockState()));
        MinecraftServer server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        craftSince = tick;
    }

    /** Районы тикают, аппарат (если он цель) собран: можно пускать. */
    private boolean ready() {
        if (s.target() == Scenario.TargetKind.CRAFT && target == null) {
            Vec3 centre = site.origin.add(0, 0.5, 0);
            List<SubLevelAccess> near = SubLevels.near(level, centre, 8);
            if (near.isEmpty()) {
                if (tick - craftSince > CRAFT_WAIT) throw new GameTestAssertException("аппарат не собрался за " + CRAFT_WAIT + " тиков");
                return false;
            }
            target = new Target.OfSubLevel(SubLevels.toPlot(near.getFirst(), centre));
        }
        return site.ticking();
    }

    // ---------------------------------------------------------------- пуск

    private Vec3 targetPoint() {
        return target.resolve(level).orElse(site.origin);
    }

    /** Место пусковой: против захода на дальности пуска, на рельефе (холм у пусковой — её место на нём). */
    private Vec3 launchSite() {
        Vec3 at = site.origin.subtract(p.approach().scale(p.standoff()));
        return site.ground(at.x, at.z, false);
    }

    /** Куда уводит цель перенацеливание или телепорт. */
    private Vec3 shifted() {
        Vec3 at = targetBase.add(p.shiftDir().scale(p.shift()));
        return site.ground(at.x, at.z, false);
    }

    private void launch() {
        Vec3 point = targetPoint();
        lastTargetPoint = point;
        aim = point;
        StrikeProjectile proj = switch (s.launch()) {
            case DRONE_FAR, MISSILE_FAR -> fromAfar(point);
            case DRONE_RAIL, MISSILE_RAIL -> guidedFromRail(point);
            case LOITER_FAR -> loiterFromAfar(point);
            case LOITER_RAIL -> loiterFromRail(point);
            case ROCKET_FAR -> rocketFromAfar(point);
            case ROCKET_TUBE -> rocketFromTube(point);
            case BOMBER -> bomber(point);
            case ICBM -> icbm(point);
        };
        eta0 = proj.etaTicks();
        // куда снаряд целит сам: у РСЗО — точка с рассеиванием, у B-2 — поверхность (синхронная точка цели — float)
        if (s.target() == Scenario.TargetKind.POINT && s.launch() != Scenario.Launch.ICBM) stationaryAim = aim;
        launchTick = tick;
        launchGameTime = level.getGameTime();
        launchServerTick = level.getServer().getTickCount();
        disturbAt = tick + (int) Math.round(p.when() * eta0);
    }

    /** Снаряд до пуска: своё зерно и UUID из сценария. */
    private <T extends StrikeProjectile> T create(EntityType<T> type) {
        T e = type.create(level);
        if (e == null) throw new GameTestAssertException("не создался снаряд " + type);
        e.setUUID(projectileId);
        e.seed(p.random());
        return e;
    }

    private StrikeProjectile guided() {
        return s.launch().weapon == WeaponType.DRONE ? create(ModEntities.DRONE.get()) : create(ModEntities.CRUISE_MISSILE.get());
    }

    private double pathLength() {
        double cruise = s.launch().weapon == WeaponType.DRONE ? DroneEntity.CRUISE_SPEED : CruiseMissileEntity.CRUISE_SPEED;
        return cruise * p.flightTime() * 20;
    }

    /** Как {@code StrikeService.fromAfar}: начало на прямой захода, на длину полёта от цели, вне мира. */
    private StrikeProjectile fromAfar(Vec3 point) {
        StrikeProjectile e = guided();
        double length = Math.max(pathLength(), p.entry());
        Vec3 start = point.subtract(p.approach().scale(length)).add(0, s.launch().weapon == WeaponType.DRONE ? DroneEntity.CRUISE_HEIGHT : 12, 0);
        e.launch(start, target, point, owner);
        e.setRoute(Route.plan(start, point, p.approach(), length, p.entry(), p.side()));
        VirtualFlights.launch(level, e);
        return e;
    }

    /** Как {@code StrikeService.fromLauncher}: пакет смотрит на первую точку маршрута. */
    private StrikeProjectile guidedFromRail(Vec3 point) {
        StrikeProjectile e = guided();
        Vec3 rail = rail();
        Route plan = Route.plan(rail, point, p.approach(), pathLength(), p.entry(), p.side());
        Vec3 first = plan.current() == null ? point : plan.current();
        e.placeOnLauncher(rail, FlightController.anglesTo(rail, first)[0], LauncherEntity.elevation(s.launch().weapon), p.readyTicks(), 0, target, point, owner);
        e.setRoute(Route.plan(rail, point, p.approach(), pathLength(), p.entry(), p.side()));
        add(e);
        return e;
    }

    private StrikeProjectile loiterFromAfar(Vec3 point) {
        LoiterEntity e = create(ModEntities.LOITER.get());
        Vec3 from = point.subtract(p.approach().scale(p.standoff())).add(0, LoiterEntity.LOITER_HEIGHT, 0);
        e.launch(from, target, point, owner);
        e.setRoute(null);
        VirtualFlights.launch(level, e);
        return e;
    }

    private StrikeProjectile loiterFromRail(Vec3 point) {
        LoiterEntity e = create(ModEntities.LOITER.get());
        Vec3 rail = rail();
        e.placeOnLauncher(rail, FlightController.anglesTo(rail, point)[0], LauncherEntity.elevation(WeaponType.LOITER), p.readyTicks(), 0, target, point, owner);
        e.setRoute(null);
        add(e);
        return e;
    }

    /** Точка падения РСЗО: рассеивание ~1% дальности по нормали, как {@code StrikeService.scatter}. */
    private Vec3 scatter(Vec3 from, Vec3 point) {
        double sigma = Math.max(1, Math.sqrt(from.distanceToSqr(point.x, from.y, point.z)) * 0.01);
        aim = point.add(p.scatter().scale(sigma));
        return aim;
    }

    private StrikeProjectile rocketFromAfar(Vec3 point) {
        RocketEntity r = create(ModEntities.ROCKET.get());
        Vec3 from = point.subtract(p.approach().scale(p.standoff()));
        r.launchFrom(from, target, scatter(from, point), owner);
        VirtualFlights.launch(level, r);
        return r;
    }

    private StrikeProjectile rocketFromTube(Vec3 point) {
        RocketEntity r = create(ModEntities.ROCKET.get());
        Vec3 rail = rail();
        r.placeInTube(rail, FlightController.anglesTo(rail, point)[0], LauncherEntity.elevation(WeaponType.ROCKET), p.readyTicks(), 0, target,
                scatter(rail, point), owner);
        add(r);
        return r;
    }

    /** Как {@code StrikeService.launchBomber}: точка на поверхности (карта высот готового чанка), без разброса. */
    private StrikeProjectile bomber(Vec3 point) {
        BomberEntity e = create(ModEntities.BOMBER.get());
        Vec3 surface = new Vec3(point.x, point.y - 0.5, point.z);
        aim = surface;
        double length = BomberEntity.CRUISE_SPEED * p.flightTime() * 20 + BomberEntity.RELEASE_DISTANCE;
        e.launch(surface.subtract(p.approach().scale(length)), surface, null, owner);
        e.setRoute(null);
        VirtualFlights.launch(level, e);
        return e;
    }

    /** Старт МБР со стола у пусковой (удар по цели ведёт уже не она). */
    private StrikeProjectile icbm(Vec3 point) {
        IcbmEntity e = create(ModEntities.ICBM.get());
        e.prepare(launchSite(), point, owner);
        add(e);
        return e;
    }

    /** Точка схода с направляющей: над землёй места пусковой. */
    private Vec3 rail() {
        return launchSite().add(0, 2, 0);
    }

    private void add(StrikeProjectile e) {
        if (!level.addFreshEntity(e)) throw new GameTestAssertException("снаряд не добавился в мир у " + e.blockPosition());
    }

    // ---------------------------------------------------------------- цель и возмущения

    /** Точка «игрока» на круге через {@code t} тиков. */
    private Vec3 playerAt(int t) {
        double a = t * p.mobSpeed() / p.orbit();
        return targetBase.add(Math.cos(a) * p.orbit(), p.altitude(), Math.sin(a) * p.orbit());
    }

    private void moveTarget() {
        if (targetEntity != null && !targetEntity.isRemoved()) {
            int t = tick - launchTick;
            Vec3 at = switch (s.target()) {
                case MOB -> {
                    // туда-обратно поперёк захода
                    double x = (t * p.mobSpeed()) % (RAIL_HALF * 4);
                    double off = x < RAIL_HALF * 2 ? x - RAIL_HALF : RAIL_HALF * 3 - x;
                    Vec3 across = new Vec3(-p.approach().z, 0, p.approach().x);
                    Vec3 q = targetBase.add(across.scale(off));
                    yield site.ground(q.x, q.z, false);
                }
                case PLAYER -> playerAt(t);
                default -> targetEntity.position();
            };
            targetEntity.setPos(at.x, at.y, at.z);
        }
        if (target != null && !(target instanceof Target.Point)) {
            target.resolve(level).ifPresent(now -> {
                targetTravel += now.distanceTo(lastTargetPoint);
                lastTargetPoint = now;
            });
        }
    }

    private void disturb() {
        disturbed = true;
        switch (s.disturbance()) {
            case TARGET_GONE -> {
                if (targetEntity != null) targetEntity.discard();
            }
            case TELEPORT_NEAR, TELEPORT_FAR -> {
                if (targetEntity == null) return;
                targetBase = shifted();
                Vec3 to = s.target() == Scenario.TargetKind.PLAYER ? playerAt(tick - launchTick) : targetBase;
                targetEntity.teleportTo(to.x, to.y, to.z);
            }
            case RETARGET -> {
                Vec3 to = shifted();
                if (s.launch().weapon == WeaponType.BUNKER) to = to.add(0, -0.5, 0);
                StrikeProjectile live = main();
                if (live != null && live.retarget(new Target.Point(to), to)) {
                    stationaryAim = to;
                    retargetShift = p.shift();
                }
            }
            case BESIDE -> {
                StrikeProjectile live = main();
                if (live == null) return;
                Vec3 at = live.position();
                Vec3 ahead = new Vec3(site.origin.x - at.x, 0, site.origin.z - at.z).normalize();
                Vec3 across = new Vec3(-ahead.z, 0, ahead.x).scale(p.side());
                Vec3 q = at.add(across.scale(BESIDE_ACROSS)).add(ahead.scale(BESIDE_AHEAD));
                Vec3 to = site.ground(q.x, q.z, false);
                site.zoneNow(to, ScenarioSite.TARGET_ZONE);
                if (live.retarget(new Target.Point(to), to)) {
                    stationaryAim = to;
                    retargetShift = to.distanceTo(site.origin);
                }
            }
            default -> {}
        }
    }

    /** Снаряд на подлёте: до цели по горизонтали не больше {@link #BESIDE_RANGE} (и не в круге барража). */
    private boolean onApproach() {
        StrikeProjectile live = main();
        return live != null && live.flightPhase() != FlightPhase.LOITER
                && live.position().subtract(site.origin).horizontalDistance() <= BESIDE_RANGE;
    }

    /** Снаряд, пущенный сценарием (в мире или вне его), или null. */
    @Nullable
    private StrikeProjectile main() {
        if (level.getEntity(projectileId) instanceof StrikeProjectile e && !e.isRemoved()) return e;
        for (StrikeProjectile e : VirtualFlights.get(level).flights()) if (e.getUUID().equals(projectileId)) return e;
        return null;
    }

    // ---------------------------------------------------------------- наблюдение

    private List<StrikeProjectile> live() {
        List<StrikeProjectile> out = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class),
                e -> !e.isRemoved() && owner.equals(e.ownerId())));
        for (StrikeProjectile e : VirtualFlights.get(level).flights()) if (owner.equals(e.ownerId())) out.add(e);
        return out;
    }

    private void observe() {
        int t = tick - launchTick;
        List<StrikeProjectile> now = live();
        // новые — в порядке первого появления (UUID бомбы случайный, по нему не сортировать)
        now.stream().filter(e -> !tracks.containsKey(e.getUUID()))
                .sorted(Comparator.comparing((StrikeProjectile e) -> e.getClass().getName()))
                .forEach(e -> tracks.put(e.getUUID(), new Track(tracks.size(), e)));
        now.sort(Comparator.comparingInt(e -> tracks.get(e.getUUID()).order));
        for (StrikeProjectile e : now) {
            Track tr = tracks.get(e.getUUID());
            Vec3 pos = e.position();
            Vec3 step = pos.subtract(tr.last);
            tr.lastStep = step.length();
            accumulateTurn(tr, e, step);
            tr.last = pos;
            if (outcome == null) trace.add(t, tr.order, e.flightPhase().ordinal(), e.isVirtual(), pos.subtract(site.origin));
            if (outcome == null && e instanceof BunkerBusterEntity bomb && bomb.isDrilling()) {
                outcome = new Outcome("entry", t, bomb.entry(), WeaponType.BUNKER);
            }
        }
        if (endTick < 0 && now.isEmpty() && !tracks.isEmpty()) {
            endTick = tick;
            endServerTick = level.getServer().getTickCount();
            if (outcome == null) {
                // пока «пропал»: взрыв попадания начинается в работе попаданий (WorkScheduler), в конце тика сервера
                // или позже, когда до него дойдёт очередь, — тогда итог станет взрывом с этим же тиком
                Track last = tracks.values().stream().reduce((a, b) -> b).orElseThrow();
                outcome = new Outcome("gone", t, last.last, last.weapon);
            }
        }
    }

    /**
     * Поворот курса по шагу — только управляемым, вне круга барража, в радиусе разворота от точки цели и пока она стоит
     * на месте: погоня за целью, которая сама ходит по кругу, — тоже круги, но не кружение у точки (его ловит срок).
     */
    private void accumulateTurn(Track tr, StrikeProjectile e, Vec3 step) {
        if (tr.turnRate <= 0) return;
        Vec3 aim = e.aimPoint();
        boolean aimMoved = tr.aim == null || aim.distanceToSqr(tr.aim) > 0.01;
        tr.aim = aim;
        double horizontal = step.horizontalDistance();
        if (horizontal < 0.05) return;
        double heading = Math.toDegrees(Math.atan2(step.z, step.x));
        double prev = tr.heading;
        tr.heading = heading;
        if (Double.isNaN(prev) || aimMoved || e.flightPhase() == FlightPhase.LOITER) return;
        double radius = horizontal / Math.toRadians(tr.turnRate) * 2;
        if (e.position().subtract(aim).horizontalDistance() > radius) return;
        tr.turn += Math.abs(Mth.wrapDegrees(heading - prev));
    }

    // TODO(корень 2): брать из WeaponSpec, когда параметры оружия станут данными; сейчас — копия констант сущностей
    /**
     * Предельная угловая скорость по курсу, °/тик (0 — не проверять: баллистика и МБР не кружат). Наибольшая у снаряда:
     * на наборе высоты шахед и ракета поворачивают медленнее, их круг шире, и подсчёт берёт меньшую окрестность цели.
     */
    private static double turnRate(StrikeProjectile e) {
        return switch (e.weapon()) {
            case DRONE, LOITER -> 3;
            case MISSILE -> 2;
            case BUNKER -> e instanceof BomberEntity ? 1 : 0;
            default -> 0;
        };
    }

    /** Что выбрал взрыв снаряда сценария (любой: подрыв, огненный шар, вторичные) — блоки мира рядом, не аппарата. */
    private void onDetonate(ExplosionEvent.Detonate e) {
        if (e.getLevel() != level) return;
        UUID by = StressDirector.blastBy(e.getExplosion());
        // столкновение до взведения (crash) — взрыв без разрушения блоков: список Detonate у него есть, но ваниль его не сносит
        if (by == null || !tracks.containsKey(by) || !e.getExplosion().interactsWithBlocks()) return;
        Vec3 c = e.getExplosion().center();
        double reach = e.getExplosion().radius() * 2 + 2;
        for (BlockPos q : e.getAffectedBlocks()) {
            // блоки аппарата Sable живут в плоте, далеко от места взрыва: их судьба — дело Sable
            if (!level.getBlockState(q).isAir() && Vec3.atCenterOf(q).distanceTo(c) <= reach) blasted.add(q.immutable());
        }
    }

    /** Работа попаданий дошла: всё выбранное взрывами снесено, и очередь попаданий с конца полёта пустела. */
    private boolean impactsDone() {
        // B-2: итог — вход бомбы, подрыв идёт позже; ждать его (или срока, тогда checkHit упадёт)
        return (detonated || s.launch().weapon != WeaponType.BUNKER) && WorkBudgetWatch.emptiedSince(endServerTick) && blasted.stream().allMatch(q -> gone(level.getBlockState(q)));
    }

    private static boolean gone(BlockState state) {
        return state.isAir() || state.getBlock() instanceof BaseFireBlock;
    }

    /** Первый взрыв снаряда сценария — по источнику урона (соседние сценарии далеко, но чужой взрыв не засчитать). */
    private void onBlast(ExplosionEvent.Start e) {
        if (e.getLevel() != level || launchTick < 0) return;
        UUID by = StressDirector.blastBy(e.getExplosion());
        Track tr = by == null ? null : tracks.get(by);
        if (tr != null && !(tr.bomb && e.getExplosion().radius() <= BUNKER_ENTRY_POWER)) {
            detonated = true;
        }
        if (outcome != null && !outcome.end.equals("gone")) return;
        // тик итога — тик попадания (снаряд пропал), а не очереди работы: он зависит от чужих взрывов в том же тике
        if (tr != null) outcome = new Outcome("blast", outcome != null ? outcome.tick : tick - launchTick, e.getExplosion().center(), tr.weapon);
    }

    // ---------------------------------------------------------------- проверки

    private void check() {
        double turn = tracks.values().stream().mapToDouble(tr -> tr.turn).max().orElse(0);
        Airstrike.LOG.info("SCENARIO flight {}: {} на тике {} (план {}), у {}, поворот у цели до {}°, промах {}, взрывы выбрали {} блоков", s.id(),
                outcome.end, outcome.tick, eta0, rel(outcome.at), Math.round(turn),
                stationaryAim == null ? "—" : String.format(Locale.ROOT, "%.1f", outcome.at.distanceTo(stationaryAim)), blasted.size());
        try {
            known(Scenario.Property.TURN, this::checkTurns);
            checkDuration();
            known(Scenario.Property.HIT, this::checkHit);
            checkReleased();
            checkWork();
            List<SyncLoadWatch.Violation> reads = SyncLoadWatch.since(launchGameTime);
            h.assertTrue(reads.isEmpty(), "синхронная загрузка чанков по вине мода: " + reads);
            // с настоящей загрузкой полёт зависит от скорости генерации на машине: эталон не про него
            if (s.baselined() && !ScenarioMode.REAL_CHUNKS) checkBaseline();
        } catch (GameTestAssertException e) {
            throw new GameTestAssertException(e.getMessage() + " — " + ScenarioMode.reproduce(s));
        }
    }

    /** Свойство, которое известный изъян мода нарушает ({@link Scenario#knownIssues}): нарушение — в лог, тест дальше. */
    private void known(Scenario.Property property, Runnable check) {
        if (!s.knownIssues().contains(property)) {
            check.run();
            return;
        }
        try {
            check.run();
        } catch (GameTestAssertException e) {
            Airstrike.LOG.warn("SCENARIO flight {}: известный изъян ({}): {}", s.id(), property, e.getMessage());
        }
    }

    /** Срок: план × 1,5 плюс погоня за целью (как продлевает срок жизни сам снаряд) и круг барража. */
    private void checkDuration() {
        double cruise = cruiseSpeed();
        double chase = Math.min(TargetTracker.CHASE_BUDGET, targetTravel + retargetShift);
        double loiter = s.launch().weapon == WeaponType.LOITER ? AirstrikeConfig.SERVER.loiterTime.get() * 20 * 1.2 + 25 : 0;
        double bound = 1.5 * (eta0 + chase / cruise + loiter) + DURATION_SLACK;
        h.assertTrue(outcome.tick <= bound, String.format(Locale.ROOT, "полёт кончился (%s) на тике %d, срок %.0f (план %d, погоня %.0f блоков)",
                outcome.end, outcome.tick, bound, eta0, chase));
    }

    // TODO(корень 2): брать из WeaponSpec, когда параметры оружия станут данными; сейчас — копия констант сущностей
    private double cruiseSpeed() {
        return switch (s.launch().weapon) {
            case DRONE -> DroneEntity.CRUISE_SPEED;
            case MISSILE -> CruiseMissileEntity.CRUISE_SPEED;
            case LOITER -> LoiterEntity.CRUISE_SPEED;
            case BUNKER -> BomberEntity.CRUISE_SPEED;
            default -> 10;
        };
    }

    private void checkTurns() {
        for (Track tr : tracks.values()) {
            h.assertTrue(tr.turn <= MAX_TURN, String.format(Locale.ROOT, "кружение: %s повернул на %.0f° в радиусе разворота от цели",
                    tr.weapon, tr.turn));
        }
    }

    /** Неподвижная цель: взрыв (бомба — вход в грунт) не дальше шага и запаса взрывателя от точки. */
    private void checkHit() {
        if (stationaryAim == null) return;
        if (s.launch().weapon == WeaponType.BUNKER) {
            h.assertTrue(outcome.end.equals("entry") || outcome.end.equals("blast"), "бомба не дошла до грунта: " + outcome.end);
            // эталон и итог кончаются входом: подрыв после него проверяется здесь (и его блоки — в checkWork)
            h.assertTrue(detonated, "бомба вошла в грунт и не взорвалась за " + IMPACT_LIMIT + " тиков");
            double miss = outcome.at.subtract(stationaryAim).horizontalDistance();
            h.assertTrue(miss <= BombDrop.REACH_PAD + 3, String.format(Locale.ROOT, "бомба вошла в грунт в %.1f блоках от точки", miss));
            return;
        }
        h.assertTrue(outcome.end.equals("blast"), "неподвижная цель не поражена: " + outcome.end + " у " + rel(outcome.at));
        double step = tracks.values().stream().mapToDouble(tr -> tr.lastStep).max().orElse(0);
        double reach = step + pad(s.launch().weapon) + 1;
        double miss = outcome.at.distanceTo(stationaryAim);
        h.assertTrue(miss <= reach, String.format(Locale.ROOT, "взрыв в %.1f блоках от неподвижной цели (дальность %.1f) у %s",
                miss, reach, rel(outcome.at)));
    }

    // TODO(корень 2): брать из WeaponSpec, когда параметры оружия станут данными; сейчас — копия констант сущностей
    /** Запас дальности взрывателя сверх шага, как в {@code advance}. */
    private static double pad(WeaponType weapon) {
        return switch (weapon) {
            case DRONE -> 4.3;
            case MISSILE -> 6.5;
            case LOITER -> 3.0;
            default -> 1.5;
        };
    }

    private void checkReleased() {
        for (UUID id : tracks.keySet()) {
            List<String> tickets = TicketProbe.types(level, id);
            h.assertTrue(tickets.isEmpty(), "после полёта у снаряда " + id + " тикеты: " + tickets);
            h.assertTrue(VirtualFlights.get(level).flights().stream().noneMatch(e -> e.getUUID().equals(id)),
                    "после полёта снаряд " + id + " всё ещё в полёте вне мира");
        }
    }

    /**
     * Бюджет работы (корень 1) на считающих часах ({@link WorkBudgetWatch}): от пуска до проверки ни в одном тике полоса
     * попаданий не взяла больше общего срока и единицы (с блэкаутом — единицы на полосу); очередь попаданий после
     * полёта пустела; каждый блок, который выбрали взрывы снаряда, снесён (или горит).
     */
    private void checkWork() {
        List<WorkBudgetWatch.Violation> over = WorkBudgetWatch.since(launchServerTick);
        h.assertTrue(over.isEmpty(), "бюджет работы за тик превышен: " + over);
        h.assertTrue(WorkBudgetWatch.emptiedSince(endServerTick), "очередь попаданий не опустела за " + IMPACT_LIMIT + " тиков после полёта");
        List<String> left = blasted.stream().filter(q -> !gone(level.getBlockState(q))).limit(5)
                .map(q -> q.toShortString() + " " + level.getBlockState(q)).toList();
        h.assertTrue(left.isEmpty(), "взрывы выбрали, но не снесли (" + blasted.size() + " выбрано): " + left);
    }

    private void checkBaseline() {
        Baseline.Entry got = new Baseline.Entry(outcome.end, outcome.tick, round(outcome.at.x - site.origin.x),
                round(outcome.at.y - site.origin.y), round(outcome.at.z - site.origin.z), trace.hex());
        if (Baseline.recording()) {
            Baseline.record(s.id(), got);
            return;
        }
        Baseline.Entry expected = Baseline.expected(s.id());
        h.assertTrue(expected != null, "trajectoriesMatchBaseline: сценария нет в эталоне — записать ./gradlew runScenarioRecord и объяснить в PR");
        h.assertTrue(expected.equals(got), "trajectoriesMatchBaseline: полёт разошёлся с эталоном: было " + expected + ", стало " + got);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private String rel(Vec3 at) {
        Vec3 r = at.subtract(site.origin);
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f) от цели", r.x, r.y, r.z);
    }

    private String status() {
        if (launchTick < 0) return "ждёт площадку (тик " + tick + ")";
        StrikeProjectile m = main();
        String where = m == null ? "снаряда нет" : m.flightPhase() + (m.isVirtual() ? " вне мира " : " ") + rel(m.position());
        return "тик " + (tick - launchTick) + " от пуска, план " + eta0 + ", " + where + (outcome == null ? "" : ", итог " + outcome.end);
    }

    private void cleanup() {
        NeoForge.EVENT_BUS.unregister(onBlast);
        NeoForge.EVENT_BUS.unregister(onDetonate);
        WorkBudgetWatch.release(level.getServer());
        if (!ScenarioMode.REAL_CHUNKS) InstantChunks.release();
        VirtualFlights.get(level).clear(level, e -> owner.equals(e.ownerId()));
        for (StrikeProjectile e : live()) e.discard();
        if (targetEntity != null) targetEntity.discard();
        site.release();
    }
}
