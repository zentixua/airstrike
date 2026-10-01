package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Рельеф из чанков, которые есть у клиента (дальность прорисовки): как ванильная карта — верхний блок по карте
 * высот {@code MOTION_BLOCKING} (другие клиенту не приходят) и глубина воды. Мир клиента читается только в потоке игры.
 */
final class LoadedChunksTerrain implements TerrainSource {
    /** Глубже этого вода на карте одинаково тёмная. */
    private static final int MAX_WATER_DEPTH = 16;
    /** Сколько блоков вниз искать блок с цветом под прозрачными (стекло, свет). */
    private static final int MAX_COLORLESS = 8;

    /**
     * Чанки ({@link ChunkPos#asLong}), пришедшие клиенту с прошлого тика, и мир, в который они пришли: плитка, построенная
     * заранее, пока их не было, — с дырами, и её надо перечитать, а не ждать часов неполной плитки (после входа в мир
     * чанки приходят секундами). Пишет и читает поток игры.
     */
    private static final Set<Long> arrived = new HashSet<>();
    @Nullable
    private static ClientLevel arrivedIn;

    /** Чанк пришёл клиенту (событие NeoForge из {@code ClientChunkCache.replaceWithPacketData}). */
    static void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ClientLevel level)) return;
        if (level != arrivedIn) {
            arrived.clear();
            arrivedIn = level;
        }
        arrived.add(e.getChunk().getPos().toLong());
    }

    /** Выход из мира: не держать его. */
    static void clearArrivals() {
        arrived.clear();
        arrivedIn = null;
    }

    @Override
    public boolean offThread() {
        return false;
    }

    /** Чанки клиента меняются на глазах (взрывы): видимая плитка перечитывается раз в 30 с. */
    @Override
    public long refreshNanos() {
        return 30_000_000_000L;
    }

    @Override
    public void changes(ClientLevel level, ChunkSink sink) {
        if (arrived.isEmpty()) return;
        if (level == arrivedIn) {
            for (long c : arrived) sink.changed(ChunkPos.getX(c), ChunkPos.getZ(c));
        }
        arrived.clear();
    }

    @Override
    public boolean arrivals() {
        return true;
    }

    @Override
    public Reader open(ClientLevel level) {
        return new Reader() {
            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            @Nullable
            @Override
            public Column column(int x, int z) {
                if (!level.hasChunk(x >> 4, z >> 4)) return null;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int bottom = Math.max(level.getMinBuildHeight(), top - MAX_COLORLESS);
                for (int y = top - 1; y >= bottom; y--) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    MapColor color = state.getMapColor(level, pos);
                    if (color == MapColor.NONE) continue;
                    return new Column(y + 1, color, state.getFluidState().is(FluidTags.WATER) ? waterDepth(level, x, y, z) : 0);
                }
                return null;
            }

            private int waterDepth(ClientLevel level, int x, int surface, int z) {
                int depth = 1;
                while (depth < MAX_WATER_DEPTH && level.getFluidState(pos.set(x, surface - depth, z)).is(FluidTags.WATER)) depth++;
                return depth;
            }

            @Override
            public void close() {}
        };
    }
}
