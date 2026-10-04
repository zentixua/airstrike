package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.strike.WeaponType;

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
                // боеприпасы — в порядке пульта (WeaponType.menu), ядерная БЧ на носитель — за МБР
                for (WeaponType w : WeaponType.menu()) output.accept(w.spec().munition().item().get());
                output.accept(ModItems.NUCLEAR_WARHEAD.get());
                output.accept(ModItems.SAM.get());
                output.accept(ModItems.INTERCEPTOR.get());
            })
            .build());

    private ModCreativeTabs() {}
}
