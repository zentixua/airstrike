package ua.zentix.airstrike.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;

public final class ModBlocks {
    public static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks(Airstrike.MOD_ID);

    /** Тринитит: песок, сплавленный огненным шаром наземного ядерного взрыва в зеленоватое стекло. */
    public static final DeferredBlock<Block> TRINITITE = REGISTER.registerSimpleBlock("trinitite", BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_GREEN)
            .strength(1.5f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.GLASS));

    private ModBlocks() {}
}
