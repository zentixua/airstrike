package ua.zentix.airstrike.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Мобильная пусковая установка у стреляющего: прицеп с наклонной направляющей. Для шахедов — пакет на пять
 * ячеек (как на иранских пусковых; с него же катапультой стартуют барражирующие), для крылатых ракет — два наклонных контейнера. Появляется рядом с игроком
 * при первом пуске, разворачивает пакет (подъём на угол возвышения) и дальше служит всем его пускам поблизости:
 * залп идёт с одной установки по ячейкам. Ломается ударом (без дропа); «Отбой» убирает все.
 */
public class LauncherEntity extends Entity implements Launcher {
    private static final EntityDataAccessor<Byte> DATA_WEAPON = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Long> DATA_DEPLOYED = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Сколько тиков пакет поднимается на угол возвышения. */
    public static final int DEPLOY_TICKS = 40;

    private final LaunchQueue queue = new LaunchQueue();

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
        return l;
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.byId(entityData.get(DATA_WEAPON));
    }

    @Nullable
    public UUID ownerId() {
        return entityData.get(DATA_OWNER).orElse(null);
    }

    /** Снаряд этой пусковой: того же оружия и того же владельца (пусковая одна на игрока и оружие рядом с ним). */
    public boolean serves(StrikeProjectile p) {
        return p.weapon() == weapon() && Objects.equals(p.ownerId(), ownerId());
    }

    @Override
    public float yaw() {
        return getYRot();
    }

    @Override
    public LauncherMount mount() {
        return LauncherMount.TRAILER;
    }

    @Override
    public long deployedAt() {
        return entityData.get(DATA_DEPLOYED);
    }

    @Override
    public LaunchQueue queue() {
        return queue;
    }

    /** Пакет пусковой оружия ({@link LauncherRack#of}). */
    public static LauncherRack rack(WeaponType weapon) {
        return LauncherRack.of(weapon);
    }

    /** Угол возвышения направляющей (паспорт): шахеды 15°, катапульта барражирующих 20°, ракеты 40°, трубы РСЗО 50°. */
    public static float elevation(WeaponType weapon) {
        return rack(weapon).elevation();
    }

    public static int slots(WeaponType weapon) {
        return rack(weapon).slots();
    }

    /** Пакет РСЗО: 4 ряда по 10 труб, шаг труб и длина трубы (блоков). */
    public static final int ROCKET_COLUMNS = 10, ROCKET_ROWS = 4;
    public static final float TUBE_PITCH = 0.3f, TUBE_LENGTH = 3.2f;

    /** Центр снаряда на направляющей {@code slot} у прицепа оружия {@code weapon}, стоящего в {@code pos} с курсом {@code yaw}. */
    public static Vec3 railPoint(Vec3 pos, float yaw, WeaponType weapon, int slot) {
        return LauncherMount.TRAILER.railPoint(pos, yaw, weapon, slot);
    }

    /** Направляющие барражирующих: три внизу, два сверху (0.83 м между осями). */
    public static float loiterLeft(int slot) {
        return slot < 3 ? 0.83f - 0.83f * slot : slot == 3 ? 0.415f : -0.415f;
    }

    public static float loiterUp(int slot) {
        return slot < 3 ? 0.5f : 1.35f;
    }

    /** Наименьший интервал между пусками с одной установки, тиков: РСЗО — полсекунды, остальные — 0.8 с. */
    public static int spacing(WeaponType weapon) {
        return rack(weapon).spacing();
    }

    /** Прицеп поворачивается целиком. */
    @Override
    public void turnTo(float yaw, long now) {
        if (turnedYaw(yaw, now) == getYRot()) return;
        setYRot(yaw);
        yRotO = yaw;
        entityData.set(DATA_DEPLOYED, now);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && level().getGameTime() - deployedAt() < DEPLOY_TICKS) {
            // гидравлика поднимает пакет: пыхтит сизым паром у оси
            Vec3 pivot = mount().pivot(position(), getYRot()).subtract(0, 0.6, 0);
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
    public boolean ignoreExplosion(Explosion explosion) {
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
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("weapon", weapon().getSerializedName());
        tag.putLong("deployed", deployedAt());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
    }
}
