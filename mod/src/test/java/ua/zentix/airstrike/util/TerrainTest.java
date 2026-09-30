package ua.zentix.airstrike.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainTest {
    /** Колонка без Minecraft: что знает мир и сколько раз спросили генератор. */
    private static final class FakeColumn implements Terrain.Column {
        final boolean ready, ceiling, generator;
        final int chunk, sea, base, bottom, top;
        int generatorCalls;

        FakeColumn(boolean ready, int chunk, boolean ceiling, boolean generator, int sea, int base) {
            this(ready, chunk, ceiling, generator, sea, base, -64, 320);
        }

        FakeColumn(boolean ready, int chunk, boolean ceiling, boolean generator, int sea, int base, int bottom, int top) {
            this.ready = ready;
            this.chunk = chunk;
            this.ceiling = ceiling;
            this.generator = generator;
            this.sea = sea;
            this.base = base;
            this.bottom = bottom;
            this.top = top;
        }

        @Override public boolean ready() { return ready; }
        @Override public int chunkHeight() { return chunk; }
        @Override public boolean ceiling() { return ceiling; }
        @Override public boolean hasGenerator() { return generator; }
        @Override public int sea() { return sea; }
        @Override public int generatorBase() { generatorCalls++; return base; }
        @Override public boolean outside(int y) { return y < bottom || y >= top; }
        @Override public int bottom() { return bottom; }
    }

    private static Terrain.Surface surface(int y, Terrain.Source source) {
        return new Terrain.Surface(y, source);
    }

    @Test
    void readyChunkWinsOverEverySource() {
        FakeColumn c = new FakeColumn(true, 107, false, true, 63, 70);
        assertEquals(surface(107, Terrain.Source.CHUNK), Terrain.choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(90))));
        assertEquals(surface(107, Terrain.Source.CHUNK), Terrain.choose(c, Terrain.Allowed.CHUNK));
        assertEquals(surface(107, Terrain.Source.CHUNK), Terrain.choose(c, Terrain.Allowed.FLIGHT));
        assertEquals(0, c.generatorCalls, "генератор при готовом чанке не спрашивают");
    }

    @Test
    void clientMapBeforeGenerator() {
        // город с карты мира: генератор — улица под крышами (Greenfield 30.09.2026: 63 под крышей на 107)
        FakeColumn c = new FakeColumn(false, 0, false, true, 63, 63);
        assertEquals(surface(107, Terrain.Source.CLIENT_MAP), Terrain.choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(107))));
        assertEquals(0, c.generatorCalls);
        // верх, чей блок вне высот мира, не в счёт
        assertEquals(surface(63, Terrain.Source.GENERATOR), Terrain.choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(-64))));
        assertEquals(surface(63, Terrain.Source.GENERATOR), Terrain.choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(321))));
        assertEquals(surface(320, Terrain.Source.CLIENT_MAP), Terrain.choose(c, Terrain.Allowed.ORDER.withMap(Optional.of(320))));
    }

    @Test
    void generatorNotBelowSea() {
        // мир 1.17, поднятый до 1.21: генератор давал дно мира (Newisle 30.09.2026, «по 83 -64 -370»)
        assertEquals(surface(63, Terrain.Source.GENERATOR), Terrain.choose(new FakeColumn(false, 0, false, true, 63, -64), Terrain.Allowed.ORDER));
        assertEquals(surface(107, Terrain.Source.GENERATOR), Terrain.choose(new FakeColumn(false, 0, false, true, 63, 107), Terrain.Allowed.ORDER));
        // плоский мир: море −63, земля −60
        assertEquals(surface(-60, Terrain.Source.GENERATOR), Terrain.choose(new FakeColumn(false, 0, false, true, -63, -60), Terrain.Allowed.ORDER));
    }

    @Test
    void tickSourcesNeverAskGenerator() {
        FakeColumn c = new FakeColumn(false, 0, false, true, -63, -60);
        assertEquals(surface(-63, Terrain.Source.SEA), Terrain.choose(c, Terrain.Allowed.FLIGHT));
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), Terrain.choose(c, Terrain.Allowed.CHUNK));
        assertEquals(0, c.generatorCalls, "в тике генератор (≈3 мс) не спрашивают");
    }

    @Test
    void ceilingIsNotGroundForFlight() {
        // Незер: карта высот готового чанка — верх потолка
        FakeColumn nether = new FakeColumn(true, 128, true, true, 32, 40, 0, 256);
        assertEquals(surface(128, Terrain.Source.CEILING), Terrain.choose(nether, Terrain.Allowed.CHUNK));
        assertEquals(surface(32, Terrain.Source.SEA), Terrain.choose(nether, Terrain.Allowed.FLIGHT));
        assertEquals(surface(128, Terrain.Source.CEILING), Terrain.choose(nether, Terrain.Allowed.ORDER));
    }

    @Test
    void clientHasNoGenerator() {
        FakeColumn client = new FakeColumn(false, 0, false, false, 63, 70);
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), Terrain.choose(client, Terrain.Allowed.ORDER));
        assertEquals(surface(-64, Terrain.Source.UNKNOWN), Terrain.choose(client, Terrain.Allowed.FLIGHT));
        assertEquals(surface(90, Terrain.Source.CLIENT_MAP), Terrain.choose(client, Terrain.Allowed.CHUNK.withMap(Optional.of(90))));
    }

    @Test
    void clientMapNeedsHint() {
        assertThrows(IllegalArgumentException.class, () -> new Terrain.Allowed(Set.of(Terrain.Source.CLIENT_MAP), Optional.empty()));
        assertEquals(Terrain.Allowed.ORDER, Terrain.Allowed.ORDER.withMap(Optional.empty()));
    }

    @Test
    void allReadyReachesTheEnd() {
        assertEquals(1, Terrain.readyFraction(1, 1, 900, -300, (x, z) -> true));
    }

    @Test
    void stopsJustBeforeFirstUnreadyChunk() {
        // вдоль +x: чанки 0..3 готовы, 4 (x = 64..79) — нет
        double t = Terrain.readyFraction(8, 8, 108, 8, (x, z) -> x < 4);
        double x = 8 + t * 100;
        assertTrue(x < 64 && x > 63.9, "обрезано у границы: " + x);
    }

    @Test
    void unreadyStartGivesZero() {
        assertEquals(0, Terrain.readyFraction(8, 8, 100, 100, (x, z) -> false));
    }

    @Test
    void visitsEveryColumnTheSegmentCrosses() {
        Set<Long> seen = new HashSet<>();
        Terrain.readyFraction(-5.5, 3.25, 70.5, 47.75, (x, z) -> {
            seen.add(((long) x << 32) | (z & 0xFFFFFFFFL));
            return true;
        });
        // проверка по мелким шагам: каждая колонка, через которую проходит отрезок, была спрошена
        for (int i = 0; i <= 10000; i++) {
            double k = i / 10000.0;
            int cx = (int) Math.floor((-5.5 + k * 76) / 16), cz = (int) Math.floor((3.25 + k * 44.5) / 16);
            assertTrue(seen.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL)), "пропущена колонка " + cx + "," + cz);
        }
    }

    @Test
    void negativeDirectionStopsBeforeBoundary() {
        // вдоль −z из чанка z = 0: чанк z = −1 не готов, граница z = 0
        double t = Terrain.readyFraction(8, 12, 8, -40, (x, z) -> z >= 0);
        double z = 12 - t * 52;
        assertTrue(z >= 0 && z < 0.1, "обрезано у границы: " + z);
    }
}
