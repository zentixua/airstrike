package ua.zentix.incoming.legacy;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Миры с id {@code airstrike}. Блоки, предметы, сущности, блок-сущности, эффекты, attachments и компоненты в них
 * записаны именами {@code airstrike:*}: реестры находят их по псевдонимам {@code airstrike:*} → {@code incoming:*}
 * ({@link DeferredRegister#addAlias}, механизм NeoForge для переименованных записей). Сохранённые данные мира
 * ({@code data/airstrike_*.dat}: ядерные подрывы, энергосеть, залпы, полёты вне мира) переезжают под новое имя до
 * загрузки миров: номера подрывов, по которым чанки помнят применённые повреждения, продолжаются, а не начинаются
 * заново. Настройки ({@code serverconfig/airstrike-server.toml}) NeoForge читает раньше любого события мода — их
 * переименовывают руками (заметки к выпуску 3.0.0).
 */
public final class OldNamespace {
    public static final String ID = "airstrike";
    private static final String DATA_PREFIX = ID + "_";

    private OldNamespace() {}

    /** До {@code register(modBus)}: записи регистров уже созданы их статическими полями. */
    public static <T> void alias(DeferredRegister<T> register) {
        for (DeferredHolder<T, ? extends T> entry : register.getEntries()) {
            register.addAlias(ResourceLocation.fromNamespaceAndPath(ID, entry.getId().getPath()), entry.getId());
        }
    }

    /** {@link ServerAboutToStartEvent} идёт до загрузки миров: их данные читаются уже под новым именем. */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        try {
            for (Path data : dataFolders(server)) adoptSavedData(data);
        } catch (IOException e) {
            Incoming.LOG.error("Could not rename the world data of the Airstrike mod in {}", server.getWorldPath(LevelResource.ROOT), e);
        }
    }

    /**
     * Папки {@code data} измерений мира там, где их держит игра ({@link DimensionType#getStorageFolder}): верхний мир —
     * корень, Незер — {@code DIM-1}, Энд — {@code DIM1}, измерения модов — {@code dimensions/<ns>/<path>}.
     */
    public static List<Path> dataFolders(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        return server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).registryKeySet().stream()
                .map(stem -> DimensionType.getStorageFolder(Registries.levelStemToLevel(stem), root).resolve("data")).toList();
    }

    /** {@code airstrike_<имя>.dat} → {@code incoming_<имя>.dat}, если нового файла ещё нет. */
    static void adoptSavedData(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        List<Path> old;
        try (Stream<Path> files = Files.list(folder)) {
            old = files.filter(p -> p.getFileName().toString().startsWith(DATA_PREFIX) && p.getFileName().toString().endsWith(".dat")).toList();
        }
        for (Path file : old) {
            Path target = folder.resolve(Incoming.MOD_ID + "_" + file.getFileName().toString().substring(DATA_PREFIX.length()));
            if (Files.exists(target)) continue;
            Files.move(file, target);
            Incoming.LOG.info("World data of the Airstrike mod renamed: {} -> {}", file.getFileName(), target.getFileName());
        }
    }
}
