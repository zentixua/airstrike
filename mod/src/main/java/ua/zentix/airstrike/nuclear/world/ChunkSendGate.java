package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Чанк, до которого волна уже дошла, а руины ещё не встали (загружен после подрыва: за волной или игроком), игроку
 * не уходит целым: пока идёт отбор чанков для игрока ({@code PlayerChunkSender.sendNextChunks}), такие чанки убраны из
 * его очереди и возвращаются в неё сразу после ({@code mixin/net/PlayerChunkSenderMixin}). Держится не дольше
 * {@code ScarQueue.WITHHOLD_LIMIT} тиков от первой просьбы чанка после волны; ушедшие до руин считает сводка подрыва.
 */
public final class ChunkSendGate {
    /** Метка миксина: {@code PlayerChunkSender} с ним (проверка, что миксин встал). */
    public interface Gated {}

    private ChunkSendGate() {}

    /**
     * Убрать из очереди игрока чанки, которые нельзя отдавать (поток сервера).
     *
     * @return убранные (вернуть в очередь после отбора); null — ничего не убрано
     */
    @Nullable
    public static LongArrayList withhold(ServerLevel level, LongSet pending) {
        if (pending.isEmpty()) return null;
        NuclearWorld world = NuclearWorld.get(level);
        if (!world.mayWithhold()) return null;
        LongArrayList out = null;
        for (LongIterator it = pending.iterator(); it.hasNext(); ) {
            long c = it.nextLong();
            if (!world.withholds(level, c)) continue;
            it.remove();
            if (out == null) out = new LongArrayList();
            out.add(c);
        }
        return out;
    }
}
