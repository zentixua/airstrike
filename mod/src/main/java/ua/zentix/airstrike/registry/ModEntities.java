package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.DroneEntity;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> REGISTER = DeferredRegister.create(Registries.ENTITY_TYPE, Airstrike.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE =
            projectile("drone", EntityType.Builder.of(DroneEntity::new, MobCategory.MISC).sized(2.0f, 0.9f), 20);
    public static final DeferredHolder<EntityType<?>, EntityType<CruiseMissileEntity>> CRUISE_MISSILE =
            projectile("cruise_missile", EntityType.Builder.of(CruiseMissileEntity::new, MobCategory.MISC).sized(1.3f, 1.3f), 24);
    public static final DeferredHolder<EntityType<?>, EntityType<BomberEntity>> BOMBER =
            projectile("bomber", EntityType.Builder.of(BomberEntity::new, MobCategory.MISC).sized(6.0f, 1.5f), 32);
    public static final DeferredHolder<EntityType<?>, EntityType<BunkerBusterEntity>> BUNKER_BUSTER =
            projectile("bunker_buster", EntityType.Builder.of(BunkerBusterEntity::new, MobCategory.MISC).sized(1.0f, 1.0f), 24);

    /** Обломки: летят по баллистике, падают блоком или рассыпаются. Как у falling_block — позиция раз в несколько тиков. */
    public static final DeferredHolder<EntityType<?>, EntityType<DebrisEntity>> DEBRIS = REGISTER.register("debris",
            () -> EntityType.Builder.<DebrisEntity>of(DebrisEntity::new, MobCategory.MISC)
                    .sized(0.98f, 0.98f)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .fireImmune()
                    .noSummon()
                    .build(Airstrike.MOD_ID + ":debris"));

    private ModEntities() {}

    /**
     * Снаряды быстрые (до 12.5 блока за тик), поэтому позиция уходит клиентам каждый тик,
     * а дальность отслеживания больше ванильной: их должно быть видно и слышно издалека.
     */
    private static <T extends Entity> DeferredHolder<EntityType<?>, EntityType<T>> projectile(String name, EntityType.Builder<T> builder, int trackingChunks) {
        return REGISTER.register(name, () -> builder
                .clientTrackingRange(trackingChunks)
                .updateInterval(1)
                .fireImmune()
                .noSummon()
                .build(Airstrike.MOD_ID + ":" + name));
    }
}
