package ua.zentix.airstrike.entity;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetTracker;

import java.util.Optional;
import java.util.UUID;

/**
 * Общая основа снарядов: ориентация и поворот ({@link FlightController}), слежение за целью, заметание пути на
 * столкновения (блоки, аппараты Sable, люди рядом с траекторией), тикеты чанков по курсу, синхронизация, сохранение.
 * <p>
 * Ошибка в тике одного снаряда не роняет сервер: снаряд удаляется, стек пишется в лог.
 */
public abstract class StrikeProjectile extends Entity {
    private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Vector3f> DATA_AIM = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.OPTIONAL_UUID);

    protected final FlightController flight = new FlightController(0, 0);
    protected double speed;
    @Nullable
    protected TargetTracker tracker;
    protected int age;
    /** Сколько урона ещё выдержит; -1 — не задано (берётся {@link #maxHealth()}). */
    protected float health = -1;
    /** Сглаженная заданная высота (фильтр как в датапаке: 1/5 разницы за тик). */
    protected double altFilter;

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

    /** Через сколько тиков взрываться в любом случае. */
    protected abstract int maxAge();

    /** Прочность: сколько урона выдержит, прежде чем его собьют. 0 — сбить нельзя. */
    protected float maxHealth() {
        return 0;
    }

    /** Держать ли тикеты чанков по курсу (бомбардировщику не нужно: улетает и исчезает). */
    protected boolean holdsChunks() {
        return true;
    }

    // ---------------------------------------------------------------- запуск

    /** Поставить снаряд в точку, развернуть на цель и привязать к владельцу. */
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        this.tracker = new TargetTracker(target, targetPoint);
        float[] a = FlightController.anglesTo(pos, targetPoint);
        this.flight.set(a[0], 0);
        this.moveTo(pos.x, pos.y, pos.z, a[0], 0);
        this.yRotO = a[0];
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        syncAim();
        syncSpeed();
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

    public int phase() {
        return entityData.get(DATA_PHASE);
    }

    protected void setPhase(int phase) {
        entityData.set(DATA_PHASE, (byte) phase);
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

    /** Снаряд виден и слышен (ракета, ждущая окончания сирены, — нет). */
    public boolean isActive() {
        return true;
    }

    /** Цель, за которой идёт снаряд (сервер). */
    @Nullable
    public Target target() {
        return tracker == null ? null : tracker.target();
    }

    // ---------------------------------------------------------------- тик

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientLerp();
            age++;
            clientTick();
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
            syncSpeed();
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Снаряд {} в {} упал с ошибкой и убран", getType().getDescriptionId(), blockPosition(), e);
            discard();
        }
    }

    protected abstract void serverTick(ServerLevel level);

    /** Клиент: след, дым. Звук ведёт клиентский менеджер звуков. */
    protected void clientTick() {}

    /** Точка удара достигнута или столкновение: взрыв, бурение и т.п. */
    protected abstract void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity);

    /** Слежение за целью; возвращает текущую точку прицеливания. */
    protected Vec3 updateTarget(ServerLevel level) {
        tracker.tick(level, position());
        syncAim();
        return tracker.point();
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

    /**
     * Шаг полёта: заметаем путь носа на длину шага. Столкновение (блок, аппарат, человек рядом с траекторией)
     * или достижение цели — {@link #impact}. Возвращает true, если снаряд ещё летит.
     *
     * @param reachPad запас дальности подрыва сверх длины шага (как в датапаке: шахед 4.3, ракета 6.5, бомба 5.3)
     */
    protected boolean advance(ServerLevel level, Vec3 aim, double reachPad) {
        Vec3 dir = flight.forward();
        Vec3 pos = position();

        if (pos.distanceTo(aim) <= speed + reachPad) {
            impact(level, aim, null);
            return false;
        }
        if (age >= maxAge()) {
            impact(level, pos.add(dir.scale(noseLength())), null);
            return false;
        }

        Vec3 noseFrom = pos.add(dir.scale(noseLength() * 0.5));
        Vec3 noseTo = pos.add(dir.scale(speed + noseLength()));

        BlockHitResult block = level.clip(new ClipContext(noseFrom, noseTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        Vec3 blockPoint = null;
        if (block.getType() != HitResult.Type.MISS) {
            // попадание в аппарат Sable приходит в координатах плота
            blockPoint = SubLevels.toWorld(level, block.getLocation());
        }
        Vec3 sweepEnd = blockPoint != null ? blockPoint : noseTo;

        Entity victim = proximityVictim(level, noseFrom, sweepEnd);
        if (victim != null) {
            Vec3 at = victim.getBoundingBox().getCenter();
            impact(level, closestOnSegment(noseFrom, sweepEnd, at), victim);
            return false;
        }
        if (blockPoint != null) {
            impact(level, blockPoint, null);
            return false;
        }

        Vec3 next = pos.add(dir.scale(speed));
        moveAlong(level, next, dir);
        if (next.y < level.getMinBuildHeight() - 64) {
            discard();
            return false;
        }
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
        if (holdsChunks()) updateChunkTickets(level, next, dir);
    }

    /** Неконтактный взрыватель: как в датапаке, срабатывает на людей рядом с траекторией, а ещё на саму цель. */
    @Nullable
    private Entity proximityVictim(ServerLevel level, Vec3 from, Vec3 to) {
        double r = speed / 8 + 1.8;
        AABB sweep = new AABB(from, to).inflate(r + 1);
        Entity best = null;
        double bestT = Double.MAX_VALUE;
        UUID targetId = tracker != null && tracker.target() instanceof Target.OfEntity e ? e.uuid() : null;
        for (Entity e : level.getEntities(this, sweep, e -> isProximityTarget(e, targetId))) {
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

    private static boolean isProximityTarget(Entity e, @Nullable UUID targetId) {
        if (!e.isAlive() || e.isSpectator() || e instanceof StrikeProjectile) return false;
        return e instanceof Player || e.getUUID().equals(targetId);
    }

    protected static Vec3 closestOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0e-9) return a;
        double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return a.add(ab.scale(t));
    }

    /** Высота рельефа (верх препятствий) в точке. */
    public static double surfaceY(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
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

    /** Сбили: подрыв там, где настигло. */
    protected void shotDown(ServerLevel level, DamageSource source) {
        impact(level, position(), null);
    }

    // ---------------------------------------------------------------- синхронизация и интерполяция

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ROLL, 0f);
        builder.define(DATA_SPEED, 0f);
        builder.define(DATA_PHASE, (byte) 0);
        builder.define(DATA_AIM, new Vector3f());
        builder.define(DATA_OWNER, Optional.empty());
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
        setPhase(tag.getByte("phase"));
        if (tag.contains("tracker")) tracker = TargetTracker.load(tag.getCompound("tracker"));
        if (tag.hasUUID("owner")) entityData.set(DATA_OWNER, Optional.of(tag.getUUID("owner")));
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
        tag.putByte("phase", (byte) phase());
        if (tracker != null) tag.put("tracker", tracker.save());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
    }
}
