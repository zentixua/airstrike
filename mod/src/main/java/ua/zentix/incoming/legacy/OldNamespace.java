package ua.zentix.incoming.legacy;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Миры версий до 3.0.0, когда мод назывался Airstrike (id {@code airstrike}). Блоки, предметы, сущности,
 * блок-сущности, эффекты, attachments и компоненты в мире записаны старыми именами: реестры находят их по
 * псевдонимам {@code airstrike:*} → {@code incoming:*} ({@link DeferredRegister#addAlias}, механизм NeoForge для
 * переименованных записей). Сохранённые данные мира ({@code data/airstrike_*.dat}: ядерные подрывы, энергосеть, залпы,
 * полёты вне мира) переезжают под новое имя до загрузки миров: номера подрывов, по которым чанки помнят применённые
 * повреждения, продолжаются, а не начинаются заново. Настройки ({@code serverconfig/airstrike-server.toml}) NeoForge
 * читает раньше любого события мода — их переименовывают руками (заметки к выпуску 3.0.0).
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
        Path root = event.getServer().getWorldPath(LevelResource.ROOT);
        try {
            for (Path data : dataFolders(root)) adoptSavedData(data);
        } catch (IOException e) {
            Incoming.LOG.error("Could not rename the world data of the Airstrike mod in {}", root, e);
        }
    }

    /** {@code data} верхнего мира и {@code dimensions/<ns>/<path>/data} остальных измерений. */
    static List<Path> dataFolders(Path root) throws IOException {
        List<Path> folders = new ArrayList<>();
        folders.add(root.resolve("data"));
        Path dimensions = root.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            try (Stream<Path> found = Files.find(dimensions, 3, (p, a) -> a.isDirectory() && p.getFileName().toString().equals("data")
                    && dimensions.relativize(p).getNameCount() == 3)) {
                found.forEach(folders::add);
            }
        }
        return folders;
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
