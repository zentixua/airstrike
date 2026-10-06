package ua.zentix.airstrike.entity.flight;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.util.Terrain;

import java.util.UUID;

/**
 * Чанки, которые снаряд держит своим тикетом ({@link ChunkTickets}): чанк, где он сейчас, и, пока его кто-то видит,
 * чанк впереди по курсу — только уже готовые ({@link Terrain#ready}): новые чанки на лету не генерируются, а
 * {@code hasChunk} верен и для чанка, который ещё грузится. Не сохраняется: после загрузки снаряд берёт тикеты заново
 * в первом тике.
 * <p>
 * Чанк впереди держит полёт в мире: тикет на нём грузит квадрат вокруг, и следующий чанк впереди уже готов — снаряд,
 * державший его всегда, тянул за собой тикающие чанки через всю карту, и залп по всей карте держал их десятки тысяч
 * (Zearth 06.10.2026: 37 тыс. чанков, TPS 7–10). Без него снаряд доходит до края своего чанка и уходит в полёт вне
 * мира ({@code StrikeProjectile.leavesTickingChunks}), а игрокам его путь идёт пакетами {@code FarFlights}.
 */
public final class ChunkHold {
    private final LongSet held = new LongOpenHashSet();

    /** Ничего не держит (снаряд только появился в мире или вернулся из выгруженного чанка). */
    public boolean isEmpty() {
        return held.isEmpty();
    }

    /**
     * Держать чанк в {@code pos} и, если {@code lookAhead}, чанк на 3 шага ({@code speed}), но не меньше 16 блоков,
     * впереди по {@code dir}.
     */
    public void update(ServerLevel level, UUID owner, Vec3 pos, Vec3 dir, double speed, boolean lookAhead) {
        long here = ChunkPos.asLong(BlockPos.containing(pos));
        long ahead = lookAhead ? ChunkPos.asLong(BlockPos.containing(pos.add(dir.scale(Math.max(16, speed * 3))))) : here;
        if (held.contains(here) && held.contains(ahead) && held.size() == (here == ahead ? 1 : 2)) return;
        LongSet want = new LongOpenHashSet();
        for (long c : new long[]{here, ahead}) {
            if (Terrain.ready(level, ChunkPos.getX(c), ChunkPos.getZ(c))) want.add(c);
        }
        for (long c : held.toLongArray()) {
            if (!want.contains(c)) {
                ChunkTickets.hold(level, owner, c, false);
                held.remove(c);
            }
        }
        for (long c : want) {
            if (held.add(c)) ChunkTickets.hold(level, owner, c, true);
        }
    }

    /** Отпустить всё. */
    public void release(ServerLevel level, UUID owner) {
        for (long c : held) ChunkTickets.hold(level, owner, c, false);
        held.clear();
    }
}
