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
        Path nether = Files.createDirectories(world.resolve("DIM-1/data"));
        Path end = Files.createDirectories(world.resolve("DIM1/data"));
        Path modded = Files.createDirectories(world.resolve("dimensions/somemod/sky/data"));
        Files.writeString(overworld.resolve("airstrike_nuclear.dat"), "old");
        Files.writeString(overworld.resolve("airstrike_grid.dat"), "grid");
        Files.writeString(overworld.resolve("raids.dat"), "vanilla");
        Files.writeString(nether.resolve("airstrike_salvos.dat"), "salvos");
        Files.writeString(end.resolve("airstrike_nuclear.dat"), "end");
        Files.writeString(modded.resolve("airstrike_grid.dat"), "sky");

        // где игра держит данные каждого измерения, решает DimensionType.getStorageFolder (GameTest oldNamespaceResolves)
        for (Path folder : List.of(overworld, nether, end, modded)) OldNamespace.adoptSavedData(folder);

        assertEquals("old", Files.readString(overworld.resolve("incoming_nuclear.dat")));
        assertEquals("grid", Files.readString(overworld.resolve("incoming_grid.dat")));
        assertEquals("salvos", Files.readString(nether.resolve("incoming_salvos.dat")));
        assertEquals("end", Files.readString(end.resolve("incoming_nuclear.dat")));
        assertEquals("sky", Files.readString(modded.resolve("incoming_grid.dat")));
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
        OldNamespace.adoptSavedData(world.resolve("data"));
        assertFalse(Files.exists(world.resolve("data")));
    }
}
