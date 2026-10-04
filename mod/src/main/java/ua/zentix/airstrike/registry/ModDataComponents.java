package ua.zentix.airstrike.registry;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.strike.Loadout;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents REGISTER = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Airstrike.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Loadout>> LOADOUT = REGISTER.registerComponentType("loadout",
            b -> b.persistent(Loadout.CODEC).networkSynchronized(Loadout.STREAM_CODEC));
    /**
     * Сколько снарядов осталось в начатом пакете (боеприпас, у которого в предмете их несколько, — пакет «Града»,
     * {@code strike.Munitions}); у целого пакета компонента нет, и целые складываются в стопку.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ROUNDS = REGISTER.registerComponentType("rounds",
            b -> b.persistent(Codec.intRange(1, Integer.MAX_VALUE)).networkSynchronized(ByteBufCodecs.VAR_INT));

    private ModDataComponents() {}
}
