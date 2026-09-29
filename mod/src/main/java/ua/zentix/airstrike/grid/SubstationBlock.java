package ua.zentix.airstrike.grid;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.registry.ModSounds;

/**
 * Трансформаторная подстанция: поставленная — узел сети радиусом {@code grid.node_radius}; удар рядом выводит её
 * из строя, и район гаснет каскадом ({@link Blackouts}). Работает — гудит; выбита — искрит и дымит, пока свет
 * не вернут. {@code powered} ставит сеть ({@link Substations#sync}), игрок его не меняет.
 */
public class SubstationBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<SubstationBlock> CODEC = simpleCodec(SubstationBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** Как модель: бетонная плита, бак с радиаторами по бокам, ряд вводов сверху (фасад — на север, повороты по 180° те же). */
    private static final VoxelShape SHAPE_NS = Shapes.or(Block.box(0, 0, 0, 16, 2, 16), Block.box(0.5, 2, 2, 15.5, 12, 14), Block.box(3.5, 12, 7, 12.5, 16, 9));
    private static final VoxelShape SHAPE_EW = Shapes.or(Block.box(0, 0, 0, 16, 2, 16), Block.box(2, 2, 0.5, 14, 12, 15.5), Block.box(7, 12, 3.5, 9, 16, 12.5));

    public SubstationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, true));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            PowerGrid grid = PowerGrid.get(server);
            if (grid.nodeAt(pos) == null) grid.addNode(pos, AirstrikeConfig.SERVER.gridNodeRadius.get(), true);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel server && !newState.is(this)) {
            PowerGrid grid = PowerGrid.get(server);
            Node node = grid.nodeAt(pos);
            if (node != null && node.block()) grid.removeNode(node.id());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Работает — гудит; выбита — трещит дугой, сыплет искрами и дымит. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.9, z = pos.getZ() + 0.5;
        if (state.getValue(POWERED)) {
            // случайный тик блока рядом с игроком — в среднем раз в 2 с; запись гула 4 с с плавными краями, наложения не слышно
            level.playLocalSound(x, y, z, ModSounds.GRID_HUM.get(), SoundSource.BLOCKS, 0.4f, 0.95f + random.nextFloat() * 0.1f, false);
            return;
        }
        level.addParticle(ParticleTypes.LARGE_SMOKE, x + random.nextGaussian() * 0.2, y + 0.2, z + random.nextGaussian() * 0.2, 0, 0.05, 0);
        if (random.nextInt(4) == 0) {
            for (int i = 0; i < 6; i++) {
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + random.nextGaussian() * 0.25, y + 0.15, z + random.nextGaussian() * 0.25,
                        random.nextGaussian() * 0.2, 0.1 + random.nextDouble() * 0.2, random.nextGaussian() * 0.2);
            }
            level.playLocalSound(x, y, z, ModSounds.GRID_SPARK.get(), SoundSource.BLOCKS, 0.8f, 0.9f + random.nextFloat() * 0.2f, false);
        }
    }
}
