package ua.zentix.airstrike.launcher;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.item.DesignatorItem;

/**
 * Стационарная пусковая установка ({@link FixedLauncherBlockEntity}): тумба с поворотным кругом, на круге — пакет
 * оружия задачи. Помнит, кто поставил. Боеприпасы кладутся рукой (ПКМ ими), воронкой и Create; ПКМ пультом —
 * привязать пусковую к пульту или отвязать ({@link LauncherLinks}): «Огонь» привязанного пульта ставит ей задачу.
 * Пустой рукой — строка состояния. Сигнал редстоуна (как у раздатчика: передний фронт, свой или блока над ней) —
 * один приказ по задаче. Сломанная или взорванная — выпадает сама (таблица добычи) вместе с запасом.
 */
public class FixedLauncherBlock extends BaseEntityBlock {
    public static final MapCodec<FixedLauncherBlock> CODEC = simpleCodec(FixedLauncherBlock::new);
    public static final BooleanProperty TRIGGERED = BlockStateProperties.TRIGGERED;
    /** Сигнал — пуск через столько тиков, как у раздатчика. */
    private static final int DELAY = 4;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);

    public FixedLauncherBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TRIGGERED, false));
    }

    @Override
    protected MapCodec<FixedLauncherBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(TRIGGERED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** У блока с блок-сущностью ваниль по умолчанию модель не рисует. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FixedLauncherBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be)) return;
        // хозяин — поставивший; механизм Create ставит от имени своего хозяина (у его FakePlayer тот же UUID)
        if (placer instanceof Player p) be.setOwner(p.getUUID());
        // пакет смотрит от ставящего, как раздатчик
        if (placer != null) be.setYaw(placer.getYRot());
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        boolean powered = level.hasNeighborSignal(pos) || level.hasNeighborSignal(pos.above());
        boolean triggered = state.getValue(TRIGGERED);
        if (powered && !triggered) {
            level.scheduleTick(pos, this, DELAY);
            level.setBlock(pos, state.setValue(TRIGGERED, true), Block.UPDATE_CLIENTS);
        } else if (!powered && triggered) {
            level.setBlock(pos, state.setValue(TRIGGERED, false), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be) be.fire(level);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be) {
            be.dropContents(level, pos);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (stack.getItem() instanceof DesignatorItem) {
            if (player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be) {
                sp.displayClientMessage(LauncherLinks.toggle(sp, stack, be), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!FixedLauncherBlockEntity.munition(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be) {
            ItemStack rest = be.load(stack.copy());
            int put = stack.getCount() - rest.getCount();
            if (put > 0) {
                if (!player.getAbilities().instabuild) stack.shrink(put);
                level.playSound(null, pos, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.6f, 1.2f);
            } else {
                player.displayClientMessage(Component.translatable("airstrike.fixed_launcher.full"), true);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be) player.displayClientMessage(be.status(), true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be ? be.comparator() : 0;
    }
}
