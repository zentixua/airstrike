package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainTilesTest {
    /** Плитка не мельче пикселя экрана: 1 пиксель на блок и крупнее — подробная, дальше — вдвое крупнее на каждую октаву. */
    @Test
    void levelFollowsScale() {
        assertEquals(0, TerrainTiles.levelFor(8));
        assertEquals(0, TerrainTiles.levelFor(1));
        assertEquals(0, TerrainTiles.levelFor(0.6));
        assertEquals(1, TerrainTiles.levelFor(0.5));
        assertEquals(3, TerrainTiles.levelFor(1.0 / 10));
        assertEquals(TerrainTiles.MAX_LEVEL, TerrainTiles.levelFor(1.0 / 1000), "крупнее самых крупных плиток не бывает");
    }

    /** Тень как у ванильной карты: склон вверх к югу светлее, вниз темнее, ровное — обычное; вода темнеет с глубиной. */
    @Test
    void slopesAndWaterShade() {
        var flat = new TerrainSource.Column(64, MapColor.GRASS, 0);
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(flat, 64, 1, 0));
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(flat, 64, 1, 1));
        assertEquals(MapColor.Brightness.HIGH, TerrainTiles.shade(flat, 62, 1, 0), "выше северной соседки");
        assertEquals(MapColor.Brightness.LOW, TerrainTiles.shade(flat, 66, 1, 0), "ниже северной соседки");
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(flat, 63, 16, 0), "на крупной клетке блок перепада — ровное");
        assertEquals(MapColor.Brightness.HIGH, TerrainTiles.shade(new TerrainSource.Column(64, MapColor.WATER, 1), 64, 1, 0), "мелко");
        assertEquals(MapColor.Brightness.LOW, TerrainTiles.shade(new TerrainSource.Column(64, MapColor.WATER, 12), 64, 1, 0), "глубоко");
    }

    /** Фоновая плитка возвращается в слой при любом исходе, и при Error: иначе слой навсегда ждал бы её задачу. */
    @Test
    void backgroundTileReturnsEvenOnError() {
        TerrainTiles.Layer layer = new TerrainTiles.Layer("test", new TerrainSource() {
            @Override
            public boolean offThread() {
                return true;
            }

            @Nullable
            @Override
            public Reader open(ClientLevel level) {
                return null;
            }
        });
        boolean[] closed = {false};
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Nullable
            @Override
            public TerrainSource.Column column(int x, int z) {
                throw new StackOverflowError();
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        TerrainTiles.Key key = new TerrainTiles.Key(2, 3, -1);
        assertThrows(StackOverflowError.class, () -> TerrainTiles.buildOffThread(layer, key, 7, reader), "Error не глотается");
        TerrainTiles.Built b = layer.done.poll();
        assertEquals(new TerrainTiles.Key(2, 3, -1), b.key());
        assertEquals(7, b.generation());
        assertEquals(0, b.pixels().length, "без пикселей — спросят снова");
        assertTrue(closed[0], "читатель закрыт");
    }
}
