package ua.zentix.airstrike.entity;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import net.minecraft.world.entity.EntitySelector;
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
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.StrikeService;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetTracker;
import ua.zentix.airstrike.util.Nbt;
import ua.zentix.airstrike.warhead.Warheads;

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
public abstract class StrikeProjectile extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    /**
     * Сдвиг за последний тик сервера ({@link #velocity()}). Ванильный пакет скорости сущности его не довезёт: он режет
     * компоненты до 3,9 блока/тик, а ракета летит 11,5, МБР — до 25.
     */
    private static final EntityDataAccessor<Vector3f> DATA_VELOCITY = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Vector3f> DATA_AIM = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> DATA_NUCLEAR = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BOOLEAN);
    /** Сколько первых тиков на пусковой снаряд не виден (пакет ещё поднимается — снаряд «в ячейке»). */
    private static final EntityDataAccessor<Integer> DATA_HIDDEN = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.INT);

    /** Взрыватель взводится на таком удалении от пусковой (или с выходом на маршевый участок). */
    private static final double ARM_DISTANCE = 96;
    /**
     * Дольше минуты район цели не загрузился — снаряд убирается: сервер не справляется с генерацией (десятки районов
     * по 9×9 чанков от залпа с разбросом) или цель недостижима.
     */
    /** Запас радиуса разворота в {@link #insideTurn}: угловая скорость набирается не сразу. */
    private static final double TURN_MARGIN = 1.2;
    private static final int AREA_WAIT_LIMIT = 1200;
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
    /** Клиент: фаза, от смены которой отсчитан {@link #phaseStart}. */
    private byte clientPhase = -1;
    /** Сколько стоять на пусковой до поджига. */
    private int readyTicks;
    /** Включить сирену у цели, когда до удара останется столько тиков; -1 — без сирены. */
    private int sirenLead = -1;
    /** Летит вне загруженного мира (см. {@link VirtualFlights}). */
    private boolean virtual;
    /** Сколько тиков снаряд вне мира ждал у цели загрузки её района: в срок жизни не входит (см. {@link #expired}). */
    private int areaWait;
    /** Вне мира дошёл до цели, чей район тикает: вернуться в мир здесь же, без запаса впереди (не сохраняется). */
    private boolean arrived;
    /**
     * Вне мира путь снаряда встретил поверхность (столкновений там нет): здесь он ждёт загрузки места, возвращается
     * в мир и в первом же тике попадает — обычным {@link #impact}. Null — путь поверхности не встречал.
     */
    @Nullable
    private Vec3 grounded;
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

    /**
     * Срок жизни вышел. Ожидание у цели загрузки её района (вне мира) в него не входит: на медленном сервере
     * генерация района под залп с разбросом идёт дольше запаса в сроке жизни, и снаряды пропадали без подрыва
     * (стенд нагрузки 28.09.2026: 38 из 41 ракеты РСЗО). У ожидания свой предел — {@link #AREA_WAIT_LIMIT}.
     */
    protected final boolean expired() {
        return age - areaWait >= maxAge();
    }

    /** Прочность: сколько урона выдержит, прежде чем его собьют. 0 — сбить нельзя. */
    protected float maxHealth() {
        return 0;
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
        extendLifetime(tracker.retarget(target, point));
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
            if (grounded != null) {
                // вернулся в мир там, где путь вне мира встретил поверхность: попадание здесь
                Vec3 at = grounded;
                grounded = null;
                if (armed()) impact(level, at, null);
                else crash(level, at);
                return;
            }
            if (holdsChunks() && forcedChunks.isEmpty()) updateChunkTickets(level, position(), flight.forward());
            serverTick(level);
            // взорвался или ушёл в полёт вне мира (там летит уже копия): ни сирены, ни новых тикетов
            if (isRemoved()) return;
            updateVelocity();
            checkSiren(level);
            holdTargetArea(level);
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
            if (isRemoved()) return;
            updateVelocity();
            checkSiren(level);
            holdTargetArea(level);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Снаряд {} вне мира у {} упал с ошибкой и убран", getType().getDescriptionId(), blockPosition(), e);
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
        return VirtualFlights.clearAhead(position(), flight.forward(), speed,
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
            // рельеф под снарядом и впереди на 5 тиков полёта, каждый блок (только готовые чанки): не возникнуть
            // перед склоном или стеной, которую не успеть перепрыгнуть
            double[] ahead = new double[(int) Math.max(80, speed * 5)];
            for (int i = 0; i < ahead.length; i++) ahead[i] = i + 1;
            double floor = Math.max(surfaceY(level, getX(), getZ()), terrainAhead(level, ahead)) + clearance();
            if (getY() < floor) {
                setPos(getX(), floor, getZ());
                altFilter = floor;
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
        // отпустит до первого тика, снаряд застынет в нетикающем чанке — ни полёта, ни ухода вне мира, ни срока жизни
        if (holdsChunks()) updateChunkTickets(level, position(), flight.forward());
    }

    /**
     * Район цели догружается в фоне, когда до неё осталось меньше {@link #PRELOAD_TICKS} полёта, — и в мире, и вне
     * его: снаряд в мире тоже летит туда, где никого нет (залп с разбросом в сотни блоков от игрока), и раньше
     * район брался только после ухода из тикающих чанков, у самой цели. Движущаяся цель
     * (аппарат, игрок) уводит район за собой: иначе снаряд ждал у неё загрузки, которой не будет, и пропадал
     * по сроку жизни (28.09.2026: 4 ракеты из 10 за улетающим аппаратом).
     */
    protected void holdTargetArea(ServerLevel level) {
        if (tracker == null || isRemoved()) return;
        // путь вне мира кончился на поверхности: грузится место попадания, а не цель
        Vec3 aim = grounded != null ? grounded : tracker.point();
        if (heldArea != null) {
            if (heldArea.getChessboardDistance(new ChunkPos(BlockPos.containing(aim))) < 2) return;
            releaseTargetArea();
        }
        double d = position().distanceTo(aim);
        if (d <= preloadDistance()) {
            heldArea = new ChunkPos(BlockPos.containing(aim));
            FlightTickets.hold(level, heldArea, targetArea(), getUUID(), true);
        }
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
     * Ещё тик ожидания загрузки района цели. Ожидание не входит в срок жизни, у него свой предел
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

    /** Слежение за целью; возвращает текущую точку прицеливания. */
    protected Vec3 updateTarget(ServerLevel level) {
        extendLifetime(tracker.tick(level));
        syncAim();
        return tracker.point();
    }

    /**
     * Цель сдвинулась (ушла, телепортировалась, перенацелена): срок жизни, рассчитанный по плану полёта, растёт
     * на время пролёта этого сдвига с тем же запасом. Иначе снаряд пропадал без подрыва по дороге к игроку,
     * улетевшему за 3000 блоков или вышедшему из игры там (стенд нагрузки 28.09.2026: 5 «Ланцетов» и ракета
     * из 158). Сдвиг ограничен запасом на погоню {@link TargetTracker#CHASE_BUDGET}: цель, которая всё время
     * уходит (элитры, быстрый аппарат), не держит снаряд и район цели вечно — кончился запас, цель потеряна,
     * срок жизни дальше не растёт. Застрявший при неподвижной цели снаряд срок убирает как прежде.
     */
    private void extendLifetime(double moved) {
        if (moved <= 0) return;
        lifetime = maxAge() + (int) Math.ceil(moved / cruiseSpeed() * 1.5);
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

    /**
     * Точка внутри круга разворота: с предельной угловой скоростью {@code maxRateDeg} °/тик снаряд до неё не довернёт
     * и кружил бы вокруг неё, пока не выйдет срок жизни (крылатая ракета на 12 блоках/тик и 3°/тик разворачивается
     * по кругу радиусом ~240 блоков: цель, сместившаяся вбок на атаке, оставалась внутри). Такой снаряд сначала уходит
     * прямо, пока точка не выйдет из круга, и заходит снова. С запасом на разгон угловой скорости — {@link #TURN_MARGIN}.
     */
    protected final boolean insideTurn(Vec3 point, double maxRateDeg) {
        Vec3 f = flight.forward();
        double fl = Math.sqrt(f.x * f.x + f.z * f.z);
        if (fl < 1e-6 || speed <= 0) return false;
        double fx = f.x / fl, fz = f.z / fl;
        double dx = point.x - getX(), dz = point.z - getZ();
        double r = speed * fl / Math.toRadians(maxRateDeg) * TURN_MARGIN;
        // центр разворота — сбоку, в сторону точки
        double nx = -fz, nz = fx;
        if (nx * dx + nz * dz < 0) {
            nx = -nx;
            nz = -nz;
        }
        double cx = dx - nx * r, cz = dz - nz * r;
        return cx * cx + cz * cz < r * r;
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
        if (expired()) {
            impact(level, pos.add(dir.scale(noseLength())), null);
            return false;
        }

        Vec3 noseFrom = pos.add(dir.scale(noseLength() * 0.5));
        // нос не заглядывает в неготовый чанк (clip грузил бы его); туда снаряд и не шагнёт — уйдёт в полёт вне мира
        Vec3 noseTo = Terrain.readyUntil(level, noseFrom, pos.add(dir.scale(speed + noseLength())));

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
        if (next.y < level.getMinBuildHeight() - 64) {
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
     * игрок в воздухе у края загрузки), пролетал цель и падал до конца срока жизни (стенд VPS 29.09.2026: ракеты РСЗО
     * в 900 блоках под миром).
     */
    private boolean advanceVirtual(ServerLevel level, Vec3 aim, double reachPad, Vec3 dir) {
        if (grounded != null) {
            // стоит на поверхности: вернуться в мир, как только место загрузится (ожидание — с тем же пределом),
            // на настоящую поверхность из карты высот загруженного чанка
            if (!aimAreaReady(level, grounded)) return waitForAimArea(grounded);
            if (!level.dimensionType().hasCeiling()) {
                grounded = new Vec3(grounded.x, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(grounded.x), Mth.floor(grounded.z)), grounded.z);
            }
            moveAlong(level, grounded, dir);
            arrived = true;
            return true;
        }
        if (expired()) {
            Airstrike.LOG.warn("Снаряд {} {} не долетел до {} за срок жизни и убран у {}", getType().getDescriptionId(), getUUID(),
                    BlockPos.containing(aim), blockPosition());
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
        Vec3 ground = groundCrossing(level, pos, next);
        if (ground != null) {
            // мимо цели (или цель под землёй): вне мира столкновений нет, и снаряд падал бы без взрыва до конца срока
            // жизни или до низа мира (бомба на точку позади B-2 — до y=−3022); путь кончается на поверхности
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
     * Где шаг вне мира уходит под поверхность (null — не уходит). Поверхность — из карты высот готового чанка; у не
     * готового — уровень моря генератора ({@code Level.getSeaLevel} в 1.21.1 — всегда 63, у плоского мира море −63):
     * ниже него суша почти не бывает (над водой поверхность — сама вода), а рельеф
     * генератора ({@code ChunkGenerator.getBaseHeight}) стоит миллисекунды на точку — не для каждого тика. Настоящую высоту
     * место попадания получает, когда загрузится. В мире с потолком (Незер) карта высот — потолок: там только море.
     */
    @Nullable
    private Vec3 groundCrossing(ServerLevel level, Vec3 from, Vec3 to) {
        // снаряд в этом же тике вернётся в мир (над рельефом, с запасом впереди — materialize), а там столкновения свои
        if (canMaterialize(level)) return null;
        int x = Mth.floor(to.x), z = Mth.floor(to.z);
        int ground = !level.dimensionType().hasCeiling() && Terrain.ready(level, x >> 4, z >> 4)
                ? Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z) : level.getChunkSource().getGenerator().getSeaLevel();
        if (to.y >= ground) return null;
        double t = from.y > to.y ? Mth.clamp((from.y - ground) / (from.y - to.y), 0, 1) : 0;
        Vec3 at = from.lerp(to, t);
        return new Vec3(at.x, ground, at.z);
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

    /**
     * Цель, выбранная оператором, взводит взрыватель всегда (кроме наблюдателя). Случайный человек у траектории — по
     * ванильному правилу «кого замечают»: не в творческом режиме и не наблюдатель
     * ({@link EntitySelector#NO_CREATIVE_OR_SPECTATOR}: так ванильные мобы выбирают, на кого нападать).
     */
    private static boolean isProximityTarget(Entity e, @Nullable UUID targetId, @Nullable UUID owner) {
        if (!e.isAlive() || e.isSpectator() || e instanceof StrikeProjectile) return false;
        if (e.getUUID().equals(targetId)) return true;
        return e instanceof Player && !e.getUUID().equals(owner) && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e);
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

    /** Наибольшая высота рельефа на нескольких расстояниях впереди по горизонтали (и вне мира: только готовые чанки). */
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

    /**
     * Держим свой чанк и чанк впереди по курсу — но только уже готовые ({@link Terrain#ready}): новые чанки на лету
     * не генерируем, а {@code hasChunk} верен и для чанка, который ещё грузится.
     */
    private void updateChunkTickets(ServerLevel level, Vec3 pos, Vec3 dir) {
        long here = ChunkPos.asLong(BlockPos.containing(pos));
        long ahead = ChunkPos.asLong(BlockPos.containing(pos.add(dir.scale(Math.max(16, speed * 3)))));
        if (forcedChunks.contains(here) && forcedChunks.contains(ahead) && forcedChunks.size() == (here == ahead ? 1 : 2)) return;
        LongSet want = new LongOpenHashSet();
        for (long c : new long[]{here, ahead}) {
            if (Terrain.ready(level, ChunkPos.getX(c), ChunkPos.getZ(c))) want.add(c);
        }
        for (long c : forcedChunks.toLongArray()) {
            if (!want.contains(c)) {
                ChunkTickets.hold(level, getUUID(), c, false);
                forcedChunks.remove(c);
            }
        }
        for (long c : want) {
            if (forcedChunks.add(c)) ChunkTickets.hold(level, getUUID(), c, true);
        }
    }

    private void releaseChunkTickets() {
        if (level() instanceof ServerLevel level) {
            for (long c : forcedChunks) ChunkTickets.hold(level, getUUID(), c, false);
        }
        forcedChunks.clear();
    }

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
            Airstrike.LOG.warn("Снаряд {} в полёте выгружен вместе с чанком у {} ({})", getType().getDescriptionId(), blockPosition(),
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
        super.remove(reason);
        releaseTickets();
    }

    private void releaseTickets() {
        releaseChunkTickets();
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
        if (armed()) impact(level, position(), null);
        else crash(level, position());
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
        altFilter = tag.getDouble("alt");
        health = tag.contains("health") ? tag.getFloat("health") : -1;
        entityData.set(DATA_PHASE, (byte) FlightPhase.byName(tag.getString("flight_phase")).ordinal());
        phaseStart = age - tag.getInt("phase_age");
        entityData.set(DATA_HIDDEN, tag.getInt("hidden_ticks"));
        if (tag.contains("tracker")) tracker = TargetTracker.load(tag.getCompound("tracker"));
        if (tag.hasUUID("owner")) entityData.set(DATA_OWNER, Optional.of(tag.getUUID("owner")));
        route = tag.contains("route") ? Route.load(tag.getCompound("route")) : null;
        launchPos = Nbt.getVec(tag, "launch");
        lifetime = tag.getInt("lifetime");
        areaWait = tag.getInt("area_wait");
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
        tag.putDouble("alt", altFilter);
        tag.putFloat("health", health);
        tag.putString("flight_phase", flightPhase().getSerializedName());
        tag.putInt("phase_age", phaseAge());
        tag.putInt("hidden_ticks", entityData.get(DATA_HIDDEN));
        if (tracker != null) tag.put("tracker", tracker.save());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
        if (route != null) tag.put("route", route.save());
        if (launchPos != null) Nbt.putVec(tag, "launch", launchPos);
        tag.putInt("lifetime", lifetime);
        tag.putInt("area_wait", areaWait);
        if (grounded != null) Nbt.putVec(tag, "grounded", grounded);
        tag.putInt("ready_ticks", readyTicks);
        tag.putInt("siren_lead", sirenLead);
        if (nuclear != null) Loadout.Nuke.CODEC.encodeStart(NbtOps.INSTANCE, nuclear).result().ifPresent(n -> tag.put("nuclear", n));
    }
}
