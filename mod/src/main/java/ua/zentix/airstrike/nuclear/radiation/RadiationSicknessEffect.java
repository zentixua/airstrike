package ua.zentix.airstrike.nuclear.radiation;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.common.EffectCure;

import java.util.Set;

/**
 * Лучевая болезнь. Сам эффект — только метка стадии для интерфейса (уровень = стадия); что с игроком происходит,
 * решает {@link RadiationTicker} по дозе и времени. Молоко и другие средства её не лечат.
 */
public class RadiationSicknessEffect extends MobEffect {
    public RadiationSicknessEffect() {
        super(MobEffectCategory.HARMFUL, 0x6B8E23);
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // лекарства нет
    }
}
