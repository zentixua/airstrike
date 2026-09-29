package ua.zentix.airstrike.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.EffectCure;

import java.util.Set;

/**
 * Лучевая болезнь; что происходит, решает {@link RadiationTicker} по дозе и времени. У игрока эффект — метка стадии
 * для интерфейса (уровень = стадия), его обновляет тикер игроков. У моба эффект сам ведёт течение: раз в секунду
 * на тике моба ({@link RadiationTicker#tickMob}) — так болезнь тикает только у облучённых, без перебора всех мобов.
 * Молоко и другие средства её не лечат.
 */
public class RadiationSicknessEffect extends MobEffect {
    public RadiationSicknessEffect() {
        super(MobEffectCategory.HARMFUL, 0x6B8E23);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        // раз в секунду; у бесконечного эффекта (у моба) сюда приходит счётчик тиков самого моба
        return duration % 20 == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity instanceof Player || !(entity.level() instanceof ServerLevel level)) return true;
        return RadiationTicker.tickMob(level, entity);
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // лекарства нет
    }
}
