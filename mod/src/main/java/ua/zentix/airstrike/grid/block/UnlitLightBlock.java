package ua.zentix.airstrike.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

import java.util.function.Supplier;

/**
 * Двойник невидимого блока света (им карты освещают улицы и комнаты): уровень хранится, чтобы вернуться, но свет
 * не идёт. Выбор блока, как у ванильного, даёт блок света с тем же уровнем.
 */
public class UnlitLightBlock extends LightBlock implements Unlit {
    private final Supplier<Block> lit;

    public UnlitLightBlock(Properties properties, Supplier<Block> lit) {
        super(properties);
        this.lit = lit;
    }

    @Override
    public Block lit() {
        return lit.get();
    }

    @Override
    public MutableComponent getName() {
        return Unlit.name(lit);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        Unlit.placed(state, level, pos, oldState);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Unlit.randomTick(state, level, pos);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return setLightOnStack(Unlit.clone(lit), state.getValue(LEVEL));
    }
}
