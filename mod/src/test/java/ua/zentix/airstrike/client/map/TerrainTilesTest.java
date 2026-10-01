package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
     * Первый ряд плитки без плитки к северу в памяти: её высота оценивается по склону к южной, поэтому склон
     * тенится так же, как в остальных рядах (раньше ради неё читался ещё участок DH на каждую плитку).
     */
    @Test
    void firstRowShadedBySlopeInside() {
        TerrainTiles.Columns c = filled((x, z) -> 60 + 2 * z); // склон вверх к югу на 2 блока за клетку
        int[] pixels = TerrainTiles.paint(c, 0, null);
        int high = MapColor.GRASS.calculateRGBColor(MapColor.Brightness.HIGH);
        assertEquals(high, pixels[0], "первый ряд — как второй");
        assertEquals(high, pixels[TerrainTiles.SIZE]);
        assertEquals(0, TerrainTiles.paint(new TerrainTiles.Columns(), 0, null)[0], "без данных — прозрачно");
    }

    /** Плитка к северу в памяти: первый ряд тенится по её последнему ряду, а не по оценке склона. */
    @Test
    void firstRowShadedByNorthTile() {
        int n = TerrainTiles.SIZE;
        TerrainTiles.Columns flat = filled((x, z) -> 64);
        // к северу — обрыв: последний ряд северной плитки на 10 блоков выше
        TerrainTiles.Columns north = filled((x, z) -> z == n - 1 ? 74 : 64);
        assertEquals(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.LOW), TerrainTiles.paint(flat, 0, north)[0], "ниже северного ряда соседки");
        assertEquals(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.NORMAL), TerrainTiles.paint(flat, 0, null)[0], "без соседки — ровно");
        // у соседки дыра в последнем ряду — оценка по склону
        TerrainTiles.Columns holed = filled((x, z) -> z == n - 1 ? Integer.MIN_VALUE : 64);
        assertEquals(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.NORMAL), TerrainTiles.paint(flat, 0, holed)[0]);
    }

    /** Отметка «рельеф изменился» по чанку — на плитках над ним на всех уровнях, и за минусом координат. */
    @Test
    void changedChunkMarksTilesAtNegativeCoordinates() {
        TerrainTiles.Layer layer = layer();
        TerrainTiles.Key[] over = {new TerrainTiles.Key(0, -1, -2), new TerrainTiles.Key(1, -1, -1), new TerrainTiles.Key(4, -1, -1)};
        TerrainTiles.Key[] beside = {new TerrainTiles.Key(0, 0, -2), new TerrainTiles.Key(0, -1, -1), new TerrainTiles.Key(0, -2, -2)};
        for (TerrainTiles.Key k : over) layer.tiles.put(k, new TerrainTiles.Tile(k));
        for (TerrainTiles.Key k : beside) layer.tiles.put(k, new TerrainTiles.Tile(k));
        // чанк (−1, −5): блоки x −16…−1, z −80…−65 — плитка (−1, −2) уровня 0
        TerrainTiles.changed(layer, -1, -5);
        for (TerrainTiles.Key k : over) assertTrue(layer.tiles.get(k).stale, "не отмечена " + k);
        for (TerrainTiles.Key k : beside) assertFalse(layer.tiles.get(k).stale, "отмечена соседняя " + k);
    }

    /**
     * Крупная плитка из четырёх мелких: пиксель — клетка ребёнка у середины своей клетки (у плитки уровня 1 это та же
     * колонка, что прочла бы плитка из источника; у крупнее — сдвинутая на четверть клетки к юго-востоку, на глаз
     * не видно), дети — северо-запад, северо-восток, юго-запад, юго-восток; дыры детей остаются дырами.
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

    /**
     * Перестроенная мелкая плитка ложится в свою четверть крупной, не трогая остальные три, и счёт колонок с данными
     * пересчитывается (дыра на месте данных — минус, данные на месте дыры — плюс).
     */
    @Test
    void quarterReplacesOnlyItsPart() {
        int n = TerrainTiles.SIZE;
        TerrainTiles.Columns[] kids = {filled((x, z) -> 1), filled((x, z) -> 2), filled((x, z) -> 3), filled((x, z) -> 4)};
        TerrainTiles.Columns parent = TerrainTiles.compose(kids);
        assertTrue(parent.complete());
        // юго-восточный ребёнок перестроен: половина без данных, половина выше
        parent.putQuarter(3, filled((x, z) -> x < n / 2 ? Integer.MIN_VALUE : 40));
        assertEquals(n * n - n * n / 8, parent.filled, "у четверти половина — дыры");
        assertEquals(1, parent.height[0]);
        assertEquals(2, parent.height[n - 1]);
        assertEquals(3, parent.height[(n - 1) * n]);
        assertFalse(parent.has(n / 2 * n + n / 2), "юго-восток, западная половина — дыра");
        assertEquals(40, parent.height[n * n - 1]);
        parent.putQuarter(3, kids[3]);
        assertTrue(parent.complete(), "дыры снова закрыты");
        assertEquals(4, parent.height[n * n - 1]);
    }

    /**
     * Крупная плитка собирается из детей, только когда все четыре свежи; строится или устарел кто-то — ждёт его
     * (а не читает источник второй раз); кого-то нет или его постройка не удалась — читает источник.
     */
    @Test
    void composeOnlyFromFreshChildren() {
        long s = 1_000_000_000L, now = 1000 * s, never = Long.MAX_VALUE;
        assertEquals(TerrainTiles.Kids.ABSENT, TerrainTiles.kids(null, never, now), "у подробной детей нет");
        assertEquals(TerrainTiles.Kids.READY, TerrainTiles.kids(kids(now - s), never, now));

        TerrainTiles.Tile[] missing = kids(now - s);
        missing[2] = null;
        assertEquals(TerrainTiles.Kids.ABSENT, TerrainTiles.kids(missing, never, now), "ребёнка нет");
        TerrainTiles.Tile[] failed = kids(now - s);
        failed[1].columns = null;
        assertEquals(TerrainTiles.Kids.ABSENT, TerrainTiles.kids(failed, never, now), "постройка не удалась");

        TerrainTiles.Tile[] stale = kids(now - s);
        stale[3].stale = true;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(stale, never, now), "устарел по событию");
        TerrainTiles.Tile[] building = kids(now - s);
        building[0].buildingSince = now - s;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(building, never, now), "строится");
        TerrainTiles.Tile[] firstBuild = kids(now - s);
        firstBuild[0].columns = null;
        firstBuild[0].buildingSince = now - s;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(firstBuild, never, now), "строится впервые — ждать, не читать второй раз");

        // неполный ребёнок: свежий — годится (источник дал бы те же дыры), пора переспросить — сперва он
        TerrainTiles.Tile[] partial = kids(now - s);
        partial[2].columns = new TerrainTiles.Columns();
        partial[2].columns.set(0, new TerrainSource.Column(64, MapColor.GRASS, 0));
        assertEquals(TerrainTiles.Kids.READY, TerrainTiles.kids(partial, never, now));
        partial[2].builtAt = now - 31 * s;
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(partial, never, now), "неполный через 30 с");
        TerrainTiles.Tile[] empty = kids(now - 6 * s);
        empty[1].columns = new TerrainTiles.Columns();
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(empty, never, now), "пустой через 5 с");
        // полные — по часам источника
        assertEquals(TerrainTiles.Kids.REFRESHING, TerrainTiles.kids(kids(now - 31 * s), 30 * s, now));
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

    private interface HeightAt {
        int at(int x, int z);
    }

    /** Плитка с данными во всех клетках, высота по месту; {@link Integer#MIN_VALUE} — дыра. */
    private static TerrainTiles.Columns filled(HeightAt h) {
        TerrainTiles.Columns c = new TerrainTiles.Columns();
        int n = TerrainTiles.SIZE;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int y = h.at(x, z);
                if (y != Integer.MIN_VALUE) c.set(z * n + x, new TerrainSource.Column(y, MapColor.GRASS, 0));
            }
        }
        return c;
    }

    /** Четыре полных готовых ребёнка плитки (1, 0, 0), построенные в {@code builtAt}. */
    private static TerrainTiles.Tile[] kids(long builtAt) {
        TerrainTiles.Tile[] kids = new TerrainTiles.Tile[4];
        for (int q = 0; q < 4; q++) {
            kids[q] = new TerrainTiles.Tile(new TerrainTiles.Key(0, q & 1, q >> 1));
            kids[q].columns = filled((x, z) -> 64);
            kids[q].builtAt = builtAt;
        }
        return kids;
    }

    private static TerrainTiles.Layer layer() {
        return layer(true, Long.MAX_VALUE);
    }

    private static TerrainTiles.Layer layer(boolean offThread, long refreshNanos) {
        return new TerrainTiles.Layer("test", new TerrainSource() {
            @Override
            public boolean offThread() {
                return offThread;
            }

            @Override
            public long refreshNanos() {
                return refreshNanos;
            }

            @Nullable
            @Override
            public Reader open(ClientLevel level) {
                return null;
            }
        });
    }

    /**
     * Сменился мир посреди фоновой постройки: она останавливается перед следующей колонкой (источник больше не
     * спрашивают, поток не прерывают — DH пишет прерывание своего чтения в лог ошибкой), плитка возвращается в слой
     * без колонок, читатель закрыт.
     */
    @Test
    void cancelledBackgroundTileStopsBetweenColumns() {
        TerrainTiles.Layer layer = layer();
        int[] reads = {0};
        boolean[] closed = {false};
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Nullable
            @Override
            public TerrainSource.Column column(int x, int z) {
                reads[0]++;
                return null;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        TerrainTiles.Key key = new TerrainTiles.Key(0, 1, 1);
        TerrainTiles.buildOffThread(layer, key, 3, reader, () -> reads[0] >= 10);
        assertEquals(10, reads[0], "после отмены источник не спрашивают");
        TerrainTiles.Built b = layer.done.poll();
        assertEquals(key, b.key());
        assertEquals(3, b.generation());
        assertNull(b.columns(), "отменённая плитка — без колонок");
        assertTrue(closed[0], "читатель закрыт");
    }

    /** Фоновая плитка возвращается в слой при любом исходе, и при Error: иначе слой навсегда ждал бы её задачу. */
    @Test
    void backgroundTileReturnsEvenOnError() {
        TerrainTiles.Layer layer = layer();
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
        assertThrows(StackOverflowError.class, () -> TerrainTiles.buildOffThread(layer, key, 7, reader, () -> false), "Error не глотается");
        TerrainTiles.Built b = layer.done.poll();
        assertEquals(new TerrainTiles.Key(2, 3, -1), b.key());
        assertEquals(7, b.generation());
        assertNull(b.columns(), "без колонок — спросят снова");
        assertTrue(closed[0], "читатель закрыт");
    }

    /**
     * Рельеф заранее — только тем, кому карта нужна: выключено в настройках — никому; карту в этом мире открывали —
     * без пульта; иначе — с пультом в инвентаре. Инвентарь смотрится, только если первые два условия не решили.
     */
    @Test
    void prefetchOnlyForMapUsers() {
        int[] asked = {0};
        java.util.function.BooleanSupplier has = () -> ++asked[0] > 0;
        java.util.function.BooleanSupplier hasNot = () -> ++asked[0] < 0;
        assertFalse(TerrainTiles.prefetchWanted(false, true, has), "выключено в настройках");
        assertEquals(0, asked[0], "выключено — инвентарь не смотрится");
        assertTrue(TerrainTiles.prefetchWanted(true, true, hasNot), "карту открывали");
        assertEquals(0, asked[0], "открывали — инвентарь не смотрится");
        assertTrue(TerrainTiles.prefetchWanted(true, false, has), "пульт в инвентаре");
        assertFalse(TerrainTiles.prefetchWanted(true, false, hasNot), "без пульта и карты");
        assertEquals(2, asked[0]);
    }

    /** Без пульта и открытой карты слой чанков заранее не стоит ничего: ни источника, ни часов; начатая плитка брошена. */
    @Test
    void gameThreadPrefetchCostsNothingWhenNotWanted() {
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key key = new TerrainTiles.Key(0, 0, 0);
        layer.tiles.put(key, new TerrainTiles.Tile(key));
        layer.prefetchOrder = List.of(key);
        layer.prefetch = Set.of(key);
        layer.partial = new TerrainTiles.Partial(key, 5);
        layer.tiles.get(key).buildingSince = 5;
        TerrainTiles.prefetchInGameThread(layer, false, () -> {
            throw new AssertionError("источник открыт");
        }, () -> {
            throw new AssertionError("часы спрошены");
        }, 0, b -> {
            throw new AssertionError("плитка построена");
        });
        assertNull(layer.partial, "начатая плитка брошена");
        assertEquals(0, layer.tiles.get(key).buildingSince, "и не числится строящейся");
    }

    /**
     * Слой чанков заранее — под сроком по часам, а не по числу плиток: колонки читаются, пока часы не дошли до срока
     * (здесь каждая колонка — единица часов), начатая плитка дочитывается в следующих тиках с того же места (пока она
     * начата, у неё отметка «строится» — карта в кадре не строит её второй раз), готовая уходит в слой, и в том же
     * тике начинается следующая из ближних. Начатая, которая больше не нужна заранее (игрок ушёл), бросается.
     */
    @Test
    void gameThreadPrefetchStopsAtDeadlineAndResumes() {
        int n = TerrainTiles.SIZE * TerrainTiles.SIZE;
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key first = new TerrainTiles.Key(0, -1, 2), second = new TerrainTiles.Key(0, 0, 2), done = new TerrainTiles.Key(0, 1, 2);
        for (TerrainTiles.Key k : List.of(first, second, done)) layer.tiles.put(k, new TerrainTiles.Tile(k));
        // готовая полная плитка заранее не перечитывается
        layer.tiles.get(done).columns = filled((x, z) -> 64);
        layer.tiles.get(done).builtAt = 1;
        layer.prefetchOrder = List.of(done, first, second);
        layer.prefetch = Set.copyOf(layer.prefetchOrder);
        long[] clock = {100};
        List<int[]> reads = new ArrayList<>();
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Override
            public TerrainSource.Column column(int x, int z) {
                clock[0]++;
                reads.add(new int[] {x, z});
                return new TerrainSource.Column(70, MapColor.STONE, 0);
            }

            @Override
            public void close() {}
        };
        List<TerrainTiles.Built> built = new ArrayList<>();
        java.util.function.Consumer<TerrainTiles.Built> sink = b -> {
            built.add(b);
            TerrainTiles.Tile t = layer.tiles.get(b.key());
            t.columns = b.columns();
            t.builtAt = clock[0];
        };

        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + 1000, sink);
        assertEquals(1000, reads.size(), "ровно до срока");
        assertEquals(first, layer.partial.key, "ближняя недостающая — первой");
        assertEquals(1000, layer.partial.next);
        assertTrue(layer.tiles.get(first).buildingSince != 0, "начатая — «строится»");
        assertTrue(built.isEmpty());
        assertEquals(-64, reads.get(0)[0], "колонка (−64, 128) — угол плитки (−1, 2)");
        assertEquals(128, reads.get(0)[1]);

        // следующий тик: плитка дочитана с того же места и ушла в слой, следующая начата
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + n, sink);
        assertEquals(1000 + n, reads.size());
        assertEquals(1, built.size());
        assertEquals(first, built.get(0).key());
        assertTrue(built.get(0).columns().complete());
        assertEquals(1000, reads.get(1000)[0] + 64 + (reads.get(1000)[1] - 128) * TerrainTiles.SIZE, "продолжена с колонки 1000");
        assertEquals(second, layer.partial.key, "в том же тике — следующая");
        assertEquals(1000, layer.partial.next);
        assertEquals(0, layer.tiles.get(first).buildingSince, "готовая больше не «строится»");

        // игрок ушёл: вторая больше не нужна заранее — брошена не дочитанной, а готовые больше не читаются
        layer.prefetchOrder = List.of(done, first);
        layer.prefetch = Set.copyOf(layer.prefetchOrder);
        int total = reads.size();
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + n, sink);
        assertEquals(total, reads.size(), "не дочитывается, и читать больше нечего");
        assertNull(layer.partial);
        assertEquals(0, layer.tiles.get(second).buildingSince, "брошенная не числится строящейся");
        assertEquals(1, built.size());
    }

    /**
     * Чанк пришёл клиенту посреди постройки плитки заранее: колонки до него уже прочитаны пустыми, поэтому дочитанная
     * плитка ложится в слой с отметкой «устарела» и в следующем тике перечитывается целиком.
     */
    @Test
    void chunkArrivingMidBuildRebuildsTile() {
        int n = TerrainTiles.SIZE * TerrainTiles.SIZE;
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key key = new TerrainTiles.Key(0, 0, 0);
        layer.tiles.put(key, new TerrainTiles.Tile(key));
        layer.prefetchOrder = List.of(key);
        layer.prefetch = Set.of(key);
        long[] clock = {100};
        boolean[] chunkHere = {false};
        int[] reads = {0};
        TerrainSource.Reader reader = new TerrainSource.Reader() {
            @Nullable
            @Override
            public TerrainSource.Column column(int x, int z) {
                clock[0]++;
                reads[0]++;
                // чанк (0, 0) — блоки 0…15: до прихода у клиента его нет
                return x < 16 && z < 16 && !chunkHere[0] ? null : new TerrainSource.Column(64, MapColor.GRASS, 0);
            }

            @Override
            public void close() {}
        };
        java.util.function.Consumer<TerrainTiles.Built> sink = b -> {
            TerrainTiles.Tile t = layer.tiles.get(b.key());
            t.columns = b.columns();
            t.builtAt = clock[0];
        };
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + 10, sink);
        assertTrue(layer.tiles.get(key).buildingSince != 0, "начата");
        chunkHere[0] = true;
        TerrainTiles.arrived(layer, 0, 0);
        // ровно до конца плитки: следующую постройку этот тик не начинает
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + n - 10, sink);
        assertFalse(layer.tiles.get(key).columns.complete(), "дочитана с дырой, прочитанной до прихода чанка");
        assertTrue(layer.tiles.get(key).stale, "и отмечена устаревшей");
        int before = reads[0];
        TerrainTiles.prefetchInGameThread(layer, true, () -> reader, () -> clock[0], clock[0] + 2L * n, sink);
        assertEquals(before + n, reads[0], "перечитана целиком");
        assertTrue(layer.tiles.get(key).columns.complete(), "без дыры");
        assertFalse(layer.tiles.get(key).stale);
    }

    /**
     * Чанк пришёл клиенту: перечитываются только плитки над ним с дырами (на всех уровнях) и строящиеся; полная, ещё
     * не построенная и соседняя не трогаются.
     */
    @Test
    void arrivedChunkRefreshesOnlyTilesWithHoles() {
        TerrainTiles.Layer layer = layer(false, 30_000_000_000L);
        TerrainTiles.Key holed = new TerrainTiles.Key(0, -1, -2), full = new TerrainTiles.Key(1, -1, -1), fresh = new TerrainTiles.Key(4, -1, -1),
                beside = new TerrainTiles.Key(0, 0, -2);
        for (TerrainTiles.Key k : List.of(holed, full, fresh, beside)) layer.tiles.put(k, new TerrainTiles.Tile(k));
        layer.tiles.get(holed).columns = filled((x, z) -> x < 8 ? 64 : Integer.MIN_VALUE);
        layer.tiles.get(holed).builtAt = 1;
        layer.tiles.get(full).columns = filled((x, z) -> 64);
        layer.tiles.get(full).builtAt = 1;
        layer.tiles.get(beside).columns = new TerrainTiles.Columns();
        layer.tiles.get(beside).builtAt = 1;
        // чанк (−1, −5): плитка (−1, −2) уровня 0
        TerrainTiles.arrived(layer, -1, -5);
        assertTrue(layer.tiles.get(holed).stale, "с дырами — перечитать");
        assertFalse(layer.tiles.get(full).stale, "полная — этот чанк у неё уже был");
        assertFalse(layer.tiles.get(fresh).stale, "ещё не построена — и так в очереди");
        assertFalse(layer.tiles.get(beside).stale, "соседняя");
        // строится впервые (готовой ещё нет): колонки до прихода чанка уже прочитаны пустыми
        layer.tiles.get(fresh).buildingSince = 5;
        TerrainTiles.arrived(layer, -1, -5);
        assertTrue(layer.tiles.get(fresh).stale, "строящаяся — перечитать");
    }
}
