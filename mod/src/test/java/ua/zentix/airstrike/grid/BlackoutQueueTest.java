package ua.zentix.airstrike.grid;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Очереди чанков блэкаута: чанки общей очереди, к которым подошёл игрок, переходят в очередь у игроков. */
class BlackoutQueueTest {
    @Test
    void promoteNearMovesOnlyChunksNearPlayers() throws ReflectiveOperationException {
        BlackoutWorld world = new BlackoutWorld();
        // без игроков всё идёт в общую очередь
        LongArrayList order = new LongArrayList();
        for (int x = -20; x <= 20; x += 2) {
            for (int z = -3; z <= 3; z += 3) order.add(ChunkPos.asLong(x, z));
        }
        order.add(ChunkPos.asLong(100, 100));
        for (long c : order) world.enqueue(c);
        LongArrayFIFOQueue near = field(world, "near"), ready = field(world, "ready");
        assertEquals(0, near.size());
        assertEquals(order.size(), ready.size());

        // уже в очереди ближней: место в её начале остаётся
        near.enqueue(ChunkPos.asLong(1, 1));
        LongOpenHashSet queued = field(world, "queued");
        queued.add(ChunkPos.asLong(1, 1));
        Long2IntOpenHashMap resume = field(world, "resume");
        resume.put(order.getLong(3), 77);
        resume.put(ChunkPos.asLong(100, 100), 5);
        Long2IntOpenHashMap resumeBefore = new Long2IntOpenHashMap(resume);
        LongOpenHashSet queuedBefore = new LongOpenHashSet(queued);

        LongArrayList players = field(world, "players");
        players.add(ChunkPos.asLong(0, 0));
        players.add(ChunkPos.asLong(100, 92));
        world.promoteNear();

        LongArrayList expectNear = new LongArrayList(), expectReady = new LongArrayList();
        expectNear.add(ChunkPos.asLong(1, 1));
        for (long c : order) {
            int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
            boolean close = Math.max(Math.abs(x), Math.abs(z)) <= BlackoutWorld.NEAR_CHUNKS
                    || Math.max(Math.abs(x - 100), Math.abs(z - 92)) <= BlackoutWorld.NEAR_CHUNKS;
            (close ? expectNear : expectReady).add(c);
        }
        // проверка не пустая: есть и ближние, и дальние, и игрок, до которого ровно NEAR_CHUNKS
        assertTrue(expectNear.size() > 2 && expectReady.size() > 2 && expectNear.contains(ChunkPos.asLong(100, 100)));
        assertEquals(expectNear, drain(near));
        assertEquals(expectReady, drain(ready));
        assertEquals(queuedBefore, queued);
        assertEquals(resumeBefore, resume);

        // второй раз — ничего не движется и не повторяется
        for (long c : expectNear) near.enqueue(c);
        for (long c : expectReady) ready.enqueue(c);
        world.promoteNear();
        assertEquals(expectNear, drain(near));
        assertEquals(expectReady, drain(ready));
    }

    private static LongArrayList drain(LongArrayFIFOQueue q) {
        LongArrayList out = new LongArrayList();
        while (!q.isEmpty()) out.add(q.dequeueLong());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(BlackoutWorld world, String name) throws ReflectiveOperationException {
        Field f = BlackoutWorld.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(world);
    }
}
