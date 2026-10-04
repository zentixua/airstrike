package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.defense.SamBlockEntity;
import ua.zentix.airstrike.launcher.FixedLauncherBlockEntity;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTER = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Airstrike.MOD_ID);

    /** ЗРК: хозяин, запас ракет, направляющие, радар ({@link SamBlockEntity}). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SamBlockEntity>> SAM = REGISTER.register("sam",
            () -> BlockEntityType.Builder.of(SamBlockEntity::new, ModBlocks.SAM.get()).build(null));

    /** Стационарная пусковая: хозяин, запас боеприпасов, задача, пакет на круге ({@link FixedLauncherBlockEntity}). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FixedLauncherBlockEntity>> FIXED_LAUNCHER = REGISTER.register("fixed_launcher",
            () -> BlockEntityType.Builder.of(FixedLauncherBlockEntity::new, ModBlocks.FIXED_LAUNCHER.get()).build(null));

    private ModBlockEntities() {}
}
