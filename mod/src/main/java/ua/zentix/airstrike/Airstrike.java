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
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.grid.ChunkSaves;
import ua.zentix.airstrike.legacy.LegacyMigration;
import ua.zentix.airstrike.net.AirstrikeNetwork;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
import ua.zentix.airstrike.nuclear.world.BlockResponse;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModBlocks;
import ua.zentix.airstrike.registry.ModCreativeTabs;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.registry.ModEffects;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.registry.ModParticles;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.FlightSounds;
import ua.zentix.airstrike.strike.FlightStatus;
import ua.zentix.airstrike.strike.PickHints;
import ua.zentix.airstrike.strike.StrikeWorld;

/**
 * Airstrike: кинематографичные удары — дрон-камикадзе, крылатая ракета, B-2 с бетонобойной бомбой, залпы, МБР с ядерной БЧ.
 * Здесь только регистрации; игровая логика — в пакетах strike/entity/warhead, клиент — в client.
 */
@Mod(Airstrike.MOD_ID)
public final class Airstrike {
    public static final String MOD_ID = "airstrike";
    /**
     * Имя клавиши камеры снаряда: подсказки сервера ссылаются на неё через {@code Component.keybind}, и клиент
     * показывает ту клавишу, что назначена у игрока, а не зашитую букву.
     */
    public static final String CAMERA_KEY = "key.airstrike.camera";
    public static final Logger LOG = LogUtils.getLogger();

    public Airstrike(IEventBus modBus, ModContainer container) {
        ModBlocks.REGISTER.register(modBus);
        ModEntities.REGISTER.register(modBus);
        ModItems.REGISTER.register(modBus);
        ModSounds.REGISTER.register(modBus);
        ModParticles.REGISTER.register(modBus);
        ModDataComponents.REGISTER.register(modBus);
        ModCreativeTabs.REGISTER.register(modBus);
        ModEffects.REGISTER.register(modBus);
        ModAttachments.REGISTER.register(modBus);

        container.registerConfig(ModConfig.Type.SERVER, AirstrikeConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, AirstrikeConfig.CLIENT_SPEC);

        modBus.addListener(AirstrikeNetwork::register);

        NeoForge.EVENT_BUS.addListener(AirstrikeCommand::register);
        NeoForge.EVENT_BUS.addListener(StrikeWorld::onLevelTick);
        NeoForge.EVENT_BUS.addListener(StrikeWorld::onServerStopping);
        NeoForge.EVENT_BUS.addListener(FlightStatus::onServerTick);
        NeoForge.EVENT_BUS.addListener(FlightSounds::onServerTick);
        NeoForge.EVENT_BUS.addListener(PickHints::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(LegacyMigration::onServerStarted);
        NeoForge.EVENT_BUS.addListener(LegacyMigration::onEntityJoin);

        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onServerTick);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onLogin);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onChangeDimension);
        NeoForge.EVENT_BUS.addListener(NuclearStrikes::onRespawn);
        NeoForge.EVENT_BUS.addListener(RadiationTicker::onHeal);
        NeoForge.EVENT_BUS.addListener(BlockResponse::onTagsUpdated);

        NeoForge.EVENT_BUS.addListener(Blackouts::onServerTick);
        NeoForge.EVENT_BUS.addListener(Blackouts::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(Blackouts::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(Blackouts::onExplosion);
        NeoForge.EVENT_BUS.addListener(Blackouts::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(ChunkSaves::onSave);
        NeoForge.EVENT_BUS.addListener(ChunkSaves::onLoad);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
