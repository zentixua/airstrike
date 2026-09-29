package ua.zentix.airstrike.strike;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualFlightsTest {
    /**
     * Стенд нагрузки 29.09.2026: ракета РСЗО у цели (494 221 313, курс на 517 328) стояла 400 тиков и пропала по сроку
     * жизни. Её чанк (30, 19) и точка в 16 блоках впереди (31, 20) тикали, а чанк между ними на углу (31, 19) — нет:
     * снаряд возвращался в мир, первым же шагом уходил из тикающих чанков обратно вне мира — и так каждый тик.
     */
    @Test
    void cornerChunkOnTheWayKeepsProjectileOutOfWorld() {
        Vec3 pos = new Vec3(494.5, 221, 313.5);
        Vec3 forward = new Vec3(23, -12, 15).normalize();
        assertFalse(VirtualFlights.clearAhead(pos, forward, 3, (x, z) -> !(x == 31 && z == 19)));
    }

    @Test
    void wholeWayLiveLetsProjectileBack() {
        Vec3 pos = new Vec3(494.5, 221, 313.5);
        Vec3 forward = new Vec3(23, -12, 15).normalize();
        assertTrue(VirtualFlights.clearAhead(pos, forward, 3, (x, z) -> x >= 30 && x <= 31 && z >= 19 && z <= 20));
    }

    @Test
    void unliveChunkBeyondTheCheckedStretchDoesNotMatter() {
        // по +x: 16 блоков (или три шага) впереди — чанки 0 и 1; чанк 2 дальше не нужен
        assertTrue(VirtualFlights.clearAhead(new Vec3(8, 70, 8), new Vec3(1, 0, 0), 2, (x, z) -> x < 2));
        assertFalse(VirtualFlights.clearAhead(new Vec3(8, 70, 8), new Vec3(1, 0, 0), 9, (x, z) -> x < 2));
    }

    @Test
    void verticalFlightChecksOwnChunk() {
        assertTrue(VirtualFlights.clearAhead(new Vec3(8, 70, 8), new Vec3(0, -1, 0), 4, (x, z) -> x == 0 && z == 0));
    }
}
