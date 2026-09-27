package ua.zentix.airstrike.registry;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.strike.Loadout;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents REGISTER = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Airstrike.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Loadout>> LOADOUT = REGISTER.registerComponentType("loadout",
            b -> b.persistent(Loadout.CODEC).networkSynchronized(Loadout.STREAM_CODEC));

    private ModDataComponents() {}
}
