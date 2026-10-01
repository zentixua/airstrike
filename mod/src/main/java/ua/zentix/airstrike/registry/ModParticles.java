package ua.zentix.airstrike.registry;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;

/**
 * Частицы эффектов. Типы нужны только команде {@code /particle airstrike:smoke}: она даёт частицу по умолчанию. Сами
 * частицы с настройками (размер, цвет по времени жизни, накал, всплытие) клиент рождает в своём пуле и рисует своим
 * слоем — {@code client/fx/particle/Fx}, {@code client/fx/layer/FxLayer}; текстуры — {@code textures/fx/particle}
 * ({@code tools/gen_particles.py}) на своём листе.
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
    /**
     * Ничего: частицы серверного ванильного взрыва ({@code Level.explode} шлёт их клиентам пакетом взрыва). Картинку
     * взрыва рисует клиент сам ({@code client/fx/BlastEffects}), как и звук ({@code ModSounds.SILENT}); ванильные клубы
     * TNT поверх неё читались белыми кольцами с тёмной серединой. Поставщик на клиенте частиц не создаёт.
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> NONE = REGISTER.register("none", () -> new SimpleParticleType(false));

    private ModParticles() {}

    private static DeferredHolder<ParticleType<?>, SimpleParticleType> register(String name) {
        return REGISTER.register(name, () -> new SimpleParticleType(true));
    }
}
