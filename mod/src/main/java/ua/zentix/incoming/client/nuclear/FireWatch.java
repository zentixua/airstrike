package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/**
 * Огонь, который клиент видит: блоки огня в своих чанках вокруг камеры, по {@link #PER_TICK} чанков за тик (весь круг
 * прорисовки — за секунду-другую). Секция без огня в палитре не перебирается ({@code maybeHas}), с огнём — счёт по
 * палитре. По нему звук горящего города ставит огонь, который рядом, на его место, а дальнюю модель ({@link FireField})
 * — в меру того, что горит на деле (настройки пожаров сервера клиент не знает: пожары могут быть выключены или
 * погашены).
 */
final class FireWatch {
    static final int PER_TICK = 16;
    /** Чанк → (число огня << 16) | высота огня над дном мира. */
    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    @Nullable
    private static ClientLevel level;
    private static int cursor, total;

    private FireWatch() {}

    /** Ещё часть чанков в радиусе {@code radius} чанков от чанка камеры. */
    static void tick(ClientLevel lvl, int cx, int cz, int radius) {
        if (lvl != level) reset();
        level = lvl;
        int side = 2 * radius + 1, all = side * side;
        for (int k = 0; k < Math.min(PER_TICK, all); k++) {
            int i = cursor++ % all;
            if (cursor >= all) {
                cursor = 0;
                prune(lvl);
            }
            int x = cx - radius + i % side, z = cz - radius + i / side;
            LevelChunk chunk = lvl.getChunkSource().getChunk(x, z, false);
            long key = ChunkPos.asLong(x, z);
            long was = SEEN.remove(key);
            total -= (int) (was >>> 16);
            if (chunk == null) continue;
            long now = scan(chunk);
            if (now != 0) {
                SEEN.put(key, now);
                total += (int) (now >>> 16);
            }
        }
    }

    private static long scan(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        long count = 0, ySum = 0;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !s.maybeHas(FireWatch::fire)) continue;
            int[] n = {0};
            s.getStates().count((state, c) -> {
                if (fire(state)) n[0] += c;
            });
            count += n[0];
            ySum += (long) n[0] * (i * 16 + 8);
        }
        if (count == 0) return 0;
        return Math.min(count, 0xFFFFFFL) << 16 | (ySum / count) & 0xFFFF;
    }

    private static boolean fire(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock;
    }

    /** Чанки, которых у клиента больше нет. */
    private static void prune(ClientLevel lvl) {
        for (var it = SEEN.long2LongEntrySet().iterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            if (lvl.getChunkSource().getChunk(ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey()), false) == null) {
                total -= (int) (e.getLongValue() >>> 16);
                it.remove();
            }
        }
    }

    /** Огонь по чанкам. */
    interface Visitor {
        void fire(double x, double y, double z, int count);
    }

    static void forEach(Visitor v) {
        if (level == null) return;
        int minY = level.getMinBuildHeight();
        for (Long2LongMap.Entry e : SEEN.long2LongEntrySet()) {
            long key = e.getLongKey(), val = e.getLongValue();
            v.fire(ChunkPos.getX(key) * 16 + 8, minY + (val & 0xFFFF), ChunkPos.getZ(key) * 16 + 8, (int) (val >>> 16));
        }
    }

    static int total() {
        return total;
    }

    static void reset() {
        SEEN.clear();
        cursor = total = 0;
        level = null;
    }
}
