package ua.zentix.airstrike.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Local;

import java.util.Optional;
import java.util.UUID;

/**
 * Мобильная пусковая установка у стреляющего: прицеп с наклонной направляющей. Для шахедов — пакет на пять
 * ячеек (как на иранских пусковых; с него же катапультой стартуют барражирующие), для крылатых ракет — два наклонных контейнера. Появляется рядом с игроком
 * при первом пуске, разворачивает пакет (подъём на угол возвышения) и дальше служит всем его пускам поблизости:
 * залп идёт с одной установки по ячейкам. Ломается ударом (без дропа); «Отбой» убирает все.
 */
public class LauncherEntity extends Entity {
    private static final EntityDataAccessor<Byte> DATA_WEAPON = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Long> DATA_DEPLOYED = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Сколько тиков пакет поднимается на угол возвышения. */
    public static final int DEPLOY_TICKS = 40;
    /** Высота оси качания пакета над землёй и её вынос назад от центра прицепа. */
    public static final double PIVOT_UP = 1.6, PIVOT_BACK = 2.2;

    private long[] slotFreeAt = new long[0];
    private long lastStart = Long.MIN_VALUE / 2;

    public LauncherEntity(EntityType<? extends LauncherEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    public static LauncherEntity create(ServerLevel level, Vec3 pos, float yaw, WeaponType weapon, @Nullable UUID owner) {
        LauncherEntity l = new LauncherEntity(ModEntities.LAUNCHER.get(), level);
        l.moveTo(pos.x, pos.y, pos.z, yaw, 0);
        l.yRotO = yaw;
        l.entityData.set(DATA_WEAPON, (byte) weapon.id());
        l.entityData.set(DATA_DEPLOYED, level.getGameTime());
        l.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        l.slotFreeAt = new long[slots(weapon)];
        return l;
    }

    public WeaponType weapon() {
        return WeaponType.values()[Mth.clamp(entityData.get(DATA_WEAPON), 0, WeaponType.values().length - 1)];
    }

    @Nullable
    public UUID ownerId() {
        return entityData.get(DATA_OWNER).orElse(null);
    }

    public long deployedAt() {
        return entityData.get(DATA_DEPLOYED);
    }

    /** Угол возвышения направляющей: шахеды 15°, катапульта барражирующих 20°, ракеты 40°, трубы РСЗО 50°. */
    public static float elevation(WeaponType weapon) {
        return switch (weapon) {
            case MISSILE -> 40f;
            case ROCKET -> 50f;
            case LOITER -> 20f;
            default -> 15f;
        };
    }

    public float elevation() {
        return elevation(weapon());
    }

    /** Текущий угол пакета с учётом подъёма (клиент рисует по нему). */
    public float deployedElevation(long gameTime, float partialTick) {
        float t = Mth.clamp((gameTime - deployedAt() + partialTick) / DEPLOY_TICKS, 0, 1);
        float eased = t * t * (3 - 2 * t);
        return elevation() * eased;
    }

    public static int slots(WeaponType weapon) {
        return switch (weapon) {
            case MISSILE -> 2;
            case ROCKET -> ROCKET_COLUMNS * ROCKET_ROWS;
            default -> 5;
        };
    }

    /** Пакет РСЗО: 4 ряда по 10 труб, шаг труб и длина трубы (блоков). */
    public static final int ROCKET_COLUMNS = 10, ROCKET_ROWS = 4;
    public static final float TUBE_PITCH = 0.3f, TUBE_LENGTH = 3.2f;

    /**
     * Точка ячейки на направляющей (центр снаряда) в координатах мира, при полном подъёме пакета.
     * Ось пакета — поперечная, в {@link #PIVOT_UP} над землёй и {@link #PIVOT_BACK} позади центра прицепа.
     */
    public Vec3 railPoint(int slot) {
        float yaw = getYRot();
        Vec3 pivot = Local.at(position(), yaw, 0, 0, PIVOT_UP, -PIVOT_BACK);
        double left, up, forward;
        if (weapon() == WeaponType.MISSILE) {
            left = slot == 0 ? 0.85 : -0.85;
            up = 0.85;
            forward = 5.4;
        } else if (weapon() == WeaponType.ROCKET) {
            // очередь идёт по рядам слева направо, начиная с верхнего — как на «Граде»
            int col = slot % ROCKET_COLUMNS, row = ROCKET_ROWS - 1 - slot / ROCKET_COLUMNS;
            left = (ROCKET_COLUMNS - 1) * TUBE_PITCH / 2 - col * TUBE_PITCH;
            up = 0.3 + row * TUBE_PITCH;
            forward = TUBE_LENGTH / 2;
        } else {
            left = 0;
            up = 0.45 + 0.95 * slot;
            forward = 3.3;
        }
        return Local.at(pivot, yaw, -elevation(), left, up, forward);
    }

    /** Наименьший интервал между пусками с одной установки, тиков: РСЗО — полсекунды, остальные — 0.8 с. */
    public static int spacing(WeaponType weapon) {
        return weapon == WeaponType.ROCKET ? 8 : 16;
    }

    /**
     * Занять ячейку под пуск.
     *
     * @param busyTicks сколько тиков снаряд простоит в ячейке после готовности (поджиг и сход)
     * @return [ячейка, через сколько тиков поджиг] — не раньше, чем пакет поднимется и освободится ячейка
     */
    public int[] reserve(long now, int minReady, int busyTicks) {
        if (slotFreeAt.length != slots(weapon())) slotFreeAt = new long[slots(weapon())];
        int best = 0;
        for (int i = 1; i < slotFreeAt.length; i++) {
            if (slotFreeAt[i] < slotFreeAt[best]) best = i;
        }
        long deployDone = deployedAt() + DEPLOY_TICKS + 5;
        long start = Math.max(now + minReady, Math.max(slotFreeAt[best], deployDone));
        // пуски, заказанные, пока пакет поднимался, не срываются разом: очередь с интервалом
        start = Math.max(start, lastStart + spacing(weapon()));
        lastStart = start;
        slotFreeAt[best] = start + busyTicks;
        return new int[]{best, (int) (start - now)};
    }

    /**
     * Довернуть пакет на новую цель (РСЗО, барражирующие): если курс отличается больше чем на 15° и установка молчит —
     * пакет опускается, прицеп поворачивается, пакет поднимается снова (пуски ждут подъёма).
     */
    public void turnTo(float yaw, long now) {
        if (Math.abs(Mth.wrapDegrees(yaw - getYRot())) <= 15 || now < lastStart + 20) return;
        setYRot(yaw);
        yRotO = yaw;
        entityData.set(DATA_DEPLOYED, now);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && level().getGameTime() - deployedAt() < DEPLOY_TICKS) {
            // гидравлика поднимает пакет: пыхтит сизым паром у оси
            Vec3 pivot = Local.at(position(), getYRot(), 0, 0, PIVOT_UP - 0.6, -PIVOT_BACK);
            if (random.nextInt(3) == 0) level().addParticle(ParticleTypes.CLOUD, pivot.x, pivot.y, pivot.z, 0, 0.02, 0);
        }
    }

    // ---------------------------------------------------------------- удар ломает

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isRemoved() || isInvulnerableTo(source) || source.is(DamageTypes.IN_WALL)) return false;
        if (level() instanceof ServerLevel level && source.getEntity() != null) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.2, getZ(), 20, 1.5, 0.8, 1.5, 0.02);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.5f, 0.6f);
            discard();
        }
        return true;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 256 * 256;
    }

    // ---------------------------------------------------------------- данные

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WEAPON, (byte) WeaponType.DRONE.id());
        builder.define(DATA_DEPLOYED, 0L);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        WeaponType w = WeaponType.parse(tag.getString("weapon"));
        entityData.set(DATA_WEAPON, (byte) (w == null ? WeaponType.DRONE : w).id());
        entityData.set(DATA_DEPLOYED, tag.getLong("deployed"));
        entityData.set(DATA_OWNER, tag.hasUUID("owner") ? Optional.of(tag.getUUID("owner")) : Optional.empty());
        slotFreeAt = new long[slots(weapon())];
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("weapon", weapon().getSerializedName());
        tag.putLong("deployed", deployedAt());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
    }
}
