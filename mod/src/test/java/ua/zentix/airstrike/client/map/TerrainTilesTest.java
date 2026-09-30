package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(64, 0, 64, 1, 0));
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(64, 0, 64, 1, 1));
        assertEquals(MapColor.Brightness.HIGH, TerrainTiles.shade(64, 0, 62, 1, 0), "выше северной соседки");
        assertEquals(MapColor.Brightness.LOW, TerrainTiles.shade(64, 0, 66, 1, 0), "ниже северной соседки");
        assertEquals(MapColor.Brightness.NORMAL, TerrainTiles.shade(64, 0, 63, 16, 0), "на крупной клетке блок перепада — ровное");
        assertEquals(MapColor.Brightness.HIGH, TerrainTiles.shade(64, 1, 64, 1, 0), "мелко");
        assertEquals(MapColor.Brightness.LOW, TerrainTiles.shade(64, 12, 64, 1, 0), "глубоко");
    }

    /**
     * Первый ряд плитки: северная соседка — за краем, её высота оценивается по склону к южной, поэтому склон
     * тенится так же, как в остальных рядах (раньше ради неё читался ещё участок DH на каждую плитку).
     */
    @Test
    void firstRowShadedBySlopeInside() {
        TerrainTiles.Columns c = new TerrainTiles.Columns();
        // склон вверх к югу на 2 блока за клетку, по всей плитке
        for (int z = 0; z < TerrainTiles.SIZE; z++) {
            for (int x = 0; x < TerrainTiles.SIZE; x++) c.set(z * TerrainTiles.SIZE + x, new TerrainSource.Column(60 + 2 * z, MapColor.GRASS, 0));
        }
        int[] pixels = TerrainTiles.paint(c, 0);
        int high = MapColor.GRASS.calculateRGBColor(MapColor.Brightness.HIGH);
        assertEquals(high, pixels[0], "первый ряд — как второй");
        assertEquals(high, pixels[TerrainTiles.SIZE]);
        assertEquals(0, TerrainTiles.paint(new TerrainTiles.Columns(), 0)[0], "без данных — прозрачно");
    }

    /**
     * Крупная плитка из четырёх мелких: пиксель — клетка ребёнка у середины своей клетки (как у плитки из источника
     * с шагом вдвое больше), дети — северо-запад, северо-восток, юго-запад, юго-восток; дыры детей остаются дырами.
     */
    @Test
    void composeSamplesChildrenAtCellMiddles() {
        int n = TerrainTiles.SIZE;
        TerrainTiles.Columns[] kids = new TerrainTiles.Columns[4];
        for (int k = 0; k < 4; k++) {
            kids[k] = new TerrainTiles.Columns();
            for (int i = 0; i < n * n; i++) {
                if (k == 3) continue; // юго-восток пуст
                // высота кодирует ребёнка (8000·k) и номер клетки (ряд·64 + столбец)
                kids[k].set(i, new TerrainSource.Column(k * 8000 + i % 8000, MapColor.STONE, 0));
            }
        }
        TerrainTiles.Columns c = TerrainTiles.compose(kids);
        // пиксель (0, 0) — клетка (1, 1) северо-западного ребёнка
        assertEquals(n + 1, c.height[0]);
        // пиксель (40, 0) — клетка (81 − 64 = 17, 1) северо-восточного
        assertEquals(8000 + n + 17, c.height[40]);
        // пиксель (0, 40) — клетка (1, 17) юго-западного
        assertEquals(16000 + (17 * n + 1) % 8000, c.height[40 * n]);
        assertFalse(c.has(40 * n + 40), "у пустого ребёнка — дыра");
        assertEquals(3 * n * n / 4, c.filled);
    }

    /** Сначала то, чего нет или что устарело по событию; пустое переспрашивается чаще неполного; полное — как велит источник. */
    @Test
    void urgencyOrder() {
        long s = 1_000_000_000L, now = 1000 * s;
        TerrainTiles.Columns full = new TerrainTiles.Columns();
        for (int i = 0; i < TerrainTiles.SIZE * TerrainTiles.SIZE; i++) full.set(i, new TerrainSource.Column(64, MapColor.GRASS, 0));
        TerrainTiles.Columns partial = new TerrainTiles.Columns();
        partial.set(0, new TerrainSource.Column(64, MapColor.GRASS, 0));
        long never = Long.MAX_VALUE;
        assertEquals(-1, TerrainTiles.urgency(true, 0, true, null, never, now), "строится");
        assertEquals(0, TerrainTiles.urgency(false, 0, false, null, never, now), "ещё не строилась");
        assertEquals(0, TerrainTiles.urgency(false, now - s, true, full, never, now), "рельеф изменился");
        assertEquals(1, TerrainTiles.urgency(false, now - 6 * s, false, new TerrainTiles.Columns(), never, now), "пустая через 5 с");
        assertEquals(-1, TerrainTiles.urgency(false, now - 6 * s, false, partial, never, now), "неполная — не через 5 с");
        assertEquals(1, TerrainTiles.urgency(false, now - 31 * s, false, partial, never, now), "неполная через 30 с");
        assertEquals(-1, TerrainTiles.urgency(false, now - 999 * s, false, full, never, now), "полная — только по событию");
        assertEquals(1, TerrainTiles.urgency(false, now - 31 * s, false, full, 30 * s, now), "полная — по часам источника");
    }

    /** Заранее — подробные плитки в квадрате вокруг игрока, ближние первыми (его плитка — первая). */
    @Test
    void prefetchAroundPlayerNearestFirst() {
        List<TerrainTiles.Key> keys = TerrainTiles.around(-100, 700);
        int r = TerrainTiles.PREFETCH_RADIUS;
        assertEquals((2 * r + 1) * (2 * r + 1), keys.size());
        assertEquals(new TerrainTiles.Key(0, -2, 10), keys.get(0));
        assertTrue(keys.stream().allMatch(k -> k.level() == 0 && Math.abs(k.tx() + 2) <= r && Math.abs(k.tz() - 10) <= r));
    }

    /** Фоновая плитка возвращается в слой при любом исходе, и при Error: иначе слой навсегда ждал бы её задачу. */
    @Test
    void backgroundTileReturnsEvenOnError() {
        TerrainTiles.Layer layer = new TerrainTiles.Layer("test", new TerrainSource() {
            @Override
            public boolean offThread() {
                return true;
            }

            @Override
            public long refreshNanos() {
                return Long.MAX_VALUE;
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
        assertNull(b.columns());
        assertTrue(closed[0], "читатель закрыт");
    }
}
