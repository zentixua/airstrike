package ua.zentix.airstrike.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

import java.util.function.Supplier;

/** Двойник стержня Энда (светящиеся трубки вывесок): форма и направление те же, но без искр. */
public class UnlitEndRodBlock extends EndRodBlock implements Unlit {
    private final Supplier<Block> lit;

    public UnlitEndRodBlock(Properties properties, Supplier<Block> lit) {
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
        return Unlit.clone(lit);
    }

    /** Погасший стержень не сыплет светящихся частиц. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {}
}
