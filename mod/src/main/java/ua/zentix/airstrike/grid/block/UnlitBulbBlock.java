package ua.zentix.airstrike.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;

import java.util.function.Supplier;

/**
 * Двойник медной лампы: хранит её «горит» и «под сигналом», на сигнал не переключается и не окисляется, пока
 * света нет, — после блэкаута лампа та же, что была.
 */
public class UnlitBulbBlock extends Block implements Unlit {
    private final Supplier<Block> lit;

    public UnlitBulbBlock(Properties properties, Supplier<Block> lit) {
        super(properties);
        this.lit = lit;
        registerDefaultState(defaultBlockState().setValue(BlockStateProperties.LIT, false).setValue(BlockStateProperties.POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.LIT, BlockStateProperties.POWERED);
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
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return Unlit.clone(lit);
    }
}
