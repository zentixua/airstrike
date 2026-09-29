package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> REGISTER = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Airstrike.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = REGISTER.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.airstrike"))
            .icon(() -> ModItems.DESIGNATOR.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(ModItems.DESIGNATOR.get());
                output.accept(ModItems.GEIGER_COUNTER.get());
                output.accept(ModItems.TRINITITE.get());
                output.accept(ModItems.SUBSTATION.get());
            })
            .build());

    private ModCreativeTabs() {}
}
