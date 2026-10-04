package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.defense.SamBlockEntity;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTER = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Airstrike.MOD_ID);

    /** ЗРК: хозяин, запас ракет, направляющие, радар ({@link SamBlockEntity}). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SamBlockEntity>> SAM = REGISTER.register("sam",
            () -> BlockEntityType.Builder.of(SamBlockEntity::new, ModBlocks.SAM.get()).build(null));

    private ModBlockEntities() {}
}
