package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.world.phys.Vec3;

import java.util.HexFormat;

/**
 * Хеш полёта по тикам (FNV-1a, 64 бита): тик от пуска, какой снаряд сценария (по порядку появления), фаза, вне мира
 * ли он, место от середины площадки с точностью до 0,001 блока. Любой сдвиг траектории, фазы или ухода из мира
 * меняет хеш; эталон хранит его вместо тысяч точек.
 */
final class FlightTrace {
    private static final long OFFSET = 0xcbf29ce484222325L, PRIME = 0x100000001b3L;
    private long hash = OFFSET;

    void add(int tick, int order, int phase, boolean virtual, Vec3 rel) {
        mix(tick);
        mix(order);
        mix(phase);
        mix(virtual ? 1 : 0);
        mix(Math.round(rel.x * 1000));
        mix(Math.round(rel.y * 1000));
        mix(Math.round(rel.z * 1000));
    }

    private void mix(long v) {
        for (int i = 0; i < 8; i++) {
            hash ^= (v >>> (i * 8)) & 0xff;
            hash *= PRIME;
        }
    }

    String hex() {
        return HexFormat.of().toHexDigits(hash);
    }
}
