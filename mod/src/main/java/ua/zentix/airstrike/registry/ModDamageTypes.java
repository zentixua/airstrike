package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.util.Map;

/**
 * Типы урона (data/airstrike/damage_type): свои сообщения о смерти, входят в ванильные теги (#minecraft:is_explosion …).
 * <p>
 * Сообщение о смерти ваниль собирает по сущностям источника ({@code DamageSource.getLocalizedDeathMessage}):
 * без сущностей — основной ключ без виновника или «.player» с тем, кто последним бил жертву («…, сражаясь с»);
 * с сущностью — основной ключ с виновником вторым аргументом (или «.item», если в руке у него переименованный
 * предмет). Удары снарядами всегда идут с сущностью (снаряд, обломок), их основной ключ её и называет. Ядерный урон
 * бывает и без сущностей (ожоги, лучевая болезнь, подрыв с консоли), и с запустившим — одним основным ключом его
 * не назвать: как ванильные {@code explosion} и {@code player_explosion}, у каждого такого типа есть пара
 * «с виновником» ({@code player_…}), и {@link #source} выбирает её сам.
 */
public final class ModDamageTypes {
    public static final ResourceKey<DamageType> STRIKE = key("strike");
    public static final ResourceKey<DamageType> SHOCKWAVE = key("shockwave");
    public static final ResourceKey<DamageType> KINETIC = key("kinetic");
    public static final ResourceKey<DamageType> NUCLEAR_BLAST = key("nuclear_blast");
    public static final ResourceKey<DamageType> NUCLEAR_THERMAL = key("nuclear_thermal");
    public static final ResourceKey<DamageType> RADIATION = key("radiation");
    public static final ResourceKey<DamageType> DEBRIS = key("debris");
    public static final ResourceKey<DamageType> PLAYER_NUCLEAR_BLAST = key("player_nuclear_blast");
    public static final ResourceKey<DamageType> PLAYER_NUCLEAR_THERMAL = key("player_nuclear_thermal");
    public static final ResourceKey<DamageType> PLAYER_RADIATION = key("player_radiation");

    /** Тип без виновника → его пара с виновником (сообщение о смерти называет его). */
    private static final Map<ResourceKey<DamageType>, ResourceKey<DamageType>> WITH_OWNER = Map.of(
            NUCLEAR_BLAST, PLAYER_NUCLEAR_BLAST,
            NUCLEAR_THERMAL, PLAYER_NUCLEAR_THERMAL,
            RADIATION, PLAYER_RADIATION);

    private ModDamageTypes() {}

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, Airstrike.id(name));
    }

    /** @param type тип без виновника; есть виновник ({@code owner}) и у типа есть пара с виновником — берётся она */
    public static DamageSource source(Level level, ResourceKey<DamageType> type, @Nullable Entity direct, @Nullable Entity owner) {
        ResourceKey<DamageType> t = owner == null ? type : WITH_OWNER.getOrDefault(type, type);
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(t), direct, owner);
    }
}
