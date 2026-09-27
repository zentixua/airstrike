package ua.zentix.airstrike.legacy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Переход с датапака на мод (один раз на мир, при запуске сервера):
 * <ul>
 *   <li>датапаки {@code airstrike} и {@code shahed} выключаются (файлы не трогаем — можно вернуть {@code /datapack enable});</li>
 *   <li>настройки из {@code storage airstrike:cfg} переезжают в конфиг мира (serverconfig/airstrike-server.toml);</li>
 *   <li>служебные objectives и storage датапака удаляются;</li>
 *   <li>его сущности (маркеры, block/item_display, стойки) с тегами airstrike/shahed не загружаются.</li>
 * </ul>
 */
public final class LegacyMigration {
    private static final Set<String> LEGACY_PACKS = Set.of("file/airstrike", "file/shahed", "file/airstrike.zip", "file/airstrike_datapack.zip");
    private static final Set<String> LEGACY_TAGS = Set.of("airstrike", "shahed");

    private LegacyMigration() {}

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            importConfig(server);
            removeObjectives(server.getScoreboard());
            disableDatapacks(server);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Переход со старого датапака не удался (мод работает, но старый датапак мог остаться включённым)", e);
        }
    }

    private static void disableDatapacks(MinecraftServer server) {
        List<String> selected = new ArrayList<>(server.getPackRepository().getSelectedIds());
        List<String> legacy = selected.stream().filter(LEGACY_PACKS::contains).toList();
        if (legacy.isEmpty()) return;
        selected.removeAll(legacy);
        Airstrike.LOG.info("Выключаю старые датапаки {}: их заменил мод Airstrike (файлы оставлены, вернуть — /datapack enable)", legacy);
        server.reloadResources(selected).exceptionally(e -> {
            Airstrike.LOG.error("Не удалось выключить старые датапаки {}", legacy, e);
            return null;
        });
    }

    private static void importConfig(MinecraftServer server) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("airstrike", "cfg");
        CompoundTag cfg = server.getCommandStorage().get(id);
        if (cfg.isEmpty()) return;
        AirstrikeConfig.Server s = AirstrikeConfig.SERVER;
        setInt(cfg, "power", s.dronePower, 1, 60);
        setInt(cfg, "missile_power", s.missilePower, 1, 60);
        setInt(cfg, "bunker_power", s.bunkerPower, 1, 60);
        setInt(cfg, "bunker_energy", s.bunkerEnergy, 50, 10000);
        setBool(cfg, "shatter", s.shatterGlass);
        setBool(cfg, "siren", s.siren);
        setBool(cfg, "debris_stay", s.debrisStay);
        setBool(cfg, "collapse", s.collapse);
        AirstrikeConfig.SERVER_SPEC.save();
        server.getCommandStorage().set(id, new CompoundTag());
        server.getCommandStorage().set(ResourceLocation.fromNamespaceAndPath("airstrike", "tmp"), new CompoundTag());
        server.getCommandStorage().set(ResourceLocation.fromNamespaceAndPath("shahed", "cfg"), new CompoundTag());
        Airstrike.LOG.info("Настройки датапака перенесены в конфиг мода: {}", cfg);
    }

    private static void setInt(CompoundTag cfg, String key, ModConfigSpec.IntValue value, int min, int max) {
        if (cfg.contains(key)) value.set(Math.max(min, Math.min(max, cfg.getInt(key))));
    }

    private static void setBool(CompoundTag cfg, String key, ModConfigSpec.BooleanValue value) {
        if (cfg.contains(key)) value.set(cfg.getByte(key) != 0);
    }

    private static void removeObjectives(Scoreboard scoreboard) {
        List<Objective> legacy = scoreboard.getObjectives().stream()
                .filter(o -> o.getName().equals("airstrike") || o.getName().startsWith("airstrike_")
                        || o.getName().equals("shahed") || o.getName().startsWith("shahed_"))
                .toList();
        for (Objective o : legacy) scoreboard.removeObjective(o);
        if (!legacy.isEmpty()) Airstrike.LOG.info("Удалены objectives старого датапака: {}", legacy.size());
    }

    /** Сущности датапака (маркеры, детали моделей, обломки) больше никто не обслуживает — не загружаем их. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        EntityType<?> t = e.getType();
        if (t != EntityType.MARKER && t != EntityType.BLOCK_DISPLAY && t != EntityType.ITEM_DISPLAY
                && t != EntityType.ARMOR_STAND && t != EntityType.FALLING_BLOCK && t != EntityType.CREEPER) return;
        for (String tag : e.getTags()) {
            if (LEGACY_TAGS.contains(tag)) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
