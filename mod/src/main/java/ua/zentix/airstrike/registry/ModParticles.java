package ua.zentix.airstrike.registry;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;

/**
 * Частицы эффектов. Типы нужны ради наборов текстур (assets/airstrike/particles/*.json → textures/particle, рисует
 * tools/gen_particles.py); сами частицы с настройками (размер, цвет по времени жизни, накал, всплытие) создаёт
 * клиент напрямую — {@code client/fx/particle/Fx}. Команда {@code /particle airstrike:smoke} даёт частицу по умолчанию.
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> REGISTER = DeferredRegister.create(Registries.PARTICLE_TYPE, Airstrike.MOD_ID);

    /** Клуб дыма, пыли, пара (4 клуба × 4 стадии рассеивания). */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE = register("smoke");
    /** Клуб пламени. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FIRE = register("fire");
    /** Искра, раскалённый осколок (тянется вдоль скорости). */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARK = register("spark");
    /** Вспышка. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FLASH = register("flash");
    /** Кольцо ударной волны по земле. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RING = register("ring");

    private ModParticles() {}

    private static DeferredHolder<ParticleType<?>, SimpleParticleType> register(String name) {
        return REGISTER.register(name, () -> new SimpleParticleType(true));
    }
}
