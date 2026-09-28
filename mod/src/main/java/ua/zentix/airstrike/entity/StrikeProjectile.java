package ua.zentix.airstrike.entity;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.net.ClientHooks;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.nuclear.world.Terrain;
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.StrikeService;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetTracker;

import java.util.Optional;
import java.util.UUID;

/**
 * Общая основа снарядов: ориентация и поворот ({@link FlightController}), фаза полёта ({@link FlightPhase}),
 * маршрут ({@link Route}), слежение за целью, заметание пути на столкновения (блоки, аппараты Sable, люди рядом
 * с траекторией), тикеты чанков по курсу, синхронизация, сохранение.
 * <p>
 * Полёт вне загруженного мира: снаряд, который уходит из тикающих чанков, не замирает на краю, а продолжает полёт
 * «виртуально» ({@link VirtualFlights}): тот же код наведения без чтения блоков, и снова появляется в мире, как
 * только входит в тикающие чанки. Район цели заранее догружается в фоне ({@link FlightTickets}).
 * <p>
 * Ошибка в тике одного снаряда не роняет сервер: снаряд удаляется, стек пишется в лог.
 */
public abstract class StrikeProjectile extends Entity {
    private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Vector3f> DATA_AIM = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> DATA_NUCLEAR = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BOOLEAN);
    /** Сколько первых тиков на пусковой снаряд не виден (пакет ещё поднимается — снаряд «в ячейке»). */
    private static final EntityDataAccessor<Integer> DATA_HIDDEN = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.INT);

    /** Взрыватель взводится на таком удалении от пусковой (или с выходом на маршевый участок). */
    private static final double ARM_DISTANCE = 96;
    /** Район цели догружается, когда до неё осталось столько тиков полёта (и не меньше 400 блоков). */
    private static final int PRELOAD_TICKS = 300;

    protected final FlightController flight = new FlightController(0, 0);
    protected double speed;
    @Nullable
    protected TargetTracker tracker;
    protected int age;
    /** Сколько урона ещё выдержит; -1 — не задано (берётся {@link #maxHealth()}). */
    protected float health = -1;
    /** Сглаженная заданная высота (фильтр как в датапаке: 1/5 разницы за тик). */
    protected double altFilter;
    /** Маршрут до точки входа; null — сразу на цель. */
    @Nullable
    protected Route route;
    /** Где стартовал (для взведения взрывателя). */
    @Nullable
    protected Vec3 launchPos;
    /** Срок жизни, тиков: время полёта по плану с запасом; 0 — {@link #defaultLifetime()}. */
    protected int lifetime;
    /** Ядерная боевая часть вместо обычной (крылатая ракета, бомба). */
    @Nullable
    protected Loadout.Nuke nuclear;

    private int phaseStart;
    /** Сколько стоять на пусковой до поджига. */
    private int readyTicks;
    /** Включить сирену у цели, когда до удара останется столько тиков; -1 — без сирены. */
    private int sirenLead = -1;
    /** Летит вне загруженного мира (см. {@link VirtualFlights}). */
    private boolean virtual;
    /** Чанк, вокруг которого держится район цели (null — не держится). */
    @Nullable
    private ChunkPos heldArea;

    private final LongSet forcedChunks = new LongOpenHashSet();

    // клиентская интерполяция: позиция приходит каждый тик, двигаемся к ней за один тик
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot, lerpXRot;

    protected StrikeProjectile(EntityType<? extends StrikeProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    // ---------------------------------------------------------------- параметры вида

    public abstract WeaponType weapon();

    /** Полудлина корпуса: от центра до носа, блоков. */
    protected abstract double noseLength();

    /** Маршевая скорость, блоков/тик: по ней считается время подлёта. */
    public abstract double cruiseSpeed();

    /** Срок жизни, если он не задан планом полёта. */
    protected abstract int defaultLifetime();

    /** Через сколько тиков взрываться (или исчезать) в любом случае. */
    protected final int maxAge() {
        return lifetime > 0 ? lifetime : defaultLifetime();
    }

    /** Прочность: сколько урона выдержит, прежде чем его собьют. 0 — сбить нельзя. */
    protected float maxHealth() {
        return 0;
    }

    /** Держать ли тикеты чанков по курсу (бомбардировщику не нужно: улетает и исчезает). */
    protected boolean holdsChunks() {
        return true;
    }

    /** Может ли продолжать полёт вне загруженного мира (иначе на краю исчезает). */
    protected boolean fliesVirtually() {
        return true;
    }

    /** Наименьший запас высоты над рельефом, с которым снаряд возвращается в мир из виртуального полёта. */
    protected double clearance() {
        return 12;
    }

    /**
     * Стартовый участок с пусковой: сколько гореть на направляющей, сколько работает ускоритель, как разгоняет
     * и куда к концу разгона опускает нос. Null — снаряд с пусковой не стартует.
     *
     * @param ignitionTicks ускоритель горит, снаряд ещё стоит
     * @param boostTicks    работа ускорителя после схода
     * @param boostAccel    прирост скорости за тик, блоков/тик²
     * @param railTicks     первые тики разгона нос держит угол направляющей
     * @param boostEndPitch тангаж к концу разгона (° , < 0 — нос вверх)
     */
    protected record LaunchProfile(int ignitionTicks, int boostTicks, double boostAccel, int railTicks, float boostEndPitch) {}

    @Nullable
    protected LaunchProfile launchProfile() {
        return null;
    }

    /** Сколько стоять на пусковой до поджига (задаёт {@link #placeOnLauncher}). */
    protected int readyTicks() {
        return readyTicks;
    }

    /** Сколько тиков ещё до схода с пусковой и выхода на маршевую скорость (для времени подлёта). */
    protected int launchTicksLeft() {
        LaunchProfile lp = launchProfile();
        if (lp == null) return 0;
        return switch (flightPhase()) {
            case READY -> Math.max(0, readyTicks - phaseAge()) + lp.ignitionTicks() + lp.boostTicks();
            case IGNITION -> Math.max(0, lp.ignitionTicks() - phaseAge()) + lp.boostTicks();
            case BOOST -> Math.max(0, lp.boostTicks() - phaseAge());
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- запуск

    /** Поставить снаряд в точку, развернуть на цель и привязать к владельцу. */
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        this.tracker = new TargetTracker(target, targetPoint);
        float[] a = FlightController.anglesTo(pos, targetPoint);
        this.flight.set(a[0], 0);
        this.moveTo(pos.x, pos.y, pos.z, a[0], 0);
        this.yRotO = a[0];
        this.launchPos = pos;
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        syncAim();
        syncSpeed();
    }

    /**
     * Поставить на направляющую пусковой: нос по курсу {@code yaw} и углу возвышения {@code elevation}, старт через
     * {@code readyTicks}, первые {@code hiddenTicks} не виден (пакет поднимается). Цель и владелец — как в {@link #launch}.
     */
    public void placeOnLauncher(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks, Target target, Vec3 targetPoint,
                                @Nullable UUID owner) {
        entityData.set(DATA_HIDDEN, hiddenTicks);
        launch(rail, target, targetPoint, owner);
        flight.set(yaw, -elevation);
        moveTo(rail.x, rail.y, rail.z, yaw, -elevation);
        yRotO = yaw;
        xRotO = -elevation;
        speed = 0;
        this.readyTicks = readyTicks;
        altFilter = rail.y;
        setPhase(FlightPhase.READY);
    }

    /** Тревога у цели, когда до удара останется {@code leadTicks} (сирена — по обнаружению на подлёте). */
    public void armSiren(int leadTicks) {
        this.sirenLead = leadTicks;
    }

    /** Маршрут до точки входа (после неё — на цель) и срок жизни по плану полёта. */
    public void setRoute(@Nullable Route route) {
        this.route = route;
        Vec3 aim = tracker == null ? position() : tracker.point();
        double path = route == null ? position().distanceTo(aim) : route.remaining(position(), aim);
        this.lifetime = (int) (path / cruiseSpeed() * 1.5) + launchTicksLeft() + 600;
    }

    /** Ядерная боевая часть (только для носителей, которые её несут). */
    public void setNuclear(@Nullable Loadout.Nuke nuke) {
        this.nuclear = nuke;
        entityData.set(DATA_NUCLEAR, nuke != null);
    }

    public boolean isNuclear() {
        return entityData.get(DATA_NUCLEAR);
    }

    @Nullable
    public UUID ownerId() {
        return entityData.get(DATA_OWNER).orElse(null);
    }

    @Nullable
    public ServerPlayer ownerPlayer() {
        UUID id = ownerId();
        return id != null && level() instanceof ServerLevel sl ? sl.getServer().getPlayerList().getPlayer(id) : null;
    }

    /** Точка, куда снаряд сейчас целится (синхронизирована — для HUD, тревоги и свиста ракеты). */
    public Vec3 aimPoint() {
        Vector3f v = entityData.get(DATA_AIM);
        return new Vec3(v.x, v.y, v.z);
    }

    public FlightPhase flightPhase() {
        return FlightPhase.byId(entityData.get(DATA_PHASE));
    }

    protected void setPhase(FlightPhase phase) {
        if (phase.ordinal() != entityData.get(DATA_PHASE)) {
            entityData.set(DATA_PHASE, (byte) phase.ordinal());
            phaseStart = age;
        }
    }

    /** Сколько тиков идёт текущая фаза (на клиенте — с момента, как он её узнал). */
    public int phaseAge() {
        return age - phaseStart;
    }

    /** Горит стартовый ускоритель. */
    public boolean boosterLit() {
        return flightPhase().boosterLit();
    }

    public float roll() {
        return entityData.get(DATA_ROLL);
    }

    /** Скорость, блоков/тик (на клиенте — синхронизированная). */
    public double speed() {
        return level().isClientSide ? entityData.get(DATA_SPEED) : speed;
    }

    public int age() {
        return age;
    }

    /** Снаряд виден и слышен (на пусковой, пока пакет поднимается, — нет: он в закрытой ячейке). */
    public boolean isActive() {
        return !(flightPhase() == FlightPhase.READY && phaseAge() < entityData.get(DATA_HIDDEN));
    }

    /** Цель, за которой идёт снаряд (сервер). */
    @Nullable
    public Target target() {
        return tracker == null ? null : tracker.target();
    }

    public boolean isVirtual() {
        return virtual;
    }

    /** Снаряд передан {@link ua.zentix.airstrike.strike.VirtualFlights}: в мир его не добавлять, пока не вернётся сам. */
    public void markVirtual() {
        virtual = true;
    }

    /** Время до удара, тиков (сервер): оставшийся путь по маршруту на маршевой скорости и старт. */
    public int etaTicks() {
        if (tracker == null) return 0;
        Vec3 aim = tracker.point();
        double path = route != null && !route.finished() ? route.remaining(position(), aim) : position().distanceTo(aim);
        double v = Math.max(cruiseSpeed(), speed);
        return (int) Math.ceil(path / v) + launchTicksLeft();
    }

    /**
     * Перенацелить в полёте (из камеры снаряда): новая цель, маршрут брошен — дальше прямо на неё.
     *
     * @return снаряд принял цель
     */
    public boolean retarget(Target target, Vec3 point) {
        if (tracker == null || !acceptsRetarget()) return false;
        this.tracker = new TargetTracker(target, point);
        if (route != null) route.skip();
        releaseTargetArea();
        syncAim();
        onRetarget();
        return true;
    }

    /** Можно ли перенацелить (у бомбы после сброса и у бомбардировщика — нельзя). */
    protected boolean acceptsRetarget() {
        return true;
    }

    protected void onRetarget() {}

    // ---------------------------------------------------------------- тик

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientLerp();
            age++;
            ClientHooks.get().projectileTick(this);
            return;
        }
        try {
            age++;
            if (tracker == null) {
                // сбой загрузки или спавна без launch(): летать некуда
                discard();
                return;
            }
            ServerLevel level = (ServerLevel) level();
            if (holdsChunks() && forcedChunks.isEmpty()) updateChunkTickets(level, position(), flight.forward());
            serverTick(level);
            checkSiren(level);
            syncSpeed();
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Снаряд {} в {} упал с ошибкой и убран", getType().getDescriptionId(), blockPosition(), e);
            discard();
        }
    }

    /**
     * Тик вне загруженного мира ({@link VirtualFlights}): то же наведение, но без столкновений и без чтения
     * незагруженных чанков.
     */
    public final void virtualTick(ServerLevel level) {
        virtual = true;
        try {
            age++;
            if (tracker == null) {
                discard();
                return;
            }
            xo = getX();
            yo = getY();
            zo = getZ();
            serverTick(level);
            checkSiren(level);
            holdTargetArea(level);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Снаряд {} вне мира у {} упал с ошибкой и убран", getType().getDescriptionId(), blockPosition(), e);
            discard();
        }
    }

    /**
     * Можно вернуться в мир: снаряд над тикающим и готовым чанком, и впереди по курсу тоже (без этого запаса он
     * на границе прыгал бы туда-обратно каждый тик).
     */
    public boolean canMaterialize(ServerLevel level) {
        Vec3 ahead = position().add(flight.forward().multiply(1, 0, 1).scale(Math.max(16, speed * 3)));
        BlockPos here = BlockPos.containing(position());
        BlockPos next = BlockPos.containing(ahead);
        return level.isPositionEntityTicking(here) && level.isPositionEntityTicking(next)
                && Terrain.ready(level, here) && Terrain.ready(level, next);
    }

    /** Вернуться в мир: не ниже рельефа с запасом. Вызывает {@link VirtualFlights}. */
    public void materialize(ServerLevel level) {
        virtual = false;
        // рельеф под снарядом и впереди на 5 тиков полёта, каждый блок (только готовые чанки): не возникнуть
        // перед склоном или стеной, которую не успеть перепрыгнуть
        double[] ahead = new double[(int) Math.max(80, speed * 5)];
        for (int i = 0; i < ahead.length; i++) ahead[i] = i + 1;
        double floor = Math.max(surfaceY(level, getX(), getZ()), terrainAhead(level, ahead)) + clearance();
        if (getY() < floor) {
            setPos(getX(), floor, getZ());
            altFilter = floor;
        }
        xo = getX();
        yo = getY();
        zo = getZ();
        setYRot(flight.yaw());
        setXRot(flight.pitch());
        yRotO = getYRot();
        xRotO = getXRot();
    }

    /** Район цели догружается в фоне, когда до неё осталось меньше {@link #PRELOAD_TICKS} полёта. */
    protected void holdTargetArea(ServerLevel level) {
        if (heldArea != null || tracker == null) return;
        Vec3 aim = tracker.point();
        double d = position().distanceTo(aim);
        if (d <= Math.max(400, Math.max(speed, cruiseSpeed()) * PRELOAD_TICKS)) {
            heldArea = new ChunkPos(BlockPos.containing(aim));
            FlightTickets.hold(level, heldArea, targetArea(), getUUID(), true);
        }
    }

    /** Отпустить район цели (снаряд убран или перенацелен — новый район возьмётся на подлёте). */
    private void releaseTargetArea() {
        if (heldArea != null && level() instanceof ServerLevel level) FlightTickets.hold(level, heldArea, targetArea(), getUUID(), false);
        heldArea = null;
    }

    /** Размер района цели, который грузится заранее: уровень тикета {@link FlightTickets} (4 — ±40 блоков). */
    protected int targetArea() {
        return FlightTickets.DISTANCE;
    }

    private void checkSiren(ServerLevel level) {
        if (sirenLead >= 0 && !isRemoved() && tracker != null && etaTicks() <= sirenLead) {
            sirenLead = -1;
            StrikeService.siren(level, weapon(), tracker.point());
        }
    }

    /**
     * Старт с пусковой: стоит, поджиг, сход с направляющей и разгон на ускорителе, затем сброс ускорителя
     * ({@link #separate}) и фаза {@link FlightPhase#CLIMB}. Возвращает true, пока снаряд на стартовом участке.
     */
    protected boolean launchTick(ServerLevel level) {
        LaunchProfile lp = launchProfile();
        FlightPhase ph = flightPhase();
        if (lp == null || !ph.launching()) return false;
        switch (ph) {
            case READY -> {
                speed = 0;
                if (phaseAge() >= readyTicks) setPhase(FlightPhase.IGNITION);
            }
            case IGNITION -> {
                speed = 0;
                if (phaseAge() >= lp.ignitionTicks()) setPhase(FlightPhase.BOOST);
            }
            default -> {
                // тяга растёт не сразу: первые тики снаряд едва сползает с направляющей
                double ramp = Math.min(1, (phaseAge() + 1) / 6.0);
                speed += lp.boostAccel() * ramp;
                if (phaseAge() > lp.railTicks()) flight.holdPitch(lp.boostEndPitch(), 0.06, 1.2, 0.12);
                if (!advance(level, tracker.point(), 0)) return true;
                if (phaseAge() >= lp.boostTicks()) {
                    separate(level);
                    setPhase(FlightPhase.CLIMB);
                }
            }
        }
        return true;
    }

    /** Ускоритель выгорел: корпус ускорителя отделяется и падает. */
    protected void separate(ServerLevel level) {
        SpentBoosterEntity.spawn(level, this, noseLength());
    }

    protected abstract void serverTick(ServerLevel level);

    /** Точка удара достигнута или столкновение: взрыв, бурение и т.п. */
    protected abstract void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity);

    /**
     * Столкновение до взведения взрывателя (на старте): боевая часть не срабатывает — снаряд разбивается,
     * горит топливо.
     */
    protected void crash(ServerLevel level, Vec3 point) {
        discard();
        level.explode(this, point.x, point.y, point.z, 1.5f, false, Level.ExplosionInteraction.NONE);
    }

    /** Слежение за целью; возвращает текущую точку прицеливания. */
    protected Vec3 updateTarget(ServerLevel level) {
        tracker.tick(level, position());
        syncAim();
        return tracker.point();
    }

    /** Куда держать курс: следующая точка маршрута или цель. */
    protected Vec3 navPoint(Vec3 aim, double capture) {
        if (route != null) {
            route.update(position(), capture);
            Vec3 wp = route.current();
            if (wp != null) return new Vec3(wp.x, aim.y, wp.z);
        }
        return aim;
    }

    /** Маршрут пройден (или его не было): последний участок — на цель. */
    protected boolean onFinalLeg() {
        return route == null || route.finished();
    }

    private void syncAim() {
        if (tracker == null) return;
        Vec3 p = tracker.point();
        Vector3f cur = entityData.get(DATA_AIM);
        if (Math.abs(cur.x - p.x) > 0.05 || Math.abs(cur.y - p.y) > 0.05 || Math.abs(cur.z - p.z) > 0.05) {
            entityData.set(DATA_AIM, new Vector3f((float) p.x, (float) p.y, (float) p.z));
        }
    }

    private void syncSpeed() {
        if (Math.abs(entityData.get(DATA_SPEED) - speed) > 0.01) entityData.set(DATA_SPEED, (float) speed);
    }

    /** Угол цели под горизонтом (°, > 0 — ниже), курс на цель (°) и расстояния до цели. */
    protected record Bearing(double distance, double horizontal, float yaw, float pitch) {}

    protected Bearing bearingTo(Vec3 aim) {
        Vec3 p = position();
        double dx = aim.x - p.x, dz = aim.z - p.z;
        float[] a = FlightController.anglesTo(p, aim);
        return new Bearing(p.distanceTo(aim), Math.sqrt(dx * dx + dz * dz), a[0], a[1]);
    }

    /**
     * Держать высоту: плавный фильтр заданной высоты и тангаж, пропорциональный ошибке (1.2° на блок,
     * от 15° вверх до 12° вниз), с ограничением угловой скорости и ускорения.
     */
    protected void holdAltitude(double desired, double gain, double maxRate, double maxAccel) {
        altFilter += (desired - altFilter) / 5;
        double climb = Math.max(-12, Math.min(15, (altFilter - getY()) * 1.2));
        flight.holdPitch(-climb, gain, maxRate, maxAccel);
    }

    /** Взрыватель взведён: снаряд отошёл от пусковой или вышел на маршевый участок. */
    protected boolean armed() {
        if (flightPhase().ordinal() >= FlightPhase.CRUISE.ordinal()) return true;
        return launchPos == null || position().distanceToSqr(launchPos) > ARM_DISTANCE * ARM_DISTANCE;
    }

    /**
     * Шаг полёта: заметаем путь носа на длину шага. Столкновение (блок, аппарат, человек рядом с траекторией)
     * или достижение цели — {@link #impact}. Возвращает true, если снаряд ещё летит.
     *
     * @param reachPad запас дальности подрыва сверх длины шага (как в датапаке: шахед 4.3, ракета 6.5, бомба 5.3)
     */
    protected boolean advance(ServerLevel level, Vec3 aim, double reachPad) {
        Vec3 dir = flight.forward();
        Vec3 pos = position();

        if (virtual) return advanceVirtual(level, aim, reachPad, dir);

        boolean onTarget = onFinalLeg() && pos.distanceTo(aim) <= speed + reachPad;
        if (onTarget && armed()) {
            impact(level, aim, null);
            return false;
        }
        if (age >= maxAge()) {
            impact(level, pos.add(dir.scale(noseLength())), null);
            return false;
        }

        Vec3 noseFrom = pos.add(dir.scale(noseLength() * 0.5));
        Vec3 noseTo = pos.add(dir.scale(speed + noseLength()));

        Vec3 blockPoint = null;
        if (!flightPhase().launching()) {
            BlockHitResult block = level.clip(new ClipContext(noseFrom, noseTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
            if (block.getType() != HitResult.Type.MISS) {
                // попадание в аппарат Sable приходит в координатах плота
                blockPoint = SubLevels.toWorld(level, block.getLocation());
            }
        }
        Vec3 sweepEnd = blockPoint != null ? blockPoint : noseTo;

        if (armed()) {
            Entity victim = proximityVictim(level, noseFrom, sweepEnd);
            if (victim != null) {
                Vec3 at = victim.getBoundingBox().getCenter();
                impact(level, closestOnSegment(noseFrom, sweepEnd, at), victim);
                return false;
            }
        }
        if (blockPoint != null) {
            if (armed()) impact(level, blockPoint, null);
            else crash(level, blockPoint);
            return false;
        }

        Vec3 next = pos.add(dir.scale(speed));
        moveAlong(level, next, dir);
        if (next.y < level.getMinBuildHeight() - 64) {
            discard();
            return false;
        }
        // уходит из тикающих чанков: дальше — полёт вне мира (или исчезает, если так не умеет)
        if (!level.isPositionEntityTicking(BlockPos.containing(next))) {
            if (fliesVirtually()) VirtualFlights.park(level, this);
            else discard();
            return false;
        }
        return true;
    }

    /**
     * Вне мира: без столкновений. У цели — только если её район уже загружен (иначе снаряд ждёт на подлёте, пока
     * он догрузится: подрыв в незагруженном чанке остановил бы сервер).
     */
    private boolean advanceVirtual(ServerLevel level, Vec3 aim, double reachPad, Vec3 dir) {
        if (age >= maxAge()) {
            Airstrike.LOG.warn("Снаряд {} так и не долетел до {} (район цели не загрузился) и убран", getType().getDescriptionId(), BlockPos.containing(aim));
            discard();
            return false;
        }
        Vec3 pos = position();
        boolean near = onFinalLeg() && pos.distanceTo(aim) <= speed + reachPad + 48;
        BlockPos at = BlockPos.containing(aim);
        if (near && !(Terrain.ready(level, at) && level.isPositionEntityTicking(at))) return true; // ждём загрузки
        moveAlong(level, pos.add(dir.scale(speed)), dir);
        return true;
    }

    /** Перемещение без проверок (бомбардировщик, бурение). */
    protected void moveAlong(ServerLevel level, Vec3 next, Vec3 dir) {
        setPos(next.x, next.y, next.z);
        setDeltaMovement(dir.scale(speed));
        setYRot(flight.yaw());
        setXRot(flight.pitch());
        float roll = flight.bankAngle(speed);
        if (Math.abs(roll - entityData.get(DATA_ROLL)) > 0.2f) entityData.set(DATA_ROLL, roll);
        if (holdsChunks() && !virtual) updateChunkTickets(level, next, dir);
    }

    /**
     * Неконтактный взрыватель: как в датапаке, срабатывает на людей рядом с траекторией, а ещё на саму цель.
     * На своего — нет (если он сам не цель): пролёт над головой запустившего не должен его убивать.
     */
    @Nullable
    private Entity proximityVictim(ServerLevel level, Vec3 from, Vec3 to) {
        double r = speed / 8 + 1.8;
        AABB sweep = new AABB(from, to).inflate(r + 1);
        Entity best = null;
        double bestT = Double.MAX_VALUE;
        UUID targetId = tracker != null && tracker.target() instanceof Target.OfEntity e ? e.uuid() : null;
        UUID owner = ownerId();
        for (Entity e : level.getEntities(this, sweep, e -> isProximityTarget(e, targetId, owner))) {
            Vec3 c = e.getBoundingBox().getCenter();
            Vec3 on = closestOnSegment(from, to, c);
            double reach = r + e.getBbWidth() * 0.5;
            if (on.distanceToSqr(c) <= reach * reach) {
                double t = on.distanceToSqr(from);
                if (t < bestT) {
                    bestT = t;
                    best = e;
                }
            }
        }
        return best;
    }

    private static boolean isProximityTarget(Entity e, @Nullable UUID targetId, @Nullable UUID owner) {
        if (!e.isAlive() || e.isSpectator() || e instanceof StrikeProjectile) return false;
        if (e.getUUID().equals(targetId)) return true;
        return e instanceof Player && !e.getUUID().equals(owner);
    }

    protected static Vec3 closestOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0e-9) return a;
        double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return a.add(ab.scale(t));
    }

    /**
     * Высота рельефа (верх препятствий) в точке. На сервере — только из готового чанка (иначе нижняя граница
     * мира): чтение незагруженного чанка из тика грузит его сразу и останавливает сервер.
     */
    public static double surfaceY(Level level, double x, double z) {
        return Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
    }

    /** Наибольшая высота рельефа на нескольких расстояниях впереди по горизонтали. */
    protected double terrainAhead(Level level, double... distances) {
        Vec3 pos = position();
        double yawRad = Math.toRadians(flight.yaw());
        double dx = -Math.sin(yawRad), dz = Math.cos(yawRad);
        double max = level.getMinBuildHeight();
        for (double d : distances) {
            max = Math.max(max, surfaceY(level, pos.x + dx * d, pos.z + dz * d));
        }
        return max;
    }

    // ---------------------------------------------------------------- чанки

    /** Держим свой чанк и чанк впереди по курсу — но только уже загруженные: новые чанки на лету не генерируем. */
    private void updateChunkTickets(ServerLevel level, Vec3 pos, Vec3 dir) {
        long here = ChunkPos.asLong(BlockPos.containing(pos));
        long ahead = ChunkPos.asLong(BlockPos.containing(pos.add(dir.scale(Math.max(16, speed * 3)))));
        if (forcedChunks.contains(here) && forcedChunks.contains(ahead) && forcedChunks.size() == (here == ahead ? 1 : 2)) return;
        LongSet want = new LongOpenHashSet();
        for (long c : new long[]{here, ahead}) {
            if (level.getChunkSource().hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) want.add(c);
        }
        for (long c : forcedChunks.toLongArray()) {
            if (!want.contains(c)) {
                ChunkTickets.force(level, this, c, false);
                forcedChunks.remove(c);
            }
        }
        for (long c : want) {
            if (forcedChunks.add(c)) ChunkTickets.force(level, this, c, true);
        }
    }

    private void releaseChunkTickets() {
        if (level() instanceof ServerLevel level) {
            for (long c : forcedChunks) ChunkTickets.force(level, this, c, false);
        }
        forcedChunks.clear();
    }

    @Override
    public void remove(RemovalReason reason) {
        releaseChunkTickets();
        releaseTargetArea();
        super.remove(reason);
    }

    // ---------------------------------------------------------------- урон: дрон и ракету можно сбить

    @Override
    public boolean isPickable() {
        return maxHealth() > 0 && !isRemoved() && isActive();
    }

    @Override
    public boolean isAttackable() {
        return maxHealth() > 0;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || maxHealth() <= 0 || isRemoved() || !isActive()) return false;
        if (level() instanceof ServerLevel level) {
            if (health < 0) health = maxHealth();
            health -= amount;
            if (health <= 0) {
                shotDown(level, source);
            }
        }
        return true;
    }

    /** Сбили: подрыв там, где настигло (на старте, пока взрыватель не взведён, — просто разбился). */
    protected void shotDown(ServerLevel level, DamageSource source) {
        if (armed()) impact(level, position(), null);
        else crash(level, position());
    }

    // ---------------------------------------------------------------- синхронизация и интерполяция

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ROLL, 0f);
        builder.define(DATA_SPEED, 0f);
        builder.define(DATA_PHASE, (byte) FlightPhase.CRUISE.ordinal());
        builder.define(DATA_AIM, new Vector3f());
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_NUCLEAR, false);
        builder.define(DATA_HIDDEN, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_PHASE.equals(key) && level().isClientSide) phaseStart = age;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpXRot = xRot;
        this.lerpSteps = 1;
    }

    private void clientLerp() {
        if (lerpSteps > 0) {
            lerpPositionAndRotationStep(lerpSteps, lerpX, lerpY, lerpZ, lerpYRot, lerpXRot);
            lerpSteps--;
        }
    }

    @Override
    public double lerpTargetX() {
        return lerpSteps > 0 ? lerpX : getX();
    }

    @Override
    public double lerpTargetY() {
        return lerpSteps > 0 ? lerpY : getY();
    }

    @Override
    public double lerpTargetZ() {
        return lerpSteps > 0 ? lerpZ : getZ();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        // видно издалека: ограничивает только дальность отслеживания сущности сервером
        return distanceSq < 1024 * 1024;
    }

    // ---------------------------------------------------------------- сохранение

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        flight.load(tag.getCompound("flight"));
        speed = tag.getDouble("speed");
        age = tag.getInt("age");
        altFilter = tag.getDouble("alt");
        health = tag.contains("health") ? tag.getFloat("health") : -1;
        entityData.set(DATA_PHASE, (byte) FlightPhase.byName(tag.getString("flight_phase")).ordinal());
        phaseStart = age - tag.getInt("phase_age");
        if (tag.contains("tracker")) tracker = TargetTracker.load(tag.getCompound("tracker"));
        if (tag.hasUUID("owner")) entityData.set(DATA_OWNER, Optional.of(tag.getUUID("owner")));
        route = tag.contains("route") ? Route.load(tag.getCompound("route")) : null;
        launchPos = tag.contains("launch_x") ? new Vec3(tag.getDouble("launch_x"), tag.getDouble("launch_y"), tag.getDouble("launch_z")) : null;
        lifetime = tag.getInt("lifetime");
        readyTicks = tag.getInt("ready_ticks");
        sirenLead = tag.contains("siren_lead") ? tag.getInt("siren_lead") : -1;
        setNuclear(tag.contains("nuclear") ? Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, tag.get("nuclear")).result().orElse(null) : null);
        syncAim();
        syncSpeed();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        CompoundTag f = new CompoundTag();
        flight.save(f);
        tag.put("flight", f);
        tag.putDouble("speed", speed);
        tag.putInt("age", age);
        tag.putDouble("alt", altFilter);
        tag.putFloat("health", health);
        tag.putString("flight_phase", flightPhase().getSerializedName());
        tag.putInt("phase_age", phaseAge());
        if (tracker != null) tag.put("tracker", tracker.save());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
        if (route != null) tag.put("route", route.save());
        if (launchPos != null) {
            tag.putDouble("launch_x", launchPos.x);
            tag.putDouble("launch_y", launchPos.y);
            tag.putDouble("launch_z", launchPos.z);
        }
        tag.putInt("lifetime", lifetime);
        tag.putInt("ready_ticks", readyTicks);
        tag.putInt("siren_lead", sirenLead);
        if (nuclear != null) Loadout.Nuke.CODEC.encodeStart(NbtOps.INSTANCE, nuclear).result().ifPresent(n -> tag.put("nuclear", n));
    }
}
