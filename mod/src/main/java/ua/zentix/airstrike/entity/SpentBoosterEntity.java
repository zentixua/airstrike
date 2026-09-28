package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Local;
import ua.zentix.airstrike.util.Particles;

/**
 * Отработавший стартовый ускоритель: отделяется от шахеда или ракеты, кувыркаясь, падает по баллистике,
 * первые секунды дымит догорающим топливом, падает с глухим стуком и через несколько секунд исчезает.
 * Никого не калечит (корпус пустой и лёгкий) и блоков не ломает.
 */
public class SpentBoosterEntity extends Entity {
    private static final EntityDataAccessor<Byte> DATA_WEAPON = SynchedEntityData.defineId(SpentBoosterEntity.class, EntityDataSerializers.BYTE);
    private static final int MAX_AGE = 400;
    private static final int LIE_TICKS = 100;

    private int age;
    private int landedAt = -1;
    /** Клиент: кувыркание, градусов за тик. */
    public final float spin;

    public SpentBoosterEntity(EntityType<? extends SpentBoosterEntity> type, Level level) {
        super(type, level);
        this.spin = (level.random.nextFloat() * 0.6f + 0.7f) * (level.random.nextBoolean() ? 18f : -18f);
    }

    /** Отделить ускоритель от снаряда: у хвоста, со скоростью снаряда, чуть вниз и в сторону. */
    public static void spawn(ServerLevel level, StrikeProjectile from, double tail) {
        SpentBoosterEntity b = new SpentBoosterEntity(ModEntities.SPENT_BOOSTER.get(), level);
        Vec3 at = Local.at(from.position(), from.getYRot(), from.getXRot(), 0, -0.5, -tail * 0.8);
        Vec3 v = from.getDeltaMovement().scale(0.55).add((level.random.nextDouble() - 0.5) * 0.2, -0.15, (level.random.nextDouble() - 0.5) * 0.2);
        b.moveTo(at.x, at.y, at.z, from.getYRot(), from.getXRot());
        b.setDeltaMovement(v);
        b.entityData.set(DATA_WEAPON, (byte) from.weapon().id());
        level.addFreshEntity(b);
    }

    public WeaponType weapon() {
        return WeaponType.byId(entityData.get(DATA_WEAPON));
    }

    public int age() {
        return age;
    }

    public boolean landed() {
        return onGround();
    }

    @Override
    public void tick() {
        super.tick();
        age++;
        Vec3 v = getDeltaMovement();
        if (!onGround()) {
            // тяжёлый корпус: гравитация сильнее, чем у предметов, сопротивление слабое
            v = v.scale(0.985).add(0, -0.06, 0);
        } else {
            v = v.multiply(0.5, 0, 0.5);
        }
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        if (level().isClientSide) {
            if (age < 70 && !onGround()) {
                Particles.burst(level(), ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 0.1, 0.1, 0.1, 0.005, 1);
                if (age < 12) Particles.burst(level(), ParticleTypes.SMALL_FLAME, getX(), getY(), getZ(), 0.05, 0.05, 0.05, 0.01, 2);
            }
            return;
        }
        if (onGround() && landedAt < 0) {
            landedAt = age;
            level().playSound(null, BlockPos.containing(position()), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 1.2f, 0.5f);
        }
        if (age > MAX_AGE || landedAt >= 0 && age - landedAt > LIE_TICKS || getY() < level().getMinBuildHeight() - 32) discard();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 512 * 512;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WEAPON, (byte) 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        age = tag.getInt("age");
        entityData.set(DATA_WEAPON, tag.getByte("weapon"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("age", age);
        tag.putByte("weapon", entityData.get(DATA_WEAPON));
    }
}
