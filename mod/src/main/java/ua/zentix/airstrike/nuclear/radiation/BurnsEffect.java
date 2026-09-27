package ua.zentix.airstrike.nuclear.radiation;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.EffectCure;
import ua.zentix.airstrike.registry.ModDamageTypes;

import java.util.Set;

/** Ожоги от светового импульса: пока действует — нет естественной регенерации, а бег причиняет боль. */
public class BurnsEffect extends MobEffect {
    public BurnsEffect() {
        super(MobEffectCategory.HARMFUL, 0xB5461E);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % 20 == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity e, int amplifier) {
        if (e.isSprinting()) e.hurt(ModDamageTypes.source(e.level(), ModDamageTypes.NUCLEAR_THERMAL, null, null), 1 + amplifier);
        return true;
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // ожоги заживают только со временем
    }
}
