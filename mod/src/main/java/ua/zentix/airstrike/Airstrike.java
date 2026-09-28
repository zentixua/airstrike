package ua.zentix.airstrike;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import ua.zentix.airstrike.command.AirstrikeCommand;
import ua.zentix.airstrike.legacy.LegacyMigration;
import ua.zentix.airstrike.net.AirstrikeNetwork;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModBlocks;
import ua.zentix.airstrike.registry.ModCreativeTabs;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.registry.ModEffects;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.strike.StrikeWorld;

/**
 * Airstrike: кинематографичные удары — дрон-камикадзе, крылатая ракета, B-2 с бетонобойной бомбой, залпы, МБР с ядерной БЧ.
 * Здесь только регистрации; игровая логика — в пакетах strike/entity/warhead, клиент — в client.
 */
@Mod(Airstrike.MOD_ID)
public final class Airstrike {
    public static final String MOD_ID = "airstrike";
    public static final Logger LOG = LogUtils.getLogger();

    public Airstrike(IEventBus modBus, ModContainer container) {
        ModBlocks.REGISTER.register(modBus);
        ModEntities.REGISTER.register(modBus);
        ModItems.REGISTER.register(modBus);
        ModSounds.REGISTER.register(modBus);
        ModDataComponents.REGISTER.register(modBus);
        ModCreativeTabs.REGISTER.register(modBus);
        ModEffects.REGISTER.register(modBus);
        ModAttachments.REGISTER.register(modBus);

        container.registerConfig(ModConfig.Type.SERVER, AirstrikeConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, AirstrikeConfig.CLIENT_SPEC);

        modBus.addListener(AirstrikeNetwork::register);
        modBus.addListener(ChunkTickets::register);

        NeoForge.EVENT_BUS.addListener(AirstrikeCommand::register);
        NeoForge.EVENT_BUS.addListener(StrikeWorld::onLevelTick);
        NeoForge.EVENT_BUS.addListener(StrikeWorld::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(LegacyMigration::onServerStarted);
        NeoForge.EVENT_BUS.addListener(LegacyMigration::onEntityJoin);

        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onLevelTick);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onLogin);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onChangeDimension);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onRespawn);
        NeoForge.EVENT_BUS.addListener(RadiationTicker::onHeal);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
