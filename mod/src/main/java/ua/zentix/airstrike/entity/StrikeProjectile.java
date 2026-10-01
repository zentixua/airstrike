package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.flight.ChunkHold;
import ua.zentix.airstrike.entity.flight.ProximityFuse;
import ua.zentix.airstrike.entity.flight.TargetAreaHold;
import ua.zentix.airstrike.guidance.AltitudeHold;
import ua.zentix.airstrike.guidance.Autopilot;
import ua.zentix.airstrike.guidance.Bearing;
import ua.zentix.airstrike.guidance.Craft;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.guidance.Mission;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.strike.FlightLog;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.StrikeService;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetTracker;
import ua.zentix.airstrike.util.Nbt;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Общая основа снарядов: ориентация и поворот ({@link FlightController}), фаза полёта ({@link FlightPhase}),
 * маршрут ({@link Route}), запас хода ({@link Mission}), слежение за целью, заметание пути на столкновения (блоки,
 * аппараты Sable, люди рядом с траекторией — {@link ProximityFuse}), тикеты чанков по курсу ({@link ChunkHold}) и района
 * цели ({@link TargetAreaHold}), синхронизация, сохранение. Законы полёта управляемых — в {@code guidance}: сущность
 * отдаёт им себя как {@link Craft}.
 * <p>
 * Полёт вне загруженного мира: снаряд, который уходит из тикающих чанков, не замирает на краю, а продолжает полёт
 * «виртуально» ({@link VirtualFlights}): тот же код наведения без чтения блоков, и снова появляется в мире, как
 * только входит в тикающие чанки. Район цели заранее догружается в фоне ({@link FlightTickets}).
 * <p>
 * Ошибка в тике одного снаряда не роняет сервер: снаряд удаляется, стек пишется в лог.
 */
public abstract class StrikeProjectile extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    /**
     * Сдвиг за последний тик сервера ({@link #velocity()}). Ванильный пакет скорости сущности его не довезёт: он режет
     * компоненты до 3,9 блока/тик, а B-2 летит 12, МБР — до 25.
     */
    private static final EntityDataAccessor<Vector3f> DATA_VELOCITY = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Vector3f> DATA_AIM = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> DATA_NUCLEAR = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BOOLEAN);
    /** Сколько первых тиков на пусковой снаряд не виден (пакет ещё поднимается — снаряд «в ячейке»). */
    private static final EntityDataAccessor<Integer> DATA_HIDDEN = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.INT);

    /**
     * Дольше минуты район цели не загрузился — снаряд убирается: сервер не справляется с генерацией (десятки районов
     * по 9×9 чанков от залпа с разбросом) или цель недостижима.
     */
    private static final int AREA_WAIT_LIMIT = 1200;
    /** Вне мира снаряд под поверхностью ещё летит к цели, пока он не ниже её на столько блоков (см. groundCrossing). */
    private static final double BELOW_AIM = 16;
    /** Район цели догружается, когда до неё осталось столько тиков полёта (и не меньше 400 блоков). */
    private static final int PRELOAD_TICKS = 300;

    protected final FlightController flight = new FlightController(0, 0);
    protected double speed;
    @Nullable
    protected TargetTracker tracker;
    protected int age;
    /** Сколько урона ещё выдержит; -1 — не задано (берётся {@link #maxHealth()}). */
    protected float health = -1;
    /** Удержание высоты: сглаженная заданная высота. */
    protected final AltitudeHold altitude = new AltitudeHold();
    /** Снаряд глазами автопилота ({@code guidance}): датчики и органы управления. */
    protected final Craft craft = new Craft() {
        @Override
        public Vec3 position() {
            return StrikeProjectile.this.position();
        }

        @Override
        public FlightController flight() {
            return flight;
        }

        @Override
        public AltitudeHold altitude() {
            return altitude;
        }

        @Override
        public double speed() {
            return speed;
        }

        @Override
        public void setSpeed(double v) {
            speed = v;
        }

        @Override
        public FlightPhase phase() {
            return flightPhase();
        }

        @Override
        public int phaseAge() {
            return StrikeProjectile.this.phaseAge();
        }

        @Override
        public void setPhase(FlightPhase phase) {
            StrikeProjectile.this.setPhase(phase);
        }

        @Override
        public double relief(int x, int z) {
            return StrikeProjectile.this.relief(level(), x, z);
        }

        @Override
        public double clearAlong(Vec3 to, double margin) {
            return StrikeProjectile.this.clearAlong(level(), to, margin);
        }
    };
    /** Маршрут до точки входа; null — сразу на цель. */
    @Nullable
    protected Route route;
    /** Где стартовал (для взведения взрывателя). */
    @Nullable
    protected Vec3 launchPos;
    /** Запас хода: план полёта с запасом; null — паспортный ({@link WeaponSpec.Airframe#range}), пока план не задан. */
    @Nullable
    private Mission mission;
    /** Ядерная боевая часть вместо обычной (крылатая ракета, бомба). */
    @Nullable
    protected Loadout.Nuke nuclear;

    private int phaseStart;
    /** Клиент: фаза, от смены которой отсчитан {@link #phaseStart}. */
    private byte clientPhase = -1;
    /** Сколько стоять на пусковой до поджига. */
    private int readyTicks;
    /** Включить сирену у цели, когда до удара останется столько тиков; -1 — без сирены. */
    private int sirenLead = -1;
    /** Летит вне загруженного мира (см. {@link VirtualFlights}). */
    private boolean virtual;
    /** Сколько тиков снаряд вне мира ждал у цели загрузки её района: предел — {@link #AREA_WAIT_LIMIT}. */
    private int areaWait;
    /** Вне мира дошёл до цели, чей район тикает: вернуться в мир здесь же, без запаса впереди (не сохраняется). */
    private boolean arrived;
    /**
     * Вне мира путь снаряда встретил поверхность (столкновений там нет): здесь он ждёт загрузки места, возвращается
     * в мир и в первом же тике попадает — обычным {@link #impact}. Null — путь поверхности не встречал.
     */
    @Nullable
    private Vec3 grounded;
    /** Район цели и полоса подлёта ({@link #visibleLeg}), которые держит снаряд; не сохраняются, как и тикеты. */
    private final TargetAreaHold aimArea = new TargetAreaHold();
    /** Свой чанк и чанк впереди по курсу. */
    private final ChunkHold chunks = new ChunkHold();

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

    /** Паспорт летательного аппарата этого снаряда (у бомбы B-2 — {@link WeaponSpec#payload}). */
    public WeaponSpec.Airframe airframe() {
        return weapon().spec().airframe();
    }

    /** Полудлина корпуса: от центра до носа, блоков. */
    protected final double noseLength() {
        return airframe().noseLength();
    }

    /** Маршевая скорость, блоков/тик: по ней считается время подлёта. */
    public final double cruiseSpeed() {
        return airframe().cruiseSpeed();
    }

    /** Запас хода: план полёта, а пока его нет — паспортный. */
    private Mission mission() {
        if (mission == null) mission = Mission.of(airframe().range());
        return mission;
    }

    /** Новый запас хода (РСЗО: дуга траектории пересчитана). */
    protected final void setMission(Mission mission) {
        this.mission = mission;
    }

    /**
     * Запас хода кончился. Ожидание у цели загрузки её района (вне мира) хода не тратит: на медленном сервере
     * генерация района под залп с разбросом идёт долго, и снаряды пропадали без подрыва (стенд нагрузки 28.09.2026:
     * 38 из 41 ракеты РСЗО). У ожидания свой предел — {@link #AREA_WAIT_LIMIT}.
     */
    protected final boolean exhausted() {
        return mission().exhausted();
    }

    /** Сколько блоков ещё можно пролететь (сервер). */
    public double rangeLeft() {
        return mission().range();
    }

    /** Прочность: сколько урона выдержит, прежде чем его собьют. 0 — сбить нельзя. */
    protected final float maxHealth() {
        return airframe().health();
    }

    /** Держать ли тикеты чанков по курсу (бомбардировщику после сброса не нужно: улетает и исчезает). */
    protected boolean holdsChunks() {
        return true;
    }

    /** Может ли продолжать полёт вне загруженного мира (иначе на краю исчезает). */
    protected boolean fliesVirtually() {
        return true;
    }

    /** Наименьший запас высоты над рельефом, с которым снаряд возвращается в мир из виртуального полёта. */
    protected final double clearance() {
        return airframe().clearance();
    }

    /** Стартовый участок с пусковой; null — снаряд с пусковой не стартует. */
    @Nullable
    protected final WeaponSpec.LaunchProfile launchProfile() {
        return airframe().launchProfile();
    }

    /** Сколько стоять на пусковой до поджига (задаёт {@link #placeOnLauncher}). */
    protected int readyTicks() {
        return readyTicks;
    }

    /** Сколько тиков ещё до схода с пусковой и выхода на маршевую скорость (для времени подлёта). */
    protected int launchTicksLeft() {
        WeaponSpec.LaunchProfile lp = launchProfile();
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
        altitude.reset(rail.y);
        setPhase(FlightPhase.READY);
    }

    /** Тревога у цели, когда до удара останется {@code leadTicks} (сирена — по обнаружению на подлёте). */
    public void armSiren(int leadTicks) {
        this.sirenLead = leadTicks;
    }

    /** Маршрут (null — прямо на цель). */
    @Nullable
    public Route route() {
        return route;
    }

    /** Маршрут до точки входа (после неё — на цель) и запас хода по плану полёта. */
    public void setRoute(@Nullable Route route) {
        this.route = route;
        this.mission = Mission.plan(pathToAim(), cruiseSpeed()).extend(extraRange());
    }

    /** Запас хода сверх пути до цели: участки, которых в пути нет (круг барража), блоков. */
    protected double extraRange() {
        return 0;
    }

    /**
     * Сколько пути по плану осталось до удара, блоков: по нему урезается запас хода, когда цель потеряна
     * ({@link Mission#lose}). По умолчанию — путь до цели ({@link #pathToAim}); у «Ланцета» — ещё круг и пике.
     */
    protected double plannedPathLeft() {
        return pathToAim();
    }

    /** Путь до цели: по оставшемуся маршруту, а без него — напрямую, блоков. */
    protected final double pathToAim() {
        Vec3 aim = tracker == null ? position() : tracker.point();
        return route != null && !route.finished() ? route.remaining(position(), aim) : position().distanceTo(aim);
    }

    /**
     * Зерно своей случайности снаряда (круг барража, сторона ухода B-2) — до {@link #launch}: сценарии полёта
     * (GameTest) повторяют полёт точно. В игре не зовётся: у каждого снаряда своё случайное зерно.
     */
    public void seed(long seed) {
        random.setSeed(seed);
    }

    /** Ядерная боевая часть (только для носителей, которые её несут). */
    public void setNuclear(@Nullable Loadout.Nuke nuke) {
        this.nuclear = nuke;
        entityData.set(DATA_NUCLEAR, nuke != null);
    }

    /** Несёт ядерную боевую часть: её отменяет только ядерный отбой. */
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

    /**
     * Сдвиг за последний тик сервера, блоков/тик (на клиенте — синхронизированный). Это правда о движении для звука
     * (Доплер, обтекание): положение сущности у клиента само по тикам клиента не годится — пакеты тика сервера
     * приходят то в один тик клиента, то в соседний, и снаряд у клиента то стоит, то прыгает на два шага.
     */
    public Vec3 velocity() {
        if (!level().isClientSide) return getDeltaMovement();
        Vector3f v = entityData.get(DATA_VELOCITY);
        return new Vec3(v.x, v.y, v.z);
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

    /** Цель пропала (умерла, ушла в другой мир, аппарат разобран): снаряд идёт в последнюю известную точку. */
    public boolean targetLost() {
        return tracker != null && tracker.isLost();
    }

    /** Цель, за которой идёт снаряд (сервер). */
    @Nullable
    public Target target() {
        return tracker == null ? null : tracker.target();
    }

    /** Вне мира путь встретил поверхность: снаряд ждёт там загрузки места и попадёт, вернувшись в мир. */
    protected final boolean isGrounded() {
        return grounded != null;
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
        double v = Math.max(cruiseSpeed(), speed);
        return (int) Math.ceil(pathToAim() / v) + launchTicksLeft();
    }

    /**
     * Точка цели уточнилась (та же цель): трекер держит её неподвижной точкой, прицел для HUD, сирены и камеры —
     * тоже она; район цели идёт за ней ({@link #holdTargetArea}).
     */
    protected final void settleAim(Vec3 point) {
        tracker.settle(point);
        syncAim();
    }

    /**
     * Перенацелить в полёте (из камеры снаряда): новая цель, маршрут брошен — дальше прямо на неё.
     *
     * @return снаряд принял цель
     */
    public boolean retarget(Target target, Vec3 point) {
        if (tracker == null || !acceptsRetarget()) return false;
        mission().retarget(tracker.retarget(target, point));
        if (route != null) route.skip();
        releaseTargetArea();
        syncAim();
        onRetarget();
        return true;
    }

    /**
     * Набирает ли высоту за целью: управляемый снаряд держит высоту над ней и под поднявшимся полом полёта вне мира
     * ({@link #groundCrossing}) доворачивает вверх — пол его не останавливает. Баллистический (бомба, снаряд РСЗО) вверх
     * не пойдёт: ниже пола на снижении он уже в земле.
     */
    protected boolean climbs() {
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
            return;
        }
        try {
            age++;
            if (tracker == null) {
                // сбой загрузки или спавна без launch(): летать некуда
                Airstrike.LOG.warn("Снаряд {} {} у {} без цели (сбой загрузки или спавн без пуска) и убран", getType().getDescriptionId(), getUUID(), blockPosition());
                discard();
                return;
            }
            ServerLevel level = (ServerLevel) level();
            if (grounded != null) {
                // вернулся в мир там, где путь вне мира встретил поверхность: попадание здесь
                Vec3 at = grounded;
                grounded = null;
                if (armed()) impact(level, at, null);
                else crashUnarmed(level, at);
                return;
            }
            if (holdsChunks() && chunks.isEmpty()) chunks.update(level, getUUID(), position(), flight.forward(), speed);
            serverTick(level);
            // взорвался или ушёл в полёт вне мира (там летит уже копия): ни сирены, ни новых тикетов
            if (isRemoved()) return;
            updateVelocity();
            mission().spend(getDeltaMovement().length());
            checkSiren(level);
            holdTargetArea(level);
            syncSpeed();
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Снаряд {} {} в {} упал с ошибкой и убран", getType().getDescriptionId(), getUUID(), blockPosition(), e);
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
                Airstrike.LOG.warn("Снаряд {} {} вне мира у {} без цели (сбой загрузки) и убран", getType().getDescriptionId(), getUUID(), blockPosition());
                discard();
                return;
            }
            xo = getX();
            yo = getY();
            zo = getZ();
            serverTick(level);
            if (isRemoved()) return;
            updateVelocity();
            mission().spend(getDeltaMovement().length());
            checkSiren(level);
            holdTargetArea(level);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Снаряд {} {} вне мира у {} упал с ошибкой и убран", getType().getDescriptionId(), getUUID(), blockPosition(), e);
            discard();
        }
    }

    /**
     * Можно вернуться в мир: снаряд над тикающим и готовым чанком, и весь путь впереди по курсу тоже (без этого
     * запаса он на границе прыгал бы туда-обратно каждый тик, {@link VirtualFlights#clearAhead}). Дошедшему до цели
     * запас не нужен: в мире он взорвётся.
     */
    public boolean canMaterialize(ServerLevel level) {
        if (arrived) {
            BlockPos here = BlockPos.containing(position());
            return level.isPositionEntityTicking(here) && Terrain.ready(level, here);
        }
        return clearAhead(level, position());
    }

    /** Из этого места снаряд вернётся в мир: тикает и готов весь путь впереди ({@link VirtualFlights#clearAhead}). */
    private boolean clearAhead(ServerLevel level, Vec3 pos) {
        return VirtualFlights.clearAhead(pos, flight.forward(), speed,
                (x, z) -> Terrain.ready(level, x, z) && level.isPositionEntityTicking(new BlockPos(x << 4, 0, z << 4)));
    }

    /**
     * Вернуться в мир: не ниже рельефа с запасом — кроме снаряда на пусковой (ушёл вне мира при остановке сервера):
     * он встаёт обратно на направляющую, место которой проверил {@link ua.zentix.airstrike.strike.LaunchSite}.
     * Вызывает {@link VirtualFlights}.
     */
    public void materialize(ServerLevel level) {
        virtual = false;
        // дошедший до поверхности попадает в неё — поднимать его над рельефом незачем
        if (!flightPhase().onLauncher() && grounded == null) {
            // рельеф под снарядом и впереди на 5 тиков полёта, каждая колонка полосы (только готовые чанки): не возникнуть
            // перед склоном или стеной, которую не успеть перепрыгнуть. Путь, который кончается у цели, — только до неё:
            // рельеф за целью поднимал ракету РСЗО, вернувшуюся в 10 блоках от цели ниже рельефа, на десятки блоков, и
            // она рвалась в воздухе или на склоне рядом с целью (стенд 29.09.2026)
            double ahead = Math.min(Math.max(80, speed * 5), pathLeft());
            double floor = reliefStraightAhead(level, ahead) + clearance();
            if (getY() < floor) {
                setPos(getX(), floor, getZ());
                altitude.reset(floor);
            }
        }
        xo = getX();
        yo = getY();
        zo = getZ();
        setYRot(flight.yaw());
        setXRot(flight.pitch());
        yRotO = getYRot();
        xRotO = getXRot();
        // свои тикеты — до входа в мир: чанк мог тикать лишь по чужому тикету (соседний снаряд), и если тот его
        // отпустит до первого тика, снаряд застынет в нетикающем чанке — ни полёта, ни ухода вне мира, ни расхода хода
        if (holdsChunks()) chunks.update(level, getUUID(), position(), flight.forward(), speed);
    }

    /**
     * Сколько по горизонтали осталось пути вперёд до его конца ({@link #pathEnd}); бесконечность — путь впереди у цели
     * не кончается (конца нет или он позади: снаряд уходит на новый заход).
     */
    private double pathLeft() {
        Vec3 end = pathEnd();
        if (end == null) return Double.POSITIVE_INFINITY;
        double dx = end.x - getX(), dz = end.z - getZ();
        double d2 = dx * dx + dz * dz;
        Vec3 f = flight.forward();
        if (d2 > 1 && f.x * dx + f.z * dz <= 0) return Double.POSITIVE_INFINITY;
        return Math.sqrt(d2);
    }

    /**
     * Где кончается путь снаряда (null — не у точки: бомбардировщик проходит цель, барражирующий кружит над ней):
     * по умолчанию — цель, когда маршрут пройден.
     */
    @Nullable
    protected Vec3 pathEnd() {
        return tracker != null && onFinalLeg() ? tracker.point() : null;
    }

    /**
     * Район цели догружается в фоне, когда до неё осталось меньше {@link #PRELOAD_TICKS} полёта, — и в мире, и вне
     * его: снаряд в мире тоже летит туда, где никого нет (залп с разбросом в сотни блоков от игрока), и раньше
     * район брался только после ухода из тикающих чанков, у самой цели. Движущаяся цель
     * (аппарат, игрок) уводит район за собой: иначе снаряд ждал у неё загрузки, которой не будет, и пропадал
     * по запасу хода (28.09.2026: 4 ракеты из 10 за улетающим аппаратом). Полоса подлёта — {@link TargetAreaHold}.
     */
    protected void holdTargetArea(ServerLevel level) {
        if (tracker == null || isRemoved()) return;
        // путь вне мира кончился на поверхности: грузится место попадания, а не цель
        Vec3 aim = grounded != null ? grounded : tracker.point();
        aimArea.hold(level, getUUID(), position(), aim, preloadDistance(), targetArea(), visibleLeg(), route, age);
    }

    /**
     * Сколько последнего пути до цели снаряд летит в мире, а не вне его ({@link FlightTickets#approach}): столько
     * его подлёт видно игроку у цели, если это не дальше прорисовки. 0 — только район цели.
     */
    protected final double visibleLeg() {
        return airframe().visibleLeg();
    }

    /** С какого расстояния до цели её район грузится заранее: {@link #PRELOAD_TICKS} полёта, не меньше 400 блоков. */
    protected double preloadDistance() {
        return Math.max(400, Math.max(speed, cruiseSpeed()) * PRELOAD_TICKS);
    }

    /** Район цели загружен и в нём тикают сущности: снаряд, пришедший туда, взорвётся в мире. */
    protected final boolean aimAreaReady(ServerLevel level, Vec3 aim) {
        BlockPos at = BlockPos.containing(aim);
        return Terrain.ready(level, at) && level.isPositionEntityTicking(at);
    }

    /**
     * Ещё тик ожидания загрузки района цели. Ожидание хода не тратит, у него свой предел
     * {@link #AREA_WAIT_LIMIT}: исчерпан — снаряд убран (false).
     */
    protected final boolean waitForAimArea(Vec3 aim) {
        if (++areaWait > AREA_WAIT_LIMIT) {
            Airstrike.LOG.warn("Снаряд {} {} не дождался загрузки района цели {} и убран", getType().getDescriptionId(), getUUID(),
                    BlockPos.containing(aim));
            discard();
            return false;
        }
        return true;
    }

    /** Отпустить район цели (снаряд убран или перенацелен — новый район возьмётся на подлёте). */
    private void releaseTargetArea() {
        if (level() instanceof ServerLevel level) aimArea.release(level, getUUID());
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
        WeaponSpec.LaunchProfile lp = launchProfile();
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

    /** Точка удара достигнута или столкновение: по умолчанию — обычная боевая часть своего оружия. */
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
        Warheads.detonate(level, weapon(), point, this, ownerId());
    }

    /**
     * Столкновение до взведения взрывателя (на старте): боевая часть не срабатывает — снаряд разбивается,
     * горит топливо.
     */
    protected void crash(ServerLevel level, Vec3 point) {
        discard();
        Warheads.whenReady(level, point, Warheads.reach(1.5f),
                l -> l.explode(this, point.x, point.y, point.z, 1.5f, false, Level.ExplosionInteraction.NONE));
    }

    /**
     * Столкновение до взведения ({@link #crash}): в лог — строкой на залп ({@link FlightLog}), каждый снаряд — строкой
     * DEBUG. Иначе такой конец полёта не виден: взрыв ванильный и без строки удара, а залп из 30 шахедов, который весь
     * разбился о постройку у пусковой, в логе выглядел как пропавший.
     */
    protected final void crashUnarmed(ServerLevel level, Vec3 point) {
        Airstrike.LOG.debug("Снаряд {} {} разбился до взведения у {} (фаза {})", getType().getDescriptionId(), getUUID(),
                BlockPos.containing(point), flightPhase());
        // курс — по 10°: у залпа с одной пусковой он общий, строка одна
        int course = Math.floorMod(Math.round(flight.yaw() / 10f) * 10, 360);
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), FlightLog.Event.CRASHED, BlockPos.containing(point), targetLost(), 0,
                "курс " + course + "°, фаза " + flightPhase().getSerializedName());
        crash(level, point);
    }

    /** Слежение за целью; возвращает текущую точку прицеливания. */
    protected Vec3 updateTarget(ServerLevel level) {
        mission().chase(tracker.tick(level));
        // и у снаряда, сохранённого прежней версией уже с потерянной целью (флага «урезан» нет)
        if (tracker.isLost() && mission().lose(plannedPathLeft(), cruiseSpeed())) onTargetLost(level);
        syncAim();
        return tracker.point();
    }

    /**
     * Цель потеряна: снаряд идёт в её последнюю точку, и запас хода — только на полёт туда ({@link Mission#lose}).
     * Не дошёл — самоликвидация ({@link #advance}). В лог — строкой на залп ({@link FlightLog}): сколько секунд
     * полёта на маршевой скорости осталось.
     */
    private void onTargetLost(ServerLevel level) {
        BlockPos last = BlockPos.containing(tracker.point());
        int seconds = (int) (Math.max(0, rangeLeft()) / cruiseSpeed()) / 20;
        Airstrike.LOG.debug("Снаряд {} {} у {} потерял цель, идёт в {}, запас на {} с", getType().getDescriptionId(), getUUID(),
                blockPosition(), last, seconds);
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(),
                tracker.outOfReach() ? FlightLog.Event.LOST_OUT_OF_REACH : FlightLog.Event.LOST_GONE, last, true, seconds);
    }

    /** Запас хода кончился: в лог — строкой на залп ({@link FlightLog}), каждый снаряд — строкой DEBUG. */
    private void noteExpired(ServerLevel level, Vec3 aim, FlightLog.Event event) {
        Airstrike.LOG.debug("Снаряд {} {} не долетел до {}, кончился запас хода ({}) у {}", getType().getDescriptionId(), getUUID(),
                BlockPos.containing(aim), event, blockPosition());
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), event, BlockPos.containing(aim), targetLost(), 0);
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

    /**
     * Скорость — сколько снаряд на деле прошёл за этот тик (от места в начале тика): и в полёте, и в ожидании района
     * цели, и в бурении. Она же уходит клиентам.
     */
    private void updateVelocity() {
        setDeltaMovement(getX() - xo, getY() - yo, getZ() - zo);
        Vector3f cur = entityData.get(DATA_VELOCITY);
        Vec3 v = getDeltaMovement();
        if (v.distanceToSqr(cur.x, cur.y, cur.z) > 1.0e-6) entityData.set(DATA_VELOCITY, v.toVector3f());
    }

    private void syncSpeed() {
        if (Math.abs(entityData.get(DATA_SPEED) - speed) > 0.01) entityData.set(DATA_SPEED, (float) speed);
    }

    /** Точка внутри круга разворота: до неё не довернуть ({@link Autopilot#insideTurn}). */
    protected final boolean insideTurn(Vec3 point, double maxRateDeg) {
        return Autopilot.insideTurn(position(), flight.forward(), speed, point, maxRateDeg);
    }

    protected final Bearing bearingTo(Vec3 aim) {
        return Bearing.of(position(), aim);
    }

    /** Держать высоту ({@link AltitudeHold}). */
    protected final void holdAltitude(double desired, double gain, double maxRate, double maxAccel) {
        altitude.hold(flight, getY(), desired, gain, maxRate, maxAccel);
    }

    /** Взрыватель взведён: снаряд отошёл от пусковой или вышел на маршевый участок ({@link ProximityFuse#armed}). */
    protected boolean armed() {
        return ProximityFuse.armed(flightPhase(), position(), launchPos);
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
        if (exhausted()) {
            // самоликвидация: так и не дошёл до цели. Ядерная БЧ вдали от цели не подрывается — только корпус и топливо
            noteExpired(level, aim, FlightLog.Event.EXPIRED);
            Vec3 nose = pos.add(dir.scale(noseLength()));
            if (nuclear != null) crash(level, nose);
            else impact(level, nose, null);
            return false;
        }

        Vec3 noseFrom = pos.add(dir.scale(noseLength() * 0.5));
        // нос не заглядывает в неготовый чанк (clip грузил бы его); туда снаряд и не шагнёт — уйдёт в полёт вне мира
        Vec3 noseTo = Terrain.readyUntil(level, noseFrom, pos.add(dir.scale(speed + noseLength())));

        // и на разгоне: снаряд, прошедший сквозь дом на ускорителе, выходил из разгона внутри постройки и разбивался о неё
        // в первом же тике набора — тихо и далеко от места, где встретил её
        Vec3 blockPoint = null;
        BlockHitResult block = level.clip(new ClipContext(noseFrom, noseTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        if (block.getType() != HitResult.Type.MISS) {
            // попадание в аппарат Sable приходит в координатах плота
            blockPoint = SubLevels.toWorld(level, block.getLocation());
        }
        Vec3 sweepEnd = blockPoint != null ? blockPoint : noseTo;

        if (armed()) {
            UUID targetId = tracker.target() instanceof Target.OfEntity e ? e.uuid() : null;
            Entity victim = ProximityFuse.victim(level, this, noseFrom, sweepEnd, speed, targetId, ownerId());
            if (victim != null) {
                Vec3 at = victim.getBoundingBox().getCenter();
                impact(level, ProximityFuse.closestOnSegment(noseFrom, sweepEnd, at), victim);
                return false;
            }
        }
        if (blockPoint != null) {
            if (armed()) impact(level, blockPoint, null);
            else crashUnarmed(level, blockPoint);
            return false;
        }

        Vec3 next = pos.add(dir.scale(speed));
        if (next.y < level.getMinBuildHeight() - 64) {
            Airstrike.LOG.warn("Снаряд {} {} ушёл ниже мира у {} и убран", getType().getDescriptionId(), getUUID(), blockPosition());
            discard();
            return false;
        }
        if (leavesTickingChunks(level, next)) return false;
        moveAlong(level, next, dir);
        return true;
    }

    /**
     * Вне мира: без столкновений. У цели — только если её район уже загружен (иначе снаряд ждёт на подлёте, пока
     * он догрузится: подрыв в незагруженном чанке остановил бы сервер).
     * <p>
     * Дойдя до цели, снаряд встаёт в неё и возвращается в мир, где и взрывается: вне мира столкновений нет, и снаряд,
     * которому для возвращения не хватило тикающих чанков впереди по курсу (цель в чанке, который тикает один, —
     * игрок в воздухе у края загрузки), пролетал цель и падал до конца запаса хода (стенд VPS 29.09.2026: ракеты РСЗО
     * в 900 блоках под миром).
     */
    private boolean advanceVirtual(ServerLevel level, Vec3 aim, double reachPad, Vec3 dir) {
        if (grounded != null) {
            // стоит на поверхности: вернуться в мир, как только место загрузится (ожидание — с тем же пределом),
            // на настоящую поверхность из карты высот загруженного чанка
            if (!aimAreaReady(level, grounded)) return waitForAimArea(grounded);
            grounded = new Vec3(grounded.x, groundBound(level, grounded.x, grounded.z), grounded.z);
            moveAlong(level, grounded, dir);
            arrived = true;
            return true;
        }
        if (exhausted()) {
            noteExpired(level, aim, FlightLog.Event.EXPIRED_VIRTUAL);
            discard();
            return false;
        }
        Vec3 pos = position();
        boolean near = onFinalLeg() && pos.distanceTo(aim) <= speed + reachPad + 48;
        if (near && !aimAreaReady(level, aim)) {
            // ждём загрузки района (тикет взят на подлёте)
            return waitForAimArea(aim);
        }
        if (onFinalLeg() && pos.distanceTo(aim) <= speed + reachPad) {
            // район цели тикает (иначе ждали бы выше): вернуться в мир у цели — VirtualFlights сделает это в этом же тике
            moveAlong(level, aim, dir);
            arrived = true;
            return true;
        }
        Vec3 next = pos.add(dir.scale(speed));
        Vec3 ground = groundCrossing(level, aim, pos, next);
        if (ground != null) {
            // мимо цели (или цель под землёй): вне мира столкновений нет, и снаряд падал бы без взрыва до конца запаса
            // хода или до низа мира (бомба на точку позади B-2 — до y=−3022); путь кончается на поверхности
            Airstrike.LOG.info("Снаряд {} {} вне мира дошёл до поверхности у {}, цель {}", getType().getDescriptionId(), getUUID(),
                    BlockPos.containing(ground), BlockPos.containing(aim));
            grounded = ground;
            moveAlong(level, ground, dir);
            return true;
        }
        moveAlong(level, next, dir);
        return true;
    }

    /**
     * Где шаг вне мира уходит под землю (null — не уходит). Поверхность — из карты высот готового чанка; у не готового —
     * уровень моря генератора ({@code Level.getSeaLevel} в 1.21.1 — всегда 63, у плоского мира море −63): ниже него суша
     * почти не бывает (над водой поверхность — сама вода), а рельеф генератора ({@code ChunkGenerator.getBaseHeight})
     * стоит миллисекунды на точку — не для каждого тика. В мире с потолком (Незер) карта высот — потолок: там только море.
     * <p>
     * Рельефа вне мира снаряд не знает и летит на высоте цели (пуск издалека — над ней, РСЗО — с её высоты, бреющий —
     * над ней же), а цель бывает ниже моря и ниже рельефа (пещера, карьер, овраг): под поверхностью, но не ниже своей
     * цели на {@link #BELOW_AIM} он ещё летит к ней. Ниже этого пола на снижении баллистический снаряд (бомба, РСЗО) уже
     * в земле: попадание — на поверхности над ним (и когда пол встал выше: чанк под ним догрузился). Управляемый
     * ({@link #climbs}) ниже пола оказывается, только когда пол встал выше него — цель, за которой он держит высоту
     * (ракета +12, шахед +30), поднялась (игрок вышел из оврага, телепорт), — и ещё несколько тиков снижается, пока
     * доворачивает вверх: его пол не останавливает, иначе он падал там, где его застало (в 3000 блоках от цели,
     * с ядерной БЧ). Ниже низа мира не уходит никто. Снаряд, который после этого шага вернётся в мир, правило не
     * трогает: в мире столкновения свои.
     */
    @Nullable
    private Vec3 groundCrossing(ServerLevel level, Vec3 aim, Vec3 from, Vec3 to) {
        double floor = Math.min(groundBound(level, to.x, to.z), aim.y - BELOW_AIM);
        boolean buried = !climbs() && to.y < floor && to.y < from.y;
        boolean bottom = to.y < level.getMinBuildHeight();
        if (!(buried || bottom) || clearAhead(level, to)) return null;
        // шаг пересёк пол — там, где пересёк; уже был под ним (пол встал выше, низ мира) — там, где снаряд сейчас
        double t = from.y >= floor && from.y > to.y ? (from.y - floor) / (from.y - to.y) : 0;
        Vec3 at = from.lerp(to, t);
        // попадание — на поверхности там, где путь её встретил (на склоне конец шага выше или ниже на блоки)
        return new Vec3(at.x, groundBound(level, at.x, at.z), at.z);
    }

    /**
     * Поверхность для полёта вне мира: карта высот готового чанка, иначе уровень моря; потолок Незера — не земля
     * ({@link Terrain.Allowed#FLIGHT}, см. {@link #groundCrossing}).
     */
    private static int groundBound(ServerLevel level, double x, double z) {
        return Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z), Terrain.Allowed.FLIGHT).y();
    }

    /**
     * Следующий шаг уходит из чанков, где тикают сущности: снаряд уходит в полёт вне мира с того места, где стоит
     * (или исчезает, если так не умеет), и шаг делает уже копия вне мира. Проверка — до шага: шагнув в чанк, где
     * сущности не тикают, снаряд оставался бы в мире без тика, с тикетами, которые снять уже некому, — такие
     * снаряды висели в мире до конца игры, а их тикеты держали чанки (стенд нагрузки 28.09.2026: 81 тикет на 2 снаряда).
     */
    protected final boolean leavesTickingChunks(ServerLevel level, Vec3 next) {
        if (virtual || level.isPositionEntityTicking(BlockPos.containing(next))) return false;
        if (fliesVirtually()) VirtualFlights.park(level, this);
        else discard();
        return true;
    }

    /** Перемещение без проверок (бомбардировщик, бурение). */
    protected void moveAlong(ServerLevel level, Vec3 next, Vec3 dir) {
        setPos(next.x, next.y, next.z);
        setYRot(flight.yaw());
        setXRot(flight.pitch());
        float roll = flight.bankAngle(speed);
        if (Math.abs(roll - entityData.get(DATA_ROLL)) > 0.2f) entityData.set(DATA_ROLL, roll);
        if (holdsChunks() && !virtual) chunks.update(level, getUUID(), next, dir, speed);
    }

    /**
     * Высота рельефа (верх препятствий) в точке. На сервере — только из готового чанка (иначе нижняя граница
     * мира): чтение незагруженного чанка из тика грузит его сразу и останавливает сервер.
     */
    public static double surfaceY(Level level, double x, double z) {
        return Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z), Terrain.Allowed.CHUNK).y();
    }

    /**
     * Датчик {@link Craft#relief}: высота рельефа в колонке ({@link #surfaceY}, только готовый чанк); вне мира рельеф
     * не читаем — низ мира.
     */
    protected double relief(Level level, int x, int z) {
        return virtual ? level.getMinBuildHeight() : surfaceY(level, x, z);
    }

    /**
     * Наибольшая высота рельефа под полосой ({@link Autopilot#reliefAlong}) прямо по курсу на {@code distance} блоков
     * по горизонтали, от колонки под снарядом.
     */
    protected double reliefStraightAhead(Level level, double distance) {
        Vec3 pos = position();
        double yawRad = Math.toRadians(flight.yaw());
        double[] track = {pos.x, pos.z, pos.x - Math.sin(yawRad) * distance, pos.z + Math.cos(yawRad) * distance};
        return Autopilot.reliefAlong(track, (x, z) -> relief(level, x, z));
    }

    /**
     * Датчик {@link Craft#clearAlong}: клетки блоков на прямой до точки без последних {@code margin} блоков, только по
     * готовым чанкам ({@link Terrain#readyUntil}); вне мира — свободна. Закрывает клетка с любой формой столкновения
     * целиком: у тонкого (забор, мачта из заборов — столб 0,25 блока) луч по форме ({@code Level.clip}) обычно проходит
     * мимо, а шахед своим корпусом его задевает. Цена — обход клеток по прямой ({@code BlockGetter.traverseBlocks}),
     * до первой закрытой.
     */
    protected double clearAlong(Level level, Vec3 to, double margin) {
        if (virtual) return Double.POSITIVE_INFINITY;
        Vec3 from = position();
        double length = from.distanceTo(to);
        if (length <= margin) return Double.POSITIVE_INFINITY;
        Vec3 end = Terrain.readyUntil(level, from, from.lerp(to, (length - margin) / length));
        BlockPos blocked = BlockGetter.traverseBlocks(from, end, level,
                (l, pos) -> l.getBlockState(pos).getCollisionShape(l, pos).isEmpty() ? null : pos.immutable(), l -> null);
        return blocked == null ? Double.POSITIVE_INFINITY : from.distanceTo(Vec3.atCenterOf(blocked));
    }

    // ---------------------------------------------------------------- чанки

    /**
     * Мир перестал его отслеживать — выгрузка вместе с чанком ({@code setRemoved}, без {@code remove}), остановка
     * сервера, чанк перестал выдаваться: тикеты отпускаются ({@code ServerLevel.EntityCallbacks.onTrackingEnd}).
     * Вернувшись из чанка, снаряд возьмёт тикеты заново в первом же тике. Снаряд в полёте так уходить не должен
     * (свой чанк он держит тикетом, из тикающих чанков уходит в {@link VirtualFlights}): пока он в чанке, которого
     * мир не отслеживает, его нет ни в мире, ни в полётах вне мира, и HUD с картой камеры его теряют — в лог.
     */
    @Override
    public void onRemovedFromLevel() {
        RemovalReason reason = getRemovalReason();
        if (level() instanceof ServerLevel level && level.getServer().isRunning() && isActive()
                && (reason == null || reason == RemovalReason.UNLOADED_TO_CHUNK)) {
            Airstrike.LOG.warn("Снаряд {} {} в полёте выгружен вместе с чанком у {} ({})", getType().getDescriptionId(), getUUID(), blockPosition(),
                    reason == null ? "чанк перестал отслеживаться" : "чанк выгружен");
        }
        super.onRemovedFromLevel();
        releaseTickets();
    }

    /**
     * Удаление (взрыв, отбой, уход в полёт вне мира) — всегда здесь: у снаряда вне мира (он в мир не добавлен)
     * и у снаряда в чанке, который мир уже не отслеживает, {@link #onRemovedFromLevel} не приходит.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && !isRemoved() && !level().isClientSide()) noteForeignRemoval(reason);
        super.remove(reason);
        releaseTickets();
    }

    /**
     * Снаряд убрал не мод (команда {@code /kill}, чистильщик сущностей другого мода): строка WARN на залп ({@link FlightLog},
     * кем — класс, позвавший удаление), цепочка вызовов — строкой DEBUG.
     * Свои концы полёта (взрыв, отбой, полёт вне мира) идут из кода мода и видны в стеке; чужое удаление иначе
     * выглядело бы как пропавший залп — без удара, ошибки и срока жизни.
     */
    private void noteForeignRemoval(RemovalReason reason) {
        List<StackWalker.StackFrame> frames = StackWalker.getInstance().walk(s -> s.skip(2).limit(48).toList());
        String own = StrikeProjectile.class.getPackageName().substring(0, StrikeProjectile.class.getPackageName().lastIndexOf('.'));
        for (StackWalker.StackFrame f : frames) if (f.getClassName().startsWith(own)) return;
        StringBuilder by = new StringBuilder();
        for (int i = 0; i < Math.min(6, frames.size()); i++) {
            StackWalker.StackFrame f = frames.get(i);
            if (i > 0) by.append(" ← ");
            by.append(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)).append('.').append(f.getMethodName());
        }
        // кем — первый вызов не из самой сущности (discard, kill)
        String who = frames.stream().map(StackWalker.StackFrame::getClassName).filter(c -> !c.startsWith("net.minecraft.world.entity."))
                .findFirst().orElse("?");
        Airstrike.LOG.debug("Снаряд {} {} у {} (фаза {}) убран не модом ({}): {}", getType().getDescriptionId(), getUUID(), blockPosition(),
                flightPhase(), reason, by);
        if (level() instanceof ServerLevel level) {
            StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), FlightLog.Event.REMOVED, blockPosition(), targetLost(), 0,
                    who.substring(who.lastIndexOf('.') + 1) + " (" + reason + ")");
        }
    }

    private void releaseTickets() {
        if (level() instanceof ServerLevel level) chunks.release(level, getUUID());
        releaseTargetArea();
    }

    /**
     * Сервер останавливается: снаряд в мире уходит в полёт вне мира ({@link VirtualFlights} сохраняются вместе
     * с миром), а не замирает в файле чанка, который после запуска никто может не загрузить часами. Тикеты
     * на чанки после запуска всё равно сняты бы (см. {@link ChunkTickets}).
     */
    public void parkForShutdown(ServerLevel level) {
        if (fliesVirtually()) VirtualFlights.park(level, this);
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
        if (armed()) {
            impact(level, position(), null);
            return;
        }
        Airstrike.LOG.debug("Снаряд {} {} сбит до взведения у {} (фаза {}, урон {})", getType().getDescriptionId(), getUUID(),
                blockPosition(), flightPhase(), source.getMsgId());
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), FlightLog.Event.SHOT_DOWN, blockPosition(), targetLost(), 0,
                source.getMsgId());
        crash(level, position());
    }

    // ---------------------------------------------------------------- синхронизация и интерполяция

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ROLL, 0f);
        builder.define(DATA_SPEED, 0f);
        builder.define(DATA_VELOCITY, new Vector3f());
        builder.define(DATA_PHASE, (byte) FlightPhase.CRUISE.ordinal());
        builder.define(DATA_AIM, new Vector3f());
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_NUCLEAR, false);
        builder.define(DATA_HIDDEN, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_PHASE.equals(key) && level().isClientSide) {
            byte phase = entityData.get(DATA_PHASE);
            if (phase != clientPhase) {
                clientPhase = phase;
                phaseStart = age;
            }
        }
    }

    /**
     * Возраст и возраст фазы уходят клиенту вместе с появлением сущности: кто начал видеть снаряд посреди фазы
     * (подошёл, вернулся в мир), видит анимации фазы (створки, крылья, выход из пусковой) с того же места, что и сервер.
     * Часы мира для этого не годятся — у клиента они прыгают, когда сервер догоняет отставание.
     */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeByte(entityData.get(DATA_PHASE));
        buf.writeVarInt(age);
        buf.writeVarInt(phaseAge());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        clientPhase = buf.readByte();
        age = buf.readVarInt();
        phaseStart = age - buf.readVarInt();
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
        altitude.reset(tag.getDouble("alt"));
        health = tag.contains("health") ? tag.getFloat("health") : -1;
        entityData.set(DATA_PHASE, (byte) FlightPhase.byName(tag.getString("flight_phase")).ordinal());
        phaseStart = age - tag.getInt("phase_age");
        entityData.set(DATA_HIDDEN, tag.getInt("hidden_ticks"));
        if (tag.contains("tracker")) tracker = TargetTracker.load(tag.getCompound("tracker"));
        if (tag.hasUUID("owner")) entityData.set(DATA_OWNER, Optional.of(tag.getUUID("owner")));
        route = tag.contains("route") ? Route.load(tag.getCompound("route")) : null;
        launchPos = Nbt.getVec(tag, "launch");
        areaWait = tag.getInt("area_wait");
        mission = readMission(tag);
        grounded = Nbt.getVec(tag, "grounded");
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
        tag.putDouble("alt", altitude.filter());
        tag.putFloat("health", health);
        tag.putString("flight_phase", flightPhase().getSerializedName());
        tag.putInt("phase_age", phaseAge());
        tag.putInt("hidden_ticks", entityData.get(DATA_HIDDEN));
        if (tracker != null) tag.put("tracker", tracker.save());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
        if (route != null) tag.put("route", route.save());
        if (launchPos != null) Nbt.putVec(tag, "launch", launchPos);
        tag.putInt("area_wait", areaWait);
        tag.put("mission", mission().save());
        if (grounded != null) Nbt.putVec(tag, "grounded", grounded);
        tag.putInt("ready_ticks", readyTicks);
        tag.putInt("siren_lead", sirenLead);
        if (nuclear != null) Loadout.Nuke.CODEC.encodeStart(NbtOps.INSTANCE, nuclear).result().ifPresent(n -> tag.put("nuclear", n));
    }

    /**
     * Запас хода из сохранения. Снаряд, сохранённый со сроком жизни (2.3.0 и раньше, ключа {@code mission} нет): остаток
     * срока (ожидание района цели в нём не считалось) и дробные тики погони — полёт на маршевой скорости, с которой его
     * сохранили ({@link #savedCruiseSpeed}); урезанный после потери цели остаётся урезанным.
     */
    @Nullable
    private Mission readMission(CompoundTag tag) {
        if (tag.contains("mission")) return Mission.load(tag.getCompound("mission"));
        if (!tag.contains("lifetime")) return null;
        int lifetime = tag.getInt("lifetime");
        double total = lifetime > 0 ? lifetime : airframe().range() / cruiseSpeed();
        return Mission.fromLifetime(total - (age - areaWait), tag.getDouble("lifetime_credit"), tag.getBoolean("lost_capped"),
                savedCruiseSpeed(tag));
    }

    /** Маршевая скорость, с которой снаряд сохранён (для переноса старого срока жизни в запас хода). */
    protected double savedCruiseSpeed(CompoundTag tag) {
        return cruiseSpeed();
    }
}
