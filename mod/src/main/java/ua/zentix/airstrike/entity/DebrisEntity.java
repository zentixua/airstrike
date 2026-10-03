package ua.zentix.airstrike.entity;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModEntities;

/**
 * Обломок взрыва: блок грунта, кусок корпуса или тлеющий уголь. Летит по баллистике (как falling_block),
 * калечит тех, в кого попал, и при падении ложится блоком (если включено {@code debris_stay}) или рассыпается.
 * Горячие обломки оставляют огненно-дымный след и могут поджечь место падения.
 */
public class DebrisEntity extends Entity {
    private static final EntityDataAccessor<BlockState> DATA_BLOCK = SynchedEntityData.defineId(DebrisEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Boolean> DATA_HOT = SynchedEntityData.defineId(DebrisEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int MAX_AGE = 600;

    private int age;
    private boolean hurtsEntities = true;
    private final IntSet hit = new IntOpenHashSet();
    /** Клиент: случайное вращение в полёте (градусов за тик вокруг двух осей). */
    public final float spinX, spinZ;

    public DebrisEntity(EntityType<? extends DebrisEntity> type, Level level) {
        super(type, level);
        this.spinX = (level.random.nextFloat() - 0.5f) * 40f;
        this.spinZ = (level.random.nextFloat() - 0.5f) * 40f;
    }

    public static DebrisEntity create(ServerLevel level, Vec3 pos, BlockState state, boolean hot, boolean hurts, Vec3 velocity) {
        DebrisEntity d = new DebrisEntity(ModEntities.DEBRIS.get(), level);
        d.setPos(pos.x, pos.y, pos.z);
        d.xo = pos.x;
        d.yo = pos.y;
        d.zo = pos.z;
        d.entityData.set(DATA_BLOCK, state);
        d.entityData.set(DATA_HOT, hot);
        d.hurtsEntities = hurts;
        d.setDeltaMovement(velocity);
        return d;
    }

    public BlockState blockState() {
        return entityData.get(DATA_BLOCK);
    }

    public boolean isHot() {
        return entityData.get(DATA_HOT);
    }

    public int age() {
        return age;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    @Override
    public void tick() {
        if (blockState().isAir()) {
            discard();
            return;
        }
        age++;
        Vec3 before = getDeltaMovement();
        applyGravity();
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(0.98));

        if (level().isClientSide) return;
        ServerLevel level = (ServerLevel) level();
        // улетел из тикающих чанков (за край района цели): там он больше не тикает и висел бы в воздухе, пока чанк
        // не выгрузится, — рассыпается сразу и не ложится блоком туда, где мир не тикает
        if (!level.isPositionEntityTicking(blockPosition())) {
            discard();
            return;
        }
        if (hurtsEntities && before.lengthSqr() > 0.09) hurtWhoIsHit(level, before);
        if (onGround() || horizontalCollision && before.y < 0 && getDeltaMovement().horizontalDistanceSqr() < 1.0e-4) {
            land(level, before);
        } else if (age > MAX_AGE || getY() < level.getMinBuildHeight() - 16) {
            discard();
        }
    }

    /** Как у falling_block: 2 урона за блок падения, но не больше 16; каждому — один раз. */
    private void hurtWhoIsHit(ServerLevel level, Vec3 v) {
        for (Entity e : level.getEntities(this, getBoundingBox().expandTowards(v).inflate(0.2), e -> e instanceof LivingEntity && e.isAlive())) {
            if (!hit.add(e.getId())) continue;
            float dmg = (float) Math.min(16, Math.max(2, fallDistance * 2 + v.length() * 3));
            e.hurt(ModDamageTypes.source(level, ModDamageTypes.DEBRIS, this, null), dmg);
        }
    }

    private void land(ServerLevel level, Vec3 impactVelocity) {
        BlockPos pos = blockPosition();
        if (impactVelocity.y <= -0.05) {
            level.playSound(null, pos, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 2.0f, 0.6f);
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.5f, 0.7f);
            level.sendParticles(ParticleTypes.POOF, getX(), getY(), getZ(), 5, 0.3, 0.1, 0.3, 0.03);
            if (isHot()) level.sendParticles(ParticleTypes.LAVA, getX(), getY(), getZ(), 3, 0.2, 0.1, 0.2, 0);
        }
        if (AirstrikeConfig.SERVER.debrisStay.get()) place(level, pos);
        discard();
    }

    private void place(ServerLevel level, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        BlockState state = blockState();
        boolean replaceable = here.canBeReplaced(new DirectionalPlaceContext(level, pos, Direction.DOWN, ItemStack.EMPTY, Direction.UP));
        boolean supported = state.canSurvive(level, pos) && !FallingBlock.isFree(level.getBlockState(pos.below()));
        if (!replaceable || !supported) return;
        if (state.is(Blocks.FIRE) && !AirstrikeConfig.SERVER.fire.get()) return;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && level.getFluidState(pos).getType() == Fluids.WATER) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        level.setBlock(pos, state, 3);
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_BLOCK, Blocks.GRAVEL.defaultBlockState());
        builder.define(DATA_HOT, false);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(DATA_BLOCK, NbtUtils.readBlockState(level().holderLookup(Registries.BLOCK), tag.getCompound("block")));
        entityData.set(DATA_HOT, tag.getBoolean("hot"));
        hurtsEntities = tag.getBoolean("hurts");
        age = tag.getInt("age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("block", NbtUtils.writeBlockState(blockState()));
        tag.putBoolean("hot", isHot());
        tag.putBoolean("hurts", hurtsEntities);
        tag.putInt("age", age);
    }

    @Nullable
    @Override
    public ItemStack getPickResult() {
        return ItemStack.EMPTY;
    }
}
