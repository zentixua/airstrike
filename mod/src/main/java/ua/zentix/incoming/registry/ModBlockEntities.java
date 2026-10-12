package ua.zentix.incoming.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTER = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Incoming.MOD_ID);

    /** ЗРК: хозяин, запас ракет, направляющие, радар ({@link SamBlockEntity}). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SamBlockEntity>> SAM = REGISTER.register("sam",
            () -> BlockEntityType.Builder.of(SamBlockEntity::new, ModBlocks.SAM.get()).build(null));

    /** Стационарная пусковая: хозяин, запас боеприпасов, задача, пакет на круге ({@link FixedLauncherBlockEntity}). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FixedLauncherBlockEntity>> FIXED_LAUNCHER = REGISTER.register("fixed_launcher",
            () -> BlockEntityType.Builder.of(FixedLauncherBlockEntity::new, ModBlocks.FIXED_LAUNCHER.get()).build(null));

    private ModBlockEntities() {}
}
