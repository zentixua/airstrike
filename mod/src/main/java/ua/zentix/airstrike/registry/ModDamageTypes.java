package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

/** Типы урона (data/airstrike/damage_type): свои сообщения о смерти, входят в #minecraft:is_explosion. */
public final class ModDamageTypes {
    public static final ResourceKey<DamageType> STRIKE = key("strike");
    public static final ResourceKey<DamageType> SHOCKWAVE = key("shockwave");
    public static final ResourceKey<DamageType> KINETIC = key("kinetic");
    public static final ResourceKey<DamageType> NUCLEAR_BLAST = key("nuclear_blast");
    public static final ResourceKey<DamageType> NUCLEAR_THERMAL = key("nuclear_thermal");
    public static final ResourceKey<DamageType> RADIATION = key("radiation");
    public static final ResourceKey<DamageType> DEBRIS = key("debris");

    private ModDamageTypes() {}

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, Airstrike.id(name));
    }

    public static DamageSource source(Level level, ResourceKey<DamageType> type, @Nullable Entity direct, @Nullable Entity owner) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), direct, owner);
    }
}
