package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.radiation.BurnsEffect;
import ua.zentix.airstrike.nuclear.radiation.RadiationSicknessEffect;

public final class ModEffects {
    public static final DeferredRegister<MobEffect> REGISTER = DeferredRegister.create(Registries.MOB_EFFECT, Airstrike.MOD_ID);

    public static final DeferredHolder<MobEffect, RadiationSicknessEffect> RADIATION_SICKNESS = REGISTER.register("radiation_sickness", RadiationSicknessEffect::new);
    public static final DeferredHolder<MobEffect, BurnsEffect> BURNS = REGISTER.register("burns", BurnsEffect::new);

    private ModEffects() {}
}
