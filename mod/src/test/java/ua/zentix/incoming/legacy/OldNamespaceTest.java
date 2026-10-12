package ua.zentix.incoming.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OldNamespaceTest {
    @Test
    void savedDataOfAllDimensionsGetsTheNewName(@TempDir Path world) throws IOException {
        Path overworld = Files.createDirectories(world.resolve("data"));
        Path nether = Files.createDirectories(world.resolve("dimensions/minecraft/the_nether/data"));
        Files.writeString(overworld.resolve("airstrike_nuclear.dat"), "old");
        Files.writeString(overworld.resolve("airstrike_grid.dat"), "grid");
        Files.writeString(overworld.resolve("raids.dat"), "vanilla");
        Files.writeString(nether.resolve("airstrike_salvos.dat"), "salvos");

        List<Path> folders = OldNamespace.dataFolders(world);
        assertEquals(List.of(overworld, nether), folders);
        for (Path folder : folders) OldNamespace.adoptSavedData(folder);

        assertEquals("old", Files.readString(overworld.resolve("incoming_nuclear.dat")));
        assertEquals("grid", Files.readString(overworld.resolve("incoming_grid.dat")));
        assertEquals("salvos", Files.readString(nether.resolve("incoming_salvos.dat")));
        assertTrue(Files.exists(overworld.resolve("raids.dat")));
        assertFalse(Files.exists(overworld.resolve("airstrike_nuclear.dat")));
    }

    @Test
    void newerDataIsNotOverwritten(@TempDir Path world) throws IOException {
        Path data = Files.createDirectories(world.resolve("data"));
        Files.writeString(data.resolve("airstrike_grid.dat"), "old");
        Files.writeString(data.resolve("incoming_grid.dat"), "new");

        OldNamespace.adoptSavedData(data);

        assertEquals("new", Files.readString(data.resolve("incoming_grid.dat")));
        assertEquals("old", Files.readString(data.resolve("airstrike_grid.dat")));
    }

    @Test
    void worldWithoutDataFolderIsFine(@TempDir Path world) throws IOException {
        for (Path folder : OldNamespace.dataFolders(world)) OldNamespace.adoptSavedData(folder);
        assertFalse(Files.exists(world.resolve("data")));
    }
}
